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
    @Test
    fun manifestContainsAContinuousEngineOnlyCabinProfile() {
        assertEquals(23, SampleEngineManifest.loops.size)
        assertEquals(23, SampleEngineManifest.requiredAssets.size)
        assertTrue(SampleEngineManifest.loops.none { "limiter" in it.id || "turbo" in it.id })

        for (rpm in 200..8_000 step 10) {
            assertTrue("load coverage missing at $rpm", hasCoverage(SampleTrack.LOAD, rpm.toDouble()))
            assertTrue("coast coverage missing at $rpm", hasCoverage(SampleTrack.COAST, rpm.toDouble()))
        }
    }

    @Test
    fun throttleAutomationMovesFromCoastToLoadWithoutADeadZone() {
        val idleLoad = automationDecibels(SampleEngineManifest.loadThrottleCurve, 0.0)
        val fullLoad = automationDecibels(SampleEngineManifest.loadThrottleCurve, 1.0)
        val idleCoast = automationDecibels(SampleEngineManifest.coastThrottleCurve, 0.0)
        val fullCoast = automationDecibels(SampleEngineManifest.coastThrottleCurve, 1.0)

        assertTrue(fullLoad > idleLoad + 25.0)
        assertTrue(idleCoast > fullCoast + 25.0)
        for (step in 0..100) {
            val throttle = step / 100.0
            val strongest = maxOf(
                automationDecibels(SampleEngineManifest.loadThrottleCurve, throttle),
                automationDecibels(SampleEngineManifest.coastThrottleCurve, throttle),
            )
            assertTrue("both engine tracks are inaudible at $throttle", strongest > -30.0)
        }
    }

    @Test
    fun wavDecoderDownmixesStereoPcm16() {
        val wav = pcm16Wav(
            sampleRate = 44_100,
            channels = 2,
            interleaved = shortArrayOf(12_000, -4_000, -16_000, 8_000).repeatFrames(20),
        )

        val decoded = WavPcmDecoder.decode(ByteArrayInputStream(wav))

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(2, decoded.sourceChannels)
        assertEquals(40, decoded.monoSamples.size)
        assertEquals(4_000.0 / 32_768.0, decoded.monoSamples[0].toDouble(), 0.00001)
        assertEquals(-4_000.0 / 32_768.0, decoded.monoSamples[1].toDouble(), 0.00001)
    }

    @Test
    fun codeDrivenSweepKeepsEveryRpmAudibleAndReportsRuntimeTelemetry() {
        val decoded = SampleEngineManifest.requiredAssets.associateWith { asset ->
            val frequency = 70.0 + abs(asset.hashCode() % 220)
            PcmLoopData(
                monoSamples = FloatArray(2_048) { frame ->
                    (sin(2.0 * PI * frequency * frame / 44_100.0) * 0.35).toFloat()
                },
                sampleRate = 44_100,
                sourceChannels = 1,
            )
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded)
        var totalNonZero = 0

        for (step in 0..80) {
            val rpm = 950.0 + (8_600.0 - 950.0) * step / 80.0
            val throttle = when {
                step < 15 -> 0.0
                step < 45 -> (step - 15) / 30.0
                else -> 1.0
            }
            val output = ShortArray(960)
            renderer.render(
                EngineAudioFrame(rpm = rpm, throttle = throttle, load = throttle, redlineRpm = 8_600.0),
                output,
                gain = 0.66,
            )
            val nonZero = output.count { it != 0.toShort() }
            assertTrue("silent render at rpm=$rpm throttle=$throttle", nonZero > output.size * 0.8)
            totalNonZero += nonZero
        }

        val diagnostics = renderer.diagnostics()
        assertTrue(totalNonZero > 70_000)
        assertEquals(23, diagnostics.loadedLoops)
        assertTrue(diagnostics.mappedAudioRpm >= 7_800)
        assertTrue(diagnostics.framesRendered > 70_000)
        assertTrue(diagnostics.loopWraps > 0)
        assertTrue(diagnostics.activeLayers != "none")
        assertTrue(diagnostics.peak in 0.01..1.0)
    }

    private fun hasCoverage(track: SampleTrack, rpm: Double): Boolean =
        SampleEngineManifest.loops.any { it.track == track && rpm in it.startRpm..it.endRpm }

    private fun ShortArray.repeatFrames(times: Int): ShortArray =
        ShortArray(size * times) { this[it % size] }

    private fun pcm16Wav(sampleRate: Int, channels: Int, interleaved: ShortArray): ByteArray {
        val dataBytes = interleaved.size * 2
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLe32(36 + dataBytes)
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
