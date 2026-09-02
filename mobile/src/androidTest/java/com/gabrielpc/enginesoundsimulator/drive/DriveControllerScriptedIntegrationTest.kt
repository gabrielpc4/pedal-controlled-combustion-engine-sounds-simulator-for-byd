package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real controller worker with direct inputs rather than slow UI gestures.
 */
@RunWith(AndroidJUnit4::class)
class DriveControllerScriptedIntegrationTest {
    @Test
    fun scriptedFullThrottleDrivesNormalSimulatedRoadSpeed() {
        val context = IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "drive_controller_scripted",
        ).also { it.clear() }
        val controller = DriveController(context)
        try {
            controller.setLoadResponsiveRpmEnabled(true)
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.setUiActive(true)
            controller.start()
            controller.setSimulatedPedalThrottle(1.0)
            val reachedSecondGear = waitUntil(timeoutMs = 5_000L) {
                controller.snapshot().drivetrain.let { state ->
                    state.speedKmh > 20.0 && state.gear > 1
                }
            }

            val fullThrottle = controller.snapshot()
            assertTrue(
                "full simulated propulsion must accelerate at the normal rate: ${fullThrottle.drivetrain}",
                fullThrottle.drivetrain.speedKmh > 20.0,
            )
            assertTrue(
                "loaded RPM must rise with normal simulated propulsion: ${fullThrottle.drivetrain}",
                fullThrottle.drivetrain.rpm > fullThrottle.tuning.engine.idleRpm + 600.0,
            )
            assertTrue("full simulated pedal must still reach audio", fullThrottle.drivetrain.audioThrottle > 0.99)
            assertTrue(
                "the authored Alfa drivetrain did not reach second gear: ${fullThrottle.drivetrain}",
                reachedSecondGear,
            )

            val beforeLift = fullThrottle.drivetrain
            controller.setSimulatedPedalThrottle(0.0)
            val liftShedEngineLoad = waitUntil(timeoutMs = 1_500L) {
                val state = controller.snapshot().drivetrain
                state.audioThrottle < 0.01 &&
                    state.engineLoad < 0.01 &&
                    state.rpm <= beforeLift.rpm + MAXIMUM_LIFT_SETTLE_RPM_OVERRUN &&
                    state.rawSpeedKmh <= beforeLift.rawSpeedKmh &&
                    state.speedKmh <= beforeLift.speedKmh + SPEED_ESTIMATE_SETTLE_TOLERANCE_KMH
            }
            val afterLift = controller.snapshot().drivetrain
            assertTrue(
                "scripted lift-off did not shed engine load cleanly: before=$beforeLift after=$afterLift",
                liftShedEngineLoad,
            )
        } finally {
            controller.stop()
            context.clear()
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

    private companion object {
        const val SPEED_ESTIMATE_SETTLE_TOLERANCE_KMH = 0.05
        const val MAXIMUM_LIFT_SETTLE_RPM_OVERRUN = 150.0
    }
}
