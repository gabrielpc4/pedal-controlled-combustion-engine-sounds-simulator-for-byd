package com.gabrielpc.enginesoundsimulator.audio

internal enum class BufferAdjustment(val diagnosticLabel: String) {
    NONE("none"),
    GROW_UNDERRUN("underrun"),
    GROW_LOW_QUEUE("low_queue"),
    SHRINK_CLEAN("clean_window"),
}

/** Allocation-free adaptive target for a streaming 48 kHz output buffer. */
internal class AdaptiveAudioBuffer(
    private val sampleRate: Int = OUTPUT_SAMPLE_RATE,
) {
    var targetMilliseconds: Int = INITIAL_BUFFER_MS
        private set
    var adjustmentCount: Int = 0
        private set
    var lastAdjustment: BufferAdjustment = BufferAdjustment.NONE
        private set

    private var observedUnderruns = 0
    private var lastStressNanos = UNSET_TIME
    private var lastAdjustmentNanos = UNSET_TIME
    private var pendingGrowth = BufferAdjustment.NONE

    fun targetFrames(): Int = millisecondsToFrames(targetMilliseconds)

    fun observe(totalUnderruns: Int, queuedFrames: Int, nowNanos: Long): BufferAdjustment {
        val underrun = totalUnderruns > observedUnderruns
        observedUnderruns = totalUnderruns.coerceAtLeast(observedUnderruns)
        val lowQueue = queuedFrames >= 0 && queuedFrames < millisecondsToFrames(MIN_BUFFER_MS)
        if (lastStressNanos == UNSET_TIME) lastStressNanos = nowNanos
        if (underrun || lowQueue) {
            lastStressNanos = nowNanos
            pendingGrowth = if (underrun) BufferAdjustment.GROW_UNDERRUN else BufferAdjustment.GROW_LOW_QUEUE
        }

        if (
            pendingGrowth != BufferAdjustment.NONE &&
            targetMilliseconds < MAX_BUFFER_MS &&
            adjustmentAllowed(nowNanos)
        ) {
            targetMilliseconds = (targetMilliseconds + GROW_STEP_MS).coerceAtMost(MAX_BUFFER_MS)
            return recordAdjustment(pendingGrowth, nowNanos).also {
                pendingGrowth = BufferAdjustment.NONE
            }
        }
        if (targetMilliseconds >= MAX_BUFFER_MS) pendingGrowth = BufferAdjustment.NONE

        if (
            pendingGrowth == BufferAdjustment.NONE &&
            targetMilliseconds > MIN_BUFFER_MS &&
            elapsedAtLeast(lastStressNanos, nowNanos, CLEAN_WINDOW_NANOS) &&
            adjustmentAllowed(nowNanos)
        ) {
            targetMilliseconds = (targetMilliseconds - SHRINK_STEP_MS).coerceAtLeast(MIN_BUFFER_MS)
            return recordAdjustment(BufferAdjustment.SHRINK_CLEAN, nowNanos)
        }
        return BufferAdjustment.NONE
    }

    private fun millisecondsToFrames(milliseconds: Int): Int =
        ((sampleRate.toLong() * milliseconds + 999L) / 1_000L).toInt()

    private fun adjustmentAllowed(nowNanos: Long): Boolean =
        lastAdjustmentNanos == UNSET_TIME || elapsedAtLeast(lastAdjustmentNanos, nowNanos, ADJUSTMENT_INTERVAL_NANOS)

    private fun recordAdjustment(adjustment: BufferAdjustment, nowNanos: Long): BufferAdjustment {
        lastAdjustment = adjustment
        lastAdjustmentNanos = nowNanos
        adjustmentCount += 1
        return adjustment
    }

    private fun elapsedAtLeast(startNanos: Long, nowNanos: Long, durationNanos: Long): Boolean =
        startNanos != UNSET_TIME && nowNanos - startNanos >= durationNanos

    private companion object {
        const val UNSET_TIME = Long.MIN_VALUE
        const val MIN_BUFFER_MS = 30
        const val MAX_BUFFER_MS = 80
        const val INITIAL_BUFFER_MS = 50
        const val GROW_STEP_MS = 10
        const val SHRINK_STEP_MS = 5
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val CLEAN_WINDOW_NANOS = 60_000L * NANOS_PER_MILLISECOND
        const val ADJUSTMENT_INTERVAL_NANOS = 60_000L * NANOS_PER_MILLISECOND
    }
}

