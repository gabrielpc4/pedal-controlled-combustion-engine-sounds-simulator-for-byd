package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** One persisted master-gain multiplier shared by the whole app. */
internal class AppMasterVolumeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Double {
        return preferences.getFloat(KEY_APP_MASTER_GAIN, DEFAULT.toFloat()).toDouble().coerceIn(MIN, MAX)
    }

    fun save(volume: Double): Double {
        val clamped = volume.coerceIn(MIN, MAX)
        preferences.edit()
            .putFloat(KEY_APP_MASTER_GAIN, clamped.toFloat())
            .apply()
        return clamped
    }

    companion object {
        const val PREFERENCES_NAME = "app_master_volume"
        private const val KEY_APP_MASTER_GAIN = "app.master_gain"
        const val DEFAULT = CarMasterVolumeRepository.DEFAULT
        const val MIN = CarMasterVolumeRepository.MIN
        const val MAX = 1.0
    }
}
