package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

internal enum class SampleLayerRole {
    IDLE, COAST, TEXTURE, INTAKE, EXHAUST, TURBO, SPOOL, LIMITER,
}

/** Roles whose authored pedal curves describe coast-side routing; without LOAD packs they must
 * still respond at full throttle via the mirrored pedal position. */
private val LOAD_COMPLEMENT_PEDAL_ROLES = setOf(
    SampleLayerRole.COAST,
    SampleLayerRole.EXHAUST,
    SampleLayerRole.INTAKE,
    SampleLayerRole.TEXTURE,
)

internal fun pedalAmplitudeForLayerRole(
    role: SampleLayerRole,
    curve: AutomationCurve?,
    throttle: Double,
): Double {
    if (curve == null) {
        return 1.0
    }
    val clamped = throttle.coerceIn(0.0, 1.0)
    val direct = curve.valueAt(clamped)
    if (role !in LOAD_COMPLEMENT_PEDAL_ROLES) {
        return direct
    }
    val loadPedal = curve.valueAt(1.0 - clamped)
    return maxOf(direct, loadPedal)
}

internal enum class SampleEffectTrigger {
    CONTINUOUS_LOOP, TRANSMISSION_LOOP, LIMITER, SHIFT_UP, SHIFT_DOWN, THROTTLE_LIFT, BOV_LIFT,
    ENGINE_EVENT, LIMITER_EVENT, TURBO_EVENT, ENGINE_START,
}

/** Compatibility defaults for schema-v1 packs and direct test fixtures. Schema-v2 packs supply
 * every value from the source-bound FMOD oracle and never use this fallback. */
internal fun defaultSoftwareVoicePriority(trigger: SampleEffectTrigger): Int = when (trigger) {
    SampleEffectTrigger.CONTINUOUS_LOOP,
    SampleEffectTrigger.TRANSMISSION_LOOP,
    SampleEffectTrigger.ENGINE_EVENT,
    SampleEffectTrigger.LIMITER_EVENT -> GlobalVoiceArbiter.FMOD_AUTHORED_ENGINE_PRIORITY
    SampleEffectTrigger.TURBO_EVENT -> GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY
    else -> GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY
}

internal enum class OneShotPlayMode {
    NORMAL, SMART_RANDOM, SEQUENTIAL,
}

internal enum class OneShotSelectionMode {
    NORMAL, SELECT_ALL,
}

internal enum class OneShotGateControl {
    ENGINE_RPM, ACCELERATOR, SHIFT_STATE, BOOST, BOV, BOV_DECAY, DRIVETRAIN_SPEED, DECAY,
}

internal enum class OneShotPolicyKind {
    AC_BACKFIRE, BOV_LIFT, LIMITER, SHIFT_UP, SHIFT_DOWN, ENGINE_EVENT_REGION,
    PERSISTENT_LIMITER_EVENT, TURBO_EVENT_PROGRAM, ENGINE_START,
}

internal data class OneShotParameterGateSpec(
    val control: OneShotGateControl,
    val minimum: Double,
    val maximum: Double,
    val includeMinimum: Boolean = true,
    val includeMaximum: Boolean = true,
)

internal sealed interface OneShotNodeSpec {
    val id: String
    val triggerChance: Double
}

internal data class OneShotGroupMemberSpec(
    val nodeId: String,
    val weight: Double,
    val order: Int,
)

internal data class OneShotGroupNodeSpec(
    override val id: String,
    override val triggerChance: Double,
    val playMode: OneShotPlayMode,
    val selectionMode: OneShotSelectionMode,
    val members: List<OneShotGroupMemberSpec>,
) : OneShotNodeSpec

internal enum class ZeroGainVirtualizationKind {
    NOT_APPLICABLE,
    EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE,
    ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO,
}

internal enum class ZeroGainTransitionPolicy {
    IMMEDIATE_EXACT_ZERO,
    RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO,
}

