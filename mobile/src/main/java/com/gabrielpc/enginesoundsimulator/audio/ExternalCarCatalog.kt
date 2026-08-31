package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import java.io.FileNotFoundException
import java.security.MessageDigest

internal data class ExternalCarCatalog(
    val profiles: List<EngineSampleProfile>,
    val families: Map<String, AtlasFamilyRuntimeDescriptor>,
)

internal data class AtlasFamilyRuntimeDescriptor(
    val id: String,
    val assetDirectory: String,
    val requirement: EngineAudioPackRequirement,
    val runtimeAssetName: String,
    val runtimeBytes: Long,
    val runtimeSha256: String,
    val eagerCapabilities: AtlasEagerCapabilities,
)

internal data class AtlasEagerCapabilities(
    val perspectives: Set<EngineSoundPerspective>,
    val triggersByPerspective: Map<EngineSoundPerspective, Set<SampleEffectTrigger>>,
) {
    fun program(familyId: String, perspective: EngineSoundPerspective): EngineSampleProgram? {
        if (perspective !in perspectives) return null
        val triggers = requireNotNull(triggersByPerspective[perspective])
        return EngineSampleProgram(
            layers = listOf(
                SampleLayerSpec(FullEventAtlasRenderer.LOAD_TRACK_ID, "atlas://$familyId/load", SampleLayerRole.LOAD, 0.0, 20_000.0, applyIdleGainBoost = false),
                SampleLayerSpec(FullEventAtlasRenderer.COAST_TRACK_ID, "atlas://$familyId/coast", SampleLayerRole.COAST, 0.0, 20_000.0, applyIdleGainBoost = false),
            ),
            effects = triggers.map { trigger ->
                SampleEffectSpec("atlas_eager_${trigger.name}", ExternalCarCatalogParser.controlFor(trigger), "atlas://$familyId/effects", trigger)
            },
            supportsLoadOnlyProgram = true,
        )
    }
}

