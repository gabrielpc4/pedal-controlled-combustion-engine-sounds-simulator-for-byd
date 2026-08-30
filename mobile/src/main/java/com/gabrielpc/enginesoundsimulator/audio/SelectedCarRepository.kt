package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

internal class SelectedCarRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("selected_car", Context.MODE_PRIVATE)

    fun load(): EngineSampleProfile = EngineSampleProfiles.find(preferences.getString("profile_id", null))

    fun save(profile: EngineSampleProfile) {
        preferences.edit().putString("profile_id", profile.id).apply()
    }
}
