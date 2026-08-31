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
    // Freshly decoded from the bank's interior event. The authoring roots below come from its
    // recovered FMOD auto-pitch references rather than inferred filenames or nearest bands.
    idle = RootedSample("aventadorintidle.wav", 1_000.0),
    load = listOf(
        RootedSample("aventadorintaccf2825.wav", 3_000.0),
        RootedSample("aventadorintaccf3685.wav", 3_890.0),
        RootedSample("aventadorintacc5250.wav", 5_575.0),
        RootedSample("aventadorintacc5600.wav", 5_900.0),
        RootedSample("aventadorintacc6000.wav", 6_310.0),
        RootedSample("aventadorintacc6501.wav", 6_900.0),
        RootedSample("aventadorintacc7103.wav", 7_500.0),
        RootedSample("aventadorintacc7592.wav", 8_050.0),
        RootedSample("aventadorintacc8294.wav", 8_790.0),
    ),
    coast = listOf(
        RootedSample("aventadorintoff3165.wav", 3_350.0),
        RootedSample("aventadorintoff4309.wav", 4_570.0),
        RootedSample("aventadorintoff5853.wav", 6_220.0),
        RootedSample("aventadorintoff6300.wav", 6_720.0),
        RootedSample("aventadorintoff7200.wav", 7_600.0),
        RootedSample("aventadorintoff8373.wav", 8_850.0),
    ),
    effects = listOf(
        SampleEffectSpec("transmission_loop", SampleEffectControls.transmission, "transmission.wav", SampleEffectTrigger.TRANSMISSION_LOOP, -17.0),
        SampleEffectSpec("shift_up", SampleEffectControls.gearChanges, "GEAR_CHANGING_CABIN.wav", SampleEffectTrigger.SHIFT_UP, -8.0),
        SampleEffectSpec("shift_down", SampleEffectControls.gearChanges, "GEAR_CHANGING_CABIN.wav", SampleEffectTrigger.SHIFT_DOWN, -10.0),
    ),
    exteriorProgram = aventadorExteriorProgram(
        effects = listOf(
            SampleEffectSpec("transmission_loop", SampleEffectControls.transmission, "transmission.wav", SampleEffectTrigger.TRANSMISSION_LOOP, -17.0),
            SampleEffectSpec("shift_up", SampleEffectControls.gearChanges, "GEAR_CHANGING_CABIN.wav", SampleEffectTrigger.SHIFT_UP, -8.0),
            SampleEffectSpec("shift_down", SampleEffectControls.gearChanges, "GEAR_CHANGING_CABIN.wav", SampleEffectTrigger.SHIFT_DOWN, -10.0),
        ),
    ),
)

internal fun nissanSkylineR34Profile() = EngineSampleProfile(
    id = "nissan_skyline_r34_cabin",
    displayName = "Nissan Skyline GT-R R34",
    assetDirectory = "nissan_skyline_r34",
    previewAssetName = "car_previews/nissan_skyline_r34.jpg",
    outputSampleRate = 44_100,
    minimumRpm = 0.0,
    maximumRpm = 8_500.0,
    idleRpm = 950.0,
    redlineRpm = 8_000.0,
    limiterRpm = 8_200.0,
    upshiftRpm = 7_900.0,
    gearRatios = listOf(3.827, 2.360, 1.685, 1.312, 1.000, 0.793),
    upshiftDurationSeconds = 0.095,
    downshiftDurationSeconds = 0.220,
    cabinProgram = EngineSampleProgram(
        layers = skylineFmodEngineLayers(),
        effects = skylineEffects(),
        supportsLoadOnlyProgram = false,
    ),
    exteriorProgram = skylineExteriorProgram(skylineEffects()),
)

private fun skylineEffects() = listOf(
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
            variantAssetNames = listOf("RB26DET_pop_2.wav", "RB26DET_pop_3.wav"),
        ),
)

/**
 * The Skyline preserves the recovered `engine_int` topology rather than using the generic
 * nearest-RPM-band approximation. Its lift blend keeps the interior engine character present
 * through acceleration and deceleration without exterior coast layers.
 */
