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
    private var framesRendered = 0L
    private var loopWraps = 0L
    private var overRangeSamples = 0L
    private var renderedBlocks = 0L
    private var effectTriggers = 0L
    private var lastShiftSerial: Long? = null
    private var throttleLiftArmed = false
    private var diagnostics = SampleRendererDiagnostics(
        profileId = profile.id,
        loadedLoops = voices.size,
        loadedEffects = effectVoices.size,
        decodedBytes = decodedBytes,
    )

    fun diagnostics(): SampleRendererDiagnostics = diagnostics

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double) {
        require(output.size % PROGRAM_CHANNELS == 0) { "Stereo render buffer must contain whole frames" }
        val frameCount = output.size / PROGRAM_CHANNELS
        val blockSeconds = frameCount.toDouble() / outputSampleRate
        val rpmAlpha = 1.0 - exp(-blockSeconds / RPM_RESPONSE_SECONDS)
        val throttleAlpha = 1.0 - exp(-blockSeconds / THROTTLE_RESPONSE_SECONDS)
        val requestedRpm = target.rpm.coerceIn(profile.minimumRpm, profile.maximumRpm)
        smoothedRpm += (requestedRpm - smoothedRpm) * rpmAlpha
        smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * throttleAlpha

        updateVoiceTargets(smoothedRpm, smoothedThrottle)
        updateEffectTargetsAndTriggers(target)
        val targetMaster = (gain * target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72).coerceIn(0.0, 1.5)
        val targetProfileOutputGain = profile.outputGainAt(smoothedThrottle)
        val targetEnabled = if (target.enabled) 1.0 else 0.0
        val masterAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.008))
        val profileGainAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.008))
        val enabledAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.010))
        val layerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.012))
        var blockPeak = 0.0

        for (frameIndex in 0 until frameCount) {
            var mixedLeft = 0.0
            var mixedRight = 0.0
            for (voice in voices) {
                voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                if (voice.gain > SILENCE_GAIN || voice.targetGain > SILENCE_GAIN) {
                    mixedLeft += voice.readCubic(0) * voice.gain
                    mixedRight += voice.readCubic(1) * voice.gain
                }
                // FMOD timelines keep running even while a layer is inaudible. Doing the same
                // prevents an audible sample restart when an RPM or throttle fade opens again.
                if (voice.advance()) loopWraps += 1
            }
            for (voice in effectVoices) {
                voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                if (voice.isAudible) {
                    mixedLeft += voice.readCubic(0) * voice.gain
                    mixedRight += voice.readCubic(1) * voice.gain
                }
                if (voice.advance()) loopWraps += 1
            }
            masterGain += (targetMaster - masterGain) * masterAlpha
            profileOutputGain += (targetProfileOutputGain - profileOutputGain) * profileGainAlpha
            enabledGain += (targetEnabled - enabledGain) * enabledAlpha
            val commonGain = SAMPLE_HEADROOM * masterGain * profileOutputGain * enabledGain
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
        renderedBlocks += 1
        if (renderedBlocks % DIAGNOSTIC_BLOCK_INTERVAL == 0L || diagnostics.framesRendered == 0L) {
            val active = voices.asSequence()
                .filter { it.targetGain > 0.006 }
                .sortedByDescending { it.targetGain }
                .take(8)
                .joinToString(",") { voice ->
                    "${voice.spec.id}@${(voice.playbackRatio * 100.0).toInt()}%/${(voice.targetGain * 100.0).toInt()}%"
                }
                .ifBlank { "none" }
            diagnostics = SampleRendererDiagnostics(
                profileId = profile.id,
                loadedLoops = voices.size,
                loadedEffects = effectVoices.size,
                decodedBytes = decodedBytes,
                targetRpm = requestedRpm.toInt(),
                renderRpm = smoothedRpm.toInt(),
                throttle = smoothedThrottle,
                activeLayers = active,
                framesRendered = framesRendered,
                loopWraps = loopWraps,
                peak = blockPeak,
                overRangeSamples = overRangeSamples,
                effectTriggers = effectTriggers,
                activeEffects = effectVoices.filter { it.isAudible || it.targetGain > SILENCE_GAIN }
                    .joinToString(",") { it.spec.id }
                    .ifBlank { "none" },
            )
        }
    }

    private fun updateEffectTargetsAndTriggers(target: EngineAudioFrame) {
        val mask = target.enabledEffectMask
        val normalizedRpm = ((smoothedRpm - profile.idleRpm) / (profile.limiterRpm - profile.idleRpm))
            .coerceIn(0.0, 1.0)
        effectVoices.filter { it.spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP }.forEach { voice ->
            val enabled = mask and voice.spec.control.bit != 0L
            voice.targetGain = if (enabled) {
                voice.baseGain * (0.12 + normalizedRpm * 0.88) * (0.55 + smoothedThrottle * 0.45)
            } else {
                0.0
            }
            voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate *
                (0.55 + normalizedRpm * 1.25)
        }

        val previousShift = lastShiftSerial
        if (previousShift == null) {
            lastShiftSerial = target.shiftSerial
        } else if (target.shiftSerial != previousShift) {
            lastShiftSerial = target.shiftSerial
            triggerOneShots(
                if (target.shiftDirection > 0) SampleEffectTrigger.SHIFT_UP else SampleEffectTrigger.SHIFT_DOWN,
                mask,
                smoothedRpm,
            )
        }

        if (target.throttle >= THROTTLE_LIFT_ARM_LEVEL) throttleLiftArmed = true
        if (throttleLiftArmed && target.throttle <= THROTTLE_LIFT_FIRE_LEVEL) {
            throttleLiftArmed = false
            triggerOneShots(SampleEffectTrigger.THROTTLE_LIFT, mask, smoothedRpm)
        }
    }

    private fun triggerOneShots(trigger: SampleEffectTrigger, mask: Long, rpm: Double) {
        effectVoices.filter {
            it.spec.trigger == trigger && mask and it.spec.control.bit != 0L && rpm >= it.spec.minimumRpm
        }.forEach {
            it.trigger()
            effectTriggers += 1
        }
    }

    private fun updateVoiceTargets(rpm: Double, throttle: Double) {
        for (voice in voices) {
            voice.targetGain = voice.spec.gainAt(rpm, throttle)
            voice.playbackRatio = voice.spec.playbackRatio(rpm)
            voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate * voice.playbackRatio
        }
    }

    private class LoopVoice(val spec: SampleLayerSpec, val data: PcmLoopData) {
        var phase = 0.0
        var phaseIncrement = 1.0
        var playbackRatio = 1.0
        var gain = 0.0
        var targetGain = 0.0
        private var hasLooped = false

        fun readCubic(outputChannel: Int): Double {
            val sourceChannel = outputChannel.coerceAtMost(data.sourceChannels - 1)
            val frame = phase.toInt()
            val fraction = phase - frame
            val y0 = sampleAt(sourceChannel, frame - 1).toDouble()
            val y1 = sampleAt(sourceChannel, frame).toDouble()
            val y2 = sampleAt(sourceChannel, frame + 1).toDouble()
            val y3 = sampleAt(sourceChannel, frame + 2).toDouble()
            val a0 = y3 - y2 - y0 + y1
            val a1 = y0 - y1 - a0
            val a2 = y2 - y0
            return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
        }

        fun advance(): Boolean {
            phase += phaseIncrement
            if (phase < data.loopEndFrameExclusive) return false
            val loopLength = data.loopEndFrameExclusive - data.loopStartFrame
            phase = data.loopStartFrame + (phase - data.loopEndFrameExclusive) % loopLength
            hasLooped = true
            return true
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
            return data.channelSamples[channel][resolved]
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
        private var active = spec.trigger == SampleEffectTrigger.TRANSMISSION_LOOP
        private var hasLooped = false
        val baseGain = 10.0.pow(spec.baseGainDb / 20.0)
        val isAudible: Boolean get() = active && gain > SILENCE_GAIN

        fun trigger() {
            phase = 0.0
            phaseIncrement = data.sampleRate.toDouble() / outputSampleRate
            gain = baseGain
            targetGain = baseGain
            active = true
            hasLooped = false
        }

        fun readCubic(outputChannel: Int): Double {
            val sourceChannel = outputChannel.coerceAtMost(data.sourceChannels - 1)
            val frame = phase.toInt()
            val fraction = phase - frame
            val y0 = sampleAt(sourceChannel, frame - 1).toDouble()
            val y1 = sampleAt(sourceChannel, frame).toDouble()
            val y2 = sampleAt(sourceChannel, frame + 1).toDouble()
            val y3 = sampleAt(sourceChannel, frame + 2).toDouble()
            val a0 = y3 - y2 - y0 + y1
            val a1 = y0 - y1 - a0
            val a2 = y2 - y0
            return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
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

        private fun sampleAt(channel: Int, index: Int): Float {
            if (spec.trigger != SampleEffectTrigger.TRANSMISSION_LOOP) {
                return data.channelSamples[channel][index.coerceIn(0, data.frameCount - 1)]
            }
            val start = data.loopStartFrame
            val end = data.loopEndFrameExclusive
            val length = end - start
            val resolved = when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, data.frameCount - 1)
            }
            return data.channelSamples[channel][resolved]
        }
    }

    companion object {
        fun load(
            assetManager: AssetManager,
            outputSampleRate: Int,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
        ): SampleEngineRenderer {
            val decoded = profile.requiredAssets.associateWith { assetName ->
                val path = "sample_engine/${profile.assetDirectory}/$assetName"
                assetManager.open(path, AssetManager.ACCESS_STREAMING).use(WavPcmDecoder::decode)
            }
            val voices = profile.layers.map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]))
            }
            val effects = profile.effects.map { spec ->
                EffectVoice(spec, requireNotNull(decoded[spec.assetName]), outputSampleRate)
            }
            val decodedBytes = decoded.values.sumOf {
                it.frameCount.toLong() * it.sourceChannels * Float.SIZE_BYTES
            }
            return SampleEngineRenderer(outputSampleRate, profile, voices, effects, decodedBytes)
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PcmLoopData>,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
        ): SampleEngineRenderer {
            val voices = profile.layers.map { spec ->
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
                    data.frameCount.toLong() * data.sourceChannels * Float.SIZE_BYTES
                },
            )
        }

        private const val SAMPLE_HEADROOM = 0.65
        private const val PROGRAM_CHANNELS = 2
        private const val SILENCE_GAIN = 0.00001
        private const val DIAGNOSTIC_BLOCK_INTERVAL = 10L
        private const val RPM_RESPONSE_SECONDS = 0.016
        private const val THROTTLE_RESPONSE_SECONDS = 0.010
        private const val THROTTLE_LIFT_ARM_LEVEL = 0.35
        private const val THROTTLE_LIFT_FIRE_LEVEL = 0.08
    }
}

private fun transparentLimit(value: Double): Double = value.coerceIn(-1.0, 1.0)
private fun toPcm16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
