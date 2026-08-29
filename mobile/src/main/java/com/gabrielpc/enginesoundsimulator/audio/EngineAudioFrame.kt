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
    /** When true, play the shared recorded pops on throttle lift and mute each car's native overrun. */
    val popsAndBangsEnabled: Boolean = false,
    /** Linear multiplier applied on top of the shared pops sample gain (default 2×). */
    val popsAndBangsGain: Double = DEFAULT_POPS_AND_BANGS_GAIN,
    /** When true, play Huracán shift one-shots on every car and mute native shift effects. */
    val sharedShiftSoundsEnabled: Boolean = false,
    /** Linear multiplier applied on top of the shared shift sample gain (default 3×). */
    val sharedShiftSoundsGain: Double = DEFAULT_SHARED_SHIFT_SOUNDS_GAIN,
) {
    companion object {
        const val DEFAULT_POPS_AND_BANGS_GAIN = 2.0
        const val DEFAULT_SHARED_SHIFT_SOUNDS_GAIN = 3.0
        const val MIN_EFFECT_GAIN = 0.5
        const val MAX_EFFECT_GAIN = 6.0
    }
}
