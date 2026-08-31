package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.pow

internal enum class SampleLayerRole { IDLE, LOAD, COAST, TEXTURE, LIMITER }

/**
 * Continuous layers that belong to each selectable engine program. IDLE and TEXTURE are shared
 * foundations; LIMITER is a full-load layer. Turbo, transmission, shifts, and overrun are effects
 * with independent live-input rules and do not participate in this source-family selection.
 */
internal fun SampleLayerRole.isIncludedIn(source: PrimaryEngineLayerSource): Boolean = when (source) {
    PrimaryEngineLayerSource.LOAD -> this != SampleLayerRole.COAST
    PrimaryEngineLayerSource.COAST -> this == SampleLayerRole.IDLE ||
        this == SampleLayerRole.COAST ||
        this == SampleLayerRole.TEXTURE
    PrimaryEngineLayerSource.FMOD_MIX -> true
}

/** Keeps the continuous idle program present in the cabin without raising driving-layer volume. */
private const val IDLE_LAYER_GAIN_BOOST_DB = 8.0

internal enum class SampleEffectTrigger {
    PARAMETER_PLACEMENT_ENTRY,
    ENGINE_EVENT_START,
    TRANSMISSION_LOOP,
    TRANSMISSION_PULSE,
    SHIFT_UP,
    SHIFT_DOWN,
    SHIFT_REJECTED,
    THROTTLE_LIFT,
    TURBO_LOOP,
    TURBO_FLUTTER,
    TURBO_DUMP,
    LIMITER_LOOP,
    LIMITER_PULSE,
    TRACTION_LIMIT,
    TRACTION_PULSE,
    ENGINE_START,
    ;

