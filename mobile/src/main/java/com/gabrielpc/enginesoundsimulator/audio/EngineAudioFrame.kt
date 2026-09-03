package com.gabrielpc.enginesoundsimulator.audio

/** Realtime controls consumed by the native FMOD Studio runtime. */
data class EngineAudioFrame(
    val rpm: Double = 0.0,
    val throttle: Double = 0.0,
    val gear: Int = 0,
    val isShifting: Boolean = false,
    val shiftProgress: Double = 0.0,
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
)