internal enum class ZeroGainTransitionPitch {
    LIVE_CURRENT_RPM_PITCH,
    AUTHORED_STATIC_BAKED_PITCH,
}

internal enum class ZeroGainTransitionPhaseTreatment {
    RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET,
    APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET,
}

internal enum class EngineTransientReentryPolicy {
    CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
    NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER,
}

/**
 * Source-bound output transition observed when an authored source reaches exact zero.
 * Transition frame values are stereo writer/output frames at 48 kHz. PCM keeps advancing at its
 * current authored pitch until the separate hold boundary. A source-certified decoded-PCM phase
 * correction may be applied once when a completed HOLD episode is restored; it is never applied
 * when positive gain returns before the hold boundary.
 */
internal data class ZeroGainTransitionSpec(
    val policy: ZeroGainTransitionPolicy,
    val retainPreZeroGainWriterFrames: Int,
    val linearFadeWriterFrames: Int,
    val pitchDuringTransition: ZeroGainTransitionPitch,
    val phaseTreatment: ZeroGainTransitionPhaseTreatment =
        ZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET,
    /** Signed 48 kHz decoded capture-PCM frame correction applied once after a completed hold. */
    val restoreCapturePcmPhaseOffsetFrames: Double = 0.0,
) {
    init {
        require(retainPreZeroGainWriterFrames >= 0)
        require(linearFadeWriterFrames >= 0)
        require(restoreCapturePcmPhaseOffsetFrames.isFinite())
        require(kotlin.math.abs(restoreCapturePcmPhaseOffsetFrames) <=
            MAXIMUM_ABSOLUTE_RESTORE_PHASE_OFFSET_FRAMES)
        when (phaseTreatment) {
            ZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET ->
                require(restoreCapturePcmPhaseOffsetFrames == 0.0)
            ZeroGainTransitionPhaseTreatment.APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET ->
                require(restoreCapturePcmPhaseOffsetFrames != 0.0)
        }
        when (policy) {
            ZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO -> {
                require(retainPreZeroGainWriterFrames == 0)
                require(linearFadeWriterFrames == 0)
            }
            ZeroGainTransitionPolicy.RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO ->
                require(linearFadeWriterFrames > 0)
        }
    }

    val exactZeroFromWriterFrame: Int = Math.addExact(
        retainPreZeroGainWriterFrames,
        linearFadeWriterFrames,
    )

    companion object {
        const val MAXIMUM_ABSOLUTE_RESTORE_PHASE_OFFSET_FRAMES = 512.0

        val IMMEDIATE = ZeroGainTransitionSpec(
            policy = ZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO,
            retainPreZeroGainWriterFrames = 0,
            linearFadeWriterFrames = 0,
            pitchDuringTransition = ZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH,
            phaseTreatment = ZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET,
            restoreCapturePcmPhaseOffsetFrames = 0.0,
        )
    }
}

/** Allocation-free subset of the source-bound FMOD zero-gain voice-lifecycle proof. */
internal data class ZeroGainVirtualizationSpec(
    val kind: ZeroGainVirtualizationKind,
    val phaseHoldLatencyWriterFrames: Int,
    val transition: ZeroGainTransitionSpec? = null,
) {
    init {
        when (kind) {
            ZeroGainVirtualizationKind.NOT_APPLICABLE -> {
                require(phaseHoldLatencyWriterFrames == 0)
                require(transition == null)
            }
            ZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE -> {
                require(phaseHoldLatencyWriterFrames > 0)
                require(phaseHoldLatencyWriterFrames % WRITER_DSP_BLOCK_FRAMES == 0)
                requireNotNull(transition)
                require(transition.exactZeroFromWriterFrame <= phaseHoldLatencyWriterFrames)
            }
            ZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO -> {
                require(phaseHoldLatencyWriterFrames == 0)
                requireNotNull(transition)
            }
        }
    }

    val exactZeroLifecycleEnabled: Boolean get() = kind != ZeroGainVirtualizationKind.NOT_APPLICABLE
    val holdsPhaseAfterLatency: Boolean
        get() = kind == ZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE

    companion object {
        const val WRITER_DSP_BLOCK_FRAMES = 256

        val NOT_APPLICABLE = ZeroGainVirtualizationSpec(
            ZeroGainVirtualizationKind.NOT_APPLICABLE,
            phaseHoldLatencyWriterFrames = 0,
            transition = null,
        )
    }
}

