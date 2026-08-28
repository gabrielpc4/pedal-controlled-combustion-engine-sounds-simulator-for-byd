package com.gabrielpc.enginesoundsimulator.catalog

import android.content.Context

/** Persists only the stable Kunos car id; decoded audio and runtime state are never persisted. */
internal class SelectedOfficialCarRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(snapshot: CarCatalogSnapshot): String {
        val availableIds = snapshot.entries.mapTo(hashSetOf()) { it.id }
        preferences.getString(KEY_CAR_ID, null)?.takeIf(availableIds::contains)?.let { return it }

        // One-time migration from the two-profile experiment. It intentionally maps to Kunos ids,
        // never to the private source paths used by the old WAV asset task.
        val legacyId = applicationContext
            .getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LEGACY_PROFILE_KEY, null)
        val migrated = when {
            legacyId?.contains("aventador", ignoreCase = true) == true -> "ks_lamborghini_aventador_sv"
            legacyId?.contains("huracan", ignoreCase = true) == true -> "ks_lamborghini_huracan_st"
            else -> null
        }?.takeIf(availableIds::contains)

        return migrated
            ?: DEFAULT_CAR_ID.takeIf(availableIds::contains)
            ?: snapshot.entries.firstOrNull { it.installed }?.id
            ?: snapshot.entries.first().id
    }

    fun save(carId: String) {
        require(OfficialCarIndex.cars.any { it.id == carId }) { "Unknown official car $carId" }
        preferences.edit().putString(KEY_CAR_ID, carId).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "selected_official_car_v1"
        const val KEY_CAR_ID = "car_id"
        const val LEGACY_PREFERENCES_NAME = "selected_car"
        const val LEGACY_PROFILE_KEY = "profile_id"
        const val DEFAULT_CAR_ID = "ks_lamborghini_huracan_st"
    }
}
