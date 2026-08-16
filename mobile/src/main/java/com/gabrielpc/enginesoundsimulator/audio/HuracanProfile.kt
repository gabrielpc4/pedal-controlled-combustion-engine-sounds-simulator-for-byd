package com.gabrielpc.enginesoundsimulator.audio

internal fun huracanTrofeoEvo2Profile(): EngineSampleProfile {
    val primaryLoad = dbCurve(0.0 to -42.0, 0.3715604 to -7.0, 0.84485775 to 0.0, 1.0 to 0.0)
    val highLoad = dbCurve(0.0 to -42.0, 0.077112384 to -24.625217, 0.16406891 to -13.84087, 1.0730107 to 0.0)
    val noiseLoad = dbCurve(0.021328958 to -39.839832, 0.19852336 to -10.845219, 1.2466724 to -1.1)
    val textureLoad = dbCurve(0.021328958 to -39.839832, 0.707137 to -16.23739, 1.2466724 to -1.1)
    val sineLoad = dbCurve(0.012417219 to -9.0, 0.990894 to 0.0)
    val coast = dbCurve(0.24935648 to 0.0, 0.5758819 to -23.426956, 0.8728466 to -42.0)
    // Keep a restrained amount of the clean C1/C2 harmonic body under load. The source event
    // originally closes these loops almost completely, leaving the noisier load layers exposed.
    val tonalCoast = dbCurve(0.24935648 to 0.0, 0.5758819 to -10.0, 0.8728466 to -9.0)
    val coastNoise = dbCurve(0.01640689 to -4.0, 0.12797375 to -38.1)
    val idle = dbCurve(0.0024834438 to -14.5, 1.0 to -10.5)
    val throttleOutputGain = dbCurve(0.0 to 0.0, 0.25 to 0.3, 0.55 to 0.55, 1.0 to 0.75)

    val l1Gain = dbCurve(6554.5728 to -42.0, 6953.308 to -15.638, 7644.906 to 0.0, 8114.469 to 0.0, 8133.7495 to -12.643)
    val l2Gain = dbCurve(5030.8154 to -42.0, 5447.599 to -17.436, 6231.4775 to 0.0, 6953.0293 to 0.0, 7645.216 to -18.035, 7931.093 to -42.0)
    val l3Gain = dbCurve(3734.3232 to -42.0, 4287.5 to -17.196, 5360.389 to 0.0, 5447.599 to 0.0, 6222.351 to -16.0, 6700.993 to -42.0)
    val l4hGain = dbCurve(3297.6084 to -42.0, 3734.2173 to -19.233, 4397.539 to 0.0, 4453.8145 to 0.0, 5338.146 to -18.035, 5953.158 to -42.0)

    fun layer(
        id: String,
        asset: String,
        role: SampleLayerRole,
        start: Double,
        end: Double,
        root: Double? = null,
        pitch: Double = 0.25,
        gain: Double = 0.0,
        throttleCurve: AutomationCurve? = null,
        amplitude: List<AutomationCurve> = emptyList(),
        rpmGain: List<AutomationCurve> = emptyList(),
    ) = SampleLayerSpec(id, asset, role, start, end, root, pitch, gain, throttleCurve, amplitude, rpmGain)

    val layers = listOf(
        // The decoded exterior event explicitly identifies this as its idle stream. Prefer it
        // at rest; all moving layers stay on the recovered cabin event.
        layer("idle_low", "s013_ex_idle.wav", SampleLayerRole.IDLE, 0.0, 2352.0, 1254.4, throttleCurve = idle,
            amplitude = listOf(ampCurve(1372.0 to 1.0, 2352.0 to 0.0))),
        layer("c3", "s134_hur_c3.wav", SampleLayerRole.COAST, 3822.0, 6294.06, 4821.6, throttleCurve = coast,
            amplitude = listOf(ampCurve(3822.0 to 0.0, 4900.0 to 1.0), ampCurve(5235.374 to 1.0, 6294.06 to 0.0))),
        layer("engine_noise_7", "s077_eng_noise7.wav", SampleLayerRole.TEXTURE, 1666.0, 9800.0, pitch = 2.5, gain = -0.5,
            amplitude = listOf(ampCurve(1666.0 to 0.0, 4366.0 to 1.0), ampCurve(9600.0 to 1.0, 9800.0 to 0.0))),
        layer("engine_noise_9_high", "s049_eng_noise9_high.wav", SampleLayerRole.LOAD, 6958.0, 9800.0, pitch = 0.0,
            throttleCurve = textureLoad, amplitude = listOf(ampCurve(6958.0 to 0.0, 7658.0 to 1.0))),
        layer("l4_high", "s117_hur_l4h.wav", SampleLayerRole.LOAD, 1438.8182, 7252.0, 4233.6, throttleCurve = highLoad,
            amplitude = listOf(ampCurve(1438.8182 to 0.0, 2638.818 to 1.0), ampCurve(6177.1655 to 1.0, 7252.0 to 0.0)), rpmGain = listOf(l4hGain)),
        layer("l1", "s113_hur_l1.wav", SampleLayerRole.LOAD, 1078.0, 9800.0, 7595.0, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(1078.0 to 0.0, 1370.3842 to 1.0)), rpmGain = listOf(l1Gain)),
        layer("idle_noise", "s037_hur_idle_noise.wav", SampleLayerRole.TEXTURE, 0.0, 3332.0, pitch = 0.0, gain = -3.9,
            amplitude = listOf(ampCurve(0.0 to 0.0, 1501.2305 to 1.0), ampCurve(1432.0 to 1.0, 3332.0 to 0.0))),
        layer("n1_high", "s010_hur_n1_high.wav", SampleLayerRole.LOAD, 6958.0, 9800.0, pitch = 0.0, throttleCurve = noiseLoad,
            amplitude = listOf(ampCurve(6958.0 to 0.0, 7658.0 to 1.0))),
        layer("l3", "s044_hur_l3.wav", SampleLayerRole.LOAD, 1078.0, 7644.0, 5831.0, throttleCurve = primaryLoad, rpmGain = listOf(l3Gain)),
        layer("l4", "s127_hur_l4.wav", SampleLayerRole.LOAD, 3136.0, 9800.0, 4233.6, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(3136.0 to 0.0, 4336.0 to 1.0), ampCurve(9600.0 to 1.0, 9800.0 to 0.0)),
            rpmGain = listOf(dbCurve(4276.948 to 0.0, 5402.461 to -19.832, 6029.532 to -42.0))),
        layer("limiter", "s073_hur_lim.wav", SampleLayerRole.LIMITER, 1078.0, 9800.0, 7595.0, pitch = 0.0, gain = -2.0, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(1078.0 to 0.0, 1278.0 to 1.0)),
            rpmGain = listOf(dbCurve(8066.714 to -42.0, 8114.0024 to -19.535, 8133.1494 to -1.259))),
        layer("l6", "s139_hur_l6.wav", SampleLayerRole.LOAD, 1438.8182, 3637.8984, 3028.2, gain = -2.0, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(1438.8182 to 0.0, 2370.6365 to 1.0), ampCurve(2744.0 to 1.0, 3637.8982 to 0.0))),
        layer("l3_high", "s038_hur_high_l3.wav", SampleLayerRole.LOAD, 1078.0, 7644.0, 5831.0, throttleCurve = highLoad, rpmGain = listOf(l3Gain)),
        layer("n_up", "s061_hur_n_up.wav", SampleLayerRole.LOAD, 1438.8182, 7644.0, pitch = 0.0, gain = 0.4, throttleCurve = noiseLoad,
            amplitude = listOf(ampCurve(1438.8182 to 0.0, 6670.636 to 1.0), ampCurve(6944.0 to 1.0, 7644.0 to 0.0))),
        layer("l1_high", "s031_hur_high_l1.wav", SampleLayerRole.LOAD, 1078.0, 9800.0, 7595.0, throttleCurve = highLoad,
            amplitude = listOf(ampCurve(1078.0 to 0.0, 1278.0 to 1.0)),
            rpmGain = listOf(dbCurve(6582.7104 to -42.0, 6957.3276 to -15.638, 7643.7515 to 0.0, 8114.469 to 0.0, 8134.196 to -4.255))),
        layer("c4", "s093_hur_c4.wav", SampleLayerRole.COAST, 1438.8182, 4900.0, 4135.6, gain = 1.2, throttleCurve = coast,
            amplitude = listOf(ampCurve(1438.8182 to 0.0, 2738.818 to 1.0), ampCurve(3822.0 to 1.0, 4900.0 to 0.0))),
        layer("l5", "s065_hur_l5.wav", SampleLayerRole.LOAD, 2744.0, 4312.0, 3606.4, gain = -2.0, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(2744.0 to 0.0, 3637.8982 to 1.0), ampCurve(3012.0 to 1.0, 4312.0 to 0.0))),
        layer("n2", "s089_hur_n2.wav", SampleLayerRole.COAST, 1438.8182, 9800.0, pitch = 0.0, gain = 0.5, throttleCurve = coastNoise,
            amplitude = listOf(ampCurve(1438.8182 to 0.0, 6870.636 to 1.0))),
        layer("l2a", "s032_hur_l2a.wav", SampleLayerRole.LOAD, 1078.0, 7644.0, 6125.0, throttleCurve = primaryLoad,
            amplitude = listOf(ampCurve(1078.0 to 0.0, 1461.7448 to 1.0)), rpmGain = listOf(l2Gain)),
        layer("sine", "s126_amrgt3_sine.wav", SampleLayerRole.TEXTURE, 3318.672, 9760.8, 5909.4, gain = -7.8, throttleCurve = sineLoad,
            amplitude = listOf(ampCurve(3318.672 to 0.0, 3918.672 to 1.0))),
        layer("l4_low", "s149_hur_l4l.wav", SampleLayerRole.LOAD, 1666.0, 4421.657, 4233.6, throttleCurve = highLoad,
            amplitude = listOf(ampCurve(1666.0 to 0.0, 3466.0 to 1.0), ampCurve(3621.9033 to 1.0, 4421.657 to 0.0))),
        layer("l2a_high", "s081_hur_high_l2a.wav", SampleLayerRole.LOAD, 1078.0, 7644.0, 6125.0, throttleCurve = highLoad,
            amplitude = listOf(ampCurve(1078.0 to 0.0, 1370.3842 to 1.0)), rpmGain = listOf(l2Gain)),
        layer("c2", "s059_hur_c2.wav", SampleLayerRole.COAST, 5235.374, 7644.0, 6487.6, throttleCurve = tonalCoast,
            amplitude = listOf(ampCurve(5235.374 to 0.0, 6294.06 to 1.0), ampCurve(6117.147 to 1.0, 7644.0 to 0.0))),
        layer("c1", "s039_hur_c1.wav", SampleLayerRole.COAST, 6117.147, 9800.0, 7448.0, throttleCurve = tonalCoast,
            amplitude = listOf(ampCurve(6117.147 to 0.0, 7644.0 to 1.0))),
    )

    return EngineSampleProfile(
        id = "lamborghini_huracan_trofeo_evo2_cabin",
        displayName = "Lamborghini Huracán Super Trofeo EVO2",
        assetDirectory = "lamborghini_huracan_trofeo_evo2",
        previewAssetName = "car_previews/lamborghini_huracan_trofeo_evo2.jpg",
        outputSampleRate = 44_100,
        minimumRpm = 0.0,
        maximumRpm = 10_000.0,
        idleRpm = 1_040.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        upshiftRpm = 8_200.0,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftDurationSeconds = 0.060,
        downshiftDurationSeconds = 0.150,
        layers = layers,
        effects = listOf(
            SampleEffectSpec(
                id = "transmission_loop",
                control = SampleEffectControls.transmission,
                assetName = "fx_transmission.wav",
                trigger = SampleEffectTrigger.TRANSMISSION_LOOP,
                baseGainDb = -17.0,
            ),
            SampleEffectSpec(
                id = "shift_up",
                control = SampleEffectControls.gearChanges,
                assetName = "fx_shift_up.wav",
                trigger = SampleEffectTrigger.SHIFT_UP,
                baseGainDb = -7.0,
            ),
            SampleEffectSpec(
                id = "shift_down",
                control = SampleEffectControls.gearChanges,
                assetName = "fx_shift_down.wav",
                trigger = SampleEffectTrigger.SHIFT_DOWN,
                baseGainDb = -7.0,
            ),
        ),
        throttleOutputGainDb = throttleOutputGain,
    )
}

