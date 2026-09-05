package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import kotlin.math.roundToInt

/** RPM subtracted from automatic up/down thresholds while cruising. */
internal object CruisingShiftOffsetRpm {
    const val MIN = 0
    const val MAX = 4_000
    const val DEFAULT = 4_000
    const val STEP = 1_000

    fun normalize(value: Int): Int {
        val stepped = ((value.toFloat() / STEP).roundToInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }
}

/** Maximum pedal travel allowed before racing-mode return-to-cruising timer resets. */
internal object RacingReturnThrottlePercent {
    const val MIN = 20
    const val MAX = 80
    const val DEFAULT = 30
    const val STEP = 10

    fun normalize(value: Int): Int {
        val stepped = ((value.toFloat() / STEP).roundToInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }

    fun asFraction(percent: Int): Double {
        return normalize(percent) / 100.0
    }
}

/** Time below [RacingReturnThrottlePercent] required to leave racing mode. */
internal object RacingReturnHoldSeconds {
    const val MIN = 0
    const val MAX = 20
    const val DEFAULT = 10
    const val STEP = 5

    fun normalize(value: Int): Int {
        val stepped = ((value.toFloat() / STEP).roundToInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }
}

internal data class AutomaticTransmissionSettings(
    val cruisingShiftOffsetRpm: Int = CruisingShiftOffsetRpm.DEFAULT,
    val racingReturnThrottlePercent: Int = RacingReturnThrottlePercent.DEFAULT,
    val racingReturnHoldSeconds: Int = RacingReturnHoldSeconds.DEFAULT,
)

internal class AutomaticTransmissionSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        AppPreferenceStores.AUTOMATIC_TRANSMISSION_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): AutomaticTransmissionSettings {
        migrateLegacyOffsetIfNeeded()
        return AutomaticTransmissionSettings(
            cruisingShiftOffsetRpm = CruisingShiftOffsetRpm.normalize(
                preferences.getInt(KEY_CRUISING_SHIFT_OFFSET_RPM, CruisingShiftOffsetRpm.DEFAULT),
            ),
            racingReturnThrottlePercent = RacingReturnThrottlePercent.normalize(
                preferences.getInt(
                    KEY_RACING_RETURN_THROTTLE_PERCENT,
                    RacingReturnThrottlePercent.DEFAULT,
                ),
            ),
            racingReturnHoldSeconds = RacingReturnHoldSeconds.normalize(
                preferences.getInt(KEY_RACING_RETURN_HOLD_SECONDS, RacingReturnHoldSeconds.DEFAULT),
            ),
        )
    }

    fun save(settings: AutomaticTransmissionSettings) {
        preferences.edit()
            .putInt(KEY_CRUISING_SHIFT_OFFSET_RPM, CruisingShiftOffsetRpm.normalize(settings.cruisingShiftOffsetRpm))
            .putInt(
                KEY_RACING_RETURN_THROTTLE_PERCENT,
                RacingReturnThrottlePercent.normalize(settings.racingReturnThrottlePercent),
            )
            .putInt(
                KEY_RACING_RETURN_HOLD_SECONDS,
                RacingReturnHoldSeconds.normalize(settings.racingReturnHoldSeconds),
            )
            .commit()
    }

    fun reset() {
        preferences.edit().clear().commit()
    }

    private fun migrateLegacyOffsetIfNeeded() {
        if (preferences.contains(KEY_CRUISING_SHIFT_OFFSET_RPM)) {
            return
        }

        val legacyPreferences = appContext.getSharedPreferences(
            AppPreferenceStores.CRUISING_SHIFT_OFFSET_RPM,
            Context.MODE_PRIVATE,
        )
        if (!legacyPreferences.contains(LEGACY_OFFSET_RPM_KEY)) {
            return
        }

        preferences.edit()
            .putInt(
                KEY_CRUISING_SHIFT_OFFSET_RPM,
                CruisingShiftOffsetRpm.normalize(
                    legacyPreferences.getInt(LEGACY_OFFSET_RPM_KEY, CruisingShiftOffsetRpm.DEFAULT),
                ),
            )
            .commit()
    }

    private companion object {
        const val KEY_CRUISING_SHIFT_OFFSET_RPM = "cruising_shift_offset_rpm"
        const val KEY_RACING_RETURN_THROTTLE_PERCENT = "racing_return_throttle_percent"
        const val KEY_RACING_RETURN_HOLD_SECONDS = "racing_return_hold_seconds"
        const val LEGACY_OFFSET_RPM_KEY = "offset_rpm"
    }
}
