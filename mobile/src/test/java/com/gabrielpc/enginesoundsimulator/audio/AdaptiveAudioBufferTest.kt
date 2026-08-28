package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveAudioBufferTest {
    @Test
    fun startsAtFiftyMillisecondsAndStaysWithinThirtyToEighty() {
        val buffer = AdaptiveAudioBuffer(48_000)

        assertEquals(50, buffer.targetMilliseconds)
        assertEquals(2_400, buffer.targetFrames())

        var now = 0L
        repeat(8) {
            buffer.observe(totalUnderruns = it + 1, queuedFrames = 0, nowNanos = now)
            now += MINUTE_NANOS
        }
        assertEquals(80, buffer.targetMilliseconds)

        repeat(12) {
            buffer.observe(totalUnderruns = 8, queuedFrames = 2_400, nowNanos = now)
            now += MINUTE_NANOS
        }
        assertTrue(buffer.targetMilliseconds in 30..80)
        assertEquals(30, buffer.targetMilliseconds)
    }

    @Test
    fun growsTenMillisecondsForAnUnderrunAndRateLimitsChanges() {
        val buffer = AdaptiveAudioBuffer(48_000)

        assertEquals(BufferAdjustment.GROW_UNDERRUN, buffer.observe(1, 2_400, 0L))
        assertEquals(60, buffer.targetMilliseconds)
        assertEquals(BufferAdjustment.NONE, buffer.observe(2, 2_400, 1_000_000_000L))
        assertEquals(60, buffer.targetMilliseconds)
        assertEquals(BufferAdjustment.GROW_UNDERRUN, buffer.observe(2, 2_400, MINUTE_NANOS))
        assertEquals(70, buffer.targetMilliseconds)
    }

    @Test
    fun lowQueueGrowsAndCleanMinuteShrinksFiveMilliseconds() {
        val buffer = AdaptiveAudioBuffer(48_000)

        assertEquals(BufferAdjustment.GROW_LOW_QUEUE, buffer.observe(0, 1_439, 0L))
        assertEquals(60, buffer.targetMilliseconds)
        assertEquals(BufferAdjustment.NONE, buffer.observe(0, 2_400, MINUTE_NANOS - 1L))
        assertEquals(BufferAdjustment.SHRINK_CLEAN, buffer.observe(0, 2_400, MINUTE_NANOS))
        assertEquals(55, buffer.targetMilliseconds)
    }

    @Test
    fun playbackHeadTrackerExtendsUnsignedWrap() {
        val tracker = PlaybackHeadTracker()

        assertEquals(0x7fff_ffffL, tracker.update(0x7fff_ffff))
        assertEquals(0xffff_fff0L, tracker.update(0xffff_fff0.toInt()))
        assertEquals(0x1_0000_0010L, tracker.update(0x10))
    }

    @Test
    fun renderHistogramReportsBoundedP99WithoutPerSampleStorage() {
        val histogram = RealtimeRenderHistogram()
        repeat(99) { histogram.record(140_000L) }
        histogram.record(2_400_000L)

        assertEquals(150, histogram.percentile99Micros())
        assertEquals(100, histogram.percentile99LowerBoundMicros())
        assertEquals(100L, histogram.sampleCount)
        assertEquals(2_400_000L, histogram.maximumNanos)
    }

    @Test
    fun renderTimingRetainsOverallCostAndSeparatesPackSwapBursts() {
        val timing = RealtimeRenderTiming()
        repeat(99) { timing.record(700_000L, transitionBurst = false) }
        timing.record(3_100_000L, transitionBurst = true)

        // Histograms expose the 50 us lower/upper interval and retain all sample counts.
        assertEquals(750, timing.overall.percentile99Micros())
        assertEquals(700, timing.overall.percentile99LowerBoundMicros())
        assertEquals(100L, timing.overall.sampleCount)
        assertEquals(3_100_000L, timing.overall.maximumNanos)
        assertEquals(750, timing.steady.percentile99Micros())
        assertEquals(99L, timing.steady.sampleCount)
        assertEquals(700_000L, timing.steady.maximumNanos)
        assertEquals(3_150, timing.transition.percentile99Micros())
        assertEquals(1L, timing.transition.sampleCount)
        assertEquals(3_100_000L, timing.transition.maximumNanos)
    }

    private companion object {
        const val MINUTE_NANOS = 60_000_000_000L
    }
}
