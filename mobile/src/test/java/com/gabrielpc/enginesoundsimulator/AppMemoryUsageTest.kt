package com.gabrielpc.enginesoundsimulator

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMemoryUsageTest {
    @Test
    fun formatPssLabelUsesMegabytesForTypicalUsage() {
        assertEquals("142 MB", AppMemoryUsage.formatPssLabel(142 * 1024))
    }

    @Test
    fun formatPssLabelUsesGigabytesForLargeUsage() {
        assertEquals("1.5 GB", AppMemoryUsage.formatPssLabel((1.5 * 1024 * 1024).toInt()))
    }

    @Test
    fun formatAvailableLabelUsesMegabytesForTypicalFreeMemory() {
        assertEquals("512 MB left", AppMemoryUsage.formatAvailableLabel(512L * 1024L * 1024L))
    }

    @Test
    fun formatAvailableLabelUsesGigabytesForLargeFreeMemory() {
        assertEquals("2.4 GB left", AppMemoryUsage.formatAvailableLabel((2.4 * 1024 * 1024 * 1024).toLong()))
    }
}
