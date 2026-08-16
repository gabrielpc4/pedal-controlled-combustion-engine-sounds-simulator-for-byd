package com.gabrielpc.enginesoundsimulator.audio

/**
 * Continuous near-car exterior event recovered from the source bank.
 * Lift/FOT one-shots and unrelated vehicle noises are intentionally excluded.
 */
internal fun huracanTrofeoEvo2ExteriorProfile(): EngineSampleProfile {
    val layers = listOf(
        layer("ex_l1b", "s088_ex_l1b.wav", SampleLayerRole.LOAD, 784, 9800, 7595, 0.25, 0, null, listOf(ampCurve(784 to 0, 1084 to 1)), listOf(dbCurve(6700.588 to -42, 6960.131 to -18.034784, 7639.2134 to 0, 8113.556 to 0, 8133.6865 to -8))),
        layer("ex_h3", "s070_ex_h3.wav", SampleLayerRole.COAST, 784, 7644, 5831, 0.27, 0, dbCurve(0.25745034 to 0, 1 to -42), listOf(ampCurve(784 to 0, 1319.4304 to 1), ampCurve(4900 to 1, 7644 to 0)), listOf(dbCurve(3559.9028 to -42, 3922.4338 to -17.196001, 5360.389 to 0, 5447.599 to 0))),
        layer("front_amb_l2", "s079_front_amb_l2.wav", SampleLayerRole.LOAD, 784, 7644, 6115.2, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.9254967 to 0), listOf(ampCurve(784 to 0, 1098.5696 to 1)), listOf(dbCurve(2264.4246 to -42, 2971.2334 to -18.215343, 6038.398 to 0, 6958.1567 to 0, 7640.6333 to -18.034784, 8012.798 to -42))),
        layer("front_c1_stro", "s056_front_c1_stro.wav", SampleLayerRole.COAST, 6811, 9800, 7448, 0.25, 0, dbCurve(0.03807947 to 0, 0.18874171 to 0, 0.6076159 to -23.97594, 0.83692056 to -42), listOf(ampCurve(6811 to 0, 7661 to 1)), emptyList()),
        layer("ex_c2", "s016_ex_c2.wav", SampleLayerRole.COAST, 4606, 9800, 6487.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.13493377 to 0, 0.5281457 to -29.163836, 0.83692056 to -42), listOf(ampCurve(4606 to 0, 5869.2915 to 1)), listOf(dbCurve(6813.3525 to 1, 7651.788 to -17.633915, 8235.894 to -41))),
        layer("front_amb_c1", "s003_front_amb_c1.wav", SampleLayerRole.COAST, 6811, 9800, 7448, 0.25, 0, dbCurve(0.03807947 to 0, 0.83692056 to -42), listOf(ampCurve(6811 to 0, 7661 to 1)), emptyList()),
        layer("loud_l2", "s125_loud_l2.wav", SampleLayerRole.LOAD, 784, 7644, 6125, 0.25, 0, dbCurve(0.0016556291 to -19.233044, 0.23675497 to -14.440001, 0.8576159 to -2.248952, 1 to -0.5), listOf(ampCurve(784 to 0, 1098.5696 to 1)), listOf(dbCurve(5136.279 to -42, 5453.6836 to -19.215343, 6223.365 to 0, 6958.2397 to 0, 7645.501 to -19.034784, 8008.539 to -42))),
        layer("ex_l6", "s042_ex_l6.wav", SampleLayerRole.LOAD, 1447.3846, 3332, 2283.4, 0.25, -4.7999997, dbCurve(0.013245033 to -29.41826, 0.09685431 to -19.233044, 0.3576159 to -2.9566689, 0.85264903 to 0), listOf(ampCurve(1447.3846 to 0, 2398.4512 to 1), ampCurve(1764 to 1, 3332 to 0)), emptyList()),
        layer("rear_l5", "s084_rear_l5.wav", SampleLayerRole.LOAD, 1447.3846, 4719.576, 3635.8, 0.25, 0, dbCurve(0 to -34.822918, 0.013245033 to -29.41826, 0.13907285 to -13.291668, 0.839404 to 0, 0.9254967 to 0), listOf(ampCurve(1447.3846 to 0, 2947.3845 to 1), ampCurve(2450 to 1, 4719.576 to 0)), emptyList()),
        layer("ex_l2a_far", "s136_ex_l2a_far.wav", SampleLayerRole.LOAD, 784, 7644, 6125, 0.25, -42, null, listOf(ampCurve(784 to 0, 1164.4636 to 1)), listOf(dbCurve(5136.279 to -42, 5453.6836 to -18.215343, 6223.365 to 0, 6821.161 to 0, 7648.543 to -18.034784, 8044.437 to -42))),
        layer("ex_c4", "s138_ex_c4.wav", SampleLayerRole.COAST, 2182.2847, 4557, 4135.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.13493377 to 0, 0.5281457 to -29.163836, 0.83692056 to -42), listOf(ampCurve(3528 to 1, 4557 to 0), ampCurve(2182.2847 to 0, 3232.702 to 1)), emptyList()),
        layer("ex_c1e", "s058_ex_c1e.wav", SampleLayerRole.COAST, 6811, 9800, 7448, 0.37, 1.8999999, dbCurve(0.03807947 to 0, 0.13493377 to 0, 0.5281457 to -29.163836, 0.83692056 to -42), listOf(ampCurve(6811 to 0, 7615.6357 to 1)), emptyList()),
        layer("loud_l1", "s069_loud_l1.wav", SampleLayerRole.LOAD, 784, 9800, 7595, 0.25, 0, dbCurve(0.0016556291 to -19.233044, 0.23675497 to -14.440001, 0.8576159 to -2.248952, 1 to -0.5), listOf(ampCurve(784 to 0, 1098.5696 to 1)), listOf(dbCurve(6699.371 to -42, 6958.9136 to -18.034784, 7639.2134 to 0, 8113.556 to 0, 8133.6865 to -3))),
        layer("ex_l4", "s123_ex_l4.wav", SampleLayerRole.LOAD, 2744, 9800, 4243.4, 0.31, 0, dbCurve(0.013245033 to -29.41826, 0.09685431 to -19.233044, 0.3576159 to -2.9566689, 0.85264903 to 0), listOf(ampCurve(2744 to 0, 3920 to 1)), listOf(dbCurve(3918.3774 to -4.435341, 5362.417 to -21.962788, 5938.4106 to -42))),
        layer("side_l1", "s095_side_l1.wav", SampleLayerRole.LOAD, 784, 9800, 7595, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.03642384 to -19.233044, 0.9254967 to 0), listOf(ampCurve(784 to 0, 1084 to 1)), listOf(dbCurve(6538.7417 to -42, 6798.2847 to -18.034784, 7639.2134 to 0, 8113.556 to 0, 8133.6865 to -6.0521736))),
        layer("rear_high_l2", "s004_rear_high_l2.wav", SampleLayerRole.COAST, 798.27814, 7658.2783, 6125, 0.25, 0, dbCurve(0.34519866 to 0.79999995, 1 to -41.2), listOf(ampCurve(798.27814 to 0, 1319.139 to 1), ampCurve(5050.5693 to 1, 7658.2783 to 0)), listOf(dbCurve(3559.9028 to -42, 3922.4338 to -17.196001, 5360.389 to 0, 5447.599 to 0))),
        layer("high_pressure_noise", "s060_high_pressure_noise.wav", SampleLayerRole.TEXTURE, 2058, 9800, null, 0, -1.9999999, dbCurve(0 to -6, 0.91887414 to 0), listOf(ampCurve(2058 to 0, 7558 to 1)), emptyList()),
        layer("ex_l5", "s131_ex_l5.wav", SampleLayerRole.LOAD, 1764, 3920, 3635.8, 0.25, -3.6999998, dbCurve(0.013245033 to -29.41826, 0.09685431 to -19.233044, 0.3576159 to -2.9566689, 0.85264903 to 0), listOf(ampCurve(2744 to 1, 3920 to 0), ampCurve(1764 to 0, 3332 to 1)), emptyList()),
        layer("ex_l3", "s009_ex_l3.wav", SampleLayerRole.LOAD, 784, 7644, 5831, 0.27, 0, dbCurve(0.169702 to -42, 0.8965232 to 0), listOf(ampCurve(784 to 0, 1184 to 1)), listOf(dbCurve(3297.055 to -41, 3922.4338 to -18.196001, 5360.389 to -1, 5447.599 to -1, 6222.351 to -17, 6636.093 to -41))),
        layer("rear_high_l1", "s100_rear_high_l1.wav", SampleLayerRole.COAST, 5050.5693, 9800, 7595, 0.25, 0, dbCurve(0.34519866 to 0.79999995, 1 to -41.2), listOf(ampCurve(5050.5693 to 0, 7658.2783 to 1)), emptyList()),
        layer("rear_l1", "s025_rear_l1.wav", SampleLayerRole.LOAD, 784, 9800, 7595, 0.25, 0, dbCurve(0.24586093 to -42, 0.9486755 to 0), listOf(ampCurve(784 to 0, 1184 to 1)), listOf(dbCurve(6419.2603 to -42, 6957.28 to -18.034784, 7639.2134 to 1, 8113.556 to 1, 8133.6865 to -4.3))),
        layer("front_amb_c4", "s153_front_amb_c4.wav", SampleLayerRole.COAST, 1447.3846, 6076, 4135.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.83692056 to -42), listOf(ampCurve(4606 to 1, 6076 to 0), ampCurve(1447.3846 to 0, 4747.385 to 1)), emptyList()),
        layer("rear_limiter", "s086_rear_limiter.wav", SampleLayerRole.LIMITER, 784, 9800, 7977.2, 0.25, 0, dbCurve(0 to -34.822918, 0.013245033 to -29.41826, 0.13907285 to -13.291668, 0.839404 to 0, 0.9254967 to 0), listOf(ampCurve(784 to 0, 1084 to 1)), listOf(dbCurve(8084.942 to -42, 8114.0024 to -19.534784, 8133.9727 to 0.6))),
        layer("ex_limiter", "s114_ex_limiter.wav", SampleLayerRole.LIMITER, 784, 9800, 8486.8, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.09685431 to -19.233044, 0.3576159 to -2.9566689, 0.85264903 to 0), listOf(ampCurve(784 to 0, 984 to 1)), listOf(dbCurve(8066.714 to -42, 8114.0024 to -19.534784, 8133.1494 to 0))),
        layer("rear_c1", "s128_rear_c1.wav", SampleLayerRole.COAST, 6811, 9800, 7448, 0.37, 1, dbCurve(0.03807947 to 0, 0.08526489 to 0, 0.40976822 to -21.33, 0.83692056 to -42), listOf(ampCurve(6811 to 0, 7861 to 1)), emptyList()),
        layer("ex_h1", "s109_ex_h1.wav", SampleLayerRole.COAST, 4900, 9800, 7595, 0.25, -1.4999999, dbCurve(0.25745034 to 0, 1 to -42), listOf(ampCurve(4900 to 0, 7644 to 1)), emptyList()),
        layer("front_amb_c2", "s147_front_amb_c2.wav", SampleLayerRole.COAST, 4606, 9800, 6487.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.83692056 to -42), listOf(ampCurve(4606 to 0, 6076 to 1)), listOf(dbCurve(6809.702 to 0, 7651.788 to -18.633915, 8235.894 to -42))),
        layer("pass_high", "s099_pass_high.wav", SampleLayerRole.LOAD, 6860, 9800, 7595, 0.25, 0, dbCurve(0 to -32.52625, 0.7152318 to -11.2820835, 1 to 0), listOf(ampCurve(6860 to 0, 7660 to 1)), emptyList()),
        layer("front_l3", "s111_front_l3.wav", SampleLayerRole.LOAD, 3059.7417, 6468, 5703.6, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.41721854 to -4.6791687, 0.83774835 to 0), listOf(ampCurve(3059.7417 to 0, 4555.8965 to 1), ampCurve(4568 to 1, 6468 to 0)), emptyList()),
        layer("front_c2", "s028_front_c2.wav", SampleLayerRole.COAST, 4606, 9800, 6487.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.18874171 to 0, 0.6076159 to -23.97594, 0.83692056 to -42), listOf(ampCurve(4606 to 0, 6076 to 1)), listOf(dbCurve(6813.3525 to 0, 7651.788 to -18.633915, 8235.894 to -42))),
        layer("rear_l4", "s023_rear_l4.wav", SampleLayerRole.LOAD, 2450, 5733, 4243.4, 0.31, 0, dbCurve(0 to -34.822918, 0.013245033 to -29.41826, 0.13907285 to -13.291668, 0.839404 to 0, 0.9254967 to 0), listOf(ampCurve(2855.1855 to 1, 5733 to 0), ampCurve(2450 to 0, 4719.576 to 1)), listOf(dbCurve(3918.3774 to -3.3288727, 5362.417 to -21.962788, 5938.4106 to -42))),
        layer("front_l2", "s132_front_l2.wav", SampleLayerRole.LOAD, 784, 7644, 6115.2, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.41721854 to -4.6791687, 0.83774835 to 0), listOf(ampCurve(784 to 0, 1098.5696 to 1)), listOf(dbCurve(4308.7954 to -42, 4626.2 to -18.215343, 6223.365 to 0, 6959.4585 to 0, 7640.6333 to -18.034784, 8012.798 to -42))),
        layer("front_c1", "s040_front_c1.wav", SampleLayerRole.COAST, 6811, 9800, 7448, 0.25, 0, dbCurve(0.03807947 to 0, 0.18874171 to 0, 0.6076159 to -23.97594, 0.83692056 to -42), listOf(ampCurve(6811 to 0, 7661 to 1)), emptyList()),
        layer("front_c3", "s048_front_c3.wav", SampleLayerRole.COAST, 2744, 6076, 4821.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.18874171 to 0, 0.6076159 to -23.97594, 0.83692056 to -42), listOf(ampCurve(4606 to 1, 6076 to 0), ampCurve(2744 to 0, 4555.8965 to 1)), emptyList()),
        layer("front_l4", "s029_front_l4.wav", SampleLayerRole.LOAD, 1308.7218, 4555.8965, 4155.2, 0.25, -1.1999999, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.41721854 to -4.6791687, 0.83774835 to 0), listOf(ampCurve(3059.7417 to 1, 4555.8965 to 0), ampCurve(1308.7218 to 0, 2887.1987 to 1)), emptyList()),
        layer("ex_c6", "s083_ex_c6.wav", SampleLayerRole.COAST, 1447.3846, 3232.7021, 1705.2, 0.25, 0, dbCurve(0.03807947 to 0, 0.13493377 to 0, 0.5281457 to -29.163836, 0.83692056 to -42), listOf(ampCurve(1447.3846 to 0, 2397.3845 to 1), ampCurve(2182.2847 to 1, 3232.702 to 0)), emptyList()),
        layer("ex_l2b_distance", "s122_ex_l2b_distance.wav", SampleLayerRole.LOAD, 784, 7644, 6125, 0.25, 0, null, listOf(ampCurve(784 to 0, 1239.6292 to 1)), listOf(dbCurve(5136.279 to -42, 5453.6836 to -18.215343, 6223.365 to 0, 6957.4526 to 0, 7645.501 to -18.034784, 8008.539 to -42))),
        layer("front_amb_l1", "s035_front_amb_l1.wav", SampleLayerRole.LOAD, 784, 9800, 7546, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.9254967 to 0), listOf(ampCurve(784 to 0, 1198.5696 to 1)), listOf(dbCurve(6734.428 to -42, 6957.9854 to -18.034784, 7641.6475 to 0))),
        layer("ex_c3", "s145_ex_c3.wav", SampleLayerRole.COAST, 3528, 5869.2915, 4821.6, 0.25, -0.99999994, dbCurve(0.03807947 to 0, 0.13493377 to 0, 0.5281457 to -29.163836, 0.83692056 to -42), listOf(ampCurve(3528 to 0, 4557 to 1), ampCurve(4606 to 1, 5869.2915 to 0)), emptyList()),
        layer("pass_mid", "s043_pass_mid.wav", SampleLayerRole.LOAD, 2255.0452, 7644, 6125, 0.25, 0, dbCurve(0 to -32.52625, 0.7152318 to -11.2820835, 1 to 0), listOf(ampCurve(2255.0452 to 0, 2653.9788 to 1), ampCurve(6924 to 1, 7644 to 0)), emptyList()),
        layer("front_amb_l3", "s144_front_amb_l3.wav", SampleLayerRole.LOAD, 1323, 6076, 5831, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.9254967 to 0), listOf(ampCurve(1323 to 0, 4473 to 1), ampCurve(2774.6755 to 1, 6076 to 0)), emptyList()),
        layer("front_c4", "s124_front_c4.wav", SampleLayerRole.COAST, 1447.3846, 4555.8965, 4135.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.18874171 to 0, 0.6076159 to -23.97594, 0.83692056 to -42), listOf(ampCurve(2744 to 1, 4555.8965 to 0), ampCurve(1447.3846 to 0, 3219.57 to 1)), emptyList()),
        layer("ex_idle", "s013_ex_idle.wav", SampleLayerRole.IDLE, 0, 2254, 1200, 0.25, -4.7, dbCurve(0.012417219 to -3, 0.955298 to 0), listOf(ampCurve(1451.5 to 1, 2254 to 0)), emptyList()),
        layer("rear_c3", "s063_rear_c3.wav", SampleLayerRole.COAST, 1447.3846, 6076, 4821.6, 0.25, 0, dbCurve(0.03807947 to 0, 0.08526489 to 0, 0.40976822 to -21.33, 0.83692056 to -42), listOf(ampCurve(1447.3846 to 0, 4747.385 to 1), ampCurve(4606 to 1, 6076 to 0)), emptyList()),
        layer("side_l2", "s104_side_l2.wav", SampleLayerRole.LOAD, 784, 7644, 6125, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.03642384 to -19.233044, 0.9254967 to 0), listOf(ampCurve(784 to 0, 1084 to 1)), listOf(dbCurve(3267.14 to -42, 3915.538 to -18.215343, 5600.319 to 0, 6821.161 to 0, 7789.0938 to -18.034784, 8161.2583 to -42))),
        layer("front_l1", "s115_front_l1.wav", SampleLayerRole.LOAD, 784, 9800, 7546, 0.25, 0, dbCurve(0.013245033 to -29.41826, 0.12251656 to -16.23739, 0.41721854 to -4.6791687, 0.83774835 to 0), listOf(ampCurve(784 to 0, 1198.5696 to 1)), listOf(dbCurve(6612.124 to -42, 6959.1616 to -18.034784, 7641.6475 to 0))),
        layer("loud_l3", "s107_loud_l3.wav", SampleLayerRole.LOAD, 784, 7644, 5831, 0.25, 0, dbCurve(0.0016556291 to -19.233044, 0.23675497 to -14.440001, 0.8576159 to -2.248952, 1 to -0.5), listOf(ampCurve(784 to 0, 1098.5696 to 1)), listOf(dbCurve(2712.949 to -42, 5360.389 to 0, 5447.599 to 0, 6222.351 to -16, 6636.093 to -42))),
    )

    return EngineSampleProfile(
        id = "lamborghini_huracan_trofeo_evo2_exterior",
        displayName = "Lamborghini Huracán Super Trofeo EVO2",
        assetDirectory = "lamborghini_huracan_trofeo_evo2_exterior",
        perspective = "EXTERIOR / NEAR CAR",
        minimumRpm = 0.0,
        maximumRpm = 10_000.0,
        idleRpm = 1_040.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        upshiftRpm = 8_200.0,
        downshiftRpm = 5_900.0,
        finalDrive = 3.96,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftDurationSeconds = 0.060,
        downshiftDurationSeconds = 0.150,
        layers = layers,
    )
}

private fun layer(
    id: String,
    asset: String,
    role: SampleLayerRole,
    start: Number,
    end: Number,
    root: Number?,
    pitch: Number,
    gain: Number,
    throttle: AutomationCurve?,
    amplitude: List<AutomationCurve>,
    rpmGain: List<AutomationCurve> = emptyList(),
) = SampleLayerSpec(
    id = id,
    assetName = asset,
    role = role,
    startRpm = start.toDouble(),
    endRpm = end.toDouble(),
    autopitchRootRpm = root?.toDouble(),
    basePitchSemitones = pitch.toDouble(),
    baseGainDb = gain.toDouble(),
    throttleGainDb = throttle,
    rpmAmplitudeCurves = amplitude,
    rpmGainDbCurves = rpmGain,
)

private fun ampCurve(vararg points: Pair<out Number, out Number>) =
    AutomationCurve(points.map { CurvePoint(it.first.toDouble(), it.second.toDouble()) })

private fun dbCurve(vararg points: Pair<out Number, out Number>) =
    AutomationCurve(points.map { CurvePoint(it.first.toDouble(), it.second.toDouble()) })
