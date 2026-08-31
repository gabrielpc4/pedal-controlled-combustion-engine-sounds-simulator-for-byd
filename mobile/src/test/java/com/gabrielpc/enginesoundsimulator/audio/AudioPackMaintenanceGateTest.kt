package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioPackMaintenanceGateTest {
    @Test
    fun canceledInstallStillRetiringDoesNotAcquireMaintenanceGate() {
        val gate = AudioPackMaintenanceGate()

        assertThrows(IllegalStateException::class.java) {
            gate.runExclusive(activeInstallJob = true) {
                error("cleanup must not start while the canceled job is retiring")
            }
        }

        assertFalse(gate.isActive())
        assertEquals("cleaned", gate.runExclusive(activeInstallJob = false) { "cleaned" })
        assertFalse(gate.isActive())
    }

    @Test
    fun cleanupFailureReleasesMaintenanceGate() {
        val gate = AudioPackMaintenanceGate()

        assertThrows(IllegalArgumentException::class.java) {
            gate.runExclusive(activeInstallJob = false) {
                throw IllegalArgumentException("injected cleanup failure")
            }
        }

        assertFalse(gate.isActive())
        assertEquals("retried", gate.runExclusive(activeInstallJob = false) { "retried" })
    }
}
