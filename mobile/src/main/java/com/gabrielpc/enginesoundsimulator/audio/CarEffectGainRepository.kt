package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** Per-car gain for shared pops & bangs and Huracán shift overrides. */
internal class CarEffectGainRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences = context.applicationContext.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun popsAndBangsGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "pops_gain",
            legacyKey = LEGACY_POPS_GAIN_KEY,
            default = EngineAudioFrame.DEFAULT_POPS_AND_BANGS_GAIN,
        )
    }

    fun savePopsAndBangsGain(profileId: String, gain: Double): Double {
        return saveGain(profileId, "pops_gain", gain)
    }

    fun sharedShiftSoundsGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "shift_gain",
            legacyKey = LEGACY_SHIFT_GAIN_KEY,
            default = EngineAudioFrame.DEFAULT_SHARED_SHIFT_SOUNDS_GAIN,
        )
    }

    fun saveSharedShiftSoundsGain(profileId: String, gain: Double): Double {
        return saveGain(profileId, "shift_gain", gain)
    }

    private fun readGain(
        profileId: String,
        keySuffix: String,
        legacyKey: String,
        default: Double,
    ): Double {
        val key = gainKey(profileId, keySuffix)
        if (preferences.contains(key)) {
            return preferences.getFloat(key, default.toFloat()).toDouble().coerceIn(MIN, MAX)
        }

        if (legacyPreferences.contains(legacyKey)) {
            return legacyPreferences.getFloat(legacyKey, default.toFloat()).toDouble().coerceIn(MIN, MAX)
        }

        return default.coerceIn(MIN, MAX)
    }

    private fun saveGain(profileId: String, keySuffix: String, gain: Double): Double {
        val clamped = gain.coerceIn(MIN, MAX)
        preferences.edit()
            .putFloat(gainKey(profileId, keySuffix), clamped.toFloat())
            .apply()
        return clamped
    }

    private fun gainKey(profileId: String, keySuffix: String): String = "$profileId.$keySuffix"

    private companion object {
        const val PREFERENCES_NAME = "car_effect_gains"
        const val LEGACY_PREFERENCES_NAME = "audio_experiments"
        const val LEGACY_POPS_GAIN_KEY = "pops_and_bangs_gain"
        const val LEGACY_SHIFT_GAIN_KEY = "shared_shift_sounds_gain"
        const val MIN = EngineAudioFrame.MIN_EFFECT_GAIN
        const val MAX = EngineAudioFrame.MAX_EFFECT_GAIN
    }
}
