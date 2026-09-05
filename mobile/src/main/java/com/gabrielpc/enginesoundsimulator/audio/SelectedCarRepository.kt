package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

internal class SelectedCarRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SELECTED_CAR,
        Context.MODE_PRIVATE,
    )

    fun load(): FmodBankProfile {
        val savedId = preferences.getString("profile_id", null)
        return FmodBankProfiles.find(savedId)
    }

    fun save(profile: FmodBankProfile) {
        preferences.edit().putString("profile_id", profile.id).commit()
    }
}
