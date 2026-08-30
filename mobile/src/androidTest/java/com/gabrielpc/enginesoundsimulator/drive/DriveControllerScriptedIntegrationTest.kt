package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real controller worker with direct inputs rather than slow UI gestures.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun scriptedLaunchAndLiftOffUseLoadedRpm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = DriveController(context)
        try {
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.setUiActive(true)
            controller.start()
            assertTrue(
                "engine and decoded audio should become ready before driving",
                waitUntil(timeoutMs = 20_000L) {
                    val snapshot = controller.snapshot()
                    snapshot.engineSoundEnabled &&
                        snapshot.carAudioReady &&
                        snapshot.drivetrain.rpm > 0.0
                },
            )
            controller.setSimulatedPedalThrottle(1.0)

            val launchBuiltSpeed = waitUntil(timeoutMs = 2_500L) {
                val state = controller.snapshot().drivetrain
                state.speedKmh >= 30.0 && state.rpm > controller.snapshot().tuning.engine.idleRpm + 500.0
            }
            assertTrue(
                "scripted full throttle did not build road speed and loaded RPM: ${controller.snapshot().drivetrain}",
                launchBuiltSpeed,
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
                "scripted lift-off did not reduce both road speed and loaded RPM",
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
