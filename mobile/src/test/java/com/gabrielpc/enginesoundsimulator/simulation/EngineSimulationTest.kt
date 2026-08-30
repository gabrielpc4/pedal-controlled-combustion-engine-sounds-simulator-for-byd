package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

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
    fun ignitionStartRevDoesNotEmitFakeShiftEvent() {
        val simulation = EngineSimulation()
        simulation.startIgnition()
        repeat(500) {
            val state = simulation.update(DriverInput(), STEP)
            assertEquals(0L, state.shiftSerial)
            assertEquals(ShiftDirection.NONE, state.shiftDirection)
        }
    }

    @Test
    fun repeatedIgnitionCyclesDoNotEmitFakeShiftEvents() {
        val simulation = EngineSimulation()
        repeat(3) {
            simulation.startIgnition()
            repeat(500) { simulation.update(DriverInput(), STEP) }
            assertEquals(0L, simulation.state.shiftSerial)
            assertEquals(ShiftDirection.NONE, simulation.state.shiftDirection)

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
    fun quantizedSpeedEstimatorPredictsBetweenFiftyHertzIntegerSamples() {
        val estimator = QuantizedSpeedEstimator()
        var physicalSpeedKmh = 0.0
        var bydIntegerSpeedKmh = 0.0
        estimator.update(bydIntegerSpeedKmh, STEP, 0.12)
        val estimates = mutableListOf<Double>()
        val physicalSpeeds = mutableListOf<Double>()

        repeat(3_000) { tick ->
            physicalSpeedKmh += 2.0 * STEP
            if (tick % 4 == 0) {
                bydIntegerSpeedKmh = floor(physicalSpeedKmh + 0.5)
            }
            val estimate = estimator.update(bydIntegerSpeedKmh, STEP, 0.12)
            if (physicalSpeedKmh >= 5.0) {
                estimates += estimate
                physicalSpeeds += physicalSpeedKmh
            }
        }

        val deltas = estimates.zipWithNext { previous, current -> current - previous }
        assertTrue("interpolated speed must advance on every 5 ms frame", deltas.all { it > 0.0 })
        assertTrue("largest reconstructed step=${deltas.max()}", deltas.max() < 0.05)
        assertTrue(
            "maximum reconstruction error=${estimates.zip(physicalSpeeds).maxOf { abs(it.first - it.second) }}",
            estimates.zip(physicalSpeeds).all { (estimate, physical) -> abs(estimate - physical) < 0.80 },
        )
        assertTrue(estimates.any { abs(it - round(it)) > 0.05 })
    }

    @Test
    fun quantizedSpeedEstimatorInterpolatesBrakingAndSettlesExactlyAtZero() {
        val estimator = QuantizedSpeedEstimator()
        var physicalSpeedKmh = 24.0
        var bydIntegerSpeedKmh = 24.0
        estimator.update(bydIntegerSpeedKmh, STEP, 0.12)
        val brakingEstimates = mutableListOf<Double>()

        repeat(2_400) { tick ->
            physicalSpeedKmh = (physicalSpeedKmh - 3.0 * STEP).coerceAtLeast(0.0)
            if (tick % 4 == 0) {
                bydIntegerSpeedKmh = floor(physicalSpeedKmh + 0.5)
            }
            val estimate = estimator.update(bydIntegerSpeedKmh, STEP, 0.12)
            if (physicalSpeedKmh in 2.0..20.0) brakingEstimates += estimate
        }

        val deltas = brakingEstimates.zipWithNext { previous, current -> current - previous }
        assertTrue("interpolated braking must move on every frame", deltas.all { it < 0.0 })
        assertTrue("largest reconstructed braking step=${deltas.min()}", deltas.min() > -0.06)

        var stoppedEstimate = Double.NaN
        repeat(400) { stoppedEstimate = estimator.update(0.0, STEP, 0.12) }
        assertEquals(0.0, stoppedEstimate, 0.0)
    }

    @Test
    fun quantizedSpeedEstimatorStopsPredictingWhenNonzeroSpeedIsHeld() {
        val estimator = QuantizedSpeedEstimator()
        estimator.update(19.0, STEP, 0.12)
        repeat(100) { estimator.update(20.0, STEP, 0.12) }

        var heldEstimate = Double.NaN
        repeat(600) { heldEstimate = estimator.update(20.0, STEP, 0.12) }

        assertEquals(20.0, heldEstimate, 0.001)
    }

    @Test
    fun wholeKmhBydTelemetryProducesContinuousSpeedAndRpmBetweenPolls() {
        val simulation = EngineSimulation().apply { engageAtIdle() }
        var physicalSpeedKmh = 10.0
        var bydIntegerSpeedKmh = 10.0
        val samples = mutableListOf<DrivetrainState>()

        repeat(1_000) { tick ->
            physicalSpeedKmh += 2.0 * STEP
            // The real integration polls at 50 Hz and can repeat one whole-km/h value for many
            // polls. The drivetrain and tach still update at the controller's 200 Hz cadence.
            if (tick % 4 == 0) {
                bydIntegerSpeedKmh = floor(physicalSpeedKmh + 0.5)
            }
            val state = simulation.update(
                DriverInput(throttle = 0.25, externalSpeedKmh = bydIntegerSpeedKmh),
                STEP,
            )
            if (physicalSpeedKmh >= 13.0) samples += state
        }

        assertTrue(samples.zipWithNext().any { (first, second) ->
            first.rawSpeedKmh == second.rawSpeedKmh
        })
        assertTrue(samples.zipWithNext().all { (first, second) ->
            second.speedKmh > first.speedKmh && second.rpm > first.rpm
        })
        val oneKmhRpmStep = coupledRpmIncreaseForSpeedKmh(
            profile = simulation.profile,
            gearIndex = 0,
            speedKmh = 1.0,
        )
        assertTrue(samples.zipWithNext().all { (first, second) ->
            val delta = second.rpm - first.rpm
            delta > 0.0 && delta < oneKmhRpmStep
        })
    }

    @Test
    fun simulatorSubOneKmhMotionProducesContinuousRpmUpdates() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val samples = mutableListOf<DrivetrainState>()
        repeat((4.0 / STEP).toInt()) {
            val state = simulation.update(
                DriverInput(throttle = 0.25, simulateCoastRegen = true),
                STEP,
            )
            if (state.speedKmh in 0.05..0.95) {
                samples += state
            }
        }

        assertTrue("expected several continuous samples below 1 km/h, got ${samples.size}", samples.size >= 8)
        assertTrue(samples.all { it.rawSpeedKmh == it.speedKmh })
        assertTrue(samples.zipWithNext().all { (first, second) ->
            second.speedKmh > first.speedKmh &&
                second.speedKmh - first.speedKmh < 1.0 &&
                second.rpm > first.rpm
        })
        val oneKmhRpmStep = coupledRpmIncreaseForSpeedKmh(simulation.profile, gearIndex = 0, speedKmh = 1.0)
        assertTrue(samples.zipWithNext().all { (first, second) ->
            val delta = second.rpm - first.rpm
            delta > 0.0 && delta < oneKmhRpmStep
        })
    }

    @Test
    fun fractionalRealTelemetryProducesMonotonicSubStepRpmUpdates() {
        val simulation = EngineSimulation().apply { engageAtIdle() }
        repeat(300) {
            simulation.update(
                DriverInput(throttle = 0.25, externalSpeedKmh = 12.0),
                STEP,
            )
        }

        val samples = (1..40).map { step ->
            simulation.update(
                DriverInput(throttle = 0.25, externalSpeedKmh = 12.0 + step * 0.01),
                STEP,
            )
        }

        assertEquals(12.40, samples.last().rawSpeedKmh, 0.000001)
        assertTrue(samples.zipWithNext().all { (first, second) ->
            second.speedKmh > first.speedKmh &&
                second.speedKmh - first.speedKmh < 1.0 &&
                second.rpm > first.rpm
        })
        val oneKmhRpmStep = coupledRpmIncreaseForSpeedKmh(simulation.profile, gearIndex = 0, speedKmh = 1.0)
        assertTrue(samples.zipWithNext().all { (first, second) ->
            val delta = second.rpm - first.rpm
            delta > 0.0 && delta < oneKmhRpmStep
        })
    }

    @Test
    fun externalToSimulatorHandoffPreservesContinuousFractionalSpeedAndRpm() {
        val simulation = EngineSimulation().apply { engageAtIdle() }
        val externalSpeedKmh = 42.375
        repeat(200) {
            simulation.update(
                DriverInput(throttle = 0.0, externalSpeedKmh = externalSpeedKmh),
                STEP,
            )
        }
        val beforeHandoff = simulation.state

        val afterHandoff = simulation.update(
            DriverInput(throttle = 0.0, simulateCoastRegen = false),
            STEP,
        )

        assertEquals(externalSpeedKmh, beforeHandoff.rawSpeedKmh, 0.0)
        assertEquals(externalSpeedKmh, beforeHandoff.speedKmh, 0.000001)
        assertEquals(beforeHandoff.gear, afterHandoff.gear)
        assertFalse(afterHandoff.isShifting)

        val speedDeltaKmh = beforeHandoff.speedKmh - afterHandoff.speedKmh
        assertTrue("handoff speed delta=$speedDeltaKmh", speedDeltaKmh in 0.0..0.10)
        assertEquals(afterHandoff.speedKmh, afterHandoff.rawSpeedKmh, 0.0)
        assertTrue(
            "simulator speed was quantized to ${afterHandoff.rawSpeedKmh}",
            afterHandoff.rawSpeedKmh > floor(afterHandoff.rawSpeedKmh),
        )

        val rpmDelta = beforeHandoff.rpm - afterHandoff.rpm
        val maximumContinuousRpmDelta = coupledRpmIncreaseForSpeedKmh(
            profile = simulation.profile,
            gearIndex = beforeHandoff.gear - 1,
            speedKmh = 0.10,
        )
        assertTrue("handoff RPM delta=$rpmDelta", rpmDelta in 0.0..maximumContinuousRpmDelta)
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
    fun driveRpmIsDeterminedByRoadSpeedRatherThanThrottleForce() {
        val profile = EngineProfile.SKYLINE_R34
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
    fun everyCarSoundGearboxDividesOneHundredNinetyKmhIntoEqualRedlineBands() {
        FmodCarProfiles.all.forEach { fmodProfile ->
            val profile = EngineProfile.SKYLINE_R34.copy(
                name = fmodProfile.displayName,
                idleRpm = fmodProfile.idleRpm,
                redlineRpm = fmodProfile.redlineRpm,
                limiterRpm = fmodProfile.limiterRpm,
                upshiftRpm = fmodProfile.upshiftRpm,
                gearRatios = fmodProfile.gearRatios.toDoubleArray(),
            )
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
                assertEquals(fmodProfile.redlineRpm, boundaryRpm, 0.001)
            }
            val topSpeedWheelRpm = (190.0 / 3.6) /
                (2.0 * Math.PI * profile.wheelRadiusMeters) * 60.0
            val topSpeedRpm = profile.idleRpm + topSpeedWheelRpm *
                evenlySpacedGearRatio(profile, profile.gearRatios.lastIndex)
            assertEquals(fmodProfile.redlineRpm, topSpeedRpm, 0.001)
        }
    }

    @Test
    fun profileSwitchCancelsShiftWhoseTargetGearDoesNotExistInNewProfile() {
        val sevenSpeed = EngineProfile.SKYLINE_R34.copy(
            gearRatios = FmodCarProfiles.huracanTrofeoEvo2.gearRatios.toDoubleArray(),
            upshiftDurationSeconds = 0.080,
        )
        val sixSpeed = sevenSpeed.copy(
            gearRatios = FmodCarProfiles.skylineR34.gearRatios.toDoubleArray(),
        )
        val simulation = EngineSimulation(sevenSpeed).apply {
            manualShiftEnabled = true
            engageAtIdle()
        }
        // Establish the external-speed source before starting a shift. Its first sample
        // intentionally synchronizes the presentation gearbox and cancels any in-flight shift.
        repeat(40) {
            simulation.update(
                DriverInput(throttle = 0.0, externalSpeedKmh = 0.0),
                STEP,
            )
        }
        // Let the deliberately rate-limited telemetry estimator reach road speed while still
        // in first; otherwise manual idle protection correctly downshifts second during the ramp.
        repeat(900) {
            simulation.update(
                DriverInput(throttle = 1.0, externalSpeedKmh = 190.0),
                STEP,
            )
        }

        repeat(5) {
            assertTrue(
                "manual upshift ${it + 1} should be accepted; state=${simulation.state}",
                simulation.requestManualUpshift(),
            )
            repeat(100) {
                simulation.update(
                    DriverInput(throttle = 1.0, externalSpeedKmh = 190.0),
                    STEP,
                )
            }
        }
        assertEquals(6, simulation.state.gear)
        assertTrue(simulation.requestManualUpshift())
        assertTrue(simulation.state.isShifting)

        simulation.updateProfile(sixSpeed)
        repeat(12) {
            simulation.update(
                DriverInput(throttle = 1.0, externalSpeedKmh = 190.0),
                STEP,
            )
            assertTrue("six-speed profile exposed gear ${simulation.state.gear}", simulation.state.gear <= 6)
        }
        assertFalse(simulation.state.isShifting)
    }

    @Test
    fun downshiftHysteresisIsSortedIntBetweenZeroAndFour() {
        val hysteresis = sortedDownshiftHysteresisKmhByGear(EngineProfile.SKYLINE_R34.gearRatios.size)
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
        FmodCarProfiles.all.forEach { sample ->
            val profile = EngineProfile.SKYLINE_R34.copy(
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
    fun secondToFirstDownshiftUsesFixedFourThousandRpm() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = EngineProfile.SKYLINE_R34
        simulation.followIntegerSpeedRamp(0.0, 40.0, 5.0, 0.5)
        assertEquals(2, simulation.state.gear)

        val triggerSpeedKmh = speedKmhForCoupledRpm(profile, 1, profile.secondToFirstDownshiftRpm)

        simulation.update(
            DriverInput(throttle = 0.5, externalSpeedKmh = triggerSpeedKmh + 4.0),
            STEP,
        )
        assertEquals("still above the configured 2→1 RPM coupled point", 2, simulation.state.gear)

        simulation.followIntegerSpeedRamp(triggerSpeedKmh + 4.0, triggerSpeedKmh - 2.0, 3.0, 0.5)
        // Allow one pair of 20 ms BYD polling periods plus the cosmetic downshift duration for the
        // reconstructed final sample to cross the threshold and finish the accepted shift.
        simulation.runForExternal(
            profile.downshiftDurationSeconds + 0.04,
            floor(triggerSpeedKmh - 2.0),
            0.5,
        )
        assertEquals(
            "must downshift to 1st once coupled RPM falls through the configured threshold; state=${simulation.state}",
            1,
            simulation.state.gear,
        )
    }

    @Test
    fun firstGearPartialThrottleUpshiftsAtConfiguredRpm() {
        val simulation = EngineSimulation()
        simulation.engageAtIdle()
        val profile = simulation.profile
        val triggerSpeedKmh = speedKmhForCoupledRpm(profile, 0, profile.firstToSecondPartialThrottleUpshiftRpm)

        simulation.followIntegerSpeedRamp(0.0, triggerSpeedKmh - 3.0, 4.0, 0.5)
        assertEquals(1, simulation.state.gear)

        simulation.followIntegerSpeedRamp(triggerSpeedKmh - 3.0, triggerSpeedKmh + 5.0, 3.0, 0.5)
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
        repeat(300) { frame ->
            val raw = if ((frame / 20) % 2 == 0) 58.0 else 59.0
            simulation.update(DriverInput(throttle = 0.45, externalSpeedKmh = raw), STEP)
        }
        val serialAfterSettling = simulation.state.shiftSerial
        repeat(700) { frame ->
            val raw = if ((frame / 20) % 2 == 0) 58.0 else 59.0
            simulation.update(DriverInput(throttle = 0.45, externalSpeedKmh = raw), STEP)
        }
        assertTrue(simulation.state.gear >= 2)
        assertTrue(
            "crossing the new six-gear boundary may settle once, but not hunt",
            serialAfterSettling - serialBeforeNoise <= 1L,
        )
        assertEquals("shift dwell and hysteresis must prevent a shift loop", serialAfterSettling, simulation.state.shiftSerial)
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

    private fun coupledRpmIncreaseForSpeedKmh(
        profile: EngineProfile,
        gearIndex: Int,
        speedKmh: Double,
    ): Double {
        val wheelRpm = (speedKmh / 3.6) /
            (2.0 * Math.PI * profile.wheelRadiusMeters) * 60.0
        return wheelRpm * evenlySpacedGearRatio(profile, gearIndex)
    }

    private companion object { const val STEP = 1.0 / 200.0 }
}
