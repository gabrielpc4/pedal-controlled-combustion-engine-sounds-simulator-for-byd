package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.exp
import kotlin.math.max

/**
 * Reconstructs the Assetto Corsa Skyline turbo parameters:
 * [boost] feeds the continuous whistle, [bovDecay] feeds compressor flutter.
 *
 * The bank exposes those two event parameters. Twin ceramic turbos on the RB26
 * spool early and dump quickly on lift, which is what this model does.
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

    fun update(dt: Double, rpm: Double, throttle: Double) {
        val clampedDt = dt.coerceIn(1.0 / 1_000.0, 0.080)
        val pedal = throttle.coerceIn(0.0, 1.0)
        val target = pedal * spoolByRpm(rpm)
        val drop = previousThrottle - pedal
        val dumping = drop >= LIFT_DROP && boost >= BOV_ARM_BOOST

        if (dumping) {
            pendingDump = true
            bovDecay = max(bovDecay, (boost * 0.62).coerceIn(0.28, 0.55))
        }

        val responseSeconds = when {
            target >= boost -> SPOOL_ATTACK_SECONDS
            pedal <= DUMP_THROTTLE -> BOOST_DUMP_SECONDS
            else -> BOOST_RELEASE_SECONDS
        }
        boost = approach(boost, target, responseSeconds, clampedDt)
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
        const val SPOOL_START_RPM = 1_450.0
        const val SPOOL_FULL_RPM = 2_850.0
        const val SPOOL_ATTACK_SECONDS = 0.28
        const val BOOST_RELEASE_SECONDS = 0.42
        const val BOOST_DUMP_SECONDS = 0.10
        const val BOV_DECAY_SECONDS = 0.85
        const val LIFT_DROP = 0.20
        const val BOV_ARM_BOOST = 0.18
        const val DUMP_THROTTLE = 0.14
        const val WHISTLE_FLOOR = 0.06

        internal fun spoolByRpm(rpm: Double): Double {
            if (rpm <= SPOOL_START_RPM) {
                return 0.0
            }

            if (rpm >= SPOOL_FULL_RPM) {
                val fade = if (rpm > 7_400.0) {
                    ((8_200.0 - rpm) / 800.0).coerceIn(0.45, 1.0)
                } else {
                    1.0
                }
                return fade
            }

            val fraction = (rpm - SPOOL_START_RPM) / (SPOOL_FULL_RPM - SPOOL_START_RPM)
            return fraction * fraction * (3.0 - 2.0 * fraction)
        }

        private fun approach(current: Double, target: Double, responseSeconds: Double, dt: Double): Double {
            val alpha = 1.0 - exp(-dt / responseSeconds.coerceAtLeast(0.008))
            return current + (target - current) * alpha
        }
    }
}
