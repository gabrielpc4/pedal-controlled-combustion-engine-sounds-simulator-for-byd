package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Realtime controls consumed by the native FMOD Studio runtime. */
data class EngineAudioFrame(
    val rpm: Double = FmodBankProfiles.default.idleRpm,
    val throttle: Double = 0.0,
    val enabled: Boolean = true,
    val shiftSerial: Long = 0L,
    val shiftDirection: Int = 0,
    val isShifting: Boolean = false,
    /** True only while the drivetrain is at its configured RPM limiter. */
    val limiterActive: Boolean = false,
    val tuning: AudioTuning = AudioTuning(),
    val layerMix: Map<String, LayerMixControl> = emptyMap(),
    /** Per-perspective master trims for all continuous Load and Coast layers. */
    val programLayerGains: ProgramLayerGains = ProgramLayerGains(),
    /** Authored throttle endpoint(s) used by the continuous engine event. */
    val primaryLayerSource: PrimaryEngineLayerSource = PrimaryEngineLayerSource.LOAD,
    /** Enables the selected bank's authored backfire event after a deliberate throttle lift. */
    val popsAndBangsEnabled: Boolean = false,
    /** Linear multiplier applied to the selected bank's authored backfire event. */
    val popsAndBangsGain: Double = DEFAULT_POPS_AND_BANGS_GAIN,
    /** False in Park/Neutral so exhaust overrun cannot fire on a free rev. */
    val throttleLiftEffectsEnabled: Boolean = true,
    /** Multiplier for turbo response when the pedal is pressed; Drive uses the authored 1× rate. */
    val turboSpoolAttackMultiplier: Double = 1.0,
    /** Enables every turbo loop, flutter, and dump sound provided by the selected car. */
    val turboSoundsEnabled: Boolean = true,
    /** Linear multiplier applied to every turbo loop, flutter, and dump sound. */
    val turboSoundsGain: Double = DEFAULT_TURBO_SOUNDS_GAIN,
    /** Enables the selected bank's authored gear-shift event. */
    val shiftSoundsEnabled: Boolean = true,
    /** Linear multiplier applied to the selected bank's authored gear-shift event. */
    val shiftSoundsGain: Double = DEFAULT_SHIFT_SOUNDS_GAIN,
    /** Enables each car's continuous transmission event, when its bank provides one. */
    val transmissionEnabled: Boolean = true,
    /** Linear multiplier applied to each car's continuous transmission event. */
    val transmissionGain: Double = DEFAULT_TRANSMISSION_GAIN,
) {
    companion object {
        const val DEFAULT_POPS_AND_BANGS_GAIN = 2.0
        const val DEFAULT_SHIFT_SOUNDS_GAIN = 3.0
        const val DEFAULT_TRANSMISSION_GAIN = 1.0
        const val DEFAULT_TURBO_SOUNDS_GAIN = 1.0
        const val MIN_EFFECT_GAIN = 0.5
        const val MIN_TURBO_SOUNDS_GAIN = 0.25
        const val MAX_EFFECT_GAIN = 6.0
    }
}
