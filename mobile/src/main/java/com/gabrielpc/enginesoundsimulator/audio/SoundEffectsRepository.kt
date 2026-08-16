package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

internal class SoundEffectsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadEnabledMask(profile: EngineSampleProfile): Long = profile.effectControls.fold(0L) { mask, control ->
        val key = key(profile.id, control.id)
        if (preferences.getBoolean(key, true)) mask or control.bit else mask
    }

    fun setEnabled(profile: EngineSampleProfile, controlId: String, enabled: Boolean): Long {
        val control = profile.effectControls.firstOrNull { it.id == controlId } ?: return loadEnabledMask(profile)
        preferences.edit().putBoolean(key(profile.id, control.id), enabled).apply()
        return loadEnabledMask(profile)
    }

    private fun key(profileId: String, controlId: String): String = "$profileId.$controlId"

    private companion object {
        const val PREFERENCES_NAME = "sample_sound_effects"
    }
}
