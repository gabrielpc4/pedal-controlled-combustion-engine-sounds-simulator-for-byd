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
    SampleLayerRole.COAST -> "Coast"
    SampleLayerRole.TEXTURE -> "Texture"
    SampleLayerRole.INTAKE -> "Intake"
    SampleLayerRole.EXHAUST -> "Exhaust"
    SampleLayerRole.TURBO -> "Turbo"
    SampleLayerRole.SPOOL -> "Spool"
    SampleLayerRole.LIMITER -> "Limiter"
}

internal fun SampleEffectSpec.playingRoleLabel(): String = when (trigger) {
    SampleEffectTrigger.CONTINUOUS_LOOP -> displayName
    SampleEffectTrigger.TRANSMISSION_LOOP -> control.displayName
    SampleEffectTrigger.LIMITER -> displayName
    SampleEffectTrigger.LIMITER_EVENT -> displayName
    SampleEffectTrigger.SHIFT_UP -> "Shift up"
    SampleEffectTrigger.SHIFT_DOWN -> "Shift down"
    SampleEffectTrigger.THROTTLE_LIFT -> displayName
    SampleEffectTrigger.BOV_LIFT -> displayName
    SampleEffectTrigger.ENGINE_EVENT -> displayName
    SampleEffectTrigger.TURBO_EVENT -> displayName
}
