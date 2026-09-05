package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Keeps FMOD engine/transmission events at their authored full-load throttle endpoint. */
internal class ForceFullLoadAudioThrottleRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.FORCE_FULL_LOAD_AUDIO_THROTTLE,
        Context.MODE_PRIVATE,
    )

    fun load(): Boolean = preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun save(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }

    fun reset() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = true
    }
}
