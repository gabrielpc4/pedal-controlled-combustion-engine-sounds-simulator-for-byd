package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.drive.AutomaticTransmissionSettings
import com.gabrielpc.enginesoundsimulator.drive.RacingReturnHoldSeconds
import com.gabrielpc.enginesoundsimulator.drive.RacingReturnThrottlePercent

internal data class AutomaticTransmissionConfig(
    val cruisingShiftOffsetRpm: Int = 0,
    val racingReturnMaxThrottle: Double = RacingReturnThrottlePercent.asFraction(
        RacingReturnThrottlePercent.DEFAULT,
    ),
    val racingReturnHoldSeconds: Double = RacingReturnHoldSeconds.DEFAULT.toDouble(),
) {
    companion object {
        fun fromSettings(settings: AutomaticTransmissionSettings): AutomaticTransmissionConfig {
            return AutomaticTransmissionConfig(
                cruisingShiftOffsetRpm = settings.cruisingShiftOffsetRpm,
                racingReturnMaxThrottle = RacingReturnThrottlePercent.asFraction(
                    settings.racingReturnThrottlePercent,
                ),
                racingReturnHoldSeconds = settings.racingReturnHoldSeconds.toDouble(),
            )
        }
    }
}
