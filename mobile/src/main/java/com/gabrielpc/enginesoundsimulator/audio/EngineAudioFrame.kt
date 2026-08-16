package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Realtime controls consumed by the sample-bank renderer. */
data class EngineAudioFrame(
    val rpm: Double = EngineSampleProfiles.default.idleRpm,
    val throttle: Double = 0.0,
    val enabled: Boolean = true,
    val tuning: AudioTuning = AudioTuning(),
)
