package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class EngineSimulationTest {
    @Test
    fun sampleProfileStartsAtItsOwnIdleInFirstGear() {
        val simulation = EngineSimulation()
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 0.0)
        assertEquals(1, simulation.state.gear)
        assertFalse(simulation.state.isShifting)
        assertEquals(0L, simulation.state.shiftSerial)
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
    fun driveRpmIsDeterminedByRoadSpeedRatherThanThrottleForce() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.ORIGINAL)
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
    fun shortStrategyCompletesAtLeastFiveShiftsByEightyKmh() {
        val simulation = EngineSimulation(EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.SHORT))
        val state = simulation.followIntegerSpeedRamp(0.0, 80.0, 10.0, 0.45)
        assertTrue("SHORT should reach sixth gear by 80 km/h: $state", state.gear >= 6)
        assertTrue(state.shiftSerial >= 5L)
    }

    @Test
    fun originalStrategyPreservesNormalLongGearProgression() {
        val simulation = EngineSimulation(EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.ORIGINAL))
        val state = simulation.followIntegerSpeedRamp(0.0, 80.0, 10.0, 1.0)
        assertTrue("normal ratios should use fewer than five shifts below 80 km/h: $state", state.gear < 6)
        assertTrue(state.gear >= 2)
    }

    @Test
    fun hybridUsesShortGearsAtModerateThrottleAndNormalGearsAtFullThrottle() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.HYBRID)
        val moderate = EngineSimulation(profile)
        val hard = EngineSimulation(profile)
        val moderateState = moderate.followIntegerSpeedRamp(0.0, 80.0, 10.0, 0.45)
        val hardState = hard.followIntegerSpeedRamp(0.0, 80.0, 10.0, 1.0)
        assertTrue("moderate demand should favor the short schedule: $moderateState", moderateState.gear >= 6)
        assertTrue("hard demand should preserve the long schedule: $hardState", hardState.gear < 6)
        assertTrue(moderateState.gear > hardState.gear)
    }

    @Test
    fun hybridDownshiftUsesTheBoundaryThatActuallySelectedTheGear() {
        val simulation = EngineSimulation(EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.HYBRID))
        val launched = simulation.followIntegerSpeedRamp(0.0, 65.0, 5.0, 1.0)
        assertEquals("full throttle should enter second on the normal schedule", 2, launched.gear)

        val lifted = simulation.followIntegerSpeedRamp(65.0, 54.0, 2.0, 0.0)
        assertEquals(
            "pedal release must not replace the remembered long-gear boundary with the short schedule",
            1,
            lifted.gear,
        )
    }

    @Test
    fun integerNoiseNearThresholdDoesNotCauseShiftHunting() {
        val simulation = EngineSimulation(EngineProfile.SAMPLE_BANK_ENGINE.copy(shiftStrategy = ShiftStrategy.SHORT))
        simulation.followIntegerSpeedRamp(0.0, 15.0, 3.0, 0.45)
        val serialBeforeNoise = simulation.state.shiftSerial
        repeat(1_000) { frame ->
            val raw = if ((frame / 20) % 2 == 0) 12.0 else 13.0
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
        assertTrue(regenCoast.rpm < launchedWithRegen.rpm - 500.0)
    }

    @Test
    fun fullThrottleAccelerationTargetsClaimedZeroToHundredWindow() {
        val simulation = EngineSimulation()
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

    private fun EngineSimulation.runFor(
        seconds: Double,
        throttle: Double = 0.0,
        brake: Double = 0.0,
        sim: Boolean = false,
        position: TransmissionPosition = TransmissionPosition.DRIVE,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle, brake, simulateCoastRegen = sim, transmissionPosition = position), STEP)
        }
        return result
    }

    private fun EngineSimulation.runForExternal(seconds: Double, speedKmh: Double, throttle: Double): DrivetrainState {
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
