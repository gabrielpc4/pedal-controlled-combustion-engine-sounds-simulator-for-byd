package com.gabrielpc.enginesoundsimulator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveUiLifecycleGateTest {
    @Test
    fun `sampler runs only while activity is visible and service is connected`() {
        val gate = DriveUiLifecycleGate()

        assertFalse(gate.shouldSample)
        gate.onRuntimeConnected()
        assertFalse(gate.shouldSample)
        gate.onActivityStarted()
        assertTrue(gate.shouldSample)

        // Home/app switch stops UI work without changing the runtime connection contract.
        gate.onActivityStopped()
        assertFalse(gate.shouldSample)

        gate.onActivityStarted()
        assertTrue(gate.shouldSample)
        gate.onRuntimeDisconnected()
        assertFalse(gate.shouldSample)
    }

    @Test
    fun `catalog callback stays primitive and dirty until visible runtime reconnects`() {
        val gate = DriveUiLifecycleGate()
        gate.onRuntimeConnected()
        gate.recordCatalogEvent(
            DeferredCatalogEvent(DeferredCatalogEventKind.PACK_IMPORT_SUCCEEDED, packCount = 2),
            catalogChanged = true,
        )

        assertFalse(gate.takeCatalogRefreshRequest())
        assertNull(gate.takeCatalogEvent())

        gate.onActivityStarted()
        assertTrue(gate.takeCatalogRefreshRequest())
        assertFalse(gate.takeCatalogRefreshRequest())
        assertEquals(DeferredCatalogEventKind.PACK_IMPORT_SUCCEEDED, gate.takeCatalogEvent()?.kind)
        assertNull(gate.takeCatalogEvent())
    }

    @Test
    fun `new runtime connection forces one visible catalog refresh`() {
        val gate = DriveUiLifecycleGate()
        gate.onActivityStarted()
        gate.onRuntimeConnected()
        assertTrue(gate.takeCatalogRefreshRequest())

        gate.onRuntimeDisconnected()
        gate.onRuntimeConnected()
        assertTrue(gate.takeCatalogRefreshRequest())
        assertFalse(gate.takeCatalogRefreshRequest())
    }
}
