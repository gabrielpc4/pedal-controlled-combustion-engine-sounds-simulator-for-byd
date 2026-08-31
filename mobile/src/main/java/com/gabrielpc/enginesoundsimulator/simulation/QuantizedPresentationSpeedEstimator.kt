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
 * rate that can advance continuously between later crossings. A bounded pedal-intent velocity
 * starts moving inside the current interval before the next whole-km/h sample, without affecting
 * gearbox or vehicle decisions. When the first boundary arrives after history was reset, any
 * missed distance is recovered over multiple frames instead of being applied as one visible and
 * audible step.
 */
internal class QuantizedPresentationSpeedEstimator {
    private var initialized = false
    private var estimateKmh = 0.0
    private var estimateVelocityKmhPerSecond = 0.0
    private var predictedKmh = 0.0
    private var observedVelocityKmhPerSecond = 0.0
    private var pedalIntentVelocityKmhPerSecond = 0.0
    private var previousThrottle = 0.0
    private var previousBrake = 0.0
    private var pedalPredictionDirection = 0.0
    private var pedalPredictionBoundaryPending = false
    private var pedalPredictionElapsedSeconds = 0.0
    private var releasePredictionSecondsRemaining = 0.0
    private var releasePredictionVelocityKmhPerSecond = 0.0
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
    /** A real boundary can disprove pedal intent, such as climbing with light throttle. */
    private var opposingTelemetryDirection = 0.0

    /** Signed rate currently moving the visible presentation speed (km/h per second). */
    val presentationVelocityKmhPerSecond: Double
        get() = estimateVelocityKmhPerSecond

    fun reset() {
        initialized = false
        estimateKmh = 0.0
        estimateVelocityKmhPerSecond = 0.0
        predictedKmh = 0.0
        observedVelocityKmhPerSecond = 0.0
        pedalIntentVelocityKmhPerSecond = 0.0
        previousThrottle = 0.0
        previousBrake = 0.0
        pedalPredictionDirection = 0.0
        pedalPredictionBoundaryPending = false
        pedalPredictionElapsedSeconds = 0.0
        releasePredictionSecondsRemaining = 0.0
        releasePredictionVelocityKmhPerSecond = 0.0
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
        opposingTelemetryDirection = 0.0
    }

    fun update(
        measurementKmh: Double,
        throttle: Double,
        brake: Double,
        dt: Double,
        responseSeconds: Double,
    ): Double {
        val measurement = floor(measurementKmh.coerceAtLeast(0.0))
        val safeThrottle = throttle.coerceIn(0.0, 1.0)
        val safeBrake = brake.coerceIn(0.0, 1.0)
        val stepSeconds = dt.coerceIn(MINIMUM_STEP_SECONDS, MAXIMUM_STEP_SECONDS)
        if (!initialized) {
            initialize(measurement, safeThrottle)
            return estimateKmh
        }

        val pedalIntentVelocity = updatePedalIntentVelocity(
            throttle = safeThrottle,
            brake = safeBrake,
            dt = stepSeconds,
        )
        secondsSinceMeasurementChanged += stepSeconds
        if (measurement != previousMeasurementKmh) {
            observeCrossing(measurement, stepSeconds, responseSeconds)
        }
        updateCrossingFreshness(stepSeconds)

        val telemetryVelocity = if (hasObservedCrossing) observedVelocityKmhPerSecond else 0.0
        val pedalPredictionActive = pedalPredictionDirection != 0.0 || !hasObservedCrossing
        val motionVelocity = when {
            pedalPredictionActive && pedalIntentVelocity < 0.0 -> min(telemetryVelocity, pedalIntentVelocity)
            pedalPredictionActive && pedalIntentVelocity > 0.0 -> max(telemetryVelocity, pedalIntentVelocity)
            hasObservedCrossing -> telemetryVelocity
            else -> pedalIntentVelocity
        }
        predictedKmh += motionVelocity * stepSeconds
        recoverPendingBoundary(stepSeconds)
        predictedKmh = clampPredictionToMeasuredBin(
            predictionKmh = predictedKmh,
            measurementKmh = measurement,
            direction = motionVelocity.sign,
            allowPollOverrun = hasObservedCrossing || pedalPredictionDirection != 0.0,
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
            safeThrottle <= ZERO_SETTLE_THROTTLE
        ) {
            estimateKmh = 0.0
            estimateVelocityKmhPerSecond = 0.0
            predictedKmh = 0.0
            observedVelocityKmhPerSecond = 0.0
            pedalIntentVelocityKmhPerSecond = 0.0
            pedalPredictionDirection = 0.0
            pedalPredictionBoundaryPending = false
            releasePredictionSecondsRemaining = 0.0
            releasePredictionVelocityKmhPerSecond = 0.0
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
            opposingTelemetryDirection = 0.0
        }

        return estimateKmh
    }

