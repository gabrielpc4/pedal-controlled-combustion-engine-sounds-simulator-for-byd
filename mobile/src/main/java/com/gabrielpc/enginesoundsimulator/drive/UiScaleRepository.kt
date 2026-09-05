package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Persists the display-only global UI scale independently from vehicle and audio state. */
internal class UiScaleRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.UI_SCALE,
        Context.MODE_PRIVATE,
    )

    fun load(): Float = preferences.getFloat(KEY, DEFAULT).coerceIn(MINIMUM, MAXIMUM)

    fun save(value: Float) {
        preferences.edit().putFloat(KEY, value.coerceIn(MINIMUM, MAXIMUM)).apply()
    }

    fun reset() = preferences.edit().clear().apply()

    companion object {
        const val DEFAULT = 0.625f
        const val MINIMUM = 0.5f
        const val MAXIMUM = 1.0f
        private const val KEY = "global_scale"
    }
}
