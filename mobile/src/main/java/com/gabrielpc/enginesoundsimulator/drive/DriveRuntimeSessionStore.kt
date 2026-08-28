package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import androidx.core.content.edit

/**
 * Small persisted state used to distinguish a system restart from an explicit user/task stop.
 *
 * The controller's own repositories remain the source of truth for the selected car and tuning.
 * This store only answers whether Android is allowed to recreate the active driving session and
 * whether its user-controlled sound state should be restored.
 */
internal class DriveRuntimeSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun read(): DriveRuntimeSessionState = DriveRuntimeSessionState(
        sessionRequested = preferences.getBoolean(KEY_SESSION_REQUESTED, false),
        stoppedByUser = preferences.getBoolean(KEY_STOPPED_BY_USER, false),
        soundEnabled = preferences.getBoolean(KEY_SOUND_ENABLED, true),
    )

    @Synchronized
    fun recordExplicitStart(): DriveRuntimeSessionState = update {
        DriveRuntimeSessionPolicy.onExplicitStart(it)
    }

    @Synchronized
    fun recordUserStop(): DriveRuntimeSessionState = update {
        DriveRuntimeSessionPolicy.onUserStop(it)
    }

    @Synchronized
    fun recordSoundEnabled(enabled: Boolean): DriveRuntimeSessionState = update {
        it.copy(soundEnabled = enabled)
    }

    private fun update(
        transform: (DriveRuntimeSessionState) -> DriveRuntimeSessionState,
    ): DriveRuntimeSessionState {
        val previous = read()
        val next = transform(previous)
        if (next == previous) return previous
        // Lifecycle markers must reach disk before stopSelf/process teardown can race them.
        preferences.edit(commit = true) {
            putBoolean(KEY_SESSION_REQUESTED, next.sessionRequested)
            putBoolean(KEY_STOPPED_BY_USER, next.stoppedByUser)
            putBoolean(KEY_SOUND_ENABLED, next.soundEnabled)
        }
        return next
    }

    private companion object {
        const val PREFERENCES_NAME = "drive_runtime_session"
        const val KEY_SESSION_REQUESTED = "session_requested"
        const val KEY_STOPPED_BY_USER = "stopped_by_user"
        const val KEY_SOUND_ENABLED = "sound_enabled"
    }
}

internal data class DriveRuntimeSessionState(
    val sessionRequested: Boolean = false,
    val stoppedByUser: Boolean = false,
    val soundEnabled: Boolean = true,
)

/** Pure transition rules kept Android-free so sticky-restart behavior has fast unit coverage. */
internal object DriveRuntimeSessionPolicy {
    fun onExplicitStart(previous: DriveRuntimeSessionState): DriveRuntimeSessionState =
        previous.copy(sessionRequested = true, stoppedByUser = false)

    fun onUserStop(previous: DriveRuntimeSessionState): DriveRuntimeSessionState =
        previous.copy(sessionRequested = false, stoppedByUser = true)

    fun shouldRun(state: DriveRuntimeSessionState): Boolean =
        state.sessionRequested && !state.stoppedByUser

    /**
     * Start intents are authorized at dispatch time, before Android queues them. Delivery only
     * observes that persisted decision; it must never turn an older queued intent into a newer
     * authorization after task removal or notification Stop.
     */
    fun acceptExplicitStartDelivery(state: DriveRuntimeSessionState): Boolean = shouldRun(state)
}
