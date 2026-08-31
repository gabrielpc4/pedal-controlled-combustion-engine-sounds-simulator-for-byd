package com.gabrielpc.enginesoundsimulator.drive

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.IsolatedPreferenceContext
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PedalPredictionLatencyIntegrationTest {
    @Test
    fun pedalDirectionMovesRpmBeforeRawSpeedChanges() {
        val context = IsolatedPreferenceContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "pedal_prediction_latency",
        ).also { it.clear() }
        val controller = DriveController(context)
        val originalResponsiveRpm = controller.snapshot().loadResponsiveRpmEnabled

        try {
            controller.setLoadResponsiveRpmEnabled(false)
            controller.setInputMode(InputMode.SimulatedPedals)
            controller.setUiActive(true)
            controller.start()
            assertTrue(
                "engine and decoded audio should become ready before latency measurement",
                waitUntil(timeoutMs = 20_000L) {
                    val snapshot = controller.snapshot()
                    snapshot.engineSoundEnabled && snapshot.carAudioReady && snapshot.drivetrain.rpm > 0.0
                },
            )
            controller.setSimulatedPedalThrottle(0.0)
            SystemClock.sleep(500L)

            val initialAcceleration = measureSignalReaction(
                controller = controller,
                label = "initial-light-acceleration",
                applySignal = { controller.setSimulatedPedalThrottle(0.40) },
                rpmMoved = { current, initial -> current >= initial + MINIMUM_MEASURABLE_RPM_CHANGE },
            )
            assertRpmReactedInNormalSim(initialAcceleration)

            assertTrue(
                "simulated car should become meaningfully rolling before lift measurement",
                waitUntil(timeoutMs = 4_000L) { controller.snapshot().drivetrain.rawSpeedKmh >= 2.0 },
            )
            // Move away from the just-crossed lower edge of the truncated bin. Otherwise passive
            // drag can legitimately produce the next RAW value before there is time to measure
            // whether pedal intent reacts while RAW is still unchanged.
            SystemClock.sleep(350L)
            val release = measureSignalReaction(
                controller = controller,
                label = "rolling-pedal-release",
                applySignal = { controller.setSimulatedPedalThrottle(0.0) },
                rpmMoved = { current, initial -> current <= initial - MINIMUM_MEASURABLE_RPM_CHANGE },
            )
            assertRpmReactedInNormalSim(release)

            // The release probe normally finishes exactly when RAW crosses downward. Give the
            // physical simulator time to move inside that new bin before testing acceleration;
            // otherwise the next upward crossing can occur within one controller tick.
            SystemClock.sleep(750L)
            val sameBinAcceleration = measureSignalReaction(
                controller = controller,
                label = "rolling-same-bin-acceleration",
                applySignal = { controller.setSimulatedPedalThrottle(0.55) },
                rpmMoved = { current, initial -> current >= initial + MINIMUM_MEASURABLE_RPM_CHANGE },
            )
            assertRpmReactedInNormalSim(sameBinAcceleration)
        } finally {
            controller.setSimulatedPedalThrottle(0.0)
            controller.setLoadResponsiveRpmEnabled(originalResponsiveRpm)
            controller.stop()
            context.clear()
        }
    }

    @Test
    fun fixedHighSpeedRpmChangesDirectionWithoutAnotherRawSample() {
        listOf(0.01, 0.10, 0.25, 0.50, 1.0).forEach { throttle ->
            val simulation = fixedSpeedSimulation()
            val baselineRpm = simulation.state.rpm
            val accelerationMs = firstSimulationReactionMs(
                simulation = simulation,
                throttle = throttle,
                initialRpm = baselineRpm,
                rpmMoved = { current, initial -> current >= initial + MINIMUM_MEASURABLE_RPM_CHANGE },
            )
            repeat((FIXED_SIGNAL_HOLD_MS / FIXED_STEP_MILLIS).toInt()) {
                simulation.update(
                    DriverInput(throttle = throttle, externalSpeedKmh = FIXED_RAW_SPEED_KMH),
                    FIXED_STEP_SECONDS,
                )
            }
            val loadedRpm = simulation.state.rpm
            val releaseMs = firstSimulationReactionMs(
                simulation = simulation,
                throttle = 0.0,
                initialRpm = loadedRpm,
                rpmMoved = { current, initial -> current <= initial - MINIMUM_MEASURABLE_RPM_CHANGE },
            )

            Log.i(
                LOG_TAG,
                "fixed-${FIXED_RAW_SPEED_KMH}kmh throttle=$throttle raw=${simulation.state.rawSpeedKmh} " +
                    "accelerate=${accelerationMs}ms release=${releaseMs}ms baselineRpm=$baselineRpm " +
                    "loadedRpm=$loadedRpm finalRpm=${simulation.state.rpm}",
            )
            assertNotNull("fixed RAW acceleration at $throttle must move RPM", accelerationMs)
            assertNotNull("fixed RAW release from $throttle must move RPM downward", releaseMs)
            assertTrue(
                "fixed RAW acceleration at $throttle took $accelerationMs ms",
                accelerationMs!! <= MAXIMUM_RPM_REACTION_MS,
            )
            assertTrue(
                "fixed RAW release from $throttle took $releaseMs ms",
                releaseMs!! <= MAXIMUM_RPM_REACTION_MS,
            )
            assertTrue(
                "RAW must stay exactly fixed during the high-speed probe",
                simulation.state.rawSpeedKmh == FIXED_RAW_SPEED_KMH,
            )
        }
    }

    @Test
    fun onePercentPedalKeepsRpmMovingThroughFirstRawBoundary() {
        val simulation = EngineSimulation().apply {
            loadResponsiveRpmEnabled = false
            engageAtIdle()
            repeat(400) {
                update(
                    DriverInput(throttle = 0.0, externalSpeedKmh = FIRST_BOUNDARY_START_KMH),
                    FIXED_STEP_SECONDS,
                )
            }
        }
        val baselineRpm = simulation.state.rpm
        val firstReactionMs = firstSimulationReactionMs(
            simulation = simulation,
            throttle = 0.01,
            initialRpm = baselineRpm,
            rpmMoved = { current, initial -> current >= initial + MINIMUM_MEASURABLE_RPM_CHANGE },
            rawSpeedKmh = FIRST_BOUNDARY_START_KMH,
        )
        repeat((FIRST_BOUNDARY_PEDAL_HOLD_MS / FIXED_STEP_MILLIS).toInt()) {
            simulation.update(
                DriverInput(throttle = 0.01, externalSpeedKmh = FIRST_BOUNDARY_START_KMH),
                FIXED_STEP_SECONDS,
            )
        }
        val rpmBeforeBoundary = simulation.state.rpm
        var previousRpm = rpmBeforeBoundary
        var maximumFrameRpmChange = 0.0
        repeat((FIRST_BOUNDARY_OBSERVATION_MS / FIXED_STEP_MILLIS).toInt()) {
            val state = simulation.update(
                DriverInput(throttle = 0.01, externalSpeedKmh = FIRST_BOUNDARY_START_KMH + 1.0),
                FIXED_STEP_SECONDS,
            )
            maximumFrameRpmChange = maxOf(maximumFrameRpmChange, state.rpm - previousRpm)
            previousRpm = state.rpm
        }
        val finalState = simulation.state

        Log.i(
            LOG_TAG,
            "first-boundary throttle=0.01 raw=${FIRST_BOUNDARY_START_KMH}->${finalState.rawSpeedKmh} " +
                "reaction=${firstReactionMs}ms baselineRpm=$baselineRpm beforeBoundaryRpm=$rpmBeforeBoundary " +
                "finalRpm=${finalState.rpm} maxFrameRpmChange=$maximumFrameRpmChange",
        )
        assertNotNull("1% pedal must move RPM before the first RAW boundary", firstReactionMs)
        assertTrue("1% pedal reaction took $firstReactionMs ms", firstReactionMs!! <= MAXIMUM_RPM_REACTION_MS)
        assertTrue(
            "RPM must keep advancing after only the first RAW boundary",
            finalState.rpm > rpmBeforeBoundary + MINIMUM_POST_BOUNDARY_RPM_CHANGE,
        )
        assertTrue(
            "the first RAW boundary must not produce a 120 ms RPM step: $maximumFrameRpmChange",
            maximumFrameRpmChange < MAXIMUM_FIRST_BOUNDARY_FRAME_RPM_CHANGE,
        )
        assertTrue("the probe must never provide a second boundary", finalState.rawSpeedKmh == 13.0)
    }

    @Test
    fun releaseAtSixMovesRpmBeforeFiveAndKeepsFirstDropSmooth() {
        val simulation = EngineSimulation().apply {
            loadResponsiveRpmEnabled = false
            engageAtIdle()
        }
        for (rawKmh in 0..6) {
            repeat((1_000L / FIXED_STEP_MILLIS).toInt()) {
                simulation.update(
                    DriverInput(throttle = 0.40, externalSpeedKmh = rawKmh.toDouble()),
                    FIXED_STEP_SECONDS,
                )
            }
        }
        val rpmBeforeRelease = simulation.state.rpm
        repeat((RELEASE_BEFORE_FIRST_DROP_MS / FIXED_STEP_MILLIS).toInt()) {
            simulation.update(
                DriverInput(throttle = 0.0, externalSpeedKmh = 6.0),
                FIXED_STEP_SECONDS,
            )
        }
        val rpmBeforeFirstRawDrop = simulation.state.rpm
        var previousRpm = rpmBeforeFirstRawDrop
        var maximumFrameRpmChange = 0.0
        repeat((FIRST_BOUNDARY_OBSERVATION_MS / FIXED_STEP_MILLIS).toInt()) {
            val state = simulation.update(
                DriverInput(throttle = 0.0, externalSpeedKmh = 5.0),
                FIXED_STEP_SECONDS,
            )
            maximumFrameRpmChange = maxOf(maximumFrameRpmChange, kotlin.math.abs(state.rpm - previousRpm))
            previousRpm = state.rpm
        }
        val rpmAfterInitialFirstDrop = simulation.state.rpm
        repeat((FIRST_BIN_CONTINUATION_MS / FIXED_STEP_MILLIS).toInt()) {
            val state = simulation.update(
                DriverInput(throttle = 0.0, externalSpeedKmh = 5.0),
                FIXED_STEP_SECONDS,
            )
            maximumFrameRpmChange = maxOf(maximumFrameRpmChange, kotlin.math.abs(state.rpm - previousRpm))
            previousRpm = state.rpm
        }
        val finalState = simulation.state

        Log.i(
            LOG_TAG,
            "release-6-to-5 raw=6.0->${finalState.rawSpeedKmh} beforeReleaseRpm=$rpmBeforeRelease " +
                "beforeFirstDropRpm=$rpmBeforeFirstRawDrop afterInitialDropRpm=$rpmAfterInitialFirstDrop " +
                "finalRpm=${finalState.rpm} maxFrameRpmChange=$maximumFrameRpmChange",
        )
        assertTrue(
            "RPM must visibly fall while RAW is still 6 km/h",
            rpmBeforeFirstRawDrop < rpmBeforeRelease - MINIMUM_RELEASE_BEFORE_BOUNDARY_RPM_CHANGE,
        )
        assertTrue(
            "the first 6-to-5 RAW drop must not create a 120 ms step: $maximumFrameRpmChange",
            maximumFrameRpmChange < MAXIMUM_FIRST_BOUNDARY_FRAME_RPM_CHANGE,
        )
        assertTrue(
            "RPM must keep falling while RAW stays at 5 instead of waiting for 4",
            finalState.rpm < rpmAfterInitialFirstDrop - MINIMUM_FIRST_BIN_CONTINUATION_RPM_CHANGE,
        )
        assertTrue("the probe must never provide 4 km/h", finalState.rawSpeedKmh == 5.0)
    }

    @Test
    fun maximumSimulatedUphillLetsFallingTelemetryOverrideHeldThrottleSmoothly() {
        val simulation = EngineSimulation().apply {
            loadResponsiveRpmEnabled = false
            engageAtIdle()
        }
        repeat((1_000L / FIXED_STEP_MILLIS).toInt()) {
            simulation.update(
                DriverInput(throttle = 1.0, simulateCoastRegen = true),
                FIXED_STEP_SECONDS,
            )
        }

        var state = simulation.state
        var previousRawKmh = state.rawSpeedKmh
        var previousPresentationKmh = state.presentationSpeedKmh
        var previousRpm = state.rpm
        var firstDropFrame: Int? = null
        var firstDropRawBeforeKmh: Double? = null
        var firstDropRawAfterKmh: Double? = null
        var firstDropPresentationStep: Double? = null
        var firstDropRpmStep: Double? = null
        var firstDropPresentationKmh: Double? = null
        var presentationKmhAfterDwell: Double? = null

        repeat((3_000L / FIXED_STEP_MILLIS).toInt()) { frame ->
            state = simulation.update(
                DriverInput(
                    throttle = 0.22,
                    simulateCoastRegen = true,
                    simulatedUphillDragGrade = 0.30,
                ),
                FIXED_STEP_SECONDS,
            )
            if (state.rawSpeedKmh < previousRawKmh && firstDropFrame == null) {
                firstDropFrame = frame
                firstDropRawBeforeKmh = previousRawKmh
                firstDropRawAfterKmh = state.rawSpeedKmh
                firstDropPresentationStep = kotlin.math.abs(
                    state.presentationSpeedKmh - previousPresentationKmh,
                )
                firstDropRpmStep = kotlin.math.abs(state.rpm - previousRpm)
                firstDropPresentationKmh = state.presentationSpeedKmh
            }
            if (firstDropFrame != null &&
                presentationKmhAfterDwell == null &&
                frame - firstDropFrame!! >= FIRST_UPHILL_DROP_DWELL_FRAMES
            ) {
                presentationKmhAfterDwell = state.presentationSpeedKmh
            }
            previousRawKmh = state.rawSpeedKmh
            previousPresentationKmh = state.presentationSpeedKmh
            previousRpm = state.rpm
        }

        Log.i(
            LOG_TAG,
            "uphill-30 throttle=0.22 firstDropFrame=$firstDropFrame " +
                "firstRaw=$firstDropRawBeforeKmh->$firstDropRawAfterKmh raw=${state.rawSpeedKmh} " +
                "presentation=${state.presentationSpeedKmh} rpm=${state.rpm} " +
                "predictedAcceleration=${state.presentationAccelerationKmhPerSecond} " +
                "firstPresentationStep=$firstDropPresentationStep firstRpmStep=$firstDropRpmStep " +
                "firstPresentation=$firstDropPresentationKmh afterDwell=$presentationKmhAfterDwell",
        )
        assertNotNull("30% uphill must make light held throttle lose RAW speed", firstDropFrame)
        assertTrue(
            "the first falling RAW boundary must not step the presentation speed: $firstDropPresentationStep",
            firstDropPresentationStep!! < MAXIMUM_UPHILL_FIRST_DROP_SPEED_STEP_KMH,
        )
        assertTrue(
            "the first falling RAW boundary must not step the audio RPM: $firstDropRpmStep",
            firstDropRpmStep!! < MAXIMUM_FIRST_BOUNDARY_FRAME_RPM_CHANGE,
        )
        assertTrue(
            "presentation must keep falling after the first RAW drop: first=$firstDropPresentationKmh " +
                "after=$presentationKmhAfterDwell",
            presentationKmhAfterDwell != null &&
                presentationKmhAfterDwell!! < firstDropPresentationKmh!! - MINIMUM_UPHILL_DWELL_DROP_KMH,
        )
    }

    private fun fixedSpeedSimulation(): EngineSimulation {
        return EngineSimulation().apply {
            loadResponsiveRpmEnabled = false
            engageAtIdle()
            repeat(400) {
                update(
                    DriverInput(throttle = 0.0, externalSpeedKmh = FIXED_RAW_SPEED_KMH),
                    FIXED_STEP_SECONDS,
                )
            }
        }
    }

    private fun measureSignalReaction(
        controller: DriveController,
        label: String,
        applySignal: () -> Unit,
        rpmMoved: (current: Double, initial: Double) -> Boolean,
    ): ReactionMeasurement {
        val initial = controller.snapshot().drivetrain
        val startedAtMs = SystemClock.elapsedRealtime()
        applySignal()
        var rpmReactionMs: Long? = null
        var rawChangeMs: Long? = null
        var latest = initial

        while (SystemClock.elapsedRealtime() - startedAtMs <= MEASUREMENT_WINDOW_MS) {
            latest = controller.snapshot().drivetrain
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            if (rpmReactionMs == null && rpmMoved(latest.rpm, initial.rpm)) {
                rpmReactionMs = elapsedMs
            }
            if (rawChangeMs == null && latest.rawSpeedKmh != initial.rawSpeedKmh) {
                rawChangeMs = elapsedMs
            }
            if (rpmReactionMs != null && rawChangeMs != null) {
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }

        return ReactionMeasurement(
            label = label,
            initialRpm = initial.rpm,
            finalRpm = latest.rpm,
            initialRawKmh = initial.rawSpeedKmh,
            finalRawKmh = latest.rawSpeedKmh,
            rpmReactionMs = rpmReactionMs,
            rawChangeMs = rawChangeMs,
        ).also { Log.i(LOG_TAG, it.toString()) }
    }

    private fun assertRpmReactedInNormalSim(measurement: ReactionMeasurement) {
        assertNotNull("${measurement.label}: RPM never reacted: $measurement", measurement.rpmReactionMs)
        assertTrue(
            "${measurement.label}: RPM reacted too slowly for normal SIM propulsion: $measurement",
            measurement.rpmReactionMs!! <= MAXIMUM_NORMAL_SIM_RPM_REACTION_MS,
        )
    }

    private fun firstSimulationReactionMs(
        simulation: EngineSimulation,
        throttle: Double,
        initialRpm: Double,
        rpmMoved: (current: Double, initial: Double) -> Boolean,
        rawSpeedKmh: Double = FIXED_RAW_SPEED_KMH,
    ): Long? {
        repeat((MEASUREMENT_WINDOW_MS / FIXED_STEP_MILLIS).toInt()) { frame ->
            val state = simulation.update(
                DriverInput(throttle = throttle, externalSpeedKmh = rawSpeedKmh),
                FIXED_STEP_SECONDS,
            )
            if (rpmMoved(state.rpm, initialRpm)) {
                return (frame + 1L) * FIXED_STEP_MILLIS
            }
        }

        return null
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(20L)
        }

        return predicate()
    }

    private data class ReactionMeasurement(
        val label: String,
        val initialRpm: Double,
        val finalRpm: Double,
        val initialRawKmh: Double,
        val finalRawKmh: Double,
        val rpmReactionMs: Long?,
        val rawChangeMs: Long?,
    )

    private companion object {
        const val LOG_TAG = "PedalLatencyProbe"
        const val MINIMUM_MEASURABLE_RPM_CHANGE = 10.0
        const val MAXIMUM_RPM_REACTION_MS = 250L
        const val MAXIMUM_NORMAL_SIM_RPM_REACTION_MS = 500L
        const val MEASUREMENT_WINDOW_MS = 1_500L
        const val POLL_INTERVAL_MS = 5L
        const val FIXED_STEP_MILLIS = 5L
        const val FIXED_STEP_SECONDS = FIXED_STEP_MILLIS / 1_000.0
        const val FIXED_SIGNAL_HOLD_MS = 800L
        const val FIXED_RAW_SPEED_KMH = 42.0
        const val FIRST_BOUNDARY_START_KMH = 12.0
        const val FIRST_BOUNDARY_PEDAL_HOLD_MS = 1_000L
        const val FIRST_BOUNDARY_OBSERVATION_MS = 750L
        const val FIRST_BIN_CONTINUATION_MS = 2_000L
        const val MINIMUM_POST_BOUNDARY_RPM_CHANGE = 25.0
        const val MINIMUM_FIRST_BIN_CONTINUATION_RPM_CHANGE = 50.0
        const val RELEASE_BEFORE_FIRST_DROP_MS = 1_000L
        const val MINIMUM_RELEASE_BEFORE_BOUNDARY_RPM_CHANGE = 25.0
        const val MAXIMUM_FIRST_BOUNDARY_FRAME_RPM_CHANGE = 8.0
        const val FIRST_UPHILL_DROP_DWELL_FRAMES = 150
        const val MAXIMUM_UPHILL_FIRST_DROP_SPEED_STEP_KMH = 0.03
        const val MINIMUM_UPHILL_DWELL_DROP_KMH = 0.05
    }
}
