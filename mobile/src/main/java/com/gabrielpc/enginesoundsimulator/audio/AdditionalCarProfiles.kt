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
        SampleEffectSpec("overrun", SampleEffectControls.exhaustOverrun, "fx_overrun.wav", SampleEffectTrigger.THROTTLE_LIFT, -11.0, 2_800.0),
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
    effects: List<SampleEffectSpec> = emptyList(),
): EngineSampleProfile {
    val loadCurve = dbCurve(0.0 to -36.0, 0.15 to -15.0, 0.45 to -5.0, 1.0 to 0.0)
    val coastCurve = dbCurve(0.0 to 0.0, 0.25 to -5.0, 0.60 to -20.0, 1.0 to -40.0)
    val textureCurve = dbCurve(0.0 to -7.0, 1.0 to -3.0)
    val layers = mutableListOf<SampleLayerSpec>()
    layers += SampleLayerSpec(
        id = "idle", assetName = idle.asset, role = SampleLayerRole.IDLE,
        startRpm = 0.0, endRpm = (idleRpm * 2.2).coerceAtMost(maximumRpm),
        autopitchRootRpm = idle.rpm, baseGainDb = idle.gainDb - 4.0,
        throttleGainDb = dbCurve(0.0 to 0.0, 1.0 to -10.0),
        rpmAmplitudeCurves = listOf(ampCurve(idleRpm to 1.0, idleRpm * 2.2 to 0.0)),
    )
    layers += bandLayers("load", SampleLayerRole.LOAD, load, idleRpm, maximumRpm, loadCurve)
    layers += bandLayers("coast", SampleLayerRole.COAST, coast, idleRpm, maximumRpm, coastCurve)
    layers += bandLayers("texture", SampleLayerRole.TEXTURE, textures, idleRpm, maximumRpm, textureCurve)
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
): List<SampleLayerSpec> = samples.mapIndexed { index, sample ->
    val left = if (index == 0) minimum else (samples[index - 1].rpm + sample.rpm) / 2.0
    val right = if (index == samples.lastIndex) maximum else (sample.rpm + samples[index + 1].rpm) / 2.0
    val fade = ((right - left) * 0.55).coerceAtLeast(220.0)
    SampleLayerSpec(
        id = "${prefix}_${index + 1}", assetName = sample.asset, role = role,
        startRpm = (left - fade).coerceAtLeast(0.0), endRpm = (right + fade).coerceAtMost(maximum),
        autopitchRootRpm = sample.rpm, baseGainDb = sample.gainDb - 5.0,
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
