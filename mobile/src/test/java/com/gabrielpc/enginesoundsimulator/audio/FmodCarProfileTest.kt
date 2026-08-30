package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmodCarProfileTest {
    @Test
    fun supraExposesItsAuthoredIgnitionThroughThePersistentEngineEvent() {
        assertTrue(FmodCarProfiles.toyotaSupraMk4.hasEmbeddedEngineStart)
    }

    @Test
    fun profilesWithoutAuthoredStartMaterialDoNotRequestAnIgnitionSequenceAtPreload() {
        listOf(
            FmodCarProfiles.skylineR34,
            FmodCarProfiles.huracanTrofeoEvo2,
            FmodCarProfiles.aventadorSv,
            FmodCarProfiles.alfaRomeo4c,
        ).forEach { profile ->
            assertFalse("${profile.displayName} must start quietly after preload", profile.hasEmbeddedEngineStart)
        }
    }
}
