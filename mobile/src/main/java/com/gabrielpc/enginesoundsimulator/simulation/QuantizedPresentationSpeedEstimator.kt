package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Reconstructs presentation-only motion from the truncated whole-km/h value exposed by BYD.
 *
 * Integer changes identify quantizer-boundary crossings. Their timing provides a measured speed
 * rate that can advance continuously between later crossings. A conservative pedal seed covers
 * the otherwise unknowable first interval without affecting gearbox or vehicle decisions. When
 * the first boundary arrives after history was reset, any missed distance is recovered over
 * multiple frames instead of being applied as one visible and audible step.
 */
internal class QuantizedPresentationSpeedEstimator {
    private var initialized = false
    private var estimateKmh = 0.0
    private var estimateVelocityKmhPerSecond = 0.0
    private var predictedKmh = 0.0
    private var observedVelocityKmhPerSecond = 0.0
    private var pedalSeedVelocityKmhPerSecond = 0.0
    private var previousMeasurementKmh = 0.0
    private var previousCrossingBoundaryKmh = 0.0
    private var previousCrossingDirection = 0.0
    private var secondsSinceMeasurementChanged = 0.0
    private var hasCrossingBoundary = false
    private var hasObservedCrossing = false
    private var crossingPredictionStale = false
    private var pendingBoundaryKmh: Double? = null
    private var pendingBoundaryDirection = 0.0
    private var pendingBoundaryResponseSeconds = FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS

    fun reset() {
        initialized = false
        estimateKmh = 0.0
        estimateVelocityKmhPerSecond = 0.0
        predictedKmh = 0.0
        observedVelocityKmhPerSecond = 0.0
        pedalSeedVelocityKmhPerSecond = 0.0
        previousMeasurementKmh = 0.0
        previousCrossingBoundaryKmh = 0.0
        previousCrossingDirection = 0.0
        secondsSinceMeasurementChanged = 0.0
        hasCrossingBoundary = false
        hasObservedCrossing = false
        crossingPredictionStale = false
        pendingBoundaryKmh = null
        pendingBoundaryDirection = 0.0
        pendingBoundaryResponseSeconds = FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS
    }

    fun update(
        measurementKmh: Double,
        throttle: Double,
        brake: Double,
        dt: Double,
        responseSeconds: Double,
    ): Double {
        val measurement = floor(measurementKmh.coerceAtLeast(0.0))
        val stepSeconds = dt.coerceIn(MINIMUM_STEP_SECONDS, MAXIMUM_STEP_SECONDS)
        if (!initialized) {
            initialize(measurement)
            return estimateKmh
        }

        secondsSinceMeasurementChanged += stepSeconds
        if (measurement != previousMeasurementKmh) {
            observeCrossing(measurement, stepSeconds, responseSeconds)
        }
        updateCrossingFreshness(stepSeconds)

        val motionVelocity = if (hasObservedCrossing) {
            observedVelocityKmhPerSecond
        } else {
            updatePedalSeedVelocity(
                measurementKmh = measurement,
                throttle = throttle,
                brake = brake,
                dt = stepSeconds,
            )
        }
        predictedKmh += motionVelocity * stepSeconds
        recoverPendingBoundary(stepSeconds)
        predictedKmh = clampPredictionToMeasuredBin(
            predictionKmh = predictedKmh,
            measurementKmh = measurement,
            direction = motionVelocity.sign,
            allowPollOverrun = hasObservedCrossing,
        )

        val crossingSeconds = if (hasObservedCrossing && abs(observedVelocityKmhPerSecond) > MINIMUM_TRACKED_VELOCITY) {
            1.0 / abs(observedVelocityKmhPerSecond)
        } else {
            0.0
        }
        val adaptiveResponseSeconds = if (crossingSeconds > 0.0) {
            responseForCrossing(responseSeconds, crossingSeconds)
        } else {
            responseSeconds.coerceIn(MINIMUM_RESPONSE_SECONDS, MAXIMUM_RESPONSE_SECONDS)
        }
        val result = followTarget(predictedKmh, stepSeconds, adaptiveResponseSeconds)

        if (measurement == 0.0 &&
            secondsSinceMeasurementChanged > ZERO_SETTLE_SECONDS &&
            result < ZERO_SETTLE_SPEED_KMH &&
            throttle <= ZERO_SETTLE_THROTTLE
        ) {
            estimateKmh = 0.0
            estimateVelocityKmhPerSecond = 0.0
            predictedKmh = 0.0
            observedVelocityKmhPerSecond = 0.0
            pedalSeedVelocityKmhPerSecond = 0.0
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
        }

        return estimateKmh
    }

