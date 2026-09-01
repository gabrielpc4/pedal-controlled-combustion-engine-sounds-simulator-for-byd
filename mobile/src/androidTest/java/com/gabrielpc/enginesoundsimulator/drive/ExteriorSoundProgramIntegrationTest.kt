package com.gabrielpc.enginesoundsimulator.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSource
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSourceRepository
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExteriorSoundProgramIntegrationTest {
    @Test
    fun everyCarPersistsIndependentCabinAndExteriorProgramChoicesBeforeItsPackIsInstalled() {
        val context = IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "exterior_sound_program",
        ).also { it.clear() }
        val selectedCarRepository = SelectedCarRepository(context)
        val perspectiveRepository = EngineSoundPerspectiveRepository(context)
        val sourceRepository = PrimaryEngineLayerSourceRepository(context)
        val originalCar = selectedCarRepository.load()
        val originalPerspectives = FmodBankProfiles.all.associateWith(perspectiveRepository::load)
        val originalSources = FmodBankProfiles.all.flatMap { profile ->
            EngineSoundPerspective.entries.map { perspective ->
                (profile.id to perspective) to sourceRepository.load(profile, perspective)
            }
        }.toMap()
        try {
            FmodBankProfiles.all.forEach { profile ->
                selectedCarRepository.save(profile)
                assertEquals(profile.id, selectedCarRepository.load().id)
                EngineSoundPerspective.entries.forEach { perspective ->
                    perspectiveRepository.save(profile, perspective)
                    assertEquals(perspective, perspectiveRepository.load(profile))
                    PrimaryEngineLayerSource.entries
                        .filter { source -> profile.supportsPrimaryLayerSource(source) }
                        .forEach { source ->
                        sourceRepository.save(profile, perspective, source)
                        assertEquals(source, sourceRepository.load(profile, perspective))
                    }
                }
            }
        } finally {
            FmodBankProfiles.all.forEach { profile ->
                perspectiveRepository.save(profile, originalPerspectives.getValue(profile))
                EngineSoundPerspective.entries.forEach { perspective ->
                    sourceRepository.save(
                        profile,
                        perspective,
                        originalSources.getValue(profile.id to perspective),
                    )
                }
            }
            selectedCarRepository.save(originalCar)
            context.clear()
        }
    }
}
