package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/**
 * User-selectable cadence for sending control frames to FMOD Studio.
 *
 * The same cadence drives the fixed-step drivetrain and FMOD parameter transfer. Keeping the
 * two clocks together avoids simulating hundreds of physical frames that the audio worker would
 * immediately skip. MAX retains the original 333 Hz behavior for comparison.
 */
internal object FmodUpdateRate {
    const val DEFAULT_HZ = 100
    const val MIN_HZ = 30
    const val MAX_HZ = 333
    const val MAX_SLIDER_HZ = 330

    fun normalize(value: Int): Int = when {
        value >= MAX_HZ -> MAX_HZ
        else -> value.coerceIn(MIN_HZ, MAX_SLIDER_HZ).let { (it / 10) * 10 }
    }

    fun periodNanos(hz: Int): Long = if (normalize(hz) == MAX_HZ) {
        // Keep the historical 3 ms scheduler period at MAX, matching the original physics loop.
        3_000_000L
    } else {
        1_000_000_000L / normalize(hz)
    }

    fun stepSeconds(hz: Int): Double = if (normalize(hz) == MAX_HZ) {
        // Preserve the historical 3 ms fixed step at MAX instead of introducing a fractional
        // 3.003 ms step merely because 333 Hz is not an integer number of nanoseconds.
        0.003
    } else {
        1.0 / normalize(hz)
    }
}

internal class FmodUpdateRateRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.FMOD_UPDATE_RATE,
        Context.MODE_PRIVATE,
    )

    fun load(): Int = FmodUpdateRate.normalize(
        preferences.getInt(KEY_RATE_HZ, FmodUpdateRate.DEFAULT_HZ),
    )

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
