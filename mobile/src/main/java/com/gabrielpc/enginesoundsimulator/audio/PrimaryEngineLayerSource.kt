package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Chooses the continuous engine WAV program. */
enum class PrimaryEngineLayerSource(val displayName: String) {
    LOAD("LOAD"),
    COAST("COAST"),
    FMOD_MIX("BOTH"),
}

internal class PrimaryEngineLayerSourceRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.PRIMARY_ENGINE_LAYER_SOURCE,
        Context.MODE_PRIVATE,
    )

    fun load(
        profile: EngineSampleProfile,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): PrimaryEngineLayerSource {
        val saved = preferences.getString(sourceKey(profile.id, perspective), null)
            ?: if (perspective == EngineSoundPerspective.CABIN) {
                preferences.getString(legacySourceKey(profile.id), null)
            } else {
                null
            }
        return profile.resolvedPrimaryLayerSource(
            PrimaryEngineLayerSource.entries.firstOrNull { it.name == saved } ?: PrimaryEngineLayerSource.LOAD,
            perspective,
        )
    }

    fun save(
        profile: EngineSampleProfile,
        perspective: EngineSoundPerspective,
        source: PrimaryEngineLayerSource,
    ): PrimaryEngineLayerSource {
        val resolved = profile.resolvedPrimaryLayerSource(source, perspective)
        preferences.edit().putString(sourceKey(profile.id, perspective), resolved.name).commit()
        return resolved
    }

    private fun sourceKey(profileId: String, perspective: EngineSoundPerspective): String =
        "$profileId.${perspective.name.lowercase()}.primary_engine_layer_source"

    private fun legacySourceKey(profileId: String): String = "$profileId.primary_engine_layer_source"

}
