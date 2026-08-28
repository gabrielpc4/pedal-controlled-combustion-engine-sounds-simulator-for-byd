package com.gabrielpc.enginesoundsimulator

internal enum class DeferredCatalogEventKind {
    PACK_IMPORT_STARTED,
    PACK_IMPORT_SUCCEEDED,
    CATALOG_IMPORT_STARTED,
    CATALOG_IMPORT_SUCCEEDED,
    FAILURE,
}

/**
 * A small, Android-free event retained while Compose is suspended. It deliberately contains no
 * catalog snapshot, bitmap, formatted meter, or other presentation model.
 */
internal data class DeferredCatalogEvent(
    val kind: DeferredCatalogEventKind,
    val packCount: Int = 0,
    val failureMessage: String? = null,
)

/** Android-free lifecycle gate for dashboard sampling and deferred catalog presentation. */
internal class DriveUiLifecycleGate {
    var visible: Boolean = false
        private set

    var connected: Boolean = false
        private set

    private var catalogDirty = true
    private var deferredCatalogEvent: DeferredCatalogEvent? = null

    val shouldSample: Boolean
        get() = visible && connected

    fun onActivityStarted() {
        visible = true
    }

    fun onActivityStopped() {
        visible = false
    }

    fun onRuntimeConnected() {
        connected = true
        // A newly connected runtime may have been restored or changed while this Activity was
        // absent. Force one fresh catalog read, but only after the Activity is visible.
        catalogDirty = true
    }

    fun onRuntimeDisconnected() {
        connected = false
    }

    fun recordCatalogEvent(event: DeferredCatalogEvent, catalogChanged: Boolean) {
        deferredCatalogEvent = event
        if (catalogChanged) catalogDirty = true
    }

    /** Returns true exactly once per dirty generation, and never while hidden/disconnected. */
    fun takeCatalogRefreshRequest(): Boolean {
        if (!shouldSample || !catalogDirty) return false
        catalogDirty = false
        return true
    }

    /** UI text is rendered from this primitive event only after the Activity becomes visible. */
    fun takeCatalogEvent(): DeferredCatalogEvent? {
        if (!shouldSample) return null
        return deferredCatalogEvent.also { deferredCatalogEvent = null }
    }
}