internal object ExternalCarCatalogParser {
    fun parse(bytes: ByteArray): ExternalCarCatalog {
        require(bytes.size <= MAXIMUM_CATALOG_BYTES) { "Car catalog is too large" }
        val root = AtlasRuntimeJson.parse(bytes).objectValues("catalog")
        require(root.keys == setOf("schema", "catalogVersion", "cars", "families")) {
            "Catalog fields do not match byd-car-atlas-catalog-v1"
        }
        require(root.getValue("schema").stringValue("catalog.schema") == SCHEMA)
        require(root.getValue("catalogVersion").intValue("catalog.catalogVersion") == VERSION)
        val families = root.getValue("families").arrayValues("catalog.families")
            .mapIndexed(::parseFamily)
        require(families.map { it.id }.distinct().size == families.size) { "Catalog has duplicate family ids" }
        val familyById = families.associateBy(Family::id)
        val profiles = root.getValue("cars").arrayValues("catalog.cars").mapIndexed { index, value ->
            parseCar(index, value, familyById)
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Catalog has duplicate car ids" }

        return ExternalCarCatalog(profiles, familyById.mapValues { it.value.descriptor })
    }

    private fun parseFamily(index: Int, value: AtlasJsonValue): Family {
        val label = "catalog.families[$index]"
        val values = value.objectValues(label)
        require(values.keys == setOf("id", "assetDirectory", "packRequirement", "runtimeAssetName", "runtimeBytes", "runtimeSha256", "eagerCapabilities")) {
            "$label fields do not match the catalog family contract"
        }
        val id = values.getValue("id").stringValue("$label.id")
        val assetDirectory = values.getValue("assetDirectory").stringValue("$label.assetDirectory")
        require(SAFE_ID.matches(id) && SAFE_ID.matches(assetDirectory)) { "Catalog family id is unsafe" }
        val requirementValues = values.getValue("packRequirement").objectValues("$label.packRequirement")
        require(requirementValues.keys == setOf("packId", "packVersion", "manifestSha256"))
        val requirement = EngineAudioPackRequirement(
            packId = requirementValues.getValue("packId").stringValue("$label.packRequirement.packId"),
            packVersion = requirementValues.getValue("packVersion").intValue("$label.packRequirement.packVersion"),
            manifestSha256 = requirementValues.getValue("manifestSha256")
                .stringValue("$label.packRequirement.manifestSha256"),
        )
        val runtimeAssetName = values.getValue("runtimeAssetName").stringValue("$label.runtimeAssetName")
        require(SAFE_RUNTIME_ASSET.matches(runtimeAssetName)) { "Catalog family runtime path is unsafe" }
        val runtimeBytes = values.getValue("runtimeBytes").longValue("$label.runtimeBytes")
        require(runtimeBytes in 1..MAXIMUM_RUNTIME_BYTES)
        val runtimeSha256 = values.getValue("runtimeSha256").stringValue("$label.runtimeSha256")
        require(BydAudioPackManifest.isSha256(runtimeSha256))
        return Family(AtlasFamilyRuntimeDescriptor(id, assetDirectory, requirement, runtimeAssetName, runtimeBytes, runtimeSha256,
            parseEagerCapabilities(values.getValue("eagerCapabilities"), "$label.eagerCapabilities")))
    }

    private fun parseCar(
        index: Int,
        value: AtlasJsonValue,
        families: Map<String, Family>,
    ): EngineSampleProfile {
        val label = "catalog.cars[$index]"
        val values = value.objectValues(label)
        require(values.keys == setOf(
            "id",
            "displayName",
            "audioProgramFamilyId",
            "previewAssetName",
            "physics",
            "specifications",
        )) { "$label fields do not match the catalog car contract" }
        val id = values.getValue("id").stringValue("$label.id")
        require(SAFE_ID.matches(id)) { "Catalog car id is unsafe" }
        val displayName = values.getValue("displayName").stringValue("$label.displayName").trim()
        require(displayName.isNotEmpty() && displayName.length <= 120) { "Catalog car display name is invalid" }
        val preview = values.getValue("previewAssetName").stringValue("$label.previewAssetName")
        require(SAFE_PREVIEW.matches(preview)) { "Catalog preview path is unsafe" }
        val familyId = values.getValue("audioProgramFamilyId").stringValue("$label.audioProgramFamilyId")
        val family = families[familyId] ?: throw IllegalArgumentException("Catalog car references missing family $familyId")
        val physics = values.getValue("physics").objectValues("$label.physics")
        require(physics.keys == PHYSICS_KEYS) { "$label physics fields do not match the runtime contract" }
        // Parse the provenance metadata even though the current dashboard does not render it yet.
        parseSpecifications(values.getValue("specifications"), "$label.specifications")

        val minimumRpm = physics.number("minimumRpm", label)
        val maximumRpm = physics.number("maximumRpm", label)
        val idleRpm = physics.number("idleRpm", label)
        val redlineRpm = physics.number("redlineRpm", label)
        val limiterRpm = physics.number("limiterRpm", label)
        val upshiftRpm = physics.number("upshiftRpm", label)
        val gears = physics.getValue("gearRatios").arrayValues("$label.physics.gearRatios")
            .mapIndexed { gearIndex, item -> item.numberValue("$label.physics.gearRatios[$gearIndex]") }
        require(minimumRpm == 0.0 && idleRpm > minimumRpm && maximumRpm >= limiterRpm)
        require(redlineRpm in idleRpm..maximumRpm && limiterRpm in redlineRpm..maximumRpm)
        require(upshiftRpm in idleRpm..limiterRpm)
        require(gears.size >= 2 && gears.all { it > 0.0 } && gears.zipWithNext().all { (left, right) -> left > right }) {
            "Catalog gear ratios are invalid"
        }
        val soundFinalDriveRatio = physics.number("soundFinalDriveRatio", label)
        val soundDrivenWheelRadiusMeters = physics.number("soundDrivenWheelRadiusMeters", label)
        require(soundFinalDriveRatio > 0.0 && soundDrivenWheelRadiusMeters > 0.0) {
            "Catalog donor drivetrain geometry is invalid"
        }
        val upshiftDurationSeconds = physics.number("upshiftDurationSeconds", label)
        val downshiftDurationSeconds = physics.number("downshiftDurationSeconds", label)
        require(upshiftDurationSeconds in 0.01..2.0 && downshiftDurationSeconds in 0.01..2.0) {
            "Catalog shift duration is invalid"
        }
        val atlasAudioPhysics = parseAtlasAudioPhysics(physics, label)
        return EngineSampleProfile(
            id = id,
            displayName = displayName,
            assetDirectory = family.assetDirectory,
            previewAssetName = preview,
            outputSampleRate = 48_000,
            playbackSampleRate = 48_000,
            minimumRpm = minimumRpm,
            maximumRpm = maximumRpm,
            idleRpm = idleRpm,
            redlineRpm = redlineRpm,
            limiterRpm = limiterRpm,
            upshiftRpm = upshiftRpm,
            gearRatios = gears,
            soundFinalDriveRatio = soundFinalDriveRatio,
            soundDrivenWheelRadiusMeters = soundDrivenWheelRadiusMeters,
            usesLegacyEvenSpeedBandGearing = false,
            upshiftDurationSeconds = upshiftDurationSeconds,
            downshiftDurationSeconds = downshiftDurationSeconds,
            cabinProgram = requireNotNull(family.descriptor.eagerCapabilities.program(family.id, EngineSoundPerspective.CABIN)),
            exteriorProgram = family.descriptor.eagerCapabilities.program(family.id, EngineSoundPerspective.EXTERIOR),
            audioPackRequirement = family.requirement,
            atlasRuntimeDescriptor = family.descriptor,
            atlasAudioPhysics = atlasAudioPhysics,
        )
    }

    internal fun parseAtlasAudioPhysics(
        physics: Map<String, AtlasJsonValue>,
        carLabel: String,
    ): AtlasCarAudioPhysics {
        require(physics.keys == PHYSICS_KEYS) { "$carLabel physics fields do not match the runtime contract" }
        val turboLabel = "$carLabel.physics.turbos"
        val turbos = physics.getValue("turbos").arrayValues(turboLabel).mapIndexed { index, item ->
            val label = "$turboLabel[$index]"
            val values = item.objectValues(label)
            require(values.keys == TURBO_KEYS) { "$label fields do not match the turbo contract" }
            AtlasTurboStage(
                index = values.getValue("index").intValue("$label.index"),
                lagUp = values.getValue("lagUp").numberValue("$label.lagUp"),
                lagDown = values.getValue("lagDown").numberValue("$label.lagDown"),
                maximumBoost = values.getValue("maximumBoost").numberValue("$label.maximumBoost"),
                wastegate = values.getValue("wastegate").numberValue("$label.wastegate"),
                referenceRpm = values.getValue("referenceRpm").numberValue("$label.referenceRpm"),
                gamma = values.getValue("gamma").numberValue("$label.gamma"),
                bovPressureThreshold = values.getValue("bovPressureThreshold")
                    .numberValue("$label.bovPressureThreshold"),
            ).also { stage ->
                require(stage.index == index)
                require(stage.lagUp >= 0.0 && stage.lagDown >= 0.0)
                require(stage.maximumBoost > 0.0 && stage.wastegate >= 0.0)
                require(stage.referenceRpm > 0.0 && stage.gamma > 0.0 && stage.bovPressureThreshold >= 0.0)
            }
        }
        val normalizationLabel = "$carLabel.physics.turboBoostNormalization"
        val normalization = physics.getValue("turboBoostNormalization").objectValues(normalizationLabel)
        require(normalization.keys == setOf("kind", "divisor", "minimum", "maximum"))
        require(normalization.getValue("kind").stringValue("$normalizationLabel.kind") ==
            "TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST")
        val divisor = normalization.getValue("divisor").numberValue("$normalizationLabel.divisor")
        require(normalization.getValue("minimum").numberValue("$normalizationLabel.minimum") == 0.0)
        require(normalization.getValue("maximum").numberValue("$normalizationLabel.maximum") == 1.0)
        require(kotlin.math.abs(divisor - turbos.sumOf(AtlasTurboStage::maximumBoost)) < 1.0e-9)
        require((turbos.isEmpty() && divisor == 0.0) || (turbos.isNotEmpty() && divisor > 0.0))

        val backfireLabel = "$carLabel.physics.backfire"
        val backfireValues = physics.getValue("backfire").objectValues(backfireLabel)
        require(backfireValues.keys == BACKFIRE_KEYS)
        val backfire = AtlasBackfirePhysics(
            maximumGas = backfireValues.getValue("maximumGas").numberValue("$backfireLabel.maximumGas"),
            minimumRpm = backfireValues.getValue("minimumRpm").numberValue("$backfireLabel.minimumRpm"),
            maximumRpm = backfireValues.getValue("maximumRpm").numberValue("$backfireLabel.maximumRpm"),
            triggerGas = backfireValues.getValue("triggerGas").numberValue("$backfireLabel.triggerGas"),
            minimumIntentThrottle = backfireValues.getValue("minimumIntentThrottle")
                .numberValue("$backfireLabel.minimumIntentThrottle"),
            minimumIntentSeconds = backfireValues.getValue("minimumIntentSeconds")
                .numberValue("$backfireLabel.minimumIntentSeconds"),
        )
        require(backfire.maximumGas in 0.0..0.3)
        require(backfire.minimumRpm >= 0.0 && backfire.maximumRpm > backfire.minimumRpm)
        require(backfire.triggerGas in 0.0..1.0)
        require(backfire.minimumIntentThrottle == 0.4 && backfire.minimumIntentSeconds == 1.0)

        val limiterFrequencyHz = physics.number("limiterFrequencyHz", carLabel)
        require(limiterFrequencyHz > 0.0)
        val drivetrainLabel = "$carLabel.physics.drivetrainSpeedControl"
        val drivetrain = physics.getValue("drivetrainSpeedControl").objectValues(drivetrainLabel)
        require(drivetrain.keys == setOf("parameterName", "unit", "formula", "signed"))
        require(drivetrain.getValue("parameterName").stringValue("$drivetrainLabel.parameterName") ==
            "drivetrain_speed")
        require(drivetrain.getValue("unit").stringValue("$drivetrainLabel.unit") ==
            "drivenWheelRadiansPerSecond")
        require(drivetrain.getValue("formula").stringValue("$drivetrainLabel.formula") ==
            "signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters")
        require(drivetrain.getValue("signed").booleanValue("$drivetrainLabel.signed"))

        return AtlasCarAudioPhysics(turbos, divisor, backfire, limiterFrequencyHz)
    }

    private fun parseSpecifications(value: AtlasJsonValue, label: String) {
        val values = value.objectValues(label)
        require(values.getValue("assettoCorsaCarId").stringValue("$label.assettoCorsaCarId").isNotBlank())
        listOf("brand", "year", "class").forEach { key ->
            values[key]?.stringValue("$label.$key")
        }
    }

    private fun parseEagerCapabilities(value: AtlasJsonValue, label: String): AtlasEagerCapabilities {
        val values = value.objectValues(label)
        require(values.keys == setOf("perspectives", "effectControls"))
        val perspectives = values.getValue("perspectives").arrayValues("$label.perspectives")
            .mapIndexed { index, item ->
                when (item.stringValue("$label.perspectives[$index]")) {
                    "cabin" -> EngineSoundPerspective.CABIN
                    "exterior" -> EngineSoundPerspective.EXTERIOR
                    else -> throw IllegalArgumentException("$label has an unsupported perspective")
                }
            }
            .toSet()
        require(perspectives.isNotEmpty())
        val controls = values.getValue("effectControls").objectValues("$label.effectControls")
        require(controls.keys == EngineSoundPerspective.entries.mapTo(linkedSetOf()) { it.name.lowercase() })
        val triggers = EngineSoundPerspective.entries.associateWith { perspective ->
            val control = controls.getValue(perspective.name.lowercase()).objectValues("$label.effectControls.${perspective.name.lowercase()}")
            require(control.keys == setOf("hasTurboEvent", "runtimeTriggers"))
            val parsed = control.getValue("runtimeTriggers").arrayValues("$label.effectControls.${perspective.name.lowercase()}.runtimeTriggers")
                .mapIndexed { index, item -> SampleEffectTrigger.valueOf(item.stringValue("$label.effectControls.${perspective.name.lowercase()}.runtimeTriggers[$index]")) }
                .toSet()
            require(control.getValue("hasTurboEvent").booleanValue("$label.effectControls.${perspective.name.lowercase()}.hasTurboEvent") ==
                parsed.any(SampleEffectTrigger::isTurboSound))
            parsed
        }
        return AtlasEagerCapabilities(perspectives, triggers)
    }

    internal fun sampleProgramFor(atlas: FullEventAtlasProgram, perspective: EngineSoundPerspective): EngineSampleProgram {
        return atlas.sampleProgram(perspective)
    }

    internal fun controlFor(trigger: SampleEffectTrigger): SampleEffectControlSpec = trigger.control()

    private fun FullEventAtlasProgram.sampleProgram(perspective: EngineSoundPerspective): EngineSampleProgram {
        val perspectiveProgram = perspective(perspective)
        val rangeStart = perspectiveProgram.rpmAxis.first()
        val rangeEnd = perspectiveProgram.rpmAxis.last()
        val layers = listOf(
            SampleLayerSpec(
                id = FullEventAtlasRenderer.LOAD_TRACK_ID,
                assetName = "atlas://${id}/${perspective.name.lowercase()}/load",
                role = SampleLayerRole.LOAD,
                startRpm = rangeStart,
                endRpm = rangeEnd,
                applyIdleGainBoost = false,
            ),
            SampleLayerSpec(
                id = FullEventAtlasRenderer.COAST_TRACK_ID,
                assetName = "atlas://${id}/${perspective.name.lowercase()}/coast",
                role = SampleLayerRole.COAST,
                startRpm = rangeStart,
                endRpm = rangeEnd,
                applyIdleGainBoost = false,
            ),
        )
        val effects = effects.filter { event -> perspective in event.perspectives }.flatMap { event ->
            event.runtimeTriggers.map { runtimeTrigger ->
                val trigger = requireNotNull(SampleEffectTrigger.entries.firstOrNull { it.name == runtimeTrigger.name }) {
                    "Unsupported core atlas trigger ${runtimeTrigger.name}"
                }
                SampleEffectSpec(
                    id = "atlas_effect_${event.eventSuffix}_${trigger.name.lowercase()}",
                    control = trigger.control(),
                    assetName = "atlas://${id}/effects/${event.eventSuffix}",
                    trigger = trigger,
                )
            }
        }

        return EngineSampleProgram(layers = layers, effects = effects, supportsLoadOnlyProgram = true)
    }

    internal fun SampleEffectTrigger.control(): SampleEffectControlSpec = when (this) {
        SampleEffectTrigger.SHIFT_UP,
        SampleEffectTrigger.SHIFT_DOWN,
        SampleEffectTrigger.SHIFT_REJECTED,
        -> SampleEffectControls.gearChanges
        SampleEffectTrigger.THROTTLE_LIFT -> SampleEffectControls.exhaustOverrun
        SampleEffectTrigger.TRANSMISSION_LOOP,
        SampleEffectTrigger.TRANSMISSION_PULSE,
        -> SampleEffectControls.transmission
        SampleEffectTrigger.TURBO_LOOP,
        SampleEffectTrigger.TURBO_FLUTTER,
        SampleEffectTrigger.TURBO_DUMP,
        -> SampleEffectControls.turbo
        SampleEffectTrigger.LIMITER_LOOP,
        SampleEffectTrigger.LIMITER_PULSE,
        -> SampleEffectControls.limiter
        SampleEffectTrigger.TRACTION_LIMIT,
        SampleEffectTrigger.TRACTION_PULSE,
        -> SampleEffectControls.tractionLimit
        SampleEffectTrigger.PARAMETER_PLACEMENT_ENTRY,
        SampleEffectTrigger.ENGINE_EVENT_START,
        SampleEffectTrigger.ENGINE_START,
        -> SampleEffectControls.engineLifecycle
    }

    private data class Family(val descriptor: AtlasFamilyRuntimeDescriptor) {
        val id: String get() = descriptor.id
        val assetDirectory: String get() = descriptor.assetDirectory
        val requirement: EngineAudioPackRequirement get() = descriptor.requirement
    }

    private fun Map<String, AtlasJsonValue>.number(name: String, carLabel: String): Double =
        getValue(name).numberValue("$carLabel.physics.$name")

    private const val SCHEMA = "byd-car-atlas-catalog-v2"
    private const val VERSION = 2
    private const val MAXIMUM_CATALOG_BYTES = 512 * 1024
    private const val MAXIMUM_RUNTIME_BYTES = 4L * 1024L * 1024L
    private val PHYSICS_KEYS = setOf(
        "minimumRpm",
        "maximumRpm",
        "idleRpm",
        "redlineRpm",
        "limiterRpm",
        "upshiftRpm",
        "gearRatios",
        "upshiftDurationSeconds",
        "downshiftDurationSeconds",
        "soundFinalDriveRatio",
        "soundDrivenWheelRadiusMeters",
        "turbos",
        "turboBoostNormalization",
        "backfire",
        "limiterFrequencyHz",
        "drivetrainSpeedControl",
    )
    private val TURBO_KEYS = setOf(
        "index",
        "lagUp",
        "lagDown",
        "maximumBoost",
        "wastegate",
        "referenceRpm",
        "gamma",
        "bovPressureThreshold",
    )
    private val BACKFIRE_KEYS = setOf(
        "maximumGas",
        "minimumRpm",
        "maximumRpm",
        "triggerGas",
        "minimumIntentThrottle",
        "minimumIntentSeconds",
    )
    private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
    private val SAFE_RUNTIME_ASSET = Regex("^families/[a-z0-9][a-z0-9._-]{0,160}\\.json$")
    private val SAFE_PREVIEW = Regex("^car_previews/[a-z0-9][a-z0-9._-]{0,110}\\.(jpg|png|webp)$")
}

internal object ExternalCarCatalogLoader {
    const val ASSET_PATH = "car_catalog/atlas-catalog-v2.json"

    fun loadIfPresent(context: Context): ExternalCarCatalog? = try {
        val bytes = context.assets.open(ASSET_PATH).use { input -> input.readBounded(512L * 1024L) }
        ExternalCarCatalogParser.parse(bytes)
    } catch (_: FileNotFoundException) {
        null
    }
}
