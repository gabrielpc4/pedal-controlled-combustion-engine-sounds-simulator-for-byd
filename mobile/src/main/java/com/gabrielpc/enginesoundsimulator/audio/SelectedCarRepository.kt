package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

internal class SelectedCarRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("selected_car", Context.MODE_PRIVATE)

    fun load(): EngineSampleProfile = EngineSampleProfiles.find(preferences.getString("profile_id", null))

    fun save(profile: EngineSampleProfile) {
        preferences.edit().putString("profile_id", profile.id).apply()
    }
}

internal class SoundPerspectiveRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sound_perspective", Context.MODE_PRIVATE)

    fun exteriorEnabled(profile: EngineSampleProfile): Boolean =
        preferences.getBoolean("exterior_${profile.id}", false)

    fun setExteriorEnabled(profile: EngineSampleProfile, enabled: Boolean) {
        preferences.edit().putBoolean("exterior_${profile.id}", enabled).apply()
    }
}
