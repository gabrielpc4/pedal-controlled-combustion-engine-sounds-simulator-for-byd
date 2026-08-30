package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.pow

internal enum class SampleLayerRole { IDLE, LOAD, COAST, TEXTURE, LIMITER }

/** Keeps the continuous idle program present in the cabin without raising driving-layer volume. */
private const val IDLE_LAYER_GAIN_BOOST_DB = 8.0

internal enum class SampleEffectTrigger {
    TRANSMISSION_LOOP,
    SHIFT_UP,
    SHIFT_DOWN,
    THROTTLE_LIFT,
    TURBO_LOOP,
    TURBO_FLUTTER,
    TURBO_DUMP,
    ;

    fun isContinuousLoop(): Boolean {
        return this == TRANSMISSION_LOOP || this == TURBO_LOOP || this == TURBO_FLUTTER
    }

    fun isTurboSound(): Boolean {
        return this == TURBO_LOOP || this == TURBO_FLUTTER || this == TURBO_DUMP
    }
}

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
    val turbo = SampleEffectControlSpec(
        id = "turbo",
        displayName = "Turbo",
        description = "Spool whistle and compressor flutter from this car's sound bank",
        bit = 1L shl 3,
    )
}

internal data class SampleEffectSpec(
    val id: String,
    val control: SampleEffectControlSpec,
    val assetName: String,
    val trigger: SampleEffectTrigger,
    val baseGainDb: Double = 0.0,
    val minimumRpm: Double = 0.0,
    /** Extra one-shot assets selected at trigger time from a deterministic source set. */
    val variantAssetNames: List<String> = emptyList(),
    /** When set, overrides the WAV smpl loop so a long take can skip dump bangs. */
    val loopStartSeconds: Double? = null,
    val loopEndSeconds: Double? = null,
) {
    val allAssetNames: List<String> = buildList {
        add(assetName)
        addAll(variantAssetNames)
    }

    fun resolvedLoopStartFrame(sampleRate: Int, fallback: Int): Int {
        val seconds = loopStartSeconds
        if (seconds == null) {
            return fallback
        }

        return (seconds * sampleRate).toInt().coerceAtLeast(0)
    }

    fun resolvedLoopEndFrameExclusive(sampleRate: Int, fallback: Int, frameCount: Int): Int {
        val seconds = loopEndSeconds
        if (seconds == null) {
            return fallback
        }

        val start = resolvedLoopStartFrame(sampleRate, 0)
        return (seconds * sampleRate).toInt().coerceIn(start + 1, frameCount)
    }

    fun isNativeExhaustOverrun(): Boolean {
        return trigger == SampleEffectTrigger.THROTTLE_LIFT &&
            control.id == SampleEffectControls.exhaustOverrun.id &&
            id != SharedPopsAndBangs.EFFECT_ID
    }

    fun isNativeGearChange(): Boolean {
        return (trigger == SampleEffectTrigger.SHIFT_UP || trigger == SampleEffectTrigger.SHIFT_DOWN) &&
            control.id == SampleEffectControls.gearChanges.id &&
            id != SharedHuracanShiftSounds.SHIFT_UP_ID &&
            id != SharedHuracanShiftSounds.SHIFT_DOWN_ID
    }
}

internal data class CurvePoint(
    val input: Double,
    val output: Double,
    /** FMOD's outgoing handle for this segment; zero retains ordinary linear interpolation. */
    val shape: Double = 0.0,
    /** FMOD 1.08 curve type: exponential (0) or two-handle ease (1). */
    val interpolationType: Int = 0,
)

internal data class AutomationCurve(val points: List<CurvePoint>) {
    init {
        require(points.isNotEmpty())
        require(points.zipWithNext().all { (left, right) -> left.input <= right.input })
        require(points.all { point -> point.interpolationType in 0..1 })
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
        val fraction = interpolationFraction(
            (input - left.input) / (right.input - left.input),
            left.shape,
            left.interpolationType,
        )
        return left.output + (right.output - left.output) * fraction
    }

    private fun interpolationFraction(fraction: Double, shape: Double, type: Int): Double {
        return when (type) {
            0 -> {
                val exponent = shape * FMOD_EXPONENTIAL_SHAPE_SCALE
                if (abs(exponent) < LINEAR_SHAPE_EPSILON) {
                    fraction
                } else {
                    expm1(exponent * fraction) / expm1(exponent)
                }
            }

            1 -> {
                val exponent = 1.0 + 2.0 * abs(shape)
                if (shape >= 0.0) {
                    if (fraction <= 0.5) {
                        0.5 * (2.0 * fraction).pow(exponent)
                    } else {
                        1.0 - 0.5 * (2.0 * (1.0 - fraction)).pow(exponent)
                    }
                } else if (fraction <= 0.5) {
                    0.5 * (1.0 - (1.0 - 2.0 * fraction).pow(exponent))
                } else {
                    0.5 + 0.5 * (2.0 * fraction - 1.0).pow(exponent)
                }
            }

            else -> error("Curve point type is validated at construction.")
        }
    }

