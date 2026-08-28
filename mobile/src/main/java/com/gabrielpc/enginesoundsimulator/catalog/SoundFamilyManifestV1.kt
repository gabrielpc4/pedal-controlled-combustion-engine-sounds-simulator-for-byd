package com.gabrielpc.enginesoundsimulator.catalog

import java.io.File
import java.util.Locale
import kotlin.math.pow

internal enum class PackTrackRole(val loops: Boolean, val requiresRootRpm: Boolean) {
    IDLE(true, true),
    COAST(true, true),
    TEXTURE(true, true),
    INTAKE(true, true),
    EXHAUST(true, true),
    TURBO(true, false),
    SPOOL(true, false),
    BOV(false, false),
    TURBO_TRANSIENT(false, false),
    TRANSMISSION(true, false),
    LIMITER(false, false),
    SHIFT_UP(false, false),
    SHIFT_DOWN(false, false),
    OVERRUN(false, false),
    POP(false, false),
    BANG(false, false),
    CRACK(false, false),
    // Source-bound pitchTreatment decides whether an engine transient uses RPM/root or baked 1x.
    ENGINE_TRANSIENT(false, false),
    /** Event-start crank/ignition one-shot captured from engine_int; optional per family. */
    ENGINE_START(false, false),
}

internal data class CurvePointV1(val input: Double, val output: Double)

internal enum class PackTrackPitchMode {
    AUTO_PITCH_RPM_RATIO,
    AUTHORED_PROPERTY_ONE_RELATIVE_RATE,
}

internal enum class PackTrackPitchCurveInterpolation {
    NONE,
    CLAMPED_LINEAR,
}

internal data class SoundTrackManifestV1(
    val id: String,
    val role: PackTrackRole,
    val path: String,
    val flacSha256: String,
    val pcmSha256: String,
    val frameCount: Long,
    val rootRpm: Double?,
    /** Inclusive first frame of the authored loop, if this track loops. */
    val loopStartFrame: Long?,
    /** Exclusive end frame of the authored loop, if this track loops. */
    val loopEndFrameExclusive: Long?,
    val gainDb: Double,
    val peakDbfs: Double,
    val rpmCurve: List<CurvePointV1>,
    val gainCurve: List<CurvePointV1>,
    val triggers: List<String>,
    /** Exact FMOD software-channel priority (0 is highest, 256 is lowest). */
    val softwareChannelPriority: Int = 64,
    /** Schema-v2 pitch source. Property-one curves replace, never multiply, RPM/root AutoPitch. */
    val pitchMode: PackTrackPitchMode = PackTrackPitchMode.AUTO_PITCH_RPM_RATIO,
    val pitchCurve: List<CurvePointV1> = emptyList(),
    val pitchCurveInterpolation: PackTrackPitchCurveInterpolation =
        PackTrackPitchCurveInterpolation.NONE,
) {
    val decodedBytes: Long
        get() = Math.multiplyExact(frameCount, AUDIO_BYTES_PER_FRAME)

    companion object {
        const val AUDIO_BYTES_PER_FRAME = 4L
    }
}

internal enum class PackOneShotTrigger {
    /** Schema-v1 compatibility only. */
    LIMITER,
    LIMITER_EVENT,
    SHIFT_UP, SHIFT_DOWN, THROTTLE_LIFT, BOV_LIFT, ENGINE_EVENT, TURBO_EVENT, ENGINE_START,
}

internal enum class PackOneShotPolicyKind {
    AC_BACKFIRE, BOV_LIFT,
    /** Schema-v1 compatibility only. */
    LIMITER,
    LIMITER_EVENT,
    SHIFT_UP, SHIFT_DOWN, ENGINE_START,
}

internal enum class PackOneShotPlayMode {
    NORMAL, SMART_RANDOM, SEQUENTIAL,
}

internal enum class PackOneShotSelectionMode {
    NORMAL, SELECT_ALL,
}

internal enum class PackOneShotGateControl {
    ENGINE_RPM, ACCELERATOR, SHIFT_STATE, BOOST, BOV, BOV_DECAY, DRIVETRAIN_SPEED, DECAY,
}

internal data class PackOneShotParameterGateV2(
    val control: PackOneShotGateControl,
    val minimum: Double,
    val maximum: Double,
    val includeMinimum: Boolean = true,
    val includeMaximum: Boolean = true,
)

internal enum class PackEngineEventArmingMode {
    EVENT_START_INSIDE_REQUIRED, FIXED_COMPILER_GEOMETRY_AT_EVENT_START,
}

internal enum class PackEngineEventInitiallyOutsideBehavior {
    DISABLED_UNTIL_EVENT_RESTART,
}

internal enum class PackEngineEventRearmMode {
    AFTER_ANY_GATE_EXIT, NONE,
}

internal enum class PackEngineEventOverlapMode {
    ALLOW_OVERLAP,
}

internal enum class PackEngineEventExitBehavior {
    LET_ACTIVE_VOICES_FINISH,
}

internal enum class PackEngineEventBoundary {
    MINIMUM, MAXIMUM,
}

internal enum class PackEngineEventDirection {
    INCREASING, DECREASING,
}

internal data class PackEngineEventEntryEdgeV2(
    val control: PackOneShotGateControl,
    val boundary: PackEngineEventBoundary,
    val direction: PackEngineEventDirection,
    val value: Double,
    val includeBoundary: Boolean,
)

internal data class PackEngineEventParameterRegionV2(
    val parameterGates: List<PackOneShotParameterGateV2>,
    val entryEdges: List<PackEngineEventEntryEdgeV2>,
    val triggerOnEventStartIfInside: Boolean,
)

internal data class PackEngineEventPolicyV2(
    val parameterRegions: List<PackEngineEventParameterRegionV2>,
    val armingMode: PackEngineEventArmingMode,
    val initiallyOutsideBehavior: PackEngineEventInitiallyOutsideBehavior?,
    val rearmMode: PackEngineEventRearmMode,
    val overlapMode: PackEngineEventOverlapMode,
    val exitBehavior: PackEngineEventExitBehavior,
    val coreProgram: Boolean,
    val auditionable: Boolean,
    val maximumDecodedOneShotFrames: Int,
    val laneCount: Int,
    val logicalVoiceLimit: Int,
    val softwareRealVoiceBudget: Int,
)

internal enum class PackLimiterProgramMode {
    PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT,
    PERSISTENT_DECAY_REGION_ONE_SHOT,
    PERSISTENT_DECAY_REGION_LOOP,
}

internal enum class PackLimiterSourceLifetime(val manifestValue: String) {
    ONE_SHOT("oneShot"),
    CONTINUOUS("continuous"),
}

internal data class PackLimiterDecayPlacementV2(
    val minimumSeconds: Double,
    val maximumSeconds: Double,
    val includeMinimum: Boolean,
    val includeMaximum: Boolean,
) {
    fun contains(seconds: Double): Boolean =
        seconds >= minimumSeconds && seconds <= maximumSeconds &&
            (includeMinimum || seconds != minimumSeconds) &&
            (includeMaximum || seconds != maximumSeconds)
}

internal data class PackLimiterTimelinePlacementV2(
    val lengthTicks: Int,
    val periodFramesAt48k: Int,
)

internal data class PackLimiterBakedModulatorV2(
    val guid: String,
    val ownerGuid: String,
)

/**
 * Strict executable-backed AC limiter contract. Constant strings are validated byte-for-byte by
 * the parser; only fields required by the allocation-free runtime are retained as primitives.
 */
internal data class PackLimiterEventPolicyV2(
    val programMode: PackLimiterProgramMode,
    val sourceLifetime: PackLimiterSourceLifetime,
    val decayGainCurve: List<CurvePointV1>,
    val decayPlacement: PackLimiterDecayPlacementV2?,
    val timelinePlacement: PackLimiterTimelinePlacementV2?,
    val maximumSimultaneousProgramTracks: Int?,
    val decodedOneShotLaneBound: Boolean,
    val bakedModulators: List<PackLimiterBakedModulatorV2>,
    val sourceVerificationPayloadSha256: String,
)

internal data class PackOneShotMemberV2(
    val nodeId: String,
    val weight: Double,
    val order: Int,
)

internal sealed interface PackOneShotNodeV2 {
    val id: String
    val triggerChance: Double
}

internal data class PackOneShotGroupNodeV2(
    override val id: String,
    override val triggerChance: Double,
    val playMode: PackOneShotPlayMode,
    val selectionMode: PackOneShotSelectionMode,
    val members: List<PackOneShotMemberV2>,
) : PackOneShotNodeV2

internal enum class PackZeroGainVirtualizationKind {
    NOT_APPLICABLE,
    EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE,
    ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO,
}

internal enum class PackZeroGainTransitionPolicy {
    IMMEDIATE_EXACT_ZERO,
    RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO,
}

internal enum class PackZeroGainTransitionPitch {
    LIVE_CURRENT_RPM_PITCH,
    AUTHORED_STATIC_BAKED_PITCH,
}

internal enum class PackZeroGainTransitionPhaseTreatment {
    RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET,
    APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET,
}

internal enum class PackEngineTransientReentryPolicy {
    CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
    NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER,
}

/** Compact, executable subset of one source-bound FMOD zero-output transition proof. */
internal data class PackZeroGainTransitionV2(
    val policy: PackZeroGainTransitionPolicy,
    val retainPreZeroGainWriterFrames: Int,
    val linearFadeWriterFrames: Int,
    val pitchDuringTransition: PackZeroGainTransitionPitch,
    val phaseTreatment: PackZeroGainTransitionPhaseTreatment,
    val restoreCapturePcmPhaseOffsetFrames: Double,
) {
    val exactZeroFromWriterFrame: Int = Math.addExact(
        retainPreZeroGainWriterFrames,
        linearFadeWriterFrames,
    )
}

/** Exact per-leaf FMOD logical/decode voice treatment after authored gain reaches zero. */
internal data class PackZeroGainVirtualizationV2(
    val kind: PackZeroGainVirtualizationKind,
    val phaseHoldLatencyWriterFrames: Int,
    val transition: PackZeroGainTransitionV2?,
) {
    companion object {
        val NOT_APPLICABLE = PackZeroGainVirtualizationV2(
            kind = PackZeroGainVirtualizationKind.NOT_APPLICABLE,
            phaseHoldLatencyWriterFrames = 0,
            transition = null,
        )
    }
}

private data class ParsedEngineTransientPitchTreatment(
    val zeroGainVirtualization: PackZeroGainVirtualizationV2,
    val reentryPolicy: PackEngineTransientReentryPolicy,
    val sourceVerificationPayloadSha256: String,
)

internal data class PackOneShotTrackNodeV2(
    override val id: String,
    override val triggerChance: Double,
    val trackId: String,
    val parameterGates: List<PackOneShotParameterGateV2>,
    val rpmCurve: List<CurvePointV1>,
    val gainCurve: List<CurvePointV1>,
    val liveVarispeed: Boolean,
    val rootRpm: Double?,
    val captureControlValues: List<PackOneShotControlValueV2> = emptyList(),
    val controlGainCurves: List<PackOneShotControlCurveV2> = emptyList(),
    val pitchAutomations: List<PackOneShotPitchAutomationV2> = emptyList(),
    val sourceVerificationPayloadSha256: String? = null,
    val zeroGainVirtualization: PackZeroGainVirtualizationV2 =
        PackZeroGainVirtualizationV2.NOT_APPLICABLE,
    val engineTransientReentryPolicy: PackEngineTransientReentryPolicy =
        PackEngineTransientReentryPolicy.CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
) : PackOneShotNodeV2

internal data class PackOneShotSilentNodeV2(
    override val id: String,
    override val triggerChance: Double,
    val sourceGuid: String,
    val resolvedRole: PackTrackRole,
    val sourceVerificationPayloadSha256: String,
) : PackOneShotNodeV2

internal data class PackOneShotControlValueV2(
    val control: PackOneShotGateControl,
    val value: Double,
)

internal data class PackOneShotControlCurveV2(
    val control: PackOneShotGateControl,
    val curve: List<CurvePointV1>,
)

internal data class PackOneShotPitchAutomationV2(
    val control: PackOneShotGateControl,
    val captureSemitones: Double,
    val playbackRateCurve: List<CurvePointV1>,
)

internal enum class PackTurboEventProgramMode {
    BOOST_RELEASE_REGION_ONE_SHOT,
    TIMELINE_PERIODIC_ONE_SHOT,
    PARAMETER_SHEET_EVENT_START_ONE_SHOT,
}

internal data class PackTurboEventPolicyV2(
    val programMode: PackTurboEventProgramMode,
    val placementMinimumBoost: Double?,
    val placementMaximumBoost: Double?,
    val includeMinimum: Boolean,
    val includeMaximum: Boolean,
    val timelineStartFrames: Long?,
    val timelinePeriodFrames: Long?,
    val coreProgram: Boolean,
)

internal data class PackOneShotProgramV2(
    val id: String,
    val trigger: PackOneShotTrigger,
    val capturedFromEventStart: Boolean,
    /** Must equal the priority of every track leaf reachable from this program. */
    val softwareChannelPriority: Int,
    val rootNodeIds: List<String>,
    val nodes: List<PackOneShotNodeV2>,
    val engineEventPolicy: PackEngineEventPolicyV2?,
    val limiterEventPolicy: PackLimiterEventPolicyV2? = null,
    val turboEventPolicy: PackTurboEventPolicyV2? = null,
)

internal data class PackOneShotTriggerPolicyV2(
    val kind: PackOneShotPolicyKind,
    val minimumRpm: Double,
    val maximumRpm: Double?,
    val armPedal: Double?,
    val firePedal: Double?,
    val armBoost: Double?,
    val initialPeakPedal: Double?,
    val initialArmPedal: Double?,
    val initialFirePedal: Double?,
    val minimumArmMs: Double,
    val cooldownMs: Double,
    val periodHz: Double?,
)

internal data class PackAssetManifestV1(
    val path: String,
    val sha256: String,
    val mediaType: String,
)

internal data class AuthoredDspProvenanceV2(
    val name: String,
    val version: Int,
    val gainDb: Double,
    val invert: Boolean,
    val treatment: String,
    val evidence: String,
)

internal data class CertifiedSilentSourceV2(
    val sourceGuid: String,
    val role: PackTrackRole,
    val verificationPayloadSha256: String,
)

internal data class TurboControllerMetadata(
    val section: String,
    val input: String,
    val combinator: String,
    val lut: List<CurvePointV1>,
    val filter: Double,
    val upLimit: Double,
    val downLimit: Double,
)

internal data class TurboControllerFileMetadata(
    val file: String,
    val sha256: String,
    val controllers: List<TurboControllerMetadata>,
)

internal data class TurboPhysicsUnitMetadata(
    val maximumBoost: Double,
    val wastegate: Double,
    val referenceRpm: Double,
    val gamma: Double,
    val lagUp: Double,
    val lagDown: Double,
    val controllerFile: String?,
)

internal data class TurboPhysicsMetadata(
    val bovPressureThreshold: Double,
    val turbos: List<TurboPhysicsUnitMetadata>,
) {
    companion object {
        val EMPTY = TurboPhysicsMetadata(0.0, emptyList())
    }
}

internal data class PhysicalThrottleMapMetadata(
    val points: List<CurvePointV1>,
) {
    companion object {
        val IDENTITY = PhysicalThrottleMapMetadata(
            listOf(CurvePointV1(0.0, 0.0), CurvePointV1(1.0, 1.0)),
        )
    }
}

/**
 * Authored automatic-shift gas assists evaluated before throttle.lut in AC's 3 ms engine step.
 *
 * [autoBlipProfile] deliberately preserves insertion order. Nineteen official cars have a
 * POINT_2 earlier than POINT_1; AC scans the stored points in authored order and separately ends
 * the program at POINT_2, so sorting this curve would change their behavior.
 */
internal data class EngineGasAssistMetadata(
    val autoShifterGasCutoffMs: Double,
    val engineCutoffMs: Double,
    val autoBlipElectronic: Boolean,
    val autoBlipClutchGateExclusive: Double,
    val autoBlipProfile: List<CurvePointV1>,
    val autoBlipEndTimeMs: Double,
) {
    companion object {
        const val ENABLE_MODE = "ELECTRONIC_OR_AUTOCLUTCH"
        const val EVALUATOR = "AUTHORED_ORDER_FIRST_UPPER_BOUND_LINEAR"
        const val COMBINER = "MAX_WITH_POST_ASSIST_PEDAL"
        const val PROCESSING_ORDER =
            "AUTOBLIP_THEN_AUTO_SHIFTER_CUT_THEN_ENGINE_CUTOFF_THEN_THROTTLE_MAP_THEN_LIMITER_CUT"

        val EMPTY = EngineGasAssistMetadata(
            autoShifterGasCutoffMs = 0.0,
            engineCutoffMs = 0.0,
            autoBlipElectronic = false,
            autoBlipClutchGateExclusive = 1.0 / Math.PI,
            autoBlipProfile = emptyList(),
            autoBlipEndTimeMs = 0.0,
        )
    }
}

internal data class HybridControllerFileMetadata(val file: String, val sha256: String)

internal data class HybridConfigMetadata(
    val file: String,
    val sha256: String,
    val maximumEnergyKjPerLap: Double,
    val dischargeTimeMs: Double,
    val hasButtonOverride: Boolean,
    val defaultController: Double,
    val heatTorquePercent: Double,
    val hasFrontMotors: Boolean,
    val frontDischargeTimeMs: Double,
    val controllerFiles: List<HybridControllerFileMetadata>,
)

internal data class AlternateGearOptionMetadata(val label: String, val ratio: Double)

internal data class AlternateGearSetMetadata(
    val file: String,
    val sha256: String,
    val options: List<AlternateGearOptionMetadata>,
)

internal data class CarEngineMetadata(
    val idleRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    val limiterHz: Double,
    val tachometerMaximumRpm: Double,
    val turboCount: Int,
    val hybrid: Boolean,
    val hybridConfig: HybridConfigMetadata?,
    val turboControllers: List<TurboControllerFileMetadata>,
    val turboPhysics: TurboPhysicsMetadata = TurboPhysicsMetadata.EMPTY,
    val throttleMap: PhysicalThrottleMapMetadata = PhysicalThrottleMapMetadata.IDENTITY,
)

internal data class CarGearboxMetadata(
    val traction: String,
    val forwardRatios: List<Double>,
    val reverseRatio: Double,
    val finalDrive: Double,
    val upshiftRpm: Double,
    /** Threshold while currently in the map's gear; values are calculated without hysteresis. */
    val downshiftLandingRpmByGear: Map<Int, Double>,
    val upshiftTimeMs: Double,
    val downshiftTimeMs: Double,
    val alternateGearSets: List<AlternateGearSetMetadata>,
    val engineGasAssist: EngineGasAssistMetadata = EngineGasAssistMetadata.EMPTY,
)

internal data class SoundFidelityMetadataV1(
    val sourceAudio: String,
    val layerIsolation: String,
    val rpmGainCurve: String,
    val effectVariants: String,
    val notes: List<String>,
)

internal data class ManifestCarV1(
    val id: String,
    val displayName: String,
    val brand: String,
    val previewPath: String?,
    val engine: CarEngineMetadata,
    val gearbox: CarGearboxMetadata,
    val oneShotTriggerPolicies: Map<String, PackOneShotTriggerPolicyV2>,
)

/** Closed first-party quirk vocabulary and its car-specific derivation rules. */
internal object OfficialCarQuirks {
    const val ALL_WHEEL_DRIVE = "allWheelDrive"
    const val HYBRID = "hybrid"
    const val GEAR_DEPENDENT_TURBO = "gearDependentTurboController"
    const val BMW_M3_E30_GRA_ADDITIONAL_DSP = "requiresBmwM3E30GraAdditionalDsp"
    const val AUTHORED_BOV_LANE_SILENT = "authoredBovLaneSilent"

    val supported: Set<String> = setOf(
        ALL_WHEEL_DRIVE,
        HYBRID,
        GEAR_DEPENDENT_TURBO,
        BMW_M3_E30_GRA_ADDITIONAL_DSP,
        AUTHORED_BOV_LANE_SILENT,
    )

    fun expectedFor(
        carId: String,
        engine: CarEngineMetadata,
        gearbox: CarGearboxMetadata,
    ): Set<String> = buildSet {
        if (gearbox.traction !in setOf("RWD", "FWD")) add(ALL_WHEEL_DRIVE)
        if (engine.hybrid) add(HYBRID)
        if (engine.turboControllers.isNotEmpty()) add(GEAR_DEPENDENT_TURBO)
        if (carId == "bmw_m3_e30_gra") add(BMW_M3_E30_GRA_ADDITIONAL_DSP)
        if (carId == "tatuusfa1") add(AUTHORED_BOV_LANE_SILENT)
    }
}

internal data class CoreEffectAvailability(
    val idle: Boolean,
    val coast: Boolean,
    val texture: Boolean,
    val intake: Boolean,
    val exhaust: Boolean,
    val turbo: Boolean,
    val spool: Boolean,
    val bov: Boolean,
    val transmission: Boolean,
    val limiter: Boolean,
    val shift: Boolean,
    val overrun: Boolean,
    val popsBangsCracks: Boolean,
    val engineStart: Boolean,
)

