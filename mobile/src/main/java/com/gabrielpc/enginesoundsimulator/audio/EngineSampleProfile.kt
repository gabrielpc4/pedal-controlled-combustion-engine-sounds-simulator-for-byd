package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

internal enum class SampleLayerRole { IDLE, LOAD, COAST, TEXTURE, LIMITER }

internal data class CurvePoint(val input: Double, val output: Double)

internal data class AutomationCurve(val points: List<CurvePoint>) {
    init {
        require(points.isNotEmpty())
        require(points.zipWithNext().all { (left, right) -> left.input <= right.input })
    }

    fun valueAt(input: Double): Double {
        if (input <= points.first().input) return points.first().output
        if (input >= points.last().input) return points.last().output
        val rightIndex = points.indexOfFirst { input <= it.input }.coerceAtLeast(1)
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val fraction = (input - left.input) / (right.input - left.input)
        return left.output + (right.output - left.output) * fraction
    }
}

internal data class SampleLayerSpec(
    val id: String,
    val assetName: String,
    val role: SampleLayerRole,
    val startRpm: Double,
    val endRpm: Double,
    val autopitchRootRpm: Double? = null,
    val basePitchSemitones: Double = 0.0,
    val baseGainDb: Double = 0.0,
    val throttleGainDb: AutomationCurve? = null,
    val rpmAmplitudeCurves: List<AutomationCurve> = emptyList(),
    val rpmGainDbCurves: List<AutomationCurve> = emptyList(),
) {
    fun playbackRatio(rpm: Double): Double {
        val authoredPitch = 2.0.pow(basePitchSemitones / 12.0)
        return ((autopitchRootRpm?.let { rpm / it } ?: 1.0) * authoredPitch).coerceIn(0.10, 4.0)
    }

    fun gainAt(rpm: Double, throttle: Double): Double {
        if (rpm !in startRpm..endRpm) return 0.0
        val amplitude = rpmAmplitudeCurves.fold(1.0) { gain, curve -> gain * curve.valueAt(rpm) }
        if (amplitude <= 0.0) return 0.0
        val decibels = baseGainDb + (throttleGainDb?.valueAt(throttle) ?: 0.0) +
            rpmGainDbCurves.sumOf { it.valueAt(rpm) }
        return amplitude * 10.0.pow(decibels / 20.0)
    }
}

internal data class EngineSampleProfile(
    val id: String,
    val displayName: String,
    val assetDirectory: String,
    val minimumRpm: Double,
    val maximumRpm: Double,
    val idleRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    val upshiftRpm: Double,
    val downshiftRpm: Double,
    val finalDrive: Double,
    val gearRatios: List<Double>,
    val upshiftDurationSeconds: Double,
    val downshiftDurationSeconds: Double,
    val layers: List<SampleLayerSpec>,
    val throttleOutputGainDb: AutomationCurve? = null,
) {
    val requiredAssets: Set<String> = layers.mapTo(linkedSetOf()) { it.assetName }

    fun outputGainAt(throttle: Double): Double =
        10.0.pow((throttleOutputGainDb?.valueAt(throttle.coerceIn(0.0, 1.0)) ?: 0.0) / 20.0)
}

internal object EngineSampleProfiles {
    val default = huracanTrofeoEvo2Profile()
}
