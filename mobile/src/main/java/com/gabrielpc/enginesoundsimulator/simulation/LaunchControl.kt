package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Phases of the optional SIMULATED PEDALS launch sequence. */
internal enum class LaunchControlPhase {
    INACTIVE,
    /** Full throttle plus brake held at a standstill; stage near 5,000 RPM. */
    ARMED,
    /** Throttle was lifted while the brake remained held; smoothly return to the prior RPM. */
    DISARMING,
    /** Brake was released; allow the normal drivetrain shift while animating first-gear revs. */
    LAUNCHED,
}

/**
 * Launch-control math formerly used by the main branch.
 *
 * This is intentionally an app-level driver-input behavior, not a replacement for the selected
 * bank's torque curve or FMOD events. The current drivetrain applies these targets in D for both
 * SIMULATED/REAL PEDALS and automatic/manual shifting; P/N remains a free-rev path.
 */
internal object LaunchControl {
    const val HOLD_RPM = 5_000.0
    const val FULL_THROTTLE_THRESHOLD = 0.98
    const val ARM_BRAKE_THRESHOLD = 0.05
    const val RELEASE_BRAKE_THRESHOLD = 0.04
    const val STANDSTILL_SPEED_MPS = 0.08
    const val JITTER_AMPLITUDE_RPM = 42.0
    const val JITTER_HZ = 10.5
    const val ARMED_RAMP_SECONDS = 0.42
    const val ARMED_OVERSHOOT_RPM = 165.0
    const val ARMED_SETTLE_SECONDS = 0.34
    const val ARMED_RAMP_FOLLOW_SECONDS = 0.11
    const val ARMED_JITTER_FOLLOW_SECONDS = 0.055
    const val LAUNCHED_ENGINE_BRAKE_RESPONSE_SECONDS = 0.22
    const val LAUNCHED_TACH_BOUNCE_RPM = 500.0
    const val LAUNCHED_TACH_REV_UP_SECONDS = 0.38
    const val LAUNCHED_TACH_BOUNCE_SECONDS = 0.12
    const val LAUNCHED_TACH_REV_UP_FOLLOW_SECONDS = 0.045
    const val LAUNCHED_TACH_BOUNCE_FOLLOW_SECONDS = 0.035

    fun blocksDriveAtStandstill(speedMps: Double, brake: Double): Boolean {
        return speedMps <= STANDSTILL_SPEED_MPS && brake >= ARM_BRAKE_THRESHOLD
    }

    fun armedTargetRpm(
        armedElapsedSeconds: Double,
        jitterPhaseRadians: Double,
        startRpm: Double,
    ): Double {
        if (armedElapsedSeconds < ARMED_RAMP_SECONDS) {
            val fraction = (armedElapsedSeconds / ARMED_RAMP_SECONDS).coerceIn(0.0, 1.0)
            val eased = 1.0 - (1.0 - fraction).pow(3.0)
            return startRpm + eased * (HOLD_RPM + ARMED_OVERSHOOT_RPM - startRpm)
        }

        val settleFraction = ((armedElapsedSeconds - ARMED_RAMP_SECONDS) / ARMED_SETTLE_SECONDS)
            .coerceIn(0.0, 1.0)
        val overshoot = ARMED_OVERSHOOT_RPM * (1.0 - settleFraction)
        val jitter = sin(jitterPhaseRadians) * JITTER_AMPLITUDE_RPM * settleFraction
        return HOLD_RPM + overshoot + jitter
    }

    fun disarmTargetRpm(
        disarmElapsedSeconds: Double,
        startRpm: Double,
        endRpm: Double,
    ): Double {
        val fraction = (disarmElapsedSeconds / ARMED_RAMP_SECONDS).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - fraction).pow(3.0)
        return startRpm - eased * (startRpm - endRpm)
    }

    fun shouldPlayLaunchTachAnimation(gearIndex: Int, throttle: Double): Boolean {
        return gearIndex == 0 && throttle >= FULL_THROTTLE_THRESHOLD
    }

    fun launchedTachTargetRpm(
        cycleElapsedSeconds: Double,
        redlineRpm: Double,
        launchStartRpm: Double,
    ): Double {
        val bounceFloor = (redlineRpm - LAUNCHED_TACH_BOUNCE_RPM).coerceAtLeast(launchStartRpm)
        val cycleDuration = LAUNCHED_TACH_REV_UP_SECONDS + LAUNCHED_TACH_BOUNCE_SECONDS
        val cycleTime = cycleElapsedSeconds % cycleDuration
        val cycleIndex = (cycleElapsedSeconds / cycleDuration).toInt()
        val revUpStart = if (cycleIndex == 0) launchStartRpm else bounceFloor
        return if (cycleTime < LAUNCHED_TACH_REV_UP_SECONDS) {
            val fraction = (cycleTime / LAUNCHED_TACH_REV_UP_SECONDS).coerceIn(0.0, 1.0)
            val eased = 1.0 - (1.0 - fraction).pow(2.0)
            revUpStart + eased * (redlineRpm - revUpStart)
        } else {
            val fraction = ((cycleTime - LAUNCHED_TACH_REV_UP_SECONDS) / LAUNCHED_TACH_BOUNCE_SECONDS)
                .coerceIn(0.0, 1.0)
            redlineRpm - fraction.pow(2.0) * LAUNCHED_TACH_BOUNCE_RPM
        }
    }

    fun advancePhase(
        phase: LaunchControlPhase,
        rawThrottle: Double,
        brake: Double,
        speedMps: Double,
        enabled: Boolean,
    ): LaunchControlPhase {
        if (!enabled) return LaunchControlPhase.INACTIVE

        val fullThrottle = rawThrottle >= FULL_THROTTLE_THRESHOLD
        val brakeHeld = brake >= ARM_BRAKE_THRESHOLD
        val brakeReleased = brake < RELEASE_BRAKE_THRESHOLD
        val canArm = speedMps <= STANDSTILL_SPEED_MPS
        return when (phase) {
            LaunchControlPhase.INACTIVE -> if (fullThrottle && brakeHeld && canArm) {
                LaunchControlPhase.ARMED
            } else {
                LaunchControlPhase.INACTIVE
            }

            LaunchControlPhase.ARMED -> when {
                brakeReleased -> LaunchControlPhase.LAUNCHED
                !fullThrottle -> LaunchControlPhase.DISARMING
                else -> LaunchControlPhase.ARMED
            }

            LaunchControlPhase.DISARMING -> if (fullThrottle && brakeHeld && canArm) {
                LaunchControlPhase.ARMED
            } else {
                LaunchControlPhase.DISARMING
            }

            LaunchControlPhase.LAUNCHED -> if (brakeHeld) {
                LaunchControlPhase.INACTIVE
            } else {
                LaunchControlPhase.LAUNCHED
            }
        }
    }
}

internal fun launchControlJitterPhaseStep(dt: Double, currentPhaseRadians: Double): Double {
    return currentPhaseRadians + dt * LaunchControl.JITTER_HZ * 2.0 * PI
}
