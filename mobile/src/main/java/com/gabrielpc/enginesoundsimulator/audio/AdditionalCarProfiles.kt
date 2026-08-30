package com.gabrielpc.enginesoundsimulator.audio

internal fun lamborghiniAventadorSvProfile() = bandProfile(
    id = "lamborghini_aventador_sv_cabin",
    displayName = "Lamborghini Aventador SV",
    assetDirectory = "lamborghini_aventador_sv",
    preview = "car_previews/lamborghini_aventador_sv.jpg",
    outputSampleRate = 48_000,
    idleRpm = 1_000.0,
    maximumRpm = 9_200.0,
    redlineRpm = 8_400.0,
    limiterRpm = 8_500.0,
    upshiftRpm = 8_400.0,
    gears = listOf(3.91, 2.44, 1.81, 1.46, 1.18, 0.97, 0.84),
    upshiftSeconds = 0.080,
    downshiftSeconds = 0.260,
    idle = RootedSample("s133.wav", 1_000.0),
    load = listOf(
        RootedSample("s098.wav", 3_000.0), RootedSample("s039.wav", 3_890.0),
        RootedSample("s119.wav", 5_575.0), RootedSample("s062.wav", 5_900.0),
        RootedSample("s138.wav", 6_310.0), RootedSample("s147.wav", 6_900.0),
        RootedSample("s013.wav", 7_500.0), RootedSample("s006.wav", 8_050.0),
        RootedSample("s127.wav", 8_790.0),
    ),
    coast = listOf(
        RootedSample("s063.wav", 3_350.0), RootedSample("s046.wav", 4_570.0),
        RootedSample("s117.wav", 6_220.0), RootedSample("s048.wav", 6_720.0),
        RootedSample("s118.wav", 7_600.0), RootedSample("s082.wav", 8_850.0),
    ),
    effects = listOf(
        SampleEffectSpec("transmission_loop", SampleEffectControls.transmission, "fx_transmission.wav", SampleEffectTrigger.TRANSMISSION_LOOP, -17.0),
        SampleEffectSpec("shift_up", SampleEffectControls.gearChanges, "fx_shift.wav", SampleEffectTrigger.SHIFT_UP, -8.0),
        SampleEffectSpec("shift_down", SampleEffectControls.gearChanges, "fx_shift.wav", SampleEffectTrigger.SHIFT_DOWN, -10.0),
    ),
)

