package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import java.security.MessageDigest

/** Persists controls by car, perspective, authored event path and raw sound name. */
internal class SourceMixRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.FMOD_SOURCE_MIX,
        Context.MODE_PRIVATE,
    )

    fun load(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceIds: Collection<String>,
    ): Map<String, SourceMixControl> = sourceIds.associateWith { sourceId ->
        SourceMixControl(
            gain = preferences.getFloat(key(profileId, perspective, sourceId, "gain"), 1.0f).toDouble(),
            muted = preferences.getBoolean(key(profileId, perspective, sourceId, "muted"), false),
            solo = preferences.getBoolean(key(profileId, perspective, sourceId, "solo"), false),
        ).sanitized()
    }

    fun setGain(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceId: String,
        gain: Double,
    ): SourceMixControl = update(profileId, perspective, sourceId) {
        it.copy(gain = gain.coerceIn(SourceMixControl.MIN_GAIN_MULTIPLIER, SourceMixControl.MAX_GAIN_MULTIPLIER))
    }

    fun setMuted(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceId: String,
        muted: Boolean,
    ): SourceMixControl = update(profileId, perspective, sourceId) { it.copy(muted = muted) }

    fun setSolo(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceId: String,
        solo: Boolean,
    ): SourceMixControl = update(profileId, perspective, sourceId) { it.copy(solo = solo) }

    private fun update(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceId: String,
        transform: (SourceMixControl) -> SourceMixControl,
    ): SourceMixControl {
        val current = load(profileId, perspective, listOf(sourceId))[sourceId] ?: SourceMixControl.DEFAULT
        val updated = transform(current).sanitized()
        preferences.edit()
            .putFloat(key(profileId, perspective, sourceId, "gain"), updated.gain.toFloat())
            .putBoolean(key(profileId, perspective, sourceId, "muted"), updated.muted)
            .putBoolean(key(profileId, perspective, sourceId, "solo"), updated.solo)
            .commit()
        return updated
    }

    private fun key(
        profileId: String,
        perspective: EngineSoundPerspective,
        sourceId: String,
        field: String,
    ): String = "$profileId.${perspective.name.lowercase()}.${sourceId.sha256()}.$field"
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