    private fun initialize(measurementKmh: Double) {
        initialized = true
        estimateKmh = measurementKmh
        predictedKmh = measurementKmh
        previousMeasurementKmh = measurementKmh
    }

    private fun observeCrossing(
        measurementKmh: Double,
        dt: Double,
        responseSeconds: Double,
    ) {
        val previousMeasurement = previousMeasurementKmh
        val direction = (measurementKmh - previousMeasurement).sign
        val elapsedSeconds = secondsSinceMeasurementChanged.coerceAtLeast(dt)
        val crossingBoundaryKmh = if (direction > 0.0) {
            measurementKmh
        } else {
            measurementKmh + QUANTIZER_INTERVAL_WIDTH_KMH
        }
        val hasSameDirectionBoundary = hasCrossingBoundary && direction == previousCrossingDirection
        val hasKnownZeroOrigin = !hasCrossingBoundary && previousMeasurement == 0.0 && direction > 0.0
        val canInferVelocity = hasSameDirectionBoundary || hasKnownZeroOrigin
        val crossingDistanceKmh = when {
            hasSameDirectionBoundary -> abs(crossingBoundaryKmh - previousCrossingBoundaryKmh)
            hasKnownZeroOrigin -> crossingBoundaryKmh
            else -> 0.0
        }
        val crossingVelocity = if (canInferVelocity) {
            (direction * crossingDistanceKmh / elapsedSeconds)
                .coerceIn(-MAXIMUM_TRACKED_VELOCITY, MAXIMUM_TRACKED_VELOCITY)
        } else {
            0.0
        }

        if (!canInferVelocity || crossingVelocity * estimateVelocityKmhPerSecond < 0.0) {
            estimateVelocityKmhPerSecond = 0.0
        }
        observedVelocityKmhPerSecond = if (!canInferVelocity) {
            0.0
        } else if (!hasObservedCrossing || crossingVelocity * observedVelocityKmhPerSecond <= 0.0) {
            crossingVelocity
        } else {
            observedVelocityKmhPerSecond +
                (crossingVelocity - observedVelocityKmhPerSecond) * OBSERVED_VELOCITY_BLEND
        }
        val predictionMissedBoundary = if (direction > 0.0) {
            predictedKmh < crossingBoundaryKmh
        } else {
            predictedKmh > crossingBoundaryKmh
        }
        // There is no trustworthy crossing rate immediately after a reset. Recover that first
        // boundary progressively; once repeated or clearly fast crossings establish motion,
        // retaining the direct correction keeps deliberate acceleration responsive.
        val shouldRecoverBoundaryGradually = !hasSameDirectionBoundary &&
            (!canInferVelocity || abs(crossingVelocity) < FAST_CROSSING_VELOCITY_KMH_PER_SECOND)
        if (predictionMissedBoundary && shouldRecoverBoundaryGradually) {
            pendingBoundaryKmh = crossingBoundaryKmh
            pendingBoundaryDirection = direction
            val crossingResponseSeconds = responseForCrossing(responseSeconds, elapsedSeconds)
            pendingBoundaryResponseSeconds = if (canInferVelocity) {
                crossingResponseSeconds
            } else {
                max(FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS, crossingResponseSeconds)
            }
        } else if (predictionMissedBoundary) {
            predictedKmh = if (direction > 0.0) {
                max(predictedKmh, crossingBoundaryKmh)
            } else {
                min(predictedKmh, crossingBoundaryKmh)
            }
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
        } else {
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
        }
        if (canInferVelocity) {
            pedalSeedVelocityKmhPerSecond = 0.0
        }
        previousMeasurementKmh = measurementKmh
        previousCrossingBoundaryKmh = crossingBoundaryKmh
        previousCrossingDirection = direction
        secondsSinceMeasurementChanged = 0.0
        hasCrossingBoundary = true
        hasObservedCrossing = canInferVelocity
        crossingPredictionStale = false
    }

