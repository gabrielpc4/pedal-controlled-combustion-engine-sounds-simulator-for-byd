package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

internal class LayerMixRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.LAYER_MIX,
        Context.MODE_PRIVATE,
    )

    fun load(profile: FmodBankProfile): Map<String, LayerMixControl> {
        val tracks = profile.allMixerTrackOrder().map { it.first }
        return tracks.associateWith { trackId ->
            LayerMixControl(
                volume = preferences.getFloat(volumeKey(profile.id, trackId), 1.0f).toDouble(),
                muted = preferences.getBoolean(muteKey(profile.id, trackId), false),
                solo = preferences.getBoolean(soloKey(profile.id, trackId), false),
            )
        }
    }

    fun setVolume(profile: FmodBankProfile, trackId: String, volume: Double): Map<String, LayerMixControl> {
        preferences.edit()
            .putFloat(
                volumeKey(profile.id, trackId),
                volume.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER).toFloat(),
            )
            .commit()
        return load(profile)
    }

    fun setMuted(profile: FmodBankProfile, trackId: String, muted: Boolean): Map<String, LayerMixControl> {
        preferences.edit()
            .putBoolean(muteKey(profile.id, trackId), muted)
            .commit()
        return load(profile)
    }

    fun setSolo(profile: FmodBankProfile, trackId: String, solo: Boolean): Map<String, LayerMixControl> {
        preferences.edit()
            .putBoolean(soloKey(profile.id, trackId), solo)
            .commit()
        return load(profile)
    }

    fun loadProgramLayerGains(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
    ): ProgramLayerGains {
        return ProgramLayerGains(
            load = preferences.getFloat(programGainKey(profile.id, perspective, "load"), 1.0f).toDouble(),
            coast = preferences.getFloat(programGainKey(profile.id, perspective, "coast"), 1.0f).toDouble(),
        ).sanitized()
    }

    fun setProgramLayerGain(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
        role: FmodEngineLayerRole,
        gain: Double,
    ): ProgramLayerGains {
        val key = when (role) {
            FmodEngineLayerRole.LOAD -> "load"
            FmodEngineLayerRole.COAST -> "coast"
        }
        preferences.edit()
            .putFloat(
                programGainKey(profile.id, perspective, key),
                gain.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER).toFloat(),
            )
            .commit()
        return loadProgramLayerGains(profile, perspective)
    }

    private fun volumeKey(profileId: String, trackId: String): String = "$profileId.$trackId.volume"
    private fun muteKey(profileId: String, trackId: String): String = "$profileId.$trackId.muted"
    private fun soloKey(profileId: String, trackId: String): String = "$profileId.$trackId.solo"
    private fun programGainKey(profileId: String, perspective: EngineSoundPerspective, role: String): String =
        "$profileId.${perspective.name.lowercase()}.$role.program_gain"

}

/** Multipliers applied on top of each individual Load/Coast track trim for one car perspective. */
data class ProgramLayerGains(
    val load: Double = 1.0,
    val coast: Double = 1.0,
) {
    fun sanitized() = copy(
        load = load.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER),
        coast = coast.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER),
    )
}
