package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FmodControlPlannerTest {
    @Test
    fun skylineProfileContainsOnlyTheFiveCockpitPowertrainEvents() {
        val profile = FmodCarProfiles.default

        assertEquals(800.0, profile.idleRpm, 0.0)
        assertEquals(7_900.0, profile.upshiftRpm, 0.0)
        assertEquals(8_000.0, profile.redlineRpm, 0.0)
        assertEquals(8_000.0, profile.limiterRpm, 0.0)
        assertEquals(8_500.0, profile.maximumRpm, 0.0)
        assertEquals(6, profile.gearCount)
        assertEquals(0.095, profile.upshiftDurationSeconds, 0.0)
        assertEquals(0.220, profile.downshiftDurationSeconds, 0.0)
        assertEquals(
            setOf(
                FmodEventKind.ENGINE,
                FmodEventKind.TURBO,
                FmodEventKind.LIMITER,
                FmodEventKind.SHIFTS,
                FmodEventKind.BACKFIRE,
            ),
            profile.events.keys,
        )
        assertTrue(profile.events.values.all { "_ext" !in it.path })
    }

    @Test
    fun updateReusesOneStateObject() {
        val planner = FmodControlPlanner()
        val first = planner.update(EngineAudioFrame(enabled = true), STEP)
        val second = planner.update(EngineAudioFrame(enabled = true), STEP)
        assertSame(first, second)
    }

    @Test
    fun mixSettingsChangesRefreshCachedFlagsAndGains() {
        val planner = FmodControlPlanner()
        val baseline = planner.update(
            EngineAudioFrame(enabled = true, eventMixSettings = FmodEventMixSettings.DEFAULT),
            STEP,
        )
        val baselineEngineGain = baseline.engineGain
        assertTrue(baseline.eventEnabled(FmodEventKind.ENGINE))

        val mutedEngine = FmodEventMixSettings.DEFAULT.withControl(
            FmodEventKind.ENGINE,
            FmodEventControl(enabled = false, gainDb = -6.0),
        )
        val updated = planner.update(
            EngineAudioFrame(enabled = true, eventMixSettings = mutedEngine),
            STEP,
        )

        assertFalse(updated.eventEnabled(FmodEventKind.ENGINE))
        assertTrue(updated.engineGain < baselineEngineGain)
    }

    @Test
    fun fractionalRpmRemainsContinuousAtFmodControlBoundary() {
        val planner = FmodControlPlanner()
        val firstRpm = planner.update(
            EngineAudioFrame(rpm = 1_234.375, enabled = true),
            STEP,
        ).rpm
        val secondRpm = planner.update(
            EngineAudioFrame(rpm = 1_234.5, enabled = true),
            STEP,
        ).rpm

        assertEquals(1_234.375f, firstRpm, 0f)
        assertEquals(1_234.5f, secondRpm, 0f)
        assertTrue(secondRpm > firstRpm)
        assertTrue(secondRpm - firstRpm < 1f)
    }

    @Test
    fun coastOnlyOverridesOnlyEngineParameter() {
        val normal = FmodControlPlanner()
        val coast = FmodControlPlanner()
        var normalState = normal.update(EngineAudioFrame(enabled = true), STEP)
        var coastState = coast.update(EngineAudioFrame(enabled = true, coastOnlyEnabled = true), STEP)

        repeat(200) {
            normalState = normal.update(
                EngineAudioFrame(rpm = 6_000.0, throttle = 0.9, enabled = true),
                STEP,
            )
            coastState = coast.update(
                EngineAudioFrame(
                    rpm = 6_000.0,
                    throttle = 0.9,
                    enabled = true,
                    coastOnlyEnabled = true,
                ),
                STEP,
            )
        }

        assertEquals(0.9f, normalState.engineThrottle, 0.0001f)
        assertEquals(0f, coastState.engineThrottle, 0f)
        assertEquals(normalState.boost, coastState.boost, 0.000001f)

        normalState = normal.update(
            EngineAudioFrame(rpm = 6_000.0, throttle = 0.0, enabled = true),
            STEP,
        )
        coastState = coast.update(
            EngineAudioFrame(
                rpm = 6_000.0,
                throttle = 0.0,
                enabled = true,
                coastOnlyEnabled = true,
            ),
            STEP,
        )
        assertEquals(1L, normalState.backfireSerial)
        assertEquals(normalState.backfireSerial, coastState.backfireSerial)
        assertEquals(normalState.bov, coastState.bov, 0f)
    }

    @Test
    fun loadOnlyMatchesDesktopLabWithoutHidingTheRealPedalFromOtherSystems() {
        val planner = FmodControlPlanner()
        planner.update(
            EngineAudioFrame(
                rpm = 6_000.0,
                throttle = 0.9,
                enabled = true,
                loadOnlyEnabled = true,
            ),
            STEP,
        )

        var state = planner.update(
            EngineAudioFrame(
                rpm = 6_000.0,
                throttle = 0.9,
                enabled = true,
                loadOnlyEnabled = true,
            ),
            STEP,
        )
        assertEquals(1f, state.engineThrottle, 0f)
        assertEquals(1f, state.transmissionThrottle, 0f)
        assertTrue(state.boost > 0f)

        state = planner.update(
            EngineAudioFrame(
                rpm = 6_000.0,
                throttle = 0.0,
                enabled = true,
                loadOnlyEnabled = true,
            ),
            STEP,
        )
        assertEquals(1f, state.engineThrottle, 0f)
        assertEquals(1f, state.transmissionThrottle, 0f)
        assertEquals(1L, state.backfireSerial)

        state = planner.update(
            EngineAudioFrame(
                rpm = 6_000.0,
                throttle = 0.5,
                enabled = true,
                loadOnlyEnabled = true,
                coastOnlyEnabled = true,
            ),
            STEP,
        )
        assertEquals(0f, state.engineThrottle, 0f)
    }

    @Test
    fun turboUsesAuthoredLagAndNeverExceedsSkylineBankDomain() {
        val planner = FmodControlPlanner()
        var state = planner.update(EngineAudioFrame(rpm = 8_000.0, throttle = 1.0, enabled = true), STEP)
        val firstBoost = state.boost
        repeat(200) {
            state = planner.update(
                EngineAudioFrame(rpm = 8_000.0, throttle = 1.0, enabled = true),
                STEP,
            )
            assertTrue(state.boost <= FmodControlPlanner.TURBO_NORMALIZED_CAP + 0.000001)
        }
        assertTrue(firstBoost > 0f)
        assertTrue(state.boost > firstBoost)
        assertEquals(FmodControlPlanner.TURBO_NORMALIZED_CAP, state.boost.toDouble(), 0.0001)
    }

    @Test
    fun turboPlanningCannotDelayOrChangePhysicalEvTorque() {
        val baseline = EngineSimulation().apply { engageAtIdle() }
        val withAudio = EngineSimulation().apply { engageAtIdle() }
        val planner = FmodControlPlanner()
        val input = DriverInput(throttle = 1.0)

        repeat(400) {
            val baselineState = baseline.update(input, STEP)
            val audioState = withAudio.update(input, STEP)
            planner.update(
                EngineAudioFrame(
                    rpm = audioState.rpm,
                    throttle = input.throttle,
                    enabled = true,
                    limiterActive = audioState.limiterActive,
                ),
                STEP,
            )
            assertEquals(baselineState.speedKmh, audioState.speedKmh, 0.0)
            assertEquals(baselineState.rpm, audioState.rpm, 0.0)
        }
    }

    @Test
    fun bovSerialAdvancesOnlyOnPressureReleaseEdges() {
        val planner = FmodControlPlanner(FmodCarProfiles.toyotaSupraMk4)
        repeat(200) {
            planner.update(EngineAudioFrame(rpm = 7_000.0, throttle = 1.0, enabled = true), STEP)
        }

        var state = planner.update(
            EngineAudioFrame(rpm = 7_000.0, throttle = 0.0, enabled = true),
            STEP,
        )
        assertEquals(1f, state.bov, 0f)
        assertEquals(1L, state.bovSerial)
        state = planner.update(
            EngineAudioFrame(rpm = 7_000.0, throttle = 0.0, enabled = true),
            STEP,
        )
        assertEquals(1L, state.bovSerial)
        assertEquals(FmodControlState.MAX_DECAY_SECONDS.toFloat(), state.bovDecaySeconds, 0f)
    }

    @Test
    fun eachAcceptedShiftSerialProducesExactlyOneNativeSerial() {
        val planner = FmodControlPlanner()
        var state = planner.update(EngineAudioFrame(enabled = true), STEP)
        assertEquals(0L, state.shiftSerial)

        state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 1L, shiftDirection = 1),
            STEP,
        )
        assertEquals(1L, state.shiftSerial)
        assertEquals(1, state.shiftDirection)

        repeat(20) {
            state = planner.update(
                EngineAudioFrame(enabled = true, shiftSerial = 1L, shiftDirection = 1),
                STEP,
            )
        }
        assertEquals(1L, state.shiftSerial)
        assertEquals(0, state.shiftDirection)

        planner.update(EngineAudioFrame(enabled = true, shiftSerial = 0L), STEP)
        state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 1L, shiftDirection = -1),
            STEP,
        )
        assertEquals(2L, state.shiftSerial)
        assertEquals(-1, state.shiftDirection)
    }

    @Test
    fun delayedWorkerPreservesEveryMonotonicShiftEdge() {
        val planner = FmodControlPlanner()

        var state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 3L, shiftDirection = 1),
            STEP,
        )
        assertEquals(0L, state.shiftSerial)

        state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 6L, shiftDirection = 1),
            STEP,
        )

        assertEquals(3L, state.shiftSerial)
        assertEquals(1, state.shiftDirection)
    }

    @Test
    fun freshPlannerBaselinesHistoricalShiftSerialWithoutReplay() {
        val planner = FmodControlPlanner()

        var state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 42L, shiftDirection = -1),
            STEP,
        )
        assertEquals(0L, state.shiftSerial)
        assertEquals(0, state.shiftDirection)

        state = planner.update(
            EngineAudioFrame(enabled = true, shiftSerial = 43L, shiftDirection = 1),
            STEP,
        )
        assertEquals(1L, state.shiftSerial)
        assertEquals(1, state.shiftDirection)
    }

    @Test
    fun limiterEmitsImmediateFiftyHertzPulsesAndTracksDecaySeconds() {
        val planner = FmodControlPlanner()
        var state = planner.update(
            EngineAudioFrame(rpm = 8_000.0, throttle = 1.0, enabled = true, limiterActive = true),
            FmodControlPlanner.LIMITER_PULSE_SECONDS,
        )
        assertEquals(1L, state.limiterSerial)
        assertEquals(0f, state.limiterDecaySeconds, 0f)

        repeat(5) {
            state = planner.update(
                EngineAudioFrame(rpm = 8_000.0, throttle = 1.0, enabled = true, limiterActive = true),
                FmodControlPlanner.LIMITER_PULSE_SECONDS,
            )
        }
        assertEquals(6L, state.limiterSerial)
        assertEquals(0f, state.limiterDecaySeconds, 0.000001f)

        state = planner.update(EngineAudioFrame(enabled = true, limiterActive = false), STEP)
        assertEquals(6L, state.limiterSerial)
        assertEquals(STEP.toFloat(), state.limiterDecaySeconds, 0.000001f)
    }

    @Test
    fun backfireAcceptsZeroReleaseAndDebouncesRepeatedEdges() {
        val planner = FmodControlPlanner()
        planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 0.81, enabled = true), STEP)
        var state = planner.update(
            EngineAudioFrame(rpm = 6_000.0, throttle = 0.0, enabled = true),
            STEP,
        )
        assertEquals(1L, state.backfireSerial)

        planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 1.0, enabled = true), STEP)
        state = planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 0.0, enabled = true), STEP)
        assertEquals(1L, state.backfireSerial)

        repeat((FmodControlPlanner.BACKFIRE_DEBOUNCE_SECONDS / STEP).toInt() + 1) {
            planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 0.5, enabled = true), STEP)
        }
        planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 1.0, enabled = true), STEP)
        state = planner.update(EngineAudioFrame(rpm = 6_000.0, throttle = 0.0, enabled = true), STEP)
        assertEquals(2L, state.backfireSerial)
    }

    @Test
    fun eventControlsProduceNativeFlagsAndClampedLinearGains() {
        val settings = FmodEventMixSettings.DEFAULT
            .withControl(FmodEventKind.ENGINE, FmodEventControl(enabled = false, gainDb = 20.0))
            .withControl(FmodEventKind.TURBO, FmodEventControl(gainDb = 6.0))
            .withControl(FmodEventKind.LIMITER, FmodEventControl(gainDb = -60.0))
        val state = FmodControlPlanner().update(
            EngineAudioFrame(enabled = true, masterGain = 0.75, eventMixSettings = settings),
            STEP,
        )

        assertTrue(state.audioEnabled)
        assertFalse(state.eventEnabled(FmodEventKind.ENGINE))
        assertTrue(state.eventEnabled(FmodEventKind.TURBO))
        assertEquals(0.75f, state.masterGain, 0f)
        assertEquals(dbToLinear(6.0), state.engineGain.toDouble(), 0.000001)
        assertEquals(dbToLinear(6.0), state.turboGain.toDouble(), 0.000001)
        assertEquals(dbToLinear(-60.0), state.limiterGain.toDouble(), 0.000001)
    }

    @Test
    fun transmissionUsesRawPedalAndNativeWheelSpeedDespiteCoastOnly() {
        val settings = FmodEventMixSettings.DEFAULT.withControl(
            FmodEventKind.TRANSMISSION,
            FmodEventControl(gainDb = -6.0),
        )
        val state = FmodControlPlanner(FmodCarProfiles.huracanTrofeoEvo2).update(
            EngineAudioFrame(
                rpm = 5_000.0,
                throttle = 0.73,
                drivetrainSpeed = 999.0,
                enabled = true,
                coastOnlyEnabled = true,
                eventMixSettings = settings,
            ),
            STEP,
        )

        assertEquals(0f, state.engineThrottle, 0f)
        assertEquals(0.73f, state.transmissionThrottle, 0.0001f)
        assertEquals(260f, state.drivetrainSpeed, 0f)
        assertEquals(dbToLinear(-6.0), state.transmissionGain.toDouble(), 0.000001)
        assertTrue(state.eventEnabled(FmodEventKind.TRANSMISSION))
    }

    @Test
    fun emptyAventadorStubsAreNeitherExposedNorTriggered() {
        val profile = FmodCarProfiles.aventadorSv
        val planner = FmodControlPlanner(profile)
        planner.update(
            EngineAudioFrame(
                rpm = 8_500.0,
                throttle = 1.0,
                enabled = true,
                shiftSerial = 10L,
                shiftDirection = 1,
                limiterActive = true,
            ),
            STEP,
        )
        val state = planner.update(
            EngineAudioFrame(
                rpm = 8_500.0,
                throttle = 0.0,
                enabled = true,
                shiftSerial = 11L,
                shiftDirection = -1,
                limiterActive = true,
            ),
            STEP,
        )

        assertEquals(setOf(FmodEventKind.ENGINE, FmodEventKind.TRANSMISSION), profile.events.keys)
        assertFalse(state.eventEnabled(FmodEventKind.SHIFTS))
        assertFalse(state.eventEnabled(FmodEventKind.LIMITER))
        assertFalse(state.eventEnabled(FmodEventKind.BACKFIRE))
        assertEquals(0L, state.shiftSerial)
        assertEquals(0L, state.limiterSerial)
        assertEquals(0L, state.backfireSerial)
    }

    @Test
    fun alfaUsesRepresentableFiftyHertzLimiterAndExactZeroBackfireRelease() {
        val profile = FmodCarProfiles.alfaRomeo4c
        val planner = FmodControlPlanner(profile)
        var state = planner.update(
            EngineAudioFrame(rpm = 6_600.0, throttle = 0.81, enabled = true, limiterActive = true),
            STEP,
        )
        assertEquals(1L, state.limiterSerial)

        state = planner.update(
            EngineAudioFrame(rpm = 6_600.0, throttle = 0.001, enabled = true, limiterActive = true),
            STEP,
        )
        assertEquals(1L, state.limiterSerial)
        assertEquals(0L, state.backfireSerial)

        state = planner.update(
            EngineAudioFrame(rpm = 6_600.0, throttle = 0.0, enabled = true, limiterActive = true),
            FmodControlPlanner.LIMITER_PULSE_SECONDS,
        )
        assertEquals(2L, state.limiterSerial)
        assertEquals(1L, state.backfireSerial)
    }

    @Test
    fun profileMetadataMatchesAuditedBanksAndSafeTachCalibration() {
        assertEquals(5, FmodCarProfiles.all.size)
        assertTrue(
            FmodCarProfiles.all
                .filter { it.supports(FmodEventKind.LIMITER) }
                .all { it.limiterHz <= FmodControlPlanner.CONTROL_HZ / 2.0 },
        )
        assertEquals("ce941cbe-fe23-4184-acd1-67f43f609cbf", FmodCarProfiles.skylineR34.carBankGuid)
        assertEquals("40e767d1-1f6e-4f72-b010-3925a72569c6", FmodCarProfiles.huracanTrofeoEvo2.carBankGuid)
        assertEquals("513e17ef-cf00-4135-827f-4b29a30af327", FmodCarProfiles.aventadorSv.carBankGuid)
        assertEquals("026643c1-7a2f-486c-983e-52b241bf4a19", FmodCarProfiles.alfaRomeo4c.carBankGuid)
        assertEquals("072e5002-4521-4f3e-88fe-7245f3b304d4", FmodCarProfiles.toyotaSupraMk4.carBankGuid)

        assertCalibration(FmodCarProfiles.huracanTrofeoEvo2, 1_040.0, 8_200.0, 8_200.0, 8_350.0, 9_000.0, 7)
        assertCalibration(FmodCarProfiles.aventadorSv, 850.0, 8_400.0, 8_400.0, 8_500.0, 9_000.0, 7)
        assertCalibration(FmodCarProfiles.alfaRomeo4c, 850.0, 6_300.0, 6_500.0, 6_750.0, 7_700.0, 6)
        assertCalibration(FmodCarProfiles.toyotaSupraMk4, 980.0, 7_950.0, 8_000.0, 8_000.0, 8_500.0, 6)
    }

    private fun assertCalibration(
        profile: FmodCarProfile,
        idle: Double,
        upshift: Double,
        redline: Double,
        limiter: Double,
        maximum: Double,
        gears: Int,
    ) {
        assertEquals(idle, profile.idleRpm, 0.0)
        assertEquals(upshift, profile.upshiftRpm, 0.0)
        assertEquals(redline, profile.redlineRpm, 0.0)
        assertEquals(limiter, profile.limiterRpm, 0.0)
        assertEquals(maximum, profile.maximumRpm, 0.0)
        assertEquals(gears, profile.gearCount)
    }

    private companion object {
        const val STEP = 1.0 / FmodControlPlanner.CONTROL_HZ
    }
}