internal data class OneShotTrackNodeSpec(
    override val id: String,
    override val triggerChance: Double,
    val effectId: String,
    val parameterGates: List<OneShotParameterGateSpec>,
    val rpmAmplitudeCurve: AutomationCurve?,
    val throttleAmplitudeCurve: AutomationCurve?,
    val liveVarispeed: Boolean = false,
    val rootRpm: Double? = null,
    val captureControlValues: List<OneShotControlValueSpec> = emptyList(),
    val controlGainCurves: List<OneShotControlCurveSpec> = emptyList(),
    val pitchAutomations: List<OneShotPitchAutomationSpec> = emptyList(),
    val sourceVerificationPayloadSha256: String? = null,
    val zeroGainVirtualization: ZeroGainVirtualizationSpec =
        ZeroGainVirtualizationSpec.NOT_APPLICABLE,
    val engineTransientReentryPolicy: EngineTransientReentryPolicy =
        EngineTransientReentryPolicy.CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
) : OneShotNodeSpec

/** A certified authored source that participates in selection but intentionally owns no PCM. */
internal data class OneShotSilentNodeSpec(
    override val id: String,
    override val triggerChance: Double,
    val sourceGuid: String,
    val resolvedRole: String,
    val sourceVerificationPayloadSha256: String,
) : OneShotNodeSpec

internal data class OneShotControlValueSpec(
    val control: OneShotGateControl,
    val value: Double,
)

internal data class OneShotControlCurveSpec(
    val control: OneShotGateControl,
    val curve: AutomationCurve,
)

internal data class OneShotPitchAutomationSpec(
    val control: OneShotGateControl,
    val captureSemitones: Double,
    val playbackRateCurve: AutomationCurve,
)

internal data class EngineEventProgramPolicySpec(
    val requiresEventStartInside: Boolean,
    val parameterGates: List<OneShotParameterGateSpec>,
    val laneCount: Int,
    val maximumDecodedOneShotFrames: Int,
    val logicalVoiceLimit: Int,
    val softwareRealVoiceBudget: Int,
)

internal enum class PersistentLimiterProgramMode {
    TIMELINE_PERIOD_LOOP,
    DECAY_REGION_ONE_SHOT,
    DECAY_REGION_LOOP,
}

internal data class LimiterDecayPlacementSpec(
    val minimumSeconds: Double,
    val maximumSeconds: Double,
    val includeMinimum: Boolean,
    val includeMaximum: Boolean,
) {
    fun contains(seconds: Float): Boolean {
        val value = seconds.toDouble()
        return value >= minimumSeconds && value <= maximumSeconds &&
            (includeMinimum || value != minimumSeconds) &&
            (includeMaximum || value != maximumSeconds)
    }
}

internal data class PersistentLimiterProgramPolicySpec(
    val mode: PersistentLimiterProgramMode,
    val decayGainCurve: AutomationCurve,
    val decayPlacement: LimiterDecayPlacementSpec?,
    val timelinePeriodFrames: Int?,
    val oneShotLaneCount: Int,
)

internal enum class TurboEventProgramMode {
    BOOST_RELEASE_REGION_ONE_SHOT,
    TIMELINE_PERIODIC_ONE_SHOT,
    PARAMETER_SHEET_EVENT_START_ONE_SHOT,
}

