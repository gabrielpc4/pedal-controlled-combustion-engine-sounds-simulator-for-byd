package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** Persists user overrides around FMOD's authored per-event mix. */
internal class FmodEventMixRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): FmodEventMixSettings {
        return FmodEventMixSettings(
            controls = FmodEventKind.entries.associateWith { kind ->
                FmodEventControl(
                    enabled = preferences.getBoolean(enabledKey(kind), true),
                    gainDb = preferences
                        .getFloat(gainKey(kind), 0f)
                        .toDouble()
                        .coerceIn(FmodEventMixSettings.MIN_GAIN_DB, FmodEventMixSettings.MAX_GAIN_DB),
                )
            },
        )
    }

    fun setEnabled(kind: FmodEventKind, enabled: Boolean): FmodEventMixSettings {
        preferences.edit().putBoolean(enabledKey(kind), enabled).apply()
        return load()
    }

    fun setGainDb(kind: FmodEventKind, gainDb: Double): FmodEventMixSettings {
        val clamped = gainDb.coerceIn(FmodEventMixSettings.MIN_GAIN_DB, FmodEventMixSettings.MAX_GAIN_DB)
        preferences.edit().putFloat(gainKey(kind), clamped.toFloat()).apply()
        return load()
    }

    private fun enabledKey(kind: FmodEventKind): String = "${kind.name.lowercase()}.enabled"
    private fun gainKey(kind: FmodEventKind): String = "${kind.name.lowercase()}.gain_db"

    private companion object {
        const val PREFERENCES_NAME = "fmod_event_mix"
    }
}
