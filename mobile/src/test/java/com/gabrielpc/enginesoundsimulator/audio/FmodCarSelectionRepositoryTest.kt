package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class FmodCarSelectionRepositoryTest {
    @Test
    fun everyCurrentProfileIdRoundTripsUnchanged() {
        FmodCarProfiles.all.forEach { profile ->
            assertEquals(profile.id, resolvePersistedFmodProfileId(profile.id))
        }
    }

    @Test
    fun transientBankSlugIdsMigrateWithoutChangingTheSelectedCar() {
        assertEquals(
            FmodCarProfiles.HURACAN_TROFEO_EVO2_ID,
            resolvePersistedFmodProfileId("fx_lamborghini_huracan_trofeo_evo2"),
        )
        assertEquals(
            FmodCarProfiles.AVENTADOR_SV_ID,
            resolvePersistedFmodProfileId("tr_lamborghini_aventador_sv"),
        )
    }

    @Test
    fun missingOrUnknownProfileFallsBackToSkyline() {
        assertEquals(FmodCarProfiles.SKYLINE_R34_ID, resolvePersistedFmodProfileId(null))
        assertEquals(
            FmodCarProfiles.SKYLINE_R34_ID,
            resolvePersistedFmodProfileId("lamborghini_huracan_performante"),
        )
    }
}
