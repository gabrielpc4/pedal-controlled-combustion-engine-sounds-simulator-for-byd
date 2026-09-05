package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import kotlin.math.roundToInt

/** Lower bound applied to FMOD engine/transmission throttle parameters. */
internal object MinimumAudioThrottle {
    const val MIN = 0.0f
    const val MAX = 1.0f
    const val DEFAULT = 0.25f
    const val STEP = 0.05f

    fun normalize(value: Float): Float {
        val stepped = (value / STEP).roundToInt() * STEP
        return stepped.coerceIn(MIN, MAX)
    }
}

internal class MinimumAudioThrottleRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.MINIMUM_AUDIO_THROTTLE,
        Context.MODE_PRIVATE,
    )

    fun load(): Float {
        if (preferences.contains(KEY_MINIMUM)) {
            return MinimumAudioThrottle.normalize(
                preferences.getFloat(KEY_MINIMUM, MinimumAudioThrottle.DEFAULT),
            )
        }

        val legacyForced = preferences.getBoolean(LEGACY_FORCE_ENABLED_KEY, true)
        val migrated = if (legacyForced) {
            1.0f
        } else {
            0.0f
        }
        save(migrated)
        return migrated
    }

    fun save(value: Float) {
        preferences.edit()
            .putFloat(KEY_MINIMUM, MinimumAudioThrottle.normalize(value))
            .remove(LEGACY_FORCE_ENABLED_KEY)
            .commit()
    }

    fun reset() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_MINIMUM = "minimum"
        const val LEGACY_FORCE_ENABLED_KEY = "enabled"
    }
}