private fun skylineFmodEngineLayers(): List<SampleLayerSpec> {
    val interiorLoadThrottle = fmodCurve(
        CurvePoint(0.20, -8.0, shape = -0.5943323),
        CurvePoint(0.70, -8.0),
    )
    val interiorAccentThrottle = fmodCurve(
        CurvePoint(0.20, -10.0, shape = -0.5943323),
        CurvePoint(0.70, -6.0),
    )
    val exteriorIdleThrottle = fmodCurve(
        CurvePoint(0.20, -8.0, shape = 0.7671622),
        CurvePoint(0.70, -35.782608),
    )
    val interiorAccentRpmGain = fmodCurve(
        CurvePoint(3_000.0, 2.0),
        CurvePoint(7_500.0, 4.0),
    )
    val interiorLoadRpmGain = fmodCurve(
        CurvePoint(1_000.0, -19.956522, shape = -0.1609896),
        CurvePoint(2_000.0, -1.304348),
        CurvePoint(2_800.0, 4.8),
        CurvePoint(5_500.0, 4.8),
    )
    val sineRpmGain = fmodCurve(
        CurvePoint(2_000.0, -35.217392, shape = -0.41455066),
        CurvePoint(2_600.0, -4.0),
        CurvePoint(7_300.0, -2.0),
        CurvePoint(8_300.0, 0.0),
    )

    return listOf(
        skylineLayer(
            id = "skyline_idle",
            assetName = "rb26_4_ex_idle.wav",
            role = SampleLayerRole.IDLE,
            startRpm = 0.0,
            endRpm = 2_000.0,
            rootRpm = 1_359.0,
            baseGainDb = -6.5,
            throttleGainDb = exteriorIdleThrottle,
            rpmAmplitudeCurves = listOf(fmodCurve(
                CurvePoint(1_400.0, 1.0, shape = 0.25471893),
                CurvePoint(2_000.0, 0.0),
            )),
        ),
        skylineLayer(
            id = "skyline_load_very_low_accent",
            assetName = "rb26_2_in_on_verylow2.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 0.0,
            endRpm = 3_000.0,
            rootRpm = 2_580.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(fmodCurve(
                CurvePoint(2_200.0, 1.0, shape = 0.25471893),
                CurvePoint(3_000.0, 0.0),
            )),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_low_accent",
            assetName = "rb26_2_in_on_verylow.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 2_200.0,
            endRpm = 4_000.0,
            rootRpm = 3_065.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(2_200.0, 0.0, shape = -0.2547189),
                    CurvePoint(3_000.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(3_500.0, 1.0, shape = 0.25471893),
                    CurvePoint(4_000.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_mid_accent",
            assetName = "rb26_2_in_on_low3.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 3_500.0,
            endRpm = 5_600.0,
            rootRpm = 3_820.0,
            baseGainDb = 3.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(3_500.0, 0.0, shape = -0.2547189),
                    CurvePoint(4_000.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(4_000.0, 1.0, shape = 0.25471893),
                    CurvePoint(5_600.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_high_mid_accent",
            assetName = "rb26_2_in_on_mid3.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 4_000.0,
            endRpm = 7_000.0,
            rootRpm = 5_430.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(4_000.0, 0.0, shape = -0.2547189),
                    CurvePoint(5_600.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(6_200.0, 1.0, shape = 0.25471893),
                    CurvePoint(7_000.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_high_accent",
            assetName = "rb26_in_on_high2.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 6_200.0,
            endRpm = 7_800.0,
            rootRpm = 6_600.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(6_200.0, 0.0, shape = -0.2547189),
                    CurvePoint(7_000.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(7_300.0, 1.0, shape = 0.25471893),
                    CurvePoint(7_800.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_very_high_accent",
            assetName = "rb26_in_on_veryhigh.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 7_300.0,
            endRpm = 7_950.0,
            rootRpm = 7_390.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(7_300.0, 0.0, shape = -0.2547189),
                    CurvePoint(7_800.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(7_870.0, 1.0, shape = 0.25471893),
                    CurvePoint(7_950.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_very_low",
            assetName = "rb26_in_2_onverylow.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 0.0,
            endRpm = 4_200.0,
            rootRpm = 2_600.0,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(fmodCurve(
                CurvePoint(2_800.0, 1.0, shape = 0.25471893),
                CurvePoint(4_200.0, 0.0),
            )),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_low",
            assetName = "rb26_in_2_onlow.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 2_800.0,
            endRpm = 4_900.0,
            rootRpm = 4_160.0,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(2_800.0, 0.0, shape = -0.2547189),
                    CurvePoint(4_200.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(4_300.0, 1.0, shape = 0.25471893),
                    CurvePoint(4_900.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_mid",
            assetName = "rb26_in_2_onmid.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 4_300.0,
            endRpm = 5_600.0,
            rootRpm = 4_780.0,
            baseGainDb = 1.0,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(4_300.0, 0.0, shape = -0.2547189),
                    CurvePoint(4_900.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(5_000.0, 1.0, shape = 0.25471893),
                    CurvePoint(5_600.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_high_mid",
            assetName = "rb26_in_2_onmid2.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 5_000.0,
            endRpm = 6_400.0,
            rootRpm = 5_680.0,
            baseGainDb = 1.0,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(5_000.0, 0.0, shape = -0.2547189),
                    CurvePoint(5_600.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(5_800.0, 1.0, shape = 0.25471893),
                    CurvePoint(6_400.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_high",
            assetName = "rb26_in_2_onhigh.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 5_800.0,
            endRpm = 7_500.0,
            rootRpm = 6_580.0,
            baseGainDb = 1.0,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(
                fmodCurve(
                    CurvePoint(5_800.0, 0.0, shape = -0.2547189),
                    CurvePoint(6_400.0, 1.0),
                ),
                fmodCurve(
                    CurvePoint(6_900.0, 1.0, shape = 0.25471893),
                    CurvePoint(7_500.0, 0.0),
                ),
            ),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_load_very_high",
            assetName = "rb26_in_2_onhigh2.wav",
            role = SampleLayerRole.LOAD,
            startRpm = 6_900.0,
            endRpm = 20_000.0,
            rootRpm = 7_200.0,
            baseGainDb = 2.5,
            throttleGainDb = interiorLoadThrottle,
            rpmAmplitudeCurves = listOf(fmodCurve(
                CurvePoint(6_900.0, 0.0, shape = -0.2547189),
                CurvePoint(7_500.0, 1.0),
            )),
            rpmGainDbCurves = listOf(interiorLoadRpmGain),
        ),
        skylineLayer(
            id = "skyline_limiter",
            assetName = "rb26_3_revlim_EQ.wav",
            role = SampleLayerRole.LIMITER,
            startRpm = 7_870.0,
            endRpm = 20_000.0,
            rootRpm = 7_400.0,
            baseGainDb = 2.0,
            throttleGainDb = interiorAccentThrottle,
            rpmAmplitudeCurves = listOf(fmodCurve(
                CurvePoint(7_870.0, 0.0, shape = 0.5, interpolationType = 1),
                CurvePoint(7_950.0, 1.0),
            )),
            rpmGainDbCurves = listOf(interiorAccentRpmGain),
        ),
        skylineLayer(
            id = "skyline_texture_low",
            assetName = "sin5.wav",
            role = SampleLayerRole.TEXTURE,
            startRpm = 2_000.0,
            endRpm = 8_100.0,
            rootRpm = 3_550.0,
            baseGainDb = -8.0,
            throttleGainDb = fmodCurve(
                CurvePoint(0.10, -4.0, shape = 0.3858271),
                CurvePoint(0.20, -7.521738, shape = -0.38582787),
                CurvePoint(0.30, -10.0, shape = 0.36938456),
                CurvePoint(0.40, -6.3913035, shape = -0.2540752),
                CurvePoint(0.50, 0.0),
                CurvePoint(0.70, 0.0),
            ),
            rpmGainDbCurves = listOf(sineRpmGain),
        ),
        skylineLayer(
            id = "skyline_texture_high",
            assetName = "sin5.wav",
            role = SampleLayerRole.TEXTURE,
            startRpm = 2_000.0,
            endRpm = 8_100.0,
            rootRpm = 7_100.0,
            baseGainDb = -12.0,
            throttleGainDb = fmodCurve(
                CurvePoint(0.10, 2.0, shape = 0.3858271),
                CurvePoint(0.20, -3.565217, shape = -0.38582787),
                CurvePoint(0.30, -12.0, shape = 0.15823701),
                CurvePoint(0.40, -4.1304345, shape = -0.38582703),
                CurvePoint(0.50, 0.0),
                CurvePoint(0.70, 0.0),
            ),
            rpmGainDbCurves = listOf(sineRpmGain),
        ),
        skylineLayer(
            id = "skyline_texture_top",
            assetName = "sin5.wav",
            role = SampleLayerRole.TEXTURE,
            startRpm = 2_000.0,
            endRpm = 20_000.0,
            rootRpm = 10_638.0,
            baseGainDb = -13.0,
            throttleGainDb = fmodCurve(
                CurvePoint(0.10, -4.0, shape = 0.3858271),
                CurvePoint(0.20, -6.3913035, shape = -0.38582787),
                CurvePoint(0.30, -7.0, shape = 0.15823701),
                CurvePoint(0.40, -4.695652, shape = -0.11886508),
                CurvePoint(0.50, 0.0),
                CurvePoint(0.70, 0.0),
            ),
            rpmGainDbCurves = listOf(sineRpmGain),
        ),
    )
}

private fun skylineLayer(
    id: String,
    assetName: String,
    role: SampleLayerRole,
    startRpm: Double,
    endRpm: Double,
    rootRpm: Double,
    baseGainDb: Double = 0.0,
    throttleGainDb: AutomationCurve? = null,
    rpmAmplitudeCurves: List<AutomationCurve> = emptyList(),
    rpmGainDbCurves: List<AutomationCurve> = emptyList(),
) = SampleLayerSpec(
    id = id,
    assetName = assetName,
    role = role,
    startRpm = startRpm,
    endRpm = endRpm,
    autopitchRootRpm = rootRpm,
    baseGainDb = baseGainDb,
    applyIdleGainBoost = false,
    throttleGainDb = throttleGainDb,
    rpmAmplitudeCurves = rpmAmplitudeCurves,
    rpmGainDbCurves = rpmGainDbCurves,
)

private fun fmodCurve(vararg points: CurvePoint) = AutomationCurve(points.toList())

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
    exteriorProgram: EngineSampleProgram? = null,
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
        cabinProgram = EngineSampleProgram(
            layers = layers,
            effects = effects,
            throttleOutputGainDb = dbCurve(0.0 to 0.0, 1.0 to 0.0),
        ),
        exteriorProgram = exteriorProgram,
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