    private fun recoverPendingBoundary(dt: Double) {
        val boundaryKmh = pendingBoundaryKmh ?: return
        val boundaryReached = if (pendingBoundaryDirection > 0.0) {
            predictedKmh >= boundaryKmh
        } else {
            predictedKmh <= boundaryKmh
        }
        if (boundaryReached) {
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
            return
        }

        predictedKmh = approachExp(
            current = predictedKmh,
            target = boundaryKmh,
            timeConstant = pendingBoundaryResponseSeconds,
            dt = dt,
        )
        if (abs(predictedKmh - boundaryKmh) <= BOUNDARY_CORRECTION_EPSILON_KMH) {
            predictedKmh = boundaryKmh
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
        }
    }

    private fun updateCrossingFreshness(dt: Double) {
        if (!hasObservedCrossing || abs(observedVelocityKmhPerSecond) <= MINIMUM_TRACKED_VELOCITY) {
            return
        }

        val expectedCrossingSeconds = 1.0 / abs(observedVelocityKmhPerSecond)
        if (secondsSinceMeasurementChanged > expectedCrossingSeconds + STALE_CROSSING_GRACE_SECONDS) {
            crossingPredictionStale = true
        }
        if (crossingPredictionStale) {
            observedVelocityKmhPerSecond *= exp(-dt / STALE_VELOCITY_DECAY_SECONDS)
            if (abs(observedVelocityKmhPerSecond) < MINIMUM_TRACKED_VELOCITY) {
                observedVelocityKmhPerSecond = 0.0
                hasObservedCrossing = false
                hasCrossingBoundary = false
                previousCrossingDirection = 0.0
                crossingPredictionStale = false
            }
        }
    }

    private fun updatePedalSeedVelocity(
        measurementKmh: Double,
        throttle: Double,
        brake: Double,
        dt: Double,
    ): Double {
        if (brake.coerceIn(0.0, 1.0) >= PEDAL_SEED_BRAKE_CUTOFF) {
            pedalSeedVelocityKmhPerSecond = 0.0
            return 0.0
        }

        val targetVelocity = if (
            measurementKmh <= PEDAL_SEED_MAXIMUM_SPEED_KMH
        ) {
            throttle.coerceIn(0.0, 1.0) * PEDAL_SEED_MAXIMUM_VELOCITY_KMH_PER_SECOND
        } else {
            0.0
        }
        pedalSeedVelocityKmhPerSecond = approachExp(
            current = pedalSeedVelocityKmhPerSecond,
            target = targetVelocity,
            timeConstant = PEDAL_SEED_RESPONSE_SECONDS,
            dt = dt,
        )
        return pedalSeedVelocityKmhPerSecond
    }

    private fun clampPredictionToMeasuredBin(
        predictionKmh: Double,
        measurementKmh: Double,
        direction: Double,
        allowPollOverrun: Boolean,
    ): Double {
        val lowerBoundary = measurementKmh
        val upperBoundary = measurementKmh + QUANTIZER_INTERVAL_WIDTH_KMH
        val pollOverrunKmh = if (allowPollOverrun) MAXIMUM_POLL_OVERRUN_KMH else 0.0
        val minimum = when {
            pendingBoundaryDirection > 0.0 -> min(lowerBoundary, predictionKmh)
            direction < 0.0 -> (lowerBoundary - pollOverrunKmh).coerceAtLeast(0.0)
            else -> lowerBoundary
        }
        val maximum = when {
            pendingBoundaryDirection < 0.0 -> max(upperBoundary, predictionKmh)
            direction > 0.0 -> upperBoundary + pollOverrunKmh
            else -> upperBoundary
        }
        return predictionKmh.coerceIn(minimum, maximum)
    }