internal data class SoundFamilyManifestV1(
    val schemaVersion: Int,
    val familyId: String,
    val displayName: String,
    val memberCarIds: List<String>,
    val cars: List<ManifestCarV1>,
    val effects: CoreEffectAvailability,
    val quirks: Set<String>,
    val tracks: List<SoundTrackManifestV1>,
    val oneShotPrograms: List<PackOneShotProgramV2>,
    val assets: List<PackAssetManifestV1>,
    val fidelity: SoundFidelityMetadataV1,
    val authoredDsp: List<AuthoredDspProvenanceV2>,
    val certifiedSilentSources: List<CertifiedSilentSourceV2>,
    val softwareChannelPriorityOracleSha256: String?,
    val catalogSha256: String?,
    val familyAttenuationDb: Double,
    val defaultMixPeakDbfs: Double,
) {
    /** Physical PCM reservation; multiple authored roles may intentionally share one FLAC path. */
    val totalDecodedBytes: Long = tracks.distinctBy(SoundTrackManifestV1::path).fold(0L) { total, track ->
        Math.addExact(total, track.decodedBytes)
    }

    fun car(id: String): ManifestCarV1? = cars.firstOrNull { it.id == id }

    companion object Parser {
        const val LEGACY_SCHEMA_VERSION = 1
        const val SCHEMA_VERSION = 2
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        private val identifier = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        private val symbol = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private val sha256 = Regex("^[0-9a-f]{64}$")
        private val guid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val forbiddenTokens = Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)

        fun parse(bytes: ByteArray): SoundFamilyManifestV1 {
            if (bytes.size > MAX_MANIFEST_BYTES) {
                throw JsonValidationException("manifest.json exceeds $MAX_MANIFEST_BYTES bytes")
            }
            rejectForbiddenRole(bytes)
            val root = StrictJson.parse(bytes).asObject("manifest")
            val schemaVersionLong = root.getRequired("schemaVersion").asLong("schemaVersion")
            if (schemaVersionLong !in LEGACY_SCHEMA_VERSION.toLong()..SCHEMA_VERSION.toLong()) {
                throw JsonValidationException("schemaVersion is unsupported")
            }
            val schemaVersion = schemaVersionLong.toInt()
            root.requireExactKeys(
                "manifest",
                setOf(
                    "schemaVersion", "familyId", "displayName", "memberCarIds",
                    "audioFormat", "cars", "effects", "quirks", "tracks", "assets",
                    "fidelity", "provenance",
                ) + if (schemaVersion >= 2) setOf("oneShotPrograms") else emptySet(),
            )
            val familyId = requireSha(root.getRequired("familyId"), "familyId")
            val displayName = root.getRequired("displayName").asString("displayName").trim()
            if (displayName.isEmpty()) throw JsonValidationException("displayName must not be blank")

            val memberIds = root.getRequired("memberCarIds").asArray("memberCarIds").mapIndexed { index, value ->
                requireIdentifier(value, "memberCarIds[$index]")
            }
            if (memberIds.isEmpty() || memberIds.size != memberIds.toSet().size) {
                throw JsonValidationException("memberCarIds must be non-empty and unique")
            }
            validateAudioFormat(root.getRequired("audioFormat"))
            val cars = parseCars(root.getRequired("cars"), memberIds, schemaVersion)
            val declaredEffects = parseEffects(root.getRequired("effects"))
            val quirks = root.getRequired("quirks").asArray("quirks").mapIndexed { index, value ->
                requireSymbol(value, "quirks[$index]")
            }.toSet()
            if (!OfficialCarQuirks.supported.containsAll(quirks)) {
                throw JsonValidationException("Manifest declares an unsupported car quirk")
            }
            val expectedQuirks = cars.flatMapTo(linkedSetOf()) { car ->
                OfficialCarQuirks.expectedFor(car.id, car.engine, car.gearbox)
            }
            if (quirks != expectedQuirks) {
                throw JsonValidationException("Manifest quirks do not match its member cars")
            }
            val tracks = parseTracks(root.getRequired("tracks"), schemaVersion)
            // Schema v1 producers sometimes advertised an authored source lane that was not
            // retained in the PCM pack. Runtime availability must reflect playable tracks, so
            // normalise only that legacy declaration. Schema v2 remains exact and fail-closed.
            val effects = if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                deriveCoreEffectAvailability(tracks.mapTo(enumSetOf<PackTrackRole>()) { it.role })
            } else {
                declaredEffects
            }
            val oneShotPrograms = if (schemaVersion >= 2) {
                parseOneShotPrograms(root.getRequired("oneShotPrograms"), tracks)
            } else {
                emptyList()
            }
            validateOneShotTriggerPolicies(cars, oneShotPrograms, schemaVersion)
            if (schemaVersion != LEGACY_SCHEMA_VERSION) validateEffectCapabilities(effects, tracks)
            if (OfficialCarQuirks.AUTHORED_BOV_LANE_SILENT in quirks && effects.bov) {
                throw JsonValidationException("An authored-silent BOV lane must not become a runtime BOV track")
            }
            val decodedBytes = tracks.fold(0L) { total, track -> Math.addExact(total, track.decodedBytes) }
            if (decodedBytes > 192L * 1024L * 1024L) {
                throw JsonValidationException("Family exceeds the 192 MiB decoded PCM hard limit")
            }
            val assets = parseAssets(root.getRequired("assets"), tracks.mapTo(hashSetOf()) { it.path })
            if (tracks.size > 256 || assets.size > 64 || memberIds.size > 64) {
                throw JsonValidationException("Manifest exceeds its bounded item counts")
            }
            val declaredPreviews = cars.mapNotNullTo(hashSetOf()) { it.previewPath }
            if (declaredPreviews != assets.mapTo(hashSetOf()) { it.path }) {
                throw JsonValidationException("Car preview paths must exactly match preview assets")
            }
            val fidelity = parseFidelity(root.getRequired("fidelity"))
            val provenance = root.getRequired("provenance").asObject("provenance")
            provenance.requireExactKeys(
                "provenance",
                setOf(
                    "source", "sourceBankSha256", "catalogSha256", "capturePlanSha256",
                    "referenceRenderer", "familyAttenuationDb", "defaultMixPeakDbfs", "encoder",
                ) + if (schemaVersion >= 2) {
                    setOf(
                        "authoredDsp", "certifiedSilentSources",
                        "softwareChannelPriorityOracleSha256",
                    )
                } else {
                    emptySet()
                },
            )
            if (requireSha(provenance.getRequired("sourceBankSha256"), "provenance.sourceBankSha256") != familyId) {
                throw JsonValidationException("provenance sourceBankSha256 does not match familyId")
            }
            provenance.getRequired("source").asString("provenance.source").requireNotBlank("provenance.source")
            provenance.getRequired("referenceRenderer").asString("provenance.referenceRenderer")
                .requireNotBlank("provenance.referenceRenderer")
            val catalogHash = requireSha(provenance.getRequired("catalogSha256"), "provenance.catalogSha256")
            requireSha(provenance.getRequired("capturePlanSha256"), "provenance.capturePlanSha256")
            val attenuation = provenance.getRequired("familyAttenuationDb").asDouble("provenance.familyAttenuationDb")
            if (attenuation > 0.0) throw JsonValidationException("provenance.familyAttenuationDb may not amplify")
            validateIdleAudibility(cars, tracks, attenuation)
            val mixPeak = provenance.getRequired("defaultMixPeakDbfs").asDouble("provenance.defaultMixPeakDbfs")
            if (mixPeak > -3.0) throw JsonValidationException("Default continuous mix exceeds -3 dBFS")
            validateEncoder(provenance.getRequired("encoder"))
            val authoredDsp = if (schemaVersion >= 2) {
                parseAuthoredDsp(provenance.getRequired("authoredDsp"))
            } else {
                emptyList()
            }
            val certifiedSilentSources = if (schemaVersion >= 2) {
                parseCertifiedSilentSources(provenance.getRequired("certifiedSilentSources"))
            } else {
                emptyList()
            }
            validateTurboSilentSourceProvenance(oneShotPrograms, certifiedSilentSources)
            validateShiftSilentSourceProvenance(tracks, oneShotPrograms, certifiedSilentSources)
            val softwareChannelPriorityOracleSha256 = if (schemaVersion >= 2) {
                requireSha(
                    provenance.getRequired("softwareChannelPriorityOracleSha256"),
                    "provenance.softwareChannelPriorityOracleSha256",
                )
            } else {
                null
            }
            if (certifiedSilentSources.any { it.role == PackTrackRole.LIMITER } &&
                (effects.limiter || tracks.any { it.role == PackTrackRole.LIMITER } ||
                    oneShotPrograms.any { it.trigger == PackOneShotTrigger.LIMITER_EVENT })
            ) {
                throw JsonValidationException(
                    "Certified authored-target-silent limiter sources must not become PCM tracks",
                )
            }
            return SoundFamilyManifestV1(
                schemaVersion = schemaVersion,
                familyId = familyId,
                displayName = displayName,
                memberCarIds = memberIds,
                cars = cars,
                effects = effects,
                quirks = quirks,
                tracks = tracks,
                oneShotPrograms = oneShotPrograms,
                assets = assets,
                fidelity = fidelity,
                authoredDsp = authoredDsp,
                certifiedSilentSources = certifiedSilentSources,
                softwareChannelPriorityOracleSha256 = softwareChannelPriorityOracleSha256,
                catalogSha256 = catalogHash,
                familyAttenuationDb = attenuation,
                defaultMixPeakDbfs = mixPeak,
            )
        }

        private fun rejectForbiddenRole(bytes: ByteArray) {
            val text = bytes.toString(Charsets.UTF_8)
            if (forbiddenTokens.findAll(text).any { it.value.equals("load", ignoreCase = true) }) {
                throw JsonValidationException("manifest contains the forbidden LOAD role or reference")
            }
        }

        private fun validateAudioFormat(value: JsonValue) {
            val format = value.asObject("audioFormat")
            format.requireExactKeys("audioFormat", setOf("codec", "sampleRate", "channels", "bitsPerSample"))
            if (format.getRequired("codec").asString("audioFormat.codec") != "FLAC") {
                throw JsonValidationException("audioFormat.codec must be FLAC")
            }
            requireInteger(format.getRequired("sampleRate"), "audioFormat.sampleRate", SAMPLE_RATE.toLong())
            requireInteger(format.getRequired("channels"), "audioFormat.channels", CHANNELS.toLong())
            requireInteger(format.getRequired("bitsPerSample"), "audioFormat.bitsPerSample", BITS_PER_SAMPLE.toLong())
        }

        private fun parseCars(value: JsonValue, memberIds: List<String>, schemaVersion: Int): List<ManifestCarV1> {
            val cars = value.asArray("cars").mapIndexed { index, raw ->
                val car = raw.asObject("cars[$index]")
                car.requireExactKeys(
                    "cars[$index]",
                    setOf("id", "name", "brand", "previewPath", "engine", "gearbox") +
                        if (schemaVersion >= 2) setOf("oneShotTriggerPolicies") else emptySet(),
                )
                val id = requireIdentifier(car.getRequired("id"), "cars[$index].id")
                val name = car.getRequired("name").asString("cars[$index].name").trim()
                if (name.isEmpty()) throw JsonValidationException("cars[$index].name must not be blank")
                ManifestCarV1(
                    id = id,
                    displayName = name,
                    brand = car.getRequired("brand").asString("cars[$index].brand").trim(),
                    previewPath = car.getRequired("previewPath").asNullableString("cars[$index].previewPath")
                        ?.let { requireArchivePath(it, "cars[$index].previewPath", "previews", false) },
                    engine = parseEngine(
                        car.getRequired("engine"),
                        "cars[$index].engine",
                        schemaVersion,
                    ),
                    gearbox = parseGearbox(
                        car.getRequired("gearbox"),
                        "cars[$index].gearbox",
                        schemaVersion,
                    ),
                    oneShotTriggerPolicies = if (schemaVersion >= 2) {
                        parseOneShotTriggerPolicies(
                            car.getRequired("oneShotTriggerPolicies"),
                            "cars[$index].oneShotTriggerPolicies",
                        )
                    } else {
                        emptyMap()
                    },
                )
            }
            if (cars.map { it.id }.toSet() != memberIds.toSet() || cars.size != memberIds.size) {
                throw JsonValidationException("cars must define every memberCarId exactly once")
            }
            return cars
        }

        internal fun parseEngine(
            value: JsonValue,
            label: String,
            schemaVersion: Int = LEGACY_SCHEMA_VERSION,
        ): CarEngineMetadata {
            val engine = value.asObject(label)
            engine.requireExactKeys(
                label,
                setOf(
                    "idleRpm", "redlineRpm", "limiterRpm", "limiterHz",
                    "tachometerMaximumRpm", "turboCount", "hybrid", "hybridConfig",
                    "turboControllers",
                ) + if (schemaVersion >= SCHEMA_VERSION) {
                    setOf("turboPhysics", "throttleMap")
                } else {
                    emptySet()
                },
            )
            val idle = positive(engine.getRequired("idleRpm"), "$label.idleRpm")
            val redline = positive(engine.getRequired("redlineRpm"), "$label.redlineRpm")
            val limiter = positive(engine.getRequired("limiterRpm"), "$label.limiterRpm")
            val tachMax = positive(engine.getRequired("tachometerMaximumRpm"), "$label.tachometerMaximumRpm")
            if (idle >= redline || redline > limiter || limiter > tachMax * 1.25) {
                throw JsonValidationException("$label RPM values are inconsistent")
            }
            val turboCount = engine.getRequired("turboCount").asLong("$label.turboCount")
            // AC exposes controller sections rather than a guaranteed physical-turbo count;
            // some official cars (notably the 488 GTB) author ten valid turbo sections.
            if (turboCount !in 0..32) throw JsonValidationException("$label.turboCount is invalid")
            val controllers = parseTurboControllers(engine.getRequired("turboControllers"), "$label.turboControllers")
            if (controllers.size > turboCount) {
                throw JsonValidationException("$label.turboControllers exceeds turboCount")
            }
            if (controllers.any { it.controllers.firstOrNull()?.combinator != "ADD" }) {
                throw JsonValidationException("$label turbo controller programs must begin with ADD")
            }
            val turboPhysics = if (schemaVersion >= SCHEMA_VERSION) {
                parseTurboPhysics(
                    engine.getRequired("turboPhysics"),
                    "$label.turboPhysics",
                    turboCount.toInt(),
                    controllers,
                )
            } else {
                TurboPhysicsMetadata.EMPTY
            }
            val throttleMap = if (schemaVersion >= SCHEMA_VERSION) {
                parsePhysicalThrottleMap(engine.getRequired("throttleMap"), "$label.throttleMap")
            } else {
                PhysicalThrottleMapMetadata.IDENTITY
            }
            val hybridConfig = parseHybridConfig(engine.getRequired("hybridConfig"), "$label.hybridConfig")
            val hybrid = engine.getRequired("hybrid").asBoolean("$label.hybrid")
            if (hybrid != (hybridConfig != null)) throw JsonValidationException("$label.hybrid and hybridConfig disagree")
            return CarEngineMetadata(
                idleRpm = idle,
                redlineRpm = redline,
                limiterRpm = limiter,
                limiterHz = positive(engine.getRequired("limiterHz"), "$label.limiterHz"),
                tachometerMaximumRpm = tachMax,
                turboCount = turboCount.toInt(),
                hybrid = hybrid,
                hybridConfig = hybridConfig,
                turboControllers = controllers,
                turboPhysics = turboPhysics,
                throttleMap = throttleMap,
            )
        }

        private fun parsePhysicalThrottleMap(
            value: JsonValue,
            label: String,
        ): PhysicalThrottleMapMetadata {
            val throttleMap = value.asObject(label)
            throttleMap.requireExactKeys(
                label,
                setOf("input", "output", "interpolation", "points"),
            )
            if (throttleMap.getRequired("input").asString("$label.input") != "NORMALIZED_PEDAL" ||
                throttleMap.getRequired("output").asString("$label.output") != "NORMALIZED_ENGINE_GAS" ||
                throttleMap.getRequired("interpolation").asString("$label.interpolation") != "CLAMPED_LINEAR"
            ) {
                throw JsonValidationException("$label declares unsupported throttle-map semantics")
            }
            val points = parseCurve(throttleMap.getRequired("points"), "$label.points", true)
            if (points.size < 2 || points.first().input != 0.0 || points.last().input != 1.0) {
                throw JsonValidationException("$label.points must cover normalized pedal endpoints 0 and 1")
            }
            return PhysicalThrottleMapMetadata(points)
        }

        private fun parseTurboPhysics(
            value: JsonValue,
            label: String,
            turboCount: Int,
            controllers: List<TurboControllerFileMetadata>,
        ): TurboPhysicsMetadata {
            val physics = value.asObject(label)
            physics.requireExactKeys(label, setOf("bovPressureThreshold", "turbos"))
            val bovPressureThreshold = nonNegative(
                physics.getRequired("bovPressureThreshold"),
                "$label.bovPressureThreshold",
            )
            if (bovPressureThreshold > MAX_REASONABLE_BOOST_BAR) {
                throw JsonValidationException("$label.bovPressureThreshold is out of range")
            }
            val turbos = physics.getRequired("turbos").asArray("$label.turbos")
                .mapIndexed { turboIndex, rawTurbo ->
                    val turboLabel = "$label.turbos[$turboIndex]"
                    val turbo = rawTurbo.asObject(turboLabel)
                    turbo.requireExactKeys(
                        turboLabel,
                        setOf(
                            "maximumBoost", "wastegate", "referenceRpm", "gamma",
                            "lagUp", "lagDown", "controllerFile",
                        ),
                    )
                    val maximumBoost = boundedPositive(
                        turbo.getRequired("maximumBoost"),
                        "$turboLabel.maximumBoost",
                        MAX_REASONABLE_BOOST_BAR,
                    )
                    val wastegate = nonNegative(turbo.getRequired("wastegate"), "$turboLabel.wastegate")
                    if (wastegate > MAX_REASONABLE_BOOST_BAR) {
                        throw JsonValidationException("$turboLabel.wastegate is out of range")
                    }
                    val referenceRpm = boundedPositive(
                        turbo.getRequired("referenceRpm"),
                        "$turboLabel.referenceRpm",
                        MAX_REASONABLE_REFERENCE_RPM,
                    )
                    val gamma = boundedPositive(
                        turbo.getRequired("gamma"),
                        "$turboLabel.gamma",
                        MAX_REASONABLE_GAMMA,
                    )
                    val lagUp = boundedNonNegative(
                        turbo.getRequired("lagUp"),
                        "$turboLabel.lagUp",
                        MAX_REASONABLE_LAG_PER_SECOND,
                    )
                    val lagDown = boundedNonNegative(
                        turbo.getRequired("lagDown"),
                        "$turboLabel.lagDown",
                        MAX_REASONABLE_LAG_PER_SECOND,
                    )
                    val controllerFile = turbo.getRequired("controllerFile")
                        .asNullableString("$turboLabel.controllerFile")
                        ?.requireNotBlank("$turboLabel.controllerFile")
                    TurboPhysicsUnitMetadata(
                        maximumBoost = maximumBoost,
                        wastegate = wastegate,
                        referenceRpm = referenceRpm,
                        gamma = gamma,
                        lagUp = lagUp,
                        lagDown = lagDown,
                        controllerFile = controllerFile,
                    )
                }
            if (turbos.size != turboCount) {
                throw JsonValidationException("$label.turbos must exactly match turboCount")
            }
            val controllerFiles = controllers.map { it.file }
            if (controllerFiles.size != controllerFiles.toSet().size) {
                throw JsonValidationException("$label has duplicate turbo controller files")
            }
            val referencedControllerFiles = turbos.mapNotNull { it.controllerFile }
            if (referencedControllerFiles.size != referencedControllerFiles.toSet().size ||
                referencedControllerFiles.toSet() != controllerFiles.toSet()
            ) {
                throw JsonValidationException(
                    "$label controllerFile values must map one-to-one to turboControllers",
                )
            }
            return TurboPhysicsMetadata(bovPressureThreshold, turbos)
        }

        private fun parseTurboControllers(value: JsonValue, label: String): List<TurboControllerFileMetadata> =
            value.asArray(label).mapIndexed { fileIndex, rawFile ->
                val fileLabel = "$label[$fileIndex]"
                val file = rawFile.asObject(fileLabel)
                file.requireExactKeys(fileLabel, setOf("file", "sha256", "controllers"))
                TurboControllerFileMetadata(
                    file = file.getRequired("file").asString("$fileLabel.file").requireNotBlank("$fileLabel.file"),
                    sha256 = requireSha(file.getRequired("sha256"), "$fileLabel.sha256"),
                    controllers = file.getRequired("controllers").asArray("$fileLabel.controllers")
                        .mapIndexed { controllerIndex, rawController ->
                            val controllerLabel = "$fileLabel.controllers[$controllerIndex]"
                            val controller = rawController.asObject(controllerLabel)
                            controller.requireExactKeys(
                                controllerLabel,
                                setOf("section", "input", "combinator", "lut", "filter", "upLimit", "downLimit"),
                            )
                            val input = controller.getRequired("input").asString("$controllerLabel.input")
                            val combinator = controller.getRequired("combinator")
                                .asString("$controllerLabel.combinator")
                            val lut = parseUnboundedCurve(controller.getRequired("lut"), "$controllerLabel.lut")
                            val filter = controller.getRequired("filter").asDouble("$controllerLabel.filter")
                            val upLimit = controller.getRequired("upLimit").asDouble("$controllerLabel.upLimit")
                            val downLimit = controller.getRequired("downLimit")
                                .asDouble("$controllerLabel.downLimit")
                            if (input !in setOf("GAS", "GEAR", "RPMS")) {
                                throw JsonValidationException("$controllerLabel.input is unsupported")
                            }
                            if (combinator !in setOf("ADD", "MULT")) {
                                throw JsonValidationException("$controllerLabel.combinator is unsupported")
                            }
                            if (lut.isEmpty()) throw JsonValidationException("$controllerLabel.lut is empty")
                            if (filter !in 0.0..1.0) {
                                throw JsonValidationException("$controllerLabel.filter must be 0..1")
                            }
                            if (downLimit >= upLimit) {
                                throw JsonValidationException("$controllerLabel limits are invalid")
                            }
                            TurboControllerMetadata(
                                section = controller.getRequired("section").asString("$controllerLabel.section"),
                                input = input,
                                combinator = combinator,
                                lut = lut,
                                filter = filter,
                                upLimit = upLimit,
                                downLimit = downLimit,
                            )
                        },
                )
            }

        private fun parseHybridConfig(value: JsonValue, label: String): HybridConfigMetadata? {
            if (value == JsonValue.NullValue) return null
            val hybrid = value.asObject(label)
            hybrid.requireExactKeys(
                label,
                setOf(
                    "file", "sha256", "maximumEnergyKjPerLap", "dischargeTimeMs",
                    "hasButtonOverride", "defaultController", "heatTorquePercent", "hasFrontMotors",
                    "frontDischargeTimeMs", "controllerFiles",
                ),
            )
            return HybridConfigMetadata(
                file = hybrid.getRequired("file").asString("$label.file").requireNotBlank("$label.file"),
                sha256 = requireSha(hybrid.getRequired("sha256"), "$label.sha256"),
                maximumEnergyKjPerLap = hybrid.getRequired("maximumEnergyKjPerLap").asDouble("$label.maximumEnergyKjPerLap"),
                dischargeTimeMs = hybrid.getRequired("dischargeTimeMs").asDouble("$label.dischargeTimeMs"),
                hasButtonOverride = hybrid.getRequired("hasButtonOverride").asBoolean("$label.hasButtonOverride"),
                defaultController = hybrid.getRequired("defaultController").asDouble("$label.defaultController"),
                heatTorquePercent = hybrid.getRequired("heatTorquePercent").asDouble("$label.heatTorquePercent"),
                hasFrontMotors = hybrid.getRequired("hasFrontMotors").asBoolean("$label.hasFrontMotors"),
                frontDischargeTimeMs = hybrid.getRequired("frontDischargeTimeMs").asDouble("$label.frontDischargeTimeMs"),
                controllerFiles = hybrid.getRequired("controllerFiles").asArray("$label.controllerFiles")
                    .mapIndexed { index, raw ->
                        val childLabel = "$label.controllerFiles[$index]"
                        val child = raw.asObject(childLabel)
                        child.requireExactKeys(childLabel, setOf("file", "sha256"))
                        HybridControllerFileMetadata(
                            child.getRequired("file").asString("$childLabel.file").requireNotBlank("$childLabel.file"),
                            requireSha(child.getRequired("sha256"), "$childLabel.sha256"),
                        )
                    },
            )
        }

        internal fun parseGearbox(
            value: JsonValue,
            label: String,
            schemaVersion: Int = LEGACY_SCHEMA_VERSION,
        ): CarGearboxMetadata {
            val gearbox = value.asObject(label)
            gearbox.requireExactKeys(
                label,
                setOf(
                    "traction", "forwardRatios", "reverseRatio", "finalDrive", "upshiftRpm",
                    "downshiftLandingRpmByGear", "upshiftTimeMs", "downshiftTimeMs",
                    "alternateGearSets",
                ) + if (schemaVersion >= SCHEMA_VERSION) setOf("engineGasAssist") else emptySet(),
            )
            val ratios = gearbox.getRequired("forwardRatios").asArray("$label.forwardRatios")
                .mapIndexed { index, item -> positive(item, "$label.forwardRatios[$index]") }
            if (ratios.size !in 1..12 || ratios.zipWithNext().any { (left, right) -> left <= right }) {
                throw JsonValidationException("$label.forwardRatios must be 1-12 descending positive ratios")
            }
            val landingObject = gearbox.getRequired("downshiftLandingRpmByGear")
                .asObject("$label.downshiftLandingRpmByGear")
            val landing = landingObject.map { (gearText, rpmValue) ->
                val gear = gearText.toIntOrNull()
                    ?: throw JsonValidationException("$label has a non-numeric landing-RPM gear")
                if (gear !in 2..ratios.size) throw JsonValidationException("$label landing-RPM gear is outside the gearbox")
                gear to positive(rpmValue, "$label.downshiftLandingRpmByGear.$gear")
            }.toMap()
            if (landing.keys != (2..ratios.size).toSet()) {
                throw JsonValidationException("$label must define a landing RPM for every gear after first")
            }
            val upshiftRpm = positive(gearbox.getRequired("upshiftRpm"), "$label.upshiftRpm")
            landing.forEach { (gear, actual) ->
                val calculated = upshiftRpm * ratios[gear - 1] / ratios[gear - 2]
                if (kotlin.math.abs(actual - calculated) > 0.01) {
                    throw JsonValidationException("$label landing RPM for gear $gear was not calculated from its ratios")
                }
            }
            val alternateSets = gearbox.getRequired("alternateGearSets").asArray("$label.alternateGearSets")
                .mapIndexed { index, raw ->
                    val setLabel = "$label.alternateGearSets[$index]"
                    val gearSet = raw.asObject(setLabel)
                    gearSet.requireExactKeys(setLabel, setOf("file", "sha256", "options"))
                    AlternateGearSetMetadata(
                        file = gearSet.getRequired("file").asString("$setLabel.file").requireNotBlank("$setLabel.file"),
                        sha256 = requireSha(gearSet.getRequired("sha256"), "$setLabel.sha256"),
                        options = gearSet.getRequired("options").asArray("$setLabel.options")
                            .mapIndexed { optionIndex, rawOption ->
                                val optionLabel = "$setLabel.options[$optionIndex]"
                                val option = rawOption.asObject(optionLabel)
                                option.requireExactKeys(optionLabel, setOf("label", "ratio"))
                                AlternateGearOptionMetadata(
                                    label = option.getRequired("label").asString("$optionLabel.label"),
                                    ratio = option.getRequired("ratio").asDouble("$optionLabel.ratio"),
                                )
                            },
                    )
                }
            val traction = gearbox.getRequired("traction").asString("$label.traction")
            if (traction !in setOf("RWD", "FWD", "AWD", "AWD2")) {
                throw JsonValidationException("$label.traction is unsupported")
            }
            val engineGasAssist = if (schemaVersion >= SCHEMA_VERSION) {
                parseEngineGasAssist(
                    gearbox.getRequired("engineGasAssist"),
                    "$label.engineGasAssist",
                )
            } else {
                EngineGasAssistMetadata.EMPTY
            }
            return CarGearboxMetadata(
                traction = traction,
                forwardRatios = ratios,
                reverseRatio = negative(gearbox.getRequired("reverseRatio"), "$label.reverseRatio"),
                finalDrive = positive(gearbox.getRequired("finalDrive"), "$label.finalDrive"),
                upshiftRpm = upshiftRpm,
                downshiftLandingRpmByGear = landing,
                upshiftTimeMs = nonNegative(gearbox.getRequired("upshiftTimeMs"), "$label.upshiftTimeMs"),
                downshiftTimeMs = nonNegative(gearbox.getRequired("downshiftTimeMs"), "$label.downshiftTimeMs"),
                alternateGearSets = alternateSets,
                engineGasAssist = engineGasAssist,
            )
        }

        private fun parseEngineGasAssist(value: JsonValue, label: String): EngineGasAssistMetadata {
            val assist = value.asObject(label)
            assist.requireExactKeys(
                label,
                setOf(
                    "autoShifterGasCutoffMs", "engineCutoffMs", "autoBlipElectronic",
                    "autoBlipEnableMode", "autoBlipClutchGateExclusive", "autoBlipProfile",
                    "autoBlipEndTimeMs", "autoBlipEvaluator", "autoBlipCombiner",
                    "processingOrder",
                ),
            )
            if (assist.getRequired("autoBlipEnableMode").asString("$label.autoBlipEnableMode") !=
                EngineGasAssistMetadata.ENABLE_MODE
            ) {
                throw JsonValidationException("$label.autoBlipEnableMode is unsupported")
            }
            if (assist.getRequired("autoBlipEvaluator").asString("$label.autoBlipEvaluator") !=
                EngineGasAssistMetadata.EVALUATOR
            ) {
                throw JsonValidationException("$label.autoBlipEvaluator is unsupported")
            }
            if (assist.getRequired("autoBlipCombiner").asString("$label.autoBlipCombiner") !=
                EngineGasAssistMetadata.COMBINER
            ) {
                throw JsonValidationException("$label.autoBlipCombiner is unsupported")
            }
            if (assist.getRequired("processingOrder").asString("$label.processingOrder") !=
                EngineGasAssistMetadata.PROCESSING_ORDER
            ) {
                throw JsonValidationException("$label.processingOrder is unsupported")
            }

            val clutchGate = assist.getRequired("autoBlipClutchGateExclusive")
                .asDouble("$label.autoBlipClutchGateExclusive")
            if (!clutchGate.isFinite() || kotlin.math.abs(clutchGate - 1.0 / Math.PI) > 1e-12) {
                throw JsonValidationException("$label.autoBlipClutchGateExclusive must be 1/pi")
            }
            val endTimeMs = nonNegative(
                assist.getRequired("autoBlipEndTimeMs"),
                "$label.autoBlipEndTimeMs",
            )
            val profile = assist.getRequired("autoBlipProfile").asArray("$label.autoBlipProfile")
                .mapIndexed { index, rawPoint ->
                    val pointLabel = "$label.autoBlipProfile[$index]"
                    val pair = rawPoint.asArray(pointLabel)
                    if (pair.size != 2) throw JsonValidationException("$pointLabel must contain [timeMs,pedal]")
                    CurvePointV1(
                        input = nonNegative(pair[0], "$pointLabel[0]"),
                        output = probability(pair[1], "$pointLabel[1]"),
                    )
                }
            if (profile.isNotEmpty()) {
                if (profile.size != 4 || profile.first() != CurvePointV1(0.0, 0.0) ||
                    profile.last().output != 0.0 || profile.last().input != endTimeMs
                ) {
                    throw JsonValidationException(
                        "$label.autoBlipProfile must be the four raw authored points ending at autoBlipEndTimeMs",
                    )
                }
            } else if (endTimeMs != 0.0) {
                throw JsonValidationException("$label.autoBlipEndTimeMs must be zero without a profile")
            }
            return EngineGasAssistMetadata(
                autoShifterGasCutoffMs = nonNegative(
                    assist.getRequired("autoShifterGasCutoffMs"),
                    "$label.autoShifterGasCutoffMs",
                ),
                engineCutoffMs = nonNegative(
                    assist.getRequired("engineCutoffMs"),
                    "$label.engineCutoffMs",
                ),
                autoBlipElectronic = assist.getRequired("autoBlipElectronic")
                    .asBoolean("$label.autoBlipElectronic"),
                autoBlipClutchGateExclusive = clutchGate,
                autoBlipProfile = profile,
                autoBlipEndTimeMs = endTimeMs,
            )
        }

        internal fun parseEffects(value: JsonValue): CoreEffectAvailability {
            val effects = value.asObject("effects")
            val keys = setOf(
                "idle", "coast", "texture", "intake", "exhaust", "turbo", "spool", "bov",
                "transmission", "limiter", "shift", "overrun", "popsBangsCracks", "engineStart",
            )
            effects.requireExactKeys("effects", keys)
            fun flag(name: String) = effects.getRequired(name).asBoolean("effects.$name")
            val result = CoreEffectAvailability(
                idle = flag("idle"), coast = flag("coast"), texture = flag("texture"),
                intake = flag("intake"), exhaust = flag("exhaust"), turbo = flag("turbo"),
                spool = flag("spool"), bov = flag("bov"), transmission = flag("transmission"),
                limiter = flag("limiter"), shift = flag("shift"), overrun = flag("overrun"),
                popsBangsCracks = flag("popsBangsCracks"), engineStart = flag("engineStart"),
            )
            if (!result.idle) throw JsonValidationException("effects.idle must be available")
            return result
        }

        private fun parseTracks(value: JsonValue, schemaVersion: Int): List<SoundTrackManifestV1> {
            val tracks = value.asArray("tracks").mapIndexed { index, raw ->
                val label = "tracks[$index]"
                val track = raw.asObject(label)
                // The first schema-v2 compiler emitted the v2 channel-priority and one-shot
                // topology but not the later explicit pitch metadata. Treat that exact shape as
                // the documented RPM-ratio default; partial pitch metadata remains invalid.
                val pitchKeys = setOf("pitchMode", "pitchCurve", "pitchCurveInterpolation")
                val presentPitchKeys = track.keys.intersect(pitchKeys)
                if (presentPitchKeys.isNotEmpty() && presentPitchKeys.size != pitchKeys.size) {
                    throw JsonValidationException("$label pitch metadata must be complete")
                }
                val hasExplicitPitch = presentPitchKeys.size == pitchKeys.size
                track.requireExactKeys(
                    label,
                    setOf(
                        "id", "role", "path", "flacSha256", "pcmSha256", "frameCount",
                        "sampleRate", "channels", "bitsPerSample", "rootRpm", "loopStartFrame",
                        "loopEndFrame", "gainDb", "peakDbfs", "rpmCurve", "gainCurve", "triggers",
                    ) + if (schemaVersion >= 2) {
                        setOf("softwareChannelPriority") + if (hasExplicitPitch) pitchKeys else emptySet()
                    } else {
                        emptySet()
                    },
                )
                val roleText = track.getRequired("role").asString("$label.role")
                val role = try {
                    PackTrackRole.valueOf(roleText)
                } catch (_: IllegalArgumentException) {
                    throw JsonValidationException("$label.role is unsupported")
                }
                if (schemaVersion < 2 && role == PackTrackRole.TURBO_TRANSIENT) {
                    throw JsonValidationException("$label.role requires schema v2")
                }
                val frames = track.getRequired("frameCount").asLong("$label.frameCount")
                if (frames <= 0 || frames > Int.MAX_VALUE) throw JsonValidationException("$label.frameCount is invalid")
                requireInteger(track.getRequired("sampleRate"), "$label.sampleRate", SAMPLE_RATE.toLong())
                requireInteger(track.getRequired("channels"), "$label.channels", CHANNELS.toLong())
                requireInteger(track.getRequired("bitsPerSample"), "$label.bitsPerSample", BITS_PER_SAMPLE.toLong())
                val rootRpm = nullableNumber(track.getRequired("rootRpm"), "$label.rootRpm")
                if ((rootRpm != null && rootRpm <= 0.0) || (role.requiresRootRpm && rootRpm == null)) {
                    throw JsonValidationException("$label requires a positive rootRpm")
                }
                val loopStart = nullableInteger(track.getRequired("loopStartFrame"), "$label.loopStartFrame")
                val loopEnd = nullableInteger(track.getRequired("loopEndFrame"), "$label.loopEndFrame")
                if ((loopStart == null) != (loopEnd == null) ||
                    (role.loops && loopStart == null && role != PackTrackRole.LIMITER)
                ) {
                    throw JsonValidationException("$label requires both explicit loop bounds")
                }
                if (loopStart != null && (loopStart < 0 || loopStart >= loopEnd!! || loopEnd > frames)) {
                    throw JsonValidationException("$label loop bounds are outside decoded PCM")
                }
                val gainDb = track.getRequired("gainDb").asDouble("$label.gainDb")
                val peakDbfs = track.getRequired("peakDbfs").asDouble("$label.peakDbfs")
                if (gainDb > 0.0) throw JsonValidationException("$label.gainDb may not amplify")
                if (peakDbfs > -3.0 || peakDbfs <= -96.0) {
                    throw JsonValidationException("$label.peakDbfs is outside the audible calibrated range")
                }
                val triggers = track.getRequired("triggers").asArray("$label.triggers")
                    .mapIndexed { triggerIndex, item ->
                        requireSymbol(item, "$label.triggers[$triggerIndex]")
                    }
                val expectedTriggers = when {
                    role == PackTrackRole.LIMITER && schemaVersion >= 2 -> setOf("limiterEvent")
                    role == PackTrackRole.BOV && schemaVersion >= 2 -> setOf("turboEvent")
                    role == PackTrackRole.TURBO_TRANSIENT -> setOf("turboEvent")
                    else -> TRIGGERS_BY_ROLE.getValue(role)
                }
                if (triggers.size != triggers.toSet().size || triggers.toSet() != expectedTriggers) {
                    throw JsonValidationException(
                        "$label.triggers must be exactly ${expectedTriggers.sorted()} for ${role.name}",
                    )
                }
                val softwareChannelPriority = if (schemaVersion >= 2) {
                    requireSoftwareChannelPriority(
                        track.getRequired("softwareChannelPriority"),
                        "$label.softwareChannelPriority",
                    )
                } else {
                    legacySoftwareChannelPriority(role)
                }
                val pitchMode = if (hasExplicitPitch) {
                    enumValue<PackTrackPitchMode>(track.getRequired("pitchMode"), "$label.pitchMode")
                } else {
                    PackTrackPitchMode.AUTO_PITCH_RPM_RATIO
                }
                val pitchCurveInterpolation = if (hasExplicitPitch) {
                    enumValue<PackTrackPitchCurveInterpolation>(
                        track.getRequired("pitchCurveInterpolation"),
                        "$label.pitchCurveInterpolation",
                    )
                } else {
                    PackTrackPitchCurveInterpolation.NONE
                }
                val pitchCurve = if (hasExplicitPitch) {
                    parseTrackPitchCurve(track.getRequired("pitchCurve"), "$label.pitchCurve")
                } else {
                    emptyList()
                }
                when (pitchMode) {
                    PackTrackPitchMode.AUTO_PITCH_RPM_RATIO -> {
                        if (pitchCurve.isNotEmpty() ||
                            pitchCurveInterpolation != PackTrackPitchCurveInterpolation.NONE
                        ) {
                            throw JsonValidationException(
                                "$label AUTO_PITCH_RPM_RATIO requires pitchCurve=[] and " +
                                    "pitchCurveInterpolation=NONE",
                            )
                        }
                    }
                    PackTrackPitchMode.AUTHORED_PROPERTY_ONE_RELATIVE_RATE -> {
                        if (pitchCurveInterpolation != PackTrackPitchCurveInterpolation.CLAMPED_LINEAR) {
                            throw JsonValidationException(
                                "$label AUTHORED_PROPERTY_ONE_RELATIVE_RATE requires " +
                                    "pitchCurveInterpolation=CLAMPED_LINEAR",
                            )
                        }
                        if (pitchCurve.size !in 2..MAX_TRACK_PITCH_CURVE_POINTS) {
                            throw JsonValidationException("$label.pitchCurve must contain 2..512 points")
                        }
                        if (rootRpm == null) {
                            throw JsonValidationException(
                                "$label AUTHORED_PROPERTY_ONE_RELATIVE_RATE requires rootRpm",
                            )
                        }
                    }
                }
                val rpmCurve = parseCurve(
                    track.getRequired("rpmCurve"), "$label.rpmCurve", normalizedInput = false,
                )
                if (pitchMode == PackTrackPitchMode.AUTHORED_PROPERTY_ONE_RELATIVE_RATE) {
                    if (rpmCurve.isEmpty() ||
                        pitchCurve.first().input != rpmCurve.first().input ||
                        pitchCurve.last().input != rpmCurve.last().input
                    ) {
                        throw JsonValidationException(
                            "$label.pitchCurve must span the exact rpmCurve input domain",
                        )
                    }
                    val normalizedRate = pitchCurve.clampedLinearValueAt(requireNotNull(rootRpm))
                    if (kotlin.math.abs(normalizedRate - 1.0) > TRACK_PITCH_ROOT_TOLERANCE) {
                        throw JsonValidationException(
                            "$label.pitchCurve must evaluate to 1 at rootRpm within 2e-4",
                        )
                    }
                }
                SoundTrackManifestV1(
                    id = requireIdentifier(track.getRequired("id"), "$label.id"),
                    role = role,
                    path = requireArchivePath(
                        track.getRequired("path").asString("$label.path"), label, "audio", true,
                    ),
                    flacSha256 = requireSha(track.getRequired("flacSha256"), "$label.flacSha256"),
                    pcmSha256 = requireSha(track.getRequired("pcmSha256"), "$label.pcmSha256"),
                    frameCount = frames,
                    rootRpm = rootRpm,
                    loopStartFrame = loopStart,
                    loopEndFrameExclusive = loopEnd,
                    gainDb = gainDb,
                    peakDbfs = peakDbfs,
                    rpmCurve = rpmCurve,
                    gainCurve = parseCurve(track.getRequired("gainCurve"), "$label.gainCurve", normalizedInput = true),
                    triggers = triggers,
                    softwareChannelPriority = softwareChannelPriority,
                    pitchMode = pitchMode,
                    pitchCurve = pitchCurve,
                    pitchCurveInterpolation = pitchCurveInterpolation,
                )
            }
            if (tracks.isEmpty() || tracks.none { it.role == PackTrackRole.IDLE }) {
                throw JsonValidationException("Every sound family must contain authored IDLE")
            }
            if (tracks.map { it.id }.toSet().size != tracks.size) {
                throw JsonValidationException("Track ids must be unique")
            }
            validateSharedTrackPaths(tracks)
            return tracks
        }

        /**
         * The compiler may bind identical PCM to more than one authored role. Role, curves, loop
         * interpretation, trigger, gain, and priority remain per-track; file identity metadata must
         * agree so Android can safely decode and own the physical clip exactly once.
         */
        private fun validateSharedTrackPaths(tracks: List<SoundTrackManifestV1>) {
            val firstByPath = HashMap<String, SoundTrackManifestV1>()
            tracks.forEach { track ->
                val first = firstByPath.putIfAbsent(track.path, track) ?: return@forEach
                if (track.flacSha256 != first.flacSha256 ||
                    track.pcmSha256 != first.pcmSha256 ||
                    track.frameCount != first.frameCount ||
                    track.peakDbfs != first.peakDbfs
                ) {
                    throw JsonValidationException(
                        "Tracks sharing ${track.path} must declare identical physical PCM metadata",
                    )
                }
            }
        }

        private fun parseOneShotPrograms(
            value: JsonValue,
            tracks: List<SoundTrackManifestV1>,
        ): List<PackOneShotProgramV2> {
            val trackById = tracks.associateBy(SoundTrackManifestV1::id)
            val programs = value.asArray("oneShotPrograms").mapIndexed { programIndex, rawProgram ->
                val label = "oneShotPrograms[$programIndex]"
                val program = rawProgram.asObject(label)
                val trigger = enumValue<PackOneShotTrigger>(
                    program.getRequired("trigger"), "$label.trigger",
                )
                program.requireExactKeys(
                    label,
                    setOf(
                        "id", "trigger", "capturedFromEventStart", "softwareChannelPriority",
                        "rootNodeIds", "nodes",
                    ) +
                        if (trigger == PackOneShotTrigger.ENGINE_EVENT ||
                            trigger == PackOneShotTrigger.LIMITER_EVENT ||
                            trigger == PackOneShotTrigger.TURBO_EVENT
                        ) setOf("policy") else emptySet(),
                )
                val programId = requireIdentifier(program.getRequired("id"), "$label.id")
                val softwareChannelPriority = requireSoftwareChannelPriority(
                    program.getRequired("softwareChannelPriority"),
                    "$label.softwareChannelPriority",
                )
                if (!program.getRequired("capturedFromEventStart").asBoolean("$label.capturedFromEventStart")) {
                    throw JsonValidationException("$label must retain event-start timing in its PCM")
                }
                val roots = program.getRequired("rootNodeIds").asArray("$label.rootNodeIds")
                    .mapIndexed { rootIndex, root -> requireIdentifier(root, "$label.rootNodeIds[$rootIndex]") }
                if (roots.isEmpty() || roots.size != roots.toSet().size) {
                    throw JsonValidationException("$label.rootNodeIds must be non-empty and unique")
                }
                val nodes = program.getRequired("nodes").asArray("$label.nodes")
                    .mapIndexed { nodeIndex, node ->
                        parseOneShotNode(node, "$label.nodes[$nodeIndex]", trigger)
                    }
                val engineEventPolicy = if (trigger == PackOneShotTrigger.ENGINE_EVENT) {
                    parseEngineEventPolicy(program.getRequired("policy"), "$label.policy")
                } else {
                    null
                }
                val limiterEventPolicy = if (trigger == PackOneShotTrigger.LIMITER_EVENT) {
                    parseLimiterEventPolicy(program.getRequired("policy"), "$label.policy")
                } else {
                    null
                }
                val turboEventPolicy = if (trigger == PackOneShotTrigger.TURBO_EVENT) {
                    parseTurboEventPolicy(program.getRequired("policy"), "$label.policy")
                } else {
                    null
                }
                if (turboEventPolicy != null && softwareChannelPriority != FMOD_DEFAULT_EVENT_PRIORITY) {
                    throw JsonValidationException(
                        "$label TURBO_EVENT softwareChannelPriority must be $FMOD_DEFAULT_EVENT_PRIORITY",
                    )
                }
                validateOneShotProgram(
                    programId, trigger, roots, nodes, engineEventPolicy, limiterEventPolicy,
                    turboEventPolicy,
                    trackById, label,
                )
                nodes.filterIsInstance<PackOneShotTrackNodeV2>().forEach { node ->
                    if (trackById.getValue(node.trackId).softwareChannelPriority !=
                        softwareChannelPriority
                    ) {
                        throw JsonValidationException(
                            "$label softwareChannelPriority disagrees with track ${node.trackId}",
                        )
                    }
                }
                PackOneShotProgramV2(
                    programId, trigger, true, softwareChannelPriority, roots, nodes,
                    engineEventPolicy, limiterEventPolicy, turboEventPolicy,
                )
            }
            if (programs.size != programs.map { it.id }.toSet().size || programs.size > MAX_ONE_SHOT_PROGRAMS) {
                throw JsonValidationException("oneShotPrograms ids must be unique and bounded")
            }
            val turboNodes = programs.asSequence()
                .filter { it.trigger == PackOneShotTrigger.TURBO_EVENT }
                .flatMap { it.nodes.asSequence() }
                .toList()
            val turboVerificationHashes = turboNodes.map { node ->
                when (node) {
                    is PackOneShotTrackNodeV2 -> requireNotNull(node.sourceVerificationPayloadSha256)
                    is PackOneShotSilentNodeV2 -> node.sourceVerificationPayloadSha256
                    is PackOneShotGroupNodeV2 -> null
                }
            }.filterNotNull()
            val turboSilentGuids = turboNodes.filterIsInstance<PackOneShotSilentNodeV2>()
                .map(PackOneShotSilentNodeV2::sourceGuid)
            if (turboVerificationHashes.size != turboVerificationHashes.toSet().size ||
                turboSilentGuids.size != turboSilentGuids.toSet().size
            ) {
                throw JsonValidationException("TURBO_EVENT source verification identities must be unique")
            }
            val representedTracks = programs.flatMap { program ->
                program.nodes.filterIsInstance<PackOneShotTrackNodeV2>().map(PackOneShotTrackNodeV2::trackId)
            }
            val authoredOneShots = tracks.filter {
                !it.role.loops || it.role == PackTrackRole.LIMITER
            }.map(SoundTrackManifestV1::id)
            if (representedTracks.size != representedTracks.toSet().size ||
                representedTracks.toSet() != authoredOneShots.toSet()
            ) {
                throw JsonValidationException("oneShotPrograms must represent every one-shot track exactly once")
            }
            return programs
        }

        private fun parseEngineEventPolicy(value: JsonValue, label: String): PackEngineEventPolicyV2 {
            val policy = value.asObject(label)
            policy.requireExactKeys(
                label,
                setOf(
                    "kind", "parameterRegions", "armingMode", "initiallyOutsideBehavior",
                    "rearmMode", "overlapMode", "exitBehavior", "coreProgram", "auditionable",
                    "maxDecodedOneShotFrameCount", "laneCount", "logicalVoiceLimit",
                    "softwareRealVoiceBudget",
                ),
            )
            if (policy.getRequired("kind").asString("$label.kind") != "ENGINE_EVENT_REGION") {
                throw JsonValidationException("$label.kind is unsupported")
            }
            val regions = policy.getRequired("parameterRegions").asArray("$label.parameterRegions")
                .mapIndexed { regionIndex, rawRegion ->
                    val regionLabel = "$label.parameterRegions[$regionIndex]"
                    val region = rawRegion.asObject(regionLabel)
                    region.requireExactKeys(
                        regionLabel,
                        setOf("parameterGates", "entryEdges", "triggerOnEventStartIfInside"),
                    )
                    val gates = region.getRequired("parameterGates").asArray("$regionLabel.parameterGates")
                        .mapIndexed { gateIndex, rawGate ->
                            parseOneShotGate(rawGate, "$regionLabel.parameterGates[$gateIndex]")
                        }
                    if (gates.isEmpty() || gates.size != gates.map { it.control }.toSet().size) {
                        throw JsonValidationException("$regionLabel.parameterGates must be non-empty and unique")
                    }
                    val edges = region.getRequired("entryEdges").asArray("$regionLabel.entryEdges")
                        .mapIndexed { edgeIndex, rawEdge ->
                            val edgeLabel = "$regionLabel.entryEdges[$edgeIndex]"
                            val edge = rawEdge.asObject(edgeLabel)
                            edge.requireExactKeys(
                                edgeLabel,
                                setOf("control", "boundary", "direction", "value", "includeBoundary"),
                            )
                            PackEngineEventEntryEdgeV2(
                                control = enumValue(edge.getRequired("control"), "$edgeLabel.control"),
                                boundary = enumValue(edge.getRequired("boundary"), "$edgeLabel.boundary"),
                                direction = enumValue(edge.getRequired("direction"), "$edgeLabel.direction"),
                                value = edge.getRequired("value").asDouble("$edgeLabel.value"),
                                includeBoundary = edge.getRequired("includeBoundary")
                                    .asBoolean("$edgeLabel.includeBoundary"),
                            )
                        }
                    if (edges.isEmpty() || edges.size != edges.toSet().size) {
                        throw JsonValidationException("$regionLabel.entryEdges must be non-empty and unique")
                    }
                    edges.forEach { edge ->
                        val gate = gates.singleOrNull { it.control == edge.control }
                            ?: throw JsonValidationException("$regionLabel edge has no matching gate")
                        val expectedValue = if (edge.boundary == PackEngineEventBoundary.MINIMUM) {
                            gate.minimum
                        } else {
                            gate.maximum
                        }
                        val expectedIncluded = if (edge.boundary == PackEngineEventBoundary.MINIMUM) {
                            gate.includeMinimum
                        } else {
                            gate.includeMaximum
                        }
                        val expectedDirection = if (edge.boundary == PackEngineEventBoundary.MINIMUM) {
                            PackEngineEventDirection.INCREASING
                        } else {
                            PackEngineEventDirection.DECREASING
                        }
                        if (edge.value != expectedValue || edge.includeBoundary != expectedIncluded ||
                            edge.direction != expectedDirection
                        ) {
                            throw JsonValidationException("$regionLabel edge disagrees with its gate")
                        }
                    }
                    if (!region.getRequired("triggerOnEventStartIfInside")
                            .asBoolean("$regionLabel.triggerOnEventStartIfInside")
                    ) {
                        throw JsonValidationException("$regionLabel must trigger when the event starts inside")
                    }
                    PackEngineEventParameterRegionV2(gates, edges, true)
                }
            val armingMode = enumValue<PackEngineEventArmingMode>(
                policy.getRequired("armingMode"), "$label.armingMode",
            )
            if (armingMode != PackEngineEventArmingMode.EVENT_START_INSIDE_REQUIRED) {
                throw JsonValidationException(
                    "$label only supports the proven cabin EVENT_START_INSIDE_REQUIRED contract",
                )
            }
            val initiallyOutside = policy.getRequired("initiallyOutsideBehavior")
                .asNullableString("$label.initiallyOutsideBehavior")
                ?.let { text ->
                    PackEngineEventInitiallyOutsideBehavior.entries.firstOrNull { it.name == text }
                        ?: throw JsonValidationException("$label.initiallyOutsideBehavior is unsupported")
                }
            val rearmMode = enumValue<PackEngineEventRearmMode>(
                policy.getRequired("rearmMode"), "$label.rearmMode",
            )
            if (regions.size != 1 ||
                initiallyOutside != PackEngineEventInitiallyOutsideBehavior.DISABLED_UNTIL_EVENT_RESTART ||
                rearmMode != PackEngineEventRearmMode.AFTER_ANY_GATE_EXIT
            ) {
                throw JsonValidationException("$label cabin region arming contract is inconsistent")
            }
            val maximumFrames = policy.getRequired("maxDecodedOneShotFrameCount")
                .asLong("$label.maxDecodedOneShotFrameCount")
            if (maximumFrames !in 1..Int.MAX_VALUE.toLong()) {
                throw JsonValidationException("$label.maxDecodedOneShotFrameCount is invalid")
            }
            val naturalLaneDemand = (maximumFrames + CONTROL_TICK_FRAMES - 1L) / CONTROL_TICK_FRAMES
            if (naturalLaneDemand > AC_LOGICAL_VOICE_LIMIT) {
                throw JsonValidationException("$label natural logical-voice demand exceeds Assetto Corsa")
            }
            val laneCount = policy.getRequired("laneCount").asLong("$label.laneCount")
            if (laneCount != naturalLaneDemand) {
                throw JsonValidationException("$label.laneCount does not match the decoded PCM bound")
            }
            requireInteger(
                policy.getRequired("logicalVoiceLimit"), "$label.logicalVoiceLimit",
                AC_LOGICAL_VOICE_LIMIT,
            )
            requireInteger(
                policy.getRequired("softwareRealVoiceBudget"), "$label.softwareRealVoiceBudget",
                AC_SOFTWARE_REAL_VOICE_BUDGET,
            )
            if (!policy.getRequired("coreProgram").asBoolean("$label.coreProgram") ||
                policy.getRequired("auditionable").asBoolean("$label.auditionable")
            ) {
                throw JsonValidationException("$label must be a non-auditionable core program")
            }
            return PackEngineEventPolicyV2(
                parameterRegions = regions,
                armingMode = armingMode,
                initiallyOutsideBehavior = initiallyOutside,
                rearmMode = rearmMode,
                overlapMode = enumValue(policy.getRequired("overlapMode"), "$label.overlapMode"),
                exitBehavior = enumValue(policy.getRequired("exitBehavior"), "$label.exitBehavior"),
                coreProgram = true,
                auditionable = false,
                maximumDecodedOneShotFrames = maximumFrames.toInt(),
                laneCount = laneCount.toInt(),
                logicalVoiceLimit = AC_LOGICAL_VOICE_LIMIT.toInt(),
                softwareRealVoiceBudget = AC_SOFTWARE_REAL_VOICE_BUDGET.toInt(),
            )
        }

        private fun parseLimiterEventPolicy(value: JsonValue, label: String): PackLimiterEventPolicyV2 {
            val policy = value.asObject(label)
            policy.requireExactKeys(
                label,
                setOf(
                    "kind", "programMode", "sourceLifetime", "decayParameter",
                    "decayGainCurve", "decayPlacement", "timelinePlacement",
                    "runtimeLifecycle", "sourceScheduling", "voicePolicy",
                    "targetCaptureBakedModulators", "sourceVerificationPayloadSha256",
                ),
            )
            if (policy.getRequired("kind").asString("$label.kind") != "PERSISTENT_LIMITER_EVENT") {
                throw JsonValidationException("$label.kind is unsupported")
            }
            val mode = enumValue<PackLimiterProgramMode>(
                policy.getRequired("programMode"), "$label.programMode",
            )
            val expectedLifetime = when (mode) {
                PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT,
                PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT ->
                    PackLimiterSourceLifetime.ONE_SHOT
                PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP ->
                    PackLimiterSourceLifetime.CONTINUOUS
            }
            if (policy.getRequired("sourceLifetime").asString("$label.sourceLifetime") !=
                expectedLifetime.manifestValue
            ) {
                throw JsonValidationException("$label.sourceLifetime disagrees with programMode")
            }

            val decayParameterLabel = "$label.decayParameter"
            val decayParameter = policy.getRequired("decayParameter").asObject(decayParameterLabel)
            decayParameter.requireExactKeys(
                decayParameterLabel,
                setOf("control", "minimum", "maximum", "defaultValue", "runtimeInput"),
            )
            if (decayParameter.getRequired("control").asString("$decayParameterLabel.control") !=
                "LIMITER_DECAY_SECONDS" ||
                decayParameter.getRequired("minimum").asDouble("$decayParameterLabel.minimum") != 0.0 ||
                decayParameter.getRequired("maximum").asDouble("$decayParameterLabel.maximum") != 1.0 ||
                decayParameter.getRequired("defaultValue").asDouble("$decayParameterLabel.defaultValue") != 0.0 ||
                decayParameter.getRequired("runtimeInput").asString("$decayParameterLabel.runtimeInput") !=
                "min(hostFloat32DecayTimerSeconds,1)"
            ) {
                throw JsonValidationException("$decayParameterLabel changed from the proven contract")
            }
            val decayCurve = parseCurve(
                policy.getRequired("decayGainCurve"), "$label.decayGainCurve", normalizedInput = true,
            )
            if (decayCurve.isEmpty() || decayCurve.first().input != 0.0 ||
                decayCurve.last().input != 1.0
            ) {
                throw JsonValidationException("$label.decayGainCurve must span exactly 0..1")
            }

            val decayPlacement = when (val rawPlacement = policy.getRequired("decayPlacement")) {
                JsonValue.NullValue -> null
                else -> {
                    val placementLabel = "$label.decayPlacement"
                    val placement = rawPlacement.asObject(placementLabel)
                    placement.requireExactKeys(
                        placementLabel,
                        setOf("control", "minimum", "maximum", "includeMinimum", "includeMaximum"),
                    )
                    if (placement.getRequired("control").asString("$placementLabel.control") !=
                        "LIMITER_DECAY_SECONDS"
                    ) {
                        throw JsonValidationException("$placementLabel.control is unsupported")
                    }
                    val minimum = placement.getRequired("minimum").asDouble("$placementLabel.minimum")
                    val maximum = placement.getRequired("maximum").asDouble("$placementLabel.maximum")
                    if (minimum < 0.0 || minimum >= maximum || maximum > 1.0) {
                        throw JsonValidationException("$placementLabel bounds are invalid")
                    }
                    PackLimiterDecayPlacementV2(
                        minimum,
                        maximum,
                        placement.getRequired("includeMinimum").asBoolean("$placementLabel.includeMinimum"),
                        placement.getRequired("includeMaximum").asBoolean("$placementLabel.includeMaximum"),
                    )
                }
            }
            if ((mode == PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT) !=
                (decayPlacement == null)
            ) {
                throw JsonValidationException("$label.decayPlacement disagrees with programMode")
            }

            val timelinePlacement = when (val rawTimeline = policy.getRequired("timelinePlacement")) {
                JsonValue.NullValue -> null
                else -> {
                    val timelineLabel = "$label.timelinePlacement"
                    val timeline = rawTimeline.asObject(timelineLabel)
                    timeline.requireExactKeys(
                        timelineLabel,
                        setOf(
                            "startTicks", "lengthTicks", "timeLocked", "tickRateHz",
                            "startFrameAt48k", "periodFramesAt48k",
                        ),
                    )
                    requireInteger(timeline.getRequired("startTicks"), "$timelineLabel.startTicks", 0)
                    requireInteger(
                        timeline.getRequired("startFrameAt48k"), "$timelineLabel.startFrameAt48k", 0,
                    )
                    requireInteger(timeline.getRequired("tickRateHz"), "$timelineLabel.tickRateHz", 48_000)
                    if (!timeline.getRequired("timeLocked").asBoolean("$timelineLabel.timeLocked")) {
                        throw JsonValidationException("$timelineLabel must be time locked")
                    }
                    val length = timeline.getRequired("lengthTicks").asLong("$timelineLabel.lengthTicks")
                    val period = timeline.getRequired("periodFramesAt48k")
                        .asLong("$timelineLabel.periodFramesAt48k")
                    if (length !in 1..Int.MAX_VALUE.toLong() || period != length) {
                        throw JsonValidationException("$timelineLabel period is invalid")
                    }
                    PackLimiterTimelinePlacementV2(length.toInt(), period.toInt())
                }
            }
            if ((mode == PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT) !=
                (timelinePlacement != null)
            ) {
                throw JsonValidationException("$label.timelinePlacement disagrees with programMode")
            }

            validateLimiterLifecycle(policy.getRequired("runtimeLifecycle"), "$label.runtimeLifecycle")
            validateLimiterScheduling(
                policy.getRequired("sourceScheduling"), "$label.sourceScheduling", mode,
            )
            val voicePolicy = policy.getRequired("voicePolicy").asObject("$label.voicePolicy")
            voicePolicy.requireExactKeys(
                "$label.voicePolicy",
                setOf(
                    "maximumSimultaneousProgramTracks", "oneShotLaneBoundAfterDecode",
                    "acGlobalLogicalVoiceCap", "acDefaultSoftwareRealVoiceBudget",
                ),
            )
            requireInteger(
                voicePolicy.getRequired("acGlobalLogicalVoiceCap"),
                "$label.voicePolicy.acGlobalLogicalVoiceCap", AC_LOGICAL_VOICE_LIMIT,
            )
            requireInteger(
                voicePolicy.getRequired("acDefaultSoftwareRealVoiceBudget"),
                "$label.voicePolicy.acDefaultSoftwareRealVoiceBudget", AC_SOFTWARE_REAL_VOICE_BUDGET,
            )
            val expectedMaximum =
                if (mode == PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT) null else 1L
            val maximum = nullableInteger(
                voicePolicy.getRequired("maximumSimultaneousProgramTracks"),
                "$label.voicePolicy.maximumSimultaneousProgramTracks",
            )
            if (maximum != expectedMaximum) {
                throw JsonValidationException("$label.voicePolicy maximum is invalid")
            }
            val laneBound = voicePolicy.getRequired("oneShotLaneBoundAfterDecode")
                .asNullableString("$label.voicePolicy.oneShotLaneBoundAfterDecode")
            val expectedLaneBound = if (mode == PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT) {
                "min(2048,ceil(decodedOneShotFrames/480))"
            } else {
                null
            }
            if (laneBound != expectedLaneBound) {
                throw JsonValidationException("$label.voicePolicy one-shot lane bound is invalid")
            }

            val modulators = policy.getRequired("targetCaptureBakedModulators")
                .asArray("$label.targetCaptureBakedModulators")
                .mapIndexed { index, rawModulator ->
                    val modulatorLabel = "$label.targetCaptureBakedModulators[$index]"
                    val modulator = rawModulator.asObject(modulatorLabel)
                    modulator.requireExactKeys(
                        modulatorLabel, setOf("guid", "ownerGuid", "type", "propertyIndex"),
                    )
                    val modulatorGuid = modulator.getRequired("guid").asString("$modulatorLabel.guid")
                    val ownerGuid = modulator.getRequired("ownerGuid")
                        .asString("$modulatorLabel.ownerGuid")
                    if (!guid.matches(modulatorGuid) || !guid.matches(ownerGuid) ||
                        modulator.getRequired("type").asString("$modulatorLabel.type") != "ADSR"
                    ) {
                        throw JsonValidationException("$modulatorLabel is invalid")
                    }
                    requireInteger(
                        modulator.getRequired("propertyIndex"), "$modulatorLabel.propertyIndex", 0,
                    )
                    PackLimiterBakedModulatorV2(modulatorGuid, ownerGuid)
                }
            if (modulators.size > 1) {
                throw JsonValidationException("$label.targetCaptureBakedModulators is not bounded")
            }
            val sourceVerificationPayloadSha256 = requireSha(
                policy.getRequired("sourceVerificationPayloadSha256"),
                "$label.sourceVerificationPayloadSha256",
            )
            return PackLimiterEventPolicyV2(
                mode, expectedLifetime, decayCurve, decayPlacement, timelinePlacement,
                maximum?.toInt(), laneBound != null, modulators,
                sourceVerificationPayloadSha256,
            )
        }

        private fun parseTurboEventPolicy(value: JsonValue, label: String): PackTurboEventPolicyV2 {
            val policy = value.asObject(label)
            policy.requireExactKeys(
                label,
                setOf(
                    "kind", "programMode", "programPlacementRootInstrumentGuid",
                    "placementSignature", "programTriggerTemplate", "voicePolicy",
                    "runtimeControlSemantics", "coreProgram", "auditionable",
                ),
            )
            if (policy.getRequired("kind").asString("$label.kind") != "TURBO_EVENT_PROGRAM") {
                throw JsonValidationException("$label.kind is unsupported")
            }
            val mode = enumValue<PackTurboEventProgramMode>(
                policy.getRequired("programMode"), "$label.programMode",
            )
            val placementRoot = policy.getRequired("programPlacementRootInstrumentGuid")
                .asString("$label.programPlacementRootInstrumentGuid")
            if (!guid.matches(placementRoot)) {
                throw JsonValidationException("$label.programPlacementRootInstrumentGuid is invalid")
            }
            val placementLabel = "$label.placementSignature"
            val placement = policy.getRequired("placementSignature").asObject(placementLabel)
            val templateLabel = "$label.programTriggerTemplate"
            val template = policy.getRequired("programTriggerTemplate").asObject(templateLabel)

            var placementMinimum: Double? = null
            var placementMaximum: Double? = null
            var includeMinimum = true
            var includeMaximum = true
            var timelineStart: Long? = null
            var timelinePeriod: Long? = null
            when (mode) {
                PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT -> {
                    placement.requireExactKeys(
                        placementLabel,
                        setOf("kind", "instrumentGuid", "startTick", "lengthTicks", "timeLocked"),
                    )
                    template.requireExactKeys(
                        templateLabel,
                        setOf(
                            "trigger", "startTick", "periodTicks", "ticksPerSecond",
                            "overlapMode", "exitBehavior",
                        ),
                    )
                    val start = placement.getRequired("startTick").asLong("$placementLabel.startTick")
                    val length = placement.getRequired("lengthTicks").asLong("$placementLabel.lengthTicks")
                    if (placement.getRequired("kind").asString("$placementLabel.kind") != "timeline" ||
                        placement.getRequired("instrumentGuid").asString("$placementLabel.instrumentGuid") !=
                        placementRoot ||
                        !placement.getRequired("timeLocked").asBoolean("$placementLabel.timeLocked") ||
                        start < 0L || length <= 0L ||
                        template.getRequired("trigger").asString("$templateLabel.trigger") !=
                        "EVENT_TIMELINE_PERIODIC" ||
                        template.getRequired("startTick").asLong("$templateLabel.startTick") != start ||
                        template.getRequired("periodTicks").asLong("$templateLabel.periodTicks") != length
                    ) {
                        throw JsonValidationException("$label timeline lifecycle changed")
                    }
                    requireInteger(
                        template.getRequired("ticksPerSecond"), "$templateLabel.ticksPerSecond", 48_000,
                    )
                    if (template.getRequired("overlapMode").asString("$templateLabel.overlapMode") !=
                        "ALLOW_OVERLAP" ||
                        template.getRequired("exitBehavior").asString("$templateLabel.exitBehavior") !=
                        "NOT_APPLICABLE"
                    ) {
                        throw JsonValidationException("$label timeline lifecycle changed")
                    }
                    timelineStart = start
                    timelinePeriod = length
                }
                PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT,
                PackTurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT -> {
                    placement.requireExactKeys(
                        placementLabel,
                        setOf(
                            "kind", "instrumentGuid", "parameter", "parameterGuid", "minimum",
                            "maximum", "authoredMaximum", "includeMaximum",
                        ),
                    )
                    val minimum = placement.getRequired("minimum").asDouble("$placementLabel.minimum")
                    val maximum = placement.getRequired("maximum").asDouble("$placementLabel.maximum")
                    val authoredMaximum = placement.getRequired("authoredMaximum")
                        .asDouble("$placementLabel.authoredMaximum")
                    val placementIncludesMaximum = placement.getRequired("includeMaximum")
                        .asBoolean("$placementLabel.includeMaximum")
                    if (placement.getRequired("kind").asString("$placementLabel.kind") != "parameter" ||
                        placement.getRequired("instrumentGuid").asString("$placementLabel.instrumentGuid") !=
                        placementRoot ||
                        placement.getRequired("parameter").asString("$placementLabel.parameter") != "boost" ||
                        !guid.matches(
                            placement.getRequired("parameterGuid").asString("$placementLabel.parameterGuid"),
                        ) || minimum < 0.0 || minimum >= maximum || maximum > 1.5 ||
                        authoredMaximum < maximum
                    ) {
                        throw JsonValidationException("$label parameter placement changed")
                    }
                    placementMinimum = minimum
                    placementMaximum = maximum
                    includeMaximum = placementIncludesMaximum
                    if (mode == PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT) {
                        template.requireExactKeys(
                            templateLabel,
                            setOf(
                                "trigger", "parameter", "minimum", "maximum", "includeMinimum",
                                "includeMaximum", "entryEdges", "armingMode",
                                "initiallyOutsideBehavior", "rearmMode", "overlapMode", "exitBehavior",
                            ),
                        )
                        val edges = template.getRequired("entryEdges").asArray("$templateLabel.entryEdges")
                        if (edges.size != 1) {
                            throw JsonValidationException("$templateLabel.entryEdges changed")
                        }
                        val edgeLabel = "$templateLabel.entryEdges[0]"
                        val edge = edges.single().asObject(edgeLabel)
                        edge.requireExactKeys(
                            edgeLabel, setOf("boundary", "direction", "value", "includeBoundary"),
                        )
                        if (template.getRequired("trigger").asString("$templateLabel.trigger") !=
                            "EVENT_START_ARMED_PARAMETER_REGION_REENTRY" ||
                            template.getRequired("parameter").asString("$templateLabel.parameter") != "boost" ||
                            template.getRequired("minimum").asDouble("$templateLabel.minimum") != minimum ||
                            template.getRequired("maximum").asDouble("$templateLabel.maximum") != maximum ||
                            !template.getRequired("includeMinimum").asBoolean("$templateLabel.includeMinimum") ||
                            template.getRequired("includeMaximum").asBoolean("$templateLabel.includeMaximum") !=
                            placementIncludesMaximum ||
                            edge.getRequired("boundary").asString("$edgeLabel.boundary") != "MAXIMUM" ||
                            edge.getRequired("direction").asString("$edgeLabel.direction") != "DECREASING" ||
                            edge.getRequired("value").asDouble("$edgeLabel.value") != maximum ||
                            edge.getRequired("includeBoundary").asBoolean("$edgeLabel.includeBoundary") !=
                            placementIncludesMaximum ||
                            template.getRequired("armingMode").asString("$templateLabel.armingMode") !=
                            "ARMED_WHEN_EVENT_STARTS_INSIDE_OR_OUTSIDE" ||
                            template.getRequired("initiallyOutsideBehavior")
                                .asString("$templateLabel.initiallyOutsideBehavior") !=
                            "SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY" ||
                            template.getRequired("rearmMode").asString("$templateLabel.rearmMode") !=
                            "AFTER_ANY_GATE_EXIT" ||
                            template.getRequired("overlapMode").asString("$templateLabel.overlapMode") !=
                            "ALLOW_OVERLAP" ||
                            template.getRequired("exitBehavior").asString("$templateLabel.exitBehavior") !=
                            "LET_ACTIVE_VOICES_FINISH"
                        ) {
                            throw JsonValidationException("$label boost-release lifecycle changed")
                        }
                    } else {
                        template.requireExactKeys(
                            templateLabel,
                            setOf(
                                "trigger", "parameter", "parameterRegionCoversEntireDomain",
                                "rearmMode", "overlapMode", "exitBehavior",
                            ),
                        )
                        if (template.getRequired("trigger").asString("$templateLabel.trigger") != "EVENT_START" ||
                            template.getRequired("parameter").asString("$templateLabel.parameter") != "boost" ||
                            !template.getRequired("parameterRegionCoversEntireDomain")
                                .asBoolean("$templateLabel.parameterRegionCoversEntireDomain") ||
                            template.getRequired("rearmMode").asString("$templateLabel.rearmMode") !=
                            "NONE_WITHOUT_EVENT_RESTART" ||
                            template.getRequired("overlapMode").asString("$templateLabel.overlapMode") !=
                            "ONE_VOICE_PER_EVENT_START" ||
                            template.getRequired("exitBehavior").asString("$templateLabel.exitBehavior") !=
                            "LET_ACTIVE_VOICE_FINISH"
                        ) {
                            throw JsonValidationException("$label event-start lifecycle changed")
                        }
                    }
                }
            }

            val voiceLabel = "$label.voicePolicy"
            val voice = policy.getRequired("voicePolicy").asObject(voiceLabel)
            voice.requireExactKeys(
                voiceLabel,
                setOf(
                    "softwareChannelPriority", "priorityRequiredFromSourceBoundOracle",
                    "acGlobalLogicalVoiceCap", "acDefaultSoftwareRealVoiceBudget",
                    "overlapSharesGlobalBudget",
                ),
            )
            requireInteger(
                voice.getRequired("softwareChannelPriority"),
                "$voiceLabel.softwareChannelPriority", FMOD_DEFAULT_EVENT_PRIORITY.toLong(),
            )
            requireInteger(
                voice.getRequired("acGlobalLogicalVoiceCap"),
                "$voiceLabel.acGlobalLogicalVoiceCap", AC_LOGICAL_VOICE_LIMIT,
            )
            requireInteger(
                voice.getRequired("acDefaultSoftwareRealVoiceBudget"),
                "$voiceLabel.acDefaultSoftwareRealVoiceBudget", AC_SOFTWARE_REAL_VOICE_BUDGET,
            )
            if (voice.getRequired("priorityRequiredFromSourceBoundOracle")
                    .asBoolean("$voiceLabel.priorityRequiredFromSourceBoundOracle") ||
                !voice.getRequired("overlapSharesGlobalBudget")
                    .asBoolean("$voiceLabel.overlapSharesGlobalBudget")
            ) {
                throw JsonValidationException("$voiceLabel is not the certified global-voice contract")
            }

            val semanticsLabel = "$label.runtimeControlSemantics"
            val semantics = policy.getRequired("runtimeControlSemantics").asObject(semanticsLabel)
            val expectedSemantics = mapOf(
                "boost" to "AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN",
                "bov" to "AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED",
                "bov_decay" to "AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED",
                "propertyZero" to "DB_VOLUME",
                "propertyOne" to "RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE",
                "propertyFour" to "LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH",
            )
            semantics.requireExactKeys(
                semanticsLabel, expectedSemantics.keys + "autoPitchFromParameterPlacement",
            )
            expectedSemantics.forEach { (name, expected) ->
                if (semantics.getRequired(name).asString("$semanticsLabel.$name") != expected) {
                    throw JsonValidationException("$semanticsLabel.$name changed")
                }
            }
            if (semantics.getRequired("autoPitchFromParameterPlacement")
                    .asBoolean("$semanticsLabel.autoPitchFromParameterPlacement")
            ) {
                throw JsonValidationException("$semanticsLabel auto-pitch contract changed")
            }
            val expectedCore = mode != PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT
            if (policy.getRequired("coreProgram").asBoolean("$label.coreProgram") != expectedCore ||
                policy.getRequired("auditionable").asBoolean("$label.auditionable")
            ) {
                throw JsonValidationException("$label identity/exposure is invalid")
            }
            return PackTurboEventPolicyV2(
                programMode = mode,
                placementMinimumBoost = placementMinimum,
                placementMaximumBoost = placementMaximum,
                includeMinimum = includeMinimum,
                includeMaximum = includeMaximum,
                timelineStartFrames = timelineStart,
                timelinePeriodFrames = timelinePeriod,
                coreProgram = expectedCore,
            )
        }

        private fun validateLimiterLifecycle(value: JsonValue, label: String) {
            val lifecycle = value.asObject(label)
            lifecycle.requireExactKeys(
                label,
                setOf(
                    "owner", "initialHostDecayTimerSeconds", "updateOrder",
                    "eventDesiredActiveWhen", "inactiveThreshold", "activeEventAction",
                    "inactiveEventAction", "limiterPulseWhileEventActive",
                    "reactivationAfterInactive", "executableEvidence",
                ),
            )
            fun exact(name: String, expected: String) {
                if (lifecycle.getRequired(name).asString("$label.$name") != expected) {
                    throw JsonValidationException("$label.$name changed from executable evidence")
                }
            }
            exact("owner", "ONE_PERSISTENT_LIMITER_EVENT_INSTANCE")
            if (lifecycle.getRequired("initialHostDecayTimerSeconds")
                    .asDouble("$label.initialHostDecayTimerSeconds") != 10.0
            ) {
                throw JsonValidationException("$label.initialHostDecayTimerSeconds must be float32 10")
            }
            val updateOrder = lifecycle.getRequired("updateOrder").asArray("$label.updateOrder")
                .mapIndexed { index, item -> item.asString("$label.updateOrder[$index]") }
            if (updateOrder != LIMITER_UPDATE_ORDER) {
                throw JsonValidationException("$label.updateOrder changed from executable evidence")
            }
            exact("eventDesiredActiveWhen", "driveAudioActive && limiterEnabled && hostDecayTimerSeconds<=10")
            exact(
                "activeEventAction",
                "UNPAUSE_IF_PAUSED_ELSE_REWIND_TIMELINE_ZERO_AND_START_IF_STOPPED",
            )
            exact("inactiveEventAction", "STOP_ALLOWFADEOUT")
            exact(
                "limiterPulseWhileEventActive",
                "RESET_DECAY_ONLY_PRESERVE_EVENT_TIMELINE_AND_ACTIVE_SOURCE_PHASE",
            )
            exact(
                "reactivationAfterInactive",
                "SET_DECAY_ZERO_THEN_REWIND_TIMELINE_ZERO_THEN_START",
            )
            val thresholdLabel = "$label.inactiveThreshold"
            val threshold = lifecycle.getRequired("inactiveThreshold").asObject(thresholdLabel)
            threshold.requireExactKeys(thresholdLabel, setOf("comparison", "seconds"))
            if (threshold.getRequired("comparison").asString("$thresholdLabel.comparison") !=
                "STRICTLY_GREATER_THAN" ||
                threshold.getRequired("seconds").asDouble("$thresholdLabel.seconds") != 10.0
            ) {
                throw JsonValidationException("$thresholdLabel changed from executable evidence")
            }
            val evidenceLabel = "$label.executableEvidence"
            val evidence = lifecycle.getRequired("executableEvidence").asObject(evidenceLabel)
            evidence.requireExactKeys(evidenceLabel, LIMITER_EXECUTABLE_EVIDENCE.keys)
            LIMITER_EXECUTABLE_EVIDENCE.forEach { (name, expected) ->
                if (evidence.getRequired(name).asString("$evidenceLabel.$name") != expected) {
                    throw JsonValidationException("$evidenceLabel.$name is not the pinned acs.exe range")
                }
            }
        }

        private fun validateLimiterScheduling(
            value: JsonValue,
            label: String,
            mode: PackLimiterProgramMode,
        ) {
            val scheduling = value.asObject(label)
            scheduling.requireExactKeys(
                label,
                setOf(
                    "timelinePeriodicOneShot", "parameterRegionEntry", "sameInsideValueBehavior",
                    "placementExitBehavior", "overlapMode",
                ),
            )
            fun nullable(name: String) = scheduling.getRequired(name).asNullableString("$label.$name")
            val actual = listOf(
                nullable("timelinePeriodicOneShot"),
                nullable("parameterRegionEntry"),
                nullable("sameInsideValueBehavior"),
                nullable("placementExitBehavior"),
                nullable("overlapMode"),
            )
            val expected = when (mode) {
                PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT -> listOf(
                    "EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER", null, "DO_NOT_RETRIGGER",
                    "TIMELINE_OWNS_SOURCE_LIFETIME", "ONE_RENDERED_TIMELINE_LOOP_TRACK",
                )
                PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT -> listOf(
                    null, "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY",
                    "DO_NOT_RETRIGGER", "LET_ACTIVE_ONE_SHOTS_FINISH",
                    "ALLOW_OVERLAPPING_ONE_SHOT_VOICES",
                )
                PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP -> listOf(
                    null, "SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY",
                    "DO_NOT_RETRIGGER",
                    "STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY",
                    "ONE_ACTIVE_LOOP_VOICE",
                )
            }
            if (actual != expected) throw JsonValidationException("$label disagrees with programMode")
        }

        private fun parseOneShotNode(
            value: JsonValue,
            label: String,
            trigger: PackOneShotTrigger,
        ): PackOneShotNodeV2 {
            val node = value.asObject(label)
            val kind = node.getRequired("kind").asString("$label.kind")
            return when (kind) {
                "GROUP" -> {
                    node.requireExactKeys(
                        label,
                        setOf("id", "kind", "triggerChance", "playMode", "selectionMode", "members"),
                    )
                    val members = node.getRequired("members").asArray("$label.members")
                        .mapIndexed { memberIndex, rawMember ->
                            val memberLabel = "$label.members[$memberIndex]"
                            val member = rawMember.asObject(memberLabel)
                            member.requireExactKeys(memberLabel, setOf("nodeId", "weight", "order"))
                            val weight = positive(member.getRequired("weight"), "$memberLabel.weight")
                            val order = member.getRequired("order").asLong("$memberLabel.order")
                            if (order !in 0..Int.MAX_VALUE.toLong()) {
                                throw JsonValidationException("$memberLabel.order is invalid")
                            }
                            PackOneShotMemberV2(
                                nodeId = requireIdentifier(member.getRequired("nodeId"), "$memberLabel.nodeId"),
                                weight = weight,
                                order = order.toInt(),
                            )
                        }
                    PackOneShotGroupNodeV2(
                        id = requireIdentifier(node.getRequired("id"), "$label.id"),
                        triggerChance = probability(node.getRequired("triggerChance"), "$label.triggerChance"),
                        playMode = enumValue(node.getRequired("playMode"), "$label.playMode"),
                        selectionMode = enumValue(node.getRequired("selectionMode"), "$label.selectionMode"),
                        members = members,
                    )
                }
                "TRACK" -> {
                    val turboFields = if (trigger == PackOneShotTrigger.TURBO_EVENT) {
                        setOf(
                            "captureControlValues", "controlGainCurves", "pitchAutomations",
                            "sourceVerificationPayloadSha256",
                        )
                    } else {
                        emptySet()
                    }
                    val engineFields = if (trigger == PackOneShotTrigger.ENGINE_EVENT) {
                        setOf("pitchTreatment", "sourceVerification")
                    } else {
                        emptySet()
                    }
                    node.requireExactKeys(
                        label,
                        setOf(
                            "id", "kind", "trackId", "triggerChance", "parameterGates",
                            "rpmCurve", "gainCurve", "liveVarispeed", "rootRpm",
                        ) + turboFields + engineFields,
                    )
                    val gates = node.getRequired("parameterGates").asArray("$label.parameterGates")
                        .mapIndexed { gateIndex, rawGate ->
                            parseOneShotGate(rawGate, "$label.parameterGates[$gateIndex]")
                        }
                    if (gates.size != gates.map { it.control }.toSet().size) {
                        throw JsonValidationException("$label.parameterGates controls must be unique")
                    }
                    val captureControlValues = if (trigger == PackOneShotTrigger.TURBO_EVENT) {
                        node.getRequired("captureControlValues").asArray("$label.captureControlValues")
                            .mapIndexed { index, rawValue ->
                                val valueLabel = "$label.captureControlValues[$index]"
                                val controlValue = rawValue.asObject(valueLabel)
                                controlValue.requireExactKeys(valueLabel, setOf("control", "value"))
                                val control = parseTurboControl(
                                    controlValue.getRequired("control"), "$valueLabel.control",
                                )
                                val captured = controlValue.getRequired("value").asDouble("$valueLabel.value")
                                if (captured !in 0.0..turboControlMaximum(control)) {
                                    throw JsonValidationException("$valueLabel is outside its authored domain")
                                }
                                PackOneShotControlValueV2(control, captured)
                            }.also { values ->
                                if (values.isEmpty() || values.size != values.map { it.control }.toSet().size) {
                                    throw JsonValidationException(
                                        "$label.captureControlValues must be non-empty and unique",
                                    )
                                }
                            }
                    } else {
                        emptyList()
                    }
                    val controlGainCurves = if (trigger == PackOneShotTrigger.TURBO_EVENT) {
                        node.getRequired("controlGainCurves").asArray("$label.controlGainCurves")
                            .mapIndexed { index, rawCurve ->
                                val curveLabel = "$label.controlGainCurves[$index]"
                                val controlCurve = rawCurve.asObject(curveLabel)
                                controlCurve.requireExactKeys(curveLabel, setOf("control", "curve"))
                                val control = parseTurboControl(
                                    controlCurve.getRequired("control"), "$curveLabel.control",
                                )
                                PackOneShotControlCurveV2(
                                    control,
                                    parseTurboControlCurve(
                                        controlCurve.getRequired("curve"), "$curveLabel.curve",
                                        control,
                                        requirePositiveOutput = false,
                                        maximumOutput = MAX_TURBO_CONTROL_GAIN,
                                    ),
                                )
                            }.also { curves ->
                                if (curves.isEmpty() || curves.size > 3 ||
                                    curves.size != curves.map { it.control }.toSet().size
                                ) {
                                    throw JsonValidationException(
                                        "$label.controlGainCurves must be non-empty, unique, and bounded",
                                    )
                                }
                            }
                    } else {
                        emptyList()
                    }
                    val pitchAutomations = if (trigger == PackOneShotTrigger.TURBO_EVENT) {
                        node.getRequired("pitchAutomations").asArray("$label.pitchAutomations")
                            .mapIndexed { index, rawAutomation ->
                                val automationLabel = "$label.pitchAutomations[$index]"
                                val automation = rawAutomation.asObject(automationLabel)
                                automation.requireExactKeys(
                                    automationLabel,
                                    setOf(
                                        "control", "propertyIndex", "rawValueToSemitonesScale",
                                        "captureSemitones", "playbackRateCurve", "runtimeTreatment",
                                        "updatesWhileVoiceActive", "continuesOutsideSchedulingRegion",
                                        "captureRate",
                                    ),
                                )
                                val control = parseTurboControl(
                                    automation.getRequired("control"), "$automationLabel.control",
                                )
                                requireInteger(
                                    automation.getRequired("propertyIndex"),
                                    "$automationLabel.propertyIndex", 1,
                                )
                                if (automation.getRequired("rawValueToSemitonesScale")
                                        .asDouble("$automationLabel.rawValueToSemitonesScale") != 24.0 ||
                                    automation.getRequired("runtimeTreatment")
                                        .asString("$automationLabel.runtimeTreatment") !=
                                    "multiplyActiveVoiceRateContinuously" ||
                                    !automation.getRequired("updatesWhileVoiceActive")
                                        .asBoolean("$automationLabel.updatesWhileVoiceActive") ||
                                    !automation.getRequired("continuesOutsideSchedulingRegion")
                                        .asBoolean("$automationLabel.continuesOutsideSchedulingRegion") ||
                                    automation.getRequired("captureRate")
                                        .asDouble("$automationLabel.captureRate") != 1.0
                                ) {
                                    throw JsonValidationException("$automationLabel contract changed")
                                }
                                PackOneShotPitchAutomationV2(
                                    control = control,
                                    captureSemitones = automation.getRequired("captureSemitones")
                                        .asDouble("$automationLabel.captureSemitones"),
                                    playbackRateCurve = parseTurboControlCurve(
                                        automation.getRequired("playbackRateCurve"),
                                        "$automationLabel.playbackRateCurve", control,
                                        requirePositiveOutput = true,
                                    ),
                                )
                            }.also { automations ->
                                if (automations.size > 3 ||
                                    automations.size != automations.map { it.control }.toSet().size
                                ) {
                                    throw JsonValidationException(
                                        "$label.pitchAutomations controls must be unique and bounded",
                                    )
                                }
                            }
                    } else {
                        emptyList()
                    }
                    val liveVarispeed = node.getRequired("liveVarispeed")
                        .asBoolean("$label.liveVarispeed")
                    val rootRpm = nullableNumber(node.getRequired("rootRpm"), "$label.rootRpm")
                    val enginePitchTreatment = if (trigger == PackOneShotTrigger.ENGINE_EVENT) {
                        parseEngineTransientPitchTreatment(
                            value = node.getRequired("pitchTreatment"),
                            sourceVerificationValue = node.getRequired("sourceVerification"),
                            label = "$label.pitchTreatment",
                            liveVarispeed = liveVarispeed,
                            rootRpm = rootRpm,
                        )
                    } else {
                        null
                    }
                    val sourceVerificationPayloadSha256 = when (trigger) {
                        PackOneShotTrigger.TURBO_EVENT -> requireSha(
                            node.getRequired("sourceVerificationPayloadSha256"),
                            "$label.sourceVerificationPayloadSha256",
                        )
                        PackOneShotTrigger.ENGINE_EVENT ->
                            requireNotNull(enginePitchTreatment).sourceVerificationPayloadSha256
                        else -> null
                    }
                    PackOneShotTrackNodeV2(
                        id = requireIdentifier(node.getRequired("id"), "$label.id"),
                        triggerChance = probability(node.getRequired("triggerChance"), "$label.triggerChance"),
                        trackId = requireIdentifier(node.getRequired("trackId"), "$label.trackId"),
                        parameterGates = gates,
                        rpmCurve = parseCurve(node.getRequired("rpmCurve"), "$label.rpmCurve", normalizedInput = false),
                        gainCurve = parseCurve(node.getRequired("gainCurve"), "$label.gainCurve", normalizedInput = true),
                        liveVarispeed = liveVarispeed,
                        rootRpm = rootRpm,
                        captureControlValues = captureControlValues,
                        controlGainCurves = controlGainCurves,
                        pitchAutomations = pitchAutomations,
                        sourceVerificationPayloadSha256 = sourceVerificationPayloadSha256,
                        zeroGainVirtualization = enginePitchTreatment?.zeroGainVirtualization
                            ?: PackZeroGainVirtualizationV2.NOT_APPLICABLE,
                        engineTransientReentryPolicy = enginePitchTreatment?.reentryPolicy
                            ?: PackEngineTransientReentryPolicy
                                .CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
                    )
                }
                "SILENT_SOURCE" -> {
                    node.requireExactKeys(
                        label,
                        setOf(
                            "id", "kind", "triggerChance", "sourceGuid", "resolvedRole",
                            "sourceVerificationPayloadSha256",
                        ),
                    )
                    if (trigger != PackOneShotTrigger.TURBO_EVENT) {
                        throw JsonValidationException("$label SILENT_SOURCE requires TURBO_EVENT")
                    }
                    val sourceGuid = node.getRequired("sourceGuid").asString("$label.sourceGuid")
                    if (!guid.matches(sourceGuid)) throw JsonValidationException("$label.sourceGuid is invalid")
                    val resolvedRole = enumValue<PackTrackRole>(
                        node.getRequired("resolvedRole"), "$label.resolvedRole",
                    )
                    if (resolvedRole != PackTrackRole.BOV &&
                        resolvedRole != PackTrackRole.TURBO_TRANSIENT
                    ) {
                        throw JsonValidationException("$label.resolvedRole is not a turbo transient role")
                    }
                    PackOneShotSilentNodeV2(
                        id = requireIdentifier(node.getRequired("id"), "$label.id"),
                        triggerChance = probability(node.getRequired("triggerChance"), "$label.triggerChance"),
                        sourceGuid = sourceGuid,
                        resolvedRole = resolvedRole,
                        sourceVerificationPayloadSha256 = requireSha(
                            node.getRequired("sourceVerificationPayloadSha256"),
                            "$label.sourceVerificationPayloadSha256",
                        ),
                    )
                }
                else -> throw JsonValidationException("$label.kind is unsupported")
            }
        }

        /**
         * Parse the compact executable projection and bind it to the immutable source oracle.
         * The source proof remains opaque to the audio thread, but its canonical hash and the
         * duplicated pitch-verification object are checked here so neither can be substituted.
         */
        private fun parseEngineTransientPitchTreatment(
            value: JsonValue,
            sourceVerificationValue: JsonValue,
            label: String,
            liveVarispeed: Boolean,
            rootRpm: Double?,
        ): ParsedEngineTransientPitchTreatment {
            val pitch = value.asObject(label)
            pitch.requireExactKeys(
                label,
                setOf(
                    "runtimeVarispeed", "rootRpm", "scale",
                    "updatesContinuouslyWhileVoiceIsActive",
                    "continuesAfterParameterGateExit", "fmodLivePitchLatchSemantics",
                    "entryEdgeSpecificCaptureVariants",
                    "captureOperatingPointEdgesAreValidationOnly", "oracleBound",
                    "zeroGainVirtualization", "timelineAutomation",
                    "sourceBoundPitchVerification",
                ),
            )
            if (pitch.getRequired("runtimeVarispeed").asBoolean("$label.runtimeVarispeed") !=
                liveVarispeed ||
                pitch.getRequired("updatesContinuouslyWhileVoiceIsActive")
                    .asBoolean("$label.updatesContinuouslyWhileVoiceIsActive") != liveVarispeed ||
                !pitch.getRequired("continuesAfterParameterGateExit")
                    .asBoolean("$label.continuesAfterParameterGateExit") ||
                pitch.getRequired("fmodLivePitchLatchSemantics")
                    .asString("$label.fmodLivePitchLatchSemantics") != "notLatched" ||
                pitch.getRequired("entryEdgeSpecificCaptureVariants")
                    .asBoolean("$label.entryEdgeSpecificCaptureVariants") ||
                !pitch.getRequired("captureOperatingPointEdgesAreValidationOnly")
                    .asBoolean("$label.captureOperatingPointEdgesAreValidationOnly") ||
                pitch.getRequired("timelineAutomation").asString("$label.timelineAutomation") !=
                "targetCompareVarispeededCaptureAgainstLiveFmodBeforeRelease"
            ) {
                throw JsonValidationException("$label authored pitch contract changed")
            }
            val treatmentRootRpm = nullableNumber(pitch.getRequired("rootRpm"), "$label.rootRpm")
            val expectedScale = if (liveVarispeed) {
                "currentPresentationEngineRpm/rootRpm"
            } else {
                "1.0;authoredStaticPitchBakedInPcm"
            }
            if (treatmentRootRpm != rootRpm ||
                (liveVarispeed && (rootRpm == null || rootRpm <= 0.0)) ||
                (!liveVarispeed && rootRpm != null) ||
                pitch.getRequired("scale").asString("$label.scale") != expectedScale
            ) {
                throw JsonValidationException("$label root/scale disagrees with the TRACK leaf")
            }

            val oracleLabel = "$label.oracleBound"
            val oracle = pitch.getRequired("oracleBound").asObject(oracleLabel)
            oracle.requireExactKeys(
                oracleLabel,
                setOf(
                    "runtime", "dspBufferFrames", "fixed3000TotalUpdates",
                    "move3000To4500After101UpdatesTotalUpdates",
                    "move3000To5400After101UpdatesTotalUpdates",
                    "maximumDurationPredictionErrorUpdates",
                ),
            )
            if (oracle.getRequired("runtime").asString("$oracleLabel.runtime") !=
                "FMOD Studio API 1.08.12"
            ) {
                throw JsonValidationException("$oracleLabel.runtime changed")
            }
            requireInteger(oracle.getRequired("dspBufferFrames"), "$oracleLabel.dspBufferFrames", 256)
            requireInteger(
                oracle.getRequired("fixed3000TotalUpdates"),
                "$oracleLabel.fixed3000TotalUpdates", 1_244,
            )
            requireInteger(
                oracle.getRequired("move3000To4500After101UpdatesTotalUpdates"),
                "$oracleLabel.move3000To4500After101UpdatesTotalUpdates", 864,
            )
            requireInteger(
                oracle.getRequired("move3000To5400After101UpdatesTotalUpdates"),
                "$oracleLabel.move3000To5400After101UpdatesTotalUpdates", 737,
            )
            requireInteger(
                oracle.getRequired("maximumDurationPredictionErrorUpdates"),
                "$oracleLabel.maximumDurationPredictionErrorUpdates", 1,
            )

            val sourceLabel = label.removeSuffix(".pitchTreatment") + ".sourceVerification"
            val sourceVerification = sourceVerificationValue.asObject(sourceLabel)
            val sourceHash = requireSha(
                sourceVerification.getRequired("verificationPayloadSha256"),
                "$sourceLabel.verificationPayloadSha256",
            )
            val calculatedHash = StrictJson.canonicalSha256ExcludingObjectKey(
                sourceVerification,
                "verificationPayloadSha256",
            ).toLowerHexString()
            if (calculatedHash != sourceHash) {
                throw JsonValidationException("$sourceLabel canonical hash differs")
            }
            if (pitch.getRequired("sourceBoundPitchVerification") !=
                sourceVerification.getRequired("pitchVerification")
            ) {
                throw JsonValidationException(
                    "$label.sourceBoundPitchVerification is not bound to sourceVerification",
                )
            }
            val zeroGain = parseEngineTransientZeroGainVirtualization(
                pitch.getRequired("zeroGainVirtualization"),
                "$label.zeroGainVirtualization",
                sourceHash,
            )
            val rawZeroLabel = "$sourceLabel.zeroGainVirtualization"
            val rawZero = sourceVerification.getRequired("zeroGainVirtualization")
                .asObject(rawZeroLabel)
            val projectedZero = pitch.getRequired("zeroGainVirtualization")
                .asObject("$label.zeroGainVirtualization")
            if (rawZero.getRequired("runtimeSemantic") !=
                projectedZero.getRequired("runtimeSemantic")
            ) {
                throw JsonValidationException(
                    "$label zero-gain runtime projection differs from sourceVerification",
                )
            }
            val sourceReentry = rawZero.getRequired("reentryPolicy")
                .asString("$rawZeroLabel.reentryPolicy")
            val sourceProjectedReentry = when (sourceReentry) {
                "PRESERVE_PRIOR_UNTIL_SOURCE_BOUND_NATURAL_END_AND_SCHEDULE_NEW_ON_REENTRY;" +
                    "OVERLAP_IF_PRIOR_REMAINS_ALIVE" ->
                    PackEngineTransientReentryPolicy
                        .CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE
                "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER" ->
                    PackEngineTransientReentryPolicy
                        .NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER
                else -> throw JsonValidationException("$rawZeroLabel.reentryPolicy is unsupported")
            }
            if (zeroGain.second != sourceProjectedReentry) {
                throw JsonValidationException(
                    "$label reentry policy differs from sourceVerification",
                )
            }
            val expectedTransitionPitch = if (liveVarispeed) {
                PackZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH
            } else {
                PackZeroGainTransitionPitch.AUTHORED_STATIC_BAKED_PITCH
            }
            val transitionPitch = zeroGain.first.transition?.pitchDuringTransition
            if (transitionPitch != null && transitionPitch != expectedTransitionPitch) {
                throw JsonValidationException(
                    "$label zero-transition pitch must equal its source-global pitch treatment",
                )
            }
            return ParsedEngineTransientPitchTreatment(
                zeroGainVirtualization = zeroGain.first,
                reentryPolicy = zeroGain.second,
                sourceVerificationPayloadSha256 = sourceHash,
            )
        }

        private fun parseEngineTransientZeroGainVirtualization(
            value: JsonValue,
            label: String,
            sourceVerificationPayloadSha256: String,
        ): Pair<PackZeroGainVirtualizationV2, PackEngineTransientReentryPolicy> {
            val wrapper = value.asObject(label)
            wrapper.requireExactKeys(
                label,
                setOf("runtimeSemantic", "reentryPolicy", "sourceVerificationPayloadSha256"),
            )
            val reentryPolicy = enumValue<PackEngineTransientReentryPolicy>(
                wrapper.getRequired("reentryPolicy"), "$label.reentryPolicy",
            )
            if (requireSha(
                    wrapper.getRequired("sourceVerificationPayloadSha256"),
                    "$label.sourceVerificationPayloadSha256",
                ) != sourceVerificationPayloadSha256
            ) {
                throw JsonValidationException("$label source binding differs")
            }
            val semanticLabel = "$label.runtimeSemantic"
            val semantic = wrapper.getRequired("runtimeSemantic").asObject(semanticLabel)
            val kind = enumValue<PackZeroGainVirtualizationKind>(
                semantic.getRequired("kind"), "$semanticLabel.kind",
            )
            val result = when (kind) {
                PackZeroGainVirtualizationKind.NOT_APPLICABLE -> {
                    semantic.requireExactKeys(
                        semanticLabel,
                        setOf(
                            "kind", "logicalVoiceDeadlineAdvancesAtWriterTime",
                            "decodeCursorTreatment", "zeroTransition",
                        ),
                    )
                    if (!semantic.getRequired("logicalVoiceDeadlineAdvancesAtWriterTime")
                            .asBoolean("$semanticLabel.logicalVoiceDeadlineAdvancesAtWriterTime") ||
                        semantic.getRequired("decodeCursorTreatment")
                            .asString("$semanticLabel.decodeCursorTreatment") !=
                        "NORMAL_ACTIVE_VOICE"
                    ) {
                        throw JsonValidationException("$semanticLabel NOT_APPLICABLE contract changed")
                    }
                    val transitionLabel = "$semanticLabel.zeroTransition"
                    val transition = semantic.getRequired("zeroTransition").asObject(transitionLabel)
                    transition.requireExactKeys(transitionLabel, setOf("policy", "reason"))
                    if (transition.getRequired("policy").asString("$transitionLabel.policy") !=
                        "NOT_APPLICABLE" ||
                        transition.getRequired("reason").asString("$transitionLabel.reason") !=
                        "EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"
                    ) {
                        throw JsonValidationException("$transitionLabel changed")
                    }
                    PackZeroGainVirtualizationV2.NOT_APPLICABLE
                }
                PackZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE -> {
                    semantic.requireExactKeys(
                        semanticLabel,
                        setOf(
                            "kind", "mixerZeroGateAction",
                            "ordinaryNonzeroGainSmoothingUnaffected", "decodePhaseBeforeHold",
                            "phaseHoldLatencyWriterFrames",
                            "phaseAndDeadlineAdvanceWriterFramesBeforeHold",
                            "phaseHoldLatencyFrameDomain", "holdDecodePhaseAfterLatency",
                            "pauseNaturalEndDeadlineWhileHeld", "reaudibilizationBeforeDeadline",
                            "writerDspBlockFrames", "zeroTransition",
                            "channelGetPositionWhileVirtualIsRuntimeAuthoritative",
                        ),
                    )
                    validateExactZeroCommonSemantic(semantic, semanticLabel)
                    val latency = boundedWriterFrames(
                        semantic.getRequired("phaseHoldLatencyWriterFrames"),
                        "$semanticLabel.phaseHoldLatencyWriterFrames",
                        requirePositive = true,
                    )
                    val advance = boundedWriterFrames(
                        semantic.getRequired("phaseAndDeadlineAdvanceWriterFramesBeforeHold"),
                        "$semanticLabel.phaseAndDeadlineAdvanceWriterFramesBeforeHold",
                        requirePositive = true,
                    )
                    if (latency != advance || latency % ZERO_GAIN_WRITER_DSP_BLOCK_FRAMES != 0 ||
                        semantic.getRequired("phaseHoldLatencyFrameDomain")
                            .asString("$semanticLabel.phaseHoldLatencyFrameDomain") !=
                        ZERO_GAIN_WRITER_FRAME_DOMAIN ||
                        semantic.getRequired("decodePhaseBeforeHold")
                            .asString("$semanticLabel.decodePhaseBeforeHold") !=
                        "CURRENT_ACTIVE_VOICE_PITCH" ||
                        !semantic.getRequired("holdDecodePhaseAfterLatency")
                            .asBoolean("$semanticLabel.holdDecodePhaseAfterLatency") ||
                        !semantic.getRequired("pauseNaturalEndDeadlineWhileHeld")
                            .asBoolean("$semanticLabel.pauseNaturalEndDeadlineWhileHeld") ||
                        semantic.getRequired("reaudibilizationBeforeDeadline")
                            .asString("$semanticLabel.reaudibilizationBeforeDeadline") !=
                        "CONTINUE_FROM_HELD_LOGICAL_PHASE"
                    ) {
                        throw JsonValidationException("$semanticLabel HOLD contract changed")
                    }
                    val transition = parseExactZeroTransition(
                        semantic.getRequired("zeroTransition"), "$semanticLabel.zeroTransition",
                    )
                    if (transition.exactZeroFromWriterFrame > latency) {
                        throw JsonValidationException(
                            "$semanticLabel zero transition exceeds phase-hold latency",
                        )
                    }
                    PackZeroGainVirtualizationV2(kind, latency, transition)
                }
                PackZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO -> {
                    semantic.requireExactKeys(
                        semanticLabel,
                        setOf(
                            "kind", "mixerZeroGateAction",
                            "ordinaryNonzeroGainSmoothingUnaffected", "decodePhaseWhileExactZero",
                            "naturalEndDeadlineAdvancesWhileExactZero",
                            "reaudibilizationBeforeDeadline", "writerDspBlockFrames",
                            "zeroTransition",
                            "channelGetPositionWhileVirtualIsRuntimeAuthoritative",
                        ),
                    )
                    validateExactZeroCommonSemantic(semantic, semanticLabel)
                    if (semantic.getRequired("decodePhaseWhileExactZero")
                            .asString("$semanticLabel.decodePhaseWhileExactZero") !=
                        "CURRENT_ACTIVE_VOICE_PITCH" ||
                        !semantic.getRequired("naturalEndDeadlineAdvancesWhileExactZero")
                            .asBoolean("$semanticLabel.naturalEndDeadlineAdvancesWhileExactZero") ||
                        semantic.getRequired("reaudibilizationBeforeDeadline")
                            .asString("$semanticLabel.reaudibilizationBeforeDeadline") !=
                        "CONTINUE_FROM_ADVANCED_LOGICAL_PHASE"
                    ) {
                        throw JsonValidationException("$semanticLabel ADVANCE contract changed")
                    }
                    val transition = parseExactZeroTransition(
                        semantic.getRequired("zeroTransition"),
                        "$semanticLabel.zeroTransition",
                    )
                    if (transition.phaseTreatment !=
                            PackZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET ||
                        transition.restoreCapturePcmPhaseOffsetFrames != 0.0
                    ) {
                        throw JsonValidationException(
                            "$semanticLabel ADVANCE cursor cannot declare a restore phase offset",
                        )
                    }
                    PackZeroGainVirtualizationV2(
                        kind = kind,
                        phaseHoldLatencyWriterFrames = 0,
                        transition = transition,
                    )
                }
            }
            return result to reentryPolicy
        }

        private fun validateExactZeroCommonSemantic(
            semantic: Map<String, JsonValue>,
            label: String,
        ) {
            if (semantic.getRequired("mixerZeroGateAction")
                    .asString("$label.mixerZeroGateAction") != ZERO_GAIN_MIXER_ACTION ||
                !semantic.getRequired("ordinaryNonzeroGainSmoothingUnaffected")
                    .asBoolean("$label.ordinaryNonzeroGainSmoothingUnaffected") ||
                semantic.getRequired("writerDspBlockFrames")
                    .asLong("$label.writerDspBlockFrames") != ZERO_GAIN_WRITER_DSP_BLOCK_FRAMES.toLong() ||
                semantic.getRequired("channelGetPositionWhileVirtualIsRuntimeAuthoritative")
                    .asBoolean("$label.channelGetPositionWhileVirtualIsRuntimeAuthoritative")
            ) {
                throw JsonValidationException("$label exact-zero common contract changed")
            }
        }

        private fun parseExactZeroTransition(
            value: JsonValue,
            label: String,
        ): PackZeroGainTransitionV2 {
            val transition = value.asObject(label)
            transition.requireExactKeys(
                label,
                setOf(
                    "policy", "frameDomain", "gainInterpolation", "gainAtTransitionStart",
                    "gainAtExactZero", "retainPreZeroGainWriterFrames",
                    "linearFadeWriterFrames", "exactZeroFromWriterFrame",
                    "pitchDuringTransition", "phaseTreatment",
                    "residualMaximumAbsolutePcmLsb",
                    "acceptanceBoundMaximumAbsolutePcmLsb",
                    "positiveGainReturnBeforePhaseHoldPolicy",
                    "subsequentExactZeroCrossingPolicy",
                    "restoreCapturePcmPhaseOffsetFrames",
                    "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames",
                ),
            )
            val policy = enumValue<PackZeroGainTransitionPolicy>(
                transition.getRequired("policy"), "$label.policy",
            )
            val retainedFrames = boundedWriterFrames(
                transition.getRequired("retainPreZeroGainWriterFrames"),
                "$label.retainPreZeroGainWriterFrames",
            )
            val fadeFrames = boundedWriterFrames(
                transition.getRequired("linearFadeWriterFrames"),
                "$label.linearFadeWriterFrames",
            )
            val exactZeroFrame = boundedWriterFrames(
                transition.getRequired("exactZeroFromWriterFrame"),
                "$label.exactZeroFromWriterFrame",
            )
            val calculatedExactZeroFrame = try {
                Math.addExact(retainedFrames, fadeFrames)
            } catch (_: ArithmeticException) {
                throw JsonValidationException("$label writer-frame sum overflows")
            }
            val residual = nonNegative(
                transition.getRequired("residualMaximumAbsolutePcmLsb"),
                "$label.residualMaximumAbsolutePcmLsb",
            )
            val acceptanceBound = transition.getRequired("acceptanceBoundMaximumAbsolutePcmLsb")
                .asDouble("$label.acceptanceBoundMaximumAbsolutePcmLsb")
            val phaseTreatment = enumValue<PackZeroGainTransitionPhaseTreatment>(
                transition.getRequired("phaseTreatment"), "$label.phaseTreatment",
            )
            val restorePhaseOffset = transition.getRequired("restoreCapturePcmPhaseOffsetFrames")
                .asDouble("$label.restoreCapturePcmPhaseOffsetFrames")
            val restorePhaseOffsetBound = transition
                .getRequired("restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames")
                .asDouble("$label.restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames")
            if (transition.getRequired("frameDomain").asString("$label.frameDomain") !=
                ZERO_GAIN_WRITER_FRAME_DOMAIN ||
                transition.getRequired("gainInterpolation").asString("$label.gainInterpolation") !=
                "LINEAR_PER_WRITER_FRAME" ||
                transition.getRequired("gainAtTransitionStart")
                    .asDouble("$label.gainAtTransitionStart") != 1.0 ||
                transition.getRequired("gainAtExactZero")
                    .asDouble("$label.gainAtExactZero") != 0.0 ||
                exactZeroFrame != calculatedExactZeroFrame ||
                transition.getRequired("positiveGainReturnBeforePhaseHoldPolicy")
                    .asString("$label.positiveGainReturnBeforePhaseHoldPolicy") !=
                "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD" ||
                transition.getRequired("subsequentExactZeroCrossingPolicy")
                    .asString("$label.subsequentExactZeroCrossingPolicy") !=
                "RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE" ||
                acceptanceBound != ZERO_GAIN_PCM_LSB_ACCEPTANCE_BOUND ||
                residual > acceptanceBound ||
                !restorePhaseOffset.isFinite() ||
                restorePhaseOffsetBound != ZERO_GAIN_MAXIMUM_ABSOLUTE_RESTORE_PHASE_OFFSET_FRAMES ||
                kotlin.math.abs(restorePhaseOffset) > restorePhaseOffsetBound ||
                (phaseTreatment ==
                    PackZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET &&
                    restorePhaseOffset != 0.0) ||
                (phaseTreatment ==
                    PackZeroGainTransitionPhaseTreatment.APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET &&
                    restorePhaseOffset == 0.0) ||
                (policy == PackZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO &&
                    (retainedFrames != 0 || fadeFrames != 0)) ||
                (policy == PackZeroGainTransitionPolicy.RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO &&
                    fadeFrames <= 0)
            ) {
                throw JsonValidationException("$label transition contract changed")
            }
            return PackZeroGainTransitionV2(
                policy = policy,
                retainPreZeroGainWriterFrames = retainedFrames,
                linearFadeWriterFrames = fadeFrames,
                pitchDuringTransition = enumValue(
                    transition.getRequired("pitchDuringTransition"),
                    "$label.pitchDuringTransition",
                ),
                phaseTreatment = phaseTreatment,
                restoreCapturePcmPhaseOffsetFrames = restorePhaseOffset,
            )
        }

        private fun boundedWriterFrames(
            value: JsonValue,
            label: String,
            requirePositive: Boolean = false,
        ): Int {
            val frames = value.asLong(label)
            val minimum = if (requirePositive) 1L else 0L
            if (frames !in minimum..Int.MAX_VALUE.toLong()) {
                throw JsonValidationException("$label is outside the bounded writer-frame range")
            }
            return frames.toInt()
        }

        private fun parseTurboControl(value: JsonValue, label: String): PackOneShotGateControl {
            val control = enumValue<PackOneShotGateControl>(value, label)
            if (control != PackOneShotGateControl.BOOST && control != PackOneShotGateControl.BOV &&
                control != PackOneShotGateControl.BOV_DECAY
            ) {
                throw JsonValidationException("$label is not a native TURBO event control")
            }
            return control
        }

        private fun turboControlMaximum(control: PackOneShotGateControl): Double = when (control) {
            PackOneShotGateControl.BOOST -> 1.5
            PackOneShotGateControl.BOV -> 1.0
            PackOneShotGateControl.BOV_DECAY -> 10.0
            else -> error("Not a TURBO event control")
        }

        private fun parseTurboControlCurve(
            value: JsonValue,
            label: String,
            control: PackOneShotGateControl,
            requirePositiveOutput: Boolean,
            maximumOutput: Double? = null,
        ): List<CurvePointV1> {
            val points = value.asArray(label).mapIndexed { index, rawPoint ->
                val point = rawPoint.asArray("$label[$index]")
                if (point.size != 2) throw JsonValidationException("$label[$index] must be [x,y]")
                CurvePointV1(
                    point[0].asDouble("$label[$index][0]"),
                    point[1].asDouble("$label[$index][1]"),
                )
            }
            if (points.isEmpty()) throw JsonValidationException("$label must not be empty")
            var previousInput: Double? = null
            points.forEach { point ->
                if (point.input !in 0.0..turboControlMaximum(control) ||
                    (if (requirePositiveOutput) point.output <= 0.0 else point.output < 0.0) ||
                    (maximumOutput != null && point.output > maximumOutput) ||
                    (previousInput != null && point.input <= requireNotNull(previousInput))
                ) {
                    throw JsonValidationException("$label is outside its authored domain")
                }
                previousInput = point.input
            }
            return points
        }

        private fun parseOneShotGate(value: JsonValue, label: String): PackOneShotParameterGateV2 {
            val gate = value.asObject(label)
            gate.requireExactKeys(
                label, setOf("control", "minimum", "maximum", "includeMinimum", "includeMaximum"),
            )
            val control = enumValue<PackOneShotGateControl>(gate.getRequired("control"), "$label.control")
            val minimum = gate.getRequired("minimum").asDouble("$label.minimum")
            val maximum = gate.getRequired("maximum").asDouble("$label.maximum")
            if (minimum >= maximum ||
                (control == PackOneShotGateControl.ACCELERATOR && (minimum < 0.0 || maximum > 1.0)) ||
                (control in setOf(PackOneShotGateControl.ENGINE_RPM, PackOneShotGateControl.DRIVETRAIN_SPEED) &&
                    minimum < 0.0)
            ) {
                throw JsonValidationException("$label has invalid bounds")
            }
            return PackOneShotParameterGateV2(
                control = control,
                minimum = minimum,
                maximum = maximum,
                includeMinimum = gate.getRequired("includeMinimum").asBoolean("$label.includeMinimum"),
                includeMaximum = gate.getRequired("includeMaximum").asBoolean("$label.includeMaximum"),
            )
        }

        private fun validateOneShotProgram(
            programId: String,
            trigger: PackOneShotTrigger,
            roots: List<String>,
            nodes: List<PackOneShotNodeV2>,
            engineEventPolicy: PackEngineEventPolicyV2?,
            limiterEventPolicy: PackLimiterEventPolicyV2?,
            turboEventPolicy: PackTurboEventPolicyV2?,
            trackById: Map<String, SoundTrackManifestV1>,
            label: String,
        ) {
            if (nodes.isEmpty() || nodes.size > MAX_ONE_SHOT_NODES_PER_PROGRAM ||
                nodes.size != nodes.map { it.id }.toSet().size
            ) {
                throw JsonValidationException("$label nodes must be non-empty, unique, and bounded")
            }
            val nodeById = nodes.associateBy(PackOneShotNodeV2::id)
            if (!nodeById.keys.containsAll(roots)) throw JsonValidationException("$label references a missing root")
            val parentCounts = HashMap<String, Int>(nodes.size)
            nodes.filterIsInstance<PackOneShotGroupNodeV2>().forEach { group ->
                if (group.members.isEmpty() || group.members.size > MAX_ONE_SHOT_GROUP_MEMBERS) {
                    throw JsonValidationException("$label group ${group.id} has an invalid member count")
                }
                val orders = group.members.map(PackOneShotMemberV2::order).sorted()
                if (orders != orders.indices.toList() ||
                    group.members.size != group.members.map { it.nodeId }.toSet().size
                ) {
                    throw JsonValidationException("$label group ${group.id} needs unique contiguous member order")
                }
                group.members.forEach { member ->
                    if (member.nodeId !in nodeById) {
                        throw JsonValidationException("$label group ${group.id} references a missing node")
                    }
                    parentCounts[member.nodeId] = (parentCounts[member.nodeId] ?: 0) + 1
                }
            }
            nodes.forEach { node ->
                val expectedParents = if (node.id in roots) 0 else 1
                if ((parentCounts[node.id] ?: 0) != expectedParents) {
                    throw JsonValidationException("$label must be a rooted tree; node=${node.id}")
                }
            }
            val visiting = hashSetOf<String>()
            val visited = hashSetOf<String>()
            fun visit(nodeId: String, depth: Int) {
                if (depth > MAX_ONE_SHOT_TREE_DEPTH) {
                    throw JsonValidationException("$label exceeds the bounded one-shot tree depth")
                }
                if (!visiting.add(nodeId)) throw JsonValidationException("$label contains a cycle")
                val node = nodeById.getValue(nodeId)
                if (node is PackOneShotGroupNodeV2) node.members.forEach { visit(it.nodeId, depth + 1) }
                visiting.remove(nodeId)
                visited.add(nodeId)
            }
            roots.forEach { visit(it, 0) }
            if (visited.size != nodes.size) throw JsonValidationException("$label contains unreachable nodes")
            val leafTracks = ArrayList<SoundTrackManifestV1>()
            nodes.filterIsInstance<PackOneShotTrackNodeV2>().forEach { leaf ->
                val track = trackById[leaf.trackId]
                    ?: throw JsonValidationException("$label leaf ${leaf.id} references a missing track")
                if ((track.role.loops && track.role != PackTrackRole.LIMITER) ||
                    !oneShotTriggerMatches(track.role, trigger)
                ) {
                    throw JsonValidationException("$label leaf ${leaf.id} trigger does not match its track")
                }
                if (leaf.rpmCurve != track.rpmCurve || leaf.gainCurve != track.gainCurve) {
                    throw JsonValidationException("$label leaf ${leaf.id} curves must match its track curves")
                }
                val isEngineEvent = trigger == PackOneShotTrigger.ENGINE_EVENT
                val invalidPitchMetadata = if (isEngineEvent) {
                    leaf.rootRpm != track.rootRpm ||
                        (leaf.liveVarispeed && leaf.rootRpm == null) ||
                        (!leaf.liveVarispeed && leaf.rootRpm != null)
                } else {
                    leaf.liveVarispeed || leaf.rootRpm != null
                }
                if (invalidPitchMetadata) {
                    throw JsonValidationException("$label leaf ${leaf.id} has invalid live-varispeed metadata")
                }
                leafTracks += track
            }
            if ((trigger == PackOneShotTrigger.ENGINE_EVENT) != (engineEventPolicy != null)) {
                throw JsonValidationException("$label engine-event policy presence is inconsistent")
            }
            if ((trigger == PackOneShotTrigger.LIMITER_EVENT) != (limiterEventPolicy != null)) {
                throw JsonValidationException("$label limiter-event policy presence is inconsistent")
            }
            if ((trigger == PackOneShotTrigger.TURBO_EVENT) != (turboEventPolicy != null)) {
                throw JsonValidationException("$label turbo-event policy presence is inconsistent")
            }
            if (engineEventPolicy != null &&
                leafTracks.maxOf(SoundTrackManifestV1::frameCount) !=
                engineEventPolicy.maximumDecodedOneShotFrames.toLong()
            ) {
                throw JsonValidationException("$label engine-event PCM bound is not exact")
            }
            if (engineEventPolicy != null) {
                val authoredRegionGates = engineEventPolicy.parameterRegions.single().parameterGates
                nodes.filterIsInstance<PackOneShotTrackNodeV2>().forEach { leaf ->
                    if (leaf.parameterGates != authoredRegionGates) {
                        throw JsonValidationException(
                            "$label engine-event leaf gates disagree with the proven program region",
                        )
                    }
                }
            }
            if (limiterEventPolicy != null) {
                if (leafTracks.size != 1 || leafTracks.single().role != PackTrackRole.LIMITER) {
                    throw JsonValidationException("$label must contain exactly one LIMITER source")
                }
                val track = leafTracks.single()
                val expectedLoop = limiterEventPolicy.programMode !=
                    PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT
                val hasLoop = track.loopStartFrame != null && track.loopEndFrameExclusive != null
                if (hasLoop != expectedLoop) {
                    throw JsonValidationException("$label limiter loop geometry disagrees with programMode")
                }
                if (limiterEventPolicy.programMode ==
                    PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT
                ) {
                    val timeline = requireNotNull(limiterEventPolicy.timelinePlacement)
                    if (track.loopStartFrame != 0L || track.loopEndFrameExclusive != track.frameCount ||
                        track.frameCount != timeline.periodFramesAt48k.toLong()
                    ) {
                        throw JsonValidationException(
                            "$label timeline limiter PCM must be one exact authored period",
                        )
                    }
                }
                if (limiterEventPolicy.decodedOneShotLaneBound) {
                    val lanes = minOf(
                        AC_LOGICAL_VOICE_LIMIT,
                        (track.frameCount + CONTROL_TICK_FRAMES - 1L) / CONTROL_TICK_FRAMES,
                    )
                    if (lanes <= 0L) throw JsonValidationException("$label limiter lane bound is invalid")
                }
            }
            if (turboEventPolicy != null) {
                val expectedRole = if (
                    turboEventPolicy.programMode == PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT
                ) {
                    PackTrackRole.BOV
                } else {
                    PackTrackRole.TURBO_TRANSIENT
                }
                val actualRoles = buildSet {
                    leafTracks.mapTo(this) { it.role }
                    nodes.filterIsInstance<PackOneShotSilentNodeV2>().mapTo(this) { it.resolvedRole }
                }
                if (actualRoles != setOf(expectedRole)) {
                    throw JsonValidationException("$label turbo-event role/program mode changed")
                }
                val verificationHashes = buildList {
                    nodes.filterIsInstance<PackOneShotTrackNodeV2>().mapTo(this) {
                        requireNotNull(it.sourceVerificationPayloadSha256)
                    }
                    nodes.filterIsInstance<PackOneShotSilentNodeV2>().mapTo(this) {
                        it.sourceVerificationPayloadSha256
                    }
                }
                val silentSourceGuids = nodes.filterIsInstance<PackOneShotSilentNodeV2>()
                    .map(PackOneShotSilentNodeV2::sourceGuid)
                if (verificationHashes.size != verificationHashes.toSet().size ||
                    silentSourceGuids.size != silentSourceGuids.toSet().size
                ) {
                    throw JsonValidationException("$label turbo source verification identities must be unique")
                }
            }
            if (programId.isEmpty()) throw JsonValidationException("$label.id must not be empty")
        }

        private fun parseOneShotTriggerPolicies(
            value: JsonValue,
            label: String,
        ): Map<String, PackOneShotTriggerPolicyV2> {
            val policies = value.asObject(label)
            if (policies.size > MAX_ONE_SHOT_PROGRAMS) throw JsonValidationException("$label is too large")
            return policies.mapValues { (programId, rawPolicy) ->
                if (!identifier.matches(programId)) throw JsonValidationException("$label has an invalid program id")
                val policyLabel = "$label.$programId"
                val policy = rawPolicy.asObject(policyLabel)
                policy.requireExactKeys(
                    policyLabel,
                    setOf(
                        "kind", "minimumRpm", "maximumRpm", "armPedal", "firePedal", "armBoost",
                        "initialPeakPedal", "initialArmPedal", "initialFirePedal",
                        "minimumArmMs", "cooldownMs", "periodHz",
                    ),
                )
                val kind = enumValue<PackOneShotPolicyKind>(policy.getRequired("kind"), "$policyLabel.kind")
                val minimumRpm = nonNegative(policy.getRequired("minimumRpm"), "$policyLabel.minimumRpm")
                val maximumRpm = nullableNumber(policy.getRequired("maximumRpm"), "$policyLabel.maximumRpm")
                if (maximumRpm != null && maximumRpm <= minimumRpm) {
                    throw JsonValidationException("$policyLabel.maximumRpm must exceed minimumRpm")
                }
                val armPedal = nullableNumber(policy.getRequired("armPedal"), "$policyLabel.armPedal")
                val firePedal = nullableNumber(policy.getRequired("firePedal"), "$policyLabel.firePedal")
                val armBoost = nullableNumber(policy.getRequired("armBoost"), "$policyLabel.armBoost")
                val initialPeakPedal = nullableNumber(
                    policy.getRequired("initialPeakPedal"), "$policyLabel.initialPeakPedal",
                )
                val initialArmPedal = nullableNumber(
                    policy.getRequired("initialArmPedal"), "$policyLabel.initialArmPedal",
                )
                val initialFirePedal = nullableNumber(
                    policy.getRequired("initialFirePedal"), "$policyLabel.initialFirePedal",
                )
                val minimumArmMs = nonNegative(
                    policy.getRequired("minimumArmMs"), "$policyLabel.minimumArmMs",
                )
                val cooldownMs = nonNegative(policy.getRequired("cooldownMs"), "$policyLabel.cooldownMs")
                val periodHz = nullableNumber(policy.getRequired("periodHz"), "$policyLabel.periodHz")
                when (kind) {
                    PackOneShotPolicyKind.AC_BACKFIRE -> if (
                        armPedal == null || firePedal == null || armPedal !in 0.0..1.0 ||
                        firePedal !in 0.0..0.3 || armBoost != null || maximumRpm == null ||
                        initialPeakPedal == null || initialPeakPedal !in 0.0..1.0 ||
                        initialArmPedal == null || initialArmPedal !in 0.0..1.0 ||
                        initialFirePedal == null || initialFirePedal !in 0.0..1.0 ||
                        initialFirePedal >= initialArmPedal || periodHz != null
                    ) {
                        throw JsonValidationException("$policyLabel has invalid AC backfire state")
                    }
                    PackOneShotPolicyKind.BOV_LIFT -> if (
                        armPedal != null || firePedal != null || armBoost != null ||
                        initialPeakPedal != null || initialArmPedal != null ||
                        initialFirePedal != null || minimumArmMs != 0.0 || periodHz != null
                    ) {
                        throw JsonValidationException("$policyLabel BOV must be driven by physical pressure")
                    }
                    PackOneShotPolicyKind.LIMITER -> if (
                        armPedal != null || firePedal != null || armBoost != null ||
                        initialPeakPedal != null || initialArmPedal != null || initialFirePedal != null ||
                        periodHz == null || periodHz <= 0.0
                    ) {
                        throw JsonValidationException("$policyLabel has invalid limiter timing")
                    }
                    PackOneShotPolicyKind.LIMITER_EVENT -> if (
                        armPedal != null || firePedal != null || armBoost != null ||
                        initialPeakPedal != null || initialArmPedal != null || initialFirePedal != null ||
                        maximumRpm != null || minimumArmMs != 0.0 || cooldownMs != 0.0 ||
                        periodHz != null
                    ) {
                        throw JsonValidationException(
                            "$policyLabel persistent limiter must be pulse-reset, not sample-periodic",
                        )
                    }
                    PackOneShotPolicyKind.SHIFT_UP,
                    PackOneShotPolicyKind.SHIFT_DOWN -> if (
                        armPedal != null || firePedal != null || armBoost != null ||
                        initialPeakPedal != null || initialArmPedal != null ||
                        initialFirePedal != null || periodHz != null
                    ) {
                        throw JsonValidationException("$policyLabel has invalid shift timing")
                    }
                    PackOneShotPolicyKind.ENGINE_START -> if (
                        armPedal != null || firePedal != null || armBoost != null ||
                        initialPeakPedal != null || initialArmPedal != null ||
                        initialFirePedal != null || periodHz != null ||
                        maximumRpm != null
                    ) {
                        throw JsonValidationException("$policyLabel has invalid engine-start timing")
                    }
                }
                PackOneShotTriggerPolicyV2(
                    kind, minimumRpm, maximumRpm, armPedal, firePedal, armBoost,
                    initialPeakPedal, initialArmPedal, initialFirePedal,
                    minimumArmMs, cooldownMs, periodHz,
                )
            }
        }

        private fun validateOneShotTriggerPolicies(
            cars: List<ManifestCarV1>,
            programs: List<PackOneShotProgramV2>,
            schemaVersion: Int,
        ) {
            if (schemaVersion < 2) return
            val programKinds = programs.asSequence()
                .filter {
                    it.trigger != PackOneShotTrigger.ENGINE_EVENT &&
                        it.trigger != PackOneShotTrigger.TURBO_EVENT &&
                        it.trigger != PackOneShotTrigger.ENGINE_START
                }
                .associate { it.id to expectedPolicyKind(it.trigger) }
            cars.forEach { car ->
                if (car.oneShotTriggerPolicies.keys != programKinds.keys ||
                    car.oneShotTriggerPolicies.any { (id, policy) -> programKinds[id] != policy.kind }
                ) {
                    throw JsonValidationException(
                        "Car ${car.id} must define exactly one matching policy for every one-shot program",
                    )
                }
                car.oneShotTriggerPolicies.values.forEach { policy ->
                    if (policy.kind == PackOneShotPolicyKind.LIMITER_EVENT &&
                        policy.minimumRpm != car.engine.limiterRpm
                    ) {
                        throw JsonValidationException(
                            "Car ${car.id} persistent limiter threshold must equal authored limiterRpm",
                        )
                    }
                }
            }
        }

        private fun expectedPolicyKind(trigger: PackOneShotTrigger): PackOneShotPolicyKind = when (trigger) {
            PackOneShotTrigger.THROTTLE_LIFT -> PackOneShotPolicyKind.AC_BACKFIRE
            PackOneShotTrigger.BOV_LIFT -> PackOneShotPolicyKind.BOV_LIFT
            PackOneShotTrigger.LIMITER -> PackOneShotPolicyKind.LIMITER
            PackOneShotTrigger.LIMITER_EVENT -> PackOneShotPolicyKind.LIMITER_EVENT
            PackOneShotTrigger.SHIFT_UP -> PackOneShotPolicyKind.SHIFT_UP
            PackOneShotTrigger.SHIFT_DOWN -> PackOneShotPolicyKind.SHIFT_DOWN
            PackOneShotTrigger.ENGINE_EVENT -> throw JsonValidationException(
                "ENGINE_EVENT uses its strict family-level policy",
            )
            PackOneShotTrigger.TURBO_EVENT -> throw JsonValidationException(
                "TURBO_EVENT uses its strict family-level policy",
            )
            PackOneShotTrigger.ENGINE_START -> throw JsonValidationException(
                "ENGINE_START uses no per-car effect policy",
            )
        }

        private fun oneShotTriggerMatches(role: PackTrackRole, trigger: PackOneShotTrigger): Boolean =
            when (role) {
                PackTrackRole.LIMITER -> trigger == PackOneShotTrigger.LIMITER_EVENT
                PackTrackRole.SHIFT_UP -> trigger == PackOneShotTrigger.SHIFT_UP
                PackTrackRole.SHIFT_DOWN -> trigger == PackOneShotTrigger.SHIFT_DOWN
                PackTrackRole.BOV -> trigger == PackOneShotTrigger.BOV_LIFT ||
                    trigger == PackOneShotTrigger.TURBO_EVENT
                PackTrackRole.TURBO_TRANSIENT -> trigger == PackOneShotTrigger.TURBO_EVENT
                PackTrackRole.OVERRUN, PackTrackRole.POP,
                PackTrackRole.BANG, PackTrackRole.CRACK -> trigger == PackOneShotTrigger.THROTTLE_LIFT
                PackTrackRole.ENGINE_TRANSIENT -> trigger == PackOneShotTrigger.ENGINE_EVENT
                PackTrackRole.ENGINE_START -> trigger == PackOneShotTrigger.ENGINE_START
                else -> false
            }

        private inline fun <reified E : Enum<E>> enumValue(value: JsonValue, label: String): E {
            val text = value.asString(label)
            return enumValues<E>().firstOrNull { it.name == text }
                ?: throw JsonValidationException("$label is unsupported")
        }

        private fun probability(value: JsonValue, label: String): Double =
            value.asDouble(label).also {
                if (it !in 0.0..1.0) throw JsonValidationException("$label must be in 0..1")
            }

        private fun validateEffectCapabilities(
            effects: CoreEffectAvailability,
            tracks: List<SoundTrackManifestV1>,
        ) {
            val roles = tracks.mapTo(enumSetOf<PackTrackRole>()) { it.role }
            val derived = deriveCoreEffectAvailability(roles)
            if (effects != derived) {
                throw JsonValidationException("effects must exactly describe retained track roles")
            }
        }

        private inline fun <reified E : Enum<E>> enumSetOf(): MutableSet<E> =
            java.util.EnumSet.noneOf(E::class.java)

        private val TRIGGERS_BY_ROLE = mapOf(
            PackTrackRole.IDLE to emptySet(),
            PackTrackRole.COAST to emptySet(),
            PackTrackRole.TEXTURE to emptySet(),
            PackTrackRole.INTAKE to emptySet(),
            PackTrackRole.EXHAUST to emptySet(),
            PackTrackRole.TURBO to emptySet(),
            PackTrackRole.SPOOL to emptySet(),
            PackTrackRole.TRANSMISSION to emptySet(),
            PackTrackRole.BOV to setOf("bov"),
            PackTrackRole.TURBO_TRANSIENT to setOf("turboEvent"),
            PackTrackRole.LIMITER to setOf("limiterPulse"),
            PackTrackRole.SHIFT_UP to setOf("shiftUp"),
            PackTrackRole.SHIFT_DOWN to setOf("shiftDown"),
            PackTrackRole.OVERRUN to setOf("overrunRelease"),
            PackTrackRole.POP to setOf("pop"),
            PackTrackRole.BANG to setOf("bang"),
            PackTrackRole.CRACK to setOf("crack"),
            PackTrackRole.ENGINE_TRANSIENT to setOf("engineEvent"),
            PackTrackRole.ENGINE_START to setOf("engineStart"),
        )
        private val CERTIFIED_SILENT_SOURCE_ROLES = setOf(
            PackTrackRole.LIMITER,
            PackTrackRole.BOV,
            PackTrackRole.TURBO_TRANSIENT,
            PackTrackRole.SHIFT_UP,
            PackTrackRole.SHIFT_DOWN,
        )
        private const val MAX_ONE_SHOT_PROGRAMS = 128
        private const val MAX_ONE_SHOT_NODES_PER_PROGRAM = 512
        private const val MAX_ONE_SHOT_GROUP_MEMBERS = 256
        private const val MAX_ONE_SHOT_TREE_DEPTH = 32
        private const val MAX_AUTHORED_DSP_ENTRIES = 8
        private const val MAX_CERTIFIED_SILENT_SOURCES = MAX_ONE_SHOT_NODES_PER_PROGRAM
        private const val MIN_AUTHORED_DSP_GAIN_DB = -96.0
        private const val CONTROL_TICK_FRAMES = 480L
        private const val AC_LOGICAL_VOICE_LIMIT = 2_048L
        private const val AC_SOFTWARE_REAL_VOICE_BUDGET = 256L
        private const val FMOD_AUTHORED_ENGINE_PRIORITY = 64
        private const val FMOD_DEFAULT_EVENT_PRIORITY = 128
        private const val MAX_REASONABLE_BOOST_BAR = 100.0
        private const val MAX_REASONABLE_REFERENCE_RPM = 100_000.0
        private const val MAX_REASONABLE_GAMMA = 100.0
        private const val MAX_REASONABLE_LAG_PER_SECOND = 100_000.0
        // The complete 171-family capture-plan proof peaks at 37.50169480863333.
        // 38.0 is the locked schema/runtime safety ceiling; values are rejected, never clamped.
        private const val MAX_TURBO_CONTROL_GAIN = 38.0
        private const val MAX_TRACK_PITCH_CURVE_POINTS = 512
        private const val MAX_TRACK_RELATIVE_RATE = 16.0
        private const val TRACK_PITCH_ROOT_TOLERANCE = 2e-4
        private const val ZERO_GAIN_WRITER_DSP_BLOCK_FRAMES = 256
        private const val ZERO_GAIN_PCM_LSB_ACCEPTANCE_BOUND = 1.0
        private const val ZERO_GAIN_MAXIMUM_ABSOLUTE_RESTORE_PHASE_OFFSET_FRAMES = 512.0
        private const val ZERO_GAIN_WRITER_FRAME_DOMAIN =
            "STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ"
        private const val ZERO_GAIN_MIXER_ACTION =
            "APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;" +
                "DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING"
        private val LIMITER_UPDATE_ORDER = listOf(
            "FLOAT32_TIMER_PLUS_DT",
            "RESET_TIMER_TO_ZERO_IF_LIMITER_PULSE",
            "WRITE_RAW_TIMER_TO_FMOD_DECAY_PARAMETER",
            "UPDATE_EVENT_OWNER_STATE",
        )
        private val LIMITER_EXECUTABLE_EVIDENCE = linkedMapOf(
            "timerInitialization" to "acs.exe:0x140063038 immediate float32 10.0",
            "timerAndParameterUpdate" to "acs.exe:0x140067134-0x14006718c",
            "tenSecondOwnerGate" to "acs.exe:0x140067e28-0x140067ea4",
            "rewindThenStart" to "acs.exe:0x1401fbf40-0x1401fbfb7",
            "allowFadeStop" to "acs.exe:0x1401fc040-0x1401fc07f",
        )

        private fun parseCurve(value: JsonValue, label: String, normalizedInput: Boolean): List<CurvePointV1> {
            var previous: Double? = null
            return value.asArray(label).mapIndexed { index, raw ->
                val point = raw.asArray("$label[$index]")
                if (point.size != 2) throw JsonValidationException("$label[$index] must contain [x,y]")
                val input = point[0].asDouble("$label[$index][0]")
                val output = point[1].asDouble("$label[$index][1]")
                if (previous != null && input <= previous!!) throw JsonValidationException("$label x values must increase")
                if (output !in 0.0..1.0) throw JsonValidationException("$label output must be linear amplitude 0..1")
                if ((normalizedInput && input !in 0.0..1.0) || (!normalizedInput && input < 0.0)) {
                    throw JsonValidationException("$label input is outside its authored domain")
                }
                previous = input
                CurvePointV1(input, output)
            }
        }

        private fun parseTrackPitchCurve(value: JsonValue, label: String): List<CurvePointV1> {
            var previousInput: Double? = null
            return value.asArray(label).mapIndexed { index, raw ->
                val point = raw.asArray("$label[$index]")
                if (point.size != 2) throw JsonValidationException("$label[$index] must contain [x,y]")
                val input = point[0].asDouble("$label[$index][0]")
                val relativeRate = point[1].asDouble("$label[$index][1]")
                if (input < 0.0 || previousInput != null && input <= previousInput!!) {
                    throw JsonValidationException("$label x values must be non-negative and increase")
                }
                if (relativeRate <= 0.0 || relativeRate > MAX_TRACK_RELATIVE_RATE) {
                    throw JsonValidationException("$label relative rate must be in (0,16]")
                }
                previousInput = input
                CurvePointV1(input, relativeRate)
            }
        }

        private fun List<CurvePointV1>.clampedLinearValueAt(input: Double): Double {
            if (input <= first().input) return first().output
            if (input >= last().input) return last().output
            var index = 1
            while (input > this[index].input) index += 1
            val left = this[index - 1]
            val right = this[index]
            val fraction = (input - left.input) / (right.input - left.input)
            return left.output + (right.output - left.output) * fraction
        }

        /**
         * IDLE is mandatory content rather than a presence flag. At the authored idle
         * RPM of every family member, at least one IDLE voice must have positive RPM
         * amplitude and positive released-pedal gain. This rejects silent placeholder
         * curves before a pack reaches the decoder or mixer.
         */
        private fun validateIdleAudibility(
            cars: List<ManifestCarV1>,
            tracks: List<SoundTrackManifestV1>,
            familyAttenuationDb: Double,
        ) {
            val idleTracks = tracks.filter { it.role == PackTrackRole.IDLE }
            cars.forEach { car ->
                val audible = idleTracks.any { track ->
                    val authoredAmplitude = if (track.rpmCurve.size >= 2 && track.gainCurve.size >= 2) {
                        track.rpmCurve.amplitudeAt(car.engine.idleRpm) *
                            track.gainCurve.amplitudeAt(0.0)
                    } else {
                        0.0
                    }
                    val calibratedGain = 10.0.pow((track.gainDb + familyAttenuationDb) / 20.0)
                    authoredAmplitude > 0.0 && calibratedGain > 0.0 && calibratedGain.isFinite()
                }
                if (!audible) {
                    throw JsonValidationException(
                        "Authored IDLE gain/RPM curves are silent at ${car.id} idle RPM ${car.engine.idleRpm}",
                    )
                }
            }
        }

        private fun List<CurvePointV1>.amplitudeAt(input: Double): Double {
            if (input <= first().input) return first().output
            if (input >= last().input) return last().output
            var index = 1
            while (index < size) {
                val right = this[index]
                if (input <= right.input) {
                    val left = this[index - 1]
                    val fraction = (input - left.input) / (right.input - left.input)
                    return left.output + (right.output - left.output) * fraction
                }
                index += 1
            }
            return last().output
        }

        private fun parseUnboundedCurve(value: JsonValue, label: String): List<CurvePointV1> {
            var previousInput: Double? = null
            return value.asArray(label).mapIndexed { index, raw ->
                val point = raw.asArray("$label[$index]")
                if (point.size != 2) throw JsonValidationException("$label[$index] must contain [x,y]")
                val input = point[0].asDouble("$label[$index][0]")
                if (previousInput != null && input <= previousInput!!) {
                    throw JsonValidationException("$label x values must increase")
                }
                previousInput = input
                CurvePointV1(input, point[1].asDouble("$label[$index][1]"))
            }
        }

        private fun parseFidelity(value: JsonValue): SoundFidelityMetadataV1 {
            val fidelity = value.asObject("fidelity")
            fidelity.requireExactKeys(
                "fidelity", setOf("sourceAudio", "layerIsolation", "rpmGainCurve", "effectVariants", "notes"),
            )
            val source = fidelity.getRequired("sourceAudio").asString("fidelity.sourceAudio")
            val isolation = fidelity.getRequired("layerIsolation").asString("fidelity.layerIsolation")
            val curve = fidelity.getRequired("rpmGainCurve").asString("fidelity.rpmGainCurve")
            val variants = fidelity.getRequired("effectVariants").asString("fidelity.effectVariants")
            if (source != "nativeFmodFinalMix" || isolation !in setOf("eventLevel", "sourceInstrument") ||
                curve !in setOf("compilerWindowApproximation", "authoredSourceInstrument") ||
                variants !in setOf("authoredOneShotTopology", "nativeRandomSequence", "singleEventTake")
            ) {
                throw JsonValidationException("fidelity declares an unsupported capture mode")
            }
            val notes = fidelity.getRequired("notes").asArray("fidelity.notes").mapIndexed { index, note ->
                note.asString("fidelity.notes[$index]").requireNotBlank("fidelity.notes[$index]")
            }
            return SoundFidelityMetadataV1(source, isolation, curve, variants, notes)
        }

        private fun validateEncoder(value: JsonValue) {
            val encoder = value.asObject("provenance.encoder")
            encoder.requireExactKeys("provenance.encoder", setOf("name", "version", "executableSha256"))
            encoder.getRequired("name").asString("provenance.encoder.name").requireNotBlank("provenance.encoder.name")
            encoder.getRequired("version").asString("provenance.encoder.version").requireNotBlank("provenance.encoder.version")
            requireSha(encoder.getRequired("executableSha256"), "provenance.encoder.executableSha256")
        }

        private fun parseAuthoredDsp(value: JsonValue): List<AuthoredDspProvenanceV2> {
            val entries = value.asArray("provenance.authoredDsp")
            if (entries.size > MAX_AUTHORED_DSP_ENTRIES) {
                throw JsonValidationException("provenance.authoredDsp exceeds its bounded count")
            }
            val result = entries.mapIndexed { index, raw ->
                val label = "provenance.authoredDsp[$index]"
                val entry = raw.asObject(label)
                entry.requireExactKeys(
                    label,
                    setOf("name", "version", "parameters", "treatment", "evidence"),
                )
                val name = entry.getRequired("name").asString("$label.name")
                if (name != "FMOD Gain") {
                    throw JsonValidationException("$label.name is unsupported")
                }
                val versionLong = entry.getRequired("version").asLong("$label.version")
                if (versionLong !in 1..Int.MAX_VALUE.toLong()) {
                    throw JsonValidationException("$label.version is invalid")
                }
                val parameters = entry.getRequired("parameters").asObject("$label.parameters")
                parameters.requireExactKeys("$label.parameters", setOf("gainDb", "invert"))
                val gainDb = parameters.getRequired("gainDb").asDouble("$label.parameters.gainDb")
                if (gainDb > 0.0 || gainDb < MIN_AUTHORED_DSP_GAIN_DB) {
                    throw JsonValidationException("$label.parameters.gainDb is outside the safe authored range")
                }
                val invert = parameters.getRequired("invert").asBoolean("$label.parameters.invert")
                val treatment = entry.getRequired("treatment").asString("$label.treatment")
                if (treatment != "BAKED_INTO_TARGET_ONLY_CAPTURE") {
                    throw JsonValidationException("$label.treatment is unsupported")
                }
                val evidence = entry.getRequired("evidence").asString("$label.evidence")
                if (evidence != "FMOD108_SET_PARAMETER_CALLBACK") {
                    throw JsonValidationException("$label.evidence is unsupported")
                }
                AuthoredDspProvenanceV2(
                    name = name,
                    version = versionLong.toInt(),
                    gainDb = gainDb,
                    invert = invert,
                    treatment = treatment,
                    evidence = evidence,
                )
            }
            if (result.map(AuthoredDspProvenanceV2::name).toSet().size != result.size) {
                throw JsonValidationException("provenance.authoredDsp names must be unique")
            }
            return result
        }

        private fun parseCertifiedSilentSources(value: JsonValue): List<CertifiedSilentSourceV2> {
            val entries = value.asArray("provenance.certifiedSilentSources")
            if (entries.size > MAX_CERTIFIED_SILENT_SOURCES) {
                throw JsonValidationException(
                    "provenance.certifiedSilentSources exceeds its bounded count",
                )
            }
            val result = entries.mapIndexed { index, raw ->
                val label = "provenance.certifiedSilentSources[$index]"
                val entry = raw.asObject(label)
                entry.requireExactKeys(
                    label,
                    setOf("sourceGuid", "role", "disposition", "verificationPayloadSha256"),
                )
                val sourceGuid = entry.getRequired("sourceGuid").asString("$label.sourceGuid")
                val role = enumValue<PackTrackRole>(entry.getRequired("role"), "$label.role")
                if (!guid.matches(sourceGuid) || role !in CERTIFIED_SILENT_SOURCE_ROLES ||
                    entry.getRequired("disposition").asString("$label.disposition") !=
                    "AUTHORED_TARGET_SILENT"
                ) {
                    throw JsonValidationException("$label is invalid")
                }
                CertifiedSilentSourceV2(
                    sourceGuid,
                    role,
                    requireSha(
                        entry.getRequired("verificationPayloadSha256"),
                        "$label.verificationPayloadSha256",
                    ),
                )
            }
            if (result.map(CertifiedSilentSourceV2::sourceGuid).toSet().size != result.size) {
                throw JsonValidationException("provenance.certifiedSilentSources must be unique")
            }
            return result
        }

        private fun validateTurboSilentSourceProvenance(
            programs: List<PackOneShotProgramV2>,
            certifiedSources: List<CertifiedSilentSourceV2>,
        ) {
            val turboPrograms = programs.filter { it.trigger == PackOneShotTrigger.TURBO_EVENT }
            val nodeCertificates = turboPrograms.flatMap { program ->
                program.nodes.filterIsInstance<PackOneShotSilentNodeV2>().map { node ->
                    Triple(node.sourceGuid, node.resolvedRole, node.sourceVerificationPayloadSha256)
                }
            }
            val provenanceCertificates = certifiedSources.asSequence()
                .filter { it.role == PackTrackRole.BOV || it.role == PackTrackRole.TURBO_TRANSIENT }
                .map { source ->
                    Triple(source.sourceGuid, source.role, source.verificationPayloadSha256)
                }
                .toList()
            if (nodeCertificates.size != nodeCertificates.toSet().size ||
                provenanceCertificates.size != provenanceCertificates.toSet().size ||
                nodeCertificates.toSet() != provenanceCertificates.toSet()
            ) {
                throw JsonValidationException(
                    "Turbo SILENT_SOURCE nodes must exactly match certified silent provenance",
                )
            }
            val audibleTrackCertificates = turboPrograms.asSequence()
                .flatMap { it.nodes.asSequence() }
                .filterIsInstance<PackOneShotTrackNodeV2>()
                .map { requireNotNull(it.sourceVerificationPayloadSha256) }
                .toSet()
            if (provenanceCertificates.any { it.third in audibleTrackCertificates }) {
                throw JsonValidationException(
                    "A certified silent turbo source must not reference an audible PCM track",
                )
            }
        }

        /**
         * An exact-zero deterministic shift is omitted as a whole: it has neither PCM nor a
         * SILENT_SOURCE topology node. Its source-bound provenance certificate is the retained
         * evidence. Reject the deterministic ids the compiler would otherwise have emitted so a
         * source cannot be both certified silent and represented by an audible program.
         */
        private fun validateShiftSilentSourceProvenance(
            tracks: List<SoundTrackManifestV1>,
            programs: List<PackOneShotProgramV2>,
            certifiedSources: List<CertifiedSilentSourceV2>,
        ) {
            val shiftCertificates = certifiedSources.filter {
                it.role == PackTrackRole.SHIFT_UP || it.role == PackTrackRole.SHIFT_DOWN
            }
            if (shiftCertificates.isEmpty()) return

            val trackIds = tracks.mapTo(hashSetOf(), SoundTrackManifestV1::id)
            val nodeIds = programs.asSequence()
                .flatMap { it.nodes.asSequence() }
                .mapTo(hashSetOf(), PackOneShotNodeV2::id)
            shiftCertificates.forEach { source ->
                val compactGuid = source.sourceGuid.replace("-", "")
                val rolePrefix = if (source.role == PackTrackRole.SHIFT_UP) {
                    "shift_up"
                } else {
                    "shift_down"
                }
                val expectedTrackId = "${rolePrefix}_${compactGuid.take(16)}"
                val expectedNodeId = "track_$compactGuid"
                if (expectedTrackId in trackIds || expectedNodeId in nodeIds) {
                    throw JsonValidationException(
                        "Certified authored-target-silent shift sources must be omitted from tracks and programs",
                    )
                }
            }

            val audibleVerificationHashes = programs.asSequence()
                .flatMap { it.nodes.asSequence() }
                .filterIsInstance<PackOneShotTrackNodeV2>()
                .mapNotNull(PackOneShotTrackNodeV2::sourceVerificationPayloadSha256)
                .toSet()
            if (shiftCertificates.any { it.verificationPayloadSha256 in audibleVerificationHashes }) {
                throw JsonValidationException(
                    "A certified silent shift source must not reference an audible PCM track",
                )
            }
        }

        private fun parseAssets(value: JsonValue, trackPaths: Set<String>): List<PackAssetManifestV1> {
            val assets = value.asArray("assets").mapIndexed { index, raw ->
                val label = "assets[$index]"
                val asset = raw.asObject(label)
                asset.requireExactKeys(label, setOf("path", "sha256", "mediaType"))
                val path = requireArchivePath(asset.getRequired("path").asString("$label.path"), label, "previews", false)
                if (path in trackPaths) throw JsonValidationException("$label collides with an audio track")
                val type = asset.getRequired("mediaType").asString("$label.mediaType")
                if (type !in setOf("image/jpeg", "image/png", "image/webp")) {
                    throw JsonValidationException("$label.mediaType is unsupported")
                }
                PackAssetManifestV1(path, requireSha(asset.getRequired("sha256"), "$label.sha256"), type)
            }
            if (assets.map { it.path }.toSet().size != assets.size) {
                throw JsonValidationException("Asset paths must be unique")
            }
            return assets
        }

        private fun requireIdentifier(value: JsonValue, label: String): String {
            val text = value.asString(label)
            if (!identifier.matches(text)) throw JsonValidationException("$label is not a valid identifier")
            return text
        }

        private fun requireSha(value: JsonValue, label: String): String {
            val text = value.asString(label)
            if (!sha256.matches(text)) throw JsonValidationException("$label is not lowercase SHA-256")
            return text
        }

        private fun requireSymbol(value: JsonValue, label: String): String {
            val text = value.asString(label)
            if (!symbol.matches(text)) throw JsonValidationException("$label is not a valid symbol")
            return text
        }

        private fun requireInteger(value: JsonValue, label: String, expected: Long) {
            if (value.asLong(label) != expected) throw JsonValidationException("$label must be $expected")
        }

        private fun requireSoftwareChannelPriority(value: JsonValue, label: String): Int {
            val priority = value.asLong(label)
            if (priority !in 0L..256L) {
                throw JsonValidationException("$label must be in 0..256")
            }
            return priority.toInt()
        }

        private fun legacySoftwareChannelPriority(role: PackTrackRole): Int = when (role) {
            PackTrackRole.IDLE,
            PackTrackRole.COAST,
            PackTrackRole.TEXTURE,
            PackTrackRole.INTAKE,
            PackTrackRole.EXHAUST,
            PackTrackRole.TURBO,
            PackTrackRole.SPOOL,
            PackTrackRole.TRANSMISSION,
            PackTrackRole.LIMITER,
            PackTrackRole.ENGINE_TRANSIENT -> FMOD_AUTHORED_ENGINE_PRIORITY
            PackTrackRole.ENGINE_START -> FMOD_AUTHORED_ENGINE_PRIORITY

            else -> FMOD_DEFAULT_EVENT_PRIORITY
        }

        private fun positive(value: JsonValue, label: String): Double =
            value.asDouble(label).also { if (it <= 0.0) throw JsonValidationException("$label must be positive") }

        private fun boundedPositive(value: JsonValue, label: String, maximum: Double): Double =
            positive(value, label).also {
                if (it > maximum) throw JsonValidationException("$label is out of range")
            }

        private fun boundedNonNegative(value: JsonValue, label: String, maximum: Double): Double =
            nonNegative(value, label).also {
                if (it > maximum) throw JsonValidationException("$label is out of range")
            }

        private fun negative(value: JsonValue, label: String): Double =
            value.asDouble(label).also { if (it >= 0.0) throw JsonValidationException("$label must be negative") }

        private fun nonNegative(value: JsonValue, label: String): Double =
            value.asDouble(label).also { if (it < 0.0) throw JsonValidationException("$label must not be negative") }

        private fun nullableNumber(value: JsonValue, label: String): Double? = when (value) {
            JsonValue.NullValue -> null
            else -> value.asDouble(label)
        }

        private fun nullableInteger(value: JsonValue, label: String): Long? = when (value) {
            JsonValue.NullValue -> null
            else -> value.asLong(label)
        }

        private fun requireArchivePath(value: String, label: String, prefix: String, requireFlac: Boolean): String {
            if (value.toByteArray(Charsets.UTF_8).size > 240) {
                throw JsonValidationException("$label path is too long")
            }
            if (value.isEmpty() || value.startsWith('/') || value.startsWith('\\') || value.contains('\\')) {
                throw JsonValidationException("$label is not a normalized relative path")
            }
            val parts = value.split('/')
            if (parts.size < 2 || parts.first() != prefix ||
                parts.any { it.isEmpty() || it == "." || it == ".." || ':' in it || '\u0000' in it }
            ) {
                throw JsonValidationException("$label must be under $prefix/")
            }
            if (File(value).isAbsolute) throw JsonValidationException("$label must be relative")
            if (requireFlac && !value.lowercase(Locale.ROOT).endsWith(".flac")) {
                throw JsonValidationException("$label must name a FLAC file")
            }
            return value
        }

        private fun String.requireNotBlank(label: String): String = also {
            if (isBlank()) throw JsonValidationException("$label must not be blank")
        }
    }
}

