package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EngineSynthesizerTest {
    @Test
    fun synthesizerProducesAudibleChangingSignalWithoutSamples() {
        val synth = EngineSynthesizer(48_000)
        val idle = ShortArray(2_048)
        val loaded = ShortArray(2_048)

        synth.render(EngineAudioFrame(rpm = 950.0, throttle = 0.0, load = 0.0), idle)
        synth.render(EngineAudioFrame(rpm = 6_500.0, throttle = 1.0, load = 1.0), loaded)

        assertTrue(rms(idle) > 50.0)
        assertTrue(rms(loaded) > rms(idle))
        assertTrue(loaded.toSet().size > 100)
    }

    @Test
    fun monoProgramIsCopiedExactlyIntoEveryLogicalChannel() {
        val mono = shortArrayOf(120, -42, 3_000)
        val output = ShortArray(mono.size * 6)

        duplicateAcrossChannels(mono, output, 6)

        assertArrayEquals(shortArrayOf(120, 120, 120, 120, 120, 120), output.copyOfRange(0, 6))
        assertArrayEquals(shortArrayOf(-42, -42, -42, -42, -42, -42), output.copyOfRange(6, 12))
        assertArrayEquals(shortArrayOf(3_000, 3_000, 3_000, 3_000, 3_000, 3_000), output.copyOfRange(12, 18))
    }

    @Test
    fun renderIsSampleExactAcrossArbitraryBufferBoundariesWithLimiterActive() {
        val frame = EngineAudioFrame(
            rpm = 8_400.0,
            throttle = 0.92,
            load = 0.88,
            limiterActive = true,
        )
        val contiguous = ShortArray(4_096)
        val chunked = ShortArray(contiguous.size)

        EngineSynthesizer(48_000).render(frame, contiguous, gain = 0.61)
        val chunkedSynth = EngineSynthesizer(48_000)
        val chunkSizes = intArrayOf(17, 127, 509, 64, 997, 3, 251)
        var offset = 0
        var chunkIndex = 0
        while (offset < chunked.size) {
            val size = minOf(chunkSizes[chunkIndex % chunkSizes.size], chunked.size - offset)
            val chunk = ShortArray(size)
            chunkedSynth.render(frame, chunk, gain = 0.61)
            chunk.copyInto(chunked, destinationOffset = offset)
            offset += size
            chunkIndex += 1
        }

        assertArrayEquals(contiguous, chunked)
    }

    @Test
    fun focusMuteAndEngineDisableUseAnAudioRampInsteadOfHardZero() {
        val running = EngineAudioFrame(rpm = 5_800.0, throttle = 0.8, load = 0.9)

        val focusSynth = EngineSynthesizer(48_000)
        focusSynth.render(running, ShortArray(8_192), gain = 0.72)
        val focusMuted = ShortArray(4_096)
        focusSynth.render(running, focusMuted, gain = 0.0)
        assertTrue(rms(focusMuted.copyOfRange(0, 256)) > rms(focusMuted.copyOfRange(3_840, 4_096)) * 8.0)

        val enableSynth = EngineSynthesizer(48_000)
        enableSynth.render(running, ShortArray(8_192), gain = 0.72)
        val disabled = ShortArray(4_096)
        enableSynth.render(running.copy(enabled = false), disabled, gain = 0.72)
        assertTrue(rms(disabled.copyOfRange(0, 256)) > rms(disabled.copyOfRange(3_840, 4_096)) * 8.0)
    }

    private fun rms(samples: ShortArray): Double =
        sqrt(samples.map { it.toDouble() * it }.average())
}
