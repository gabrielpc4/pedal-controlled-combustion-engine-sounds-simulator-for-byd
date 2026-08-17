package com.gabrielpc.enginesoundsimulator.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleEngineRendererTest {
    private val profile = EngineSampleProfiles.default

    @Test
    fun everySelectableCarHasACompleteDistinctSampleProfile() {
        assertEquals(2, EngineSampleProfiles.all.size)
        assertEquals(2, EngineSampleProfiles.all.map { it.id }.distinct().size)
        assertEquals(2, EngineSampleProfiles.all.map { it.previewAssetName }.distinct().size)

        EngineSampleProfiles.all.forEach { candidate ->
            assertTrue("${candidate.id} has no layers", candidate.layers.isNotEmpty())
            assertTrue(candidate.requiredAssets.containsAll(candidate.layers.map { it.assetName }))
            assertTrue(candidate.requiredAssets.containsAll(candidate.effects.map { it.assetName }))
            assertTrue(candidate.outputSampleRate == 44_100 || candidate.outputSampleRate == 48_000)
            for (rpm in candidate.idleRpm.toInt()..candidate.limiterRpm.toInt() step 25) {
                val onLoad = candidate.layers.maxOf { it.gainAt(rpm.toDouble(), 1.0) }
                val lifted = candidate.layers.maxOf { it.gainAt(rpm.toDouble(), 0.0) }
                assertTrue("${candidate.id} has no full-load voice at $rpm", onLoad > 0.0001)
                assertTrue("${candidate.id} has no lift-off voice at $rpm", lifted > 0.0001)
            }
        }
        val huracan = EngineSampleProfiles.find("lamborghini_huracan_trofeo_evo2_cabin")
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        assertEquals(44_100, huracan.outputSampleRate)
        assertEquals(48_000, huracan.playbackSampleRate)
        assertEquals(48_000, aventador.outputSampleRate)
        assertEquals(48_000, aventador.playbackSampleRate)
        EngineSampleProfiles.all.filterNot { it.id == huracan.id }.forEach { candidate ->
            assertEquals("${candidate.id} playback rate changed", candidate.outputSampleRate, candidate.playbackSampleRate)
        }
    }

    @Test
    fun profileContainsRecoveredContinuousEngineEvent() {
        assertEquals(24, profile.layers.size)
        assertEquals(27, profile.requiredAssets.size)
        assertEquals(3, profile.effects.size)
        assertEquals(7, profile.gearRatios.size)
        assertEquals(10_000.0, profile.maximumRpm, 0.0)
        assertEquals(8_350.0, profile.limiterRpm, 0.0)
        assertEquals(1.0, profile.outputGainAt(0.0), 0.0001)
        assertEquals(0.75, profile.throttleOutputGainDb?.valueAt(1.0) ?: 0.0, 0.0)
        assertTrue(profile.outputGainAt(1.0) > profile.outputGainAt(0.0) * 1.09)
        assertTrue(profile.layers.any { it.role == SampleLayerRole.IDLE })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.LOAD })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.COAST })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.LIMITER })

        for (rpm in profile.idleRpm.toInt()..profile.limiterRpm.toInt() step 10) {
            assertTrue("no audible full-load layer at $rpm", strongestGain(rpm.toDouble(), 1.0) > 0.0001)
            assertTrue("no audible lift-off layer at $rpm", strongestGain(rpm.toDouble(), 0.0) > 0.0001)
        }
    }

    @Test
    fun throttleCurvesKeepTonalBodyWhileStillFavoringTheCorrectLayerSet() {
        val load = profile.layers.first { it.id == "l1" }
        val coast = profile.layers.first { it.id == "c2" }
        val broadbandNoise = profile.layers.first { it.id == "engine_noise_7" }

        assertTrue(load.gainAt(7_500.0, 1.0) > load.gainAt(7_500.0, 0.0) * 8.0)
        assertTrue(coast.gainAt(7_000.0, 0.0) > coast.gainAt(7_000.0, 1.0) * 2.5)
        assertTrue(coast.gainAt(7_000.0, 1.0) > 0.10)
        assertEquals(-0.5, broadbandNoise.baseGainDb, 0.0)
    }

    @Test
    fun wavDecoderPreservesStereoPcm16AndReadsLoopMetadataAfterData() {
        val wav = pcm16Wav(
            sampleRate = 44_100,
            channels = 2,
            interleaved = shortArrayOf(12_000, -4_000, -16_000, 8_000).repeatFrames(20),
            loopStart = 7,
            loopEndInclusive = 31,
        )

        val decoded = WavPcmDecoder.decode(ByteArrayInputStream(wav))

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(2, decoded.sourceChannels)
        assertEquals(40, decoded.frameCount)
        assertEquals(7, decoded.loopStartFrame)
        assertEquals(32, decoded.loopEndFrameExclusive)
        assertEquals(12_000.0 / 32_768.0, decoded.channelSamples[0][0].toDouble(), 0.00001)
        assertEquals(-4_000.0 / 32_768.0, decoded.channelSamples[1][0].toDouble(), 0.00001)
        assertEquals(-16_000.0 / 32_768.0, decoded.channelSamples[0][1].toDouble(), 0.00001)
        assertEquals(8_000.0 / 32_768.0, decoded.channelSamples[1][1].toDouble(), 0.00001)
    }

    @Test
    fun codeDrivenSweepKeepsNativeRangeAudibleAndReportsRuntimeTelemetry() {
        val decoded = testBank()
        val renderer = SampleEngineRenderer.fromDecoded(44_100, decoded, profile)
        var totalNonZero = 0

        for (step in 0..100) {
            val rpm = profile.idleRpm + (profile.limiterRpm - profile.idleRpm) * step / 100.0
            val throttle = when {
                step < 20 -> 0.0
                step < 60 -> (step - 20) / 40.0
                else -> 1.0
            }
            val output = ShortArray(1_920)
            renderer.render(EngineAudioFrame(rpm = rpm, throttle = throttle), output, gain = 1.0)
            val nonZero = output.count { it != 0.toShort() }
            assertTrue("silent render at rpm=$rpm throttle=$throttle", nonZero > output.size * 0.75)
            totalNonZero += nonZero
        }
        repeat(12) {
            renderer.render(EngineAudioFrame(rpm = profile.limiterRpm, throttle = 1.0), ShortArray(1_920), gain = 1.0)
        }

        val diagnostics = renderer.diagnostics()
        assertTrue(totalNonZero > 80_000)
        assertEquals(profile.layers.size, diagnostics.loadedLoops)
        assertEquals(profile.limiterRpm, diagnostics.targetRpm.toDouble(), 2.0)
        assertEquals(profile.limiterRpm, diagnostics.renderRpm.toDouble(), 10.0)
        assertTrue(diagnostics.framesRendered > 90_000)
        assertTrue(diagnostics.loopWraps > 0)
        assertTrue(diagnostics.activeLayers != "none")
        assertTrue(diagnostics.peak in 0.01..1.0)
        assertEquals(0L, diagnostics.overRangeSamples)
    }

    @Test
    fun rendererUsesProfileRpmWithoutAxisRemapping() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(9_600)

        repeat(8) {
            renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.5), output, gain = 0.5)
        }

        assertEquals(4_000.0, renderer.diagnostics().targetRpm.toDouble(), 1.0)
        assertEquals(4_000.0, renderer.diagnostics().renderRpm.toDouble(), 8.0)
    }

    @Test
    fun rendererDoesNotCollapseTheStereoProgramToMono() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(12) {
            renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 1.0), output, gain = 0.66)
        }

        assertTrue((output.indices step 2).any { output[it] != output[it + 1] })
    }

    @Test
    fun effectMaskControlsTransmissionAndShiftEventsWithoutASecondAudioPath() {
        val enabled = SampleEffectControls.transmission.bit or SampleEffectControls.gearChanges.bit
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(10) {
            renderer.render(
                EngineAudioFrame(rpm = 4_500.0, throttle = 0.6, enabledEffectMask = enabled),
                output,
                gain = 0.6,
            )
        }
        renderer.render(
            EngineAudioFrame(
                rpm = 7_800.0,
                throttle = 1.0,
                enabledEffectMask = enabled,
                shiftSerial = 1,
                shiftDirection = 1,
            ),
            output,
            gain = 0.6,
        )
        repeat(9) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_000.0,
                    throttle = 0.7,
                    enabledEffectMask = enabled,
                    shiftSerial = 1,
                    shiftDirection = 1,
                ),
                output,
                gain = 0.6,
            )
        }

        val diagnostics = renderer.diagnostics()
        assertEquals(profile.effects.size, diagnostics.loadedEffects)
        assertEquals(1L, diagnostics.effectTriggers)
        assertTrue(diagnostics.activeEffects.contains("transmission_loop"))
    }

    @Test
    fun disabledEffectDoesNotTriggerOnShift() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)
        renderer.render(EngineAudioFrame(shiftSerial = 0), output, gain = 0.5)
        renderer.render(EngineAudioFrame(shiftSerial = 1, shiftDirection = 1), output, gain = 0.5)
        repeat(8) { renderer.render(EngineAudioFrame(shiftSerial = 1), output, gain = 0.5) }

        assertEquals(0L, renderer.diagnostics().effectTriggers)
        assertEquals("none", renderer.diagnostics().activeEffects)
    }

    @Test
    fun soloEffectsMutesEngineLoopsetAndKeepsCheckedEffectAudible() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)
        repeat(30) {
            renderer.render(
                EngineAudioFrame(rpm = 4_500.0, throttle = 0.6, soloEffects = true),
                output,
                gain = 0.7,
            )
        }
        assertTrue("unchecked solo must mute the continuous engine", output.maxOf { abs(it.toInt()) } <= 1)

        repeat(30) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 4_500.0,
                    throttle = 0.6,
                    enabledEffectMask = SampleEffectControls.transmission.bit,
                    soloEffects = true,
                ),
                output,
                gain = 0.7,
            )
        }
        assertTrue("checked transmission effect must remain audible in solo", output.maxOf { abs(it.toInt()) } > 20)
        assertTrue(renderer.diagnostics().activeEffects.contains("transmission_loop"))
        assertTrue(renderer.diagnostics().activeLayers.contains("effects solo"))
    }

    @Test
    fun incompleteBankIsRejectedInsteadOfUsingAnotherSoundSource() {
        val decoded = mapOf(
            profile.requiredAssets.first() to PcmLoopData(arrayOf(FloatArray(32) { 0.1f }), 48_000, 1),
        )

        val failure = runCatching { SampleEngineRenderer.fromDecoded(48_000, decoded, profile) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.startsWith("Missing ") == true)
    }

    @Test
    fun logicalChannelMappingPreservesStereoAndMirrorsTheProgram() {
        val stereo = shortArrayOf(10, 30, -20, 40)
        val surround = ShortArray(16)

        mapStereoAcrossChannels(stereo, surround, 8)

        assertEquals(listOf<Short>(10, 30, 20, 20, 10, 30, 10, 30), surround.take(8))
        assertEquals(listOf<Short>(-20, 40, 10, 10, -20, 40, -20, 40), surround.drop(8))
    }

    private fun strongestGain(rpm: Double, throttle: Double): Double =
        profile.layers.maxOf { it.gainAt(rpm, throttle) }

    private fun testBank(): Map<String, PcmLoopData> = profile.requiredAssets.associateWith { asset ->
        val frequency = 70.0 + abs(asset.hashCode() % 220)
        val left = FloatArray(2_048) { frame ->
            (sin(2.0 * PI * frequency * frame / 44_100.0) * 0.35).toFloat()
        }
        PcmLoopData(
            channelSamples = arrayOf(left, FloatArray(left.size) { -left[it] * 0.75f }),
            sampleRate = 44_100,
            loopStartFrame = 250,
            loopEndFrameExclusive = 1_900,
        )
    }

    private fun ShortArray.repeatFrames(times: Int): ShortArray =
        ShortArray(size * times) { this[it % size] }

    private fun pcm16Wav(
        sampleRate: Int,
        channels: Int,
        interleaved: ShortArray,
        loopStart: Int,
        loopEndInclusive: Int,
    ): ByteArray {
        val dataBytes = interleaved.size * 2
        val smplBytes = 60
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLe32(36 + dataBytes + 8 + smplBytes)
            write("WAVEfmt ".toByteArray())
            writeLe32(16)
            writeLe16(1)
            writeLe16(channels)
            writeLe32(sampleRate)
            writeLe32(sampleRate * channels * 2)
            writeLe16(channels * 2)
            writeLe16(16)
            write("data".toByteArray())
            writeLe32(dataBytes)
            interleaved.forEach { sample -> writeLe16(sample.toInt()) }
            write("smpl".toByteArray())
            writeLe32(smplBytes)
            repeat(7) { writeLe32(0) }
            writeLe32(1)
            writeLe32(0)
            writeLe32(0)
            writeLe32(0)
            writeLe32(loopStart)
            writeLe32(loopEndInclusive)
            writeLe32(0)
            writeLe32(0)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
