package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Persists the main-screen tachometer's display size independently of the global UI scale. */
internal class TachometerScaleRepository(context: Context) {
    private val preferences = context.getSharedPreferences(AppPreferenceStores.SELECTED_CAR, Context.MODE_PRIVATE)

    fun load(): Float = preferences.getFloat(AppPreferenceStores.TACHOMETER_SCALE, DEFAULT).coerceIn(MINIMUM, MAXIMUM)

    fun save(value: Float) { preferences.edit().putFloat(AppPreferenceStores.TACHOMETER_SCALE, value).apply() }

    fun reset() { preferences.edit().remove(AppPreferenceStores.TACHOMETER_SCALE).apply() }

    companion object {
    const val DEFAULT = 0.8f
        const val MINIMUM = 0.6f
        const val MAXIMUM = 1.0f
    }
}
