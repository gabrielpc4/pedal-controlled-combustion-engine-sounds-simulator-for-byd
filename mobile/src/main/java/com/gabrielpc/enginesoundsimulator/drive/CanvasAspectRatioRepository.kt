package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

internal class CanvasAspectRatioRepository(context: Context) {
    private val preferences = context.getSharedPreferences(AppPreferenceStores.SELECTED_CAR, Context.MODE_PRIVATE)
    fun load(): Float = preferences.getFloat(AppPreferenceStores.CANVAS_ASPECT_RATIO, DEFAULT).coerceIn(MINIMUM, MAXIMUM)
    fun save(value: Float) { preferences.edit().putFloat(AppPreferenceStores.CANVAS_ASPECT_RATIO, value).apply() }
    fun reset() { preferences.edit().remove(AppPreferenceStores.CANVAS_ASPECT_RATIO).apply() }
    companion object { const val DEFAULT = 1920f / 990f; const val MINIMUM = 1.40f; const val MAXIMUM = 2.40f }
}
