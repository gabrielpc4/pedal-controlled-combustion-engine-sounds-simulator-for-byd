package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Per-car enable state for optional audio effects. */
internal class CarEffectModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.CAR_EFFECT_MODES,
        Context.MODE_PRIVATE,
    )
    private val legacyPreferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.AUDIO_EXPERIMENTS,
        Context.MODE_PRIVATE,
    )

    fun popsAndBangsEnabled(profileId: String): Boolean {
        return readMode(profileId, "pops_enabled", LEGACY_POPS_AND_BANGS_ENABLED, false)
    }

    fun savePopsAndBangsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "pops_enabled", enabled)
    }

    fun sharedShiftSoundsEnabled(profileId: String): Boolean {
        return readMode(profileId, "shift_enabled", LEGACY_SHARED_SHIFT_SOUNDS_ENABLED, false)
    }

    fun saveSharedShiftSoundsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "shift_enabled", enabled)
    }

    fun transmissionEnabled(profileId: String): Boolean {
        return readMode(profileId, "transmission_enabled", legacyKey = null, default = true)
    }

    fun saveTransmissionEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "transmission_enabled", enabled)
    }

    fun turboSoundsEnabled(profileId: String): Boolean {
        return readMode(profileId, "turbo_enabled", legacyKey = null, default = true)
    }

    fun saveTurboSoundsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "turbo_enabled", enabled)
    }

    private fun readMode(profileId: String, keySuffix: String, legacyKey: String?, default: Boolean): Boolean {
        val key = modeKey(profileId, keySuffix)
        if (preferences.contains(key)) {
            return preferences.getBoolean(key, default)
        }

        if (legacyKey != null && legacyPreferences.contains(legacyKey)) {
            return legacyPreferences.getBoolean(legacyKey, default)
        }

        return default
    }

    private fun saveMode(profileId: String, keySuffix: String, enabled: Boolean): Boolean {
        preferences.edit()
            .putBoolean(modeKey(profileId, keySuffix), enabled)
            .commit()

        return readMode(profileId, keySuffix, legacyKey = null, default = enabled)
    }

    private fun modeKey(profileId: String, keySuffix: String): String = "$profileId.$keySuffix"

    private companion object {
        const val LEGACY_POPS_AND_BANGS_ENABLED = "pops_and_bangs_enabled"
        const val LEGACY_SHARED_SHIFT_SOUNDS_ENABLED = "shared_shift_sounds_enabled"
    }
}
