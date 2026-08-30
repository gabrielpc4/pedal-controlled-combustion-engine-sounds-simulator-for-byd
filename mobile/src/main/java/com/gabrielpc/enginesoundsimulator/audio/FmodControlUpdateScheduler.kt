package com.gabrielpc.enginesoundsimulator.audio

/**
 * Gates native FMOD submissions to one control state per period.
 *
 * It intentionally drops missed deadlines instead of catching them up in a burst: a late
 * continuous state is less audible than two or more parameter changes in one mixer block.
 */
internal class FmodControlUpdateScheduler(
    private val periodNanos: Long,
) {
    private var nextSubmissionNanos = 0L

    fun reset(nowNanos: Long) {
        nextSubmissionNanos = nowNanos
    }

    fun remainingUntilSubmission(nowNanos: Long): Long =
        (nextSubmissionNanos - nowNanos).coerceAtLeast(0L)

    fun recordCompletedSubmission(completedAtNanos: Long) {
        nextSubmissionNanos = completedAtNanos + periodNanos
    }
}