/**
 * The same source bank's separately decoded engine_ext event. Stream names identify the
 * exterior microphone positions, so this can be selected without guessing perspective.
 */
internal fun huracanTrofeoEvo2ExteriorProfile(): EngineSampleProfile {
    fun layer(
        id: String,
        asset: String,
        role: SampleLayerRole,
        start: Double,
        end: Double,
        root: Double,
        throttleCurve: AutomationCurve? = null,
        gain: Double = -5.0,
        amplitude: List<AutomationCurve> = emptyList(),
    ) = SampleLayerSpec(id, asset, role, start, end, root, 0.0, gain, throttleCurve, amplitude)

    val load = dbCurve(0.0 to -38.0, 0.15 to -15.0, 0.45 to -5.0, 1.0 to 0.0)
    val coast = dbCurve(0.0 to 0.0, 0.25 to -5.0, 0.60 to -20.0, 1.0 to -40.0)
    val idle = dbCurve(0.0 to 0.0, 1.0 to -10.0)
    val layers = listOf(
        layer("ex_idle", "s013_ex_idle.wav", SampleLayerRole.IDLE, 0.0, 2_300.0, 1_254.0, idle, -3.0,
            listOf(ampCurve(1_350.0 to 1.0, 2_300.0 to 0.0))),
        layer("ex_l1", "s025_rear_l1.wav", SampleLayerRole.LOAD, 1_100.0, 3_000.0, 2_000.0, load),
        layer("ex_l2", "s132_front_l2.wav", SampleLayerRole.LOAD, 2_200.0, 4_900.0, 3_800.0, load),
        layer("ex_l3", "s009_ex_l3.wav", SampleLayerRole.LOAD, 3_800.0, 6_500.0, 5_200.0, load),
        layer("ex_l4", "s123_ex_l4.wav", SampleLayerRole.LOAD, 5_300.0, 7_600.0, 6_500.0, load),
        layer("ex_l5", "s131_ex_l5.wav", SampleLayerRole.LOAD, 6_500.0, 8_900.0, 7_600.0, load),
        layer("ex_l6", "s042_ex_l6.wav", SampleLayerRole.LOAD, 7_500.0, 10_000.0, 8_700.0, load),
        layer("ex_c1", "s058_ex_c1e.wav", SampleLayerRole.COAST, 1_800.0, 4_400.0, 3_000.0, coast),
        layer("ex_c2", "s016_ex_c2.wav", SampleLayerRole.COAST, 3_300.0, 6_300.0, 4_800.0, coast),
        layer("ex_c3", "s145_ex_c3.wav", SampleLayerRole.COAST, 5_200.0, 8_000.0, 6_500.0, coast),
        layer("ex_c4", "s138_ex_c4.wav", SampleLayerRole.COAST, 7_000.0, 10_000.0, 8_300.0, coast),
        layer("ex_limiter", "s114_ex_limiter.wav", SampleLayerRole.LIMITER, 7_800.0, 10_000.0, 8_200.0, load, -6.0),
    )
    return EngineSampleProfile(
        id = "lamborghini_huracan_trofeo_evo2_exterior",
        displayName = "Lamborghini Huracán Super Trofeo EVO2",
        assetDirectory = "lamborghini_huracan_trofeo_evo2",
        previewAssetName = "car_previews/lamborghini_huracan_trofeo_evo2.jpg",
        outputSampleRate = 44_100,
        minimumRpm = 0.0,
        maximumRpm = 10_000.0,
        idleRpm = 1_040.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        upshiftRpm = 8_200.0,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftDurationSeconds = 0.060,
        downshiftDurationSeconds = 0.150,
        layers = layers,
        throttleOutputGainDb = dbCurve(0.0 to 0.0, 1.0 to 0.75),
    )
}

private fun ampCurve(vararg points: Pair<Double, Double>) = AutomationCurve(points.map { CurvePoint(it.first, it.second) })
private fun dbCurve(vararg points: Pair<Double, Double>) = AutomationCurve(points.map { CurvePoint(it.first, it.second) })
