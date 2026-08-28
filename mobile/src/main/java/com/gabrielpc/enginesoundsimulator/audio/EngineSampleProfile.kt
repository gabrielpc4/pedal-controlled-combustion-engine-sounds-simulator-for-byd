package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

internal enum class SampleLayerRole { IDLE, LOAD, COAST, TEXTURE, LIMITER }

/** Keeps the continuous idle program present in the cabin without raising driving-layer volume. */
private const val IDLE_LAYER_GAIN_BOOST_DB = 8.0

internal enum class SampleEffectTrigger { TRANSMISSION_LOOP, SHIFT_UP, SHIFT_DOWN, THROTTLE_LIFT }

internal data class SampleEffectControlSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val bit: Long,
)

internal object SampleEffectControls {
    val gearChanges = SampleEffectControlSpec(
        id = "gear_changes",
        displayName = "Gear changes",
        description = "Cabin shift impacts from this car's sound bank",
        bit = 1L shl 0,
    )
    val transmission = SampleEffectControlSpec(
        id = "transmission",
        displayName = "Transmission whine",
        description = "Drivetrain tone that rises with the simulated RPM",
        bit = 1L shl 1,
    )
    val exhaustOverrun = SampleEffectControlSpec(
        id = "exhaust_overrun",
        displayName = "Exhaust overrun",
        description = "Crackle or backfire after a strong throttle lift",
        bit = 1L shl 2,
    )
}

internal data class SampleEffectSpec(
    val id: String,
    val control: SampleEffectControlSpec,
    val assetName: String,
    val trigger: SampleEffectTrigger,
    val baseGainDb: Double = 0.0,
    val minimumRpm: Double = 0.0,
)

internal data class CurvePoint(val input: Double, val output: Double)

internal data class AutomationCurve(val points: List<CurvePoint>) {
    init {
        require(points.isNotEmpty())
        require(points.zipWithNext().all { (left, right) -> left.input <= right.input })
    }

