package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Persists the driver's favorite installed car profiles. */
internal class CarFavoritesRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.CAR_FAVORITES,
        Context.MODE_PRIVATE,
    )

    fun load(): Set<String> = preferences.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun toggle(profileId: String): Set<String> {
        val updated = load().toMutableSet()
        if (!updated.add(profileId)) {
            updated.remove(profileId)
        }
        preferences.edit().putStringSet(KEY, HashSet(updated)).apply()
        return updated.toSet()
    }

    companion object {
        private const val KEY = "favorite_profile_ids"
    }
}
