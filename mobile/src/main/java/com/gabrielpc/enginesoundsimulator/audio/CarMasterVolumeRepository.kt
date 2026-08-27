package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

internal class CarMasterVolumeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(profileId: String): Double {
        return preferences.getFloat(volumeKey(profileId), DEFAULT.toFloat()).toDouble().coerceIn(MIN, MAX)
    }

    fun save(profileId: String, volume: Double): Double {
        val clamped = volume.coerceIn(MIN, MAX)
        preferences.edit()
            .putFloat(volumeKey(profileId), clamped.toFloat())
            .apply()
        return clamped
    }

    fun resetAll() {
        preferences.edit().clear().apply()
    }

    private fun volumeKey(profileId: String): String = "$profileId.master_gain"

    companion object {
        const val PREFERENCES_NAME = "car_master_volume"
        const val DEFAULT = 0.72
        const val MIN = 0.0
        const val MAX = 1.2
    }
}
