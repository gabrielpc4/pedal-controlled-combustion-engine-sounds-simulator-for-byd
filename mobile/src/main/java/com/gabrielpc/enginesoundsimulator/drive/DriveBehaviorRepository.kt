package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Global driving-presentation choices that must survive process restarts. */
internal class DriveBehaviorRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.DRIVE_BEHAVIOR,
        Context.MODE_PRIVATE,
    )

    fun loadResponsiveRpmEnabled(): Boolean {
        return preferences.getBoolean(KEY_LOAD_RESPONSIVE_RPM_ENABLED, false)
    }

    fun saveLoadResponsiveRpmEnabled(enabled: Boolean): Boolean {
        val saved = preferences.edit()
            .putBoolean(KEY_LOAD_RESPONSIVE_RPM_ENABLED, enabled)
            .commit()

        return if (saved) enabled else loadResponsiveRpmEnabled()
    }

    fun throttleRpmBumpEnabled(): Boolean {
        return preferences.getBoolean(KEY_THROTTLE_RPM_BUMP_ENABLED, false)
    }

    fun saveThrottleRpmBumpEnabled(enabled: Boolean): Boolean {
        val saved = preferences.edit()
            .putBoolean(KEY_THROTTLE_RPM_BUMP_ENABLED, enabled)
            .commit()

        return if (saved) enabled else throttleRpmBumpEnabled()
    }

    private companion object {
        const val KEY_LOAD_RESPONSIVE_RPM_ENABLED = "load_responsive_rpm_enabled"
        const val KEY_THROTTLE_RPM_BUMP_ENABLED = "throttle_rpm_bump_enabled"
    }
}