    fun valueAt(input: Double): Double {
        if (input <= points.first().input) return points.first().output
        if (input >= points.last().input) return points.last().output
        var rightIndex = 1
        while (rightIndex < points.size && input > points[rightIndex].input) {
            rightIndex += 1
        }
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

    fun gainAt(
        rpm: Double,
        throttle: Double,
        coastLayerMixEnabled: Boolean = true,
    ): Double {
        if (rpm !in startRpm..endRpm) return 0.0
        if (coastLayerMixEnabled && role == SampleLayerRole.LOAD) return 0.0

        if (coastLayerMixEnabled && role == SampleLayerRole.IDLE) {
            val amplitude = idleCoastMixAmplitude(rpm)
            if (amplitude <= 0.0) return 0.0

            val throttleGainContribution = throttleGainDb?.valueAt(throttle) ?: 0.0
            var rpmGainDb = 0.0
            for (index in rpmGainDbCurves.indices) {
                rpmGainDb += rpmGainDbCurves[index].valueAt(rpm)
            }
            val decibels = baseGainDb + IDLE_LAYER_GAIN_BOOST_DB + throttleGainContribution +
                rpmGainDb
            return amplitude * 10.0.pow(decibels / 20.0)
        }

        var amplitude = 1.0
        for (index in rpmAmplitudeCurves.indices) {
            amplitude *= rpmAmplitudeCurves[index].valueAt(rpm)
        }
        if (amplitude <= 0.0) return 0.0

        if (coastLayerMixEnabled && role == SampleLayerRole.COAST) {
            return amplitude * 10.0.pow(baseGainDb / 20.0)
        }

        val throttleGainContribution = throttleGainDb?.valueAt(throttle) ?: 0.0
        var rpmGainDb = 0.0
        for (index in rpmGainDbCurves.indices) {
            rpmGainDb += rpmGainDbCurves[index].valueAt(rpm)
        }
        val decibels = baseGainDb + (if (role == SampleLayerRole.IDLE) IDLE_LAYER_GAIN_BOOST_DB else 0.0) +
            throttleGainContribution + rpmGainDb
        return amplitude * 10.0.pow(decibels / 20.0)
    }

    /** Wider smoothstep fade so idle_low eases in/out instead of snapping at the band edge. */
    private fun idleCoastMixAmplitude(rpm: Double): Double {
        val holdEndRpm = 1_350.0
        val fadeOutEndRpm = 2_950.0
        if (rpm <= holdEndRpm) return 1.0
        if (rpm >= fadeOutEndRpm) return 0.0

        val fraction = (rpm - holdEndRpm) / (fadeOutEndRpm - holdEndRpm)
        return 1.0 - smoothstep(fraction)
    }
}

private fun smoothstep(fraction: Double): Double {
    val clamped = fraction.coerceIn(0.0, 1.0)
    return clamped * clamped * (3.0 - 2.0 * clamped)
}

internal data class EngineSampleProfile(
    val id: String,
    val displayName: String,
    val assetDirectory: String,
    val previewAssetName: String,
    /** Authored WAV rate used to validate the recovered source bank. */
    val outputSampleRate: Int,
    /** Rate requested from AudioTrack; may differ when a car needs app-side resampling. */
    val playbackSampleRate: Int = outputSampleRate,
    val minimumRpm: Double,
    val maximumRpm: Double,
    val idleRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    val upshiftRpm: Double,
    val gearRatios: List<Double>,
    val upshiftDurationSeconds: Double,
    val downshiftDurationSeconds: Double,
    val layers: List<SampleLayerSpec>,
    val effects: List<SampleEffectSpec> = emptyList(),
    val throttleOutputGainDb: AutomationCurve? = null,
) {
    val requiredAssets: Set<String> = linkedSetOf<String>().apply {
        layers.mapTo(this) { it.assetName }
        effects.mapTo(this) { it.assetName }
    }

    fun loopLayersForLoad(coastLayerMixEnabled: Boolean): List<SampleLayerSpec> {
        if (coastLayerMixEnabled) {
            return layers.filter { layer -> layer.role != SampleLayerRole.LOAD }
        }
        return layers
    }

    fun requiredAssetsForLoad(coastLayerMixEnabled: Boolean): Set<String> = linkedSetOf<String>().apply {
        loopLayersForLoad(coastLayerMixEnabled).mapTo(this) { it.assetName }
        effects.mapTo(this) { it.assetName }
    }
    val effectControls: List<SampleEffectControlSpec> = effects.map { it.control }.distinctBy { it.id }
    val defaultEffectMask: Long = effectControls.fold(0L) { mask, control -> mask or control.bit }

    fun outputGainAt(throttle: Double): Double =
        10.0.pow((throttleOutputGainDb?.valueAt(throttle.coerceIn(0.0, 1.0)) ?: 0.0) / 20.0)
}

/** Common road-car figures and Brazilian market-price references for a sound profile's model family. */
internal data class CarSpecifications(
    val horsepower: String,
    val torqueKgfm: String,
    val zeroToHundred: String,
    val weight: String,
    val priceBrl: String,
) {
    fun summary(): String =
        "$horsepower HP  •  $torqueKgfm kgfm  •  0–100 $zeroToHundred  •  $weight kg  •  PRICE $priceBrl"
}

internal object EngineSampleProfiles {
    val default = huracanTrofeoEvo2Profile()
    val all = listOf(
        default,
        lamborghiniAventadorSvProfile(),
    )
    val maximumSupportedRpm = all.maxOf { it.maximumRpm }

    fun find(id: String?): EngineSampleProfile = all.firstOrNull { it.id == id } ?: default

    fun adjacent(currentId: String, offset: Int): EngineSampleProfile {
        val current = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return all[(current + offset).mod(all.size)]
    }

    fun specificationsFor(id: String): CarSpecifications = specifications[id] ?: unavailableSpecifications

    private val unavailableSpecifications = CarSpecifications(
        horsepower = "—",
        torqueKgfm = "—",
        zeroToHundred = "—",
        weight = "—",
        priceBrl = "—",
    )

    private val specifications = mapOf(
        "lamborghini_huracan_trofeo_evo2_cabin" to CarSpecifications(
            horsepower = "631",
            torqueKgfm = "61",
            zeroToHundred = "2.9 s",
            weight = "1,422",
            priceBrl = "R$ 3.333.920",
        ),
        "lamborghini_aventador_sv_cabin" to CarSpecifications(
            horsepower = "730",
            torqueKgfm = "70",
            zeroToHundred = "2.9 s",
            weight = "1,575",
            priceBrl = "R$ 5.200.000",
        ),
    )
}
