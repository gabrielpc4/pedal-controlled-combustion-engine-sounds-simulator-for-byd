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
    /** Signed, quantization-smoothed road speed used by donor drivetrain sound parameters. */
    val presentationSpeedMetersPerSecond: Double = 0.0,
    val limiterActive: Boolean = false,
    val tractionLimitActive: Boolean = false,
    val shiftRejectedSerial: Long = 0L,
    val engineStarting: Boolean = false,
    val tuning: AudioTuning = AudioTuning(),
    val layerMix: Map<String, LayerMixControl> = emptyMap(),
    /** Per-perspective master trims for all continuous Load and Coast layers. */
    val programLayerGains: ProgramLayerGains = ProgramLayerGains(),
    /** Enables LOAD/COAST isolation; BOTH still follows the live pedal while RPM changes independently. */
    val loadOnlyProgram: Boolean = true,
    /** Recorded family used as the continuous driving engine sound for profiles that support it. */
    val primaryLayerSource: PrimaryEngineLayerSource = PrimaryEngineLayerSource.LOAD,
    /** When true, play the shared recorded pops on throttle lift and mute each car's native overrun. */
    val popsAndBangsEnabled: Boolean = false,
    /** Linear multiplier applied on top of the shared pops sample gain (default 2×). */
    val popsAndBangsGain: Double = DEFAULT_POPS_AND_BANGS_GAIN,
    /** False in Park/Neutral so exhaust overrun and shared pops cannot fire on a free rev. */
    val throttleLiftEffectsEnabled: Boolean = true,
    /** Multiplier for turbo response when the pedal is pressed; Drive uses the authored 1× rate. */
    val turboSpoolAttackMultiplier: Double = 1.0,
    /** Enables every turbo loop, flutter, and dump sound provided by the selected car. */
    val turboSoundsEnabled: Boolean = true,
    /** Linear multiplier applied to every turbo loop, flutter, and dump sound. */
    val turboSoundsGain: Double = DEFAULT_TURBO_SOUNDS_GAIN,
    /** When true, play Huracán shift one-shots on every car and mute native shift effects. */
    val sharedShiftSoundsEnabled: Boolean = false,
    /** Linear multiplier applied on top of the shared shift sample gain (default 3×). */
    val sharedShiftSoundsGain: Double = DEFAULT_SHARED_SHIFT_SOUNDS_GAIN,
    /** Enables each car's continuous transmission sample, when its bank provides one. */
    val transmissionEnabled: Boolean = true,
    /** Linear multiplier applied to each car's continuous transmission sample. */
    val transmissionGain: Double = DEFAULT_TRANSMISSION_GAIN,
) {
    companion object {
        const val DEFAULT_POPS_AND_BANGS_GAIN = 2.0
        const val DEFAULT_SHARED_SHIFT_SOUNDS_GAIN = 3.0
        const val DEFAULT_TRANSMISSION_GAIN = 1.0
        const val DEFAULT_TURBO_SOUNDS_GAIN = 1.0
        const val MIN_EFFECT_GAIN = 0.5
        const val MIN_TURBO_SOUNDS_GAIN = 0.25
        const val MAX_EFFECT_GAIN = 6.0
    }
}
