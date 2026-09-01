package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Per-car gain for authored native-bank effects. */
internal class CarEffectGainRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.CAR_EFFECT_GAINS,
        Context.MODE_PRIVATE,
    )
    fun popsAndBangsGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "pops_gain",
            default = EngineAudioFrame.DEFAULT_POPS_AND_BANGS_GAIN,
        )
    }

    fun savePopsAndBangsGain(profileId: String, gain: Double): Double {
        return saveGain(profileId, "pops_gain", gain)
    }

    fun shiftSoundsGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "shift_gain",
            default = EngineAudioFrame.DEFAULT_SHIFT_SOUNDS_GAIN,
        )
    }

    fun saveShiftSoundsGain(profileId: String, gain: Double): Double {
        return saveGain(profileId, "shift_gain", gain)
    }

    fun transmissionGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "transmission_gain",
            default = EngineAudioFrame.DEFAULT_TRANSMISSION_GAIN,
        )
    }

    fun saveTransmissionGain(profileId: String, gain: Double): Double {
        return saveGain(profileId, "transmission_gain", gain)
    }

    fun turboSoundsGain(profileId: String): Double {
        return readGain(
            profileId = profileId,
            keySuffix = "turbo_gain",
            default = EngineAudioFrame.DEFAULT_TURBO_SOUNDS_GAIN,
            minimum = EngineAudioFrame.MIN_TURBO_SOUNDS_GAIN,
        )
    }

    fun saveTurboSoundsGain(profileId: String, gain: Double): Double {
        return saveGain(
            profileId = profileId,
            keySuffix = "turbo_gain",
            gain = gain,
            minimum = EngineAudioFrame.MIN_TURBO_SOUNDS_GAIN,
        )
    }

    private fun readGain(
        profileId: String,
        keySuffix: String,
        default: Double,
        minimum: Double = MIN,
    ): Double {
        val key = gainKey(profileId, keySuffix)
        return preferences.getFloat(key, default.toFloat()).toDouble().coerceIn(minimum, MAX)
    }

    private fun saveGain(
        profileId: String,
        keySuffix: String,
        gain: Double,
        minimum: Double = MIN,
    ): Double {
        val clamped = gain.coerceIn(minimum, MAX)
        preferences.edit()
            .putFloat(gainKey(profileId, keySuffix), clamped.toFloat())
            .commit()

        return readGain(
            profileId = profileId,
            keySuffix = keySuffix,
            default = clamped,
            minimum = minimum,
        )
    }

    private fun gainKey(profileId: String, keySuffix: String): String = "$profileId.$keySuffix"
    private companion object {
        const val MIN = EngineAudioFrame.MIN_EFFECT_GAIN
        const val MAX = EngineAudioFrame.MAX_EFFECT_GAIN
    }
}