internal data class TurboEventProgramPolicySpec(
    val mode: TurboEventProgramMode,
    val placementMinimumBoost: Double?,
    val placementMaximumBoost: Double?,
    val includeMinimum: Boolean,
    val includeMaximum: Boolean,
    val timelineStartFrames: Long?,
    val timelinePeriodFrames: Long?,
    val coreProgram: Boolean,
    /** Turbo one-shots share the global cap; this is a per-program admission ceiling only. */
    val oneShotLaneCount: Int = GlobalVoiceArbiter.AC_LOGICAL_VOICE_LIMIT,
) {
    fun containsBoost(boost: Double): Boolean {
        val minimum = placementMinimumBoost ?: return true
        val maximum = requireNotNull(placementMaximumBoost)
        return boost >= minimum && boost <= maximum &&
            (includeMinimum || boost != minimum) &&
            (includeMaximum || boost != maximum)
    }
}

internal data class OneShotTriggerPolicySpec(
    val kind: OneShotPolicyKind,
    val minimumRpm: Double,
    val maximumRpm: Double?,
    val armPedal: Double?,
    val firePedal: Double?,
    val armBoost: Double?,
    val initialPeakPedal: Double?,
    val initialArmPedal: Double?,
    val initialFirePedal: Double?,
    val minimumArmSeconds: Double,
    val cooldownSeconds: Double,
    val periodHz: Double?,
    val engineEvent: EngineEventProgramPolicySpec? = null,
    val limiterEvent: PersistentLimiterProgramPolicySpec? = null,
    val turboEvent: TurboEventProgramPolicySpec? = null,
)

internal data class OneShotProgramSpec(
    val id: String,
    val trigger: SampleEffectTrigger,
    val rootNodeIds: List<String>,
    val nodes: List<OneShotNodeSpec>,
    val policy: OneShotTriggerPolicySpec,
    val softwareVoicePriority: Int = defaultSoftwareVoicePriority(trigger),
)

internal data class SampleEffectControlSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val bit: Long,
)

internal object SampleEffectControls {
    /** Internal core routing only; never presented as a user effect checkbox. */
    val coreEngine = SampleEffectControlSpec(
        id = "core_engine_transient",
        displayName = "Engine transient",
        description = "Authored engine-event transient",
        bit = 1L shl 6,
    )
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
        displayName = "Turbo and blow-off",
        description = "Authored turbo spool and blow-off effects",
        bit = 1L shl 3,
    )
    val limiter = SampleEffectControlSpec(
        id = "limiter",
        displayName = "Limiter",
        description = "Authored limiter pulse",
        bit = 1L shl 4,
    )
    val popsBangsCracks = SampleEffectControlSpec(
        id = "pops_bangs_cracks",
        displayName = "Pops, bangs, and cracks",
        description = "Authored exhaust transients on overrun",
        bit = 1L shl 5,
    )
    /** Internal routing for optional car-select engine-start one-shots; never shown in the mixer. */
    val engineStart = SampleEffectControlSpec(
        id = "engine_start",
        displayName = "Engine start",
        description = "Authored crank/ignition one-shot on car selection",
        bit = 1L shl 7,
    )
}

