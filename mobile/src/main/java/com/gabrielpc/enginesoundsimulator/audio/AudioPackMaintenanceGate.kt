package com.gabrielpc.enginesoundsimulator.audio

import java.util.concurrent.atomic.AtomicBoolean

/** Serializes destructive pack maintenance without blocking an install worker that is still retiring. */
internal class AudioPackMaintenanceGate {
    private val active = AtomicBoolean(false)

    fun <T> runExclusive(activeInstallJob: Boolean, operation: () -> T): T {
        check(!activeInstallJob) { "Cannot clean audio packs while one is being installed" }
        check(active.compareAndSet(false, true)) { "Audio-pack cleanup is already active" }
        try {
            return operation()
        } finally {
            active.set(false)
        }
    }

    fun isActive(): Boolean = active.get()
}
