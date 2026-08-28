package com.gabrielpc.enginesoundsimulator.drive

/**
 * Main-thread service lifecycle state for a user-requested stop racing an explicit reopen.
 *
 * Once a stop begins, the active controller must always reach [completeTeardown]. An explicit
 * start can only request a brand-new controller after that close; it can never cancel the fade or
 * make the old controller audible again.
 */
internal class DriveRuntimeStopRestartCoordinator {
    private var phase = Phase.ACTIVE
    private var restartQueued = false

    @Synchronized
    fun requestStop(): Boolean = when (phase) {
        Phase.ACTIVE -> {
            phase = Phase.FADING
            restartQueued = false
            true
        }

        Phase.FADING, Phase.TEARING_DOWN -> {
            // A second Stop wins over an explicit start that was queued during teardown.
            restartQueued = false
            false
        }

        Phase.STOPPED -> false
    }

    @Synchronized
    fun requestExplicitStart(): ExplicitStartDisposition = when (phase) {
        Phase.ACTIVE -> ExplicitStartDisposition.START_NOW
        Phase.FADING, Phase.TEARING_DOWN -> {
            restartQueued = true
            ExplicitStartDisposition.QUEUED_AFTER_TEARDOWN
        }

        Phase.STOPPED -> {
            phase = Phase.ACTIVE
            ExplicitStartDisposition.START_NOW
        }
    }

    @Synchronized
    fun beginTeardown(): Boolean {
        if (phase != Phase.FADING) return false
        phase = Phase.TEARING_DOWN
        return true
    }

    @Synchronized
    fun completeTeardown(externalRestartRequested: Boolean = false): TeardownDisposition {
        check(phase == Phase.TEARING_DOWN) { "Drive runtime teardown completed outside teardown" }
        return if (restartQueued || externalRestartRequested) {
            restartQueued = false
            phase = Phase.ACTIVE
            TeardownDisposition.START_NEW_RUNTIME
        } else {
            phase = Phase.STOPPED
            TeardownDisposition.STOP_SERVICE
        }
    }

    @Synchronized
    fun isStopping(): Boolean = phase == Phase.FADING || phase == Phase.TEARING_DOWN

    private enum class Phase { ACTIVE, FADING, TEARING_DOWN, STOPPED }
}

internal enum class ExplicitStartDisposition {
    START_NOW,
    QUEUED_AFTER_TEARDOWN,
}

internal enum class TeardownDisposition {
    START_NEW_RUNTIME,
    STOP_SERVICE,
}

/** Allocation-light state that the service may read while Compose is hidden. */
internal interface DriveRuntimePrimitiveState {
    fun selectedCarDisplayName(): String
    fun shutdownFadeTimeConstantMillis(): Double
    fun uiSnapshotBuildCount(): Long
}

/**
 * Compile-time barrier between background service bookkeeping and expensive DriveUiSnapshot
 * construction. Tests use the snapshot counter to prove these reads never enter snapshot().
 */
internal object DriveRuntimeBackgroundReadPolicy {
    fun notificationCarName(runtime: DriveRuntimePrimitiveState?, fallback: String): String =
        runtime?.selectedCarDisplayName()?.takeIf(String::isNotBlank) ?: fallback

    fun shutdownFadeMillis(
        runtime: DriveRuntimePrimitiveState?,
        minimumMillis: Long,
        maximumMillis: Long,
        timeConstants: Double,
    ): Long {
        require(minimumMillis in 1L..maximumMillis)
        require(timeConstants > 0.0)
        val timeConstantMillis = runtime?.shutdownFadeTimeConstantMillis()
            ?: return minimumMillis
        if (!timeConstantMillis.isFinite() || timeConstantMillis <= 0.0) return minimumMillis
        return (timeConstantMillis * timeConstants).toLong().coerceIn(minimumMillis, maximumMillis)
    }
}

/**
 * Pure resource-ownership rule for persisted mute. Startup mute is deliberately stronger than
 * the phase-preserving mute used after playback has begun: it opens no AudioTrack and queues no
 * selected-family decode.
 */
internal object DriveAudioResourcePolicy {
    fun shouldStartOnControllerStart(soundEnabled: Boolean): Boolean = soundEnabled

    fun shouldPrepareSelectedProfile(audioRuntimeStarted: Boolean): Boolean = audioRuntimeStarted
}