    private fun responseForCrossing(baseResponseSeconds: Double, crossingSeconds: Double): Double {
        return max(
            baseResponseSeconds.coerceIn(MINIMUM_RESPONSE_SECONDS, MAXIMUM_RESPONSE_SECONDS),
            min(MAXIMUM_SLOW_CROSSING_RESPONSE_SECONDS, crossingSeconds * SLOW_CROSSING_RESPONSE_FRACTION),
        )
    }

    private fun followTarget(targetKmh: Double, dt: Double, responseSeconds: Double): Double {
        if ((targetKmh - estimateKmh) * estimateVelocityKmhPerSecond < 0.0) {
            estimateVelocityKmhPerSecond = 0.0
        }
        val omega = 2.0 / responseSeconds
        val acceleration = omega * omega * (targetKmh - estimateKmh) -
            2.0 * omega * estimateVelocityKmhPerSecond
        val previousEstimate = estimateKmh
        estimateVelocityKmhPerSecond = (estimateVelocityKmhPerSecond + acceleration * dt)
            .coerceIn(-MAXIMUM_TRACKED_VELOCITY, MAXIMUM_TRACKED_VELOCITY)
        estimateKmh = (estimateKmh + estimateVelocityKmhPerSecond * dt).coerceAtLeast(0.0)
        if ((previousEstimate <= targetKmh && estimateKmh > targetKmh) ||
            (previousEstimate >= targetKmh && estimateKmh < targetKmh)
        ) {
            estimateKmh = targetKmh
            estimateVelocityKmhPerSecond = 0.0
        }
        return estimateKmh
    }

    private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
        if (timeConstant <= 0.0) {
            return target
        }

        val blend = 1.0 - exp(-dt / timeConstant)
        return current + (target - current) * blend
    }

    private companion object {
        const val QUANTIZER_INTERVAL_WIDTH_KMH = 1.0
        const val MAXIMUM_POLL_OVERRUN_KMH = 0.08
        const val BOUNDARY_CORRECTION_EPSILON_KMH = 0.002
        const val FAST_CROSSING_VELOCITY_KMH_PER_SECOND = 2.0
        const val OBSERVED_VELOCITY_BLEND = 0.72
        const val MINIMUM_TRACKED_VELOCITY = 0.05
        const val MAXIMUM_TRACKED_VELOCITY = 45.0
        const val STALE_CROSSING_GRACE_SECONDS = 0.08
        const val STALE_VELOCITY_DECAY_SECONDS = 0.30
        const val SLOW_CROSSING_RESPONSE_FRACTION = 0.10
        const val MAXIMUM_SLOW_CROSSING_RESPONSE_SECONDS = 0.30
        const val FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS = 0.25
        const val PEDAL_SEED_MAXIMUM_SPEED_KMH = 15.0
        const val PEDAL_SEED_MAXIMUM_VELOCITY_KMH_PER_SECOND = 1.0
        const val PEDAL_SEED_RESPONSE_SECONDS = 0.25
        const val PEDAL_SEED_BRAKE_CUTOFF = 0.02
        const val ZERO_SETTLE_SECONDS = 0.55
        const val ZERO_SETTLE_SPEED_KMH = 0.04
        const val ZERO_SETTLE_THROTTLE = 0.001
        const val MINIMUM_RESPONSE_SECONDS = 0.04
        const val MAXIMUM_RESPONSE_SECONDS = 0.80
        const val MINIMUM_STEP_SECONDS = 1.0 / 1_000.0
        const val MAXIMUM_STEP_SECONDS = 1.0 / 20.0
    }
}
