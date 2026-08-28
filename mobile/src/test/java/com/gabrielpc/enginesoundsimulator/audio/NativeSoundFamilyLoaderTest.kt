package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.catalog.CurvePointV1
import com.gabrielpc.enginesoundsimulator.catalog.PackTrackRole
import com.gabrielpc.enginesoundsimulator.catalog.SoundTrackManifestV1
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSoundFamilyLoaderTest {
    @Test
    fun hardBudgetIsAnUnconditionalLimit() {
        val failure = runCatching {
            NativeSoundFamilyLoader.validateDecodedBudget(
                totalDecodedBytes = 101L,
                budget = DecodedAudioBudget(softBytes = 64L, hardBytes = 100L),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("hard budget"))
    }

    @Test
    fun aboveSoftBudgetRequiresCompilerRpmWindows() {
        val failure = runCatching {
            NativeSoundFamilyLoader.validateDecodedBudget(
                totalDecodedBytes = 65L,
                budget = DecodedAudioBudget(softBytes = 64L, hardBytes = 100L),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("compiler-defined RPM windows"))
    }

    @Test
    fun profileAtSoftBudgetIsAccepted() {
        NativeSoundFamilyLoader.validateDecodedBudget(
            totalDecodedBytes = 64L,
            budget = DecodedAudioBudget(softBytes = 64L, hardBytes = 100L),
        )
    }

    @Test
    fun deviceBudgetRejectsInvalidOrdering() {
        val failure = runCatching { DecodedAudioBudget(softBytes = 101L, hardBytes = 100L) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Decoded-audio hard budget must cover the soft budget", failure?.message)
    }

    @Test
    fun continuousTrackRejectsOnePointDefaultCurve() {
        val failure = runCatching {
            NativeSoundFamilyLoader.validateContinuousCurves(
                listOf(track("coast", PackTrackRole.COAST, listOf(CurvePointV1(0.0, 1.0)))),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun continuousTracksRejectAllVoicesActiveAcrossRpmRange() {
        val alwaysOn = listOf(CurvePointV1(0.0, 1.0), CurvePointV1(9_000.0, 1.0))
        val failure = runCatching {
            NativeSoundFamilyLoader.validateContinuousCurves(
                listOf(
                    track("idle", PackTrackRole.IDLE, alwaysOn),
                    track("coast", PackTrackRole.COAST, alwaysOn),
                    track("texture", PackTrackRole.TEXTURE, alwaysOn),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun explicitAdjacentWindowsAreAccepted() {
        NativeSoundFamilyLoader.validateContinuousCurves(
            listOf(
                track("idle", PackTrackRole.IDLE, listOf(CurvePointV1(0.0, 1.0), CurvePointV1(3_000.0, 0.0))),
                track("coast", PackTrackRole.COAST, listOf(CurvePointV1(0.0, 0.0), CurvePointV1(5_000.0, 1.0))),
                track("texture", PackTrackRole.TEXTURE, listOf(CurvePointV1(4_000.0, 0.0), CurvePointV1(9_000.0, 1.0))),
            ),
        )
    }

    @Test
    fun continuousEffectCanCarryCompilerCaptureRoot() {
        val effect = SampleEffectSpec(
            id = "transmission",
            control = SampleEffectControls.transmission,
            assetName = "transmission.flac",
            trigger = SampleEffectTrigger.TRANSMISSION_LOOP,
            autopitchRootRpm = 4_200.0,
        )

        assertEquals(4_200.0, effect.autopitchRootRpm!!, 0.0)
        assertEquals(1.0, effect.authoredPlaybackRatio(4_200.0)!!, 0.0)
        assertEquals(0.5, effect.authoredPlaybackRatio(2_100.0)!!, 0.0)
    }

    @Test
    fun engineTransientAutoPitchUsesTheExactUnclampedLiveRpmRatio() {
        val effect = SampleEffectSpec(
            id = "engine_transient",
            control = SampleEffectControls.coreEngine,
            assetName = "engine_transient.flac",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            autopitchRootRpm = 2_000.0,
            coreEngineTransient = true,
        )

        assertEquals(0.0, effect.authoredEngineTransientPlaybackRatio(0.0), 0.0)
        assertEquals(0.05, effect.authoredEngineTransientPlaybackRatio(100.0), 0.0)
        assertEquals(5.0, effect.authoredEngineTransientPlaybackRatio(10_000.0), 0.0)
    }

    private fun track(id: String, role: PackTrackRole, rpmCurve: List<CurvePointV1>) = SoundTrackManifestV1(
        id = id,
        role = role,
        path = "$id.flac",
        flacSha256 = "0".repeat(64),
        pcmSha256 = "1".repeat(64),
        frameCount = 128,
        rootRpm = 1_000.0,
        loopStartFrame = 0,
        loopEndFrameExclusive = 128,
        gainDb = -12.0,
        peakDbfs = -6.0,
        rpmCurve = rpmCurve,
        gainCurve = listOf(CurvePointV1(0.0, 1.0), CurvePointV1(1.0, 1.0)),
        triggers = emptyList(),
    )
}
