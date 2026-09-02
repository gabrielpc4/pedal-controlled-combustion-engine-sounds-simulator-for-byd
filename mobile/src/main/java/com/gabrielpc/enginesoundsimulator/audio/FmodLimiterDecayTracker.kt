package com.gabrielpc.enginesoundsimulator.audio

/**
 * Converts the drivetrain's limiter state into FMOD's authored `decay`
 * parameter: zero only at the beginning of a limiter event, then elapsed time.
 */
internal class FmodLimiterDecayTracker {
    private var secondsSincePulse = SILENT_DECAY_SECONDS
    private var wasLimiterActive = false

    fun advance(limiterActive: Boolean, deltaSeconds: Double): Float {
        secondsSincePulse = if (limiterActive && !wasLimiterActive) {
            0.0
        } else {
            (secondsSincePulse + deltaSeconds.coerceAtLeast(0.0)).coerceAtMost(SILENT_DECAY_SECONDS)
        }
        wasLimiterActive = limiterActive

        return secondsSincePulse.toFloat()
    }

    private companion object {
        const val SILENT_DECAY_SECONDS = 10.0
    }
}
