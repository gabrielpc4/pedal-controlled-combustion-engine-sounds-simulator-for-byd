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
        val established = trace.filter { it.physicalKmh >= 3.0 }
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
        assertTrue("pedal seed must remain in the reported zero interval: $maximum", maximum <= 1.0)
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
