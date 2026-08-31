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
    SampleEffectTrigger.PARAMETER_PLACEMENT_ENTRY -> "Engine parameter accent"
    SampleEffectTrigger.ENGINE_EVENT_START -> "Engine event start"
    SampleEffectTrigger.ENGINE_START -> "Engine start"
    SampleEffectTrigger.TRANSMISSION_LOOP -> control.displayName
    SampleEffectTrigger.TRANSMISSION_PULSE -> "Transmission pulse"
    SampleEffectTrigger.SHIFT_UP -> "Shift up"
    SampleEffectTrigger.SHIFT_DOWN -> "Shift down"
    SampleEffectTrigger.SHIFT_REJECTED -> "Gear grind"
    SampleEffectTrigger.THROTTLE_LIFT -> control.displayName
    SampleEffectTrigger.TURBO_LOOP -> "Turbo"
    SampleEffectTrigger.TURBO_FLUTTER -> "Turbo flutter"
    SampleEffectTrigger.TURBO_DUMP -> "Turbo dump"
    SampleEffectTrigger.LIMITER_LOOP,
    SampleEffectTrigger.LIMITER_PULSE,
    -> "Limiter"
    SampleEffectTrigger.TRACTION_LIMIT,
    SampleEffectTrigger.TRACTION_PULSE,
    -> "Traction control"
}