/** Extends AudioTrack's wrapping unsigned 32-bit playback-head counter. */
internal class PlaybackHeadTracker {
    private var previousRaw = 0L
    private var wrapOffset = 0L

    fun update(playbackHeadPosition: Int): Long {
        val raw = playbackHeadPosition.toLong() and UINT_MASK
        if (raw < previousRaw && previousRaw - raw > WRAP_THRESHOLD) {
            wrapOffset += UINT_RANGE
        }
        previousRaw = raw
        return wrapOffset + raw
    }

    private companion object {
        const val UINT_MASK = 0xffff_ffffL
        const val UINT_RANGE = 0x1_0000_0000L
        const val WRAP_THRESHOLD = 0x8000_0000L
    }
}

/** Fixed-size histogram written by the audio thread and summarized by diagnostic callers. */
internal class RealtimeRenderHistogram {
    private val buckets = LongArray(BUCKET_COUNT)
    @Volatile private var samples = 0L
    @Volatile var maximumNanos = 0L
        private set
    val sampleCount: Long
        get() = samples

    fun record(durationNanos: Long) {
        val bounded = durationNanos.coerceAtLeast(0L)
        val index = (bounded / BUCKET_WIDTH_NANOS).coerceAtMost((BUCKET_COUNT - 1).toLong()).toInt()
        buckets[index] += 1L
        samples += 1L
        if (bounded > maximumNanos) maximumNanos = bounded
    }

    fun percentile99Micros(): Int {
        val index = percentile99BucketIndex()
        if (index < 0) return 0
        return ((index + 1L) * BUCKET_WIDTH_NANOS / NANOS_PER_MICROSECOND).toInt()
    }

    /** Lower edge paired with [percentile99Micros]' conservative fixed-bucket upper edge. */
    fun percentile99LowerBoundMicros(): Int {
        val index = percentile99BucketIndex()
        if (index < 0) return 0
        return (index * BUCKET_WIDTH_NANOS / NANOS_PER_MICROSECOND).toInt()
    }

    private fun percentile99BucketIndex(): Int {
        val count = samples
        if (count <= 0L) return -1
        val threshold = ((count * 99L) + 99L) / 100L
        var cumulative = 0L
        var index = 0
        while (index < buckets.size) {
            cumulative += buckets[index]
            if (cumulative >= threshold) return index
            index += 1
        }
        return buckets.lastIndex
    }

    private companion object {
        // 50 us resolution avoids a 100 us bucket alone deciding the 1.5 ms/256-frame gate.
        // The fixed 6.4 ms p99 range and raw maximum remain unchanged and allocation-free.
        const val BUCKET_COUNT = 128
        const val BUCKET_WIDTH_NANOS = 50_000L
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}

/**
 * Separates ordinary render cost from the deliberately more expensive, 30 ms pack-swap
 * crossfade while retaining the lifetime aggregate. All three histograms are fixed-size and are
 * allocated with the AudioTrack runtime, never by the writer loop.
 */
internal class RealtimeRenderTiming {
    val overall = RealtimeRenderHistogram()
    val steady = RealtimeRenderHistogram()
    val transition = RealtimeRenderHistogram()

    fun record(durationNanos: Long, transitionBurst: Boolean) {
        overall.record(durationNanos)
        if (transitionBurst) transition.record(durationNanos) else steady.record(durationNanos)
    }
}
