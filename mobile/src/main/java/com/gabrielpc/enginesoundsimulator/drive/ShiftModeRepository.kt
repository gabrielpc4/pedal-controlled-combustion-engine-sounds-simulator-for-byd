package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Persists the global automatic/manual presentation gearbox choice. */
internal class ShiftModeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SHIFT_MODE,
        Context.MODE_PRIVATE,
    )

    fun isManualEnabled(): Boolean = preferences.getBoolean(KEY_MANUAL_ENABLED, false)

    fun setManualEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MANUAL_ENABLED, enabled).commit()
    }

    private companion object {
        const val KEY_MANUAL_ENABLED = "manual_shift_mode_enabled"
    }
}
