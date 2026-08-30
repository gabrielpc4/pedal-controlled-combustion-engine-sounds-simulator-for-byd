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
    fun scriptedFullThrottleFlaresLoadedRpmBeforeSlowRoadSpeed() {
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
            SystemClock.sleep(1_500L)

            val fullThrottle = controller.snapshot()
            assertTrue(
                "simulated road speed should barely move at full throttle: ${fullThrottle.drivetrain}",
                fullThrottle.drivetrain.speedKmh < 5.0,
            )
            assertTrue(
                "loaded RPM should flare while simulated road speed stays low: ${fullThrottle.drivetrain}",
                fullThrottle.drivetrain.rpm > fullThrottle.tuning.engine.idleRpm + 600.0,
            )
            assertTrue("full simulated pedal must still reach audio", fullThrottle.drivetrain.audioThrottle > 0.99)
            assertTrue("slow test launch should remain in first gear", fullThrottle.drivetrain.gear == 1)

            val beforeLift = fullThrottle.drivetrain
            controller.setSimulatedPedalThrottle(0.0)
            assertTrue(
                "scripted lift-off did not reduce loaded RPM",
                waitUntil(timeoutMs = 1_500L) {
                    val state = controller.snapshot().drivetrain
                    state.rpm < beforeLift.rpm - 300.0 && state.speedKmh <= beforeLift.speedKmh
                },
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
