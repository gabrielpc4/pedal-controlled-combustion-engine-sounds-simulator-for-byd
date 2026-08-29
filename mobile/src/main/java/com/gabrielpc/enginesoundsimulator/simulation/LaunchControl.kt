package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

internal enum class LaunchControlPhase {
    INACTIVE,
    /** Full throttle + brake held: revs pinned near 5k with jitter. */
    ARMED,
    /** Throttle lifted after arm: revs fall back along the mirrored ramp curve. */
    DISARMING,
    /** Brake released once: scripted tach bounce in 1st until the normal 2nd-gear upshift. */
    LAUNCHED,
}

internal object LaunchControl {
    const val HOLD_RPM = 5_000.0
    const val FULL_THROTTLE_THRESHOLD = 0.98
    const val ARM_BRAKE_THRESHOLD = 0.05
    const val RELEASE_BRAKE_THRESHOLD = 0.04
    /** Below this road speed, brake + throttle keeps the simulator car from creeping forward. */
    const val STANDSTILL_SPEED_MPS = 0.08
    const val JITTER_AMPLITUDE_RPM = 42.0
    const val JITTER_HZ = 10.5
    /** Fast continuous rev-up from idle into the launch hold band. */
    const val ARMED_RAMP_SECONDS = 0.42
    /** Brief overshoot above [HOLD_RPM] before jitter takes over. */
    const val ARMED_OVERSHOOT_RPM = 165.0
    /** Time to bleed overshoot into the steady jitter orbit. */
    const val ARMED_SETTLE_SECONDS = 0.34
    const val ARMED_RAMP_FOLLOW_SECONDS = 0.11
    const val ARMED_JITTER_FOLLOW_SECONDS = 0.055
    const val LAUNCHED_ENGINE_BRAKE_RESPONSE_SECONDS = 0.22
    /** Scripted 1st-gear launch tach: rise to redline, bounce back this many RPM, repeat. */
    const val LAUNCHED_TACH_BOUNCE_RPM = 500.0
    const val LAUNCHED_TACH_REV_UP_SECONDS = 0.38
    const val LAUNCHED_TACH_BOUNCE_SECONDS = 0.12
    const val LAUNCHED_TACH_REV_UP_FOLLOW_SECONDS = 0.045
    const val LAUNCHED_TACH_BOUNCE_FOLLOW_SECONDS = 0.035

    /** True when simulated pedals should hold the car at a stop despite throttle input. */
    fun blocksDriveAtStandstill(speedMps: Double, brake: Double): Boolean {
        return speedMps <= STANDSTILL_SPEED_MPS && brake >= ARM_BRAKE_THRESHOLD
    }

    fun holdRpmWithJitter(jitterPhaseRadians: Double): Double {
        return HOLD_RPM + sin(jitterPhaseRadians) * JITTER_AMPLITUDE_RPM
    }

    /** Rev target while launch is armed: ramp → slight overshoot → jitter around 5k. */
    fun armedTargetRpm(
        armedElapsedSeconds: Double,
        jitterPhaseRadians: Double,
        startRpm: Double,
    ): Double {
        if (armedElapsedSeconds < ARMED_RAMP_SECONDS) {
            val fraction = (armedElapsedSeconds / ARMED_RAMP_SECONDS).coerceIn(0.0, 1.0)
            val eased = 1.0 - (1.0 - fraction).pow(3.0)
            val peakRpm = HOLD_RPM + ARMED_OVERSHOOT_RPM
            return startRpm + eased * (peakRpm - startRpm)
        }

        val settleElapsed = armedElapsedSeconds - ARMED_RAMP_SECONDS
        val settleFraction = (settleElapsed / ARMED_SETTLE_SECONDS).coerceIn(0.0, 1.0)
        val overshootComponent = ARMED_OVERSHOOT_RPM * (1.0 - settleFraction)
        val jitter = sin(jitterPhaseRadians) * JITTER_AMPLITUDE_RPM * settleFraction
        return HOLD_RPM + overshootComponent + jitter
    }

