package com.gabrielpc.enginesoundsimulator.audio

/** Huracán Trofeo shift one-shots, shared across every car when the dashboard toggle is on. */
internal object SharedHuracanShiftSounds {
    const val ASSET_DIRECTORY = "shared/huracan_shift_sounds"
    const val SHIFT_UP_ID = "shared_shift_up"
    const val SHIFT_DOWN_ID = "shared_shift_down"

    val assetNames = listOf(
        "fx_shift_up.wav",
        "fx_shift_down.wav",
    )

    val shiftUpSpec = SampleEffectSpec(
        id = SHIFT_UP_ID,
        control = SampleEffectControls.gearChanges,
        assetName = "fx_shift_up.wav",
        trigger = SampleEffectTrigger.SHIFT_UP,
        baseGainDb = -7.0,
    )

    val shiftDownSpec = SampleEffectSpec(
        id = SHIFT_DOWN_ID,
        control = SampleEffectControls.gearChanges,
        assetName = "fx_shift_down.wav",
        trigger = SampleEffectTrigger.SHIFT_DOWN,
        baseGainDb = -7.0,
    )

    fun assetPath(assetName: String): String = "sample_engine/$ASSET_DIRECTORY/$assetName"
}
