package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

internal class AutoblipRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(AppPreferenceStores.AUTOBLIP, Context.MODE_PRIVATE)
    fun load(): Boolean = preferences.getBoolean("enabled", true)
    fun save(enabled: Boolean) { preferences.edit().putBoolean("enabled", enabled).apply() }
    fun reset() { preferences.edit().clear().apply() }
}
