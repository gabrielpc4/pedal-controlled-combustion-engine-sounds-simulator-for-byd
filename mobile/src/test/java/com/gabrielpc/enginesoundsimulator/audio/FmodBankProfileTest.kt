package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmodBankProfileTest {
    @Test
    fun everySelectableCarHasOneNativeBankProfileAndValidPresentationLimits() {
        assertEquals(58, FmodBankProfiles.all.size)
        assertEquals(FmodBankProfiles.all.size, FmodBankProfiles.all.map(FmodBankProfile::id).distinct().size)
        assertEquals(
            FmodBankProfiles.all.size,
            FmodBankProfiles.all.map(FmodBankProfile::previewAssetName).distinct().size,
        )
        FmodBankProfiles.all.forEach { profile ->
            assertTrue(profile.bankPackId.isNotBlank())
            assertTrue(profile.idleRpm in profile.minimumRpm..profile.redlineRpm)
            assertTrue(profile.redlineRpm <= profile.limiterRpm)
            assertTrue(profile.limiterRpm <= profile.maximumRpm)
            assertTrue(profile.upshiftRpm in profile.idleRpm..profile.redlineRpm)
            assertTrue(profile.gearRatios.zipWithNext().all { (left, right) -> left > right })
            assertTrue(profile.supportsPrimaryLayerSource(PrimaryEngineLayerSource.LOAD))
            assertTrue(profile.supportsPrimaryLayerSource(PrimaryEngineLayerSource.COAST))
            assertTrue(profile.supportsPrimaryLayerSource(PrimaryEngineLayerSource.BOTH))
        }
    }

    @Test
    fun everyRuntimeProfileRequiresTheOriginalAssettoSharedBanks() {
        val carPackIds = FmodBankProfiles.all.map(FmodBankProfile::bankPackId).toSet()

        assertTrue(FmodBankProfiles.commonStringsPackId in FmodBankProfiles.requiredPackIds)
        assertTrue(FmodBankProfiles.commonPackId in FmodBankProfiles.requiredPackIds)
        assertEquals(carPackIds.size + 2, FmodBankProfiles.requiredPackIds.size)
    }

    @Test
    fun onlyVerifiedIdenticalBanksShareAnInstallerPayload() {
        val repeatedPackIds = FmodBankProfiles.all.groupBy(FmodBankProfile::bankPackId)
            .filterValues { it.size > 1 }
        assertEquals(
            setOf("lamborghini_aventador_sv_cabin", "nissan-350z"),
            repeatedPackIds.keys,
        )
        assertEquals(
            setOf("lamborghini_aventador_sv_cabin", "lexus-lfa-concept-gt500"),
            repeatedPackIds.getValue("lamborghini_aventador_sv_cabin").map(FmodBankProfile::id).toSet(),
        )
        assertEquals(
            setOf("nissan-350z", "nissan-370z-widebody"),
            repeatedPackIds.getValue("nissan-350z").map(FmodBankProfile::id).toSet(),
        )
    }

    @Test
    fun nativeMixerUsesOnlyAllowedEngineAndPowertrainGroups() {
        FmodBankProfiles.all.forEach { profile ->
            profile.mixerTracks(EngineSoundPerspective.CABIN).forEach { track ->
                assertTrue(track.id in ALLOWED_TRACK_IDS)
                assertFalse(track.displayName.contains("WAV", ignoreCase = true))
            }
        }
    }

    @Test
    fun smoothedFmodControlNeverReceivesWholeSpeedTelemetry() {
        val smoother = FmodControlSmoother(initialRpm = 1_000.0)
        val input = EngineAudioFrame(rpm = 1_000.0, throttle = 0.0)
        repeat(30) { smoother.advance(input, 0.004) }

        val ramp = buildList {
            repeat(50) { index -> add(1_000.0 + index * 4.0) }
        }
        val sent = ramp.map { rpm -> smoother.advance(input.copy(rpm = rpm), 0.004).rpm }
        val maximumStep = sent.zipWithNext().maxOf { (left, right) -> right - left }

        assertTrue("FMOD control must follow a continuous presentation RPM ramp", maximumStep < 4.1)
        assertTrue(sent.zipWithNext().all { (left, right) -> right >= left })
    }

    private companion object {
        val ALLOWED_TRACK_IDS = setOf(
            "engine_load",
            "engine_coast",
            "transmission",
            "turbo",
            "limiter",
            "gear",
            "overrun",
        )
    }
}
