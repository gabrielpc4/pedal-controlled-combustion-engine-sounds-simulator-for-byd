package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign

internal data class SampleRendererDiagnostics(
    val profileId: String = "none",
    val loadedLoops: Int = 0,
    val loadedEffects: Int = 0,
    val decodedBytes: Long = 0,
    val targetRpm: Int = 0,
    val renderRpm: Int = 0,
    val throttle: Double = 0.0,
    val activeLayers: String = "none",
    val playingSamples: List<PlayingSampleLabel> = emptyList(),
    val layerOutputMeters: List<LayerOutputMeter> = emptyList(),
    val framesRendered: Long = 0,
    val loopWraps: Long = 0,
    val peak: Double = 0.0,
    val overRangeSamples: Long = 0,
    val effectTriggers: Long = 0,
    val activeEffects: String = "none",
    val turboControllerGain: Double = 1.0,
    val globalVoiceBudget: Int = 0,
    val globalLogicalVoices: Int = 0,
    val globalRealVoices: Int = 0,
    val globalVirtualVoices: Int = 0,
    val globalRejectedTriggers: Long = 0L,
    val globalStolenLogicalVoices: Long = 0L,
)

/** A profile-driven reconstruction of one FMOD engine event. */
internal class SampleEngineRenderer private constructor(
    private val outputSampleRate: Int,
    private val profile: EngineSampleProfile,
    private val voices: Array<LoopVoice>,
    private val effectVoices: Array<EffectVoice>,
    private val decodedBytes: Long,
    private val nativeMixer: NativePcmMixer? = null,
) {
    fun closeNativeMixer() = nativeMixer?.close()
    @Volatile private var requestedRpmSnapshot = profile.idleRpm
    @Volatile private var smoothedRpm = profile.idleRpm
    @Volatile private var smoothedDrivetrainRpm = 0.0
    @Volatile private var smoothedThrottle = 0.0
    @Volatile private var masterGain = 0.0
    @Volatile private var enabledGain = 0.0
    @Volatile private var continuousProgramGain = 1.0
    @Volatile private var framesRendered = 0L
    @Volatile private var loopWraps = 0L
    @Volatile private var overRangeSamples = 0L
    @Volatile private var effectTriggers = 0L
    @Volatile private var latestPeak = 0.0
    @Volatile private var latestTarget: EngineAudioControlFrame = EngineAudioFrame()
    private var profileOutputGain = profile.outputGainAt(0.0)
    private var lastShiftSerial = 0L
    private var hasLastShiftSerial = false
    private var lastAuditionSerial = 0L
    private var hasLastAuditionSerial = false
    private var lastEngineStartSerial = 0L
    private var hasLastEngineStartSerial = false
    private var throttleLiftArmed = false
    private var limiterActive = false
    private var limiterFramesUntilPulse = 0
    private var auditionActive = false
    private val auditionTriggerRpm = findAuditionTriggerRpm(profile)
    private val turboPhysicsRuntime = profile.turboPhysics?.newRuntime()
    private val engineGasAssistRuntime = profile.engineGasAssist.newRuntime(
        throttleMap = profile.turboPhysicalThrottleCurve ?: IDENTITY_THROTTLE_CURVE,
        limiterRpm = profile.limiterRpm,
        limiterHz = profile.limiterHz,
    )
    /** Schema-v1 compatibility only; V2 boost always comes from authored physical turbo pressure. */
    private val legacyTurboControllerRuntime = if (turboPhysicsRuntime == null) {
        profile.turboControllerBank?.newRuntime()
    } else {
        null
    }
    private val polyphonicProgramOrdinalByProfileIndex = IntArray(profile.oneShotPrograms.size) { -1 }
        .also { ordinals ->
            var ordinal = 0
            profile.oneShotPrograms.forEachIndexed { index, program ->
                if (program.policy.polyphonicLaneCount() > 0) {
                    ordinals[index] = ordinal++
                }
            }
        }
    private val polyphonicProfileIndexByOrdinal = IntArray(
        polyphonicProgramOrdinalByProfileIndex.count { it >= 0 },
    ).also { reverse ->
        polyphonicProgramOrdinalByProfileIndex.forEachIndexed { profileIndex, ordinal ->
            if (ordinal >= 0) reverse[ordinal] = profileIndex
        }
    }
    private val polyphonicLaneLimits = profile.oneShotPrograms.asSequence()
        .map { it.policy.polyphonicLaneCount() }
        .filter { it > 0 }
        .toList()
        .toIntArray()
    private val fixedVoiceIndexByEffectIndex = IntArray(effectVoices.size) { -1 }.also { indices ->
        var fixedIndex = voices.size
        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            if (!effectVoices[effectIndex].spec.polyphonicTemplate) {
                indices[effectIndex] = fixedIndex++
            }
            effectIndex += 1
        }
    }
    private val fixedVoiceCount = voices.size + fixedVoiceIndexByEffectIndex.count { it >= 0 }
    private val globalVoiceArbiter = GlobalVoiceArbiter(
        fixedVoicePriorities = IntArray(fixedVoiceCount) { fixedIndex ->
            if (fixedIndex < voices.size) {
                voices[fixedIndex].spec.softwareVoicePriority
            } else {
                var effectIndex = 0
                var priority = GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY
                while (effectIndex < effectVoices.size) {
                    if (fixedVoiceIndexByEffectIndex[effectIndex] == fixedIndex) {
                        priority = effectVoices[effectIndex].spec.softwareVoicePriority
                        break
                    }
                    effectIndex += 1
                }
                priority
            }
        },
        fixedInitiallyActive = BooleanArray(fixedVoiceCount) { fixedIndex ->
            if (fixedIndex < voices.size) {
                true
            } else {
                var effectIndex = 0
                var startsActive = false
                while (effectIndex < effectVoices.size) {
                    if (fixedVoiceIndexByEffectIndex[effectIndex] == fixedIndex) {
                        startsActive = effectVoices[effectIndex].spec.startsActive
                        break
                    }
                    effectIndex += 1
                }
                startsActive
            }
        },
        programLaneLimits = polyphonicLaneLimits,
    )
    private val dynamicEffectVoiceCount = if (polyphonicLaneLimits.isEmpty()) {
        0
    } else {
        GlobalVoiceArbiter.AC_SOFTWARE_REAL_VOICE_BUDGET
    }
    private val dynamicEffectVoices = Array(dynamicEffectVoiceCount) {
        DynamicEffectVoice(outputSampleRate)
    }
    private val pendingDynamicEffectCommands = IntArray(dynamicEffectVoiceCount)
    private val pendingDynamicEffectStartOffsets = IntArray(dynamicEffectVoiceCount)
    private val pendingDynamicEffectRestorePhaseOffsets = DoubleArray(dynamicEffectVoiceCount)
    private val boundLogicalByDynamicSlot = IntArray(dynamicEffectVoiceCount) {
        GlobalVoiceArbiter.NO_LOGICAL_VOICE
    }
    private val boundSequenceByDynamicSlot = LongArray(dynamicEffectVoiceCount)
    private val authoredOneShotPrograms = Array(profile.oneShotPrograms.size) { index ->
        AuthoredOneShotProgramRuntime(
            profile.oneShotPrograms[index], polyphonicProgramOrdinalByProfileIndex[index],
        )
    }
    private val persistentLimiterProgramIndexByEffectIndex = IntArray(effectVoices.size) { -1 }
        .also { mappings ->
            authoredOneShotPrograms.forEachIndexed { programIndex, program ->
                program.registerPersistentLimiterEffectMappings(mappings, programIndex)
            }
        }
    @Volatile private var turboControllerGain = 1.0
    @Volatile private var physicalBovValue = 0.0
    @Volatile private var physicalBovDecaySeconds = TurboPhysicsRuntime.MAX_BOV_DECAY_SECONDS
    private var physicalBovRisingEdge = false
    private var physicalBovRisingEdgeBoost = 0.0
    private var oneShotRandomState =
        (profile.id.hashCode().toLong() shl 32) xor 0x6A09E667F3BCC909L

    /**
     * Builds presentation data on the caller thread. The realtime renderer updates only primitive
     * counters and voice state, so strings and dashboard lists are never formatted in [render].
     */
    fun diagnostics(): SampleRendererDiagnostics {
        val target = latestTarget
        val activeLayers = if (target.soloEffects) {
            "none (effects solo)"
        } else {
            voices.asSequence()
                .filter { it.targetGain > ACTIVE_LAYER_GAIN }
                .sortedByDescending { it.targetGain }
                .take(MAX_ACTIVE_LAYER_LABELS)
                .joinToString(",") { voice ->
                    "${voice.spec.id}@${(voice.playbackRatio * 100.0).toInt()}%/${(voice.targetGain * 100.0).toInt()}%"
                }
                .ifBlank { "none" }
        }
        val activeEffects = buildList {
            effectVoices.asSequence()
                .filter { it.softwareReal && (it.isAudible || it.targetGain > SILENCE_GAIN) }
                .mapTo(this) { it.spec.id }
            dynamicEffectVoices.asSequence()
                .filter { it.isAudible || it.targetGain > SILENCE_GAIN }
                .mapTo(this) { effectVoices[it.effectIndex].spec.id }
        }.distinct().joinToString(",").ifBlank { "none" }
        return SampleRendererDiagnostics(
            profileId = profile.id,
            loadedLoops = voices.size,
            loadedEffects = effectVoices.size,
            decodedBytes = decodedBytes,
            targetRpm = requestedRpmSnapshot.toInt(),
            renderRpm = smoothedRpm.toInt(),
            throttle = smoothedThrottle,
            activeLayers = activeLayers,
            playingSamples = audiblePlayingSamples(target),
            layerOutputMeters = buildLayerOutputMeters(target),
            framesRendered = framesRendered,
            loopWraps = loopWraps,
            peak = latestPeak,
            overRangeSamples = overRangeSamples,
            effectTriggers = effectTriggers,
            activeEffects = activeEffects,
            turboControllerGain = turboControllerGain,
            globalVoiceBudget = GlobalVoiceArbiter.AC_SOFTWARE_REAL_VOICE_BUDGET,
            globalLogicalVoices = globalVoiceArbiter.activeLogicalVoices,
            globalRealVoices = globalVoiceArbiter.activeRealVoices,
            globalVirtualVoices = globalVoiceArbiter.activeVirtualVoices,
            globalRejectedTriggers = globalVoiceArbiter.rejectedTriggers,
            globalStolenLogicalVoices = globalVoiceArbiter.stolenLogicalVoices,
        )
    }

    /** Allocation-free steady-state mix into interleaved PCM16 stereo. */
    fun render(
        target: EngineAudioControlFrame,
        output: ShortArray,
        gain: Double,
        popsAndBangsAuditionSerial: Long = 0L,
        engineStartSerial: Long = 0L,
        frameCount: Int = output.size / PROGRAM_CHANNELS,
    ) {
        require(frameCount >= 0 && output.size >= frameCount * PROGRAM_CHANNELS) {
            "Stereo render buffer does not contain the requested whole frames"
        }
        latestTarget = target
        val blockSeconds = frameCount.toDouble() / outputSampleRate
        val rpmAlpha = 1.0 - exp(-blockSeconds / (target.tuning.rpmSmoothingMs / 1_000.0))
        val throttleAlpha = 1.0 - exp(-blockSeconds / (target.tuning.throttleSmoothingMs / 1_000.0))
        val requestedRpm = target.rpm.coerceIn(profile.minimumRpm, profile.maximumRpm)
        requestedRpmSnapshot = requestedRpm
        smoothedRpm += (requestedRpm - smoothedRpm) * rpmAlpha
        smoothedDrivetrainRpm +=
            (target.drivetrainRpm.coerceAtLeast(0.0) - smoothedDrivetrainRpm) * rpmAlpha

        val anySolo = hasAudibleSolo(target.layerMix)
        val atLimiter = target.limiterActive || target.rpm >= profile.limiterRpm
        val turboPhysics = turboPhysicsRuntime
        engineGasAssistRuntime.update(
            // DriveController publishes the unsmoothed physical/player pedal in throttle. Keep
            // this source separate from the post-assist controlsGas derived below.
            rawPedal = target.throttle,
            rpm = target.rpm,
            gear = target.gear,
            shiftSerial = target.shiftSerial,
            shiftDirection = target.shiftDirection,
            elapsedSeconds = blockSeconds,
            turboPhysics = turboPhysics,
        )
        val controlsGas = engineGasAssistRuntime.latestControlsGas
        // FMOD receives post-assist Car.controls.gas, never throttle.lut or limiter-cut gEff.
        smoothedThrottle += (controlsGas - smoothedThrottle) * throttleAlpha
        turboControllerGain = if (turboPhysics != null) {
            turboPhysics.latestNormalizedBoost.also {
                physicalBovRisingEdge = turboPhysics.bovRisingEdge
                physicalBovRisingEdgeBoost = turboPhysics.bovRisingEdgeBoost
                // Parameter placements are evaluated at the 3 ms edge. Preserve that state even
                // if a second physics tick in this audio block already closed the valve.
                physicalBovValue = if (physicalBovRisingEdge) 1.0 else turboPhysics.latestBovValue
                physicalBovDecaySeconds = if (physicalBovRisingEdge) {
                    0.0
                } else {
                    turboPhysics.latestBovDecaySeconds
                }
            }
        } else {
            physicalBovValue = 0.0
            physicalBovDecaySeconds = TurboPhysicsRuntime.MAX_BOV_DECAY_SECONDS
            physicalBovRisingEdge = false
            physicalBovRisingEdgeBoost = 0.0
            legacyTurboControllerRuntime?.update(
                rpm = smoothedRpm,
                throttle = controlsGas,
                gear = target.gear,
                elapsedSeconds = blockSeconds,
            ) ?: 1.0
        }
        updateVoiceTargets(
            smoothedRpm, smoothedThrottle, turboControllerGain, target.layerMix, anySolo,
        )
        updateEffectTargetsAndTriggers(
            target, target.layerMix, anySolo, popsAndBangsAuditionSerial, engineStartSerial,
            frameCount, turboControllerGain, controlsGas,
        )
        updatePolyphonicVoiceTargets(target, target.layerMix, anySolo, controlsGas)
        val targetMaster = (gain * target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72).coerceIn(0.0, 1.5)
        val targetProfileOutputGain = profile.outputGainAt(smoothedThrottle)
        val targetEnabled = if (target.enabled) 1.0 else 0.0
        val targetContinuousProgram = if (target.soloEffects || auditionActive) 0.0 else 1.0
        updateGlobalVoiceArbitration(targetContinuousProgram)
        synchronizeDynamicPhysicalBindings(frameCount)
        val programFadeSeconds = target.tuning.programFadeMs / 1_000.0
        val masterAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val profileGainAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val enabledAlpha = 1.0 - exp(-1.0 / (outputSampleRate * (target.tuning.enabledFadeMs / 1_000.0)))
        val layerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * (target.tuning.layerFadeMs / 1_000.0)))
        val native = nativeMixer
        if (native != null) {
            var index = 0
            while (index < voices.size) {
                native.loopTargets[index] = voices[index].targetGain
                native.loopIncrements[index] = voices[index].phaseIncrement
                native.loopReal[index] = if (voices[index].softwareReal) 1 else 0
                index += 1
            }
            index = 0
            while (index < effectVoices.size) {
                native.effectTargets[index] = effectVoices[index].targetGain
                native.effectIncrements[index] = effectVoices[index].phaseIncrement
                native.effectTriggers[index] = effectVoices[index].consumeNativeCommand()
                native.effectStartOffsets[index] = effectVoices[index].consumeNativeStartOffset()
                native.effectReal[index] = if (effectVoices[index].softwareReal) 1 else 0
                index += 1
            }
            index = 0
            while (index < dynamicEffectVoiceCount) {
                native.dynamicEffectTargets[index] = dynamicEffectVoices[index].targetGain
                native.dynamicEffectIncrements[index] = dynamicEffectVoices[index].phaseIncrement
                native.dynamicEffectCommands[index] = pendingDynamicEffectCommands[index]
                native.dynamicEffectStartOffsets[index] = pendingDynamicEffectStartOffsets[index]
                native.dynamicEffectStartPhases[index] = dynamicEffectVoices[index].phase
                native.dynamicEffectStartGains[index] = dynamicEffectVoices[index].gain
                native.dynamicEffectZeroTransitionActive[index] =
                    if (dynamicEffectVoices[index].zeroTransitionActive) 1 else 0
                native.dynamicEffectZeroTransitionElapsedFrames[index] =
                    dynamicEffectVoices[index].zeroTransitionElapsedFrames
                native.dynamicEffectZeroTransitionRetainFrames[index] =
                    dynamicEffectVoices[index].zeroTransitionRetainFrames
                native.dynamicEffectZeroTransitionFadeFrames[index] =
                    dynamicEffectVoices[index].zeroTransitionFadeFrames
                native.dynamicEffectZeroTransitionStartGains[index] =
                    dynamicEffectVoices[index].zeroTransitionStartGain
                native.dynamicEffectPhaseAdvanceFrames[index] =
                    dynamicEffectVoices[index].phaseAdvanceFramesRemaining
                native.dynamicEffectRestorePhaseOffsets[index] =
                    pendingDynamicEffectRestorePhaseOffsets[index]
                pendingDynamicEffectCommands[index] = 0
                pendingDynamicEffectStartOffsets[index] = 0
                pendingDynamicEffectRestorePhaseOffsets[index] = 0.0
                index += 1
            }
            native.render(
                output, frameCount, targetMaster, targetProfileOutputGain, targetEnabled,
                targetContinuousProgram, masterAlpha, profileGainAlpha, enabledAlpha, layerAlpha,
            )
            index = 0
            while (index < voices.size) {
                voices[index].gain = native.loopGains[index]
                index += 1
            }
            index = 0
            while (index < effectVoices.size) {
                effectVoices[index].gain = native.effectGains[index]
                effectVoices[index].setNativeActive(native.effectActive[index] != 0)
                index += 1
            }
            index = 0
            while (index < dynamicEffectVoiceCount) {
                dynamicEffectVoices[index].gain = native.dynamicEffectGains[index]
                dynamicEffectVoices[index].setNativeActive(native.dynamicEffectActive[index] != 0)
                index += 1
            }
            advanceGlobalDynamicTimelines(frameCount, layerAlpha)
            framesRendered = native.framesRendered
            loopWraps = native.loopWraps
            overRangeSamples = native.overRangeSamples
            latestPeak = native.peak
            masterGain = targetMaster
            profileOutputGain = targetProfileOutputGain
            enabledGain = targetEnabled
            continuousProgramGain = targetContinuousProgram
            return
        }
        var blockPeak = 0.0

        var frameIndex = 0
        while (frameIndex < frameCount) {
            continuousProgramGain += (targetContinuousProgram - continuousProgramGain) * masterAlpha
            var loopLeft = 0.0
            var loopRight = 0.0
            var voiceIndex = 0
            while (voiceIndex < voices.size) {
                val voice = voices[voiceIndex]
                voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                if (voice.softwareReal &&
                    (voice.gain > SILENCE_GAIN || voice.targetGain > SILENCE_GAIN)
                ) {
                    loopLeft += voice.readCubic(0) * voice.gain
                    loopRight += voice.readCubic(1) * voice.gain
                }
                // Authored timelines continue while inaudible, avoiding restarts as curves open.
                if (voice.advance()) loopWraps += 1
                voiceIndex += 1
            }

            var effectLeft = 0.0
            var effectRight = 0.0
            var effectIndex = 0
            while (effectIndex < effectVoices.size) {
                val voice = effectVoices[effectIndex]
                if (voice.beginFrame()) {
                    voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                    if (voice.softwareReal && voice.isAudible) {
                        effectLeft += voice.readCubic(0) * voice.gain
                        effectRight += voice.readCubic(1) * voice.gain
                    }
                    if (voice.advance()) loopWraps += 1
                }
                effectIndex += 1
            }
            effectIndex = 0
            while (effectIndex < dynamicEffectVoices.size) {
                val voice = dynamicEffectVoices[effectIndex]
                if (voice.beginFrame()) {
                    voice.updateGain(layerAlpha)
                    if (voice.isAudible) {
                        effectLeft += voice.readCubic(0) * voice.gain
                        effectRight += voice.readCubic(1) * voice.gain
                    }
                    voice.advance()
                }
                effectIndex += 1
            }

            masterGain += (targetMaster - masterGain) * masterAlpha
            profileOutputGain += (targetProfileOutputGain - profileOutputGain) * profileGainAlpha
            enabledGain += (targetEnabled - enabledGain) * enabledAlpha
            val commonGain = SAMPLE_HEADROOM * masterGain * profileOutputGain * enabledGain
            val preLimitedLeft = (loopLeft * continuousProgramGain + effectLeft) * commonGain
            val preLimitedRight = (loopRight * continuousProgramGain + effectRight) * commonGain
            if (abs(preLimitedLeft) > 1.0) overRangeSamples += 1
            if (abs(preLimitedRight) > 1.0) overRangeSamples += 1
            val limitedLeft = safetyLimit(preLimitedLeft)
            val limitedRight = safetyLimit(preLimitedRight)
            blockPeak = max(blockPeak, max(abs(limitedLeft), abs(limitedRight)))
            val outputIndex = frameIndex * PROGRAM_CHANNELS
            output[outputIndex] = toPcm16(limitedLeft)
            output[outputIndex + 1] = toPcm16(limitedRight)
            frameIndex += 1
        }

        framesRendered += frameCount
        latestPeak = blockPeak
        advanceGlobalDynamicTimelines(frameCount, layerAlpha)
    }

    /** Loop and effect assets audibly contributing to the mixed output right now. */
    private fun audiblePlayingSamples(target: EngineAudioControlFrame): List<PlayingSampleLabel> = buildList {
        val anySolo = hasAudibleSolo(target.layerMix)
        if (isProgramAudible(target) && !target.soloEffects && continuousProgramGain > SILENCE_GAIN) {
            voices.asSequence()
                .filter { voice ->
                    voice.gain > SILENCE_GAIN &&
                        voice.softwareReal &&
                        (!anySolo || target.layerMix[voice.spec.id]?.solo == true)
                }
                .sortedByDescending { it.gain }
                .map { PlayingSampleLabel(it.spec.role.playingRoleLabel(), it.spec.assetName) }
                .forEach(::add)
        }
        if (isProgramAudible(target)) {
            effectVoices.asSequence()
                .filter { voice ->
                    voice.softwareReal && voice.isAudible && voice.gain > SILENCE_GAIN &&
                        (!anySolo || target.layerMix[voice.spec.id]?.solo == true)
                }
                .sortedByDescending { it.gain }
                .map { PlayingSampleLabel(it.spec.playingRoleLabel(), it.spec.assetName) }
                .forEach(::add)
            dynamicEffectVoices.asSequence()
                .filter { it.isAudible && it.effectIndex >= 0 }
                .map { effectVoices[it.effectIndex].spec }
                .distinctBy(SampleEffectSpec::id)
                .map { PlayingSampleLabel(it.playingRoleLabel(), it.assetName) }
                .forEach(::add)
        }
    }

    private fun isProgramAudible(target: EngineAudioControlFrame): Boolean =
        target.enabled && enabledGain > SILENCE_GAIN && masterGain > SILENCE_GAIN

    private fun updateEffectTargetsAndTriggers(
        target: EngineAudioControlFrame,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
        popsAndBangsAuditionSerial: Long,
        engineStartSerial: Long,
        renderedFrameCount: Int,
        turboGain: Double,
        physicalControlsGas: Double,
    ) {
        val mask = target.enabledEffectMask
        val normalizedRpm = ((smoothedRpm - profile.idleRpm) / (profile.limiterRpm - profile.idleRpm))
            .coerceIn(0.0, 1.0)

        if (auditionActive) {
            var activeAuditionVoice = false
            var voiceIndex = 0
            while (voiceIndex < effectVoices.size) {
                val voice = effectVoices[voiceIndex]
                if (voice.spec.auditionable && voice.isOneShotActive) activeAuditionVoice = true
                voiceIndex += 1
            }
            if (!activeAuditionVoice) auditionActive = false
        }

        var authoredProgramIndex = 0
        while (authoredProgramIndex < authoredOneShotPrograms.size) {
            authoredOneShotPrograms[authoredProgramIndex].beginBlock(renderedFrameCount)
            authoredProgramIndex += 1
        }

        if (!hasLastShiftSerial) {
            lastShiftSerial = target.shiftSerial
            hasLastShiftSerial = true
        } else if (target.shiftSerial != lastShiftSerial) {
            lastShiftSerial = target.shiftSerial
            val trigger = if (target.shiftDirection > 0) {
                SampleEffectTrigger.SHIFT_UP
            } else {
                SampleEffectTrigger.SHIFT_DOWN
            }
            if (authoredOneShotPrograms.isNotEmpty()) {
                triggerAuthoredPrograms(
                    trigger, false, mask, smoothedRpm, smoothedThrottle, smoothedDrivetrainRpm,
                    target.shiftDirection.toDouble(), turboGain, layerMix, anySolo, false,
                )
            } else {
                triggerMatchingOneShots(
                    trigger, false, mask, smoothedRpm, smoothedThrottle, layerMix, anySolo, false,
                )
            }
        }

        if (!hasLastEngineStartSerial) {
            lastEngineStartSerial = engineStartSerial
            hasLastEngineStartSerial = true
        } else if (engineStartSerial != lastEngineStartSerial) {
            lastEngineStartSerial = engineStartSerial
            if (authoredOneShotPrograms.isNotEmpty()) {
                triggerAuthoredPrograms(
                    SampleEffectTrigger.ENGINE_START, false, mask, smoothedRpm, smoothedThrottle,
                    smoothedDrivetrainRpm, 0.0, turboGain, layerMix, anySolo, false,
                )
            } else {
                triggerMatchingOneShots(
                    SampleEffectTrigger.ENGINE_START, false, mask, smoothedRpm, smoothedThrottle,
                    layerMix, anySolo, false,
                )
            }
        }

        if (!hasLastAuditionSerial) {
            lastAuditionSerial = popsAndBangsAuditionSerial
            hasLastAuditionSerial = true
        } else if (popsAndBangsAuditionSerial != lastAuditionSerial) {
            lastAuditionSerial = popsAndBangsAuditionSerial
            auditionActive = if (authoredOneShotPrograms.isNotEmpty()) {
                triggerAuthoredPrograms(
                    trigger = null,
                    auditionOnly = true,
                    mask = mask or profile.auditionEffectMask,
                    rpm = auditionTriggerRpm,
                    throttle = 0.0,
                    drivetrainRpm = smoothedDrivetrainRpm,
                    shiftState = 0.0,
                    turboGain = turboGain,
                    layerMix = layerMix,
                    anySolo = false,
                    bypassMixControls = true,
                ) > 0
            } else {
                triggerMatchingOneShots(
                    trigger = null,
                    auditionOnly = true,
                    mask = mask or profile.auditionEffectMask,
                    rpm = auditionTriggerRpm,
                    throttle = 0.0,
                    layerMix = layerMix,
                    anySolo = false,
                    bypassMixControls = true,
                ) > 0
            }
        }

        val atLimiter = target.limiterActive || target.rpm >= profile.limiterRpm
        val limiterPeriodFrames =
            (outputSampleRate / profile.limiterHz.coerceIn(1.0, 200.0)).toInt().coerceAtLeast(1)
        var limiterPulseOffsetFrames = NO_FRAME_OFFSET
        if (atLimiter && renderedFrameCount > 0) {
            var nextPulse = if (limiterActive) limiterFramesUntilPulse else 0
            if (nextPulse < renderedFrameCount) {
                limiterPulseOffsetFrames = nextPulse
                do {
                    nextPulse += limiterPeriodFrames
                } while (nextPulse < renderedFrameCount)
            }
            limiterFramesUntilPulse = (nextPulse - renderedFrameCount).coerceAtLeast(0)
        } else {
            limiterFramesUntilPulse = 0
        }
        if (authoredOneShotPrograms.isNotEmpty()) {
            authoredProgramIndex = 0
            while (authoredProgramIndex < authoredOneShotPrograms.size) {
                val program = authoredOneShotPrograms[authoredProgramIndex]
                effectTriggers += program.updateAutomaticTriggers(
                    atLimiter = atLimiter,
                    limiterPulseOffsetFrames = limiterPulseOffsetFrames,
                    driveAudioActive = target.enabled,
                    bovRisingEdge = physicalBovRisingEdge,
                    bovRisingEdgeBoost = physicalBovRisingEdgeBoost,
                    mask = mask,
                    rpm = when (program.trigger) {
                        // Strict engine-region and limiter policies use authored exact bounds.
                        // Exponential presentation smoothing approaches a threshold without
                        // necessarily reaching it and must not consume a valid source trigger.
                        SampleEffectTrigger.ENGINE_EVENT,
                        SampleEffectTrigger.LIMITER_EVENT -> requestedRpmSnapshot
                        else -> smoothedRpm
                    },
                    throttle = physicalControlsGas,
                    drivetrainRpm = smoothedDrivetrainRpm,
                    turboGain = turboGain,
                    layerMix = layerMix,
                    anySolo = anySolo,
                )
                authoredProgramIndex += 1
            }
        } else {
            if (physicalControlsGas >= THROTTLE_LIFT_ARM_LEVEL) throttleLiftArmed = true
            if (throttleLiftArmed && physicalControlsGas <= THROTTLE_LIFT_FIRE_LEVEL) {
                throttleLiftArmed = false
                triggerMatchingOneShots(
                    SampleEffectTrigger.THROTTLE_LIFT, false, mask, smoothedRpm,
                    smoothedThrottle, layerMix, anySolo, false,
                )
                // Legacy schema-v1 packs have no authored boost-arm policy. Preserve their
                // former lift behavior while schema-v2 packs use the separate BOV program.
                triggerMatchingOneShots(
                    SampleEffectTrigger.BOV_LIFT, false, mask, smoothedRpm,
                    smoothedThrottle, layerMix, anySolo, false,
                )
            }
            if (atLimiter) {
                if (limiterPulseOffsetFrames != NO_FRAME_OFFSET) {
                    triggerMatchingOneShots(
                        SampleEffectTrigger.LIMITER, false, mask, smoothedRpm,
                        smoothedThrottle, layerMix, anySolo, false,
                        startOffsetFrames = limiterPulseOffsetFrames,
                    )
                }
            }
        }
        limiterActive = atLimiter

        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            val auditioningVoice = auditionActive && voice.spec.auditionable
            val curveRpm = when {
                auditioningVoice -> auditionTriggerRpm
                voice.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP -> smoothedDrivetrainRpm
                voice.isOneShotActive -> voice.triggerRpm
                else -> smoothedRpm
            }
            val curveThrottle = when {
                auditioningVoice -> 0.0
                voice.isOneShotActive -> voice.triggerThrottle
                else -> smoothedThrottle
            }
            val effectTurboGain = if (voice.isOneShotActive) voice.triggerTurboGain else turboGain
            val curvedGain = voice.baseGain *
                (voice.spec.rpmAmplitudeCurve?.valueAt(curveRpm) ?: 1.0) *
                (voice.spec.throttleAmplitudeCurve?.valueAt(curveThrottle) ?: 1.0) *
                if (voice.spec.turboAudioResponse == TurboAudioResponse.BOOST) effectTurboGain else 1.0
            val authoredGain = when (voice.spec.trigger) {
                SampleEffectTrigger.CONTINUOUS_LOOP -> {
                    if (auditionActive || mask and voice.spec.control.bit == 0L) 0.0 else curvedGain
                }
                SampleEffectTrigger.TRANSMISSION_LOOP -> {
                    if (auditionActive || target.soloEffects || mask and voice.spec.control.bit == 0L) {
                        0.0
                    } else {
                        val hasAuthoredResponse = voice.spec.rpmAmplitudeCurve != null ||
                            voice.spec.throttleAmplitudeCurve != null
                        curvedGain * if (hasAuthoredResponse) {
                            1.0
                        } else {
                            (0.12 + normalizedRpm * 0.88) * (0.55 + smoothedThrottle * 0.45)
                        }
                    }
                }
                SampleEffectTrigger.LIMITER_EVENT -> {
                    val programIndex = persistentLimiterProgramIndexByEffectIndex[effectIndex]
                    if (voice.spec.looping && voice.isActive && programIndex >= 0) {
                        curvedGain * authoredOneShotPrograms[programIndex].persistentLimiterSourceGain
                    } else {
                        0.0
                    }
                }
                else -> if (
                    voice.isOneShotActive && (!auditionActive || voice.spec.auditionable)
                ) curvedGain else 0.0
            }
            voice.targetGain = if (auditioningVoice) {
                authoredGain
            } else {
                applyLayerMix(voice.spec.id, authoredGain, layerMix, anySolo)
            }
            if (voice.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP) {
                voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate *
                    (voice.spec.authoredPlaybackRatio(smoothedDrivetrainRpm)
                        ?: (0.55 + normalizedRpm * 1.25))
            }
            effectIndex += 1
        }
    }

    private fun triggerPolyphonicVoice(
        programOrdinal: Int,
        effectIndex: Int,
        startOffsetFrames: Int,
        zeroGainVirtualization: ZeroGainVirtualizationSpec,
    ): Boolean {
        val source = effectVoices[effectIndex]
        return globalVoiceArbiter.triggerDynamic(
            programIndex = programOrdinal,
            trackIndex = effectIndex,
            priority = source.spec.softwareVoicePriority,
            initialAudibility = source.baseGain,
            frameCount = source.data.frameCount,
            startDelayFrames = startOffsetFrames,
            zeroGainVirtualization = zeroGainVirtualization,
        ) != GlobalVoiceArbiter.REJECTED
    }

    private fun updatePolyphonicVoiceTargets(
        target: EngineAudioControlFrame,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
        controlsGas: Double,
    ) {
        var logical = globalVoiceArbiter.firstDynamicHandle
        while (logical < globalVoiceArbiter.recordCapacity) {
            if (globalVoiceArbiter.isDynamicActive(logical)) {
                val effectIndex = globalVoiceArbiter.dynamicTrack(logical)
                val source = effectVoices[effectIndex]
                val spec = source.spec
                if (globalVoiceArbiter.isDynamicRetiring(logical)) {
                    globalVoiceArbiter.updateDynamicMix(
                        logical = logical,
                        targetGain = 0.0,
                        increment = globalVoiceArbiter.dynamicIncrement(logical),
                    )
                } else if (spec.coreEngineTransient) {
                    val liveRpm = requestedRpmSnapshot
                    val authoredGain = source.baseGain *
                        (spec.rpmAmplitudeCurve?.valueAt(liveRpm) ?: 1.0) *
                        (spec.throttleAmplitudeCurve?.valueAt(controlsGas) ?: 1.0)
                    val targetGain = if (target.soloEffects || auditionActive) {
                        0.0
                    } else {
                        applyLayerMix(spec.id, authoredGain, layerMix, anySolo)
                    }
                    globalVoiceArbiter.updateDynamicMix(
                        logical = logical,
                        targetGain = targetGain,
                        increment = source.data.sampleRate.toDouble() / outputSampleRate *
                            spec.authoredEngineTransientPlaybackRatio(liveRpm),
                        authoredExactZero = authoredGain == 0.0,
                    )
                } else if (spec.trigger == SampleEffectTrigger.TURBO_EVENT) {
                    val programProfileIndex = polyphonicProfileIndexByOrdinal[
                        globalVoiceArbiter.dynamicProgram(logical)
                    ]
                    val program = authoredOneShotPrograms[programProfileIndex]
                    val authoredGain = source.baseGain *
                        (spec.rpmAmplitudeCurve?.valueAt(requestedRpmSnapshot) ?: 1.0) *
                        (spec.throttleAmplitudeCurve?.valueAt(controlsGas) ?: 1.0) *
                        program.turboControlGain(effectIndex, turboControllerGain)
                    val targetGain = if (auditionActive) {
                        0.0
                    } else {
                        applyLayerMix(spec.id, authoredGain, layerMix, anySolo)
                    }
                    globalVoiceArbiter.updateDynamicMix(
                        logical = logical,
                        targetGain = targetGain,
                        increment = source.data.sampleRate.toDouble() / outputSampleRate *
                            program.turboPlaybackRate(effectIndex, turboControllerGain),
                    )
                } else {
                    val programProfileIndex = polyphonicProfileIndexByOrdinal[
                        globalVoiceArbiter.dynamicProgram(logical)
                    ]
                    val program = authoredOneShotPrograms[programProfileIndex]
                    val authoredGain = source.baseGain * program.persistentLimiterSourceGain
                    val targetGain = if (auditionActive) 0.0 else {
                        applyLayerMix(spec.id, authoredGain, layerMix, anySolo)
                    }
                    globalVoiceArbiter.updateDynamicMix(
                        logical = logical,
                        targetGain = targetGain,
                        increment = source.data.sampleRate.toDouble() / outputSampleRate,
                    )
                }
            }
            logical += 1
        }
    }

    private fun updateGlobalVoiceArbitration(targetContinuousProgram: Double) {
        var loopIndex = 0
        while (loopIndex < voices.size) {
            val voice = voices[loopIndex]
            globalVoiceArbiter.setFixedAudibility(
                loopIndex,
                max(voice.gain, voice.targetGain) *
                    max(continuousProgramGain, targetContinuousProgram),
            )
            loopIndex += 1
        }

        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val fixedIndex = fixedVoiceIndexByEffectIndex[effectIndex]
            if (fixedIndex >= 0) {
                val voice = effectVoices[effectIndex]
                if (voice.consumeArbiterActivationRequest()) {
                    globalVoiceArbiter.activateFixed(fixedIndex)
                } else if (!voice.isActive) {
                    globalVoiceArbiter.deactivateFixed(fixedIndex)
                }
                globalVoiceArbiter.setFixedAudibility(
                    fixedIndex,
                    if (voice.isActive) max(voice.gain, voice.targetGain) else 0.0,
                )
            }
            effectIndex += 1
        }

        // A logical-cap admission can stop an older fixed event source. Do not let its native or
        // Kotlin source remain active and silently re-admit itself on the next render buffer.
        effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val fixedIndex = fixedVoiceIndexByEffectIndex[effectIndex]
            if (fixedIndex >= 0 && effectVoices[effectIndex].isActive &&
                !globalVoiceArbiter.isFixedActive(fixedIndex)
            ) {
                effectVoices[effectIndex].stopLogical()
            }
            effectIndex += 1
        }

        globalVoiceArbiter.rebalance()
        loopIndex = 0
        while (loopIndex < voices.size) {
            voices[loopIndex].softwareReal = globalVoiceArbiter.isFixedReal(loopIndex)
            loopIndex += 1
        }
        effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val fixedIndex = fixedVoiceIndexByEffectIndex[effectIndex]
            effectVoices[effectIndex].softwareReal =
                fixedIndex >= 0 && globalVoiceArbiter.isFixedReal(fixedIndex)
            effectIndex += 1
        }
    }

    private fun synchronizeDynamicPhysicalBindings(renderedFrames: Int) {
        var slot = 0
        while (slot < dynamicEffectVoiceCount) {
            val expectedLogical = globalVoiceArbiter.logicalForRealSlot(slot)
            val expectedSequence = if (expectedLogical >= 0) {
                globalVoiceArbiter.sequence(expectedLogical)
            } else {
                0L
            }
            val bindingChanged = boundLogicalByDynamicSlot[slot] != expectedLogical ||
                boundSequenceByDynamicSlot[slot] != expectedSequence
            if (bindingChanged) {
                if (expectedLogical >= 0) {
                    val effectIndex = globalVoiceArbiter.dynamicTrack(expectedLogical)
                    val source = effectVoices[effectIndex]
                    dynamicEffectVoices[slot].start(
                        effectIndex = effectIndex,
                        spec = source.spec,
                        data = source.data,
                        retainedPhase = globalVoiceArbiter.dynamicPhase(expectedLogical),
                        retainedGain = globalVoiceArbiter.dynamicGain(expectedLogical),
                        startDelayFrames = globalVoiceArbiter.dynamicStartDelayFrames(expectedLogical),
                    )
                    pendingDynamicEffectCommands[slot] = effectIndex + 1
                    pendingDynamicEffectStartOffsets[slot] =
                        globalVoiceArbiter.dynamicStartDelayFrames(expectedLogical)
                } else {
                    dynamicEffectVoices[slot].stop()
                    pendingDynamicEffectCommands[slot] = -1
                    pendingDynamicEffectStartOffsets[slot] = 0
                }
                boundLogicalByDynamicSlot[slot] = expectedLogical
                boundSequenceByDynamicSlot[slot] = expectedSequence
            }
            if (expectedLogical >= 0) {
                val restorePhaseOffset =
                    globalVoiceArbiter.consumeDynamicPhysicalRestorePhaseOffset(expectedLogical)
                if (!bindingChanged && restorePhaseOffset != 0.0) {
                    dynamicEffectVoices[slot].applyRestorePhaseOffset(restorePhaseOffset)
                    pendingDynamicEffectRestorePhaseOffsets[slot] = restorePhaseOffset
                } else {
                    // A newly bound voice starts from the already-corrected logical phase. Consuming
                    // but not applying the pending value prevents a double correction on promotion.
                    pendingDynamicEffectRestorePhaseOffsets[slot] = 0.0
                }
                dynamicEffectVoices[slot].targetGain =
                    globalVoiceArbiter.dynamicTargetGain(expectedLogical)
                dynamicEffectVoices[slot].phaseIncrement =
                    globalVoiceArbiter.dynamicIncrement(expectedLogical)
                dynamicEffectVoices[slot].configureZeroGainLifecycle(
                    transitionActive = globalVoiceArbiter.dynamicExactZeroGated(expectedLogical),
                    transitionElapsedFrames =
                        globalVoiceArbiter.dynamicZeroTransitionElapsedFrames(expectedLogical),
                    transitionRetainFrames =
                        globalVoiceArbiter.dynamicZeroTransitionRetainFrames(expectedLogical),
                    transitionFadeFrames =
                        globalVoiceArbiter.dynamicZeroTransitionFadeFrames(expectedLogical),
                    transitionStartGain =
                        globalVoiceArbiter.dynamicZeroTransitionStartGain(expectedLogical),
                    phaseAdvanceFrames = globalVoiceArbiter.dynamicPhaseAdvanceFrames(
                        expectedLogical, renderedFrames,
                    ),
                )
            } else {
                pendingDynamicEffectRestorePhaseOffsets[slot] = 0.0
                dynamicEffectVoices[slot].targetGain = 0.0
                dynamicEffectVoices[slot].configureZeroGainLifecycle(
                    transitionActive = false,
                    transitionElapsedFrames = 0,
                    transitionRetainFrames = 0,
                    transitionFadeFrames = 0,
                    transitionStartGain = 0.0,
                    phaseAdvanceFrames = 0,
                )
            }
            slot += 1
        }
    }

    private fun advanceGlobalDynamicTimelines(renderedFrames: Int, layerAlpha: Double) {
        val gainRetention = (1.0 - layerAlpha).pow(renderedFrames)
        globalVoiceArbiter.advanceDynamicVoices(renderedFrames, gainRetention)
    }

    private fun triggerAuthoredPrograms(
        trigger: SampleEffectTrigger?,
        auditionOnly: Boolean,
        mask: Long,
        rpm: Double,
        throttle: Double,
        drivetrainRpm: Double,
        shiftState: Double,
        turboGain: Double,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
        bypassMixControls: Boolean,
    ): Int {
        var total = 0
        var index = 0
        while (index < authoredOneShotPrograms.size) {
            val program = authoredOneShotPrograms[index]
            total += if (auditionOnly) {
                program.triggerAudition(
                    mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                    layerMix, anySolo, bypassMixControls,
                )
            } else if (program.trigger == trigger) {
                program.triggerPolicyEvent(
                    mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                    layerMix, anySolo,
                )
            } else {
                0
            }
            index += 1
        }
        effectTriggers += total
        return total
    }

    private fun triggerMatchingOneShots(
        trigger: SampleEffectTrigger?,
        auditionOnly: Boolean,
        mask: Long,
        rpm: Double,
        throttle: Double,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
        bypassMixControls: Boolean,
        startOffsetFrames: Int = 0,
    ): Int {
        // FMOD random instruments choose one recording from a variant group. A sound family can
        // also have simultaneous independent groups (for example BOV + exhaust overrun), so
        // select one eligible voice per effect-control bit rather than stacking every capture.
        var candidateControlBits = 0L
        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            if (voice.isEligibleOneShot(
                    trigger, auditionOnly, mask, rpm, throttle, layerMix, anySolo, bypassMixControls,
                )
            ) {
                candidateControlBits = candidateControlBits or voice.spec.control.bit
            }
            effectIndex += 1
        }

        var triggeredCount = 0
        while (candidateControlBits != 0L) {
            val controlBit = java.lang.Long.lowestOneBit(candidateControlBits)
            candidateControlBits = candidateControlBits xor controlBit
            var chosen: EffectVoice? = null
            var eligibleCount = 0
            effectIndex = 0
            while (effectIndex < effectVoices.size) {
                val voice = effectVoices[effectIndex]
                if (
                    voice.spec.control.bit == controlBit &&
                    voice.isEligibleOneShot(
                        trigger, auditionOnly, mask, rpm, throttle, layerMix, anySolo, bypassMixControls,
                    )
                ) {
                    eligibleCount += 1
                    if (nextOneShotVariantIndex(eligibleCount) == 0) chosen = voice
                }
                effectIndex += 1
            }
            chosen?.trigger(rpm, throttle, turboControllerGain, startOffsetFrames)
            if (chosen != null) {
                effectTriggers += 1
                triggeredCount += 1
            }
        }
        return triggeredCount
    }

    /**
     * Precompiled FMOD MultiInstrument tree. Construction may allocate on the decoder worker;
     * trigger traversal on the audio thread uses only primitive arrays and existing objects.
     */
    private inner class AuthoredOneShotProgramRuntime(
        private val spec: OneShotProgramSpec,
        private val polyphonicProgramOrdinal: Int,
    ) {
        val trigger: SampleEffectTrigger get() = spec.trigger

        private val nodes = spec.nodes.toTypedArray()
        private val nodeIndices = spec.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        private val roots = IntArray(spec.rootNodeIds.size) { rootIndex ->
            requireNotNull(nodeIndices[spec.rootNodeIds[rootIndex]])
        }
        private val memberIndices = Array(nodes.size) { IntArray(0) }
        private val memberWeights = Array(nodes.size) { DoubleArray(0) }
        private val effectIndices = IntArray(nodes.size) { -1 }
        private val nodeIndexByEffectIndex = IntArray(effectVoices.size) { -1 }
        private val randomStates = LongArray(nodes.size) { index ->
            var seed = (profile.id.hashCode().toLong() shl 32) xor
                spec.id.hashCode().toLong() xor nodes[index].id.hashCode().toLong() xor
                0x3C6EF372FE94F82BL
            if (seed == 0L) seed = 0x6A09E667F3BCC909L
            seed
        }
        private val lastSelections = IntArray(nodes.size) { -1 }
        private val sequentialCursors = IntArray(nodes.size)
        private val engineLeafHasTriggered = BooleanArray(nodes.size)
        private var engineParameterRegionReentry = false
        private var throttleArmed = false
        private var backfirePeakPedal = spec.policy.initialPeakPedal ?: 0.0
        private var backfireArmLevel = spec.policy.initialArmPedal ?: 0.0
        private var backfireFireBelow = spec.policy.initialFirePedal ?: 0.0
        private var backfireFuelSeconds = 0.0
        private var physicsStepAccumulatorSeconds = 0.0
        private var limiterWasActive = false
        private var limiterFramesRemaining = 0
        private var persistentLimiterDecaySeconds = 10.0f
        private var persistentLimiterEventActive = false
        private var persistentLimiterPlacementInside = false
        var persistentLimiterSourceGain: Double = 0.0
            private set
        private var currentBlockFrames = 0
        private var turboEventOwnerActive = false
        private var turboPreviousBoost = 0.0
        private var turboTimelineFrame = 0L
        private var turboNextTimelineTriggerFrame = 0L
        private var cooldownFramesRemaining = 0
        private val engineEventState = spec.policy.engineEvent?.let { enginePolicy ->
            EngineTransientEventState(enginePolicy.requiresEventStartInside)
        }

        init {
            var nodeIndex = 0
            while (nodeIndex < nodes.size) {
                when (val node = nodes[nodeIndex]) {
                    is OneShotGroupNodeSpec -> {
                        val sorted = node.members.sortedBy(OneShotGroupMemberSpec::order)
                        memberIndices[nodeIndex] = IntArray(sorted.size) { memberIndex ->
                            requireNotNull(nodeIndices[sorted[memberIndex].nodeId])
                        }
                        memberWeights[nodeIndex] = DoubleArray(sorted.size) { memberIndex ->
                            sorted[memberIndex].weight
                        }
                    }
                    is OneShotTrackNodeSpec -> {
                        effectIndices[nodeIndex] = profile.effects.indexOfFirst { it.id == node.effectId }
                            .also { require(it >= 0) { "Missing effect ${node.effectId}" } }
                        nodeIndexByEffectIndex[effectIndices[nodeIndex]] = nodeIndex
                    }
                    is OneShotSilentNodeSpec -> Unit
                }
                nodeIndex += 1
            }
        }

        fun beginBlock(renderedFrames: Int) {
            currentBlockFrames = renderedFrames
            cooldownFramesRemaining = (cooldownFramesRemaining - renderedFrames).coerceAtLeast(0)
            physicsStepAccumulatorSeconds += renderedFrames.toDouble() / outputSampleRate
            if (spec.policy.kind == OneShotPolicyKind.PERSISTENT_LIMITER_EVENT) {
                persistentLimiterDecaySeconds =
                    (persistentLimiterDecaySeconds + renderedFrames.toFloat() / outputSampleRate.toFloat())
            }
        }

        fun updateAutomaticTriggers(
            atLimiter: Boolean,
            limiterPulseOffsetFrames: Int,
            driveAudioActive: Boolean,
            bovRisingEdge: Boolean,
            bovRisingEdgeBoost: Double,
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
        ): Int = when (spec.policy.kind) {
            OneShotPolicyKind.AC_BACKFIRE -> {
                var count = 0
                while (physicsStepAccumulatorSeconds >= AC_PHYSICS_STEP_SECONDS) {
                    physicsStepAccumulatorSeconds -= AC_PHYSICS_STEP_SECONDS
                    if (throttle > backfirePeakPedal && throttle != 0.0) {
                        backfirePeakPedal = throttle
                        backfireArmLevel = requireNotNull(spec.policy.armPedal) * throttle
                        backfireFireBelow = requireNotNull(spec.policy.firePedal) * throttle
                    }
                    if (throttle > backfireArmLevel) throttleArmed = true
                    val fire = throttleArmed &&
                        throttle > 0.0 && throttle < backfireFireBelow &&
                        rpm > spec.policy.minimumRpm &&
                        rpm <= requireNotNull(spec.policy.maximumRpm) &&
                        backfireFuelSeconds > spec.policy.minimumArmSeconds
                    if (fire) {
                        // AC clears the detector first, then asks the cabin event to start. A
                        // still-playing backfire refuses that start; the trigger is consumed and
                        // is neither queued nor allowed to rewind the active transient.
                        throttleArmed = false
                        if (!hasActiveProgramEffect()) {
                            count += triggerPolicyEvent(
                                mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                                layerMix, anySolo,
                            )
                        }
                    } else if (throttleArmed) {
                        backfireFuelSeconds = (backfireFuelSeconds + AC_PHYSICS_STEP_SECONDS)
                            .coerceAtMost(MAX_BACKFIRE_FUEL_SECONDS)
                    }
                }
                count
            }
            OneShotPolicyKind.BOV_LIFT -> {
                if (bovRisingEdge) {
                    triggerPolicyEvent(
                        mask, rpm, throttle, drivetrainRpm, 0.0, bovRisingEdgeBoost,
                        layerMix, anySolo,
                    )
                } else {
                    0
                }
            }
            OneShotPolicyKind.LIMITER -> {
                if (!atLimiter) {
                    limiterWasActive = false
                    limiterFramesRemaining = 0
                    0
                } else if (!limiterWasActive || limiterFramesRemaining < currentBlockFrames) {
                    limiterWasActive = true
                    val startOffsetFrames = if (limiterFramesRemaining <= 0) {
                        0
                    } else {
                        limiterFramesRemaining
                    }
                    val count = triggerPolicyEvent(
                        mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                        layerMix, anySolo, startOffsetFrames,
                    )
                    val periodFrames = (outputSampleRate /
                        requireNotNull(spec.policy.periodHz).coerceIn(1.0, 200.0))
                        .toInt()
                        .coerceAtLeast(1)
                    var nextOffset = startOffsetFrames + periodFrames
                    while (nextOffset < currentBlockFrames) nextOffset += periodFrames
                    limiterFramesRemaining = nextOffset - currentBlockFrames
                    count
                } else {
                    limiterFramesRemaining -= currentBlockFrames
                    0
                }
            }
            OneShotPolicyKind.PERSISTENT_LIMITER_EVENT -> {
                if (limiterPulseOffsetFrames != NO_FRAME_OFFSET) {
                    // The AC host updates this timer at its physics tick then applies the cut.
                    // Preserve that exact reset semantics; the source command itself still carries
                    // the authored sub-buffer start offset into the mixer.
                    persistentLimiterDecaySeconds = 0.0f
                }
                val limiterEnabled = mask and SampleEffectControls.limiter.bit != 0L
                val desiredActive = driveAudioActive && limiterEnabled &&
                    persistentLimiterDecaySeconds <= 10.0f
                if (!desiredActive) {
                    if (persistentLimiterEventActive) {
                        stopPersistentLimiterLoopSources()
                        if (polyphonicProgramOrdinal >= 0) {
                            globalVoiceArbiter.retireDynamicVoicesForProgram(
                                polyphonicProgramOrdinal,
                            )
                        }
                    }
                    persistentLimiterEventActive = false
                    persistentLimiterPlacementInside = false
                    persistentLimiterSourceGain = 0.0
                    0
                } else {
                    val limiterPolicy = requireNotNull(spec.policy.limiterEvent)
                    val eventJustStarted = !persistentLimiterEventActive
                    persistentLimiterEventActive = true
                    persistentLimiterSourceGain = limiterPolicy.decayGainCurve.valueAt(
                        persistentLimiterDecaySeconds.coerceAtMost(1.0f).toDouble(),
                    )
                    when (limiterPolicy.mode) {
                        PersistentLimiterProgramMode.TIMELINE_PERIOD_LOOP -> {
                            if (eventJustStarted) {
                                triggerPolicyEvent(
                                    mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                                    layerMix, anySolo,
                                    startOffsetFrames = limiterPulseOffsetFrames.coerceAtLeast(0),
                                )
                            } else {
                                0
                            }
                        }
                        PersistentLimiterProgramMode.DECAY_REGION_ONE_SHOT,
                        PersistentLimiterProgramMode.DECAY_REGION_LOOP -> {
                            val inside = requireNotNull(limiterPolicy.decayPlacement)
                                .contains(persistentLimiterDecaySeconds)
                            val pulseEntered = limiterPulseOffsetFrames != NO_FRAME_OFFSET &&
                                !persistentLimiterPlacementInside &&
                                limiterPolicy.decayPlacement.contains(0.0f)
                            val entered = (inside && (!persistentLimiterPlacementInside || eventJustStarted)) ||
                                pulseEntered
                            var count = 0
                            if (entered) {
                                count = triggerPolicyEvent(
                                    mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                                    layerMix, anySolo,
                                    startOffsetFrames = if (pulseEntered) {
                                        limiterPulseOffsetFrames
                                    } else {
                                        0
                                    },
                                )
                            } else if (!inside &&
                                limiterPolicy.mode == PersistentLimiterProgramMode.DECAY_REGION_LOOP &&
                                persistentLimiterPlacementInside
                            ) {
                                stopPersistentLimiterLoopSources()
                            }
                            persistentLimiterPlacementInside = inside
                            count
                        }
                    }
                }
            }
            OneShotPolicyKind.ENGINE_EVENT_REGION -> {
                val enginePolicy = requireNotNull(spec.policy.engineEvent)
                val inside = gatesAllow(
                    enginePolicy.parameterGates, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                )
                val state = requireNotNull(engineEventState)
                if (state.update(inside)) {
                    engineParameterRegionReentry = state.lastTriggerWasParameterRegionReentry
                    val triggered = triggerRoots(
                        mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                        layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                    )
                    engineParameterRegionReentry = false
                    triggered
                } else {
                    0
                }
            }
            OneShotPolicyKind.TURBO_EVENT_PROGRAM -> updateTurboEventProgram(
                driveAudioActive = driveAudioActive,
                mask = mask,
                rpm = rpm,
                throttle = throttle,
                drivetrainRpm = drivetrainRpm,
                turboGain = turboGain,
                layerMix = layerMix,
                anySolo = anySolo,
            )
            OneShotPolicyKind.SHIFT_UP,
            OneShotPolicyKind.SHIFT_DOWN,
            OneShotPolicyKind.ENGINE_START -> 0
        }

        private fun updateTurboEventProgram(
            driveAudioActive: Boolean,
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
        ): Int {
            val desiredActive = driveAudioActive && mask and SampleEffectControls.turbo.bit != 0L
            if (!desiredActive) {
                if (turboEventOwnerActive && polyphonicProgramOrdinal >= 0) {
                    globalVoiceArbiter.retireDynamicVoicesForProgram(polyphonicProgramOrdinal)
                }
                turboEventOwnerActive = false
                turboTimelineFrame = 0L
                turboNextTimelineTriggerFrame = 0L
                turboPreviousBoost = turboGain
                return 0
            }

            val turboPolicy = requireNotNull(spec.policy.turboEvent)
            val eventJustStarted = !turboEventOwnerActive
            if (eventJustStarted) {
                turboEventOwnerActive = true
                turboPreviousBoost = turboGain
                turboTimelineFrame = 0L
                turboNextTimelineTriggerFrame = turboPolicy.timelineStartFrames ?: 0L
            }
            return when (turboPolicy.mode) {
                TurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT -> {
                    if (eventJustStarted) {
                        triggerRoots(
                            mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                            layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                        )
                    } else {
                        0
                    }
                }
                TurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT -> {
                    val previous = turboPreviousBoost
                    turboPreviousBoost = turboGain
                    val shouldSchedule = if (eventJustStarted) {
                        turboPolicy.containsBoost(turboGain)
                    } else {
                        val maximum = requireNotNull(turboPolicy.placementMaximumBoost)
                        val previousAbove = if (turboPolicy.includeMaximum) {
                            previous > maximum
                        } else {
                            previous >= maximum
                        }
                        val crossedToInsideSide = if (turboPolicy.includeMaximum) {
                            turboGain <= maximum
                        } else {
                            turboGain < maximum
                        }
                        previousAbove && crossedToInsideSide
                    }
                    if (shouldSchedule) {
                        triggerRoots(
                            mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                            layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                        )
                    } else {
                        0
                    }
                }
                TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT -> {
                    val period = requireNotNull(turboPolicy.timelinePeriodFrames)
                    val blockEnd = if (turboTimelineFrame > Long.MAX_VALUE - currentBlockFrames) {
                        Long.MAX_VALUE
                    } else {
                        turboTimelineFrame + currentBlockFrames
                    }
                    var count = 0
                    if (eventJustStarted && turboNextTimelineTriggerFrame == 0L) {
                        count += triggerRoots(
                            mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                            layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                        )
                        turboNextTimelineTriggerFrame = period
                    }
                    while (turboNextTimelineTriggerFrame < blockEnd) {
                        val startOffsetFrames =
                            (turboNextTimelineTriggerFrame - turboTimelineFrame).toInt()
                        count += triggerRoots(
                            mask, rpm, throttle, drivetrainRpm, 0.0, turboGain,
                            layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                            startOffsetFrames = startOffsetFrames,
                        )
                        turboNextTimelineTriggerFrame = if (
                            turboNextTimelineTriggerFrame > Long.MAX_VALUE - period
                        ) {
                            Long.MAX_VALUE
                        } else {
                            turboNextTimelineTriggerFrame + period
                        }
                        if (turboNextTimelineTriggerFrame == Long.MAX_VALUE) break
                    }
                    turboTimelineFrame = blockEnd
                    count
                }
            }
        }

        private fun stopPersistentLimiterLoopSources() {
            var nodeIndex = 0
            while (nodeIndex < effectIndices.size) {
                val effectIndex = effectIndices[nodeIndex]
                if (effectIndex >= 0 && effectVoices[effectIndex].spec.looping) {
                    effectVoices[effectIndex].stopPersistent()
                }
                nodeIndex += 1
            }
        }

        fun registerPersistentLimiterEffectMappings(mappings: IntArray, programIndex: Int) {
            if (spec.policy.kind != OneShotPolicyKind.PERSISTENT_LIMITER_EVENT) return
            var nodeIndex = 0
            while (nodeIndex < effectIndices.size) {
                val effectIndex = effectIndices[nodeIndex]
                if (effectIndex >= 0) {
                    check(mappings[effectIndex] == -1)
                    mappings[effectIndex] = programIndex
                }
                nodeIndex += 1
            }
        }

        fun turboControlGain(effectIndex: Int, boost: Double): Double {
            val nodeIndex = nodeIndexByEffectIndex[effectIndex]
            if (nodeIndex < 0) return 0.0
            val node = nodes[nodeIndex] as OneShotTrackNodeSpec
            var gain = 1.0
            var curveIndex = 0
            while (curveIndex < node.controlGainCurves.size) {
                val controlCurve = node.controlGainCurves[curveIndex]
                gain *= controlCurve.curve.valueAt(turboControlValue(controlCurve.control, boost))
                curveIndex += 1
            }
            return gain
        }

        fun turboPlaybackRate(effectIndex: Int, boost: Double): Double {
            val nodeIndex = nodeIndexByEffectIndex[effectIndex]
            if (nodeIndex < 0) return 1.0
            val node = nodes[nodeIndex] as OneShotTrackNodeSpec
            var rate = 1.0
            var automationIndex = 0
            while (automationIndex < node.pitchAutomations.size) {
                val automation = node.pitchAutomations[automationIndex]
                rate *= automation.playbackRateCurve.valueAt(
                    turboControlValue(automation.control, boost),
                )
                automationIndex += 1
            }
            return rate
        }

        private fun turboControlValue(control: OneShotGateControl, boost: Double): Double =
            when (control) {
                OneShotGateControl.BOOST -> boost
                OneShotGateControl.BOV -> physicalBovValue
                OneShotGateControl.BOV_DECAY -> physicalBovDecaySeconds
                else -> error("Non-native control in TURBO_EVENT automation")
            }

        private fun hasActiveProgramEffect(): Boolean {
            var nodeIndex = 0
            while (nodeIndex < effectIndices.size) {
                val effectIndex = effectIndices[nodeIndex]
                if (effectIndex >= 0 && effectVoices[effectIndex].isOneShotActive) return true
                nodeIndex += 1
            }
            return false
        }

        fun triggerPolicyEvent(
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            startOffsetFrames: Int = 0,
        ): Int {
            if (rpm < spec.policy.minimumRpm ||
                (spec.policy.maximumRpm?.let { rpm > it } == true) ||
                cooldownFramesRemaining > 0
            ) return 0
            val count = triggerRoots(
                mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                layerMix, anySolo, auditionOnly = false, bypassMixControls = false,
                startOffsetFrames = startOffsetFrames,
            )
            cooldownFramesRemaining = (spec.policy.cooldownSeconds * outputSampleRate)
                .toInt()
                .coerceAtLeast(0)
            return count
        }

        fun triggerAudition(
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            bypassMixControls: Boolean,
        ): Int = triggerRoots(
            mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
            layerMix, anySolo, auditionOnly = true, bypassMixControls = bypassMixControls,
        )

        private fun triggerRoots(
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            auditionOnly: Boolean,
            bypassMixControls: Boolean,
            startOffsetFrames: Int = 0,
        ): Int {
            var count = 0
            var rootIndex = 0
            while (rootIndex < roots.size) {
                val nodeIndex = roots[rootIndex]
                if (nodeEligible(
                        nodeIndex, mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                        layerMix, anySolo, auditionOnly, bypassMixControls,
                    )
                ) {
                    count += triggerNode(
                        nodeIndex, mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                        layerMix, anySolo, auditionOnly, bypassMixControls, startOffsetFrames,
                    )
                }
                rootIndex += 1
            }
            return count
        }

        private fun nodeEligible(
            nodeIndex: Int,
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            auditionOnly: Boolean,
            bypassMixControls: Boolean,
        ): Boolean {
            val node = nodes[nodeIndex]
            if (spec.policy.kind == OneShotPolicyKind.TURBO_EVENT_PROGRAM) {
                return when (node) {
                    is OneShotTrackNodeSpec -> !auditionOnly &&
                        effectVoices[effectIndices[nodeIndex]].spec.trigger == SampleEffectTrigger.TURBO_EVENT
                    is OneShotSilentNodeSpec -> !auditionOnly
                    is OneShotGroupNodeSpec -> {
                        val children = memberIndices[nodeIndex]
                        var eligible = false
                        var childIndex = 0
                        while (childIndex < children.size && !eligible) {
                            eligible = nodeEligible(
                                children[childIndex], mask, rpm, throttle, drivetrainRpm, shiftState,
                                turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                            )
                            childIndex += 1
                        }
                        eligible
                    }
                }
            }
            return when (node) {
            is OneShotTrackNodeSpec -> {
                val voice = effectVoices[effectIndices[nodeIndex]]
                val coreEngineProgram = spec.policy.kind == OneShotPolicyKind.ENGINE_EVENT_REGION
                if (coreEngineProgram && engineParameterRegionReentry &&
                    engineLeafHasTriggered[nodeIndex] &&
                    node.engineTransientReentryPolicy ==
                    EngineTransientReentryPolicy
                        .NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER
                ) {
                    false
                } else if (auditionOnly && !voice.spec.auditionable) {
                    false
                } else if (!auditionOnly && voice.spec.trigger != spec.trigger) {
                    false
                } else if ((!coreEngineProgram && !bypassMixControls &&
                        mask and voice.spec.control.bit == 0L) ||
                    rpm < voice.spec.minimumRpm ||
                    (node.rpmAmplitudeCurve?.valueAt(rpm) ?: 1.0) <= SILENCE_GAIN ||
                    (node.throttleAmplitudeCurve?.valueAt(throttle) ?: 1.0) <= SILENCE_GAIN ||
                    !gatesAllow(node, rpm, throttle, drivetrainRpm, shiftState, turboGain)
                ) {
                    false
                } else if (bypassMixControls || coreEngineProgram) {
                    true
                } else {
                    applyLayerMix(voice.spec.id, voice.baseGain, layerMix, anySolo) > SILENCE_GAIN
                }
            }
            is OneShotGroupNodeSpec -> {
                val children = memberIndices[nodeIndex]
                var eligible = false
                var childIndex = 0
                while (childIndex < children.size && !eligible) {
                    eligible = nodeEligible(
                        children[childIndex], mask, rpm, throttle, drivetrainRpm, shiftState,
                        turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                    )
                    childIndex += 1
                }
                eligible
            }
            is OneShotSilentNodeSpec -> false
            }
        }

        private fun gatesAllow(
            node: OneShotTrackNodeSpec,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
        ): Boolean {
            return gatesAllow(
                node.parameterGates, rpm, throttle, drivetrainRpm, shiftState, turboGain,
            )
        }

        private fun gatesAllow(
            gates: List<OneShotParameterGateSpec>,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
        ): Boolean {
            var gateIndex = 0
            while (gateIndex < gates.size) {
                val gate = gates[gateIndex]
                val value = when (gate.control) {
                    OneShotGateControl.ENGINE_RPM -> rpm
                    OneShotGateControl.ACCELERATOR -> throttle
                    OneShotGateControl.SHIFT_STATE -> shiftState
                    OneShotGateControl.BOOST -> turboGain
                    OneShotGateControl.BOV -> physicalBovValue
                    OneShotGateControl.BOV_DECAY -> physicalBovDecaySeconds
                    OneShotGateControl.DECAY -> 0.0
                    OneShotGateControl.DRIVETRAIN_SPEED -> drivetrainRpm
                }
                if (value < gate.minimum || value > gate.maximum ||
                    (!gate.includeMinimum && value == gate.minimum) ||
                    (!gate.includeMaximum && value == gate.maximum)
                ) {
                    return false
                }
                gateIndex += 1
            }
            return true
        }

        private fun triggerNode(
            nodeIndex: Int,
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            auditionOnly: Boolean,
            bypassMixControls: Boolean,
            startOffsetFrames: Int,
        ): Int {
            val node = nodes[nodeIndex]
            if (!passesChance(nodeIndex, node.triggerChance)) return 0
            return when (node) {
                is OneShotTrackNodeSpec -> {
                    val effectIndex = effectIndices[nodeIndex]
                    val admitted = if (spec.policy.polyphonicLaneCount() > 0) {
                        if (triggerPolyphonicVoice(
                            polyphonicProgramOrdinal, effectIndex, startOffsetFrames,
                            node.zeroGainVirtualization,
                        )
                        ) 1 else 0
                    } else {
                        effectVoices[effectIndex].trigger(
                            rpm, throttle, turboGain, startOffsetFrames,
                        )
                        1
                    }
                    if (admitted > 0 && spec.policy.kind == OneShotPolicyKind.ENGINE_EVENT_REGION) {
                        engineLeafHasTriggered[nodeIndex] = true
                    }
                    admitted
                }
                is OneShotSilentNodeSpec -> 0
                is OneShotGroupNodeSpec -> {
                    val children = memberIndices[nodeIndex]
                    if (node.selectionMode == OneShotSelectionMode.SELECT_ALL) {
                        var count = 0
                        var childIndex = 0
                        while (childIndex < children.size) {
                            val child = children[childIndex]
                            if (nodeEligible(
                                    child, mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                                    layerMix, anySolo, auditionOnly, bypassMixControls,
                                )
                            ) {
                                count += triggerNode(
                                    child, mask, rpm, throttle, drivetrainRpm, shiftState, turboGain,
                                    layerMix, anySolo, auditionOnly, bypassMixControls,
                                    startOffsetFrames,
                                )
                            }
                            childIndex += 1
                        }
                        count
                    } else {
                        val selected = selectMember(
                            nodeIndex, node, mask, rpm, throttle, drivetrainRpm, shiftState,
                            turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                        )
                        if (selected < 0) 0 else triggerNode(
                            children[selected], mask, rpm, throttle, drivetrainRpm, shiftState,
                            turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                            startOffsetFrames,
                        )
                    }
                }
            }
        }

        private fun selectMember(
            nodeIndex: Int,
            node: OneShotGroupNodeSpec,
            mask: Long,
            rpm: Double,
            throttle: Double,
            drivetrainRpm: Double,
            shiftState: Double,
            turboGain: Double,
            layerMix: Map<String, LayerMixControl>,
            anySolo: Boolean,
            auditionOnly: Boolean,
            bypassMixControls: Boolean,
        ): Int {
            val children = memberIndices[nodeIndex]
            if (node.playMode == OneShotPlayMode.SEQUENTIAL) {
                var offset = 0
                while (offset < children.size) {
                    val candidate = (sequentialCursors[nodeIndex] + offset) % children.size
                    if (nodeEligible(
                            children[candidate], mask, rpm, throttle, drivetrainRpm, shiftState,
                            turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                        )
                    ) {
                        sequentialCursors[nodeIndex] = (candidate + 1) % children.size
                        lastSelections[nodeIndex] = candidate
                        return candidate
                    }
                    offset += 1
                }
                return -1
            }

            var eligibleCount = 0
            var childIndex = 0
            while (childIndex < children.size) {
                if (nodeEligible(
                        children[childIndex], mask, rpm, throttle, drivetrainRpm, shiftState,
                        turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                    )
                ) eligibleCount += 1
                childIndex += 1
            }
            val excluded = if (
                node.playMode == OneShotPlayMode.SMART_RANDOM && eligibleCount > 1
            ) lastSelections[nodeIndex] else -1
            var totalWeight = 0.0
            childIndex = 0
            while (childIndex < children.size) {
                if (childIndex != excluded && nodeEligible(
                        children[childIndex], mask, rpm, throttle, drivetrainRpm, shiftState,
                        turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                    )
                ) totalWeight += memberWeights[nodeIndex][childIndex]
                childIndex += 1
            }
            if (totalWeight <= 0.0) return -1
            val needle = nextUnit(nodeIndex) * totalWeight
            var cumulative = 0.0
            var fallback = -1
            childIndex = 0
            while (childIndex < children.size) {
                if (childIndex != excluded && nodeEligible(
                        children[childIndex], mask, rpm, throttle, drivetrainRpm, shiftState,
                        turboGain, layerMix, anySolo, auditionOnly, bypassMixControls,
                    )
                ) {
                    fallback = childIndex
                    cumulative += memberWeights[nodeIndex][childIndex]
                    if (needle < cumulative) {
                        lastSelections[nodeIndex] = childIndex
                        return childIndex
                    }
                }
                childIndex += 1
            }
            lastSelections[nodeIndex] = fallback
            return fallback
        }

        private fun passesChance(nodeIndex: Int, chance: Double): Boolean = when {
            chance >= 1.0 -> true
            chance <= 0.0 -> false
            else -> nextUnit(nodeIndex) < chance
        }

        private fun nextUnit(nodeIndex: Int): Double {
            var value = randomStates[nodeIndex]
            value = value xor (value shl 13)
            value = value xor (value ushr 7)
            value = value xor (value shl 17)
            randomStates[nodeIndex] = value
            return (value ushr 11).toDouble() / 9_007_199_254_740_992.0
        }
    }

    private fun EffectVoice.isEligibleOneShot(
        trigger: SampleEffectTrigger?,
        auditionOnly: Boolean,
        mask: Long,
        rpm: Double,
        throttle: Double,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
        bypassMixControls: Boolean,
    ): Boolean {
        if (spec.looping) return false
        if (if (auditionOnly) !spec.auditionable else spec.trigger != trigger) return false
        if ((!bypassMixControls && mask and spec.control.bit == 0L) || rpm < spec.minimumRpm) return false
        val authoredGain = baseGain *
            (spec.rpmAmplitudeCurve?.valueAt(rpm) ?: 1.0) *
            (spec.throttleAmplitudeCurve?.valueAt(throttle) ?: 1.0)
        return if (bypassMixControls) {
            authoredGain > SILENCE_GAIN
        } else {
            applyLayerMix(spec.id, authoredGain, layerMix, anySolo) > SILENCE_GAIN
        }
    }

    /** Allocation-free xorshift sequence; FMOD does not expose its private random seed. */
    private fun nextOneShotVariantIndex(bound: Int): Int {
        if (bound <= 1) return 0
        var value = oneShotRandomState
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        oneShotRandomState = value
        return ((value ushr 1) % bound.toLong()).toInt()
    }

    private fun updateVoiceTargets(
        rpm: Double,
        throttle: Double,
        turboGain: Double,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
    ) {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            val roleGain = when (voice.spec.role) {
                SampleLayerRole.TURBO, SampleLayerRole.SPOOL -> turboGain
                else -> 1.0
            }
            voice.targetGain = applyLayerMix(
                voice.spec.id,
                voice.spec.gainAt(rpm, throttle) * roleGain,
                layerMix,
                anySolo,
            )
            voice.playbackRatio = voice.spec.playbackRatio(rpm)
            voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate * voice.playbackRatio
            voiceIndex += 1
        }
    }

    private fun applyLayerMix(
        trackId: String,
        authoredGain: Double,
        layerMix: Map<String, LayerMixControl>,
        anySolo: Boolean,
    ): Double {
        val mix = layerMix[trackId] ?: LayerMixControl.DEFAULT
        if (mix.muted || (anySolo && !mix.solo)) return 0.0
        return authoredGain * mix.volume.coerceIn(
            LayerMixControl.MIN_GAIN_MULTIPLIER,
            LayerMixControl.MAX_GAIN_MULTIPLIER,
        )
    }

    private fun hasAudibleSolo(layerMix: Map<String, LayerMixControl>): Boolean {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val control = layerMix[voices[voiceIndex].spec.id]
            if (control?.solo == true && !control.muted) return true
            voiceIndex += 1
        }
        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val control = layerMix[effectVoices[effectIndex].spec.id]
            if (control?.solo == true && !control.muted) return true
            effectIndex += 1
        }
        return false
    }

    private fun buildLayerOutputMeters(target: EngineAudioControlFrame): List<LayerOutputMeter> {
        if (!isProgramAudible(target)) return emptyList()
        val loopScale = if (target.soloEffects) 0.0 else continuousProgramGain
        return buildList(voices.size + effectVoices.size) {
            voices.forEach { voice ->
                val level = if (voice.softwareReal) voice.gain * loopScale else 0.0
                add(LayerOutputMeter(voice.spec.id, level.coerceIn(0.0, 1.0)))
            }
            effectVoices.forEachIndexed { effectIndex, voice ->
                var level = if (voice.softwareReal && voice.isAudible) {
                    voice.gain.coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
                if (voice.spec.polyphonicTemplate) {
                    dynamicEffectVoices.forEach { dynamic ->
                        if (dynamic.effectIndex == effectIndex && dynamic.isAudible) {
                            level = max(level, dynamic.gain.coerceIn(0.0, 1.0))
                        }
                    }
                }
                add(LayerOutputMeter(voice.spec.id, level))
            }
        }
    }

    private class LoopVoice(val spec: SampleLayerSpec, val data: PlanarPcmData) {
        var phase = 0.0
        var phaseIncrement = 1.0
        var playbackRatio = 1.0
        var softwareReal = false
        @Volatile var gain = 0.0
        @Volatile var targetGain = 0.0
        private var hasLooped = false
        private val crossfadeFrames = spec.loopCrossfadeFrames.coerceAtMost(
            (data.loopEndFrameExclusive - data.loopStartFrame) / MAX_CROSSFADE_LOOP_FRACTION,
        )
        private val crossfadeStart = data.loopEndFrameExclusive - crossfadeFrames

        fun readCubic(outputChannel: Int): Double {
            val sourceChannel = outputChannel.coerceAtMost(data.sourceChannels - 1)
            if (crossfadeFrames == 0 || phase < crossfadeStart) {
                return readLoopCubic(sourceChannel, phase)
            }
            val offset = phase - crossfadeStart
            val blend = smoothstep(offset / crossfadeFrames)
            val tail = readClampedCubic(sourceChannel, phase)
            val head = readClampedCubic(sourceChannel, data.loopStartFrame + offset)
            return tail + (head - tail) * blend
        }

        fun advance(): Boolean {
            phase += phaseIncrement
            if (phase < data.loopEndFrameExclusive) return false
            val resumeFrame = data.loopStartFrame + crossfadeFrames
            val effectiveLoopLength = data.loopEndFrameExclusive - resumeFrame
            phase = resumeFrame + (phase - data.loopEndFrameExclusive) % effectiveLoopLength
            hasLooped = true
            return true
        }

        private fun readLoopCubic(channel: Int, samplePhase: Double): Double {
            val frame = samplePhase.toInt()
            val fraction = samplePhase - frame
            return cubic(
                sampleAt(channel, frame - 1).toDouble(),
                sampleAt(channel, frame).toDouble(),
                sampleAt(channel, frame + 1).toDouble(),
                sampleAt(channel, frame + 2).toDouble(),
                fraction,
            )
        }

        private fun readClampedCubic(channel: Int, samplePhase: Double): Double {
            val frame = samplePhase.toInt()
            val fraction = samplePhase - frame
            return cubic(
                data.normalizedSample(channel, (frame - 1).coerceIn(0, data.frameCount - 1)),
                data.normalizedSample(channel, frame.coerceIn(0, data.frameCount - 1)),
                data.normalizedSample(channel, (frame + 1).coerceIn(0, data.frameCount - 1)),
                data.normalizedSample(channel, (frame + 2).coerceIn(0, data.frameCount - 1)),
                fraction,
            )
        }

        private fun sampleAt(channel: Int, index: Int): Float {
            val start = data.loopStartFrame
            val end = data.loopEndFrameExclusive
            val length = end - start
            val resolved = when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, data.frameCount - 1)
            }
            return data.normalizedSample(channel, resolved).toFloat()
        }
    }

    /** One preallocated software voice rebound to immutable PCM at a program trigger. */
    private class DynamicEffectVoice(
        private val outputSampleRate: Int,
    ) {
        private var data: PlanarPcmData? = null
        var phase = 0.0
            private set
        private var active = false
        private var startDelayFrames = 0
        var effectIndex = -1
            private set
        var phaseIncrement = 1.0
        var zeroTransitionActive = false
            private set
        var zeroTransitionElapsedFrames = 0
            private set
        var zeroTransitionRetainFrames = 0
            private set
        var zeroTransitionFadeFrames = 0
            private set
        var zeroTransitionStartGain = 0.0
            private set
        var phaseAdvanceFramesRemaining = 0
            private set
        @Volatile var gain = 0.0
        @Volatile var targetGain = 0.0
        val isActive: Boolean get() = active
        val isAudible: Boolean get() = active && gain > 0.0

        fun start(
            effectIndex: Int,
            spec: SampleEffectSpec,
            data: PlanarPcmData,
            retainedPhase: Double,
            retainedGain: Double,
            startDelayFrames: Int,
        ) {
            require(spec.polyphonicTemplate && !spec.looping)
            require(
                (spec.coreEngineTransient && spec.trigger == SampleEffectTrigger.ENGINE_EVENT) ||
                    spec.trigger == SampleEffectTrigger.LIMITER_EVENT ||
                    spec.trigger == SampleEffectTrigger.TURBO_EVENT,
            )
            this.effectIndex = effectIndex
            this.data = data
            phase = retainedPhase.coerceIn(0.0, data.frameCount - 1.0)
            phaseIncrement = data.sampleRate.toDouble() / outputSampleRate *
                if (spec.coreEngineTransient) {
                    spec.authoredEngineTransientPlaybackRatio(0.0)
                } else {
                    1.0
                }
            gain = retainedGain.coerceAtLeast(0.0)
            this.startDelayFrames = startDelayFrames.coerceAtLeast(0)
            targetGain = 0.0
            zeroTransitionActive = false
            zeroTransitionElapsedFrames = 0
            zeroTransitionRetainFrames = 0
            zeroTransitionFadeFrames = 0
            zeroTransitionStartGain = 0.0
            phaseAdvanceFramesRemaining = 0
            active = true
        }

        fun stop() {
            active = false
            gain = 0.0
            targetGain = 0.0
            effectIndex = -1
            data = null
            phase = 0.0
            startDelayFrames = 0
            zeroTransitionActive = false
            zeroTransitionElapsedFrames = 0
            zeroTransitionRetainFrames = 0
            zeroTransitionFadeFrames = 0
            zeroTransitionStartGain = 0.0
            phaseAdvanceFramesRemaining = 0
        }

        fun configureZeroGainLifecycle(
            transitionActive: Boolean,
            transitionElapsedFrames: Int,
            transitionRetainFrames: Int,
            transitionFadeFrames: Int,
            transitionStartGain: Double,
            phaseAdvanceFrames: Int,
        ) {
            require(transitionElapsedFrames >= 0)
            require(transitionRetainFrames >= 0)
            require(transitionFadeFrames >= 0)
            require(transitionStartGain >= 0.0 && transitionStartGain.isFinite())
            require(phaseAdvanceFrames >= 0)
            require(!transitionActive || transitionFadeFrames > 0 || transitionRetainFrames == 0)
            zeroTransitionActive = transitionActive
            zeroTransitionElapsedFrames = transitionElapsedFrames
            zeroTransitionRetainFrames = transitionRetainFrames
            zeroTransitionFadeFrames = transitionFadeFrames
            zeroTransitionStartGain = transitionStartGain
            phaseAdvanceFramesRemaining = phaseAdvanceFrames
            if (transitionActive) gain = zeroTransitionGain()
        }

        fun applyRestorePhaseOffset(offsetFrames: Double) {
            require(offsetFrames.isFinite())
            require(kotlin.math.abs(offsetFrames) <=
                ZeroGainTransitionSpec.MAXIMUM_ABSOLUTE_RESTORE_PHASE_OFFSET_FRAMES)
            if (!active || offsetFrames == 0.0) return
            val pcm = requireNotNull(data)
            phase = (phase + offsetFrames).coerceIn(0.0, pcm.frameCount - 1.0)
        }

        fun updateGain(layerAlpha: Double) {
            gain = if (zeroTransitionActive) {
                zeroTransitionGain()
            } else {
                gain + (targetGain - gain) * layerAlpha
            }
        }

        fun setNativeActive(value: Boolean) {
            active = value
            if (!value) gain = 0.0
        }

        fun readCubic(outputChannel: Int): Double {
            val pcm = requireNotNull(data)
            val sourceChannel = outputChannel.coerceAtMost(pcm.sourceChannels - 1)
            val frame = phase.toInt()
            val fraction = phase - frame
            return cubic(
                pcm.normalizedSample(sourceChannel, (frame - 1).coerceIn(0, pcm.frameCount - 1)),
                pcm.normalizedSample(sourceChannel, frame.coerceIn(0, pcm.frameCount - 1)),
                pcm.normalizedSample(sourceChannel, (frame + 1).coerceIn(0, pcm.frameCount - 1)),
                pcm.normalizedSample(sourceChannel, (frame + 2).coerceIn(0, pcm.frameCount - 1)),
                fraction,
            )
        }

        fun beginFrame(): Boolean {
            if (!active) return false
            if (startDelayFrames > 0) {
                startDelayFrames -= 1
                return false
            }
            return true
        }

        fun advance() {
            if (!active) return
            val pcm = requireNotNull(data)
            if (zeroTransitionActive && zeroTransitionElapsedFrames < Int.MAX_VALUE) {
                zeroTransitionElapsedFrames += 1
            }
            if (phaseAdvanceFramesRemaining <= 0) return
            phaseAdvanceFramesRemaining -= 1
            phase += phaseIncrement
            if (phase >= pcm.frameCount - 1) {
                active = false
                gain = 0.0
                targetGain = 0.0
            }
        }

        private fun zeroTransitionGain(): Double {
            if (zeroTransitionElapsedFrames < zeroTransitionRetainFrames) {
                return zeroTransitionStartGain
            }
            if (zeroTransitionFadeFrames <= 0) return 0.0
            val fadeElapsed = zeroTransitionElapsedFrames - zeroTransitionRetainFrames
            if (fadeElapsed >= zeroTransitionFadeFrames) return 0.0
            return zeroTransitionStartGain *
                (zeroTransitionFadeFrames - fadeElapsed).toDouble() /
                zeroTransitionFadeFrames.toDouble()
        }
    }

    private class EffectVoice(
        val spec: SampleEffectSpec,
        val data: PlanarPcmData,
        private val outputSampleRate: Int,
    ) {
        private var phase = 0.0
        var phaseIncrement = 1.0
        @Volatile var gain = 0.0
        @Volatile var targetGain = 0.0
        private var active = spec.startsActive
        private var pendingNativeCommand = if (spec.looping && !spec.startsActive) -1 else 0
        private var pendingNativeStartOffset = 0
        private var startDelayFrames = 0
        private var arbiterActivationRequested = false
        private var hasLooped = false
        var softwareReal = false
        var triggerRpm = 0.0
            private set
        var triggerThrottle = 0.0
            private set
        var triggerTurboGain = 1.0
            private set
        val baseGain = 10.0.pow(spec.baseGainDb / 20.0)
        val isOneShotActive: Boolean get() = !spec.looping && active
        val isActive: Boolean get() = active
        val isAudible: Boolean get() = active && gain > SILENCE_GAIN

        fun trigger(
            rpm: Double,
            throttle: Double,
            turboGain: Double,
            startOffsetFrames: Int = 0,
        ) {
            require(startOffsetFrames >= 0)
            phase = 0.0
            phaseIncrement = data.sampleRate.toDouble() / outputSampleRate *
                (spec.authoredPlaybackRatio(rpm) ?: 1.0)
            triggerRpm = rpm
            triggerThrottle = throttle
            triggerTurboGain = turboGain
            active = true
            pendingNativeCommand = 1
            pendingNativeStartOffset = startOffsetFrames
            startDelayFrames = startOffsetFrames
            arbiterActivationRequested = true
            hasLooped = false
        }

        fun stopPersistent() {
            stopLogical()
        }

        fun stopLogical() {
            active = false
            gain = 0.0
            targetGain = 0.0
            pendingNativeCommand = -1
            pendingNativeStartOffset = 0
            startDelayFrames = 0
            arbiterActivationRequested = false
        }

        fun consumeArbiterActivationRequest(): Boolean = arbiterActivationRequested.also {
            arbiterActivationRequested = false
        }
        fun consumeNativeCommand(): Int = pendingNativeCommand.also { pendingNativeCommand = 0 }
        fun consumeNativeStartOffset(): Int =
            pendingNativeStartOffset.also { pendingNativeStartOffset = 0 }
        fun setNativeActive(value: Boolean) { active = value }

        fun beginFrame(): Boolean {
            if (!active) return false
            if (startDelayFrames > 0) {
                startDelayFrames -= 1
                return false
            }
            return true
        }

        fun readCubic(outputChannel: Int): Double {
            val sourceChannel = outputChannel.coerceAtMost(data.sourceChannels - 1)
            val frame = phase.toInt()
            val fraction = phase - frame
            return cubic(
                sampleAt(sourceChannel, frame - 1).toDouble(),
                sampleAt(sourceChannel, frame).toDouble(),
                sampleAt(sourceChannel, frame + 1).toDouble(),
                sampleAt(sourceChannel, frame + 2).toDouble(),
                fraction,
            )
        }

        fun advance(): Boolean {
            if (!active) return false
            phase += phaseIncrement
            if (!spec.looping) {
                if (phase >= data.frameCount - 1) {
                    active = false
                    gain = 0.0
                    targetGain = 0.0
                }
                return false
            }
            if (phase < data.loopEndFrameExclusive) return false
            val loopLength = data.loopEndFrameExclusive - data.loopStartFrame
            phase = data.loopStartFrame + (phase - data.loopEndFrameExclusive) % loopLength
            hasLooped = true
            return true
        }

        private fun sampleAt(channel: Int, index: Int): Float {
            if (!spec.looping) {
                return data.normalizedSample(channel, index.coerceIn(0, data.frameCount - 1)).toFloat()
            }
            val start = data.loopStartFrame
            val end = data.loopEndFrameExclusive
            val length = end - start
            val resolved = when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, data.frameCount - 1)
            }
            return data.normalizedSample(channel, resolved).toFloat()
        }
    }

    companion object {
        private val IDENTITY_THROTTLE_CURVE = AutomationCurve(
            listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0)),
        )

        /**
         * Chooses an authored, audible operating point for the explicit audition command. Natural
         * overrun still uses the live RPM; this only prevents the test button from silently firing
         * outside a car's own transient curve.
         */
        private fun findAuditionTriggerRpm(profile: EngineSampleProfile): Double {
            var bestRpm = profile.redlineRpm.coerceIn(profile.minimumRpm, profile.limiterRpm)
            var bestGain = -1.0
            profile.effects.asSequence()
                .filter { it.auditionable && !it.looping }
                .forEach { effect ->
                    val minimum = max(profile.minimumRpm, effect.minimumRpm)
                    val maximum = profile.limiterRpm.coerceAtMost(profile.maximumRpm)
                    if (minimum > maximum) return@forEach
                    val throttleGain = effect.throttleAmplitudeCurve?.valueAt(0.0) ?: 1.0
                    fun consider(rpm: Double) {
                        val candidate = rpm.coerceIn(minimum, maximum)
                        val gain = 10.0.pow(effect.baseGainDb / 20.0) *
                            (effect.rpmAmplitudeCurve?.valueAt(candidate) ?: 1.0) * throttleGain
                        if (gain > bestGain) {
                            bestGain = gain
                            bestRpm = candidate
                        }
                    }
                    consider(minimum)
                    consider(maximum)
                    consider(profile.redlineRpm)
                    effect.rpmAmplitudeCurve?.points?.forEach { point -> consider(point.input) }
                }
            return bestRpm
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PlanarPcmData>,
            profile: EngineSampleProfile,
        ): SampleEngineRenderer {
            val voices = profile.layers.map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" })
            }.toTypedArray()
            val effects = profile.effects.map { spec ->
                EffectVoice(
                    spec,
                    requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" },
                    outputSampleRate,
                )
            }.toTypedArray()
            val nativeLoops = profile.layers.map { spec ->
                val data = decoded.getValue(spec.assetName)
                (data as? NativePlanarPcmData)?.let { it to spec.loopCrossfadeFrames }
            }
            val nativeEffects = profile.effects.map { spec ->
                val data = decoded.getValue(spec.assetName)
                (data as? NativePlanarPcmData)?.let { it to spec.looping }
            }
            val hasPlayableNativeAudio = nativeLoops.isNotEmpty() || nativeEffects.isNotEmpty()
            val nativeMixer = if (
                hasPlayableNativeAudio && nativeLoops.all { it != null } && nativeEffects.all { it != null }
            ) {
                val dynamicEffectCount = if (
                    profile.oneShotPrograms.any { it.policy.polyphonicLaneCount() > 0 }
                ) {
                    GlobalVoiceArbiter.AC_SOFTWARE_REAL_VOICE_BUDGET
                } else {
                    0
                }
                NativePcmMixer.create(
                    nativeLoops.filterNotNull(), nativeEffects.filterNotNull(), dynamicEffectCount,
                )
            } else null
            return SampleEngineRenderer(
                outputSampleRate,
                profile,
                voices,
                effects,
                profile.requiredAssets.sumOf { asset ->
                    val data = requireNotNull(decoded[asset]) { "Missing $asset" }
                    data.frameCount.toLong() * data.sourceChannels *
                        (if (data is NativePlanarPcmData) Short.SIZE_BYTES else Float.SIZE_BYTES)
                },
                nativeMixer,
            )
        }

        private const val SAMPLE_HEADROOM = 0.65
        private const val PROGRAM_CHANNELS = 2
        private const val SILENCE_GAIN = 0.00001
        private const val ACTIVE_LAYER_GAIN = 0.006
        private const val MAX_ACTIVE_LAYER_LABELS = 8
        private const val NO_FRAME_OFFSET = -1
        private const val MAX_CROSSFADE_LOOP_FRACTION = 4
        private const val THROTTLE_LIFT_ARM_LEVEL = 0.35
        private const val THROTTLE_LIFT_FIRE_LEVEL = 0.08
        private const val AC_PHYSICS_STEP_SECONDS = 0.003
        private const val MAX_BACKFIRE_FUEL_SECONDS = 10.0
    }
}

