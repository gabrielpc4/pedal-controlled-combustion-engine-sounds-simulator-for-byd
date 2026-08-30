package com.gabrielpc.enginesoundsimulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCpuUsageTest {
    @Test
    fun formatCpuLabelRoundsToWholePercent() {
        assertEquals("12% CPU", AppCpuUsage.formatCpuLabel(12.4))
    }

    @Test
    fun formatCpuLabelCapsAtOneHundred() {
        assertEquals("100% CPU", AppCpuUsage.formatCpuLabel(148.2))
    }

    @Test
    fun hottestThreadCpuPercentUsesBusiestThreadOnly() {
        val percent = AppCpuUsage.hottestThreadCpuPercent(
            deltaWallMs = 1_000L,
            previousJiffies = mapOf(
                1 to 0L,
                2 to 0L,
            ),
            currentJiffies = mapOf(
                1 to 50L,
                2 to 100L,
            ),
            clockTicksPerSecond = 100.0,
        )

        assertEquals(100.0, percent!!, 0.0)
    }

    @Test
    fun hottestThreadCpuPercentIgnoresIdleThreads() {
        val percent = AppCpuUsage.hottestThreadCpuPercent(
            deltaWallMs = 1_000L,
            previousJiffies = mapOf(
                1 to 100L,
                2 to 200L,
            ),
            currentJiffies = mapOf(
                1 to 120L,
                2 to 250L,
            ),
            clockTicksPerSecond = 100.0,
        )

        assertEquals(50.0, percent!!, 0.0)
    }

    @Test
    fun hottestThreadCpuPercentReturnsNullForInvalidWindow() {
        assertNull(
            AppCpuUsage.hottestThreadCpuPercent(
                deltaWallMs = 0L,
                previousJiffies = emptyMap(),
                currentJiffies = emptyMap(),
                clockTicksPerSecond = 100.0,
            ),
        )
    }
}
