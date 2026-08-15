package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSimulationTest {
    @Test
    fun defaultCalibrationUsesPublishedSealPerformanceAnchors() {
        val profile = EngineProfile.APEX_V10

        assertEquals(670.0, profile.maxTorqueNm, 0.0)
        assertEquals(390.0, profile.peakPowerKw, 0.0)
        assertEquals(3_170.0, profile.frontPeakWheelTorqueNm, 0.0)
        assertEquals(3_975.0, profile.rearPeakWheelTorqueNm, 0.0)
        assertEquals(2_185.0, profile.vehicleMassKg, 0.0)
        assertEquals(180.0, profile.topSpeedKmh, 0.0)
        assertEquals(8_600.0, profile.redlineRpm, 0.0)
        assertEquals(8_850.0, profile.limiterRpm, 0.0)
        assertEquals(8_250.0, profile.upshiftRpm, 0.0)
        assertTrue(profile.upshiftRpm < profile.redlineRpm)
        assertTrue(profile.redlineRpm < profile.limiterRpm)
    }

    @Test
    fun fullThrottleBuildsSyntheticRpmProgressivelyWithRoadSpeed() {
        val simulation = EngineSimulation()
        val initial = simulation.state
        val afterHalfSecond = simulation.runFor(0.5, throttle = 1.0)

        assertTrue(
            "rpm should rise progressively with electric road speed: $afterHalfSecond",
            afterHalfSecond.rpm > initial.rpm + 200.0,
        )
        assertTrue(afterHalfSecond.rpm < simulation.profile.redlineRpm)
        assertTrue(afterHalfSecond.speedKmh > 0.0)
    }

    @Test
    fun electricLaunchDoesNotBogAtAnyPositiveThrottle() {
        listOf(0.01, 0.05, 0.10, 0.25, 0.50, 0.75, 1.0).forEach { throttle ->
            val simulation = EngineSimulation()
            var peakRpm = simulation.state.rpm
            var largestDrop = 0.0
            var state = simulation.state

            repeat((3.0 / STEP).toInt()) {
                state = simulation.update(DriverInput(throttle = throttle), STEP)
                if (state.gear == 1 && !state.isShifting) {
                    peakRpm = maxOf(peakRpm, state.rpm)
                    largestDrop = maxOf(largestDrop, peakRpm - state.rpm)
                }
            }

            assertTrue(
                "first-gear launch at ${(throttle * 100).toInt()}% throttle lost " +
                    "$largestDrop RPM after reaching $peakRpm: $state",
                largestDrop < 30.0,
            )
        }
    }

    @Test
    fun digitizedAxleEnvelopeMatchesMeasuredPeaksAndTorqueDistribution() {
        val profile = EngineProfile.APEX_V10
        val launch = axleWheelTorqueAtSpeed(profile, 0.0)
        val midSpeed = axleWheelTorqueAtSpeed(profile, 100.0)
        val highSpeed = axleWheelTorqueAtSpeed(profile, 180.0)

        assertEquals(3_170.0, launch.frontNm, 0.5)
        assertEquals(3_975.0, launch.rearNm, 0.5)
        assertEquals(7_145.0, launch.totalNm, 0.5)
        assertEquals(0.556, launch.rearShare, 0.002)
        assertEquals(0.63, midSpeed.rearShare, 0.02)
        assertEquals(0.71, highSpeed.rearShare, 0.02)
        assertTrue(midSpeed.totalNm < launch.totalNm * 0.60)
        assertTrue(highSpeed.totalNm < midSpeed.totalNm * 0.50)
    }

    @Test
    fun accelerationIsStrongestLowDownAndTapersAtHighMotorSpeed() {
        fun accelerationAt(speedKmh: Double): Double {
            val simulation = EngineSimulation()
            repeat((1.0 / STEP).toInt()) {
                simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = speedKmh), STEP)
            }
            return simulation.update(DriverInput(throttle = 1.0), STEP).accelerationMps2
        }

        val lowSpeedAcceleration = accelerationAt(25.0)
        val highSpeedAcceleration = accelerationAt(120.0)
        assertTrue("expected immediate low-speed EV thrust: $lowSpeedAcceleration", lowSpeedAcceleration > 8.3)
        assertTrue(
            "measured wheel-torque taper should reduce acceleration with speed: low=$lowSpeedAcceleration high=$highSpeedAcceleration",
            highSpeedAcceleration < lowSpeedAcceleration * 0.55,
        )
    }

    @Test
    fun fullThrottleZeroToHundredMatchesPublishedPerformance() {
        val simulation = EngineSimulation()
        var elapsed = 0.0
        while (simulation.state.speedKmh < 100.0 && elapsed < 8.0) {
            simulation.update(DriverInput(throttle = 1.0), STEP)
            elapsed += STEP
        }

        assertTrue("0-100 km/h took $elapsed seconds", elapsed in 3.90..4.02)
    }

    @Test
    fun fullThrottleSplitTimesAgreeWithIndependentInstrumentedTests() {
        val simulation = EngineSimulation()
        var elapsed = 0.0
        val crossings = mutableMapOf<Int, Double>()
        while (simulation.state.speedKmh < 120.0 && elapsed < 10.0) {
            val state = simulation.update(DriverInput(throttle = 1.0), STEP)
            elapsed += STEP
            listOf(60, 80, 110, 120).forEach { speed ->
                if (state.speedKmh >= speed && speed !in crossings) crossings[speed] = elapsed
            }
        }

        val zeroToSixty = requireNotNull(crossings[60])
        val sixtyToOneTen = requireNotNull(crossings[110]) - zeroToSixty
        val eightyToOneTwenty = requireNotNull(crossings[120]) - requireNotNull(crossings[80])
        assertTrue("0-60 km/h took $zeroToSixty seconds", zeroToSixty in 1.90..2.20)
        assertTrue("60-110 km/h took $sixtyToOneTen seconds", sixtyToOneTen in 2.40..2.80)
        assertTrue("80-120 km/h took $eightyToOneTwenty seconds", eightyToOneTwenty in 2.40..3.00)
    }

    @Test
    fun fullBrakeStoppingDistanceMatchesIndependentTrackTests() {
        val simulation = EngineSimulation()
        simulation.update(DriverInput(externalSpeedKmh = 100.0), STEP)
        var distanceMeters = 0.0
        var elapsed = 0.0
        while (simulation.state.speedKmh > 0.05 && elapsed < 8.0) {
            val beforeMps = simulation.state.speedKmh / 3.6
            val after = simulation.update(DriverInput(brake = 1.0), STEP)
            val afterMps = after.speedKmh / 3.6
            distanceMeters += (beforeMps + afterMps) * 0.5 * STEP
            elapsed += STEP
        }

        assertTrue("100-0 km/h used $distanceMeters m", distanceMeters in 35.0..39.0)
    }

    @Test
    fun syntheticUpshiftNeverCutsElectricWheelTorque() {
        val simulation = EngineSimulation()
        var beforeShiftAcceleration: Double? = null
        var largestSingleStepDrop = 0.0
        var previous = simulation.state
        var elapsed = 0.0
        while (elapsed < 20.0) {
            val state = simulation.update(DriverInput(throttle = 1.0), STEP)
            if (!previous.isShifting && state.isShifting) {
                beforeShiftAcceleration = previous.accelerationMps2
            }
            if (state.isShifting && state.shiftSerial == 1L) {
                largestSingleStepDrop = maxOf(
                    largestSingleStepDrop,
                    previous.accelerationMps2 - state.accelerationMps2,
                )
            }
            if (beforeShiftAcceleration != null && previous.isShifting && !state.isShifting) break
            previous = state
            elapsed += STEP
        }

        val before = requireNotNull(beforeShiftAcceleration)
        assertTrue(
            "presentation shift caused a wheel-torque discontinuity: before=$before stepDrop=$largestSingleStepDrop",
            largestSingleStepDrop < 0.03,
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
    fun eachDownshiftPointIsItsPrecedingUpshiftLandingRpm() {
        val profile = EngineProfile.APEX_V10
        for (gearIndex in 1..profile.gearRatios.lastIndex) {
            val expected = profile.idleRpm +
                (profile.upshiftRpm - profile.idleRpm) *
                profile.gearRatios[gearIndex] / profile.gearRatios[gearIndex - 1]
            assertEquals(expected, postUpshiftLandingRpm(profile, gearIndex), 0.001)
        }
    }

    @Test
    fun releasingPedalKeepsRpmCoupledToRoadSpeed() {
        val profile = EngineProfile.APEX_V10.copy(gearRatios = doubleArrayOf(3.14))
        val simulation = EngineSimulation(profile)
        simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = 40.0), STEP)
        simulation.runForExternal(0.15, speedKmh = 40.0, throttle = 1.0)
        val beforeLift = simulation.state
        val afterLift = simulation.runForExternal(0.35, speedKmh = 40.0, throttle = 0.0)

        assertEquals(40.0, afterLift.speedKmh, 0.001)
        assertEquals(
            "lift-off RPM should stay road-coupled when speed is held constant",
            beforeLift.rpm,
            afterLift.rpm,
            120.0,
        )
    }

    @Test
    fun liftOffFromThirdDoesNotUpshiftHuntWhileCoasting() {
        val simulation = EngineSimulation()
        var previous = simulation.state
        var reachedThird = false
        var elapsed = 0.0
        while (elapsed < 20.0 && !reachedThird) {
            val state = simulation.update(DriverInput(throttle = 1.0), STEP)
            if (previous.isShifting && !state.isShifting && state.gear == 3) {
                reachedThird = true
            }
            previous = state
            elapsed += STEP
        }
        assertTrue("full-throttle run did not reach third gear", reachedThird)

        var upshiftStartsAfterLift = 0
        var lastShiftSerial = simulation.state.shiftSerial
        elapsed = 0.0
        while (elapsed < 6.0) {
            val state = simulation.update(DriverInput(), STEP)
            if (state.shiftSerial != lastShiftSerial) {
                if (state.shiftDirection == ShiftDirection.UP) upshiftStartsAfterLift += 1
                lastShiftSerial = state.shiftSerial
            }
            elapsed += STEP
        }

        assertEquals(
            "coasting lift-off must not trigger an upshift hunt",
            0,
            upshiftStartsAfterLift,
        )
    }

    @Test
    fun liftOffWithLiveSpeedHeldDoesNotHuntGears() {
        val profile = EngineProfile.APEX_V10.copy(
            redlineRpm = 2_800.0,
            limiterRpm = 3_000.0,
            upshiftRpm = 2_600.0,
            downshiftRpm = 1_100.0,
            gearRatios = doubleArrayOf(3.14, 2.10, 1.57, 0.10),
        )
        val simulation = EngineSimulation(profile)
        val joined = simulation.update(DriverInput(externalSpeedKmh = 30.0), STEP)
        assertEquals("calibrated live-speed setup should join in third", 3, joined.gear)
        simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = 30.0), STEP)

        var upshiftStartsWhileLifted = 0
        var lastShiftSerial = simulation.state.shiftSerial
        repeat((2.0 / STEP).toInt()) {
            val state = simulation.update(DriverInput(externalSpeedKmh = 35.0), STEP)
            if (state.shiftSerial != lastShiftSerial) {
                if (state.shiftDirection == ShiftDirection.UP) upshiftStartsWhileLifted += 1
                lastShiftSerial = state.shiftSerial
            }
        }

        assertEquals("live lift-off at constant road speed must not hunt upward", 0, upshiftStartsWhileLifted)
        assertEquals(3, simulation.state.gear)
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
    fun simulatorCoastRegenSlowsVirtualVehicleFasterThanDragAlone() {
        val profile = EngineProfile.APEX_V10.copy(simulatorCoastRegenMps2 = 0.50)
        val withoutRegen = EngineSimulation(profile)
        val withRegen = EngineSimulation(profile)
        withoutRegen.runFor(6.0, throttle = 1.0)
        withRegen.runFor(6.0, throttle = 1.0, simulateCoastRegen = true)

        val dragOnly = withoutRegen.runFor(2.0, throttle = 0.0)
        val regenCoast = withRegen.runFor(2.0, throttle = 0.0, simulateCoastRegen = true)

        assertTrue(
            "simulator coast regen should reduce speed faster than drag alone: " +
                "drag=${dragOnly.speedKmh} regen=${regenCoast.speedKmh}",
            regenCoast.speedKmh < dragOnly.speedKmh - 2.0,
        )
    }

    @Test
    fun brakingSlowsVehicleAndEngineMoreThanCoasting() {
        val coasting = EngineSimulation()
        val braking = EngineSimulation()
        coasting.runFor(8.0, throttle = 1.0)
        braking.runFor(8.0, throttle = 1.0)

        val coastState = coasting.runFor(1.8, throttle = 0.0, brake = 0.0, simulateCoastRegen = true)
        val brakeState = braking.runFor(1.8, throttle = 0.0, brake = 1.0, simulateCoastRegen = true)

        assertTrue(brakeState.speedKmh < coastState.speedKmh)
        assertTrue("braking should select the same or a lower gear", brakeState.gear <= coastState.gear)
        assertTrue(brakeState.rpm <= braking.profile.limiterRpm)
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
        simulateCoastRegen: Boolean = false,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(
                DriverInput(
                    throttle = throttle,
                    brake = brake,
                    simulateCoastRegen = simulateCoastRegen,
                ),
                STEP,
            )
        }
        return result
    }

    private fun EngineSimulation.runForExternal(
        seconds: Double,
        speedKmh: Double,
        throttle: Double = 0.0,
    ): DrivetrainState {
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(DriverInput(throttle = throttle, externalSpeedKmh = speedKmh), STEP)
        }
        return result
    }

    private companion object {
        const val STEP = 1.0 / 200.0
        const val MIN_EXPECTED_COMPLETED_DWELL_SECONDS = 0.44
        const val MAX_EXPECTED_REPORTED_ACCELERATION = 15.0
    }
}
