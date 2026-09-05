package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Per-car sound switches and Override choices shown on the main dashboard. */
internal data class CarEffectModes(
    val popsAndBangsEnabled: Boolean = true,
    /** When true, global backfire policy and shared samples replace the bank's authored behavior. */
    val popsAndBangsOverride: Boolean = false,
    val shiftSoundsEnabled: Boolean = true,
    /** When true, shared shift one-shots replace the bank's authored gear events. */
    val shiftSoundsOverride: Boolean = false,
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
        popsAndBangsOverride = readOverride(profile, "pops", default = false),
        shiftSoundsEnabled = read(profile, "shift_enabled", true),
        shiftSoundsOverride = readOverride(profile, "shift", default = false),
        transmissionEnabled = read(profile, "transmission_enabled", true),
        turboEnabled = read(profile, "turbo_enabled", true),
    )

    fun save(profile: FmodBankProfile, modes: CarEffectModes) {
        preferences.edit()
            .putBoolean(key(profile, "pops_enabled"), modes.popsAndBangsEnabled)
            .putBoolean(key(profile, "pops_override"), modes.popsAndBangsOverride)
            .putBoolean(key(profile, "shift_enabled"), modes.shiftSoundsEnabled)
            .putBoolean(key(profile, "shift_override"), modes.shiftSoundsOverride)
            .putBoolean(key(profile, "transmission_enabled"), modes.transmissionEnabled)
            .putBoolean(key(profile, "turbo_enabled"), modes.turboEnabled)
            .commit()
    }

    fun resetAll() { preferences.edit().clear().commit() }

    private fun read(profile: FmodBankProfile, name: String, default: Boolean): Boolean =
        preferences.getBoolean(key(profile, name), default)

    /**
     * Reads the new override flag when present. Legacy installs stored the inverse as
     * `*_original`; migrate that once so existing per-car choices keep the same behavior.
     */
    private fun readOverride(profile: FmodBankProfile, prefix: String, default: Boolean): Boolean {
        val overrideKey = key(profile, "${prefix}_override")
        val legacyOriginalKey = key(profile, "${prefix}_original")
        if (preferences.contains(overrideKey)) {
            return preferences.getBoolean(overrideKey, default)
        }
        if (preferences.contains(legacyOriginalKey)) {
            return !preferences.getBoolean(legacyOriginalKey, !default)
        }
        return default
    }

    private fun key(profile: FmodBankProfile, name: String): String = "${profile.id}.$name"
}
