package com.gabrielpc.enginesoundsimulator.simulation

enum class AutomaticTransmissionMode {
    CRUISING,
    RACING,
}

/**
 * Automatic shift behavior layered on top of each bank's authored thresholds.
 *
 * Racing upshift/downshift triggers are relocated near the limiter instead of using the bank's
 * absolute auto_up/auto_down RPM values. Cruising then lowers those relocated triggers further.
 */
internal object AutomaticTransmissionPolicy {
    const val RACING_ENTER_MIN_THROTTLE = 0.40
    /** Emergency upshift once after holding the limiter this long, unless already in top gear. */
    const val EMERGENCY_UPSHIFT_HOLD_SECONDS = 1.5
    /** Automatic racing ends immediately below this road speed with a light pedal. */
    const val RACING_RETURN_MAX_SPEED_KMH = 30.0
    /** Racing upshift sits this many RPM below the authored limiter. */
    const val UPSHIFT_MARGIN_BELOW_LIMITER_RPM = 150.0
    private const val MINIMUM_THRESHOLD_ABOVE_IDLE_RPM = 250.0
    private const val MINIMUM_UPSHIFT_DOWNSHIFT_SPREAD_RPM = 500.0

    data class RelocatedShiftThresholds(
        val upshiftRpm: Double,
        val downshiftRpm: Double,
    )

    /**
     * Anchor automatic upshift just below the limiter and preserve the bank's up/down spread
     * so downshift hysteresis moves with it instead of staying at the authored absolute RPMs.
     */
    fun relocatedShiftThresholds(
        authoredUpshiftRpm: Int,
        authoredDownshiftRpm: Int,
        limiterRpm: Double,
        idleRpm: Double,
    ): RelocatedShiftThresholds {
        val minimumRpm = idleRpm + MINIMUM_THRESHOLD_ABOVE_IDLE_RPM
        val authoredSpread = (authoredUpshiftRpm - authoredDownshiftRpm)
            .toDouble()
            .coerceAtLeast(MINIMUM_UPSHIFT_DOWNSHIFT_SPREAD_RPM)

        val upshiftRpm = if (limiterRpm > 0.0) {
            (limiterRpm - UPSHIFT_MARGIN_BELOW_LIMITER_RPM).coerceAtLeast(minimumRpm)
        } else {
            authoredUpshiftRpm.toDouble().coerceAtLeast(minimumRpm)
        }

        val downshiftRpm = (upshiftRpm - authoredSpread).coerceAtLeast(minimumRpm)

        return RelocatedShiftThresholds(
            upshiftRpm = upshiftRpm,
            downshiftRpm = downshiftRpm,
        )
    }

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
