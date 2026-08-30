package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodCarSelectionRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Exercises the real controller worker with direct inputs rather than slow UI gestures.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun switchingToSupraAtDeferredCompletionWaitsForTheSupraBank() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectionRepository = FmodCarSelectionRepository(context)
        val previousProfile = selectionRepository.load()
        selectionRepository.save(FmodCarProfiles.skylineR34)
        val initialBankObserved = CountDownLatch(1)
        val allowDeferredCompletion = CountDownLatch(1)
        val interceptInitialBankOnce = AtomicBoolean(true)
        val controller = DriveController(context) { loadedProfileId ->
            if (
                loadedProfileId == FmodCarProfiles.skylineR34.id &&
                interceptInitialBankOnce.compareAndSet(true, false)
            ) {
                initialBankObserved.countDown()
                allowDeferredCompletion.await(10L, TimeUnit.SECONDS)
            }
        }
        try {
            controller.setUiActive(true)
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.start()

            assertTrue(
                "Skyline bank never reached the deferred-completion boundary",
                initialBankObserved.await(12L, TimeUnit.SECONDS),
            )
            assertTrue(controller.selectCar(FmodCarProfiles.toyotaSupraMk4.id))
            assertEquals(FmodCarProfiles.toyotaSupraMk4.id, controller.snapshot().selectedCarId)
            assertFalse("Supra must not inherit Skyline readiness", controller.snapshot().carAudioReady)
            assertFalse("ignition started before the selected bank was ready", controller.snapshot().engineSoundEnabled)

            allowDeferredCompletion.countDown()
            var ignitionBeforeSupraReady = false
            assertTrue(
                "Supra did not preload and begin its deferred authored ignition",
                waitUntil(timeoutMs = 12_000L) {
                    val snapshot = controller.snapshot()
                    if (!snapshot.carAudioReady && snapshot.engineSoundEnabled) {
                        ignitionBeforeSupraReady = true
                    }
                    snapshot.carAudioReady && snapshot.engineSoundEnabled && !snapshot.engineStartLoading
                },
            )
            assertFalse(
                "the stale Skyline completion consumed first-start state before Supra was ready",
                ignitionBeforeSupraReady,
            )

            var peakRpm = controller.snapshot().drivetrain.rpm
            val ignitionDeadline = SystemClock.elapsedRealtime() + 2_300L
            while (SystemClock.elapsedRealtime() < ignitionDeadline) {
                peakRpm = maxOf(peakRpm, controller.snapshot().drivetrain.rpm)
                SystemClock.sleep(10L)
            }
            assertTrue(
                "Supra's authored ignition RPM trace did not fire after its own bank became ready; peak=$peakRpm",
                peakRpm >= 4_500.0,
            )
        } finally {
            allowDeferredCompletion.countDown()
            controller.stop()
            selectionRepository.save(previousProfile)
        }
    }

    @Test
    fun firstSupraSessionPlaysEmbeddedIgnitionOnlyAfterItsBankIsReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectionRepository = FmodCarSelectionRepository(context)
        val previousProfile = selectionRepository.load()
        selectionRepository.save(FmodCarProfiles.toyotaSupraMk4)
        val controller = DriveController(context)
        try {
            controller.setUiActive(true)
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.start()

            assertTrue(
                "Supra bank did not finish preloading before the deferred start",
                waitUntil(timeoutMs = 12_000L) {
                    val snapshot = controller.snapshot()
                    snapshot.carAudioReady && snapshot.engineSoundEnabled && !snapshot.engineStartLoading
                },
            )

            var peakRpm = controller.snapshot().drivetrain.rpm
            val ignitionDeadline = SystemClock.elapsedRealtime() + 2_300L
            while (SystemClock.elapsedRealtime() < ignitionDeadline) {
                peakRpm = maxOf(peakRpm, controller.snapshot().drivetrain.rpm)
                SystemClock.sleep(10L)
            }

            assertTrue(
                "deferred Supra start skipped its embedded ignition RPM trace; peak=$peakRpm",
                peakRpm >= 4_500.0,
            )
            assertTrue(
                "Supra ignition did not settle at the selected profile idle",
                waitUntil(timeoutMs = 1_000L) {
                    abs(controller.snapshot().drivetrain.rpm - FmodCarProfiles.toyotaSupraMk4.idleRpm) <= 25.0
                },
            )
        } finally {
            controller.stop()
            selectionRepository.save(previousProfile)
        }
    }

    @Test
    fun scriptedLaunchAndLiftOffStaySpeedCoupled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = DriveController(context)
        try {
            controller.setUiActive(true)
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.start()
            assertTrue(
                "engine should auto-start when the controller loop begins",
                waitUntil(timeoutMs = 8_000L) {
                    controller.snapshot().engineSoundEnabled
                },
            )
            controller.setSimulatedPedalThrottle(1.0)

            assertTrue(
                "scripted full throttle did not build road speed and coupled RPM",
                waitUntil(timeoutMs = 2_500L) {
                    val state = controller.snapshot().drivetrain
                    state.speedKmh >= 30.0 && state.rpm > controller.snapshot().tuning.engine.idleRpm + 500.0
                },
            )
            assertTrue(
                "scripted full throttle did not create a virtual upshift",
                waitUntil(timeoutMs = 3_000L) {
                    val state = controller.snapshot().drivetrain
                    state.gear >= 2 && !state.isShifting
                },
            )

            val beforeLift = controller.snapshot().drivetrain
            controller.setSimulatedPedalThrottle(0.0)
            assertTrue(
                "scripted lift-off did not reduce both road speed and coupled RPM",
                waitUntil(timeoutMs = 1_500L) {
                    val state = controller.snapshot().drivetrain
                    state.rpm < beforeLift.rpm && state.speedKmh < beforeLift.speedKmh
                },
            )
            assertTrue(
                "scripted lift-off did not eventually create a virtual downshift",
                waitUntil(timeoutMs = 5_000L) { controller.snapshot().drivetrain.gear < beforeLift.gear },
            )
        } finally {
            controller.stop()
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(20L)
        }
        return predicate()
    }
}
