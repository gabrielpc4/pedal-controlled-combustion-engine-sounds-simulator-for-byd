package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

internal class SelectedCarRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SELECTED_CAR,
        Context.MODE_PRIVATE,
    )

    fun load(): EngineSampleProfile = EngineSampleProfiles.find(preferences.getString("profile_id", null))

    fun save(profile: EngineSampleProfile) {
        preferences.edit().putString("profile_id", profile.id).commit()
    }
}
