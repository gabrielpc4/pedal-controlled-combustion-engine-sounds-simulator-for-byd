package com.gabrielpc.bydmotorsound.simulation

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSimulationTest {
    @Test
    fun apexV10DefaultCalibrationIsInternallyOrdered() {
        val profile = EngineProfile.APEX_V10

        assertEquals(8_600.0, profile.redlineRpm, 0.0)
        assertEquals(8_850.0, profile.limiterRpm, 0.0)
        assertEquals(8_250.0, profile.upshiftRpm, 0.0)
        assertTrue(profile.upshiftRpm < profile.redlineRpm)
        assertTrue(profile.redlineRpm < profile.limiterRpm)
    }

    @Test
    fun fullThrottleSpinsEngineProgressivelyInsteadOfJumpingToPedalMappedRpm() {
        val simulation = EngineSimulation()
        val initial = simulation.state
        val afterHalfSecond = simulation.runFor(0.5, throttle = 1.0)

        assertTrue(
            "rpm should rise through engine inertia: $afterHalfSecond",
            afterHalfSecond.rpm > initial.rpm + 250.0,
        )
        assertTrue(afterHalfSecond.rpm < simulation.profile.redlineRpm)
        assertTrue(afterHalfSecond.speedKmh > 0.0)
    }

    @Test
    fun fullThrottleLaunchDoesNotBogAsClutchEngages() {
        val simulation = EngineSimulation()
        var peakRpm = simulation.state.rpm
        var largestDrop = 0.0
        var state = simulation.state

        repeat((3.0 / STEP).toInt()) {
            state = simulation.update(DriverInput(throttle = 1.0), STEP)
            if (state.gear == 1 && !state.isShifting) {
                peakRpm = maxOf(peakRpm, state.rpm)
                largestDrop = maxOf(largestDrop, peakRpm - state.rpm)
            }
        }

        assertTrue(
            "first-gear full-throttle launch lost $largestDrop RPM after reaching $peakRpm: $state",
            largestDrop < 30.0,
        )
    }

    @Test
    fun automaticShiftStartsAtTheShiftPointDropsRpmAndHonorsCompletedGearDwell() {
        val simulation = EngineSimulation()
        var firstShiftStart: DrivetrainState? = null
        var firstShiftCompletion: DrivetrainState? = null
        var firstCompletionTime: Double? = null
        var secondShiftStartTime: Double? = null
        var previousState = simulation.state
        var elapsed = 0.0

        while (elapsed < 40.0 && secondShiftStartTime == null) {
            val state = simulation.update(DriverInput(throttle = 1.0), STEP)
            elapsed += STEP
            if (firstShiftStart == null && state.shiftSerial == 1L) {
                firstShiftStart = state
            }
            if (
                firstShiftStart != null &&
                firstShiftCompletion == null &&
                previousState.isShifting &&
                !state.isShifting
            ) {
                firstShiftCompletion = state
                firstCompletionTime = elapsed
            }
            if (state.shiftSerial >= 2L) {
                secondShiftStartTime = elapsed
            }
            previousState = state
        }

        assertNotNull("expected a first automatic upshift", firstShiftStart)
        assertNotNull("expected the first shift to complete", firstShiftCompletion)
        assertNotNull("expected a second automatic upshift", secondShiftStartTime)

        val start = requireNotNull(firstShiftStart)
        val completion = requireNotNull(firstShiftCompletion)
        val completedAt = requireNotNull(firstCompletionTime)
        val secondStartedAt = requireNotNull(secondShiftStartTime)
        assertEquals(ShiftDirection.UP, start.shiftDirection)
        assertTrue(
            "shift began too early at ${start.rpm} rpm",
            start.rpm >= simulation.profile.upshiftRpm - 100.0,
        )
        assertTrue(
            "normal shift must happen before the limiter, start=$start",
            start.rpm < simulation.profile.limiterRpm - 250.0 && !start.limiterActive,
        )
        assertTrue(
            "the taller gear should produce a visible rpm drop: start=$start completion=$completion",
            completion.rpm < start.rpm - 900.0,
        )
        assertTrue(
            "gear dwell was measured from shift start instead of completion",
            secondStartedAt - completedAt >= MIN_EXPECTED_COMPLETED_DWELL_SECONDS,
        )
    }

    @Test
    fun joiningLiveSpeedSelectsSafeGearAndEmergencyUpshiftsWithoutThrottle() {
        val simulation = EngineSimulation()
        val joined = simulation.update(DriverInput(externalSpeedKmh = 100.0), STEP)

        assertTrue("live join should not start in first gear: $joined", joined.gear >= 4)
        assertTrue(joined.rpm < simulation.profile.redlineRpm * 0.95)
        assertFalse(joined.limiterActive)
        assertEquals("first live sample has no acceleration history", 0.0, joined.accelerationMps2, 0.0)

        simulation.reset()
        simulation.update(DriverInput(externalSpeedKmh = 30.0), STEP)
        var recovered = simulation.state
        repeat((2.0 / STEP).toInt()) {
            recovered = simulation.update(DriverInput(externalSpeedKmh = 200.0), STEP)
        }

        assertTrue("unsafe projected rpm should force sequential upshifts: $recovered", recovered.gear >= 4)
        assertFalse("gear recovery should leave the limiter", recovered.limiterActive)
        assertTrue(recovered.rpm < simulation.profile.redlineRpm)
    }

    @Test
    fun accelerationMatchesVirtualSpeedDeltaAndLiveSpeedUsesBoundedSignedDerivative() {
        val virtual = EngineSimulation()
        virtual.runFor(0.5, throttle = 1.0)
        val speedBeforeMps = virtual.state.speedKmh / 3.6
        val next = virtual.update(DriverInput(throttle = 1.0), STEP)
        val expectedAcceleration = (next.speedKmh / 3.6 - speedBeforeMps) / STEP
        assertEquals(expectedAcceleration, next.accelerationMps2, 1e-9)

        val stopped = EngineSimulation()
        val brakeAtRest = stopped.runFor(0.5, brake = 1.0)
        assertEquals("braking at rest is not sustained negative acceleration", 0.0, brakeAtRest.accelerationMps2, 0.0)

        val live = EngineSimulation()
        live.update(DriverInput(externalSpeedKmh = 60.0), STEP)
        live.runForExternal(0.4, speedKmh = 60.0)
        val accelerating = live.update(DriverInput(externalSpeedKmh = 80.0), STEP)
        assertTrue("increasing live speed should report positive acceleration", accelerating.accelerationMps2 > 0.0)
        assertTrue(abs(accelerating.accelerationMps2) <= MAX_EXPECTED_REPORTED_ACCELERATION)

        live.runForExternal(0.5, speedKmh = 80.0)
        val decelerating = live.update(DriverInput(externalSpeedKmh = 55.0), STEP)
        assertTrue("decreasing live speed should report negative acceleration", decelerating.accelerationMps2 < 0.0)
        assertTrue(abs(decelerating.accelerationMps2) <= MAX_EXPECTED_REPORTED_ACCELERATION)
    }

    @Test
    fun limiterCutUsesReleaseHysteresis() {
        val limiterProfile = EngineProfile.APEX_V10.copy(
            redlineRpm = 3_000.0,
            limiterRpm = 3_200.0,
            upshiftRpm = 2_900.0,
            downshiftRpm = 1_100.0,
            gearRatios = doubleArrayOf(3.14),
        )
        val simulation = EngineSimulation(limiterProfile)
        var state = simulation.state
        var elapsed = 0.0
        while (!state.limiterActive && elapsed < 12.0) {
            state = simulation.update(DriverInput(throttle = 1.0), STEP)
            elapsed += STEP
        }
        assertTrue("single-gear profile should reach its limiter: $state", state.limiterActive)

        var heldBelowTrigger = false
        var releasedRpm: Double? = null
        elapsed = 0.0
        while (state.limiterActive && elapsed < 3.0) {
            state = simulation.update(DriverInput(brake = 1.0), STEP)
            if (state.limiterActive && state.rpm < limiterProfile.limiterRpm - 60.0) {
                heldBelowTrigger = true
            }
            if (!state.limiterActive) releasedRpm = state.rpm
            elapsed += STEP
        }

        assertTrue("limiter should remain cut below its trigger threshold", heldBelowTrigger)
        assertNotNull("limiter should release after rpm falls through the hysteresis band", releasedRpm)
        assertTrue(requireNotNull(releasedRpm) <= limiterProfile.limiterRpm - 170.0)
    }

    @Test
    fun brakingSlowsVehicleAndEngineMoreThanCoasting() {
        val coasting = EngineSimulation()
        val braking = EngineSimulation()
        coasting.runFor(8.0, throttle = 1.0)
        braking.runFor(8.0, throttle = 1.0)

        val coastState = coasting.runFor(1.8, throttle = 0.0, brake = 0.0)
        val brakeState = braking.runFor(1.8, throttle = 0.0, brake = 1.0)

        assertTrue(brakeState.speedKmh < coastState.speedKmh)
        assertTrue(brakeState.rpm <= coastState.rpm)
    }

    @Test
    fun zeroPedalsSettleAtRealIdleAndNeverProduceNegativeSpeed() {
        val simulation = EngineSimulation()
        val untouchedIdle = simulation.runFor(2.0)
        assertEquals(simulation.profile.idleRpm, untouchedIdle.rpm, 1.0)

        simulation.runFor(3.0, throttle = 0.6)
        simulation.runFor(8.0, brake = 1.0)
        val settled = simulation.runFor(2.0)

        assertEquals(0.0, settled.speedKmh, 0.05)
        assertEquals(simulation.profile.idleRpm, settled.rpm, 8.0)
        assertEquals(0.0, settled.accelerationMps2, 0.01)
    }

    private fun EngineSimulation.runFor(
        seconds: Double,
        throttle: Double = 0.0,
        brake: Double = 0.0,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle = throttle, brake = brake), STEP)
        }
        return result
    }

    private fun EngineSimulation.runForExternal(seconds: Double, speedKmh: Double): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(externalSpeedKmh = speedKmh), STEP)
        }
        return result
    }

    private companion object {
        const val STEP = 1.0 / 200.0
        const val MIN_EXPECTED_COMPLETED_DWELL_SECONDS = 0.44
        const val MAX_EXPECTED_REPORTED_ACCELERATION = 15.0
    }
}
