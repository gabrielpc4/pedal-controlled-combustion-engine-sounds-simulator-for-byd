package com.gabrielpc.enginesoundsimulator.audio

import android.content.res.AssetManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

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

/** A profile-driven reconstruction of one FMOD engine event. */
internal class SampleEngineRenderer private constructor(
    private val outputSampleRate: Int,
    private val profile: EngineSampleProfile,
    private val voices: List<LoopVoice>,
    private val effectVoices: List<EffectVoice>,
    private val decodedBytes: Long,
) {
    private var smoothedRpm = profile.idleRpm
    private var smoothedThrottle = 0.0
    private var masterGain = 0.0
    private var profileOutputGain = profile.outputGainAt(0.0)
    private var enabledGain = 0.0
    private var continuousProgramGain = 1.0
    private var framesRendered = 0L
    private var loopWraps = 0L
    private var overRangeSamples = 0L
    private var effectTriggers = 0L
    private var lastShiftSerial: Long? = null
    private var throttleLiftArmed = false
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
            loadedEffects = effectVoices.size,
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
            activeEffects = effectVoices.asSequence()
                .filter { it.isAudible || it.targetGain > SILENCE_GAIN }
                .joinToString(",") { it.spec.id }
                .ifBlank { "none" },
        )
    }

    val meterTrackIds: List<String> = voices.map { it.spec.id } + effectVoices.map { it.spec.id }

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
        updateVoiceTargets(
            smoothedRpm,
            smoothedThrottle,
            target.layerMix,
            target.coastLayerMixEnabled,
        )
        updateEffectTargetsAndTriggers(target, target.layerMix)
        val targetMaster = (gain * target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72).coerceIn(0.0, 1.5)
        val targetProfileOutputGain = if (target.coastLayerMixEnabled) {
            profile.outputGainAt(1.0)
        } else {
            profile.outputGainAt(smoothedThrottle)
        }
        val targetEnabled = if (target.enabled) 1.0 else 0.0
        val targetContinuousProgram = 1.0
        val programFadeSeconds = target.tuning.programFadeMs / 1_000.0
        val masterAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val profileGainAlpha = 1.0 - exp(-1.0 / (outputSampleRate * programFadeSeconds))
        val enabledAlpha = 1.0 - exp(-1.0 / (outputSampleRate * (target.tuning.enabledFadeMs / 1_000.0)))
        val layerFadeSeconds = target.tuning.layerFadeMs / 1_000.0
        val idleLayerFadeSeconds = if (target.coastLayerMixEnabled) {
            COAST_IDLE_LAYER_FADE_MS / 1_000.0
        } else {
            layerFadeSeconds
        }
        val layerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * layerFadeSeconds))
        val idleLayerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * idleLayerFadeSeconds))
        var blockPeak = 0.0

        for (frameIndex in 0 until frameCount) {
            continuousProgramGain += (targetContinuousProgram - continuousProgramGain) * masterAlpha
            var loopLeft = 0.0
            var loopRight = 0.0
            var voiceIndex = 0
            while (voiceIndex < voices.size) {
                val voice = voices[voiceIndex]
                val voiceLayerAlpha = if (target.coastLayerMixEnabled && voice.spec.role == SampleLayerRole.IDLE) {
                    idleLayerAlpha
                } else {
                    layerAlpha
                }
                voice.gain += (voice.targetGain - voice.gain) * voiceLayerAlpha
                if (voice.gain > SILENCE_GAIN || voice.targetGain > SILENCE_GAIN) {
                    voice.readStereoCubic()
                    loopLeft += voice.sampleLeft * voice.gain
                    loopRight += voice.sampleRight * voice.gain
                }
                // FMOD timelines keep running even while a layer is inaudible. Doing the same
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
        }
    }

    private fun isProgramAudible(target: EngineAudioFrame): Boolean =
        target.enabled && enabledGain > SILENCE_GAIN && masterGain > SILENCE_GAIN

    private fun updateEffectTargetsAndTriggers(target: EngineAudioFrame, layerMix: Map<String, LayerMixControl>) {
        val normalizedRpm = ((smoothedRpm - profile.idleRpm) / (profile.limiterRpm - profile.idleRpm))
            .coerceIn(0.0, 1.0)

        val previousShift = lastShiftSerial
        if (previousShift == null) {
            lastShiftSerial = target.shiftSerial
        } else if (target.shiftSerial != previousShift) {
            lastShiftSerial = target.shiftSerial
            triggerOneShots(
                if (target.shiftDirection > 0) SampleEffectTrigger.SHIFT_UP else SampleEffectTrigger.SHIFT_DOWN,
                smoothedRpm,
                layerMix,
                target.coastLayerMixEnabled,
            )
        }

        if (target.throttle >= THROTTLE_LIFT_ARM_LEVEL) throttleLiftArmed = true
        if (throttleLiftArmed && target.throttle <= THROTTLE_LIFT_FIRE_LEVEL) {
            throttleLiftArmed = false
            triggerOneShots(SampleEffectTrigger.THROTTLE_LIFT, smoothedRpm, layerMix, target.coastLayerMixEnabled)
        }

        var effectIndex = 0
        while (effectIndex < effectVoices.size) {
            val voice = effectVoices[effectIndex]
            val authoredGain = when (voice.spec.trigger) {
                SampleEffectTrigger.TRANSMISSION_LOOP -> {
                    voice.baseGain * (0.12 + normalizedRpm * 0.88) * (0.55 + smoothedThrottle * 0.45)
                }
                else -> {
                    if (voice.isOneShotActive) {
                        voice.baseGain
                    } else {
                        0.0
                    }
                }
            }
            voice.targetGain = applyLayerMix(voice.spec.id, authoredGain, layerMix, target.coastLayerMixEnabled)
            if (voice.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP) {
                voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate *
                    (0.55 + normalizedRpm * 1.25)
            }
            effectIndex += 1
        }
    }

    private fun triggerOneShots(
        trigger: SampleEffectTrigger,
        rpm: Double,
        layerMix: Map<String, LayerMixControl>,
        coastLayerMixEnabled: Boolean,
    ) {
        effectVoices.filter {
            it.spec.trigger == trigger && rpm >= it.spec.minimumRpm
        }.forEach { voice ->
            val authoredGain = voice.baseGain
            if (applyLayerMix(voice.spec.id, authoredGain, layerMix, coastLayerMixEnabled) > SILENCE_GAIN) {
                voice.trigger()
                effectTriggers += 1
            }
        }
    }

    private fun updateVoiceTargets(
        rpm: Double,
        throttle: Double,
        layerMix: Map<String, LayerMixControl>,
        coastLayerMixEnabled: Boolean,
    ) {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            val authoredGain = voice.spec.gainAt(rpm, throttle, coastLayerMixEnabled)
            voice.targetGain = applyLayerMix(voice.spec.id, authoredGain, layerMix, coastLayerMixEnabled)
            voice.playbackRatio = voice.spec.playbackRatio(rpm)
            voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate * voice.playbackRatio
            voiceIndex += 1
        }
    }

    private fun applyLayerMix(
        trackId: String,
        authoredGain: Double,
        layerMix: Map<String, LayerMixControl>,
        coastLayerMixEnabled: Boolean,
    ): Double {
        val mix = layerMix[trackId] ?: LayerMixControl.DEFAULT
        if (mix.muted) {
            return 0.0
        }
        if (anyLayerSolo && !mix.solo) {
            return 0.0
        }
        val multiplier = if (coastLayerMixEnabled) {
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
        val data: PcmLoopData,
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
        private var active = spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP
        private var hasLooped = false
        val baseGain = 10.0.pow(spec.baseGainDb / 20.0)
        val isOneShotActive: Boolean
            get() = spec.trigger != SampleEffectTrigger.TRANSMISSION_LOOP && active
        val isAudible: Boolean get() = active && gain > SILENCE_GAIN

        fun trigger() {
            phase = 0.0
            phaseIncrement = data.sampleRate.toDouble() / outputSampleRate
            active = true
            hasLooped = false
        }

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
            if (!active) return false
            phase += phaseIncrement
            if (spec.trigger != SampleEffectTrigger.TRANSMISSION_LOOP) {
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

        private fun resolveFrame(index: Int): Int {
            if (spec.trigger != SampleEffectTrigger.TRANSMISSION_LOOP) {
                return index.coerceIn(0, data.frameCount - 1)
            }
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

    companion object {
        fun load(
            assetManager: AssetManager,
            outputSampleRate: Int,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
            coastLayerMixEnabled: Boolean = true,
        ): SampleEngineRenderer {
            val assetsToLoad = profile.requiredAssetsForLoad(coastLayerMixEnabled)
            val decoded = assetsToLoad.associateWith { assetName ->
                val path = "sample_engine/${profile.assetDirectory}/$assetName"
                assetManager.open(path, AssetManager.ACCESS_STREAMING).use(WavPcmDecoder::decode)
            }
            val voices = profile.loopLayersForLoad(coastLayerMixEnabled).map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]))
            }
            val effects = profile.effects.map { spec ->
                EffectVoice(spec, requireNotNull(decoded[spec.assetName]), outputSampleRate)
            }
            val decodedBytes = decoded.values.sumOf(PcmLoopData::decodedBytes)
            return SampleEngineRenderer(outputSampleRate, profile, voices, effects, decodedBytes)
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PcmLoopData>,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
            coastLayerMixEnabled: Boolean = false,
        ): SampleEngineRenderer {
            val voices = profile.loopLayersForLoad(coastLayerMixEnabled).map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" })
            }
            val effects = profile.effects.map { spec ->
                EffectVoice(
                    spec,
                    requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" },
                    outputSampleRate,
                )
            }
            return SampleEngineRenderer(
                outputSampleRate,
                profile,
                voices,
                effects,
                profile.requiredAssets.sumOf { asset ->
                    val data = requireNotNull(decoded[asset]) { "Missing $asset" }
                    data.decodedBytes
                },
            )
        }

        private const val SAMPLE_HEADROOM = 0.65
        private const val PROGRAM_CHANNELS = 2
        private const val SILENCE_GAIN = 0.00001
        private const val THROTTLE_LIFT_ARM_LEVEL = 0.35
        private const val THROTTLE_LIFT_FIRE_LEVEL = 0.08
        private const val COAST_IDLE_LAYER_FADE_MS = 120.0
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
