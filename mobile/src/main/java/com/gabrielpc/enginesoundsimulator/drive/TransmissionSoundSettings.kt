package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/**
 * Global attenuation applied before each car's own transmission trim.
 *
 * This intentionally mirrors the shift-override gain: it is a driver listening preference, not
 * an authored-bank change. A per-car mixer trim remains available as a second multiplier.
 */
data class TransmissionSoundSettings(val globalGain: Float = 0.5f)

internal class TransmissionSoundSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.TRANSMISSION_SOUND_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): TransmissionSoundSettings = TransmissionSoundSettings(
        globalGain = preferences.getFloat("global_gain", 0.5f).coerceIn(0.25f, 1.0f),
    )

    fun save(settings: TransmissionSoundSettings) {
        preferences.edit()
            .putFloat("global_gain", settings.globalGain.coerceIn(0.25f, 1.0f))
            .apply()
    }

    fun reset() {
        preferences.edit().clear().apply()
    }
}