    fun isContinuousLoop(): Boolean {
        return this == TRANSMISSION_LOOP || this == TURBO_LOOP || this == TURBO_FLUTTER ||
            this == LIMITER_LOOP || this == TRACTION_LIMIT
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
    val limiter = SampleEffectControlSpec(
        id = "limiter",
        displayName = "Limiter",
        description = "Engine limiter pulse from this car's sound bank",
        bit = 1L shl 4,
    )
    val tractionLimit = SampleEffectControlSpec(
        id = "traction_limit",
        displayName = "Traction control",
        description = "Traction-limit sound from this car's sound bank",
        bit = 1L shl 5,
    )
    val engineLifecycle = SampleEffectControlSpec(
        id = "engine_lifecycle",
        displayName = "Engine lifecycle",
        description = "Engine start and event-start transients from this car's sound bank",
        bit = 1L shl 6,
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
        if (loadOnlyProgram && !role.isIncludedIn(primaryLayerSource)) return 0.0

        var amplitude = 1.0
        for (index in rpmAmplitudeCurves.indices) {
            amplitude *= rpmAmplitudeCurves[index].valueAt(rpm)
        }
        if (amplitude <= 0.0) return 0.0

        val effectiveThrottle = when {
            !loadOnlyProgram -> throttle
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

internal data class EngineSampleProgram(
    val layers: List<SampleLayerSpec>,
    val effects: List<SampleEffectSpec> = emptyList(),
    val throttleOutputGainDb: AutomationCurve? = null,
    val supportsLoadOnlyProgram: Boolean = true,
)

/** Identifies the one validated external WAV pack that may satisfy a profile. */
internal data class EngineAudioPackRequirement(
    val packId: String,
    val packVersion: Int,
    val manifestSha256: String,
) {
    init {
        require(BydAudioPackManifest.isValidPackId(packId)) { "Invalid audio pack id" }
        require(packVersion > 0) { "Audio pack version must be positive" }
        require(BydAudioPackManifest.isSha256(manifestSha256)) { "Invalid audio pack manifest hash" }
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
    /** Donor-car final drive used only to couple road speed to the synthetic combustion sound. */
    val soundFinalDriveRatio: Double = 1.0,
    /** Donor driven-wheel radius, separate from BYD EV propulsion physics. */
    val soundDrivenWheelRadiusMeters: Double = 0.347,
    /** Keeps the original three hand-calibrated equal-speed-band profiles bit-for-behavior compatible. */
    val usesLegacyEvenSpeedBandGearing: Boolean = true,
    val upshiftDurationSeconds: Double,
    val downshiftDurationSeconds: Double,
    val cabinProgram: EngineSampleProgram,
    val exteriorProgram: EngineSampleProgram? = null,
    /** Null keeps legacy profiles in APK assets; new profiles can require a separately installed pack. */
    val audioPackRequirement: EngineAudioPackRequirement? = null,
    /** Full-event FMOD NRT atlas used instead of the legacy per-source renderer. */
    val atlasProgram: FullEventAtlasProgram? = null,
    /** Root-catalog descriptor. Its family runtime is verified and parsed only for playback. */
    val atlasRuntimeDescriptor: AtlasFamilyRuntimeDescriptor? = null,
    /** Donor-car controls used only by full-event atlas effects. */
    val atlasAudioPhysics: AtlasCarAudioPhysics? = null,
) {
    val isAtlasProfile: Boolean get() = atlasProgram != null || atlasRuntimeDescriptor != null
    val layers: List<SampleLayerSpec> = cabinProgram.layers
    val effects: List<SampleEffectSpec> = cabinProgram.effects
    val throttleOutputGainDb: AutomationCurve? = cabinProgram.throttleOutputGainDb
    val supportsLoadOnlyProgram: Boolean = cabinProgram.supportsLoadOnlyProgram
    val hasExteriorProgram: Boolean = exteriorProgram != null
    val hasTurboSounds: Boolean = hasTurboSounds(EngineSoundPerspective.CABIN)
    val requiredAssets: Set<String> = linkedSetOf<String>().apply {
        layers.mapTo(this) { it.assetName }
        effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun resolvedPerspective(perspective: EngineSoundPerspective): EngineSoundPerspective {
        return if (perspective == EngineSoundPerspective.EXTERIOR && exteriorProgram == null) {
            EngineSoundPerspective.CABIN
        } else {
            perspective
        }
    }

    fun program(perspective: EngineSoundPerspective): EngineSampleProgram {
        return if (resolvedPerspective(perspective) == EngineSoundPerspective.EXTERIOR) {
            requireNotNull(exteriorProgram)
        } else {
            cabinProgram
        }
    }

    fun hasTurboSounds(perspective: EngineSoundPerspective): Boolean {
        val hasTurboEvent = program(perspective).effects.any { effect -> effect.trigger.isTurboSound() }
        // Some banks retain a dormant turbo event even for naturally aspirated cars. Atlas cars
        // expose the dashboard control only when the donor's physical TURBO_n data exists too.
        return if (isAtlasProfile) {
            hasTurboEvent && atlasAudioPhysics?.turbos?.isNotEmpty() == true
        } else {
            hasTurboEvent
        }
    }

    fun appliesLoadOnlyProgram(
        loadOnlyProgram: Boolean,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Boolean {
        return program(perspective).supportsLoadOnlyProgram && loadOnlyProgram
    }

    fun resolvedPrimaryLayerSource(
        source: PrimaryEngineLayerSource,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): PrimaryEngineLayerSource {
        if (isAtlasProfile) return source
        val program = program(perspective)
        val hasCoastLayers = program.layers.any { it.role == SampleLayerRole.COAST }
        return if (source != PrimaryEngineLayerSource.LOAD && program.supportsLoadOnlyProgram && hasCoastLayers) {
            source
        } else {
            PrimaryEngineLayerSource.LOAD
        }
    }

    fun supportsPrimaryLayerSource(
        source: PrimaryEngineLayerSource,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Boolean = resolvedPrimaryLayerSource(source, perspective) == source

    fun loopLayersForLoad(
        loadOnlyProgram: Boolean,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): List<SampleLayerSpec> {
        val program = program(perspective)
        if (appliesLoadOnlyProgram(loadOnlyProgram, perspective)) {
            return program.layers.filter { layer -> layer.role.isIncludedIn(PrimaryEngineLayerSource.LOAD) }
        }
        return program.layers
    }

    fun requiredAssetsForLoad(
        loadOnlyProgram: Boolean,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Set<String> = linkedSetOf<String>().apply {
        val program = program(perspective)
        loopLayersForLoad(loadOnlyProgram, perspective).mapTo(this) { it.assetName }
        program.effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun loopLayersForPrimarySource(
        source: PrimaryEngineLayerSource,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): List<SampleLayerSpec> {
        val program = program(perspective)
        val resolvedSource = resolvedPrimaryLayerSource(source, perspective)
        return program.layers.filter { layer -> layer.role.isIncludedIn(resolvedSource) }
    }

    fun requiredAssetsForPrimarySource(
        source: PrimaryEngineLayerSource,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Set<String> = linkedSetOf<String>().apply {
        val program = program(perspective)
        loopLayersForPrimarySource(source, perspective).mapTo(this) { it.assetName }
        program.effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun requiredAssets(perspective: EngineSoundPerspective): Set<String> = linkedSetOf<String>().apply {
        val program = program(perspective)
        program.layers.mapTo(this) { layer -> layer.assetName }
        program.effects.forEach { effect -> addAll(effect.allAssetNames) }
    }

    fun requiredExternalAssetPaths(): Set<String> = linkedSetOf<String>().apply {
        atlasProgram?.requiredShardNames?.forEach { shardName ->
            add("sample_engine/$assetDirectory/$shardName")
        }
        if (isAtlasProfile) return@apply
        EngineSoundPerspective.entries.forEach { perspective ->
            requiredAssets(perspective).forEach { assetName ->
                add("sample_engine/$assetDirectory/$assetName")
            }
        }
    }

    fun outputGainAt(
        throttle: Double,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Double = 10.0.pow(
        (program(perspective).throttleOutputGainDb?.valueAt(throttle.coerceIn(0.0, 1.0)) ?: 0.0) / 20.0,
    )

    fun outputGainForPrimarySource(
        liveThrottle: Double,
        loadOnlyProgram: Boolean,
        primaryLayerSource: PrimaryEngineLayerSource,
        perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    ): Double {
        val outputThrottle = if (appliesLoadOnlyProgram(loadOnlyProgram, perspective)) {
            when (resolvedPrimaryLayerSource(primaryLayerSource, perspective)) {
                PrimaryEngineLayerSource.LOAD -> 1.0
                PrimaryEngineLayerSource.COAST -> 0.0
                PrimaryEngineLayerSource.FMOD_MIX -> liveThrottle
            }
        } else {
            liveThrottle
        }

        return outputGainAt(outputThrottle, perspective)
    }
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
    private val bundled = listOf(
        default,
        lamborghiniAventadorSvProfile(),
        nissanSkylineR34Profile(),
    )

    @Volatile
    private var external: List<EngineSampleProfile> = emptyList()

    @Volatile
    private var initialized = false

    @Volatile
    private var atlasRuntimeLoader: AtlasFamilyRuntimeLoader? = null

    private val resolvedAtlasProfiles = CurrentAtlasResolvedProfileCache()

    val all: List<EngineSampleProfile> get() = bundled + external
    val maximumSupportedRpm: Double get() = all.maxOf { it.maximumRpm }

    fun initialize(context: android.content.Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val loadedCatalog = ExternalCarCatalogLoader.loadIfPresent(context)
            val loaded = loadedCatalog?.profiles.orEmpty()
            val bundledIds = bundled.mapTo(hashSetOf()) { it.id }
            require(loaded.none { it.id in bundledIds }) { "External car catalog collides with a bundled profile" }
            external = loaded
            atlasRuntimeLoader = loadedCatalog?.let { AtlasFamilyRuntimeLoader(context.applicationContext, it.families) }
            resolvedAtlasProfiles.clear()
            initialized = true
        }
    }

    fun find(id: String?): EngineSampleProfile {
        if (id == null) return default
        return resolvedAtlasProfiles.find(id) ?: all.firstOrNull { it.id == id } ?: default
    }

    fun resolveForPlayback(profile: EngineSampleProfile): EngineSampleProfile {
        val descriptor = profile.atlasRuntimeDescriptor ?: return profile
        val atlas = requireNotNull(atlasRuntimeLoader) { "Atlas runtime loader is unavailable" }.load(descriptor)
        return profile.copy(
            atlasProgram = atlas,
            cabinProgram = ExternalCarCatalogParser.sampleProgramFor(atlas, EngineSoundPerspective.CABIN),
            exteriorProgram = ExternalCarCatalogParser.sampleProgramFor(atlas, EngineSoundPerspective.EXTERIOR),
        ).also(resolvedAtlasProfiles::replace)
    }

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