    /** Rev target while launch disarms: mirror of [armedTargetRpm]'s ramp back to [endRpm]. */
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

    /**
     * Scripted launch tach in 1st gear: rev quickly to [redlineRpm], bounce back [LAUNCHED_TACH_BOUNCE_RPM],
     * then repeat until the car reaches the normal 2nd-gear upshift speed.
     */
    fun launchedTachTargetRpm(
        cycleElapsedSeconds: Double,
        redlineRpm: Double,
        launchStartRpm: Double,
    ): Double {
        val bounceFloor = (redlineRpm - LAUNCHED_TACH_BOUNCE_RPM).coerceAtLeast(launchStartRpm)
        val cycleDuration = LAUNCHED_TACH_REV_UP_SECONDS + LAUNCHED_TACH_BOUNCE_SECONDS
        val cycleTime = cycleElapsedSeconds % cycleDuration
        val cycleIndex = (cycleElapsedSeconds / cycleDuration).toInt()
        val revUpStart = if (cycleIndex == 0) {
            launchStartRpm
        } else {
            bounceFloor
        }

        return if (cycleTime < LAUNCHED_TACH_REV_UP_SECONDS) {
            val fraction = (cycleTime / LAUNCHED_TACH_REV_UP_SECONDS).coerceIn(0.0, 1.0)
            val eased = 1.0 - (1.0 - fraction).pow(2.0)
            revUpStart + eased * (redlineRpm - revUpStart)
        } else {
            val bounceTime = cycleTime - LAUNCHED_TACH_REV_UP_SECONDS
            val fraction = (bounceTime / LAUNCHED_TACH_BOUNCE_SECONDS).coerceIn(0.0, 1.0)
            val eased = fraction.pow(2.0)
            redlineRpm - eased * LAUNCHED_TACH_BOUNCE_RPM
        }
    }

    /** True when launch control is allowed to enter the armed staging state. */
    fun canArmAtSpeed(speedMps: Double): Boolean {
        return speedMps <= STANDSTILL_SPEED_MPS
    }

    fun advancePhase(
        phase: LaunchControlPhase,
        rawThrottle: Double,
        brake: Double,
        speedMps: Double,
        enabled: Boolean,
    ): LaunchControlPhase {
        if (!enabled) {
            return LaunchControlPhase.INACTIVE
        }

        val fullThrottle = rawThrottle >= FULL_THROTTLE_THRESHOLD
        val brakeHeld = brake >= ARM_BRAKE_THRESHOLD
        val brakeReleased = brake < RELEASE_BRAKE_THRESHOLD
        val canArm = canArmAtSpeed(speedMps)

        return when (phase) {
            LaunchControlPhase.INACTIVE -> {
                if (fullThrottle && brakeHeld && canArm) {
                    LaunchControlPhase.ARMED
                } else {
                    LaunchControlPhase.INACTIVE
                }
            }

            LaunchControlPhase.ARMED -> {
                when {
                    brakeReleased -> LaunchControlPhase.LAUNCHED
                    !fullThrottle -> LaunchControlPhase.DISARMING
                    else -> LaunchControlPhase.ARMED
                }
            }

            LaunchControlPhase.DISARMING -> {
                if (fullThrottle && brakeHeld && canArm) {
                    LaunchControlPhase.ARMED
                } else {
                    LaunchControlPhase.DISARMING
                }
            }

            LaunchControlPhase.LAUNCHED -> {
                if (brakeHeld) {
                    LaunchControlPhase.INACTIVE
                } else {
                    LaunchControlPhase.LAUNCHED
                }
            }
        }
    }
}

internal fun launchControlJitterPhaseStep(dt: Double, currentPhaseRadians: Double): Double {
    return currentPhaseRadians + dt * LaunchControl.JITTER_HZ * 2.0 * PI
}
