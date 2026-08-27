package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.BuildConfig

/** Runtime toggles for audio experiments. Compile-time default comes from BuildConfig. */
class AudioExperimentRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCoastOnlyFullGain(): Boolean {
        if (!preferences.contains(KEY_COAST_ONLY_FULL_GAIN)) {
            return BuildConfig.COAST_ONLY_FULL_GAIN_EXPERIMENT
        }
        return preferences.getBoolean(KEY_COAST_ONLY_FULL_GAIN, false)
    }

    fun setCoastOnlyFullGain(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_COAST_ONLY_FULL_GAIN, enabled).apply()
    }

    fun resetCoastOnlyFullGain() {
        preferences.edit().remove(KEY_COAST_ONLY_FULL_GAIN).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "audio_experiments"
        const val KEY_COAST_ONLY_FULL_GAIN = "coast_only_full_gain"
    }
}
