package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.max

/**
 * Reconstructs the Assetto Corsa Skyline turbo parameters:
 * [boost] feeds the continuous whistle, [bovDecay] feeds compressor flutter.
 *
 * The bank exposes those two event parameters. Its boost follows the same
 * direct LAG_UP/LAG_DN integrator used by the Audio Lab's Skyline engine path.
 */
internal class TurboSpoolModel {
    var boost = 0.0
        private set

    var bovDecay = 0.0
        private set

    private var previousThrottle = 0.0
    private var pendingDump = false

    fun reset() {
        boost = 0.0
        bovDecay = 0.0
        previousThrottle = 0.0
        pendingDump = false
    }

    fun consumeDumpPulse(): Boolean {
        val fire = pendingDump
        pendingDump = false
        return fire
    }

    fun update(
        dt: Double,
        rpm: Double,
        throttle: Double,
        attackMultiplier: Double = 1.0,
    ) {
        val clampedDt = dt.coerceIn(1.0 / 1_000.0, 0.080)
        val pedal = throttle.coerceIn(0.0, 1.0)
        val target = (pedal * rpm.coerceAtLeast(0.0) / REFERENCE_RPM).coerceIn(0.0, 1.0)
        val drop = previousThrottle - pedal
        val dumping = drop >= LIFT_DROP && boost * (1.0 - pedal) > DUMP_CHARGE_THRESHOLD

        if (dumping) {
            pendingDump = true
            bovDecay = max(bovDecay, (boost * 0.62).coerceIn(0.28, 0.55))
        }

        val lag = if (target > boost) {
            LAG_UP * attackMultiplier.coerceIn(MINIMUM_ATTACK_MULTIPLIER, MAXIMUM_ATTACK_MULTIPLIER)
        } else {
            LAG_DOWN
        }
        boost += (clampedDt * lag).coerceIn(0.0, 1.0) * (target - boost)
        bovDecay = (bovDecay - clampedDt / BOV_DECAY_SECONDS).coerceAtLeast(0.0)
        previousThrottle = pedal
    }

    fun whistleGain(): Double {
        if (boost < WHISTLE_FLOOR) {
            return 0.0
        }

        return ((boost - WHISTLE_FLOOR) / (1.0 - WHISTLE_FLOOR)).coerceIn(0.0, 1.0).let { shaped ->
            shaped * shaped
        }
    }

    fun whistlePlaybackRatio(): Double {
        return 0.58 + boost * 1.12
    }

    fun flutterGain(): Double {
        if (bovDecay <= 0.02) {
            return 0.0
        }

        return bovDecay
    }

    fun flutterPlaybackRatio(): Double {
        return 0.86 + bovDecay * 0.28
    }

    companion object {
        const val REFERENCE_RPM = 5_000.0
        const val LAG_UP = 0.98
        const val LAG_DOWN = 0.98
        const val BOV_DECAY_SECONDS = 0.85
        const val LIFT_DROP = 0.20
        /**
         * Minimum charged boost required to vent on a real throttle lift.
         *
         * This is deliberately much lower than the fully-spooled boost level: short, useful pulls
         * should still vent, while a brush of the accelerator must not produce a dump or overrun.
         */
        const val DUMP_CHARGE_THRESHOLD = 0.18
        const val WHISTLE_FLOOR = 0.06
        const val MINIMUM_ATTACK_MULTIPLIER = 0.25
        const val MAXIMUM_ATTACK_MULTIPLIER = 16.0
    }
}