private fun cubic(y0: Double, y1: Double, y2: Double, y3: Double, fraction: Double): Double {
    val a0 = y3 - y2 - y0 + y1
    val a1 = y0 - y1 - a0
    val a2 = y2 - y0
    return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
}

private fun smoothstep(value: Double): Double {
    val clamped = value.coerceIn(0.0, 1.0)
    return clamped * clamped * (3.0 - 2.0 * clamped)
}

private fun OneShotTriggerPolicySpec.polyphonicLaneCount(): Int = when (kind) {
    OneShotPolicyKind.ENGINE_EVENT_REGION -> requireNotNull(engineEvent).laneCount
    OneShotPolicyKind.PERSISTENT_LIMITER_EVENT ->
        requireNotNull(limiterEvent).oneShotLaneCount
    OneShotPolicyKind.TURBO_EVENT_PROGRAM -> requireNotNull(turboEvent).oneShotLaneCount
    else -> 0
}

internal val SAFETY_LIMITER_CEILING: Double = 10.0.pow(-1.0 / 20.0)
private val SAFETY_LIMITER_KNEE: Double = 10.0.pow(-3.0 / 20.0)

/** Transparent through -3 dBFS, then smoothly approaches the -1 dBFS ceiling. */
internal fun safetyLimit(value: Double): Double {
    val magnitude = abs(value)
    if (magnitude <= SAFETY_LIMITER_KNEE) return value
    val kneeWidth = SAFETY_LIMITER_CEILING - SAFETY_LIMITER_KNEE
    val limitedMagnitude = SAFETY_LIMITER_KNEE + kneeWidth *
        (1.0 - exp(-(magnitude - SAFETY_LIMITER_KNEE) / kneeWidth))
    return limitedMagnitude * value.sign
}

private fun toPcm16(value: Double): Short =
    (value * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