internal data class SampleEffectSpec(
    val id: String,
    val control: SampleEffectControlSpec,
    val assetName: String,
    val trigger: SampleEffectTrigger,
    val baseGainDb: Double = 0.0,
    val minimumRpm: Double = 0.0,
    val displayName: String = control.displayName,
    val auditionable: Boolean = false,
    val rpmAmplitudeCurve: AutomationCurve? = null,
    val throttleAmplitudeCurve: AutomationCurve? = null,
    /** RPM at which a compiler capture was rendered, used for authored continuous-effect pitch. */
    val autopitchRootRpm: Double? = null,
    /** FMOD PropertyIndex1 relative-rate curve; when present it replaces RPM/root AutoPitch. */
    val authoredRelativeRateCurve: AutomationCurve? = null,
    val turboAudioResponse: TurboAudioResponse = TurboAudioResponse.NONE,
    val coreEngineTransient: Boolean = false,
    val engineStartEffect: Boolean = false,
    val polyphonicTemplate: Boolean = coreEngineTransient,
    val softwareVoicePriority: Int = defaultSoftwareVoicePriority(trigger),
    val looping: Boolean = trigger == SampleEffectTrigger.CONTINUOUS_LOOP ||
        trigger == SampleEffectTrigger.TRANSMISSION_LOOP,
    val startsActive: Boolean = looping,
) {
    init {
        require(softwareVoicePriority in
            GlobalVoiceArbiter.FMOD_HIGHEST_PRIORITY..GlobalVoiceArbiter.FMOD_LOWEST_PRIORITY
        ) { "FMOD channel priority must be in 0..256" }
    }

    fun authoredPlaybackRatio(controlRpm: Double): Double? = when {
        authoredRelativeRateCurve != null ->
            authoredRelativeRateCurve.valueAt(controlRpm.coerceAtLeast(0.0))
        autopitchRootRpm != null -> {
            require(autopitchRootRpm > 0.0) { "Continuous-effect capture root must be positive" }
            (controlRpm.coerceAtLeast(0.0) / autopitchRootRpm).coerceIn(0.10, 4.0)
        }
        else -> null
    }

    /** FMOD AutoPitch on an engine-event leaf follows live RPM/root without pitch clamping. */
    fun authoredEngineTransientPlaybackRatio(controlRpm: Double): Double {
        require(coreEngineTransient && trigger == SampleEffectTrigger.ENGINE_EVENT)
        authoredRelativeRateCurve?.let {
            return it.valueAt(controlRpm.coerceAtLeast(0.0))
        }
        // A source-certified static leaf has its authored pitch baked into PCM for its whole
        // lifetime. It deliberately has no rootRpm and therefore remains at capture rate 1.0.
        val root = autopitchRootRpm ?: return 1.0
        require(root > 0.0) { "Engine-transient capture root must be positive" }
        return controlRpm.coerceAtLeast(0.0) / root
    }
}

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
    /** FMOD PropertyIndex1 relative-rate curve; when present it replaces RPM/root AutoPitch. */
    val authoredRelativeRateCurve: AutomationCurve? = null,
    val basePitchSemitones: Double = 0.0,
    val baseGainDb: Double = 0.0,
    val throttleGainDb: AutomationCurve? = null,
    val throttleAmplitudeCurve: AutomationCurve? = null,
    val rpmAmplitudeCurves: List<AutomationCurve> = emptyList(),
    val rpmGainDbCurves: List<AutomationCurve> = emptyList(),
    /** Runtime overlap used for sources whose authored endpoints are not phase-continuous. */
    val loopCrossfadeFrames: Int = 0,
    val softwareVoicePriority: Int = GlobalVoiceArbiter.FMOD_AUTHORED_ENGINE_PRIORITY,
) {
    init {
        require(loopCrossfadeFrames >= 0) { "Loop crossfade cannot be negative" }
        require(softwareVoicePriority in
            GlobalVoiceArbiter.FMOD_HIGHEST_PRIORITY..GlobalVoiceArbiter.FMOD_LOWEST_PRIORITY
        ) { "FMOD channel priority must be in 0..256" }
    }

    fun playbackRatio(rpm: Double): Double {
        val authoredPitch = 2.0.pow(basePitchSemitones / 12.0)
        val relativeRate = authoredRelativeRateCurve?.valueAt(rpm.coerceAtLeast(0.0))
            ?: (autopitchRootRpm?.let { rpm / it } ?: 1.0).coerceIn(0.10, 4.0)
        return relativeRate * authoredPitch
    }

    fun gainAt(rpm: Double, throttle: Double): Double {
        if (rpm !in startRpm..endRpm) return 0.0

        var amplitude = 1.0
        amplitude *= pedalAmplitudeForLayerRole(role, throttleAmplitudeCurve, throttle)
        var amplitudeIndex = 0
        while (amplitudeIndex < rpmAmplitudeCurves.size) {
            amplitude *= rpmAmplitudeCurves[amplitudeIndex].valueAt(rpm)
            amplitudeIndex += 1
        }
        if (amplitude <= 0.0) return 0.0

        var decibels = baseGainDb + (throttleGainDb?.valueAt(throttle) ?: 0.0)
        var gainIndex = 0
        while (gainIndex < rpmGainDbCurves.size) {
            decibels += rpmGainDbCurves[gainIndex].valueAt(rpm)
            gainIndex += 1
        }
        return amplitude * 10.0.pow(decibels / 20.0)
    }
}

