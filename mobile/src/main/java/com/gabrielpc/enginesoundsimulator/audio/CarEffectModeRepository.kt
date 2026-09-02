package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Per-car enable state for authored native-bank effects. */
internal class CarEffectModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.CAR_EFFECT_MODES,
        Context.MODE_PRIVATE,
    )
    fun popsAndBangsEnabled(profileId: String): Boolean {
        return readMode(profileId, "pops_enabled", false)
    }

    fun savePopsAndBangsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "pops_enabled", enabled)
    }

    fun shiftSoundsEnabled(profileId: String): Boolean {
        return readMode(profileId, "shift_enabled", false)
    }

    fun saveShiftSoundsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "shift_enabled", enabled)
    }

    fun transmissionEnabled(profileId: String): Boolean {
        return readMode(profileId, "transmission_enabled", true)
    }

    fun saveTransmissionEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "transmission_enabled", enabled)
    }

    fun turboSoundsEnabled(profileId: String): Boolean {
        return readMode(profileId, "turbo_enabled", true)
    }

    fun saveTurboSoundsEnabled(profileId: String, enabled: Boolean): Boolean {
        return saveMode(profileId, "turbo_enabled", enabled)
    }

    private fun readMode(profileId: String, keySuffix: String, default: Boolean): Boolean {
        val key = modeKey(profileId, keySuffix)
        return preferences.getBoolean(key, default)
    }

    private fun saveMode(profileId: String, keySuffix: String, enabled: Boolean): Boolean {
        preferences.edit()
            .putBoolean(modeKey(profileId, keySuffix), enabled)
            .commit()

        return readMode(profileId, keySuffix, enabled)
    }

    private fun modeKey(profileId: String, keySuffix: String): String = "$profileId.$keySuffix"
}
