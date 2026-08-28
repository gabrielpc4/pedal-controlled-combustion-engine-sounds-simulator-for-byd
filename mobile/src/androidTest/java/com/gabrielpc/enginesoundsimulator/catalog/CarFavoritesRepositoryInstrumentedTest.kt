package com.gabrielpc.enginesoundsimulator.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarFavoritesRepositoryInstrumentedTest {
    @Test
    fun favoritesPersistAcrossRepositoryRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "car_catalog_favorites_v1",
            0,
        )
        val original = preferences.getStringSet(
            "favorite_car_ids",
            emptySet(),
        ).orEmpty().toSet()

        try {
            preferences.edit().clear().commit()
            CarFavoritesRepository(context).apply {
                setFavorite("ks_toyota_supra_mkiv", true)
                setFavorite("ks_lamborghini_huracan_st", true)
            }

            assertEquals(
                setOf("ks_toyota_supra_mkiv", "ks_lamborghini_huracan_st"),
                CarFavoritesRepository(context).favoriteIds(),
            )
        } finally {
            preferences.edit()
                .clear()
                .putStringSet("favorite_car_ids", original)
                .commit()
        }
    }
}
