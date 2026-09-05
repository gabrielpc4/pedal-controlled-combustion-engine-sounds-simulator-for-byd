package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import kotlin.math.roundToInt

/** RPM subtracted from automatic up/down thresholds while cruising. */
internal object CruisingShiftOffsetRpm {
    const val MIN = 0
    const val MAX = 4_000
    const val DEFAULT = 2_000
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

/** Manual mode: sustained redline time before returning to automatic racing mode. */
internal object ManualRedlineHoldSeconds {
    const val MIN = 1
    const val MAX = 10
    const val DEFAULT = 1
    const val STEP = 1

    fun normalize(value: Int): Int {
        val stepped = ((value.toFloat() / STEP).roundToInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }
}

/** Manual mode: below this RPM the drivetrain downshifts once without leaving manual. */
internal object ManualAutodownshiftRpm {
    const val MIN = 500
    const val MAX = 4_000
    const val DEFAULT = 2_000
    const val STEP = 100

    fun normalize(value: Int): Int {
        val stepped = ((value.toFloat() / STEP).roundToInt() * STEP)
        return stepped.coerceIn(MIN, MAX)
    }
}

internal data class AutomaticTransmissionSettings(
    val cruisingShiftOffsetRpm: Int = CruisingShiftOffsetRpm.DEFAULT,
    val racingReturnThrottlePercent: Int = RacingReturnThrottlePercent.DEFAULT,
    val racingReturnHoldSeconds: Int = RacingReturnHoldSeconds.DEFAULT,
    val manualRedlineHoldSeconds: Int = ManualRedlineHoldSeconds.DEFAULT,
    val manualAutodownshiftRpm: Int = ManualAutodownshiftRpm.DEFAULT,
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
            manualRedlineHoldSeconds = ManualRedlineHoldSeconds.normalize(
                preferences.getInt(KEY_MANUAL_REDLINER_HOLD_SECONDS, ManualRedlineHoldSeconds.DEFAULT),
            ),
            manualAutodownshiftRpm = ManualAutodownshiftRpm.normalize(
                preferences.getInt(KEY_MANUAL_AUTODOWNSHIFT_RPM, ManualAutodownshiftRpm.DEFAULT),
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
            .putInt(
                KEY_MANUAL_REDLINER_HOLD_SECONDS,
                ManualRedlineHoldSeconds.normalize(settings.manualRedlineHoldSeconds),
            )
            .putInt(
                KEY_MANUAL_AUTODOWNSHIFT_RPM,
                ManualAutodownshiftRpm.normalize(settings.manualAutodownshiftRpm),
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
        const val KEY_MANUAL_REDLINER_HOLD_SECONDS = "manual_redline_hold_seconds"
        const val KEY_MANUAL_AUTODOWNSHIFT_RPM = "manual_autodownshift_rpm"
        const val LEGACY_OFFSET_RPM_KEY = "offset_rpm"
    }
}
