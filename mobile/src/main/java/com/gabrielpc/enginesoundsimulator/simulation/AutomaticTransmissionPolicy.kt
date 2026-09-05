package com.gabrielpc.enginesoundsimulator.simulation

internal enum class AutomaticTransmissionMode {
    CRUISING,
    RACING,
}

/**
 * Automatic shift behavior layered on top of each bank's authored thresholds.
 *
 * Cruising lowers up/down RPM triggers. A sudden throttle stomp downshifts once and switches to
 * racing, which uses the car's normal thresholds until the driver stays below a configurable
 * throttle ceiling for a configurable hold time.
 */
internal object AutomaticTransmissionPolicy {
    const val STOMP_MIN_THROTTLE = 0.55
    const val STOMP_MIN_DELTA = 0.22
    const val STOMP_MIN_RATE_PER_SECOND = 3.5
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
