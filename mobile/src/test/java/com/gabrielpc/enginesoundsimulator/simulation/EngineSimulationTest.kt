package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

class EngineSimulationTest {
    @Test
    fun engineStartsAtZeroRpmUntilIgnition() {
        val simulation = EngineSimulation()
        assertEquals(0.0, simulation.state.rpm, 0.0)
        assertEquals(EngineIgnitionState.OFF, simulation.ignition)
        assertEquals(1, simulation.state.gear)
        assertFalse(simulation.state.isShifting)
        assertEquals(0L, simulation.state.shiftSerial)
    }

    @Test
    fun ignitionStartRevvesThenSettlesAtIdle() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        var peakRpm = 0.0
        repeat(500) {
            val state = simulation.update(DriverInput(), STEP)
            peakRpm = maxOf(peakRpm, state.rpm)
        }
        assertTrue("start sequence must blip toward 5000 rpm", peakRpm > simulation.profile.idleRpm * 2.5)
        assertTrue(peakRpm >= ENGINE_START_PEAK_RPM * 0.95)
        assertEquals(EngineIgnitionState.RUNNING, simulation.ignition)
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 1.0)
    }

    @Test
    fun quantizedSpeedEstimatorMakesIntegerStepsContinuousInBothDirections() {
        val estimator = QuantizedSpeedEstimator()
        estimator.update(20.0, STEP, 0.12)
        val rising = mutableListOf<Double>()
        repeat(80) { rising += estimator.update(21.0, STEP, 0.12) }
        assertTrue("one integer step must not reach the tach in one frame", rising.first() in 20.0..20.20)
        assertTrue("speed must move continuously upward", rising.zipWithNext().all { it.second >= it.first })
        assertTrue(rising.last() > 20.85)

        val falling = mutableListOf<Double>()
        repeat(80) { falling += estimator.update(20.0, STEP, 0.12) }
        assertTrue("one integer step must not reach the tach in one frame", falling.first() > 20.75)
        assertTrue("speed must move continuously downward", falling.zipWithNext().all { it.second <= it.first })
        assertTrue("falling estimate=${falling.last()}", falling.last() < 20.20)
    }

    @Test
    fun simulatorReportsWholeKmhWhileDrivingAudioFromContinuousEstimate() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        var observedInterpolatedFrame = false
        repeat((1.5 / STEP).toInt()) {
            val state = simulation.update(
                DriverInput(throttle = 0.55, simulateCoastRegen = true),
                STEP,
            )
            assertEquals(state.rawSpeedKmh.roundToInt().toDouble(), state.rawSpeedKmh, 0.0)
            if (kotlin.math.abs(state.speedKmh - state.rawSpeedKmh) > 0.01) {
                observedInterpolatedFrame = true
            }
        }
        assertTrue("SIM must reconstruct motion between whole-km/h reports", observedInterpolatedFrame)
    }

    @Test
    fun throttleDoesNotIncreaseSpeedWhileEngineOff() {
        val simulation = EngineSimulation()
        repeat(400) {
            simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), 0.005)
        }
        assertTrue(simulation.state.speedKmh < 1.0)
    }

    @Test
    fun engageAtIdleSkipsStarterRevSequence() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        assertEquals(EngineIgnitionState.RUNNING, simulation.ignition)
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 1.0)
    }

    @Test
    fun driveRpmIsDeterminedByRoadSpeedRatherThanThrottleForce() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE
        val light = EngineSimulation(profile)
        val heavy = EngineSimulation(profile)
        val lightState = light.runForExternal(2.0, 48.0, 0.15)
        val heavyState = heavy.runForExternal(2.0, 48.0, 0.85)
        assertEquals(lightState.gear, heavyState.gear)
        assertEquals(lightState.speedKmh, heavyState.speedKmh, 0.01)
        assertEquals(lightState.rpm, heavyState.rpm, 1.0)
        assertTrue(lightState.rpm > profile.idleRpm + 2_000.0)
    }

    @Test
    fun ratioBasedGearboxPreservesNormalProgression() {
        val simulation = EngineSimulation()
        val state = simulation.followIntegerSpeedRamp(0.0, 80.0, 10.0, 1.0)
        assertTrue("normal ratios should use fewer than five shifts below 80 km/h: $state", state.gear < 6)
        assertTrue(state.gear >= 2)
    }

    @Test
    fun soundGearsDivideTopSpeedIntoEqualBands() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE
        val expectedBandKmh = profile.topSpeedKmh / profile.gearRatios.size
        profile.gearRatios.indices.forEach { gearIndex ->
            assertEquals(
                expectedBandKmh * (gearIndex + 1),
                evenlySpacedUpshiftSpeedKmh(profile, gearIndex),
                0.0001,
            )
            val boundaryWheelRpm = (evenlySpacedUpshiftSpeedKmh(profile, gearIndex) / 3.6) /
                (2.0 * Math.PI * profile.wheelRadiusMeters) * 60.0
            val boundaryRpm = profile.idleRpm + boundaryWheelRpm *
                evenlySpacedGearRatio(profile, gearIndex)
            assertEquals(profile.upshiftRpm, boundaryRpm, 0.001)
        }
        assertEquals(190.0 / 7.0, expectedBandKmh, 0.0001)
    }

    @Test
    fun downshiftUsesTheBoundaryThatSelectedTheGear() {
        val simulation = EngineSimulation()
        val launched = simulation.followIntegerSpeedRamp(0.0, 65.0, 5.0, 1.0)
        assertEquals("65 km/h should be in the third equal-width band", 3, launched.gear)

        val lifted = simulation.followIntegerSpeedRamp(65.0, 45.0, 3.0, 0.0)
        assertEquals("the remembered boundary must produce one stable downshift", 2, lifted.gear)
    }

    @Test
    fun integerNoiseNearThresholdDoesNotCauseShiftHunting() {
        val simulation = EngineSimulation()
        simulation.followIntegerSpeedRamp(0.0, 65.0, 5.0, 0.45)
        val serialBeforeNoise = simulation.state.shiftSerial
        repeat(1_000) { frame ->
            val raw = if ((frame / 20) % 2 == 0) 58.0 else 59.0
            simulation.update(DriverInput(throttle = 0.45, externalSpeedKmh = raw), STEP)
        }
        assertTrue(simulation.state.gear >= 2)
        assertEquals("4 km/h hysteresis must prevent a shift loop", serialBeforeNoise, simulation.state.shiftSerial)
    }

    @Test
    fun simulatorUsesPhysicalAccelerationAndAddsRegenOnLiftOff() {
        val withRegen = EngineSimulation()
        val dragOnly = EngineSimulation()
        val launchedWithRegen = withRegen.runFor(2.0, throttle = 1.0, sim = true)
        val launchedDragOnly = dragOnly.runFor(2.0, throttle = 1.0)
        assertEquals(launchedDragOnly.speedKmh, launchedWithRegen.speedKmh, 0.01)
        val regenCoast = withRegen.runFor(0.8, sim = true)
        val dragCoast = dragOnly.runFor(0.8)
        assertTrue(regenCoast.speedKmh < dragCoast.speedKmh - 6.0)
        assertTrue("lower coast speed must produce lower coupled RPM", regenCoast.rpm < dragCoast.rpm)
    }

    @Test
    fun fullThrottleAccelerationTargetsClaimedZeroToHundredWindow() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        var elapsedSeconds = 0.0
        var state = simulation.state
        while (state.speedKmh < 100.0 && elapsedSeconds < 6.0) {
            state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
            elapsedSeconds += STEP
        }
        assertTrue("0-100 km/h should remain near 3.8 seconds: $elapsedSeconds", elapsedSeconds in 3.4..4.2)
    }

    @Test
    fun serviceBrakeReducesRoadSpeedFasterThanCoasting() {
        val coast = EngineSimulation()
        val brake = EngineSimulation()
        coast.runFor(2.0, throttle = 0.75, sim = true)
        brake.runFor(2.0, throttle = 0.75, sim = true)
        val coastState = coast.runFor(0.40, sim = true)
        val brakeState = brake.runFor(0.40, brake = 1.0, sim = true)
        assertTrue(brakeState.speedKmh < coastState.speedKmh - 10.0)
    }

    @Test
    fun parkKeepsSimulatorSpeedAtZero() {
        val simulation = EngineSimulation()
        val state = simulation.runFor(0.8, throttle = 1.0, sim = true, position = TransmissionPosition.PARK)
        assertEquals(0.0, state.speedKmh, 0.001)
    }

    private fun EngineSimulation.ensureIgnitionRunning() {
        if (!isIgnitionActive()) {
            startIgnition()
        }
        repeat(500) {
            if (ignition == EngineIgnitionState.RUNNING) {
                return
            }
            update(DriverInput(), STEP)
        }
    }

    private fun EngineSimulation.runFor(
        seconds: Double,
        throttle: Double = 0.0,
        brake: Double = 0.0,
        sim: Boolean = false,
        position: TransmissionPosition = TransmissionPosition.DRIVE,
    ): DrivetrainState {
        ensureIgnitionRunning()
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle, brake, simulateCoastRegen = sim, transmissionPosition = position), STEP)
        }
        return result
    }

    private fun EngineSimulation.runForExternal(seconds: Double, speedKmh: Double, throttle: Double): DrivetrainState {
        ensureIgnitionRunning()
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle = throttle, externalSpeedKmh = speedKmh), STEP)
        }
        return result
    }

    private fun EngineSimulation.followIntegerSpeedRamp(
        fromKmh: Double,
        toKmh: Double,
        seconds: Double,
        throttle: Double,
    ): DrivetrainState {
        ensureIgnitionRunning()
        var result = state
        val frames = (seconds / STEP).toInt()
        repeat(frames) { frame ->
            val fraction = frame.toDouble() / (frames - 1).coerceAtLeast(1)
            val continuous = fromKmh + (toKmh - fromKmh) * fraction
            result = update(DriverInput(throttle = throttle, externalSpeedKmh = floor(continuous)), STEP)
        }
        return result
    }

    private companion object { const val STEP = 1.0 / 200.0 }
}