    private companion object {
        const val FMOD_EXPONENTIAL_SHAPE_SCALE = 6.9522
        const val LINEAR_SHAPE_EPSILON = 1.0e-7
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
    /** The generic profiles retain their intentionally louder cabin idle; authored profiles can opt out. */
    val applyIdleGainBoost: Boolean = true,
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
        loadOnlyProgram: Boolean = true,
        primaryLayerSource: PrimaryEngineLayerSource = PrimaryEngineLayerSource.LOAD,
    ): Double {
        if (rpm !in startRpm..endRpm) return 0.0
        if (loadOnlyProgram && primaryLayerSource == PrimaryEngineLayerSource.LOAD && role == SampleLayerRole.COAST) return 0.0
        if (loadOnlyProgram && primaryLayerSource == PrimaryEngineLayerSource.COAST && role == SampleLayerRole.LOAD) return 0.0

        var amplitude = 1.0
        for (index in rpmAmplitudeCurves.indices) {
            amplitude *= rpmAmplitudeCurves[index].valueAt(rpm)
        }
        if (amplitude <= 0.0) return 0.0

        val effectiveThrottle = when {
            !loadOnlyProgram || role == SampleLayerRole.IDLE -> throttle
            primaryLayerSource == PrimaryEngineLayerSource.LOAD -> 1.0
            primaryLayerSource == PrimaryEngineLayerSource.COAST -> 0.0
            primaryLayerSource == PrimaryEngineLayerSource.FMOD_MIX -> throttle
            else -> throttle
        }
        val throttleGainContribution = throttleGainDb?.valueAt(effectiveThrottle) ?: 0.0
        var rpmGainDb = 0.0
        for (index in rpmGainDbCurves.indices) {
            rpmGainDb += rpmGainDbCurves[index].valueAt(rpm)
        }
        val decibels = baseGainDb +
            (if (role == SampleLayerRole.IDLE && applyIdleGainBoost) IDLE_LAYER_GAIN_BOOST_DB else 0.0) +
            throttleGainContribution + rpmGainDb
        return amplitude * 10.0.pow(decibels / 20.0)
    }

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
    /** Skyline's recovered FMOD event requires its authored load/coast crossfade. */
    val supportsLoadOnlyProgram: Boolean = true,
) {
    val hasTurboSounds: Boolean = effects.any { effect -> effect.trigger.isTurboSound() }
    val requiredAssets: Set<String> = linkedSetOf<String>().apply {
        layers.mapTo(this) { it.assetName }
        effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun appliesLoadOnlyProgram(loadOnlyProgram: Boolean): Boolean {
        return supportsLoadOnlyProgram && loadOnlyProgram
    }

    fun resolvedPrimaryLayerSource(source: PrimaryEngineLayerSource): PrimaryEngineLayerSource {
        val hasCoastLayers = layers.any { it.role == SampleLayerRole.COAST }
        return if (source != PrimaryEngineLayerSource.LOAD && supportsLoadOnlyProgram && hasCoastLayers) {
            source
        } else {
            PrimaryEngineLayerSource.LOAD
        }
    }

    fun supportsPrimaryLayerSource(source: PrimaryEngineLayerSource): Boolean =
        resolvedPrimaryLayerSource(source) == source

    fun loopLayersForLoad(loadOnlyProgram: Boolean): List<SampleLayerSpec> {
        if (appliesLoadOnlyProgram(loadOnlyProgram)) {
            return layers.filter { layer -> layer.role != SampleLayerRole.COAST }
        }
        return layers
    }

    fun requiredAssetsForLoad(loadOnlyProgram: Boolean): Set<String> = linkedSetOf<String>().apply {
        loopLayersForLoad(loadOnlyProgram).mapTo(this) { it.assetName }
        effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun loopLayersForPrimarySource(source: PrimaryEngineLayerSource): List<SampleLayerSpec> {
        return when (resolvedPrimaryLayerSource(source)) {
            PrimaryEngineLayerSource.LOAD -> loopLayersForLoad(loadOnlyProgram = true)
            PrimaryEngineLayerSource.COAST -> layers.filter { it.role != SampleLayerRole.LOAD }
            PrimaryEngineLayerSource.FMOD_MIX -> layers
        }
    }

    fun requiredAssetsForPrimarySource(source: PrimaryEngineLayerSource): Set<String> = linkedSetOf<String>().apply {
        loopLayersForPrimarySource(source).mapTo(this) { it.assetName }
        effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

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
        nissanSkylineR34Profile(),
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
        "nissan_skyline_r34_cabin" to CarSpecifications(
            horsepower = "325",
            torqueKgfm = "40",
            zeroToHundred = "4.9 s",
            weight = "1,560",
            priceBrl = "R$ 1.200.000",
        ),
    )
}
