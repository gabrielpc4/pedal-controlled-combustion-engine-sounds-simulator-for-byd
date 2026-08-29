package com.gabrielpc.enginesoundsimulator.audio

/** Recorded Alfa 4C FMOD pops, shared across every car when the dashboard toggle is on. */
internal object SharedPopsAndBangs {
    const val ASSET_DIRECTORY = "shared/pops_and_bangs"
    const val EFFECT_ID = "shared_pops_bangs"

    val assetNames = listOf(
        "backfire_1.wav",
        "backfire_2.wav",
        "backfire_3.wav",
        "backfire_4.wav",
    )

    val effectSpec = SampleEffectSpec(
        id = EFFECT_ID,
        control = SampleEffectControls.exhaustOverrun,
        assetName = assetNames.first(),
        trigger = SampleEffectTrigger.THROTTLE_LIFT,
        baseGainDb = 0.0,
        minimumRpm = 2_800.0,
        variantAssetNames = assetNames.drop(1),
    )

    fun assetPath(assetName: String): String = "sample_engine/$ASSET_DIRECTORY/$assetName"
}
