package com.gabrielpc.enginesoundsimulator.audio

private data class ExteriorRootedSample(
    val assetName: String,
    val rootRpm: Double,
    val gainDb: Double = 0.0,
)

internal fun huracanExteriorProgram(effects: List<SampleEffectSpec>): EngineSampleProgram = exteriorBandProgram(
    idleRpm = 1_040.0,
    maximumRpm = 10_000.0,
    idle = ExteriorRootedSample("s013_ex_idle.wav", 1_200.0, -4.7),
    load = listOf(
        ExteriorRootedSample("s042_ex_l6.wav", 2_283.4, -4.8),
        ExteriorRootedSample("s131_ex_l5.wav", 3_635.8, -3.7),
        ExteriorRootedSample("s123_ex_l4.wav", 4_243.4),
        ExteriorRootedSample("s009_ex_l3.wav", 5_831.0),
        ExteriorRootedSample("s136_ex_l2a_far.wav", 6_125.0),
        ExteriorRootedSample("s088_ex_l1b.wav", 7_595.0),
    ),
    coast = listOf(
        ExteriorRootedSample("s083_ex_c6.wav", 1_705.2),
        ExteriorRootedSample("s138_ex_c4.wav", 4_135.6),
        ExteriorRootedSample("s145_ex_c3.wav", 4_821.6, -1.0),
        ExteriorRootedSample("s016_ex_c2.wav", 6_487.6),
        ExteriorRootedSample("s058_ex_c1e.wav", 7_448.0, 1.9),
    ),
    effects = effects,
    bandGainDb = -5.5,
)

internal fun aventadorExteriorProgram(effects: List<SampleEffectSpec>): EngineSampleProgram = exteriorBandProgram(
    idleRpm = 1_000.0,
    maximumRpm = 9_200.0,
    idle = ExteriorRootedSample("ex_aventador_idle.wav", 1_720.0, -3.0),
    load = listOf(
        ExteriorRootedSample("ex_aventador_onlow.wav", 4_430.0, 2.0),
        ExteriorRootedSample("ex_aventador_onmid.wav", 4_720.0, -1.0),
        ExteriorRootedSample("ex_aventador_onmidhigh.wav", 5_585.0),
        ExteriorRootedSample("ex_aventador_onhigh.wav", 7_530.0, 1.5),
        ExteriorRootedSample("ex_aventador_onveryhigh.wav", 7_830.0),
    ),
    coast = listOf(
        ExteriorRootedSample("ex_aventador_offverylow.wav", 3_160.0, 4.0),
        ExteriorRootedSample("ex_aventador_offmid.wav", 5_650.0, 2.0),
        ExteriorRootedSample("ex_aventador_offveryhigh.wav", 7_620.0, 3.0),
    ),
    effects = effects,
    bandGainDb = -5.0,
)

internal fun skylineExteriorProgram(effects: List<SampleEffectSpec>): EngineSampleProgram = exteriorBandProgram(
    idleRpm = 950.0,
    maximumRpm = 8_500.0,
    idle = ExteriorRootedSample("rb26_4_ex_idle.wav", 1_359.0, -6.5),
    load = listOf(
        ExteriorRootedSample("rb26_in_2_onverylow.wav", 2_600.0),
        ExteriorRootedSample("rb26_in_2_onlow.wav", 4_160.0, 1.0),
        ExteriorRootedSample("rb26_in_2_onmid.wav", 4_780.0, 1.0),
        ExteriorRootedSample("rb26_in_2_onmid2.wav", 5_680.0, 1.0),
        ExteriorRootedSample("rb26_in_2_onhigh.wav", 6_580.0, 1.0),
        ExteriorRootedSample("rb26_in_2_onhigh2.wav", 7_200.0, 2.0),
    ),
    coast = listOf(
        ExteriorRootedSample("rb26_4_ex_off_verylow.wav", 1_600.0, -1.0),
        ExteriorRootedSample("rb26_ex_5_offverylow.wav", 2_330.0),
        ExteriorRootedSample("rb26_ex_5_offlow.wav", 4_060.0),
        ExteriorRootedSample("rb26_ex_5_offmid.wav", 5_330.0),
    ),
    effects = effects,
    bandGainDb = -5.0,
)

