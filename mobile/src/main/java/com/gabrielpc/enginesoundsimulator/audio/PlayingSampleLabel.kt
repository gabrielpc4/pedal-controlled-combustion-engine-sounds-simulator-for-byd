package com.gabrielpc.enginesoundsimulator.audio

/** One audibly mixed engine loop or effect shown in the dashboard NOW PLAYING panel. */
data class PlayingSampleLabel(
    val role: String,
    val assetName: String,
) {
    fun displayText(): String = "$role ($assetName)"
}

internal fun SampleLayerRole.playingRoleLabel(): String = when (this) {
    SampleLayerRole.IDLE -> "Idle"
    SampleLayerRole.LOAD -> "Load"
    SampleLayerRole.COAST -> "Coast"
    SampleLayerRole.TEXTURE -> "Texture"
    SampleLayerRole.LIMITER -> "Limiter"
}

internal fun SampleEffectSpec.playingRoleLabel(): String = when (trigger) {
    SampleEffectTrigger.TRANSMISSION_LOOP -> control.displayName
    SampleEffectTrigger.SHIFT_UP -> "Shift up"
    SampleEffectTrigger.SHIFT_DOWN -> "Shift down"
    SampleEffectTrigger.THROTTLE_LIFT -> control.displayName
}
