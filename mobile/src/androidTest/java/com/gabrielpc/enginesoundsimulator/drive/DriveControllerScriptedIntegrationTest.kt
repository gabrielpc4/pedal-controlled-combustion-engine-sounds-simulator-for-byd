package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.diagnostics.PersistentDiagnosticLog
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real controller worker with direct inputs rather than slow UI gestures. It also
 * proves the persistent trail can explain a scripted direct-tach lift-off session after shutdown.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun scriptedFullPedalKickAndLiftOffStayInDirectTachMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PersistentDiagnosticLog.install(context)
        PersistentDiagnosticLog.event("scripted_lift_off_test_started")

        val controller = DriveController(context)
        try {
            // Keep the test deterministic and independent from the emulator audio backend.
            controller.toggleSound()
            controller.setInputMode(InputMode.SIMULATOR)
            controller.start()
            controller.setManualThrottle(1.0)

            assertTrue(
                "scripted full throttle did not reach the direct-tach sweet spot",
                waitUntil(timeoutMs = 1_500L) {
                    val state = controller.snapshot().drivetrain
                    state.rpm >= controller.snapshot().tuning.engine.fullThrottleSweetSpotRpm - 150.0 &&
                        state.gear >= 1
                },
            )
            assertTrue(
                "scripted full throttle did not create a virtual upshift",
                waitUntil(timeoutMs = 3_000L) { controller.snapshot().drivetrain.gear >= 2 },
            )

            val beforeLift = controller.snapshot().drivetrain
            controller.setManualThrottle(0.0)
            PersistentDiagnosticLog.event("scripted_lift_off_started")
            assertTrue(
                "scripted lift-off did not rapidly reduce direct RPM and SIM speed",
                waitUntil(timeoutMs = 1_500L) {
                    val state = controller.snapshot().drivetrain
                    state.rpm < beforeLift.rpm - 2_000.0 &&
                        state.speedKmh < beforeLift.speedKmh - 40.0 &&
                        state.gear < beforeLift.gear
                },
            )
        } finally {
            controller.stop()
        }

        val log = File(context.filesDir, "diagnostics/drive-events.log").readText()
        val liftOffSession = log.substringAfterLast("event=scripted_lift_off_started", missingDelimiterValue = "")
        assertTrue("scripted session did not persist direct-tach telemetry", log.contains("mode=DIRECT_TACH"))
        assertTrue("scripted session did not persist an upshift", log.contains("event=virtual_shift_started") && log.contains("direction=UP"))
        assertTrue("scripted lift-off did not persist a virtual downshift", liftOffSession.contains("event=virtual_shift_started") && liftOffSession.contains("direction=DOWN"))
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
