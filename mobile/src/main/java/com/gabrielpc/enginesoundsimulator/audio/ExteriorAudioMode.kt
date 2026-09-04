package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Controls the optional exterior listener neutralization without changing FMOD event content. */
internal class ExteriorAudioModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.EXTERIOR_AUDIO_MODE,
        Context.MODE_PRIVATE,
    )

    fun load(): Boolean = preferences.getBoolean(KEY_PURE_EXTERIOR, false)

    fun save(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PURE_EXTERIOR, enabled).apply()
    }

    fun reset() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_PURE_EXTERIOR = "pure_exterior"
    }
}
