package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
/** Persists optional sample-engine effects controls. The renderer always uses the load-only program. */
class AudioMixModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isPopsAndBangsEnabled(): Boolean {
        return preferences.getBoolean(KEY_POPS_AND_BANGS_ENABLED, DEFAULT_POPS_AND_BANGS_ENABLED)
    }

    fun setPopsAndBangsEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_POPS_AND_BANGS_ENABLED, enabled)
            .apply()
    }

    fun isSharedShiftSoundsEnabled(): Boolean {
        return preferences.getBoolean(KEY_SHARED_SHIFT_SOUNDS_ENABLED, DEFAULT_SHARED_SHIFT_SOUNDS_ENABLED)
    }

    fun setSharedShiftSoundsEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SHARED_SHIFT_SOUNDS_ENABLED, enabled)
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
        const val KEY_POPS_AND_BANGS_ENABLED = "pops_and_bangs_enabled"
        const val KEY_SHARED_SHIFT_SOUNDS_ENABLED = "shared_shift_sounds_enabled"
        const val KEY_MANUAL_SHIFT_MODE_ENABLED = "manual_shift_mode_enabled"
        const val DEFAULT_POPS_AND_BANGS_ENABLED = false
        const val DEFAULT_SHARED_SHIFT_SOUNDS_ENABLED = false
        const val DEFAULT_MANUAL_SHIFT_MODE_ENABLED = false
    }
}