private fun exteriorBandProgram(
    idleRpm: Double,
    maximumRpm: Double,
    idle: ExteriorRootedSample,
    load: List<ExteriorRootedSample>,
    coast: List<ExteriorRootedSample>,
    effects: List<SampleEffectSpec>,
    bandGainDb: Double,
): EngineSampleProgram {
    val loadThrottle = exteriorDbCurve(
        0.0 to -40.0,
        0.12 to -20.0,
        0.40 to -6.0,
        1.0 to 0.0,
    )
    val coastThrottle = exteriorDbCurve(
        0.0 to 0.0,
        0.20 to -4.0,
        0.55 to -22.0,
        1.0 to -42.0,
    )
    val layers = buildList {
        add(
            SampleLayerSpec(
                id = "exterior_idle",
                assetName = idle.assetName,
                role = SampleLayerRole.IDLE,
                startRpm = 0.0,
                endRpm = (idleRpm * 2.25).coerceAtMost(maximumRpm),
                autopitchRootRpm = idle.rootRpm,
                baseGainDb = idle.gainDb,
                applyIdleGainBoost = false,
                throttleGainDb = exteriorDbCurve(0.0 to 0.0, 1.0 to -12.0),
                rpmAmplitudeCurves = listOf(
                    exteriorAmplitudeCurve(
                        idleRpm to 1.0,
                        (idleRpm * 2.25).coerceAtMost(maximumRpm) to 0.0,
                    ),
                ),
            ),
        )
        addAll(exteriorBandLayers("exterior_load", SampleLayerRole.LOAD, load, idleRpm, maximumRpm, loadThrottle, bandGainDb))
        addAll(exteriorBandLayers("exterior_coast", SampleLayerRole.COAST, coast, idleRpm, maximumRpm, coastThrottle, bandGainDb))
    }
    return EngineSampleProgram(
        layers = layers,
        effects = effects,
        throttleOutputGainDb = exteriorDbCurve(0.0 to 0.0, 1.0 to 0.75),
        supportsLoadOnlyProgram = true,
    )
}

private fun exteriorBandLayers(
    prefix: String,
    role: SampleLayerRole,
    samples: List<ExteriorRootedSample>,
    minimumRpm: Double,
    maximumRpm: Double,
    throttleCurve: AutomationCurve,
    bandGainDb: Double,
): List<SampleLayerSpec> = samples.mapIndexed { index, sample ->
    val left = if (index == 0) minimumRpm else (samples[index - 1].rootRpm + sample.rootRpm) / 2.0
    val right = if (index == samples.lastIndex) maximumRpm else (sample.rootRpm + samples[index + 1].rootRpm) / 2.0
    val fadeWidth = ((right - left) * 0.60).coerceAtLeast(260.0)
    val start = (left - fadeWidth).coerceAtLeast(0.0)
    val end = (right + fadeWidth).coerceAtMost(maximumRpm)
    SampleLayerSpec(
        id = "${prefix}_${index + 1}",
        assetName = sample.assetName,
        role = role,
        startRpm = start,
        endRpm = end,
        autopitchRootRpm = sample.rootRpm,
        baseGainDb = sample.gainDb + bandGainDb,
        applyIdleGainBoost = false,
        throttleGainDb = throttleCurve,
        rpmAmplitudeCurves = listOf(
            exteriorAmplitudeCurve(start to 0.0, left to 1.0),
            exteriorAmplitudeCurve(right to 1.0, end to 0.0),
        ),
    )
}

private fun exteriorAmplitudeCurve(vararg points: Pair<Double, Double>) =
    AutomationCurve(points.map { point -> CurvePoint(point.first, point.second) })

private fun exteriorDbCurve(vararg points: Pair<Double, Double>) =
    AutomationCurve(points.map { point -> CurvePoint(point.first, point.second) })
