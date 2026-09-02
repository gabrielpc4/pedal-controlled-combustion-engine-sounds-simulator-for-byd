package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning

/** Realtime controls consumed by the native FMOD Studio runtime. */
data class EngineAudioFrame(
    val rpm: Double = FmodBankProfiles.default.idleRpm,
    val enabled: Boolean = true,
    val shiftSerial: Long = 0L,
    val shiftDirection: Int = 0,
    val shiftRejected: Boolean = false,
    val limiterPulse: Boolean = false,
    val backfireTriggered: Boolean = false,
    val tractionLimitActive: Boolean = false,
    val tractionLimitPulse: Boolean = false,
    val drivetrainSpeedRadiansPerSecond: Double = 0.0,
    val boost: Double = 0.0,
    val maximumBoost: Double = 0.0,
    val bov: Double = 0.0,
    val bovDecaySeconds: Double = 10.0,
    val perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    val tuning: AudioTuning = AudioTuning(),
    /** Enables the selected bank's authored backfire event after a deliberate throttle lift. */
    val popsAndBangsEnabled: Boolean = false,
    /** Linear multiplier applied to the selected bank's authored backfire event. */
    val popsAndBangsGain: Double = DEFAULT_POPS_AND_BANGS_GAIN,
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
        const val DEFAULT_POPS_AND_BANGS_GAIN = 1.0
        const val DEFAULT_SHIFT_SOUNDS_GAIN = 1.0
        const val DEFAULT_TRANSMISSION_GAIN = 1.0
        const val DEFAULT_TURBO_SOUNDS_GAIN = 1.0
        const val MIN_EFFECT_GAIN = 0.5
        const val MIN_TURBO_SOUNDS_GAIN = 0.25
        const val MAX_EFFECT_GAIN = 6.0
    }
}
