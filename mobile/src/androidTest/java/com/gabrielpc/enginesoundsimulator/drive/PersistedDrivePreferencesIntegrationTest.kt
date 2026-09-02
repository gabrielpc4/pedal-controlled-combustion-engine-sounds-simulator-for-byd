package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import com.gabrielpc.enginesoundsimulator.audio.CarEffectGainRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.SourceMixRepository
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistedDrivePreferencesIntegrationTest {
    @Test
    fun responsiveRpmIsDisabledWhenTheUserHasNeverSavedAChoice() {
        val context = isolatedContext("responsive_rpm_default")

        try {
            assertFalse(DriveBehaviorRepository(context).loadResponsiveRpmEnabled())
            assertFalse(DriveBehaviorRepository(context).throttleRpmBumpEnabled())
        } finally {
            context.clear()
        }
    }

    @Test
    fun effectPresetAndSelectedCarAreVisibleToNewRepositoryInstancesImmediately() {
        val context = isolatedContext("repository_visibility")
        val selectedCarRepository = SelectedCarRepository(context)
        val originalCar = selectedCarRepository.load()
        val differentCar = FmodBankProfiles.all.first { it.id != originalCar.id }
        val testProfileId = "instrumentation_persistence_test"

        try {
            CarEffectGainRepository(context).savePopsAndBangsGain(testProfileId, 2.0)
            selectedCarRepository.save(differentCar)

            assertEquals(2.0, CarEffectGainRepository(context).popsAndBangsGain(testProfileId), 0.0)
            assertEquals(differentCar.id, SelectedCarRepository(context).load().id)
        } finally {
            context.getSharedPreferences(AppPreferenceStores.CAR_EFFECT_GAINS, Context.MODE_PRIVATE)
                .edit()
                .remove("$testProfileId.pops_gain")
                .commit()
            selectedCarRepository.save(originalCar)
            context.clear()
        }
    }

    @Test
    fun responsiveRpmChoiceIsVisibleToANewRepositoryImmediately() {
        val context = isolatedContext("responsive_rpm")
        val repository = DriveBehaviorRepository(context)
        val original = repository.loadResponsiveRpmEnabled()

        try {
            val changed = !original
            assertEquals(changed, repository.saveLoadResponsiveRpmEnabled(changed))
            assertEquals(changed, DriveBehaviorRepository(context).loadResponsiveRpmEnabled())
        } finally {
            repository.saveLoadResponsiveRpmEnabled(original)
            context.clear()
        }
    }

    @Test
    fun throttleRpmBumpChoiceIsVisibleToANewRepositoryImmediately() {
        val context = isolatedContext("throttle_rpm_bump")
        val repository = DriveBehaviorRepository(context)

        try {
            assertTrue(repository.saveThrottleRpmBumpEnabled(true))
            assertTrue(DriveBehaviorRepository(context).throttleRpmBumpEnabled())
            assertFalse(repository.saveThrottleRpmBumpEnabled(false))
            assertFalse(DriveBehaviorRepository(context).throttleRpmBumpEnabled())
        } finally {
            context.clear()
        }
    }

    @Test
    fun exteriorPerspectivePersistsIndependently() {
        val context = isolatedContext("sound_program")
        val profile = FmodBankProfiles.default
        val perspectiveRepository = EngineSoundPerspectiveRepository(context)
        val originalPerspective = perspectiveRepository.load(profile)

        try {
            perspectiveRepository.save(profile, EngineSoundPerspective.EXTERIOR)
            assertEquals(
                EngineSoundPerspective.EXTERIOR,
                EngineSoundPerspectiveRepository(context).load(profile),
            )
        } finally {
            perspectiveRepository.save(profile, originalPerspective)
            context.clear()
        }
    }

    @Test
    fun exactSourceControlsPersistIndependentlyForCabinAndExterior() {
        val context = isolatedContext("source_mix_controls")
        val profile = FmodBankProfiles.default
        val repository = SourceMixRepository(context)
        val sourceId = "event:/cars/test/engine_int\u001etest_engine_mid"

        try {
            repository.setGain(profile.id, EngineSoundPerspective.CABIN, sourceId, 1.5)
            repository.setMuted(profile.id, EngineSoundPerspective.CABIN, sourceId, true)
            repository.setGain(profile.id, EngineSoundPerspective.EXTERIOR, sourceId, 2.5)
            repository.setSolo(profile.id, EngineSoundPerspective.EXTERIOR, sourceId, true)

            val cabin = repository.load(profile.id, EngineSoundPerspective.CABIN, listOf(sourceId)).getValue(sourceId)
            val exterior = repository.load(profile.id, EngineSoundPerspective.EXTERIOR, listOf(sourceId)).getValue(sourceId)
            assertEquals(1.5, cabin.gain, 0.001)
            assertTrue(cabin.muted)
            assertEquals(2.5, exterior.gain, 0.001)
            assertTrue(exterior.solo)
        } finally {
            context.clear()
        }
    }

    @Test
    fun resetClearsEveryAppPreferenceStoreWithoutTouchingProductionFiles() {
        val context = isolatedContext("complete_reset")
        val nonDefaultCar = FmodBankProfiles.all.first { it.id != FmodBankProfiles.default.id }
        SelectedCarRepository(context).save(nonDefaultCar)
        DriveBehaviorRepository(context).saveLoadResponsiveRpmEnabled(true)
        DriveBehaviorRepository(context).saveThrottleRpmBumpEnabled(true)
        CarEffectGainRepository(context).savePopsAndBangsGain(nonDefaultCar.id, 3.0)
        val controller = DriveController(context)

        try {
            AppPreferenceStores.all.forEach { storeName ->
                context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
                    .edit()
                    .putString("test_marker", storeName)
                    .commit()
            }

            controller.resetAllPreferences()

            assertFalse(controller.snapshot().loadResponsiveRpmEnabled)
            assertFalse(controller.snapshot().throttleRpmBumpEnabled)
            assertEquals(FmodBankProfiles.default.id, SelectedCarRepository(context).load().id)
            AppPreferenceStores.all.forEach { storeName ->
                assertTrue(
                    "$storeName still contains preferences after reset",
                    context.getSharedPreferences(storeName, Context.MODE_PRIVATE).all.isEmpty(),
                )
            }
        } finally {
            controller.stop()
            context.clear()
        }
    }

    private fun isolatedContext(namespace: String): IsolatedPreferenceContext {
        return IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            namespace,
        ).also { it.clear() }
    }
}
