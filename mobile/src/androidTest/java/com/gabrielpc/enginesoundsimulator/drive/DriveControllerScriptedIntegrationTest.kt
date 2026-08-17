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
 * proves the persistent trail can explain a scripted speed-coupled launch and lift-off session.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun scriptedLaunchAndLiftOffStaySpeedCoupled() {
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
            controller.setManualThrottle(0.0)
            PersistentDiagnosticLog.event("scripted_lift_off_started")
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

        val log = File(context.filesDir, "diagnostics/drive-events.log").readText()
        val liftOffSession = log.substringAfterLast("event=scripted_lift_off_started", missingDelimiterValue = "")
        assertTrue("scripted session did not persist speed-coupled telemetry", log.contains("mode=SPEED_COUPLED"))
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
