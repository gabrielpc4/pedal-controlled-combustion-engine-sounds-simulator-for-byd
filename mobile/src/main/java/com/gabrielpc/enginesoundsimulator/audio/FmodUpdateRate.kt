package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/**
 * User-selectable cadence for sending control frames to FMOD Studio.
 *
 * The same cadence drives the fixed-step drivetrain and FMOD parameter transfer. Keeping the
 * two clocks together avoids simulating physical frames that the audio worker would immediately
 * skip. Only the two cadences that were validated for this application are exposed.
 */
internal object FmodUpdateRate {
    const val DEFAULT_HZ = 60
    const val ECONOMY_HZ = 30
    const val STANDARD_HZ = 60

    fun normalize(value: Int): Int = if (value == ECONOMY_HZ) ECONOMY_HZ else STANDARD_HZ

    fun periodNanos(hz: Int): Long = 1_000_000_000L / normalize(hz)

    fun stepSeconds(hz: Int): Double = 1.0 / normalize(hz)
}

internal class FmodUpdateRateRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.FMOD_UPDATE_RATE,
        Context.MODE_PRIVATE,
    )

    fun load(): Int {
        val stored = preferences.getInt(KEY_RATE_HZ, FmodUpdateRate.DEFAULT_HZ)
        val normalized = FmodUpdateRate.normalize(stored)

        // No migration is needed for the old multi-rate setting. Any old value is deliberately
        // replaced by the validated 60 Hz default unless it was the explicit 30 Hz economy mode.
        if (stored != normalized) {
            preferences.edit().putInt(KEY_RATE_HZ, normalized).apply()
        }

        return normalized
    }

    fun save(rateHz: Int) {
        preferences.edit()
            .putInt(KEY_RATE_HZ, FmodUpdateRate.normalize(rateHz))
            .commit()
    }

    fun reset() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_RATE_HZ = "rate_hz"
    }
}
