package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

internal data class SampleRendererDiagnostics(
    val profileId: String = EngineSampleProfiles.default.id,
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
)

/** A profile-driven reconstruction of one recorded engine program. */
internal class SampleEngineRenderer private constructor(
    private val outputSampleRate: Int,
    private val profile: EngineSampleProfile,
    private val perspective: EngineSoundPerspective,
    private val voices: List<LoopVoice>,
    private val effectVoices: List<EffectVoice>,
    private val popsAndBangsVoice: EffectVoice?,
    private val sharedShiftUpVoice: EffectVoice?,
    private val sharedShiftDownVoice: EffectVoice?,
    private val decodedBytes: Long,
) {
    private var smoothedRpm = profile.idleRpm
    private var smoothedThrottle = 0.0
    private var masterGain = 0.0
    private var profileOutputGain = profile.outputGainAt(0.0, perspective)
    private var enabledGain = 0.0
    private var continuousProgramGain = 1.0
    private var framesRendered = 0L
    private var loopWraps = 0L
    private var overRangeSamples = 0L
    private var effectTriggers = 0L
    private var lastShiftSerial: Long? = null
    private var throttleLiftSustainedSeconds = 0.0
    private var throttleLiftQualified = false
    private var throttleLiftDelayRemainingSeconds = 0.0
    private val turboSpool = TurboSpoolModel()
    private var anyLayerSolo = false
    private var lastRequestedRpm = profile.idleRpm
    private var lastBlockPeak = 0.0
    private var lastTarget = EngineAudioFrame()

    /** Built only when a test explicitly asks for it; production rendering stores no diagnostic snapshots. */
    fun diagnostics(): SampleRendererDiagnostics {
        val target = lastTarget
        val activeLayers = voices.asSequence()
            .filter { it.targetGain > 0.006 }
            .sortedByDescending { it.targetGain }
            .take(8)
            .joinToString(",") { voice ->
                "${voice.spec.id}@${(voice.playbackRatio * 100.0).toInt()}%/${(voice.targetGain * 100.0).toInt()}%"
            }
            .ifBlank { "none" }
        return SampleRendererDiagnostics(
            profileId = profile.id,
            loadedLoops = voices.size,
            loadedEffects = effectVoices.size +
                listOfNotNull(popsAndBangsVoice, sharedShiftUpVoice, sharedShiftDownVoice).size,
            decodedBytes = decodedBytes,
            targetRpm = lastRequestedRpm.toInt(),
            renderRpm = smoothedRpm.toInt(),
            throttle = smoothedThrottle,
            activeLayers = activeLayers,
            playingSamples = audiblePlayingSamples(target),
            layerOutputMeters = buildLayerOutputMeters(target),
            framesRendered = framesRendered,
            loopWraps = loopWraps,
            peak = lastBlockPeak,
            overRangeSamples = overRangeSamples,
            effectTriggers = effectTriggers,
            activeEffects = buildList {
                effectVoices.asSequence()
                    .filter { it.isAudible || it.targetGain > SILENCE_GAIN }
                    .mapTo(this) { it.spec.id }
                popsAndBangsVoice?.let { voice ->
                    if (voice.isAudible || voice.targetGain > SILENCE_GAIN) {
                        add(voice.spec.id)
                    }
                }
                sharedShiftUpVoice?.let { voice ->
                    if (voice.isAudible || voice.targetGain > SILENCE_GAIN) {
                        add(voice.spec.id)
                    }
                }
                sharedShiftDownVoice?.let { voice ->
                    if (voice.isAudible || voice.targetGain > SILENCE_GAIN) {
                        add(voice.spec.id)
                    }
                }
            }.joinToString(",").ifBlank { "none" },
        )
    }

    val meterTrackIds: List<String> = buildList {
        addAll(voices.map { it.spec.id })
        addAll(effectVoices.map { it.spec.id })
        if (popsAndBangsVoice != null) {
            add(popsAndBangsVoice.spec.id)
        }
        sharedShiftUpVoice?.let { voice ->
            add(voice.spec.id)
        }
        sharedShiftDownVoice?.let { voice ->
            add(voice.spec.id)
        }
    }

    /** Copies current gains into caller-owned storage without allocating on the audio thread. */
    fun writeLayerOutputLevels(target: EngineAudioFrame, destination: DoubleArray) {
        require(destination.size == meterTrackIds.size)
        if (!isProgramAudible(target)) {
            destination.fill(0.0)
            return
        }
        val loopScale = continuousProgramGain
        var index = 0
        while (index < voices.size) {
            destination[index] = (voices[index].gain * loopScale).coerceIn(0.0, 1.0)
            index += 1
        }
        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            destination[index] = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
            index += 1
            effectIndex += 1
        }
        popsAndBangsVoice?.let { voice ->
            destination[index] = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
            index += 1
        }
        sharedShiftUpVoice?.let { voice ->
            destination[index] = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
            index += 1
        }
        sharedShiftDownVoice?.let { voice ->
            destination[index] = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
        }
    }

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double) {
        require(output.size % PROGRAM_CHANNELS == 0) { "Stereo render buffer must contain whole frames" }
        val frameCount = output.size / PROGRAM_CHANNELS
        val blockSeconds = frameCount.toDouble() / outputSampleRate
        val rpmAlpha = 1.0 - exp(-blockSeconds / (target.tuning.rpmSmoothingMs / 1_000.0))
        val throttleAlpha = 1.0 - exp(-blockSeconds / (target.tuning.throttleSmoothingMs / 1_000.0))
        val requestedRpm = target.rpm.coerceIn(profile.minimumRpm, profile.maximumRpm)
        smoothedRpm += (requestedRpm - smoothedRpm) * rpmAlpha
        smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * throttleAlpha

        anyLayerSolo = target.layerMix.values.any { control -> control.solo && !control.muted }
        val loadProgram = profile.appliesLoadOnlyProgram(target.loadOnlyProgram, perspective)
        val primaryLayerSource = profile.resolvedPrimaryLayerSource(target.primaryLayerSource, perspective)
        turboSpool.update(
            blockSeconds,
            smoothedRpm,
            smoothedThrottle,
            target.turboSpoolAttackMultiplier,
        )
        updateVoiceTargets(
            smoothedRpm,
            smoothedThrottle,
            target.layerMix,
            loadProgram,
            primaryLayerSource,
            target.loadOnlyProgram,
            target.programLayerGains,
        )
        updateEffectTargetsAndTriggers(target, target.layerMix, blockSeconds)
        val targetMaster = (gain * target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72).coerceIn(0.0, 1.5)
        val targetProfileOutputGain = if (loadProgram) {
            profile.outputGainAt(1.0, perspective)
        } else {
            profile.outputGainAt(smoothedThrottle, perspective)
        }
        val targetEnabled = if (target.enabled) 1.0 else 0.0
        val targetContinuousProgram = 1.0
        val programFadeSeconds = target.tuning.programFadeMs / 1_000.0
        val masterAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val profileGainAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val enabledAlpha = 1.0 - exp(-1.0 / (outputSampleRate * (target.tuning.enabledFadeMs / 1_000.0)))
        val layerFadeSeconds = target.tuning.layerFadeMs / 1_000.0
        val layerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * layerFadeSeconds))
        var blockPeak = 0.0

        for (frameIndex in 0 until frameCount) {
            continuousProgramGain += (targetContinuousProgram - continuousProgramGain) * masterAlpha
            var loopLeft = 0.0
            var loopRight = 0.0
            var voiceIndex = 0
            while (voiceIndex < voices.size) {
                val voice = voices[voiceIndex]
                voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                if (voice.gain > SILENCE_GAIN || voice.targetGain > SILENCE_GAIN) {
                    voice.readStereoCubic()
                    loopLeft += voice.sampleLeft * voice.gain
                    loopRight += voice.sampleRight * voice.gain
                }
                // Source timelines keep running even while a layer is inaudible. Doing the same
                // prevents an audible sample restart when an RPM or throttle fade opens again.
                if (voice.advance()) loopWraps += 1
                voiceIndex += 1
            }
            var effectLeft = 0.0
            var effectRight = 0.0
        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            voice.gain += (voice.targetGain - voice.gain) * layerAlpha
            if (voice.isAudible) {
                voice.readStereoCubic()
                effectLeft += voice.sampleLeft * voice.gain
                effectRight += voice.sampleRight * voice.gain
            }
            if (voice.advance()) loopWraps += 1
            effectIndex += 1
        }
        popsAndBangsVoice?.let { voice ->
            voice.gain += (voice.targetGain - voice.gain) * layerAlpha
            if (voice.isAudible) {
                voice.readStereoCubic()
                effectLeft += voice.sampleLeft * voice.gain
                effectRight += voice.sampleRight * voice.gain
            }
            if (voice.advance()) loopWraps += 1
        }
        sharedShiftUpVoice?.let { voice ->
            voice.gain += (voice.targetGain - voice.gain) * layerAlpha
            if (voice.isAudible) {
                voice.readStereoCubic()
                effectLeft += voice.sampleLeft * voice.gain
                effectRight += voice.sampleRight * voice.gain
            }
            if (voice.advance()) loopWraps += 1
        }
        sharedShiftDownVoice?.let { voice ->
            voice.gain += (voice.targetGain - voice.gain) * layerAlpha
            if (voice.isAudible) {
                voice.readStereoCubic()
                effectLeft += voice.sampleLeft * voice.gain
                effectRight += voice.sampleRight * voice.gain
            }
            if (voice.advance()) loopWraps += 1
        }
            masterGain += (targetMaster - masterGain) * masterAlpha
            profileOutputGain += (targetProfileOutputGain - profileOutputGain) * profileGainAlpha
            enabledGain += (targetEnabled - enabledGain) * enabledAlpha
            val commonGain = SAMPLE_HEADROOM * masterGain * profileOutputGain * enabledGain
            val mixedLeft = loopLeft * continuousProgramGain + effectLeft
            val mixedRight = loopRight * continuousProgramGain + effectRight
            val preLimitedLeft = mixedLeft * commonGain
            val preLimitedRight = mixedRight * commonGain
            if (abs(preLimitedLeft) > 1.0) overRangeSamples += 1
            if (abs(preLimitedRight) > 1.0) overRangeSamples += 1
            // Stay transparent throughout the normal range. Saturation now happens only on
            // a genuine full-scale overload instead of reshaping every quiet sample.
            val limitedLeft = transparentLimit(preLimitedLeft)
            val limitedRight = transparentLimit(preLimitedRight)
            blockPeak = max(blockPeak, max(abs(limitedLeft), abs(limitedRight)))
            val outputIndex = frameIndex * PROGRAM_CHANNELS
            output[outputIndex] = toPcm16(limitedLeft)
            output[outputIndex + 1] = toPcm16(limitedRight)
        }

        framesRendered += frameCount
        lastRequestedRpm = requestedRpm
        lastBlockPeak = blockPeak
        lastTarget = target
    }

    /** Loop and effect WAV assets audibly contributing to the mixed output right now. */
    private fun audiblePlayingSamples(target: EngineAudioFrame): List<PlayingSampleLabel> = buildList {
        if (isProgramAudible(target) && continuousProgramGain > SILENCE_GAIN) {
            voices.asSequence()
                .filter { voice ->
                    voice.gain > SILENCE_GAIN &&
                        (!anyLayerSolo || target.layerMix[voice.spec.id]?.solo == true)
                }
                .sortedByDescending { it.gain }
                .map { voice ->
                    PlayingSampleLabel(voice.spec.role.playingRoleLabel(), voice.spec.assetName)
                }
                .forEach(::add)
        }
        if (isProgramAudible(target)) {
            effectVoices.asSequence()
                .filter { voice ->
                    voice.isAudible &&
                        voice.gain > SILENCE_GAIN &&
                        (!anyLayerSolo || target.layerMix[voice.spec.id]?.solo == true)
                }
                .sortedByDescending { it.gain }
                .map { voice ->
                    PlayingSampleLabel(voice.spec.playingRoleLabel(), voice.spec.assetName)
                }
                .forEach(::add)
            popsAndBangsVoice?.let { voice ->
                if (
                    voice.isAudible &&
                    voice.gain > SILENCE_GAIN &&
                    (!anyLayerSolo || target.layerMix[voice.spec.id]?.solo == true)
                ) {
                    add(PlayingSampleLabel(voice.spec.playingRoleLabel(), voice.spec.assetName))
                }
            }
            sharedShiftUpVoice?.let { voice ->
                if (
                    voice.isAudible &&
                    voice.gain > SILENCE_GAIN &&
                    (!anyLayerSolo || target.layerMix[voice.spec.id]?.solo == true)
                ) {
                    add(PlayingSampleLabel(voice.spec.playingRoleLabel(), voice.spec.assetName))
                }
            }
            sharedShiftDownVoice?.let { voice ->
                if (
                    voice.isAudible &&
                    voice.gain > SILENCE_GAIN &&
                    (!anyLayerSolo || target.layerMix[voice.spec.id]?.solo == true)
                ) {
                    add(PlayingSampleLabel(voice.spec.playingRoleLabel(), voice.spec.assetName))
                }
            }
        }
    }

    private fun isProgramAudible(target: EngineAudioFrame): Boolean =
        target.enabled && enabledGain > SILENCE_GAIN && masterGain > SILENCE_GAIN

    private fun updateEffectTargetsAndTriggers(
        target: EngineAudioFrame,
        layerMix: Map<String, LayerMixControl>,
        blockSeconds: Double,
    ) {
        val normalizedRpm = ((smoothedRpm - profile.idleRpm) / (profile.limiterRpm - profile.idleRpm))
            .coerceIn(0.0, 1.0)
        val turboGain = if (target.turboSoundsEnabled) {
            target.turboSoundsGain.coerceIn(
                EngineAudioFrame.MIN_TURBO_SOUNDS_GAIN,
                EngineAudioFrame.MAX_EFFECT_GAIN,
            )
        } else {
            0.0
        }

        val previousShift = lastShiftSerial
        if (previousShift == null) {
            lastShiftSerial = target.shiftSerial
            if (target.shiftDirection != 0) {
                val trigger = if (target.shiftDirection > 0) {
                    SampleEffectTrigger.SHIFT_UP
                } else {
                    SampleEffectTrigger.SHIFT_DOWN
                }
                triggerShiftOneShots(trigger, smoothedRpm, layerMix, target)
            }
        } else if (target.shiftSerial != previousShift) {
            lastShiftSerial = target.shiftSerial
            val trigger = if (target.shiftDirection > 0) {
                SampleEffectTrigger.SHIFT_UP
            } else {
                SampleEffectTrigger.SHIFT_DOWN
            }
            triggerShiftOneShots(trigger, smoothedRpm, layerMix, target)
        }

        val turboDumped = turboSpool.consumeDumpPulse()
        if (turboDumped && turboGain > 0.0) {
            effectVoices.forEach { voice ->
                if (voice.spec.trigger == SampleEffectTrigger.TURBO_FLUTTER) {
                    voice.restartAtLoop()
                }
            }
            triggerOneShots(
                SampleEffectTrigger.TURBO_DUMP,
                smoothedRpm,
                layerMix,
                target.loadOnlyProgram,
                gainMultiplier = turboGain,
            )
        }

        if (target.throttleLiftEffectsEnabled) {
            when {
                target.throttle >= THROTTLE_LIFT_ARM_LEVEL -> {
                    throttleLiftSustainedSeconds += blockSeconds
                    throttleLiftQualified = throttleLiftSustainedSeconds >= THROTTLE_LIFT_MINIMUM_DURATION_SECONDS
                    throttleLiftDelayRemainingSeconds = 0.0
                }
                target.throttle <= THROTTLE_LIFT_FIRE_LEVEL -> {
                    if (throttleLiftQualified) {
                        throttleLiftDelayRemainingSeconds = THROTTLE_LIFT_EFFECT_DELAY_SECONDS
                    }
                    throttleLiftSustainedSeconds = 0.0
                    throttleLiftQualified = false
                }
                else -> {
                    // Keep an intentional pull armed through normal pedal modulation; only a full lift resets it.
                }
            }

            if (throttleLiftDelayRemainingSeconds > 0.0) {
                throttleLiftDelayRemainingSeconds -= blockSeconds
                if (throttleLiftDelayRemainingSeconds <= 0.0) {
                    throttleLiftDelayRemainingSeconds = 0.0
                    triggerThrottleLiftOneShots(smoothedRpm, layerMix, target)
                }
            }
        } else {
            throttleLiftSustainedSeconds = 0.0
            throttleLiftQualified = false
            stopThrottleLiftOneShots()
        }

        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            val authoredGain = when {
                voice.spec.isNativeExhaustOverrun() &&
                    (!target.throttleLiftEffectsEnabled || target.popsAndBangsEnabled) -> 0.0
                voice.spec.isNativeGearChange() && target.sharedShiftSoundsEnabled -> 0.0
                voice.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP -> {
                    if (target.transmissionEnabled) {
                        voice.baseGain * target.transmissionGain.coerceIn(
                            EngineAudioFrame.MIN_EFFECT_GAIN,
                            EngineAudioFrame.MAX_EFFECT_GAIN,
                        ) * (0.12 + normalizedRpm * 0.88) * (0.55 + smoothedThrottle * 0.45)
                    } else {
                        0.0
                    }
                }
                voice.spec.trigger == SampleEffectTrigger.TURBO_LOOP -> {
                    voice.baseGain * turboSpool.whistleGain() * turboGain
                }
                voice.spec.trigger == SampleEffectTrigger.TURBO_FLUTTER -> {
                    voice.baseGain * turboSpool.flutterGain() * turboGain
                }
                voice.spec.trigger == SampleEffectTrigger.TURBO_DUMP -> {
                    if (voice.isOneShotActive) {
                        voice.baseGain * turboGain
                    } else {
                        0.0
                    }
                }
                else -> {
                    if (voice.isOneShotActive) {
                        voice.baseGain
                    } else {
                        0.0
                    }
                }
            }
            voice.targetGain = applyLayerMix(voice.spec.id, authoredGain, layerMix, target.loadOnlyProgram)
            if (voice.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP) {
                voice.phaseIncrement = voice.sampleRate.toDouble() / outputSampleRate *
                    (0.55 + normalizedRpm * 1.25)
            }
            if (voice.spec.trigger == SampleEffectTrigger.TURBO_LOOP) {
                voice.phaseIncrement = voice.sampleRate.toDouble() / outputSampleRate *
                    turboSpool.whistlePlaybackRatio()
            }
            if (voice.spec.trigger == SampleEffectTrigger.TURBO_FLUTTER) {
                voice.phaseIncrement = voice.sampleRate.toDouble() / outputSampleRate *
                    turboSpool.flutterPlaybackRatio()
            }
            effectIndex += 1
        }
        popsAndBangsVoice?.let { voice ->
            val authoredGain = if (
                target.throttleLiftEffectsEnabled &&
                    target.popsAndBangsEnabled &&
                    voice.isOneShotActive
            ) {
                voice.baseGain * target.popsAndBangsGain.coerceIn(
                    EngineAudioFrame.MIN_EFFECT_GAIN,
                    EngineAudioFrame.MAX_EFFECT_GAIN,
                )
            } else {
                0.0
            }
            voice.targetGain = applyLayerMix(
                voice.spec.id,
                authoredGain,
                layerMix,
                target.loadOnlyProgram,
            )
        }
        sharedShiftUpVoice?.let { voice ->
            val authoredGain = if (target.sharedShiftSoundsEnabled && voice.isOneShotActive) {
                voice.baseGain * target.sharedShiftSoundsGain.coerceIn(
                    EngineAudioFrame.MIN_EFFECT_GAIN,
                    EngineAudioFrame.MAX_EFFECT_GAIN,
                )
            } else {
                0.0
            }
            voice.targetGain = applyLayerMix(
                voice.spec.id,
                authoredGain,
                layerMix,
                target.loadOnlyProgram,
            )
        }
        sharedShiftDownVoice?.let { voice ->
            val authoredGain = if (target.sharedShiftSoundsEnabled && voice.isOneShotActive) {
                voice.baseGain * target.sharedShiftSoundsGain.coerceIn(
                    EngineAudioFrame.MIN_EFFECT_GAIN,
                    EngineAudioFrame.MAX_EFFECT_GAIN,
                )
            } else {
                0.0
            }
            voice.targetGain = applyLayerMix(
                voice.spec.id,
                authoredGain,
                layerMix,
                target.loadOnlyProgram,
            )
        }
    }

    private fun triggerThrottleLiftOneShots(
        rpm: Double,
        layerMix: Map<String, LayerMixControl>,
        target: EngineAudioFrame,
    ) {
        if (rpm < SharedPopsAndBangs.effectSpec.minimumRpm) {
            return
        }
        if (isThrottleLiftOneShotActive()) {
            return
        }

        if (target.popsAndBangsEnabled) {
            val voice = popsAndBangsVoice ?: return
            val authoredGain = voice.baseGain * target.popsAndBangsGain.coerceIn(
                EngineAudioFrame.MIN_EFFECT_GAIN,
                EngineAudioFrame.MAX_EFFECT_GAIN,
            )
            if (applyLayerMix(voice.spec.id, authoredGain, layerMix, target.loadOnlyProgram) > SILENCE_GAIN) {
                if (voice.trigger()) {
                    effectTriggers += 1
                }
            }
            return
        }

        triggerOneShots(
            SampleEffectTrigger.THROTTLE_LIFT,
            rpm,
            layerMix,
            target.loadOnlyProgram,
        )
    }

    private fun isThrottleLiftOneShotActive(): Boolean {
        if (popsAndBangsVoice?.isOneShotActive == true) {
            return true
        }
        return effectVoices.any { voice ->
            voice.spec.trigger == SampleEffectTrigger.THROTTLE_LIFT && voice.isOneShotActive
        }
    }

    private fun stopThrottleLiftOneShots() {
        throttleLiftSustainedSeconds = 0.0
        throttleLiftQualified = false
        throttleLiftDelayRemainingSeconds = 0.0
        effectVoices.forEach { voice ->
            if (voice.spec.trigger == SampleEffectTrigger.THROTTLE_LIFT) {
                voice.stop()
            }
        }
        popsAndBangsVoice?.stop()
    }

    private fun triggerShiftOneShots(
        trigger: SampleEffectTrigger,
        rpm: Double,
        layerMix: Map<String, LayerMixControl>,
        target: EngineAudioFrame,
    ) {
        if (target.sharedShiftSoundsEnabled) {
            val voice = when (trigger) {
                SampleEffectTrigger.SHIFT_UP -> sharedShiftUpVoice
                SampleEffectTrigger.SHIFT_DOWN -> sharedShiftDownVoice
                else -> null
            } ?: return
            if (rpm < voice.spec.minimumRpm) {
                return
            }
            val authoredGain = voice.baseGain * target.sharedShiftSoundsGain.coerceIn(
                EngineAudioFrame.MIN_EFFECT_GAIN,
                EngineAudioFrame.MAX_EFFECT_GAIN,
            )
            if (applyLayerMix(voice.spec.id, authoredGain, layerMix, target.loadOnlyProgram) > SILENCE_GAIN) {
                if (voice.trigger()) {
                    effectTriggers += 1
                }
            }
            return
        }

        triggerOneShots(trigger, rpm, layerMix, target.loadOnlyProgram)
    }

    private fun triggerOneShots(
        trigger: SampleEffectTrigger,
        rpm: Double,
        layerMix: Map<String, LayerMixControl>,
        loadOnlyProgram: Boolean,
        gainMultiplier: Double = 1.0,
    ) {
        effectVoices.filter {
            it.spec.trigger == trigger && rpm >= it.spec.minimumRpm
        }.forEach { voice ->
            val authoredGain = voice.baseGain * gainMultiplier
            if (applyLayerMix(voice.spec.id, authoredGain, layerMix, loadOnlyProgram) > SILENCE_GAIN) {
                if (voice.trigger()) {
                    effectTriggers += 1
                }
            }
        }
    }

    private fun updateVoiceTargets(
        rpm: Double,
        throttle: Double,
        layerMix: Map<String, LayerMixControl>,
        loadProgram: Boolean,
        primaryLayerSource: PrimaryEngineLayerSource,
        loadOnlyProgram: Boolean,
        programLayerGains: ProgramLayerGains,
    ) {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            val authoredGain = voice.spec.gainAt(rpm, throttle, loadProgram, primaryLayerSource)
            val groupGain = when (voice.spec.role) {
                SampleLayerRole.LOAD -> programLayerGains.load
                SampleLayerRole.COAST -> programLayerGains.coast
                else -> 1.0
            }
            voice.targetGain = applyLayerMix(voice.spec.id, authoredGain, layerMix, loadOnlyProgram) * groupGain
            voice.playbackRatio = voice.spec.playbackRatio(rpm)
            voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate * voice.playbackRatio
            voiceIndex += 1
        }
    }

    private fun applyLayerMix(
        trackId: String,
        authoredGain: Double,
        layerMix: Map<String, LayerMixControl>,
        loadOnlyProgram: Boolean,
    ): Double {
        val mix = layerMix[trackId] ?: LayerMixControl.DEFAULT
        if (mix.muted) {
            return 0.0
        }
        if (anyLayerSolo && !mix.solo) {
            return 0.0
        }
        val multiplier = if (loadOnlyProgram) {
            mix.volume.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER)
        } else {
            LayerMixControl.DEFAULT_GAIN_MULTIPLIER
        }
        return authoredGain * multiplier
    }

    private fun buildLayerOutputMeters(target: EngineAudioFrame): List<LayerOutputMeter> {
        if (!isProgramAudible(target)) {
            return emptyList()
        }
        val loopScale = continuousProgramGain
        val meters = buildList {
            voices.forEach { voice ->
                add(LayerOutputMeter(voice.spec.id, (voice.gain * loopScale).coerceIn(0.0, 1.0)))
            }
            effectVoices.forEach { voice ->
                val level = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
                add(LayerOutputMeter(voice.spec.id, level))
            }
            popsAndBangsVoice?.let { voice ->
                val level = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
                add(LayerOutputMeter(voice.spec.id, level))
            }
            sharedShiftUpVoice?.let { voice ->
                val level = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
                add(LayerOutputMeter(voice.spec.id, level))
            }
            sharedShiftDownVoice?.let { voice ->
                val level = if (voice.isAudible) voice.gain.coerceIn(0.0, 1.0) else 0.0
                add(LayerOutputMeter(voice.spec.id, level))
            }
        }
        return meters
    }

    private class LoopVoice(val spec: SampleLayerSpec, val data: PcmLoopData) {
        var phase = 0.0
        var phaseIncrement = 1.0
        var playbackRatio = 1.0
        var gain = 0.0
        var targetGain = 0.0
        var sampleLeft = 0.0
            private set
        var sampleRight = 0.0
            private set
        private var hasLooped = false

        fun readStereoCubic() {
            val frame = phase.toInt()
            val fraction = phase - frame
            val frame0 = resolveFrame(frame - 1)
            val frame1 = resolveFrame(frame)
            val frame2 = resolveFrame(frame + 1)
            val frame3 = resolveFrame(frame + 2)
            sampleLeft = data.interpolateCubic(0, frame0, frame1, frame2, frame3, fraction)
            sampleRight = if (data.sourceChannels == 1) {
                sampleLeft
            } else {
                data.interpolateCubic(1, frame0, frame1, frame2, frame3, fraction)
            }
        }

        fun advance(): Boolean {
            phase += phaseIncrement
            if (phase < data.loopEndFrameExclusive) return false
            val loopLength = data.loopEndFrameExclusive - data.loopStartFrame
            phase = data.loopStartFrame + (phase - data.loopEndFrameExclusive) % loopLength
            hasLooped = true
            return true
        }

        private fun resolveFrame(index: Int): Int {
            val start = data.loopStartFrame
            val end = data.loopEndFrameExclusive
            val length = end - start
            return when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, data.frameCount - 1)
            }
        }
    }

    private class EffectVoice(
        val spec: SampleEffectSpec,
        private val sampleVariants: List<PcmLoopData>,
        private val outputSampleRate: Int,
    ) {
        var phase = 0.0
        var phaseIncrement = 1.0
        var gain = 0.0
        var targetGain = 0.0
        var sampleLeft = 0.0
            private set
        var sampleRight = 0.0
            private set
        private var activeSample = sampleVariants.first()
        private var active = spec.trigger.isContinuousLoop()
        private var hasLooped = false
        private var lastVariantIndex = -1
        val sampleRate = sampleVariants.first().sampleRate
        val baseGain = 10.0.pow(spec.baseGainDb / 20.0)
        val isOneShotActive: Boolean
            get() = !spec.trigger.isContinuousLoop() && active
        val isAudible: Boolean get() = active && gain > SILENCE_GAIN

        init {
            if (spec.trigger.isContinuousLoop()) {
                phase = loopStartFrame().toDouble()
            }
        }

        fun restartAtLoop() {
            phase = loopStartFrame().toDouble()
            hasLooped = false
            if (spec.trigger.isContinuousLoop()) {
                active = true
            }
        }

        private fun loopStartFrame(): Int {
            return spec.resolvedLoopStartFrame(activeSample.sampleRate, activeSample.loopStartFrame)
        }

        private fun loopEndFrameExclusive(): Int {
            return spec.resolvedLoopEndFrameExclusive(
                activeSample.sampleRate,
                activeSample.loopEndFrameExclusive,
                activeSample.frameCount,
            )
        }

        fun trigger(): Boolean {
            if (!spec.trigger.isContinuousLoop() && active) {
                return false
            }
            activeSample = pickVariantSample()
            phase = 0.0
            phaseIncrement = activeSample.sampleRate.toDouble() / outputSampleRate
            active = true
            hasLooped = false
            return true
        }

        fun stop() {
            if (spec.trigger.isContinuousLoop()) {
                return
            }

            active = false
            gain = 0.0
            targetGain = 0.0
        }

        private fun pickVariantSample(): PcmLoopData {
            if (sampleVariants.size == 1) {
                lastVariantIndex = 0
                return sampleVariants.first()
            }
            var candidateIndex = Random.nextInt(sampleVariants.size)
            while (candidateIndex == lastVariantIndex) {
                candidateIndex = Random.nextInt(sampleVariants.size)
            }
            lastVariantIndex = candidateIndex
            return sampleVariants[candidateIndex]
        }

        fun readStereoCubic() {
            val frame = phase.toInt()
            val fraction = phase - frame
            val frame0 = resolveFrame(frame - 1)
            val frame1 = resolveFrame(frame)
            val frame2 = resolveFrame(frame + 1)
            val frame3 = resolveFrame(frame + 2)
            sampleLeft = activeSample.interpolateCubic(0, frame0, frame1, frame2, frame3, fraction)
            sampleRight = if (activeSample.sourceChannels == 1) {
                sampleLeft
            } else {
                activeSample.interpolateCubic(1, frame0, frame1, frame2, frame3, fraction)
            }
        }

        fun advance(): Boolean {
            if (!active) return false
            phase += phaseIncrement
            if (!spec.trigger.isContinuousLoop()) {
                if (phase >= activeSample.frameCount - 1) {
                    active = false
                    gain = 0.0
                    targetGain = 0.0
                }
                return false
            }
            val loopEnd = loopEndFrameExclusive()
            if (phase < loopEnd) return false
            val loopStart = loopStartFrame()
            val loopLength = loopEnd - loopStart
            phase = loopStart + (phase - loopEnd) % loopLength
            hasLooped = true
            return true
        }

        private fun resolveFrame(index: Int): Int {
            if (!spec.trigger.isContinuousLoop()) {
                return index.coerceIn(0, activeSample.frameCount - 1)
            }
            val start = loopStartFrame()
            val end = loopEndFrameExclusive()
            val length = end - start
            return when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, activeSample.frameCount - 1)
            }
        }
    }

    companion object {
        fun load(
            assetSource: AudioAssetSource,
            outputSampleRate: Int,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
            perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
            loadOnlyProgram: Boolean = true,
            primaryLayerSource: PrimaryEngineLayerSource = PrimaryEngineLayerSource.LOAD,
        ): SampleEngineRenderer {
            val program = profile.program(perspective)
            val assetsToLoad = if (profile.appliesLoadOnlyProgram(loadOnlyProgram, perspective)) {
                profile.requiredAssetsForPrimarySource(primaryLayerSource, perspective)
            } else {
                profile.requiredAssetsForLoad(loadOnlyProgram, perspective)
            }
            val decoded = assetsToLoad.associateWith { assetName ->
                val path = "sample_engine/${profile.assetDirectory}/$assetName"
                assetSource.open(path).use(WavPcmDecoder::decode)
            }
            val voices = if (profile.appliesLoadOnlyProgram(loadOnlyProgram, perspective)) {
                profile.loopLayersForPrimarySource(primaryLayerSource, perspective)
            } else {
                profile.loopLayersForLoad(loadOnlyProgram, perspective)
            }.map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]))
            }
            val effects = program.effects.map { spec ->
                val samples = spec.allAssetNames.map { assetName ->
                    requireNotNull(decoded[assetName])
                }
                EffectVoice(spec, samples, outputSampleRate)
            }
            val popsSamples = SharedPopsAndBangs.assetNames.map { assetName ->
                assetSource.open(SharedPopsAndBangs.assetPath(assetName))
                    .use(WavPcmDecoder::decode)
            }
            val popsVoice = EffectVoice(SharedPopsAndBangs.effectSpec, popsSamples, outputSampleRate)
            val shiftUpSample = assetSource.open(
                SharedHuracanShiftSounds.assetPath(SharedHuracanShiftSounds.shiftUpSpec.assetName),
            ).use(WavPcmDecoder::decode)
            val shiftDownSample = assetSource.open(
                SharedHuracanShiftSounds.assetPath(SharedHuracanShiftSounds.shiftDownSpec.assetName),
            ).use(WavPcmDecoder::decode)
            val sharedShiftUpVoice = EffectVoice(
                SharedHuracanShiftSounds.shiftUpSpec,
                listOf(shiftUpSample),
                outputSampleRate,
            )
            val sharedShiftDownVoice = EffectVoice(
                SharedHuracanShiftSounds.shiftDownSpec,
                listOf(shiftDownSample),
                outputSampleRate,
            )
            val decodedBytes = decoded.values.sumOf(PcmLoopData::decodedBytes) +
                popsSamples.sumOf(PcmLoopData::decodedBytes) +
                shiftUpSample.decodedBytes +
                shiftDownSample.decodedBytes
            return SampleEngineRenderer(
                outputSampleRate,
                profile,
                perspective,
                voices,
                effects,
                popsVoice,
                sharedShiftUpVoice,
                sharedShiftDownVoice,
                decodedBytes,
            )
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PcmLoopData>,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
            perspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
            loadOnlyProgram: Boolean = false,
        ): SampleEngineRenderer {
            val program = profile.program(perspective)
            val voices = profile.loopLayersForLoad(loadOnlyProgram, perspective).map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" })
            }
            val effects = program.effects.map { spec ->
                val samples = spec.allAssetNames.map { assetName ->
                    requireNotNull(decoded[assetName]) { "Missing $assetName" }
                }
                EffectVoice(spec, samples, outputSampleRate)
            }
            val popsVoice = SharedPopsAndBangs.assetNames
                .mapNotNull { assetName -> decoded[assetName] }
                .takeIf { it.size == SharedPopsAndBangs.assetNames.size }
                ?.let { samples ->
                    EffectVoice(SharedPopsAndBangs.effectSpec, samples, outputSampleRate)
                }
            val sharedShiftUpVoice = decoded[SharedHuracanShiftSounds.shiftUpSpec.assetName]?.let { sample ->
                EffectVoice(SharedHuracanShiftSounds.shiftUpSpec, listOf(sample), outputSampleRate)
            }
            val sharedShiftDownVoice = decoded[SharedHuracanShiftSounds.shiftDownSpec.assetName]?.let { sample ->
                EffectVoice(SharedHuracanShiftSounds.shiftDownSpec, listOf(sample), outputSampleRate)
            }
            return SampleEngineRenderer(
                outputSampleRate,
                profile,
                perspective,
                voices,
                effects,
                popsVoice,
                sharedShiftUpVoice,
                sharedShiftDownVoice,
                decoded.values.sumOf(PcmLoopData::decodedBytes),
            )
        }

        private const val SAMPLE_HEADROOM = 0.65
        private const val PROGRAM_CHANNELS = 2
        private const val SILENCE_GAIN = 0.00001
        /** A deliberate pull is at least 40% pedal; its time may accumulate across pedal modulation. */
        private const val THROTTLE_LIFT_ARM_LEVEL = 0.40
        private const val THROTTLE_LIFT_FIRE_LEVEL = 0.08
        private const val THROTTLE_LIFT_MINIMUM_DURATION_SECONDS = 1.0
        /** Gives the engine a short release before its exhaust event, independent of turbo hardware. */
        private const val THROTTLE_LIFT_EFFECT_DELAY_SECONDS = 0.18
    }
}

private fun PcmLoopData.interpolateCubic(
    channel: Int,
    frame0: Int,
    frame1: Int,
    frame2: Int,
    frame3: Int,
    fraction: Double,
): Double {
    val y0 = sampleAt(channel, frame0).toDouble()
    val y1 = sampleAt(channel, frame1).toDouble()
    val y2 = sampleAt(channel, frame2).toDouble()
    val y3 = sampleAt(channel, frame3).toDouble()
    val a0 = y3 - y2 - y0 + y1
    val a1 = y0 - y1 - a0
    val a2 = y2 - y0
    return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
}

private fun transparentLimit(value: Double): Double = value.coerceIn(-1.0, 1.0)
private fun toPcm16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
