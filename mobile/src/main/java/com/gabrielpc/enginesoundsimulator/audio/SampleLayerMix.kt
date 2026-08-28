package com.gabrielpc.enginesoundsimulator.audio

/** User trim applied to one loop layer or effect sample. [volume] is a gain multiplier (1.0 = unchanged) in coast layer mix mode. */
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
    /** True for mixer rows that expose an independent trim slider. */
    val showVolumeSlider: Boolean = false,
)

/** Live output level for one mixer track row. */
data class LayerOutputMeter(
    val id: String,
    val outputLevel: Double,
)

internal fun SampleLayerRole.mixerSortGroup(): Int = when (this) {
    SampleLayerRole.IDLE -> 0
    SampleLayerRole.COAST -> 1
    SampleLayerRole.TEXTURE -> 2
    SampleLayerRole.INTAKE -> 3
    SampleLayerRole.EXHAUST -> 4
    SampleLayerRole.TURBO -> 5
    SampleLayerRole.SPOOL -> 6
    SampleLayerRole.LIMITER -> 7
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
    SampleEffectTrigger.ENGINE_EVENT -> 7
    SampleEffectTrigger.ENGINE_START -> 7
    SampleEffectTrigger.TURBO_EVENT -> 8
    SampleEffectTrigger.CONTINUOUS_LOOP -> 8
    SampleEffectTrigger.TRANSMISSION_LOOP -> 9
    SampleEffectTrigger.LIMITER -> 10
    SampleEffectTrigger.LIMITER_EVENT -> 10
    SampleEffectTrigger.SHIFT_UP -> 11
    SampleEffectTrigger.SHIFT_DOWN -> 12
    SampleEffectTrigger.THROTTLE_LIFT -> 13
    SampleEffectTrigger.BOV_LIFT -> 13
}

internal fun EngineSampleProfile.mixerTrackOrder(): List<Pair<String, Int>> {
    val layers = layers.map { it.id to it.role.mixerSortGroup() }
    val effects = effects.map { it.id to it.trigger.mixerSortGroup() }
    return (layers + effects).sortedWith(compareBy({ it.second }, { it.first }))
}