internal fun nissanSkylineR34Profile() = bandProfile(
    id = "nissan_skyline_r34_cabin",
    displayName = "Nissan Skyline GT-R R34",
    assetDirectory = "nissan_skyline_r34",
    preview = "car_previews/nissan_skyline_r34.jpg",
    outputSampleRate = 44_100,
    idleRpm = 950.0,
    maximumRpm = 8_500.0,
    redlineRpm = 8_000.0,
    limiterRpm = 8_200.0,
    upshiftRpm = 7_900.0,
    gears = listOf(3.827, 2.360, 1.685, 1.312, 1.000, 0.793),
    upshiftSeconds = 0.095,
    downshiftSeconds = 0.220,
    idle = RootedSample("rb26_4_ex_idle.wav", 950.0, 2.0),
    load = listOf(
        RootedSample("rb26_2_in_on_verylow.wav", 1_650.0),
        RootedSample("rb26_in_2_onverylow.wav", 2_000.0),
        RootedSample("rb26_2_in_on_verylow2.wav", 2_400.0),
        RootedSample("rb26_2_in_on_low3.wav", 3_000.0),
        RootedSample("rb26_in_2_onlow.wav", 3_400.0),
        RootedSample("rb26_in_2_onmid.wav", 4_300.0),
        RootedSample("rb26_2_in_on_mid3.wav", 5_000.0),
        RootedSample("rb26_in_2_onmid2.wav", 5_400.0),
        RootedSample("rb26_in_2_onhigh.wav", 6_300.0),
        RootedSample("rb26_in_on_high2.wav", 6_900.0),
        RootedSample("rb26_in_2_onhigh2.wav", 7_300.0),
        RootedSample("rb26_in_on_veryhigh.wav", 7_900.0),
    ),
    coast = listOf(
        RootedSample("rb26_4_ex_off_verylow.wav", 1_400.0),
        RootedSample("rb26_ex_5_offverylow.wav", 2_200.0),
        RootedSample("rb26_ex_5_offlow.wav", 3_800.0),
        RootedSample("rb26_ex_5_offmid.wav", 5_800.0),
    ),
    textures = listOf(
        RootedSample("sin5.wav", 4_500.0, -4.0),
    ),
    limiter = RootedSample("rb26_3_revlim_EQ.wav", 8_000.0, 1.0),
    effects = listOf(
        SampleEffectSpec(
            id = "turbo_loop",
            control = SampleEffectControls.turbo,
            assetName = "s1_turbo.wav",
            trigger = SampleEffectTrigger.TURBO_LOOP,
            baseGainDb = -5.5,
        ),
        SampleEffectSpec(
            id = "turbo_flutter",
            control = SampleEffectControls.turbo,
            assetName = "flutter_4.wav",
            trigger = SampleEffectTrigger.TURBO_FLUTTER,
            baseGainDb = -7.0,
            loopStartSeconds = 5.42,
            loopEndSeconds = 6.55,
        ),
        SampleEffectSpec(
            id = "turbo_dump",
            control = SampleEffectControls.turbo,
            assetName = "rb26_bf1.wav",
            trigger = SampleEffectTrigger.TURBO_DUMP,
            baseGainDb = -5.0,
            minimumRpm = 1_800.0,
            variantAssetNames = listOf("rb26_bf2.wav"),
        ),
        SampleEffectSpec(
            id = "shift_up",
            control = SampleEffectControls.gearChanges,
            assetName = "gearup.wav",
            trigger = SampleEffectTrigger.SHIFT_UP,
            baseGainDb = -6.0,
            variantAssetNames = listOf("gearupEXT.wav"),
        ),
        SampleEffectSpec(
            id = "shift_down",
            control = SampleEffectControls.gearChanges,
            assetName = "geardnEXT.wav",
            trigger = SampleEffectTrigger.SHIFT_DOWN,
            baseGainDb = -8.0,
            variantAssetNames = listOf("missgear.wav"),
        ),
        SampleEffectSpec(
            id = "rb26_overrun",
            control = SampleEffectControls.exhaustOverrun,
            assetName = "RB26DET_pop_1.wav",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            baseGainDb = -8.0,
            minimumRpm = 3_800.0,
            variantAssetNames = listOf(
                "RB26DET_pop_2.wav",
                "RB26DET_pop_3.wav",
                "rb26_pop1.wav",
                "rb26_pop2.wav",
                "s1_pop.wav",
            ),
        ),
    ),
)

private data class RootedSample(val asset: String, val rpm: Double, val gainDb: Double = 0.0)

