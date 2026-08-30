package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.drive.DriveController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FmodAudioSettingsRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun eventControlsDefaultToAuthoredMixAndPersistOverrides() {
        val repository = FmodEventMixRepository(context)

        FmodEventKind.entries.forEach { kind ->
            assertTrue(repository.load().control(kind).enabled)
            assertEquals(0.0, repository.load().control(kind).gainDb, 0.0)
        }

        repository.setEnabled(FmodEventKind.TURBO, false)
        repository.setGainDb(FmodEventKind.TURBO, -12.5)

        val restored = FmodEventMixRepository(context).load().control(FmodEventKind.TURBO)
        assertFalse(restored.enabled)
        assertEquals(-12.5, restored.gainDb, 0.001)
    }

    @Test
    fun eventGainIsClampedBeforePersistence() {
        val repository = FmodEventMixRepository(context)

        assertEquals(
            FmodEventMixSettings.MAX_GAIN_DB,
            repository.setGainDb(FmodEventKind.ENGINE, 100.0).control(FmodEventKind.ENGINE).gainDb,
            0.0,
        )
        assertEquals(
            FmodEventMixSettings.MIN_GAIN_DB,
            repository.setGainDb(FmodEventKind.ENGINE, -100.0).control(FmodEventKind.ENGINE).gainDb,
            0.0,
        )
    }

    @Test
    fun coastOnlyDefaultsOffAndDoesNotInheritLegacyCoastMix() {
        context.getSharedPreferences("audio_experiments", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("coast_layer_mix_enabled", true)
            .putBoolean("coast_only_full_gain", true)
            .commit()

        val repository = AudioMixModeRepository(context)
        assertFalse(repository.isCoastOnlyEnabled())
        assertFalse(repository.isLoadOnlyEnabled())

        repository.setCoastOnlyEnabled(true)
        var reloaded = AudioMixModeRepository(context)
        assertTrue(reloaded.isCoastOnlyEnabled())
        assertFalse(reloaded.isLoadOnlyEnabled())

        repository.setLoadOnlyEnabled(true)
        reloaded = AudioMixModeRepository(context)
        assertTrue(reloaded.isLoadOnlyEnabled())
        assertFalse(reloaded.isCoastOnlyEnabled())
    }

    @Test
    fun staleUnknownCarSelectionFallsBackToSkyline() {
        context.getSharedPreferences("selected_car", Context.MODE_PRIVATE)
            .edit()
            .putString("profile_id", "lamborghini_huracan_performante")
            .commit()

        val snapshot = DriveController(context).snapshot()

        assertEquals(FmodCarProfiles.SKYLINE_R34_ID, snapshot.selectedCarId)
        assertEquals(FmodCarProfiles.all.size, snapshot.availableCarCount)
    }

    @Test
    fun originalLamborghiniSelectionIdIsPreserved() {
        context.getSharedPreferences("selected_car", Context.MODE_PRIVATE)
            .edit()
            .putString("profile_id", "lamborghini_huracan_trofeo_evo2_cabin")
            .commit()

        val snapshot = DriveController(context).snapshot()

        assertEquals(FmodCarProfiles.HURACAN_TROFEO_EVO2_ID, snapshot.selectedCarId)
        assertEquals(FmodCarProfiles.indexOf(FmodCarProfiles.huracanTrofeoEvo2), snapshot.selectedCarIndex)
    }

    @Test
    fun selectionRepositoryMigratesTransientBankSlugAndPersistsNewSelection() {
        context.getSharedPreferences("selected_car", Context.MODE_PRIVATE)
            .edit()
            .putString("profile_id", "tr_lamborghini_aventador_sv")
            .commit()

        val repository = FmodCarSelectionRepository(context)
        assertEquals(FmodCarProfiles.AVENTADOR_SV_ID, repository.load().id)
        assertEquals(
            FmodCarProfiles.AVENTADOR_SV_ID,
            context.getSharedPreferences("selected_car", Context.MODE_PRIVATE)
                .getString("profile_id", null),
        )

        repository.save(FmodCarProfiles.alfaRomeo4c)
        assertEquals(FmodCarProfiles.ALFA_ROMEO_4C_ID, FmodCarSelectionRepository(context).load().id)
    }

    @Test
    fun driveControllerCyclesAndPersistsProfileSpecificSimulationMetadata() {
        val controller = DriveController(context)

        assertTrue(controller.selectNextCar())
        val huracan = controller.snapshot()
        assertEquals(FmodCarProfiles.HURACAN_TROFEO_EVO2_ID, huracan.selectedCarId)
        assertEquals(1, huracan.selectedCarIndex)
        assertEquals(8_200.0, huracan.tuning.engine.redlineRpm, 0.0)
        assertEquals(7, huracan.tuning.engine.gearRatios.size)

        assertTrue(controller.selectPreviousCar())
        assertEquals(FmodCarProfiles.SKYLINE_R34_ID, controller.snapshot().selectedCarId)
        assertFalse(controller.selectCar("missing-profile"))
        assertEquals(FmodCarProfiles.SKYLINE_R34_ID, FmodCarSelectionRepository(context).load().id)
    }

    private fun clearPreferences() {
        context.getSharedPreferences("fmod_event_mix", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audio_experiments", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("selected_car", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
