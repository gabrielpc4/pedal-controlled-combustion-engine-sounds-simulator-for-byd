package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.simulation.VirtualGearProfile

/** Persists the global virtual forward-gear count (6–10, default 10). */
internal class VirtualGearCountRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.VIRTUAL_GEAR_COUNT,
        Context.MODE_PRIVATE,
    )

    fun load(): Int {
        return preferences.getInt(
            KEY_VIRTUAL_GEAR_COUNT,
            VirtualGearProfile.DEFAULT_VIRTUAL_GEARS,
        ).coerceIn(VirtualGearProfile.MIN_VIRTUAL_GEARS, VirtualGearProfile.MAX_VIRTUAL_GEARS)
    }

    fun save(count: Int) {
        preferences.edit()
            .putInt(
                KEY_VIRTUAL_GEAR_COUNT,
                count.coerceIn(VirtualGearProfile.MIN_VIRTUAL_GEARS, VirtualGearProfile.MAX_VIRTUAL_GEARS),
            )
            .commit()
    }

    fun reset() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_VIRTUAL_GEAR_COUNT = "virtual_forward_gear_count"
    }
}