private fun bandProfile(
    id: String,
    displayName: String,
    assetDirectory: String,
    preview: String,
    outputSampleRate: Int,
    idleRpm: Double,
    maximumRpm: Double,
    redlineRpm: Double,
    limiterRpm: Double,
    upshiftRpm: Double,
    gears: List<Double>,
    upshiftSeconds: Double,
    downshiftSeconds: Double,
    idle: RootedSample,
    load: List<RootedSample>,
    coast: List<RootedSample>,
    textures: List<RootedSample> = emptyList(),
    limiter: RootedSample? = null,
    effects: List<SampleEffectSpec> = emptyList(),
    bandGainDb: Double = -5.0,
    loadThrottleCurve: AutomationCurve = dbCurve(0.0 to -36.0, 0.15 to -15.0, 0.45 to -5.0, 1.0 to 0.0),
    coastThrottleCurve: AutomationCurve = dbCurve(0.0 to 0.0, 0.25 to -5.0, 0.60 to -20.0, 1.0 to -40.0),
): EngineSampleProfile {
    val textureCurve = dbCurve(0.0 to -7.0, 1.0 to -3.0)
    val layers = mutableListOf<SampleLayerSpec>()
    layers += SampleLayerSpec(
        id = "idle", assetName = idle.asset, role = SampleLayerRole.IDLE,
        startRpm = 0.0, endRpm = (idleRpm * 2.2).coerceAtMost(maximumRpm),
        autopitchRootRpm = idle.rpm, baseGainDb = idle.gainDb - 4.0,
        throttleGainDb = dbCurve(0.0 to 0.0, 1.0 to -10.0),
        rpmAmplitudeCurves = listOf(ampCurve(idleRpm to 1.0, idleRpm * 2.2 to 0.0)),
    )
    layers += bandLayers("load", SampleLayerRole.LOAD, load, idleRpm, maximumRpm, loadThrottleCurve, bandGainDb)
    layers += bandLayers("coast", SampleLayerRole.COAST, coast, idleRpm, maximumRpm, coastThrottleCurve, bandGainDb)
    layers += bandLayers("texture", SampleLayerRole.TEXTURE, textures, idleRpm, maximumRpm, textureCurve, bandGainDb)
    if (limiter != null) {
        layers += SampleLayerSpec(
            id = "limiter",
            assetName = limiter.asset,
            role = SampleLayerRole.LIMITER,
            startRpm = (limiter.rpm * 0.90).coerceAtLeast(idleRpm),
            endRpm = maximumRpm,
            autopitchRootRpm = limiter.rpm,
            baseGainDb = limiter.gainDb - 3.0,
            throttleGainDb = loadThrottleCurve,
            rpmAmplitudeCurves = listOf(
                ampCurve((limiter.rpm * 0.90) to 0.0, limiter.rpm to 1.0),
            ),
        )
    }
    return EngineSampleProfile(
        id = id, displayName = displayName, assetDirectory = assetDirectory,
        previewAssetName = preview, outputSampleRate = outputSampleRate,
        minimumRpm = 0.0, maximumRpm = maximumRpm, idleRpm = idleRpm,
        redlineRpm = redlineRpm, limiterRpm = limiterRpm, upshiftRpm = upshiftRpm,
        gearRatios = gears,
        upshiftDurationSeconds = upshiftSeconds, downshiftDurationSeconds = downshiftSeconds,
        layers = layers, effects = effects, throttleOutputGainDb = dbCurve(0.0 to 0.0, 1.0 to 0.0),
    )
}

private fun bandLayers(
    prefix: String,
    role: SampleLayerRole,
    samples: List<RootedSample>,
    minimum: Double,
    maximum: Double,
    throttleCurve: AutomationCurve,
    bandGainDb: Double,
): List<SampleLayerSpec> = samples.mapIndexed { index, sample ->
    val left = if (index == 0) minimum else (samples[index - 1].rpm + sample.rpm) / 2.0
    val right = if (index == samples.lastIndex) maximum else (sample.rpm + samples[index + 1].rpm) / 2.0
    val fade = ((right - left) * 0.55).coerceAtLeast(220.0)
    SampleLayerSpec(
        id = "${prefix}_${index + 1}", assetName = sample.asset, role = role,
        startRpm = (left - fade).coerceAtLeast(0.0), endRpm = (right + fade).coerceAtMost(maximum),
        autopitchRootRpm = sample.rpm, baseGainDb = sample.gainDb + bandGainDb,
        throttleGainDb = throttleCurve,
        rpmAmplitudeCurves = listOf(
            AutomationCurve(listOf(CurvePoint((left - fade).coerceAtLeast(0.0), 0.0), CurvePoint(left, 1.0))),
            AutomationCurve(listOf(CurvePoint(right, 1.0), CurvePoint((right + fade).coerceAtMost(maximum), 0.0))),
        ),
    )
}

private fun ampCurve(vararg points: Pair<Double, Double>) =
    AutomationCurve(points.map { CurvePoint(it.first, it.second) })

private fun dbCurve(vararg points: Pair<Double, Double>) =
    AutomationCurve(points.map { CurvePoint(it.first, it.second) })
