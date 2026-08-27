package com.gabrielpc.enginesoundsimulator.audio

/** User trim applied to one loop layer or effect sample. */
data class LayerMixControl(
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    companion object {
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
    SampleEffectTrigger.TRANSMISSION_LOOP -> 5
    SampleEffectTrigger.SHIFT_UP -> 6
    SampleEffectTrigger.SHIFT_DOWN -> 7
    SampleEffectTrigger.THROTTLE_LIFT -> 8
}

internal fun EngineSampleProfile.mixerTrackOrder(): List<Pair<String, Int>> {
    val layers = layers.map { it.id to it.role.mixerSortGroup() }
    val effects = effects.map { it.id to it.trigger.mixerSortGroup() }
    return (layers + effects).sortedWith(compareBy({ it.second }, { it.first }))
}
