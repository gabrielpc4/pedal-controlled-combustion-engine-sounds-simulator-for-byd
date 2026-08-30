package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
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
    fun ignitionStartRevPlaysUpshiftCueAtBlip() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        val catchFrames = (ENGINE_START_CATCH_END_SECONDS / STEP).toInt() - 1
        repeat(catchFrames) {
            simulation.update(DriverInput(), STEP)
        }
        assertEquals(0L, simulation.state.shiftSerial)

        simulation.update(DriverInput(), STEP)
        assertEquals(1L, simulation.state.shiftSerial)
        assertEquals(ShiftDirection.UP, simulation.state.shiftDirection)
    }

    @Test
    fun ignitionStartRevPlaysUpshiftCueOnEveryStartCycle() {
        val simulation = EngineSimulation()
        repeat(3) {
            simulation.startIgnition()
            val catchFrames = (ENGINE_START_CATCH_END_SECONDS / STEP).toInt() - 1
            repeat(catchFrames) {
                simulation.update(DriverInput(), STEP)
            }
            simulation.update(DriverInput(), STEP)
            assertEquals(1L, simulation.state.shiftSerial)
            assertEquals(ShiftDirection.UP, simulation.state.shiftDirection)

            simulation.requestShutdown()
            repeat(1_500) {
                simulation.update(DriverInput(), STEP)
            }
            assertEquals(EngineIgnitionState.OFF, simulation.ignition)
        }
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
    fun shutdownResetsGearAndBrakesToStop() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        repeat(600) {
            simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
        }
        assertTrue(simulation.state.speedKmh > 20.0)
        assertTrue(simulation.state.gear > 1)

        simulation.requestShutdown()
        repeat(1_200) {
            simulation.update(DriverInput(throttle = 0.0, simulateCoastRegen = true), STEP)
        }

        assertEquals(EngineIgnitionState.OFF, simulation.ignition)
        assertEquals(1, simulation.state.gear)
        assertEquals(0.0, simulation.state.speedKmh, 0.5)
    }

    @Test
    fun engageAtIdleSkipsStarterRevSequence() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        assertEquals(EngineIgnitionState.RUNNING, simulation.ignition)
        assertEquals(simulation.profile.idleRpm, simulation.state.rpm, 1.0)
    }

    @Test
    fun driveRpmRespondsToThrottleAtUnchangedRoadSpeed() {
        val profile = EngineProfile.SAMPLE_BANK_ENGINE
        val light = EngineSimulation(profile)
        val heavy = EngineSimulation(profile)
        val lightState = light.runForExternal(2.0, 48.0, 0.15)
        val heavyState = heavy.runForExternal(2.0, 48.0, 0.85)
        assertEquals(lightState.gear, heavyState.gear)
        assertEquals(lightState.speedKmh, heavyState.speedKmh, 0.01)
        assertTrue(
            "loaded crank RPM must rise ahead of unchanged wheel speed: light=${lightState.rpm}, heavy=${heavyState.rpm}",
            heavyState.rpm > lightState.rpm + 300.0,
        )
        assertTrue(lightState.rpm > profile.idleRpm + 2_000.0)
    }

    @Test
    fun drivePedalMovesAudioAndCrankBeforeTheNextSpeedSample() {
        val simulation = EngineSimulation()
        val baseline = simulation.runForExternal(1.5, 8.0, 0.0)

        val firstLoadedFrame = simulation.update(
            DriverInput(throttle = 1.0, externalSpeedKmh = 8.0),
            STEP,
        )
        assertEquals(baseline.speedKmh, firstLoadedFrame.speedKmh, 0.01)
        assertEquals(1.0, firstLoadedFrame.audioThrottle, 0.0)
        assertTrue("EV torque filtering must not delay audio load", firstLoadedFrame.smoothedThrottle < 0.20)
        assertTrue(
            "combustion RPM must react on the first 5 ms frame",
            firstLoadedFrame.rpm > baseline.rpm + 5.0,
        )

        repeat(9) {
            simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = 8.0), STEP)
        }
        assertEquals(baseline.speedKmh, simulation.state.speedKmh, 0.01)
        assertTrue(
            "combustion RPM must visibly lead wheel speed within 50 ms",
            simulation.state.rpm > baseline.rpm + 150.0,
        )
    }

    @Test
    fun driveRpmRecouplesPromptlyAfterPedalRelease() {
        val simulation = EngineSimulation()
        val baseline = simulation.runForExternal(1.5, 8.0, 0.0)
        val loaded = simulation.runForExternal(0.45, 8.0, 0.85)
        assertTrue(
            "loaded RPM must lead the same-speed coast RPM: baseline=${baseline.rpm}, loaded=${loaded.rpm}",
            loaded.rpm > baseline.rpm + 300.0,
        )

        val released = simulation.runForExternal(0.8, 8.0, 0.0)
        assertEquals(0.0, released.audioThrottle, 0.0)
        assertEquals(baseline.rpm, released.rpm, 80.0)
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
    fun downshiftHysteresisIsSortedIntBetweenZeroAndFour() {
        val hysteresis = sortedDownshiftHysteresisKmhByGear(EngineProfile.SAMPLE_BANK_ENGINE.gearRatios.size)
        val perDownshift = hysteresis.drop(1)

        assertTrue(perDownshift.isNotEmpty())
        perDownshift.forEach { value ->
            assertTrue(value in 0..EngineSimulation.DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH)
        }
        assertEquals(perDownshift, perDownshift.sortedDescending())
        assertEquals(0, perDownshift.last())
        assertEquals(EngineSimulation.DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH, perDownshift.first())
    }

    @Test
    fun upshiftTriggersBeforeLimiterEngages() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        var sawUpshift = false
        var limiterDuringUpshift = false
        repeat(2_000) {
            val state = simulation.update(DriverInput(throttle = 1.0, simulateCoastRegen = true), STEP)
            if (state.isShifting && state.shiftDirection == ShiftDirection.UP) {
                sawUpshift = true
                if (state.limiterActive) {
                    limiterDuringUpshift = true
                }
            }
        }
        assertTrue("expected at least one upshift under full throttle", sawUpshift)
        assertFalse("upshift must stay below the limiter latch", limiterDuringUpshift)
    }

    @Test
    fun upshiftTriggerUsesEachCarsShiftAndLimiterRpm() {
        EngineSampleProfiles.all.forEach { sample ->
            val profile = EngineProfile.SAMPLE_BANK_ENGINE.copy(
                idleRpm = sample.idleRpm,
                limiterRpm = sample.limiterRpm,
                upshiftRpm = sample.upshiftRpm,
            )
            val trigger = upshiftTriggerRpmForProfile(profile)
            val latchRpm = sample.limiterRpm - EngineSimulation.LIMITER_TRIGGER_MARGIN_RPM

            assertEquals(
                sample.upshiftRpm - EngineSimulation.UPSHIFT_EARLY_MARGIN_RPM,
                trigger,
                0.001,
            )
            assertTrue(trigger <= latchRpm - EngineSimulation.UPSHIFT_LIMITER_HEADROOM_RPM + 0.001)
        }
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
    fun secondToFirstDownshiftUsesTheLowerOfConfiguredRpmAndStableSpeed() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = EngineProfile.SAMPLE_BANK_ENGINE
        simulation.followIntegerSpeedRamp(0.0, 40.0, 5.0, 0.5)
        assertEquals(2, simulation.state.gear)

        val triggerSpeedKmh = speedKmhForCoupledRpm(profile, 1, profile.secondToFirstDownshiftRpm)
        val partialThrottleUpshiftSpeedKmh = evenlySpacedUpshiftSpeedKmh(profile, 0) *
            ((profile.firstToSecondPartialThrottleUpshiftRpm - profile.idleRpm) /
                (profile.upshiftRpm - profile.idleRpm))
        val stableDownshiftSpeedKmh = minOf(
            triggerSpeedKmh,
            partialThrottleUpshiftSpeedKmh - EngineSimulation.DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH,
        )
        val speedBoundaryDownshiftKmh = evenlySpacedUpshiftSpeedKmh(profile, 0) -
            sortedDownshiftHysteresisKmhByGear(profile.gearRatios.size)[1]
        assertTrue(
            "2→1 must keep a gap below the partial-throttle 1→2 shift point",
            stableDownshiftSpeedKmh < partialThrottleUpshiftSpeedKmh,
        )
        assertTrue("the configured 4,000 RPM point remains the upper bound", stableDownshiftSpeedKmh <= triggerSpeedKmh)
        assertTrue("the normal speed boundary remains below the configured 4,000 RPM point", triggerSpeedKmh < speedBoundaryDownshiftKmh)

        simulation.update(
            DriverInput(throttle = 0.5, externalSpeedKmh = triggerSpeedKmh + 4.0),
            STEP,
        )
        assertEquals("still above the configured 2→1 RPM coupled point", 2, simulation.state.gear)

        simulation.followIntegerSpeedRamp(triggerSpeedKmh + 4.0, stableDownshiftSpeedKmh - 2.0, 3.0, 0.5)
        assertEquals("must downshift to 1st once it crosses the stable 2→1 threshold", 1, simulation.state.gear)
    }

    @Test
    fun firstToSecondShiftDoesNotHuntAtThePartialThrottleSpeed() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = simulation.profile
        val partialThrottleUpshiftSpeedKmh = evenlySpacedUpshiftSpeedKmh(profile, 0) *
            ((profile.firstToSecondPartialThrottleUpshiftRpm - profile.idleRpm) /
                (profile.upshiftRpm - profile.idleRpm))
        val heldSpeedKmh = partialThrottleUpshiftSpeedKmh + 0.5

        simulation.followIntegerSpeedRamp(0.0, heldSpeedKmh + 3.0, 4.0, 0.5)
        simulation.runForExternal(1.0, heldSpeedKmh, 0.5)
        assertEquals(2, simulation.state.gear)
        val settledShiftSerial = simulation.state.shiftSerial

        simulation.runForExternal(1.0, heldSpeedKmh, 0.5)
        assertEquals("holding near the 1→2 point must remain in 2nd", 2, simulation.state.gear)
        assertEquals("the protected 1↔2 band must not repeatedly shift", settledShiftSerial, simulation.state.shiftSerial)
    }

    @Test
    fun firstGearPartialThrottleUpshiftsAtConfiguredRpm() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = simulation.profile
        val triggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)

        simulation.followIntegerSpeedRamp(0.0, triggerSpeedKmh - 3.0, 4.0, 0.5)
        assertEquals(1, simulation.state.gear)

        simulation.followIntegerSpeedRamp(triggerSpeedKmh - 3.0, triggerSpeedKmh + 2.0, 2.0, 0.5)
        assertEquals(
            "partial throttle must upshift 1→2 at the configured partial-shift RPM",
            2,
            simulation.state.gear,
        )
    }

    @Test
    fun firstGearFullThrottleWaitsForNormalUpshiftRpm() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = simulation.profile
        val partialTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)
        val normalTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, upshiftTriggerRpmForProfile(profile))

        simulation.followIntegerSpeedRamp(0.0, partialTriggerSpeedKmh + 2.0, 5.0, 1.0)
        assertEquals(
            "full throttle should stay in 1st past the partial-throttle upshift point",
            1,
            simulation.state.gear,
        )

        simulation.followIntegerSpeedRamp(partialTriggerSpeedKmh + 2.0, normalTriggerSpeedKmh + 2.0, 5.0, 1.0)
        assertTrue(
            "full throttle must upshift only after the normal shift RPM: gear=${simulation.state.gear}",
            simulation.state.gear >= 2,
        )
    }

    @Test
    fun firstGearPartialThrottleUsesNormalUpshiftWhenEarlyShiftDisabled() {
        val baseProfile = EngineSimulation().profile
        val profile = baseProfile.copy(secondGearEarlyShiftEnabled = false)
        val simulation = EngineSimulation(profile)
        simulation.engageAtIdle()
        val partialTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)
        val normalTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, upshiftTriggerRpmForProfile(profile))

        simulation.followIntegerSpeedRamp(0.0, partialTriggerSpeedKmh + 2.0, 5.0, 0.5)
        assertEquals(
            "with early shift off, partial throttle must stay in 1st past the partial-shift RPM",
            1,
            simulation.state.gear,
        )

        simulation.followIntegerSpeedRamp(partialTriggerSpeedKmh + 2.0, normalTriggerSpeedKmh + 2.0, 5.0, 0.5)
        assertEquals(
            "with early shift off, partial throttle must upshift at the normal shift RPM",
            2,
            simulation.state.gear,
        )
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
        assertEquals("shift dwell must prevent a shift loop", serialBeforeNoise, simulation.state.shiftSerial)
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
    fun reducedSimulatedDriveForceLetsRpmRiseBeforeRoadSpeed() {
        val regular = EngineSimulation().runFor(1.5, throttle = 1.0, sim = true)
        val slowed = EngineSimulation().runFor(
            seconds = 1.5,
            throttle = 1.0,
            sim = true,
            simulatedDriveForceScale = 0.05,
        )

        assertTrue("test-mode road speed must build very slowly: $slowed", slowed.speedKmh < 5.0)
        assertTrue("test-mode speed must remain far below normal simulation", slowed.speedKmh < regular.speedKmh * 0.15)
        assertTrue("full pedal must still reach audio immediately", slowed.audioThrottle > 0.99)
        assertTrue(
            "loaded RPM must flare despite the nearly stationary virtual car: $slowed",
            slowed.rpm > EngineProfile.SAMPLE_BANK_ENGINE.idleRpm + 600.0,
        )
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
    fun manualModeDoesNotAutoUpshift() {
        val simulation = EngineSimulation()
        simulation.manualShiftEnabled = true
        simulation.engageAtIdle()
        val profile = simulation.profile
        val partialTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)
        val normalTriggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, upshiftTriggerRpmForProfile(profile))

        simulation.followIntegerSpeedRamp(0.0, partialTriggerSpeedKmh + 4.0, 6.0, 0.5)
        assertEquals(1, simulation.state.gear)

        simulation.followIntegerSpeedRamp(partialTriggerSpeedKmh + 4.0, normalTriggerSpeedKmh + 4.0, 8.0, 1.0)
        assertEquals(
            "manual mode must never auto-upshift",
            1,
            simulation.state.gear,
        )
    }

    @Test
    fun manualModeRequestUpshiftChangesGear() {
        val simulation = EngineSimulation()
        simulation.manualShiftEnabled = true
        simulation.engageAtIdle()
        val profile = simulation.profile
        val triggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)
        simulation.followIntegerSpeedRamp(0.0, triggerSpeedKmh + 2.0, 4.0, 0.8)
        assertEquals(1, simulation.state.gear)

        assertTrue(simulation.requestManualUpshift())
        repeat(80) {
            simulation.update(DriverInput(throttle = 0.8, externalSpeedKmh = triggerSpeedKmh + 2.0), STEP)
        }
        assertEquals(2, simulation.state.gear)
    }

    @Test
    fun manualModeIdleProtectionAutoDownshifts() {
        val simulation = EngineSimulation()
        simulation.manualShiftEnabled = true
        simulation.engageAtIdle()
        val profile = simulation.profile
        val highSpeedKmh = 120.0
        simulation.followIntegerSpeedRamp(0.0, highSpeedKmh, 10.0, 1.0)
        var upshifts = 0
        while (upshifts < profile.gearRatios.lastIndex) {
            if (simulation.requestManualUpshift()) {
                upshifts += 1
            }
            repeat(60) {
                simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = highSpeedKmh), STEP)
            }
        }
        assertTrue(
            "manual upshifts should reach a high gear before the coast-down check",
            simulation.state.gear >= 5,
        )

        val lowSpeedKmh = 8.0
        val shiftSerialBeforeCoast = simulation.state.shiftSerial
        val highGearBeforeCoast = simulation.state.gear
        simulation.followIntegerSpeedRamp(highSpeedKmh, lowSpeedKmh, 8.0, 0.0)
        repeat(800) {
            simulation.update(DriverInput(throttle = 0.0, externalSpeedKmh = lowSpeedKmh), STEP)
        }
        assertTrue(
            "idle protection must move out of the highest gear at ${lowSpeedKmh}km/h: before=$highGearBeforeCoast after=${simulation.state.gear}",
            simulation.state.gear < highGearBeforeCoast,
        )
        assertTrue(
            "coupled RPM must stay above the idle-audio band after protection",
            simulation.state.rpm >= EngineSimulation.MANUAL_IDLE_PROTECTION_RPM - 25.0,
        )
        assertTrue(
            "idle protection should trigger at least one downshift",
            simulation.state.shiftSerial > shiftSerialBeforeCoast,
        )
    }

    @Test
    fun manualModeNonLastGearLimiterEngagesAtCarMaximum() {
        val simulation = EngineSimulation()
        simulation.manualShiftEnabled = true
        simulation.engageAtIdle()
        val limiter = simulation.profile.limiterRpm
        simulation.followIntegerSpeedRamp(0.0, 100.0, 12.0, 1.0)
        repeat(600) {
            simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = 100.0), STEP)
        }
        assertEquals(1, simulation.state.gear)
        assertEquals(limiter, simulation.state.rpm, 80.0)
        assertTrue(simulation.state.limiterActive)
        assertTrue(simulation.state.speedKmh > 30.0)
    }

    @Test
    fun manualModeDownshiftCapsRpmAtCarMaximum() {
        val simulation = EngineSimulation()
        simulation.manualShiftEnabled = true
        simulation.engageAtIdle()
        val limiter = simulation.profile.limiterRpm
        simulation.followIntegerSpeedRamp(0.0, 110.0, 10.0, 1.0)
        var upshifts = 0
        while (upshifts < simulation.profile.gearRatios.lastIndex) {
            if (simulation.requestManualUpshift()) {
                upshifts += 1
            }
            repeat(40) {
                simulation.update(DriverInput(throttle = 1.0, externalSpeedKmh = 110.0), STEP)
            }
        }
        simulation.requestManualDownshift()
        repeat(100) {
            simulation.update(DriverInput(throttle = 0.8, externalSpeedKmh = 110.0), STEP)
        }
        assertTrue(
            "downshift must never push coupled RPM above the car maximum",
            simulation.state.rpm <= limiter + 1.0,
        )
    }

    @Test
    fun launchControlDisarmsWithGradualRpmFallWhenThrottleReleased() {
        val simulation = EngineSimulation()
        simulation.ensureIgnitionRunning()

        repeat(240) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }
        val rpmBeforeRelease = simulation.state.rpm
        assertTrue(rpmBeforeRelease > LaunchControl.HOLD_RPM - 200.0)

        simulation.update(
            DriverInput(throttle = 0.0, brake = 0.35, simulateCoastRegen = true),
            STEP,
        )
        val rpmAfterOneFrame = simulation.state.rpm
        assertTrue(rpmAfterOneFrame > simulation.profile.idleRpm + 200.0)
        assertTrue(rpmAfterOneFrame < rpmBeforeRelease)

        repeat(5) {
            simulation.update(
                DriverInput(throttle = 0.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }
        assertTrue(simulation.state.rpm > simulation.profile.idleRpm + 100.0)
    }

    @Test
    fun launchControlHoldsFiveThousandWhileArmedThenLaunches() {
        val simulation = EngineSimulation()
        simulation.ensureIgnitionRunning()

        repeat(120) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }
        assertTrue(simulation.state.rpm > LaunchControl.HOLD_RPM - 120.0)
        assertTrue(simulation.state.rpm < LaunchControl.HOLD_RPM + LaunchControl.ARMED_OVERSHOOT_RPM + 120.0)

        repeat(200) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }
        assertTrue(simulation.state.rpm > LaunchControl.HOLD_RPM - LaunchControl.JITTER_AMPLITUDE_RPM - 40.0)
        assertTrue(simulation.state.rpm < LaunchControl.HOLD_RPM + LaunchControl.JITTER_AMPLITUDE_RPM + 40.0)

        var peakLaunchRpm = 0.0
        repeat(160) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.0, simulateCoastRegen = true),
                STEP,
            )
            peakLaunchRpm = maxOf(peakLaunchRpm, simulation.state.rpm)
        }
        assertTrue(peakLaunchRpm > simulation.profile.redlineRpm - 150.0)

        repeat(40) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }
        repeat(600) {
            simulation.update(
                DriverInput(throttle = 0.0, brake = 1.0, simulateCoastRegen = true),
                STEP,
            )
        }
        assertTrue(simulation.state.speedKmh < 5.0)
        assertTrue(simulation.state.rpm < LaunchControl.HOLD_RPM - 200.0)
    }

    @Test
    fun brakeAtStandstillBlocksThrottleFromAddingSpeed() {
        assertTrue(LaunchControl.blocksDriveAtStandstill(speedMps = 0.0, brake = 0.2))
        assertTrue(!LaunchControl.blocksDriveAtStandstill(speedMps = 1.0, brake = 0.2))
    }

    @Test
    fun fullThrottleAndBrakeAtStopKeepsSimulatorSpeedAtZero() {
        val simulation = EngineSimulation()
        simulation.ensureIgnitionRunning()

        repeat(240) {
            simulation.update(
                DriverInput(throttle = 1.0, brake = 0.35, simulateCoastRegen = true),
                STEP,
            )
        }

        assertEquals(0.0, simulation.state.speedKmh, 0.001)
    }

    @Test
    fun parkKeepsSimulatorSpeedAtZero() {
        val simulation = EngineSimulation()
        val state = simulation.runFor(0.8, throttle = 1.0, sim = true, position = TransmissionPosition.PARK)
        assertEquals(0.0, state.speedKmh, 0.001)
    }

    @Test
    fun skylineNeutralRevMatchesTheAudioLabTorqueAndInertiaTrace() {
        val skyline = EngineSampleProfiles.find("nissan_skyline_r34_cabin")
        val profile = EngineProfile.SAMPLE_BANK_ENGINE.copy(
            name = skyline.displayName,
            idleRpm = skyline.idleRpm,
            redlineRpm = skyline.redlineRpm,
            limiterRpm = skyline.limiterRpm,
            upshiftRpm = skyline.upshiftRpm,
            freeRevCalibration = FreeRevCalibration.forEngine(
                name = skyline.displayName,
                idleRpm = skyline.idleRpm,
                limiterRpm = skyline.limiterRpm,
                maxTorqueNm = EngineProfile.SAMPLE_BANK_ENGINE.maxTorqueNm,
            ),
        )
        val simulation = EngineSimulation(profile)
        simulation.ensureIgnitionRunning()
        val idle = simulation.profile.idleRpm

        val firstFrame = simulation.update(
            DriverInput(throttle = 1.0, transmissionPosition = TransmissionPosition.NEUTRAL),
            STEP,
        )
        assertTrue(firstFrame.rpm > idle)
        assertTrue("free rev must not teleport on its first frame", firstFrame.rpm < idle + 100.0)

        repeat((0.05 / STEP).toInt() - 1) {
            simulation.update(
                DriverInput(throttle = 1.0, transmissionPosition = TransmissionPosition.NEUTRAL),
                STEP,
            )
        }
        assertEquals("50 ms Audio Lab trace", 1_213.4, simulation.state.rpm, 45.0)

        repeat((0.45 / STEP).toInt()) {
            simulation.update(
                DriverInput(throttle = 1.0, transmissionPosition = TransmissionPosition.NEUTRAL),
                STEP,
            )
        }
        val fullThrottle = simulation.state.rpm
        assertEquals("500 ms Audio Lab trace", 8_239.4, fullThrottle, 110.0)
        assertTrue("the free rev must pass the limiter threshold before fuel cut", fullThrottle > simulation.profile.limiterRpm)

        repeat((0.5 / STEP).toInt()) {
            simulation.update(
                DriverInput(throttle = 1.0, transmissionPosition = TransmissionPosition.NEUTRAL),
                STEP,
            )
        }
        assertEquals("one-second Audio Lab limiter trace", 7_891.9, simulation.state.rpm, 130.0)

        val liftFrame = simulation.update(
            DriverInput(throttle = 0.0, transmissionPosition = TransmissionPosition.NEUTRAL),
            STEP,
        )
        assertTrue("crank inertia must keep the first lift frame above idle", liftFrame.rpm > idle + 500.0)
        assertTrue(liftFrame.rpm <= fullThrottle + 75.0)

        repeat((0.495 / STEP).toInt()) {
            simulation.update(
                DriverInput(throttle = 0.0, transmissionPosition = TransmissionPosition.NEUTRAL),
                STEP,
            )
        }
        assertEquals("500 ms lift Audio Lab trace", 5_833.0, simulation.state.rpm, 140.0)
        assertTrue("coast torque must retain audible revs after a half-second lift", simulation.state.rpm > idle + 4_000.0)
    }

    @Test
    fun parkAndNeutralUseTheSameFreeRevModelWithoutMovingTheVehicle() {
        val neutral = EngineSimulation()
        val park = EngineSimulation()
        neutral.ensureIgnitionRunning()
        park.ensureIgnitionRunning()

        repeat((0.35 / STEP).toInt()) {
            neutral.update(DriverInput(throttle = 0.8, transmissionPosition = TransmissionPosition.NEUTRAL), STEP)
            park.update(DriverInput(throttle = 0.8, transmissionPosition = TransmissionPosition.PARK), STEP)
        }

        assertEquals(neutral.state.rpm, park.state.rpm, 0.001)
        assertEquals(0.0, neutral.state.speedKmh, 0.001)
        assertEquals(0.0, park.state.speedKmh, 0.001)
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
        simulatedDriveForceScale: Double = 1.0,
    ): DrivetrainState {
        ensureIgnitionRunning()
        var result = state
        repeat((seconds / STEP).toInt()) {
            result = update(
                DriverInput(
                    throttle = throttle,
                    brake = brake,
                    simulateCoastRegen = sim,
                    transmissionPosition = position,
                    simulatedDriveForceScale = simulatedDriveForceScale,
                ),
                STEP,
            )
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
