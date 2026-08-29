package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Realtime controls consumed by the sample-bank renderer. */
data class EngineAudioFrame(
    val rpm: Double = EngineSampleProfiles.default.idleRpm,
    val throttle: Double = 0.0,
    val enabled: Boolean = true,
    val shiftSerial: Long = 0L,
    val shiftDirection: Int = 0,
    val isShifting: Boolean = false,
    val tuning: AudioTuning = AudioTuning(),
    val layerMix: Map<String, LayerMixControl> = emptyMap(),
    /** When true, mute Load layers and ignore throttle in layer/output gain (RPM crossfades only). */
    val coastLayerMixEnabled: Boolean = true,
)
