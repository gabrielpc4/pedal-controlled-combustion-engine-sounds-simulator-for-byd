package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

enum class EngineSoundMode(val displayName: String) {
    SAMPLE("SAMPLE"),
    SYNTH("SYNTH"),
}

internal enum class SampleTrack {
    LOAD,
    COAST,
    EXTRA,
}

internal data class SampleLoopSpec(
    val id: String,
    val assetName: String,
    val track: SampleTrack,
    val startRpm: Double,
    val endRpm: Double,
    val autopitchRootRpm: Double,
    val basePitchSemitones: Double,
    val minimumPlaybackRatio: Double = 0.20,
) {
    fun playbackRatio(audioRpm: Double): Double =
        ((audioRpm / autopitchRootRpm) * 2.0.pow(basePitchSemitones / 12.0))
            .coerceIn(minimumPlaybackRatio, 3.0)
}

internal data class AutomationPoint(val input: Double, val decibels: Double)

internal object SampleEngineManifest {
    const val PROFILE_ID = "supra_mk4_cabin"
    const val AUTHORED_MAX_RPM = 8_000.0

    val loadThrottleCurve = listOf(
        AutomationPoint(0.10, -28.819),
        AutomationPoint(0.202922, 2.5),
        AutomationPoint(0.432616, 3.0),
    )
    val coastThrottleCurve = listOf(
        AutomationPoint(0.10, -7.8496),
        AutomationPoint(0.85, -36.5),
    )
    val extraThrottleCurve = listOf(
        AutomationPoint(0.10, -27.6209),
        AutomationPoint(0.52, -19.8322),
        AutomationPoint(0.90, -13.2417),
    )

    // Engine-only reconstruction of the bank's cabin event. One-shots, limiter, turbo,
    // transmission, pump, fan, tyre, and body sounds are deliberately excluded.
    val loops = listOf(
        load("load_01", "010_1.wav", 0.0, 1_604.50, 1_777.7777, 0.0),
        load("load_03", "060_3.wav", 1_198.07, 2_032.85, 3_091.7876, 6.6),
        load("load_06", "036_6.wav", 1_661.84, 2_581.64, 3_091.7876, 4.0),
        load("load_31", "097_31.wav", 2_110.15, 2_859.90, 3_091.7876, -7.7),
        load("load_11", "089_11.wav", 2_597.10, 3_285.02, 3_091.7876, -5.2),
        load("load_21", "049_21.wav", 2_986.67, 3_710.15, 3_091.7876, -4.0),
        load("load_29", "088_29.wav", 3_372.29, 4_019.32, 3_091.7876, -7.5),
        load("load_32", "022_32.wav", 3_814.49, 4_251.21, 3_091.7876, -8.5),
        load("load_33", "080_33.wav", 4_088.37, 4_544.93, 3_091.7876, -9.0),
        load("load_37", "090_37.wav", 4_320.77, 4_765.22, 3_091.7876, -10.7),
        load("load_39", "053_39.wav", 4_618.36, 5_201.93, 3_091.7876, -11.0),
        load("load_45", "068_45.wav", 4_853.33, 5_600.00, 3_091.7876, -13.0),
        load("load_41", "077_41.wav", 5_271.50, 6_608.70, 3_091.7876, -12.0),
        load("load_high_1", "055_6.1 INT.wav", 5_797.10, 8_000.00, 3_864.7344, -0.18),
        load("load_high_2", "046_54.wav", 6_028.99, 7_783.57, 3_864.7344, -16.3),
        load("load_high_3", "032_56.wav", 7_567.15, 7_788.04, 4_251.2080, -14.5),
        coast("idle", "029_idle int 2.wav", 154.59, 1_661.84, 1_777.7777, 3.05, 0.24),
        coast("coast_09", "030_decel 9.wav", 1_082.13, 3_169.08, 3_091.7876, 0.98),
        coast("coast_02", "056_decel 2.wav", 2_048.31, 4_173.91, 4_000.0, 1.75),
        coast("coast_03", "023_decel 3.wav", 3_632.85, 5_256.04, 4_000.0, -1.65),
        coast("coast_06", "014_decel 6.wav", 4_714.98, 6_956.52, 4_000.0, -6.2),
        coast("coast_08", "086_decel 8.wav", 5_256.04, 8_000.00, 4_000.0, -9.1),
        SampleLoopSpec(
            id = "engine_body",
            assetName = "092_4.2 int.wav",
            track = SampleTrack.EXTRA,
            startRpm = 231.88,
            endRpm = 5_913.04,
            autopitchRootRpm = 4_000.0,
            basePitchSemitones = 3.7,
        ),
    )

    val requiredAssets: Set<String> = loops.mapTo(linkedSetOf()) { it.assetName }

    private fun load(
        id: String,
        asset: String,
        start: Double,
        end: Double,
        root: Double,
        pitch: Double,
    ) = SampleLoopSpec(id, asset, SampleTrack.LOAD, start, end, root, pitch)

    private fun coast(
        id: String,
        asset: String,
        start: Double,
        end: Double,
        root: Double,
        pitch: Double,
        minimumRatio: Double = 0.20,
    ) = SampleLoopSpec(id, asset, SampleTrack.COAST, start, end, root, pitch, minimumRatio)
}

internal fun automationDecibels(points: List<AutomationPoint>, input: Double): Double {
    require(points.isNotEmpty())
    val x = input.coerceIn(0.0, 1.0)
    if (x <= points.first().input) return points.first().decibels
    if (x >= points.last().input) return points.last().decibels
    val rightIndex = points.indexOfFirst { x <= it.input }.coerceAtLeast(1)
    val left = points[rightIndex - 1]
    val right = points[rightIndex]
    val fraction = (x - left.input) / (right.input - left.input)
    return left.decibels + (right.decibels - left.decibels) * fraction
}

