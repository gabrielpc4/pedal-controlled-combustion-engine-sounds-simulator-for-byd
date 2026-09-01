package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Persists global driving-mode choices. Per-car effect settings live in [CarEffectModeRepository]. */
class AudioMixModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.AUDIO_EXPERIMENTS,
        Context.MODE_PRIVATE,
    )

    fun isManualShiftModeEnabled(): Boolean {
        return preferences.getBoolean(KEY_MANUAL_SHIFT_MODE_ENABLED, DEFAULT_MANUAL_SHIFT_MODE_ENABLED)
    }

    fun setManualShiftModeEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_MANUAL_SHIFT_MODE_ENABLED, enabled)
            .commit()
    }

    private companion object {
        const val KEY_MANUAL_SHIFT_MODE_ENABLED = "manual_shift_mode_enabled"
        const val DEFAULT_MANUAL_SHIFT_MODE_ENABLED = false
    }
}
