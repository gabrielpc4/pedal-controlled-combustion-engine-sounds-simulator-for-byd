package com.gabrielpc.enginesoundsimulator.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExteriorSoundPerspectiveIntegrationTest {
    @Test
    fun everyCarPersistsItsPerspectiveBeforeItsPackIsInstalled() {
        val context = IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "exterior_sound_perspective",
        ).also { it.clear() }
        val selectedCarRepository = SelectedCarRepository(context)
        val perspectiveRepository = EngineSoundPerspectiveRepository(context)

        try {
            FmodBankProfiles.all.forEach { profile ->
                selectedCarRepository.save(profile)
                assertEquals(profile.id, selectedCarRepository.load().id)
                EngineSoundPerspective.entries.forEach { perspective ->
                    perspectiveRepository.save(profile, perspective)
                    assertEquals(perspective, perspectiveRepository.load(profile))
                }
            }
        } finally {
            context.clear()
        }
    }
}
