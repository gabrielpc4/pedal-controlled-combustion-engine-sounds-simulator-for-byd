package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmodControlUpdateSchedulerTest {
    @Test
    fun schedulerDropsMissedDeadlinesInsteadOfSubmittingThemAsABurst() {
        val scheduler = FmodControlUpdateScheduler(PERIOD_NANOS)
        scheduler.reset(0L)
        val submissions = mutableListOf<Long>()

        // This trace covers both an early wakeup and a 4.9 ms late wakeup. The former must wait;
        // the latter may submit once, but must not immediately replay the missed 5 ms deadline.
        listOf(0L, 2_400_000L, 2_500_000L, 7_400_000L, 7_500_000L, 9_899_999L, 9_900_000L)
            .forEach { wakeNanos ->
                if (scheduler.remainingUntilSubmission(wakeNanos) == 0L) {
                    submissions += wakeNanos
                    scheduler.recordCompletedSubmission(wakeNanos)
                }
            }

        assertEquals(listOf(0L, 2_500_000L, 7_400_000L, 9_900_000L), submissions)
        assertTrue(submissions.zipWithNext().all { (first, second) -> second - first >= PERIOD_NANOS })
    }

    @Test
    fun completedSubmissionStartsTheFullNextControlPeriod() {
        val scheduler = FmodControlUpdateScheduler(PERIOD_NANOS)
        scheduler.reset(0L)
        scheduler.recordCompletedSubmission(7_400_000L)

        assertEquals(PERIOD_NANOS, scheduler.remainingUntilSubmission(7_400_000L))
        assertEquals(1L, scheduler.remainingUntilSubmission(9_899_999L))
        assertEquals(0L, scheduler.remainingUntilSubmission(9_900_000L))
    }

    private companion object {
        const val PERIOD_NANOS = 2_500_000L
    }
}
