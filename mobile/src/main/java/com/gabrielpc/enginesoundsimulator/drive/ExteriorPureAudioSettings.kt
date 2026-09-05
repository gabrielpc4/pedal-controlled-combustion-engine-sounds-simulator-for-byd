package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/**
 * Global attenuation applied to engine host gain while exterior pure audio is active.
 *
 * This mirrors the transmission and shift global trims: it is a driver listening preference,
 * not an authored-bank change. The per-car ENGINE preset gain remains an independent multiplier.
 */
data class ExteriorPureAudioSettings(val globalGain: Float = 0.5f)

internal class ExteriorPureAudioSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.EXTERIOR_PURE_AUDIO_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): ExteriorPureAudioSettings = ExteriorPureAudioSettings(
        globalGain = preferences.getFloat("global_gain", 0.5f).coerceIn(0.25f, 1.0f),
    )

    fun save(settings: ExteriorPureAudioSettings) {
        preferences.edit()
            .putFloat("global_gain", settings.globalGain.coerceIn(0.25f, 1.0f))
            .apply()
    }

    fun reset() {
        preferences.edit().clear().apply()
    }
}
