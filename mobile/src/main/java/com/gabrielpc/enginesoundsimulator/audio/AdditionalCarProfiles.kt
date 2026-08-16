package com.gabrielpc.enginesoundsimulator.audio

/** Profiles reconstructed from each mod's continuous interior engine event. */
internal fun ferrariF430Gt2Profile() = bandProfile(
    id = "ferrari_f430_gt2_cabin",
    displayName = "Ferrari F430 GT2",
    assetDirectory = "ferrari_f430_gt2",
    preview = "car_previews/ferrari_f430_gt2.jpg",
    outputSampleRate = 44_100,
    idleRpm = 1_900.0,
    maximumRpm = 8_600.0,
    redlineRpm = 8_200.0,
    limiterRpm = 8_300.0,
    upshiftRpm = 8_200.0,
    downshiftRpm = 5_900.0,
    finalDrive = 3.50,
    gears = listOf(3.100, 2.250, 1.765, 1.421, 1.211, 1.066),
    upshiftSeconds = 0.100,
    downshiftSeconds = 0.150,
    idle = RootedSample("s024.wav", 1_900.0),
    load = listOf(
        RootedSample("s048.wav", 2_100.0), RootedSample("s029.wav", 3_200.0),
        RootedSample("s007.wav", 4_700.0), RootedSample("s040.wav", 6_300.0),
        RootedSample("s034.wav", 8_000.0), RootedSample("s010.wav", 8_300.0),
    ),
    coast = listOf(
        RootedSample("s011.wav", 2_400.0), RootedSample("s059.wav", 4_900.0),
        RootedSample("s047.wav", 7_500.0),
    ),
)

internal fun bmwM8CoupeProfile() = bandProfile(
    id = "bmw_m8_coupe_cabin",
    displayName = "BMW M8 Coupé",
    assetDirectory = "bmw_m8_coupe",
    preview = "car_previews/bmw_m8_coupe.jpg",
    outputSampleRate = 44_100,
    idleRpm = 1_029.0,
    maximumRpm = 7_500.0,
    redlineRpm = 7_000.0,
    limiterRpm = 7_200.0,
    upshiftRpm = 7_000.0,
    downshiftRpm = 4_900.0,
    finalDrive = 3.15,
    gears = listOf(5.00, 3.20, 2.14, 1.72, 1.31, 1.00, 0.82, 0.64),
    upshiftSeconds = 0.090,
    downshiftSeconds = 0.170,
    idle = RootedSample("s048.wav", 1_029.0, -4.5),
    load = listOf(
        RootedSample("s002.wav", 3_754.0, 2.5), RootedSample("s057.wav", 5_972.0, -1.5),
        RootedSample("s036.wav", 6_338.0), RootedSample("s037.wav", 6_661.0),
        RootedSample("s003.wav", 7_065.0, -0.5), RootedSample("s024.wav", 7_212.0, 1.5),
    ),
    coast = listOf(
        RootedSample("s010.wav", 4_682.0), RootedSample("s028.wav", 6_296.0),
        RootedSample("s015.wav", 6_964.0, -4.0), RootedSample("s044.wav", 7_136.0, -2.5),
    ),
    textures = listOf(RootedSample("s025.wav", 6_823.0, -6.0)),
)

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
    downshiftRpm = 5_800.0,
    finalDrive = 3.54,
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
)

internal fun ferrari812NlargoProfile() = bandProfile(
    id = "ferrari_812_nlargo_cabin",
    displayName = "Ferrari 812 N-Largo",
    assetDirectory = "ferrari_812_nlargo",
    preview = "car_previews/ferrari_812_nlargo.jpg",
    outputSampleRate = 48_000,
    idleRpm = 720.0,
    maximumRpm = 9_200.0,
    redlineRpm = 8_700.0,
    limiterRpm = 8_900.0,
    upshiftRpm = 8_700.0,
    downshiftRpm = 6_100.0,
    finalDrive = 4.38,
    gears = listOf(3.40, 2.19, 1.63, 1.29, 1.03, 0.84, 0.69),
    upshiftSeconds = 0.080,
    downshiftSeconds = 0.180,
    idle = RootedSample("s207.wav", 720.0),
    load = listOf(
        RootedSample("s138.wav", 1_620.0), RootedSample("s137.wav", 2_440.0),
        RootedSample("s208.wav", 3_100.0), RootedSample("s161.wav", 3_520.0),
        RootedSample("s182.wav", 4_035.0), RootedSample("s027.wav", 4_625.0),
        RootedSample("s035.wav", 5_000.0), RootedSample("s021.wav", 5_525.0),
        RootedSample("s075.wav", 5_980.0), RootedSample("s040.wav", 6_510.0),
        RootedSample("s131.wav", 7_020.0), RootedSample("s078.wav", 7_600.0),
        RootedSample("s096.wav", 8_104.0), RootedSample("s134.wav", 8_560.0),
        RootedSample("s064.wav", 8_900.0),
    ),
    // This bank has stripped stream names. Keeping the same continuous voices quietly present
    // at lift-off is safer than guessing load/coast roles that the source metadata does not expose.
    coast = emptyList(),
    neutralBlend = true,
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
    downshiftRpm: Double,
    finalDrive: Double,
    gears: List<Double>,
    upshiftSeconds: Double,
    downshiftSeconds: Double,
    idle: RootedSample,
    load: List<RootedSample>,
    coast: List<RootedSample>,
    textures: List<RootedSample> = emptyList(),
    neutralBlend: Boolean = false,
): EngineSampleProfile {
    val loadCurve = if (neutralBlend) dbCurve(0.0 to -5.0, 0.35 to -2.5, 1.0 to 0.0)
        else dbCurve(0.0 to -36.0, 0.15 to -15.0, 0.45 to -5.0, 1.0 to 0.0)
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
        downshiftRpm = downshiftRpm, finalDrive = finalDrive, gearRatios = gears,
        upshiftDurationSeconds = upshiftSeconds, downshiftDurationSeconds = downshiftSeconds,
        layers = layers, throttleOutputGainDb = dbCurve(0.0 to 0.0, 1.0 to 0.0),
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
