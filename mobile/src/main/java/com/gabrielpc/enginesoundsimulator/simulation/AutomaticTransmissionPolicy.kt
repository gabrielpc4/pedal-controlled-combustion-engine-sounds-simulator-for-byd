package com.gabrielpc.enginesoundsimulator.simulation

enum class AutomaticTransmissionMode {
    CRUISING,
    RACING,
}

/**
 * Automatic shift behavior layered on top of each bank's authored thresholds.
 *
 * Cruising lowers up/down RPM triggers. Pressing above [RACING_ENTER_MIN_THROTTLE] switches
 * to racing and downshifts to the gear that would place the engine within manualAutodownshiftRpm
 * of redline at the current mapped road speed.
 */
internal object AutomaticTransmissionPolicy {
    const val RACING_ENTER_MIN_THROTTLE = 0.40
    /** Emergency upshift once after holding the limiter this long, unless already in top gear. */
    const val EMERGENCY_UPSHIFT_HOLD_SECONDS = 1.5
    /** Automatic racing ends immediately below this road speed with a light pedal. */
    const val RACING_RETURN_MAX_SPEED_KMH = 30.0
    private const val MINIMUM_THRESHOLD_ABOVE_IDLE_RPM = 250.0

    fun applyCruisingOffset(
        baseRpm: Double,
        offsetRpm: Int,
        idleRpm: Double,
    ): Double {
        if (offsetRpm <= 0) {
            return baseRpm
        }

        return (baseRpm - offsetRpm).coerceAtLeast(idleRpm + MINIMUM_THRESHOLD_ABOVE_IDLE_RPM)
    }
}