/**
 * AC's TURBO event is the continuous boost/spool character. SPOOL remains a distinct future
 * compiler role, but a pack must not duplicate identical PCM merely to advertise availability.
 */
internal fun deriveCoreEffectAvailability(roles: Set<PackTrackRole>): CoreEffectAvailability =
    CoreEffectAvailability(
        idle = PackTrackRole.IDLE in roles,
        coast = PackTrackRole.COAST in roles,
        texture = PackTrackRole.TEXTURE in roles,
        intake = PackTrackRole.INTAKE in roles,
        exhaust = PackTrackRole.EXHAUST in roles,
        turbo = PackTrackRole.TURBO in roles || PackTrackRole.TURBO_TRANSIENT in roles,
        spool = PackTrackRole.TURBO in roles || PackTrackRole.SPOOL in roles ||
            PackTrackRole.TURBO_TRANSIENT in roles,
        bov = PackTrackRole.BOV in roles,
        transmission = PackTrackRole.TRANSMISSION in roles,
        limiter = PackTrackRole.LIMITER in roles,
        shift = PackTrackRole.SHIFT_UP in roles || PackTrackRole.SHIFT_DOWN in roles,
        overrun = PackTrackRole.OVERRUN in roles,
        popsBangsCracks = roles.any {
            it == PackTrackRole.OVERRUN || it == PackTrackRole.POP ||
                it == PackTrackRole.BANG || it == PackTrackRole.CRACK
        },
        engineStart = PackTrackRole.ENGINE_START in roles,
    )

private fun ByteArray.toLowerHexString(): String {
    val digits = "0123456789abcdef"
    val result = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 0x0f]
    }
    return result.concatToString()
}
