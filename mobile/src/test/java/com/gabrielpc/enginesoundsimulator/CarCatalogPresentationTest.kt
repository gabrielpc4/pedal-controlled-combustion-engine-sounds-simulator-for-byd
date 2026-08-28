package com.gabrielpc.enginesoundsimulator

import com.gabrielpc.enginesoundsimulator.catalog.CarCatalogEntry
import com.gabrielpc.enginesoundsimulator.catalog.orderCarCatalogEntriesForSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CarCatalogPresentationTest {
    private val entries = listOf(
        entry(id = "ks_toyota_supra_mkiv", name = "Toyota Supra MKIV", brand = "Toyota", installed = true),
        entry(id = "bmw_m4", name = "BMW M4", brand = "BMW", installed = false),
        entry(id = "ks_lamborghini_huracan_st", name = "Huracan ST", brand = "Lamborghini", installed = true),
    )

    @Test
    fun searchIsTrimmedCaseInsensitiveAndMatchesNameBrandOrId() {
        assertEquals(listOf("bmw_m4"), filterCarCatalogEntries(entries, "  BMW  ").map { it.id })
        assertEquals(
            listOf("ks_lamborghini_huracan_st"),
            filterCarCatalogEntries(entries, "lAmBoRgHiNi").map { it.id },
        )
        assertEquals(
            listOf("ks_toyota_supra_mkiv"),
            filterCarCatalogEntries(entries, "SUPRA_MKIV").map { it.id },
        )
        assertSame(entries, filterCarCatalogEntries(entries, "  "))
    }

    @Test
    fun favoritesAreMarkedAndSortedFirstWithoutHidingInstallState() {
        val ordered = orderCarCatalogEntriesForSelector(
            entries.map { it.copy(favorite = it.id == "bmw_m4") },
        )

        assertEquals(listOf("bmw_m4", "ks_lamborghini_huracan_st", "ks_toyota_supra_mkiv"), ordered.map { it.id })
        assertEquals("★", carFavoriteMarker(ordered.first().favorite))
        assertEquals("☆", carFavoriteMarker(ordered.last().favorite))
        assertEquals("IMPORT PACK", carInstallationLabel(ordered.first().installed))
        assertEquals("INSTALLED", carInstallationLabel(ordered.last().installed))
    }

    private fun entry(
        id: String,
        name: String,
        brand: String,
        installed: Boolean,
    ) = CarCatalogEntry(
        id = id,
        displayName = name,
        brand = brand,
        familyId = null,
        installed = installed,
        favorite = false,
        previewFile = null,
        engine = null,
        gearbox = null,
        effects = null,
        quirks = emptySet(),
    )
}
