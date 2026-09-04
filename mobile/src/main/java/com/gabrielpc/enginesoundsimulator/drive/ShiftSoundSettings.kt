package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.audio.CarEffectModes

data class ShiftSoundSettings(val overrideEnabled: Boolean = false)

enum class EffectSoundKind { POPS_AND_BANGS, SHIFT, TRANSMISSION, TURBO }

internal fun CarEffectModes.withEnabled(kind: EffectSoundKind, enabled: Boolean): CarEffectModes = when (kind) {
    EffectSoundKind.POPS_AND_BANGS -> copy(popsAndBangsEnabled = enabled)
    EffectSoundKind.SHIFT -> copy(shiftSoundsEnabled = enabled)
    EffectSoundKind.TRANSMISSION -> copy(transmissionEnabled = enabled)
    EffectSoundKind.TURBO -> copy(turboEnabled = enabled)
}

internal fun CarEffectModes.withOriginal(kind: EffectSoundKind, original: Boolean): CarEffectModes = when (kind) {
    EffectSoundKind.POPS_AND_BANGS -> copy(popsAndBangsOriginal = original)
    EffectSoundKind.SHIFT -> copy(shiftSoundsOriginal = original)
    EffectSoundKind.TRANSMISSION, EffectSoundKind.TURBO -> this
}

internal class ShiftSoundSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SHIFT_SOUND_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): ShiftSoundSettings = ShiftSoundSettings(
        overrideEnabled = preferences.getBoolean("override_enabled", false),
    )

    fun save(settings: ShiftSoundSettings) {
        preferences.edit().putBoolean("override_enabled", settings.overrideEnabled).apply()
    }

    fun reset() { preferences.edit().clear().apply() }
}
