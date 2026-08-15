package com.gabrielpc.bydmotorsound.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.bydmotorsound.diagnostics.PersistentDiagnosticLog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real controller worker with direct inputs rather than slow UI gestures. It also
 * proves the persistent transition trail can explain a scripted lift-off session after shutdown.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun scriptedThirdGearLiftOffStaysInSecondAndPersistsItsShiftTrail() {
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
                "scripted full throttle did not reach third gear",
                waitUntil(timeoutMs = 12_000L) {
                    val state = controller.snapshot().drivetrain
                    state.gear == 3 && !state.isShifting
                },
            )

            controller.setManualThrottle(0.0)
            PersistentDiagnosticLog.event("scripted_lift_off_started")
            assertTrue(
                "scripted lift-off did not complete the expected third-to-second downshift",
                waitUntil(timeoutMs = 20_000L) {
                    val state = controller.snapshot().drivetrain
                    state.gear == 2 && !state.isShifting
                },
            )

            // The former defect immediately reversed this downshift. Stay beyond its old loop
            // interval without sending any UI input.
            SystemClock.sleep(1_000L)
            assertEquals(2, controller.snapshot().drivetrain.gear)
        } finally {
            controller.stop()
        }

        val log = File(context.filesDir, "diagnostics/drive-events.log").readText()
        val liftOffSession = log.substringAfterLast("event=scripted_lift_off_started", missingDelimiterValue = "")
        assertTrue("scripted session did not persist a downshift request", liftOffSession.contains("direction=DOWN"))
        assertTrue("scripted session did not persist a second-gear completion", liftOffSession.contains("shift_completed") && liftOffSession.contains("gear=2"))
        assertTrue("scripted session unexpectedly persisted an upshift", !liftOffSession.contains("direction=UP"))
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
