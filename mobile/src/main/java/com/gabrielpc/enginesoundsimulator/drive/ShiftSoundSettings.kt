package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

data class ShiftSoundSettings(val overrideEnabled: Boolean = false)

internal class ShiftSoundSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SHIFT_SOUND_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): ShiftSoundSettings = ShiftSoundSettings(
        overrideEnabled = preferences.getBoolean("override_enabled", false),
    )

    fun save(settings: ShiftSoundSettings) {
        preferences.edit().putBoolean("override_enabled", settings.overrideEnabled).apply()
    }

    fun reset() { preferences.edit().clear().apply() }
}
