package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

internal class LayerMixRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(profile: EngineSampleProfile): Map<String, LayerMixControl> {
        val tracks = profile.mixerTrackOrder().map { it.first }
        return tracks.associateWith { trackId ->
            LayerMixControl(
                volume = preferences.getFloat(volumeKey(profile.id, trackId), 1.0f).toDouble(),
                muted = preferences.getBoolean(muteKey(profile.id, trackId), false),
                solo = preferences.getBoolean(soloKey(profile.id, trackId), false),
            )
        }
    }

    fun setVolume(profile: EngineSampleProfile, trackId: String, volume: Double): Map<String, LayerMixControl> {
        preferences.edit()
            .putFloat(
                volumeKey(profile.id, trackId),
                volume.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER).toFloat(),
            )
            .apply()
        return load(profile)
    }

    fun setMuted(profile: EngineSampleProfile, trackId: String, muted: Boolean): Map<String, LayerMixControl> {
        preferences.edit()
            .putBoolean(muteKey(profile.id, trackId), muted)
            .apply()
        return load(profile)
    }

    fun setSolo(profile: EngineSampleProfile, trackId: String, solo: Boolean): Map<String, LayerMixControl> {
        preferences.edit()
            .putBoolean(soloKey(profile.id, trackId), solo)
            .apply()
        return load(profile)
    }

    private fun volumeKey(profileId: String, trackId: String): String = "$profileId.$trackId.volume"
    private fun muteKey(profileId: String, trackId: String): String = "$profileId.$trackId.muted"
    private fun soloKey(profileId: String, trackId: String): String = "$profileId.$trackId.solo"

    private companion object {
        const val PREFERENCES_NAME = "sample_layer_mix"
    }
}
