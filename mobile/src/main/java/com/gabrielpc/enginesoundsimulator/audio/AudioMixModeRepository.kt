package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** Persists audio controls that are independent of an FMOD event's authored mix. */
class AudioMixModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCoastOnlyEnabled(): Boolean = preferences.getBoolean(KEY_COAST_ONLY_ENABLED, false)

    fun setCoastOnlyEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_COAST_ONLY_ENABLED, enabled)
            .apply { if (enabled) putBoolean(KEY_LOAD_ONLY_ENABLED, false) }
            .apply()
    }

    /** Matches the desktop audio lab by holding engine/transmission events at authored full load. */
    fun isLoadOnlyEnabled(): Boolean = preferences.getBoolean(KEY_LOAD_ONLY_ENABLED, DEFAULT_LOAD_ONLY_ENABLED)

    fun setLoadOnlyEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_LOAD_ONLY_ENABLED, enabled)
            .apply { if (enabled) putBoolean(KEY_COAST_ONLY_ENABLED, false) }
            .apply()
    }

    fun isManualShiftModeEnabled(): Boolean {
        return preferences.getBoolean(KEY_MANUAL_SHIFT_MODE_ENABLED, DEFAULT_MANUAL_SHIFT_MODE_ENABLED)
    }

    fun setManualShiftModeEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_MANUAL_SHIFT_MODE_ENABLED, enabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "audio_experiments"
        const val KEY_COAST_ONLY_ENABLED = "fmod_coast_only_enabled"
        const val KEY_LOAD_ONLY_ENABLED = "fmod_load_only_enabled"
        const val KEY_MANUAL_SHIFT_MODE_ENABLED = "manual_shift_mode_enabled"
        const val DEFAULT_LOAD_ONLY_ENABLED = false
        const val DEFAULT_MANUAL_SHIFT_MODE_ENABLED = false
    }
}
