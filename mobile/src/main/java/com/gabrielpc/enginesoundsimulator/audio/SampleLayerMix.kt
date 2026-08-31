package com.gabrielpc.enginesoundsimulator.audio

/** User trim applied to one loop layer or effect sample. [volume] is a gain multiplier (1.0 = unchanged) in load-only mode. */
data class LayerMixControl(
    val volume: Double = DEFAULT_GAIN_MULTIPLIER,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    companion object {
        const val DEFAULT_GAIN_MULTIPLIER = 1.0
        const val MIN_GAIN_MULTIPLIER = 0.0
        /** Enough headroom for quiet effects (e.g. transmission ~14%) to reach full meter scale. */
        const val MAX_GAIN_MULTIPLIER = 8.0

        val DEFAULT = LayerMixControl()
    }
}

/** One row in the mixer dashboard: slider, mute/solo, and live output meter. */
data class LayerMixTrackState(
    val id: String,
    val displayName: String,
    val sortGroup: Int,
    val userVolume: Double,
    val muted: Boolean,
    val solo: Boolean,
    /** Smoothed gain currently reaching the audio mix (0–1). */
    val outputLevel: Double,
    val isEffect: Boolean,
    /** True for every mixer row except Coast and Load — shows a trim slider under the live meter. */
    val showVolumeSlider: Boolean = false,
    val isLoadLayer: Boolean = false,
)

/** Live output level for one mixer track row. */
data class LayerOutputMeter(
    val id: String,
    val outputLevel: Double,
)

internal fun SampleLayerRole.mixerSortGroup(): Int = when (this) {
    SampleLayerRole.IDLE -> 0
    SampleLayerRole.COAST -> 1
    SampleLayerRole.LOAD -> 2
    SampleLayerRole.TEXTURE -> 3
    SampleLayerRole.LIMITER -> 4
}

internal fun SampleLayerSpec.mixerDisplayName(): String {
    val detail = id.split('_').joinToString(" ") { segment ->
        segment.replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase()
            } else {
                char.toString()
            }
        }
    }
    return "${role.playingRoleLabel()} · $detail"
}

internal fun SampleEffectSpec.mixerDisplayName(): String = playingRoleLabel()

internal fun SampleEffectTrigger.mixerSortGroup(): Int = when (this) {
    SampleEffectTrigger.PARAMETER_PLACEMENT_ENTRY,
    SampleEffectTrigger.ENGINE_EVENT_START,
    SampleEffectTrigger.ENGINE_START,
    -> 5
    SampleEffectTrigger.TRANSMISSION_LOOP -> 5
    SampleEffectTrigger.TRANSMISSION_PULSE -> 6
    SampleEffectTrigger.TURBO_LOOP -> 7
    SampleEffectTrigger.TURBO_FLUTTER -> 8
    SampleEffectTrigger.TURBO_DUMP -> 9
    SampleEffectTrigger.LIMITER_LOOP,
    SampleEffectTrigger.LIMITER_PULSE,
    -> 10
    SampleEffectTrigger.TRACTION_LIMIT,
    SampleEffectTrigger.TRACTION_PULSE,
    -> 11
    SampleEffectTrigger.SHIFT_UP -> 12
    SampleEffectTrigger.SHIFT_DOWN -> 13
    SampleEffectTrigger.SHIFT_REJECTED -> 14
    SampleEffectTrigger.THROTTLE_LIFT -> 15
}

internal fun EngineSampleProfile.mixerTrackOrder(
    perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
): List<Pair<String, Int>> {
    val program = program(perspective)
    val layers = program.layers.map { it.id to it.role.mixerSortGroup() }
    val effects = program.effects.map { it.id to it.trigger.mixerSortGroup() }
    return (layers + effects).sortedWith(compareBy({ it.second }, { it.first }))
}

internal fun EngineSampleProfile.allMixerTrackOrder(): List<Pair<String, Int>> =
    EngineSoundPerspective.entries
        .flatMap { perspective -> mixerTrackOrder(perspective) }
        .distinctBy { track -> track.first }
