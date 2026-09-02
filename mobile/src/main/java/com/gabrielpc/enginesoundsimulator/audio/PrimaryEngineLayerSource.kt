package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Chooses the throttle endpoint used by the native FMOD engine event. */
enum class PrimaryEngineLayerSource(
    val displayName: String,
    internal val nativeValue: Int,
) {
    LOAD("LOAD", 0),
    COAST("COAST", 1),
    BOTH("BOTH", 2),
}

internal class PrimaryEngineLayerSourceRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.PRIMARY_ENGINE_LAYER_SOURCE,
        Context.MODE_PRIVATE,
    )

    fun load(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): PrimaryEngineLayerSource {
        val saved = preferences.getString(sourceKey(profile.id, perspective), null)
        return PrimaryEngineLayerSource.entries.firstOrNull { it.name == saved }
            ?: defaultFor(perspective)
    }

    fun save(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
        source: PrimaryEngineLayerSource,
    ): PrimaryEngineLayerSource {
        preferences.edit().putString(sourceKey(profile.id, perspective), source.name).commit()
        return source
    }

    private fun sourceKey(profileId: String, perspective: EngineSoundPerspective): String =
        "$profileId.${perspective.name.lowercase()}.primary_engine_layer_source"

    private fun defaultFor(perspective: EngineSoundPerspective): PrimaryEngineLayerSource =
        if (perspective == EngineSoundPerspective.EXTERIOR) {
            PrimaryEngineLayerSource.BOTH
        } else {
            PrimaryEngineLayerSource.LOAD
        }
}
