package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** Chooses the continuous engine WAV program. */
enum class PrimaryEngineLayerSource(val displayName: String) {
    LOAD("LOAD"),
    COAST("COAST"),
    FMOD_MIX("FMOD MIX"),
}

internal class PrimaryEngineLayerSourceRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(profile: EngineSampleProfile): PrimaryEngineLayerSource {
        val saved = preferences.getString(sourceKey(profile.id), null)
        return profile.resolvedPrimaryLayerSource(
            PrimaryEngineLayerSource.entries.firstOrNull { it.name == saved } ?: PrimaryEngineLayerSource.LOAD,
        )
    }

    fun save(profile: EngineSampleProfile, source: PrimaryEngineLayerSource): PrimaryEngineLayerSource {
        val resolved = profile.resolvedPrimaryLayerSource(source)
        preferences.edit().putString(sourceKey(profile.id), resolved.name).apply()
        return resolved
    }

    private fun sourceKey(profileId: String): String = "$profileId.primary_engine_layer_source"

    private companion object {
        const val PREFERENCES_NAME = "primary_engine_layer_source"
    }
}