    private fun initialize(measurementKmh: Double, throttle: Double) {
        initialized = true
        estimateKmh = measurementKmh
        predictedKmh = measurementKmh
        previousMeasurementKmh = measurementKmh
        previousThrottle = throttle
        if (throttle >= PEDAL_INTENT_THROTTLE_CUTOFF) {
            pedalIntentVelocityKmhPerSecond = accelerationIntentVelocity(throttle)
            beginPedalPrediction(direction = 1.0)
        }
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
        reconcilePedalIntentWithTelemetry(direction)
        val hasSameDirectionBoundary = hasCrossingBoundary && direction == previousCrossingDirection
        val hasPedalTimedBoundary = !hasSameDirectionBoundary &&
            direction == pedalPredictionDirection &&
            pedalPredictionElapsedSeconds >= MINIMUM_PEDAL_CROSSING_SECONDS
        val canInferVelocity = hasSameDirectionBoundary || hasPedalTimedBoundary
        val crossingVelocity = when {
            hasSameDirectionBoundary -> (
                direction * abs(crossingBoundaryKmh - previousCrossingBoundaryKmh) / elapsedSeconds
                )
                .coerceIn(-MAXIMUM_TRACKED_VELOCITY, MAXIMUM_TRACKED_VELOCITY)
            // The hidden fractional starting point makes speed calculated from pedal-to-first-
            // boundary time ambiguous. Keep the already-moving pedal estimate until the second
            // boundary supplies a full, trustworthy one-km/h interval.
            hasPedalTimedBoundary -> direction * max(
                abs(pedalIntentVelocityKmhPerSecond),
                MINIMUM_TRACKED_VELOCITY,
            )
            else -> 0.0
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
        // A first crossing can happen almost immediately because the real speed may already be
        // near the top or bottom of its hidden one-km/h interval. Its apparent rate is therefore
        // not trustworthy even when it looks fast. Always absorb that first boundary
        // progressively; pedal intent already keeps the presentation moving in the meantime.
        val shouldRecoverBoundaryGradually = !hasSameDirectionBoundary
        if (predictionMissedBoundary && shouldRecoverBoundaryGradually) {
            pendingBoundaryKmh = crossingBoundaryKmh
            pendingBoundaryDirection = direction
            val crossingResponseSeconds = responseForCrossing(responseSeconds, elapsedSeconds)
            pendingBoundaryResponseSeconds = max(
                FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS,
                crossingResponseSeconds,
            )
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
        previousMeasurementKmh = measurementKmh
        previousCrossingBoundaryKmh = crossingBoundaryKmh
        previousCrossingDirection = direction
        secondsSinceMeasurementChanged = 0.0
        hasCrossingBoundary = true
        hasObservedCrossing = canInferVelocity
        crossingPredictionStale = false
        if (direction == pedalPredictionDirection && hasSameDirectionBoundary) {
            pedalPredictionBoundaryPending = false
        }
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

    private fun updatePedalIntentVelocity(
        throttle: Double,
        brake: Double,
        dt: Double,
    ): Double {
        val releasedPedal = previousThrottle >= PEDAL_RELEASE_ARM_THROTTLE &&
            throttle <= PEDAL_INTENT_THROTTLE_CUTOFF
        val pressedPedal = previousThrottle < PEDAL_INTENT_THROTTLE_CUTOFF &&
            throttle >= PEDAL_INTENT_THROTTLE_CUTOFF
        val pressedBrake = previousBrake < PEDAL_INTENT_BRAKE_CUTOFF &&
            brake >= PEDAL_INTENT_BRAKE_CUTOFF
        if (releasedPedal) {
            releasePredictionSecondsRemaining = PEDAL_RELEASE_PREDICTION_SECONDS
            releasePredictionVelocityKmhPerSecond = -(
                PEDAL_RELEASE_MINIMUM_VELOCITY_KMH_PER_SECOND +
                    previousThrottle * PEDAL_RELEASE_ADDITIONAL_VELOCITY_KMH_PER_SECOND
                )
            invalidateObservedVelocityIfOpposing(direction = -1.0)
            alignPredictionForDirectionChange(direction = -1.0)
            // The pedal edge already tells us the intended direction. Starting this velocity
            // immediately avoids stacking its old 80 ms ramp on top of presentation and RPM
            // smoothing, while the one-km/h bin clamp still bounds how far it can predict.
            pedalIntentVelocityKmhPerSecond = releasePredictionVelocityKmhPerSecond
            beginPedalPrediction(direction = -1.0)
        } else if (pressedPedal) {
            releasePredictionSecondsRemaining = 0.0
            releasePredictionVelocityKmhPerSecond = 0.0
            invalidateObservedVelocityIfOpposing(direction = 1.0)
            alignPredictionForDirectionChange(direction = 1.0)
            pedalIntentVelocityKmhPerSecond = accelerationIntentVelocity(throttle)
            beginPedalPrediction(direction = 1.0)
        } else if (pressedBrake) {
            invalidateObservedVelocityIfOpposing(direction = -1.0)
            alignPredictionForDirectionChange(direction = -1.0)
            pedalIntentVelocityKmhPerSecond = -max(
                PEDAL_BRAKE_MINIMUM_VELOCITY_KMH_PER_SECOND,
                brake * PEDAL_INTENT_MAXIMUM_VELOCITY_KMH_PER_SECOND,
            )
            beginPedalPrediction(direction = -1.0)
        }
        previousThrottle = throttle
        previousBrake = brake

        val targetVelocity = when {
            brake >= PEDAL_INTENT_BRAKE_CUTOFF -> {
                releasePredictionSecondsRemaining = 0.0
                releasePredictionVelocityKmhPerSecond = 0.0
                invalidateObservedVelocityIfOpposing(direction = -1.0)
                -max(
                    PEDAL_BRAKE_MINIMUM_VELOCITY_KMH_PER_SECOND,
                    brake * PEDAL_INTENT_MAXIMUM_VELOCITY_KMH_PER_SECOND,
                )
            }
            opposingTelemetryDirection != 0.0 -> {
                opposingTelemetryDirection * OPPOSING_TELEMETRY_MINIMUM_VELOCITY_KMH_PER_SECOND
            }
            throttle >= PEDAL_INTENT_THROTTLE_CUTOFF -> {
                accelerationIntentVelocity(throttle)
            }
            releasePredictionSecondsRemaining > 0.0 -> {
                val remainingFraction = (
                    releasePredictionSecondsRemaining / PEDAL_RELEASE_PREDICTION_SECONDS
                    ).coerceIn(0.0, 1.0)
                releasePredictionSecondsRemaining =
                    (releasePredictionSecondsRemaining - dt).coerceAtLeast(0.0)
                releasePredictionVelocityKmhPerSecond * remainingFraction
            }
            pedalPredictionBoundaryPending && pedalPredictionDirection < 0.0 -> {
                -PEDAL_RELEASE_CONTINUATION_VELOCITY_KMH_PER_SECOND
            }
            else -> 0.0
        }
        val targetDirection = targetVelocity.sign
        if (targetDirection != 0.0 && targetDirection != pedalPredictionDirection) {
            alignPredictionForDirectionChange(targetDirection)
            beginPedalPrediction(targetDirection)
        } else if (targetDirection != 0.0) {
            pedalPredictionElapsedSeconds += dt
        }
        pedalIntentVelocityKmhPerSecond = approachExp(
            current = pedalIntentVelocityKmhPerSecond,
            target = targetVelocity,
            timeConstant = PEDAL_INTENT_RESPONSE_SECONDS,
            dt = dt,
        )
        if (targetDirection == 0.0 &&
            !pedalPredictionBoundaryPending &&
            abs(pedalIntentVelocityKmhPerSecond) < MINIMUM_PEDAL_INTENT_VELOCITY
        ) {
            pedalIntentVelocityKmhPerSecond = 0.0
            pedalPredictionDirection = 0.0
            pedalPredictionElapsedSeconds = 0.0
        }

        return pedalIntentVelocityKmhPerSecond
    }

    /**
     * A single whole-km/h telemetry change is enough to establish direction, but not speed.
     * Keep that direction between crossings so a drag-induced drop cannot be overridden by a
     * still-pressed pedal and then corrected as an audible one-km/h step on the next boundary.
     */
    private fun reconcilePedalIntentWithTelemetry(direction: Double) {
        if (direction == 0.0) return

        val pedalDemandDirection = when {
            previousBrake >= PEDAL_INTENT_BRAKE_CUTOFF -> -1.0
            previousThrottle >= PEDAL_INTENT_THROTTLE_CUTOFF -> 1.0
            else -> 0.0
        }
        if (direction == pedalDemandDirection) {
            opposingTelemetryDirection = 0.0
            return
        }

        opposingTelemetryDirection = direction
        releasePredictionSecondsRemaining = 0.0
        releasePredictionVelocityKmhPerSecond = 0.0
        invalidateObservedVelocityIfOpposing(direction)
        alignPredictionForDirectionChange(direction)
        pedalIntentVelocityKmhPerSecond = direction * OPPOSING_TELEMETRY_MINIMUM_VELOCITY_KMH_PER_SECOND
        beginPedalPrediction(direction)
    }

    private fun beginPedalPrediction(direction: Double) {
        pedalPredictionDirection = direction.sign
        pedalPredictionBoundaryPending = true
        pedalPredictionElapsedSeconds = 0.0
    }

    private fun accelerationIntentVelocity(throttle: Double): Double {
        return max(
            PEDAL_ACCELERATION_MINIMUM_VELOCITY_KMH_PER_SECOND,
            throttle * PEDAL_INTENT_MAXIMUM_VELOCITY_KMH_PER_SECOND,
        )
    }

    private fun invalidateObservedVelocityIfOpposing(direction: Double) {
        if (!hasObservedCrossing || observedVelocityKmhPerSecond * direction >= 0.0) {
            return
        }

        observedVelocityKmhPerSecond = 0.0
        hasObservedCrossing = false
        crossingPredictionStale = false
    }

    private fun alignPredictionForDirectionChange(direction: Double) {
        if (pendingBoundaryDirection * direction < 0.0) {
            pendingBoundaryKmh = null
            pendingBoundaryDirection = 0.0
        }
        predictedKmh = estimateKmh + direction * PEDAL_DIRECTION_CUE_KMH
        if (estimateVelocityKmhPerSecond * direction < 0.0) {
            estimateVelocityKmhPerSecond = 0.0
        }
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
        val predictionDirection = if (direction != 0.0) direction else pedalPredictionDirection
        val minimum = when {
            pendingBoundaryDirection > 0.0 -> min(lowerBoundary, predictionKmh)
            predictionDirection < 0.0 && pedalPredictionBoundaryPending -> {
                (lowerBoundary - PEDAL_REVERSAL_LEAD_KMH).coerceAtLeast(0.0)
            }
            predictionDirection < 0.0 -> (lowerBoundary - pollOverrunKmh).coerceAtLeast(0.0)
            else -> lowerBoundary
        }
        val maximum = when {
            pendingBoundaryDirection < 0.0 -> max(upperBoundary, predictionKmh)
            predictionDirection > 0.0 -> upperBoundary + pollOverrunKmh
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
        const val PEDAL_REVERSAL_LEAD_KMH = 0.58
        const val PEDAL_DIRECTION_CUE_KMH = 0.15
        const val BOUNDARY_CORRECTION_EPSILON_KMH = 0.002
        const val MINIMUM_PEDAL_CROSSING_SECONDS = 0.04
        const val OBSERVED_VELOCITY_BLEND = 0.72
        const val MINIMUM_TRACKED_VELOCITY = 0.05
        const val MAXIMUM_TRACKED_VELOCITY = 45.0
        const val STALE_CROSSING_GRACE_SECONDS = 0.08
        const val STALE_VELOCITY_DECAY_SECONDS = 0.30
        const val SLOW_CROSSING_RESPONSE_FRACTION = 0.10
        const val MAXIMUM_SLOW_CROSSING_RESPONSE_SECONDS = 0.30
        const val FIRST_BOUNDARY_CORRECTION_MINIMUM_SECONDS = 0.25
        const val PEDAL_INTENT_MAXIMUM_VELOCITY_KMH_PER_SECOND = 1.0
        const val PEDAL_ACCELERATION_MINIMUM_VELOCITY_KMH_PER_SECOND = 0.20
        const val PEDAL_INTENT_RESPONSE_SECONDS = 0.08
        const val PEDAL_INTENT_THROTTLE_CUTOFF = 0.005
        const val PEDAL_INTENT_BRAKE_CUTOFF = 0.02
        const val PEDAL_RELEASE_ARM_THROTTLE = PEDAL_INTENT_THROTTLE_CUTOFF
        const val MINIMUM_PEDAL_INTENT_VELOCITY = 0.01
        const val PEDAL_RELEASE_PREDICTION_SECONDS = 1.20
        const val PEDAL_RELEASE_MINIMUM_VELOCITY_KMH_PER_SECOND = 0.25
        const val PEDAL_RELEASE_ADDITIONAL_VELOCITY_KMH_PER_SECOND = 0.75
        const val PEDAL_RELEASE_CONTINUATION_VELOCITY_KMH_PER_SECOND = 0.20
        const val OPPOSING_TELEMETRY_MINIMUM_VELOCITY_KMH_PER_SECOND = 0.30
        const val PEDAL_BRAKE_MINIMUM_VELOCITY_KMH_PER_SECOND = 0.35
        const val ZERO_SETTLE_SECONDS = 0.55
        const val ZERO_SETTLE_SPEED_KMH = 0.04
        const val ZERO_SETTLE_THROTTLE = 0.001
        const val MINIMUM_RESPONSE_SECONDS = 0.04
        const val MAXIMUM_RESPONSE_SECONDS = 0.80
        const val MINIMUM_STEP_SECONDS = 1.0 / 1_000.0
        const val MAXIMUM_STEP_SECONDS = 1.0 / 20.0
    }
}
