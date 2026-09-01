package com.gabrielpc.enginesoundsimulator.audio

/** User trim applied to one native FMOD event group. */
data class LayerMixControl(
    val volume: Double = DEFAULT_GAIN_MULTIPLIER,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    companion object {
        const val DEFAULT_GAIN_MULTIPLIER = 1.0
        const val MIN_GAIN_MULTIPLIER = 0.0
        const val MAX_GAIN_MULTIPLIER = 4.0

        val DEFAULT = LayerMixControl()
    }
}

data class LayerMixTrackState(
    val id: String,
    val displayName: String,
    val sortGroup: Int,
    val userVolume: Double,
    val muted: Boolean,
    val solo: Boolean,
    /** Current host gain reaching this authored event group. */
    val outputLevel: Double,
    val isEffect: Boolean,
    val showVolumeSlider: Boolean = true,
    val isLoadLayer: Boolean = false,
)

data class LayerOutputMeter(
    val id: String,
    val outputLevel: Double,
)

internal data class FmodMixerTrack(
    val id: String,
    val displayName: String,
    val sortGroup: Int,
    val isEffect: Boolean,
    val isLoadLayer: Boolean = false,
)

internal enum class FmodEngineLayerRole { LOAD, COAST }

internal fun FmodBankProfile.mixerTracks(
    perspective: EngineSoundPerspective,
): List<FmodMixerTrack> = buildList {
    add(FmodMixerTrack("engine_load", "ENGINE · LOAD", 0, isEffect = false, isLoadLayer = true))
    add(FmodMixerTrack("engine_coast", "ENGINE · COAST", 1, isEffect = false))
    val capabilities = capabilitiesFor(resolvedPerspective(perspective))
    if (GenericCarEffect.TRANSMISSION in capabilities) {
        add(FmodMixerTrack("transmission", "TRANSMISSION", 2, isEffect = true))
    }
    if (GenericCarEffect.TURBO_LOOP in capabilities || GenericCarEffect.TURBO_DUMP in capabilities) {
        add(FmodMixerTrack("turbo", "TURBO", 3, isEffect = true))
    }
    if (GenericCarEffect.LIMITER in capabilities) {
        add(FmodMixerTrack("limiter", "LIMITER", 4, isEffect = true))
    }
    if (GenericCarEffect.SHIFT_UP in capabilities || GenericCarEffect.SHIFT_DOWN in capabilities) {
        add(FmodMixerTrack("gear", "GEAR SHIFTS", 5, isEffect = true))
    }
    if (GenericCarEffect.OVERRUN in capabilities) {
        add(FmodMixerTrack("overrun", "POPS & BANGS", 6, isEffect = true))
    }
}.sortedWith(compareBy(FmodMixerTrack::sortGroup, FmodMixerTrack::id))

internal fun FmodBankProfile.allMixerTrackOrder(): List<Pair<String, Int>> =
    EngineSoundPerspective.entries
        .flatMap { perspective -> mixerTracks(perspective).map { it.id to it.sortGroup } }
        .distinctBy { it.first }
