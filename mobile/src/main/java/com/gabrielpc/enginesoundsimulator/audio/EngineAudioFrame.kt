package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Realtime controls consumed by the sample-bank renderer. */
data class EngineAudioFrame(
    val rpm: Double = EngineSampleProfiles.default.idleRpm,
    val throttle: Double = 0.0,
    val enabled: Boolean = true,
    val enabledEffectMask: Long = 0L,
    val soloEffects: Boolean = false,
    val shiftSerial: Long = 0L,
    val shiftDirection: Int = 0,
    val tuning: AudioTuning = AudioTuning(),
    val layerMix: Map<String, LayerMixControl> = emptyMap(),
)
