package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSource
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSourceRepository
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExteriorSoundProgramIntegrationTest {
    @Test
    fun everyCarLoadsCabinAndExteriorProgramsWithoutKeepingThePreviousBankAlive() {
        val context = IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "exterior_sound_program",
        ).also { it.clear() }
        val selectedCarRepository = SelectedCarRepository(context)
        val perspectiveRepository = EngineSoundPerspectiveRepository(context)
        val sourceRepository = PrimaryEngineLayerSourceRepository(context)
        val originalCar = selectedCarRepository.load()
        val originalPerspectives = EngineSampleProfiles.all.associateWith(perspectiveRepository::load)
        val originalSources = EngineSampleProfiles.all.flatMap { profile ->
            EngineSoundPerspective.entries.map { perspective ->
                (profile.id to perspective) to sourceRepository.load(profile, perspective)
            }
        }.toMap()
        val controller = DriveController(context)

        try {
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.setUiActive(true)
            controller.start()
            assertTrue("initial engine audio did not become ready", waitUntil(25_000L) {
                controller.snapshot().carAudioReady
            })

            EngineSampleProfiles.all.forEach { profile ->
                controller.selectCar(profile.id)
                EngineSoundPerspective.entries.forEach { perspective ->
                    controller.setSoundPerspective(perspective)
                    PrimaryEngineLayerSource.entries
                        .filter { source -> profile.supportsPrimaryLayerSource(source, perspective) }
                        .forEach { source ->
                        controller.setPrimaryLayerSource(source)
                        assertTrue(
                            "${profile.displayName} $perspective ${source.displayName} failed: ${controller.snapshot().userMessage}",
                            waitUntil(25_000L) {
                                val snapshot = controller.snapshot()
                                snapshot.selectedCarId == profile.id &&
                                    snapshot.soundPerspective == perspective &&
                                    snapshot.primaryLayerSource == source &&
                                    snapshot.carAudioReady &&
                                    snapshot.userMessage == null
                            },
                        )
                    }
                }
            }
        } finally {
            controller.stop()
            EngineSampleProfiles.all.forEach { profile ->
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

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(25L)
        }
        return predicate()
    }
}
