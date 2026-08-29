package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.BuildConfig

/** Persists which sample-engine mix path is active: coast layer mix (default) or legacy throttle mix. */
class AudioMixModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCoastLayerMixEnabled(): Boolean {
        if (preferences.contains(KEY_COAST_LAYER_MIX_ENABLED)) {
            return preferences.getBoolean(KEY_COAST_LAYER_MIX_ENABLED, DEFAULT_COAST_LAYER_MIX_ENABLED)
        }
        if (preferences.contains(KEY_COAST_ONLY_FULL_GAIN_LEGACY)) {
            return preferences.getBoolean(KEY_COAST_ONLY_FULL_GAIN_LEGACY, DEFAULT_COAST_LAYER_MIX_ENABLED)
        }
        return BuildConfig.COAST_LAYER_MIX_ENABLED_BY_DEFAULT
    }

    fun setCoastLayerMixEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_COAST_LAYER_MIX_ENABLED, enabled)
            .remove(KEY_COAST_ONLY_FULL_GAIN_LEGACY)
            .apply()
    }

    fun resetCoastLayerMixEnabled() {
        preferences.edit()
            .remove(KEY_COAST_LAYER_MIX_ENABLED)
            .remove(KEY_COAST_ONLY_FULL_GAIN_LEGACY)
            .apply()
    }

    fun isPopsAndBangsEnabled(): Boolean {
        return preferences.getBoolean(KEY_POPS_AND_BANGS_ENABLED, DEFAULT_POPS_AND_BANGS_ENABLED)
    }

    fun setPopsAndBangsEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_POPS_AND_BANGS_ENABLED, enabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "audio_experiments"
        const val KEY_COAST_LAYER_MIX_ENABLED = "coast_layer_mix_enabled"
        const val KEY_COAST_ONLY_FULL_GAIN_LEGACY = "coast_only_full_gain"
        const val KEY_POPS_AND_BANGS_ENABLED = "pops_and_bangs_enabled"
        const val DEFAULT_COAST_LAYER_MIX_ENABLED = true
        const val DEFAULT_POPS_AND_BANGS_ENABLED = false
    }
}
