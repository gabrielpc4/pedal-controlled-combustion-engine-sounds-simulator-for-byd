package com.gabrielpc.enginesoundsimulator.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class QuantizedPresentationSpeedEstimatorTest {
    @Test
    fun verySlowIntegerTelemetryAdvancesContinuouslyBetweenBoundaries() {
        val trace = accelerationTrace(
            accelerationKmhPerSecond = 0.2,
            durationSeconds = 30.0,
            throttle = 0.25,
        )
        val established = trace.filter { it.physicalKmh in 1.0..5.5 }
        val deltas = established.zipWithNext().map { (first, second) -> second.estimateKmh - first.estimateKmh }
        val advancingFrames = deltas.count { it > 1.0e-6 }

        assertTrue("slow reconstructed speed must stay monotonic", deltas.all { it >= -1.0e-8 })
        assertTrue(
            "slow reconstructed speed must advance on most 200 Hz frames: $advancingFrames/${deltas.size}",
            advancingFrames > deltas.size * 0.80,
        )
        assertTrue(
            "integer crossings must not create a presentation jump: max=${deltas.maxOrNull()}",
            deltas.maxOrNull()!! < 0.025,
        )
    }

    @Test
    fun strongAccelerationStaysCloseToPhysicalSpeed() {
        val trace = accelerationTrace(
            accelerationKmhPerSecond = 6.0,
            durationSeconds = 3.0,
            throttle = 1.0,
        )
        // The 0..1 km/h interval intentionally uses only the capped pedal seed. Measure strong
        // tracking after repeated integer crossings have established telemetry velocity.
        val established = trace.filter { it.physicalKmh >= 4.0 }
        val maximumError = established.maxOf { abs(it.estimateKmh - it.physicalKmh) }

        assertTrue("strong acceleration error must stay below 0.8 km/h: $maximumError", maximumError < 0.8)
    }

    @Test
    fun firstBinPedalSeedCannotInventAReportedBoundary() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var maximum = estimator.update(0.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)

        repeat((2.0 / STEP).toInt()) {
            maximum = maxOf(
                maximum,
                estimator.update(0.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE),
            )
        }

        assertTrue("pedal seed should move presentation speed before the first crossing", maximum > 0.70)
        assertTrue(
            "pedal seed must remain within the bounded poll allowance: $maximum",
            maximum <= 1.08,
        )
    }

    @Test
    fun throttleStartsPredictionBeforeRawSpeedChangesAtAnyRoadSpeed() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(42.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((0.5 / STEP).toInt()) {
            estimate = estimator.update(42.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeThrottle = estimate

        repeat((0.25 / STEP).toInt()) {
            estimate = estimator.update(42.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertEquals("RAW speed must remain unchanged for this scenario", 42.0, floor(estimate), 0.0)
        assertTrue(
            "pedal intent must advance presentation before the next whole km/h: before=$beforeThrottle after=$estimate",
            estimate > beforeThrottle + 0.05,
        )
    }

    @Test
    fun onePercentBydThrottleStartsPredictionBeforeFirstRawChange() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((3.0 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeThrottle = estimate

        repeat((0.75 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.01, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertEquals("RAW speed must remain in the same truncated bin", 12.0, floor(estimate), 0.0)
        assertTrue(
            "the smallest BYD pedal step must visibly advance before RAW changes: before=$beforeThrottle after=$estimate",
            estimate > beforeThrottle + 0.08,
        )
    }

    @Test
    fun firstRawChangeKeepsPedalPredictionMovingWithoutWaitingForSecondChange() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((3.0 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        repeat((2.0 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.01, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeFirstRawChange = estimate
        val afterFirstRawChange = mutableListOf<Double>()

        repeat((0.75 / STEP).toInt()) {
            estimate = estimator.update(13.0, throttle = 0.01, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
            afterFirstRawChange += estimate
        }
        val maximumFrameChange = listOf(beforeFirstRawChange).plus(afterFirstRawChange)
            .zipWithNext()
            .maxOf { (first, second) -> second - first }

        assertTrue(
            "prediction must continue after the first RAW change without waiting for 14 km/h: $estimate",
            estimate > beforeFirstRawChange + 0.20,
        )
        assertTrue(
            "the first RAW change must be recovered continuously rather than in 120 ms: $maximumFrameChange",
            maximumFrameChange < 0.012,
        )
    }

    @Test
    fun firstRawChangeNearHiddenBinEdgeDoesNotCreateAFastCorrection() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((2.0 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        repeat((0.05 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeFirstRawChange = estimate
        val afterFirstRawChange = mutableListOf<Double>()

        repeat((0.35 / STEP).toInt()) {
            estimate = estimator.update(13.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
            afterFirstRawChange += estimate
        }
        val maximumFrameChange = listOf(beforeFirstRawChange).plus(afterFirstRawChange)
            .zipWithNext()
            .maxOf { (first, second) -> second - first }

        assertTrue(
            "an early first boundary must not be mistaken for a full one-km/h traversal: $maximumFrameChange",
            maximumFrameChange < 0.015,
        )
        assertTrue(
            "pedal prediction must continue moving after that first boundary: before=$beforeFirstRawChange after=$estimate",
            estimate > beforeFirstRawChange + 0.20,
        )
    }

    @Test
    fun releasingOnePercentBydThrottlePredictsDownBeforeRawChanges() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(12.0, throttle = 0.01, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((2.0 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.01, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeRelease = estimate

        repeat((0.50 / STEP).toInt()) {
            estimate = estimator.update(12.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertTrue("release prediction must remain bounded near the unchanged RAW bin", estimate >= 11.42)
        assertTrue(
            "release from the smallest BYD pedal step must turn RPM prediction down: before=$beforeRelease after=$estimate",
            estimate < beforeRelease - 0.05,
        )
    }

    @Test
    fun pedalReleaseReversesAnEstablishedRiseBeforeRawSpeedChanges() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(20.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(20.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(21.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        repeat((0.20 / STEP).toInt()) {
            estimate = estimator.update(22.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeRelease = estimate

        repeat((0.30 / STEP).toInt()) {
            estimate = estimator.update(22.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertTrue("presentation must stay inside the bounded reversal allowance: $estimate", estimate >= 21.42)
        assertTrue(
            "pedal release must turn presentation downward before another RAW boundary: before=$beforeRelease after=$estimate",
            estimate < beforeRelease - 0.05,
        )
    }

    @Test
    fun releaseAtSixFallsBeforeFiveAndCrossesFirstDownwardBoundarySmoothly() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(0.0, throttle = 0.40, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        for (rawKmh in 1..6) {
            repeat((1.0 / STEP).toInt()) {
                estimate = estimator.update(
                    rawKmh.toDouble(),
                    throttle = 0.40,
                    brake = 0.0,
                    dt = STEP,
                    responseSeconds = RESPONSE,
                )
            }
        }
        val beforeRelease = estimate

        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(6.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeFirstRawDrop = estimate
        val afterFirstRawDrop = mutableListOf<Double>()
        repeat((0.75 / STEP).toInt()) {
            estimate = estimator.update(5.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
            afterFirstRawDrop += estimate
        }
        val afterInitialFirstRawDrop = estimate
        repeat((2.0 / STEP).toInt()) {
            estimate = estimator.update(5.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val maximumFrameChange = listOf(beforeFirstRawDrop).plus(afterFirstRawDrop)
            .zipWithNext()
            .maxOf { (first, second) -> abs(second - first) }

        assertTrue(
            "release must visibly lower presentation while RAW is still 6: before=$beforeRelease after=$beforeFirstRawDrop",
            beforeFirstRawDrop < beforeRelease - 0.20,
        )
        assertTrue(
            "the first 6-to-5 RAW drop must continue downward without a 120 ms correction: $maximumFrameChange",
            maximumFrameChange < 0.015,
        )
        assertTrue(
            "prediction must already be moving immediately after the first downward boundary",
            afterFirstRawDrop.last() < beforeFirstRawDrop - 0.02,
        )
        assertTrue(
            "prediction must keep falling while RAW stays at 5 instead of waiting for 4: initial=$afterInitialFirstRawDrop late=$estimate",
            estimate < afterInitialFirstRawDrop - 0.20,
        )
    }

    @Test
    fun fallingTelemetryOverridesLightThrottleWithoutAOneKilometerStep() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(0.0, throttle = 0.45, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        for (rawKmh in 1..6) {
            repeat((0.50 / STEP).toInt()) {
                estimate = estimator.update(
                    rawKmh.toDouble(),
                    throttle = 0.45,
                    brake = 0.0,
                    dt = STEP,
                    responseSeconds = RESPONSE,
                )
            }
        }

        // A virtual steep climb can overpower a held light throttle. The first reported lower
        // km/h must reverse the presentation smoothly instead of waiting for the next boundary.
        val beforeFirstDrop = estimate
        val afterFirstDrop = mutableListOf<Double>()
        repeat((0.75 / STEP).toInt()) {
            estimate = estimator.update(5.0, throttle = 0.18, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
            afterFirstDrop += estimate
        }
        val maximumFrameChange = listOf(beforeFirstDrop).plus(afterFirstDrop)
            .zipWithNext()
            .maxOf { (first, second) -> abs(second - first) }

        assertTrue(
            "first falling RAW boundary must not be a visible step: $maximumFrameChange",
            maximumFrameChange < 0.015,
        )
        assertTrue(
            "falling telemetry must reverse prediction despite held throttle: before=$beforeFirstDrop after=$estimate",
            estimate < beforeFirstDrop - 0.06,
        )
        val afterInitialDrop = estimate
        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(5.0, throttle = 0.18, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        assertTrue(
            "prediction must keep descending in the lowered RAW bin: initial=$afterInitialDrop late=$estimate",
            estimate < afterInitialDrop - 0.10,
        )
    }

    @Test
    fun firstCrossingAfterResetContinuesPedalPredictionWithoutASurge() {
        val estimator = QuantizedPresentationSpeedEstimator()
        estimator.update(3.0, throttle = 0.25, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        estimator.reset()
        var estimate = estimator.update(5.0, throttle = 0.25, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)

        repeat((2.0 / STEP).toInt()) {
            estimate = estimator.update(5.0, throttle = 0.25, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val beforeCrossing = estimate
        val afterCrossing = mutableListOf<Double>()
        repeat((0.5 / STEP).toInt()) {
            estimate = estimator.update(6.0, throttle = 0.25, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
            afterCrossing += estimate
        }
        val crossingDeltas = listOf(beforeCrossing).plus(afterCrossing)
            .zipWithNext()
            .map { (first, second) -> second - first }

        assertTrue("pedal prediction must already move the estimate before the first RAW change", beforeCrossing > 5.25)
        assertTrue("the first RAW change must keep moving toward the new interval", afterCrossing.last() > beforeCrossing + 0.30)
        assertTrue(
            "reset recovery must spread the first boundary correction across frames: max=${crossingDeltas.maxOrNull()}",
            crossingDeltas.maxOrNull()!! < 0.012,
        )
    }

    @Test
    fun brakeCancelsFirstBinPedalPrediction() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(0.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((0.35 / STEP).toInt()) {
            estimate = estimator.update(0.0, throttle = 1.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }
        val estimateAtBrake = estimate

        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(0.0, throttle = 1.0, brake = 0.03, dt = STEP, responseSeconds = RESPONSE)
        }

        assertTrue("brake must prevent continued first-bin prediction", estimate <= estimateAtBrake + 0.12)
        assertTrue("brake-cancelled prediction must remain inside its interval", estimate <= 1.0)
    }

    @Test
    fun steadyIntegerSpeedStopsDrifting() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(10.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((4.0 / STEP).toInt()) {
            estimate = estimator.update(10.0, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertEquals(10.0, estimate, 1.0e-6)
    }

    @Test
    fun brakingIsMonotonicAfterDirectionChangeAndZeroSettlesExactly() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var sampledMeasurement = 8.0
        var estimate = estimator.update(sampledMeasurement, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        val descending = mutableListOf<Double>()
        var physicalKmh = 8.0

        repeat((5.0 / STEP).toInt()) { frame ->
            physicalKmh = (physicalKmh - 2.0 * STEP).coerceAtLeast(0.0)
            if (frame % TELEMETRY_FRAME_INTERVAL == 0) {
                sampledMeasurement = floor(physicalKmh)
            }
            estimate = estimator.update(
                sampledMeasurement,
                throttle = 0.0,
                brake = 0.5,
                dt = STEP,
                responseSeconds = RESPONSE,
            )
            if (sampledMeasurement <= 7.0) {
                descending += estimate
            }
        }
        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(0.0, throttle = 0.0, brake = 0.5, dt = STEP, responseSeconds = RESPONSE)
            descending += estimate
        }

        assertTrue(
            "direction reversal must not overshoot upward",
            descending.zipWithNext().all { (first, second) -> second <= first + 1.0e-8 },
        )
        assertEquals(0.0, estimate, 0.0)
    }

    @Test
    fun fractionalInputIsDefensivelyTruncated() {
        val estimator = QuantizedPresentationSpeedEstimator()
        var estimate = estimator.update(4.9, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        repeat((1.0 / STEP).toInt()) {
            estimate = estimator.update(4.9, throttle = 0.0, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)
        }

        assertEquals("the BYD path assumes whole values and truncates any unexpected fraction", 4.0, estimate, 0.0)
    }

    private fun accelerationTrace(
        accelerationKmhPerSecond: Double,
        durationSeconds: Double,
        throttle: Double,
    ): List<TraceFrame> {
        val estimator = QuantizedPresentationSpeedEstimator()
        var physicalKmh = 0.0
        var sampledMeasurement = 0.0
        val frames = mutableListOf<TraceFrame>()
        estimator.update(sampledMeasurement, throttle, brake = 0.0, dt = STEP, responseSeconds = RESPONSE)

        repeat((durationSeconds / STEP).toInt()) { frame ->
            physicalKmh += accelerationKmhPerSecond * STEP
            if (frame % TELEMETRY_FRAME_INTERVAL == 0) {
                sampledMeasurement = floor(physicalKmh)
            }
            val estimate = estimator.update(
                sampledMeasurement,
                throttle = throttle,
                brake = 0.0,
                dt = STEP,
                responseSeconds = RESPONSE,
            )
            frames += TraceFrame(physicalKmh, sampledMeasurement, estimate)
        }

        return frames
    }

    private data class TraceFrame(
        val physicalKmh: Double,
        val measurementKmh: Double,
        val estimateKmh: Double,
    )

    private companion object {
        const val STEP = 1.0 / 200.0
        const val TELEMETRY_FRAME_INTERVAL = 4
        const val RESPONSE = 0.12
    }
}