internal data class EngineSampleProfile(
    val id: String,
    val displayName: String,
    val assetDirectory: String,
    val previewAssetName: String,
    /** Compiler PCM rate; imported FLAC must decode to this rate. */
    val outputSampleRate: Int,
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
    /** Exact FMOD MultiInstrument trees and selected car's event trigger policy. */
    val oneShotPrograms: List<OneShotProgramSpec> = emptyList(),
    val throttleOutputGainDb: AutomationCurve? = null,
    /** Authored AC limiter pulse frequency, not a one-time threshold event. */
    val limiterHz: Double = 20.0,
    /** Allocation-free programs compiled from this car's authored ctrl_turbo*.ini metadata. */
    val turboControllerBank: TurboControllerBankSpec? = null,
    /** AC turbo spool, pressure, normalized FMOD boost, and physical BOV state. */
    val turboPhysics: TurboPhysicsSpec? = null,
    /** Authored throttle.lut used by AC's physical engine/turbo input, never by FMOD throttle. */
    val turboPhysicalThrottleCurve: AutomationCurve? = null,
    /** Authored AutoBlip/AutoShifter/drivetrain gas cuts evaluated at AC's 3 ms step. */
    val engineGasAssist: EngineGasAssistSpec = EngineGasAssistSpec.NONE,
    /** Exact authored setup provenance and explicit compiler/runtime quirk policy. */
    val authoredCarMetadata: AuthoredCarMetadata = AuthoredCarMetadata.EMPTY,
) {
    val requiredAssets: Set<String> = linkedSetOf<String>().apply {
        layers.mapTo(this) { it.assetName }
        effects.mapTo(this) { it.assetName }
    }

    val effectControls: List<SampleEffectControlSpec> = effects.asSequence()
        .filterNot(SampleEffectSpec::coreEngineTransient)
        .filterNot(SampleEffectSpec::engineStartEffect)
        .map(SampleEffectSpec::control)
        .distinctBy(SampleEffectControlSpec::id)
        .toList()
    val hasEngineStart: Boolean = effects.any(SampleEffectSpec::engineStartEffect)
    val defaultEffectMask: Long = effectControls.fold(0L) { mask, control -> mask or control.bit } or
        if (hasEngineStart) SampleEffectControls.engineStart.bit else 0L
    val auditionEffectMask: Long = effects.asSequence().filter { it.auditionable }
        .fold(0L) { mask, effect -> mask or effect.control.bit }

    fun outputGainAt(throttle: Double): Double =
        10.0.pow((throttleOutputGainDb?.valueAt(throttle.coerceIn(0.0, 1.0)) ?: 0.0) / 20.0)
}

/** Metadata-only bootstrap state. Every selectable car is supplied by the external catalog. */
internal val SILENT_CATALOG_PROFILE = EngineSampleProfile(
    id = "catalog_unselected",
    displayName = "Assetto Corsa catalog",
    assetDirectory = "",
    previewAssetName = "",
    outputSampleRate = 48_000,
    minimumRpm = 0.0,
    maximumRpm = 10_000.0,
    idleRpm = 900.0,
    redlineRpm = 8_000.0,
    limiterRpm = 8_200.0,
    upshiftRpm = 8_000.0,
    gearRatios = listOf(3.20, 2.10, 1.52, 1.18, 0.96, 0.80),
    upshiftDurationSeconds = 0.150,
    downshiftDurationSeconds = 0.180,
    layers = emptyList(),
)
