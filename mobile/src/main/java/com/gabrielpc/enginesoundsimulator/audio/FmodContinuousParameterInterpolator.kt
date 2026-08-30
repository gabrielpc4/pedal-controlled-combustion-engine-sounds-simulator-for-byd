package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Removes zero-order-hold steps from continuous Studio parameters.
 *
 * Assetto's banks author `rpms` and `drivetrain_speed` with zero seek speed. The simulation
 * publishes at 200 Hz, while this interpolator runs on the 400 Hz FMOD owner thread and turns
 * each new source value into a short, allocation-free ramp. The engine event's load parameter
 * gets a longer asymmetric crossfade so an instant EV pedal release does not hard-swap dissimilar
 * authored load/coast layers in engine and transmission events. During a cosmetic gear change,
 * the engine pitch latches the post-shift RPM target so the source follower cannot turn a
 * downshift into an immediate rise and fall. It only changes the values delivered to FMOD;
 * raw pedal input, event serials, limiter decay, BOV detection, and gains stay untouched.
 */
internal class FmodContinuousParameterInterpolator(
    pitchResponseSeconds: Double = DEFAULT_PITCH_RESPONSE_SECONDS,
    throttleAttackSeconds: Double = DEFAULT_THROTTLE_ATTACK_SECONDS,
    throttleReleaseSeconds: Double = DEFAULT_THROTTLE_RELEASE_SECONDS,
) {
    // `rpms` selects pitched sample layers in the bank.  Smoothing its value alone is not
    // enough: the synthetic tach can make a large launch catch-up in one simulation frame,
    // which is heard as a sequence of layer jumps even when every FMOD update is fractional.
    // Follow it in log-pitch space with bounded velocity and acceleration instead.
    private val engineRpm = PerceptualPitchFollower(pitchResponseSeconds)
    private val drivetrainSpeed = CriticallyDampedTargetFollower(pitchResponseSeconds)
    private val engineThrottle = ExponentialTargetFollower(throttleAttackSeconds, throttleReleaseSeconds)
    private val transmissionThrottle = ExponentialTargetFollower(throttleAttackSeconds, throttleReleaseSeconds)
    private val turboBoost = BoundedDeltaTargetFollower(MAXIMUM_BOOST_DELTA_PER_UPDATE)
    private var audioWasEnabled = false

    /** Mutates and returns the planner's reusable output object. */
    fun apply(
        state: FmodControlState,
        deltaSeconds: Double,
        isShifting: Boolean = false,
        shiftSerial: Long = 0L,
        shiftTargetRpm: Float = Float.NaN,
    ): FmodControlState {
        val enabled = state.audioEnabled
        val snap = !enabled || !audioWasEnabled
        state.rpm = engineRpm.update(
            requested = state.rpm,
            deltaSeconds = deltaSeconds,
            snap = snap,
            isShifting = isShifting,
            shiftSerial = shiftSerial,
            shiftTargetRpm = shiftTargetRpm,
        )
        state.boost = turboBoost.update(state.boost, snap)
        state.drivetrainSpeed = drivetrainSpeed.update(
            state.drivetrainSpeed,
            deltaSeconds,
            snap,
        )
        state.engineThrottle = engineThrottle.update(state.engineThrottle, deltaSeconds, snap)
        state.transmissionThrottle = transmissionThrottle.update(
            state.transmissionThrottle,
            deltaSeconds,
            snap,
        )
        audioWasEnabled = enabled
        return state
    }

    private class ExponentialTargetFollower(
        private val attackSeconds: Double,
        private val releaseSeconds: Double,
    ) {
        private var initialized = false
        private var current = 0f

        fun update(requested: Float, deltaSeconds: Double, snap: Boolean): Float {
            val responseSeconds = if (requested > current) attackSeconds else releaseSeconds
            if (!initialized || snap || responseSeconds <= 0.0) {
                initialized = true
                current = requested
                return current
            }
            if (abs(requested - current) <= TARGET_EPSILON) return requested.also { current = it }

            val dt = deltaSeconds.coerceIn(0.0, MAXIMUM_PRESENTATION_STEP_SECONDS)
            val alpha = -expm1(-dt / responseSeconds)
            current += ((requested - current) * alpha).toFloat()
            if (abs(requested - current) <= TARGET_EPSILON) current = requested
            return current
        }
    }

    /**
     * Turbo banks use `boost` to select both gain and authored layers. The planner keeps the
     * physical-looking charge state, while this audio-only follower prevents a late worker wakeup
     * from delivering that elapsed catch-up as one large bank-parameter jump.
     */
    private class BoundedDeltaTargetFollower(
        private val maximumDeltaPerUpdate: Float,
    ) {
        private var initialized = false
        private var current = 0f

        fun update(requested: Float, snap: Boolean): Float {
            if (!initialized || snap) {
                initialized = true
                current = requested
                return current
            }

            current += (requested - current).coerceIn(
                -maximumDeltaPerUpdate,
                maximumDeltaPerUpdate,
            )
            return current
        }
    }

    /**
     * A second-order follower keeps pitch velocity continuous when the 200 Hz producer publishes
     * its next target. A first-order exponential changed velocity abruptly at every source frame;
     * the values were fractional but their slope still formed an audible 200 Hz staircase.
     */
    private class CriticallyDampedTargetFollower(
        private val responseSeconds: Double,
    ) {
        private var initialized = false
        private var current = 0.0
        private var velocity = 0.0

        fun update(requested: Float, deltaSeconds: Double, snap: Boolean): Float {
            val target = requested.toDouble()
            if (!initialized || snap || responseSeconds <= 0.0) {
                initialized = true
                current = target
                velocity = 0.0
                return requested
            }

            val dt = deltaSeconds.coerceIn(0.0, MAXIMUM_PRESENTATION_STEP_SECONDS)
            val omega = 2.0 / responseSeconds
            val displacement = current - target
            val combined = velocity + omega * displacement
            val decay = exp(-omega * dt)
            current = target + (displacement + combined * dt) * decay
            velocity = (velocity - omega * combined * dt) * decay
            if (
                abs(target - current) <= TARGET_EPSILON &&
                abs(velocity) <= TARGET_EPSILON / responseSeconds
            ) {
                current = target
                velocity = 0.0
            }
            return current.toFloat()
        }
    }

    /**
     * A presentation-only, jerk-bounded RPM follower.  A constant change in this domain is a
     * constant musical pitch change, rather than a constant number of RPM.  It deliberately
     * does not feed the tachometer, shift logic, limiter, or EV torque path.
     */
    private class PerceptualPitchFollower(
        responseSeconds: Double,
    ) {
        private val maximumOctavesPerSecond = (1.0 / responseSeconds.coerceAtLeast(0.001))
            .coerceIn(MINIMUM_OCTAVES_PER_SECOND, MAXIMUM_OCTAVES_PER_SECOND)
        private val maximumAcceleration = maximumOctavesPerSecond * ACCELERATION_MULTIPLIER
        private var initialized = false
        private var currentLog2 = 0.0
        private var velocityOctavesPerSecond = 0.0
        private var shiftSerialInitialized = false
        private var lastShiftSerial = 0L
        private var shiftTargetActive = false
        private var shiftTargetLog2 = 0.0
        private var shiftTargetDirection = 0

        fun update(
            requested: Float,
            deltaSeconds: Double,
            snap: Boolean,
            isShifting: Boolean,
            shiftSerial: Long,
            shiftTargetRpm: Float,
        ): Float {
            val safeRequested = requested.coerceAtLeast(MINIMUM_RPM).toDouble()
            if (!initialized || snap) {
                initialized = true
                currentLog2 = log2(safeRequested)
                velocityOctavesPerSecond = 0.0
                resetShiftTarget(
                    shiftSerial = shiftSerial,
                    isShifting = isShifting,
                    shiftTargetRpm = shiftTargetRpm,
                )
                return requested
            }

            val targetLog2 = targetLog2(
                requested = safeRequested,
                isShifting = isShifting,
                shiftSerial = shiftSerial,
                shiftTargetRpm = shiftTargetRpm,
            )

            // One invocation emits one native control buffer. Do not turn scheduler lateness
            // into a larger audible pitch step; let the presentation catch up across updates.
            val dt = deltaSeconds.coerceIn(0.0, MAXIMUM_PRESENTATION_STEP_SECONDS)
            val distance = targetLog2 - currentLog2
            if (abs(distance) <= LOG_TARGET_EPSILON &&
                abs(velocityOctavesPerSecond) <= LOG_VELOCITY_EPSILON
            ) {
                currentLog2 = targetLog2
                velocityOctavesPerSecond = 0.0
                settleShiftTargetIfReached()
                return exp2(currentLog2).toFloat()
            }

            // Brake early enough to arrive without a velocity snap.  Approaching this desired
            // velocity under a bounded acceleration makes the audible pitch slope continuous,
            // including when the 200 Hz producer publishes a new target.
            val direction = if (distance >= 0.0) 1.0 else -1.0
            val brakingVelocity = sqrt(2.0 * maximumAcceleration * abs(distance))
            val desiredVelocity = direction * minOf(maximumOctavesPerSecond, brakingVelocity)
            val maximumVelocityChange = maximumAcceleration * dt
            velocityOctavesPerSecond += (desiredVelocity - velocityOctavesPerSecond)
                .coerceIn(-maximumVelocityChange, maximumVelocityChange)
            val nextLog2 = currentLog2 + velocityOctavesPerSecond * dt

            // Never cross the target in a single FMOD control step; stop there cleanly instead.
            if ((distance > 0.0 && nextLog2 >= targetLog2) ||
                (distance < 0.0 && nextLog2 <= targetLog2)
            ) {
                currentLog2 = targetLog2
                velocityOctavesPerSecond = 0.0
            } else {
                currentLog2 = nextLog2
            }
            settleShiftTargetIfReached()
            return exp2(currentLog2).toFloat()
        }

        private fun targetLog2(
            requested: Double,
            isShifting: Boolean,
            shiftSerial: Long,
            shiftTargetRpm: Float,
        ): Double {
            val sourceSerial = shiftSerial.coerceAtLeast(0L)
            if (!shiftSerialInitialized) {
                shiftSerialInitialized = true
                lastShiftSerial = sourceSerial
            } else if (sourceSerial < lastShiftSerial) {
                lastShiftSerial = sourceSerial
                clearShiftTarget()
            } else if (sourceSerial > lastShiftSerial) {
                lastShiftSerial = sourceSerial
                startShiftTarget(
                    isShifting = isShifting,
                    shiftTargetRpm = shiftTargetRpm,
                )
            }

            if (shiftTargetActive && isShifting) {
                extendShiftTarget(shiftTargetRpm)
            }
            return if (shiftTargetActive) shiftTargetLog2 else log2(requested)
        }

        private fun startShiftTarget(isShifting: Boolean, shiftTargetRpm: Float) {
            if (!isShifting || !shiftTargetRpm.isFinite()) {
                clearShiftTarget()
                return
            }

            val target = log2(shiftTargetRpm.coerceAtLeast(MINIMUM_RPM).toDouble())
            val distance = target - currentLog2
            if (abs(distance) <= LOG_TARGET_EPSILON) {
                clearShiftTarget()
                return
            }

            shiftTargetActive = true
            shiftTargetLog2 = target
            shiftTargetDirection = if (distance > 0.0) 1 else -1
        }

        private fun extendShiftTarget(shiftTargetRpm: Float) {
            if (!shiftTargetRpm.isFinite()) return

            val candidate = log2(shiftTargetRpm.coerceAtLeast(MINIMUM_RPM).toDouble())
            shiftTargetLog2 = when (shiftTargetDirection) {
                1 -> maxOf(shiftTargetLog2, candidate)
                -1 -> minOf(shiftTargetLog2, candidate)
                else -> shiftTargetLog2
            }
        }

        private fun settleShiftTargetIfReached() {
            if (
                shiftTargetActive &&
                abs(shiftTargetLog2 - currentLog2) <= LOG_TARGET_EPSILON &&
                abs(velocityOctavesPerSecond) <= LOG_VELOCITY_EPSILON
            ) {
                clearShiftTarget()
            }
        }

        private fun resetShiftTarget(
            shiftSerial: Long,
            isShifting: Boolean,
            shiftTargetRpm: Float,
        ) {
            shiftSerialInitialized = true
            lastShiftSerial = shiftSerial.coerceAtLeast(0L)
            clearShiftTarget()
            startShiftTarget(isShifting, shiftTargetRpm)
        }

        private fun clearShiftTarget() {
            shiftTargetActive = false
            shiftTargetDirection = 0
        }

        private fun log2(value: Double): Double = ln(value) / LN_2
        private fun exp2(value: Double): Double = exp(value * LN_2)

        private companion object {
            const val MINIMUM_RPM = 1.0f
            const val MINIMUM_OCTAVES_PER_SECOND = 2.0
            // At 400 Hz this is at most 15 cents per delivered native control update.
            const val MAXIMUM_OCTAVES_PER_SECOND = 5.0
            const val ACCELERATION_MULTIPLIER = 6.0
            const val LOG_TARGET_EPSILON = 1.0e-6
            const val LOG_VELOCITY_EPSILON = 1.0e-5
            const val LN_2 = 0.6931471805599453
        }
    }

    companion object {
        /** Sets a responsive, bounded log-pitch trajectory without touching vehicle dynamics. */
        const val DEFAULT_PITCH_RESPONSE_SECONDS = 0.020
        /** Fast enough to retain pedal response while crossfading into the authored load layers. */
        const val DEFAULT_THROTTLE_ATTACK_SECONDS = 0.035
        /** Roughly 0.36 s to reach 5%, preventing an instant load-to-coast layer replacement. */
        const val DEFAULT_THROTTLE_RELEASE_SECONDS = 0.120
        /** A worker delay must add presentation latency, never enlarge a delivered audio step. */
        private const val MAXIMUM_PRESENTATION_STEP_SECONDS = 1.0 / FmodControlPlanner.CONTROL_HZ
        /** Preserves the planner's normal <= 0.0025 boost delta while bounding delayed delivery. */
        private const val MAXIMUM_BOOST_DELTA_PER_UPDATE = 0.005f
        private const val TARGET_EPSILON = 0.0001f
    }
}
