package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Per-car sound switches and Original/Override choices shown on the main dashboard. */
internal data class CarEffectModes(
    val popsAndBangsEnabled: Boolean = true,
    val popsAndBangsOriginal: Boolean = false,
    val shiftSoundsEnabled: Boolean = true,
    val shiftSoundsOriginal: Boolean = true,
    val transmissionEnabled: Boolean = true,
    val turboEnabled: Boolean = true,
)

internal class CarEffectModesRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.CAR_EFFECT_MODES,
        Context.MODE_PRIVATE,
    )

    fun load(profile: FmodBankProfile): CarEffectModes = CarEffectModes(
        popsAndBangsEnabled = read(profile, "pops_enabled", true),
        popsAndBangsOriginal = read(profile, "pops_original", false),
        shiftSoundsEnabled = read(profile, "shift_enabled", true),
        shiftSoundsOriginal = read(profile, "shift_original", true),
        transmissionEnabled = read(profile, "transmission_enabled", true),
        turboEnabled = read(profile, "turbo_enabled", true),
    )

    fun save(profile: FmodBankProfile, modes: CarEffectModes) {
        preferences.edit()
            .putBoolean(key(profile, "pops_enabled"), modes.popsAndBangsEnabled)
            .putBoolean(key(profile, "pops_original"), modes.popsAndBangsOriginal)
            .putBoolean(key(profile, "shift_enabled"), modes.shiftSoundsEnabled)
            .putBoolean(key(profile, "shift_original"), modes.shiftSoundsOriginal)
            .putBoolean(key(profile, "transmission_enabled"), modes.transmissionEnabled)
            .putBoolean(key(profile, "turbo_enabled"), modes.turboEnabled)
            .commit()
    }

    fun resetAll() { preferences.edit().clear().commit() }

    private fun read(profile: FmodBankProfile, name: String, default: Boolean): Boolean =
        preferences.getBoolean(key(profile, name), default)

    private fun key(profile: FmodBankProfile, name: String): String = "${profile.id}.$name"
}
