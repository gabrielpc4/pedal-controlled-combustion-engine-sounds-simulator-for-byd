package com.gabrielpc.enginesoundsimulator.audio

import android.content.res.AssetManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

internal data class SampleRendererDiagnostics(
    val profileId: String = EngineSampleProfiles.default.id,
    val loadedLoops: Int = 0,
    val decodedBytes: Long = 0,
    val targetRpm: Int = 0,
    val renderRpm: Int = 0,
    val throttle: Double = 0.0,
    val activeLayers: String = "none",
    val framesRendered: Long = 0,
    val loopWraps: Long = 0,
    val peak: Double = 0.0,
    val overRangeSamples: Long = 0,
)

/** A profile-driven reconstruction of one FMOD engine event. */
internal class SampleEngineRenderer private constructor(
    private val outputSampleRate: Int,
    private val profile: EngineSampleProfile,
    private val voices: List<LoopVoice>,
    private val decodedBytes: Long,
) {
    private var smoothedRpm = profile.idleRpm
    private var smoothedThrottle = 0.0
    private var masterGain = 0.0
    private var enabledGain = 0.0
    private var framesRendered = 0L
    private var loopWraps = 0L
    private var overRangeSamples = 0L
    private var renderedBlocks = 0L
    private var diagnostics = SampleRendererDiagnostics(
        profileId = profile.id,
        loadedLoops = voices.size,
        decodedBytes = decodedBytes,
    )

    fun diagnostics(): SampleRendererDiagnostics = diagnostics

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double) {
        val blockSeconds = output.size.toDouble() / outputSampleRate
        val rpmAlpha = 1.0 - exp(-blockSeconds / RPM_RESPONSE_SECONDS)
        val throttleAlpha = 1.0 - exp(-blockSeconds / THROTTLE_RESPONSE_SECONDS)
        val requestedRpm = target.rpm.coerceIn(profile.minimumRpm, profile.maximumRpm)
        smoothedRpm += (requestedRpm - smoothedRpm) * rpmAlpha
        smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * throttleAlpha

        updateVoiceTargets(smoothedRpm, smoothedThrottle)
        val targetMaster = (gain * target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72).coerceIn(0.0, 1.5)
        val targetEnabled = if (target.enabled) 1.0 else 0.0
        val masterAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.008))
        val enabledAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.010))
        val layerAlpha = 1.0 - exp(-1.0 / (outputSampleRate * 0.012))
        var blockPeak = 0.0

        for (index in output.indices) {
            var mixed = 0.0
            for (voice in voices) {
                voice.gain += (voice.targetGain - voice.gain) * layerAlpha
                if (voice.gain > SILENCE_GAIN || voice.targetGain > SILENCE_GAIN) {
                    mixed += voice.readCubic() * voice.gain
                }
                // FMOD timelines keep running even while a layer is inaudible. Doing the same
                // prevents an audible sample restart when an RPM or throttle fade opens again.
                if (voice.advance()) loopWraps += 1
            }
            masterGain += (targetMaster - masterGain) * masterAlpha
            enabledGain += (targetEnabled - enabledGain) * enabledAlpha
            val preLimited = mixed * SAMPLE_HEADROOM * masterGain * enabledGain
            if (abs(preLimited) > 1.0) overRangeSamples += 1
            val limited = softClip(preLimited)
            blockPeak = max(blockPeak, abs(limited))
            output[index] = (limited.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }

        framesRendered += output.size
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
                decodedBytes = decodedBytes,
                targetRpm = requestedRpm.toInt(),
                renderRpm = smoothedRpm.toInt(),
                throttle = smoothedThrottle,
                activeLayers = active,
                framesRendered = framesRendered,
                loopWraps = loopWraps,
                peak = blockPeak,
                overRangeSamples = overRangeSamples,
            )
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

        fun readCubic(): Double {
            val frame = phase.toInt()
            val fraction = phase - frame
            val y0 = sampleAt(frame - 1).toDouble()
            val y1 = sampleAt(frame).toDouble()
            val y2 = sampleAt(frame + 1).toDouble()
            val y3 = sampleAt(frame + 2).toDouble()
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

        private fun sampleAt(index: Int): Float {
            val start = data.loopStartFrame
            val end = data.loopEndFrameExclusive
            val length = end - start
            val resolved = when {
                index >= end -> start + (index - end) % length
                hasLooped && index < start -> end - 1 - ((start - 1 - index) % length)
                else -> index.coerceIn(0, data.monoSamples.lastIndex)
            }
            return data.monoSamples[resolved]
        }
    }

    companion object {
        fun load(
            assetManager: AssetManager,
            outputSampleRate: Int,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
        ): SampleEngineRenderer {
            val voices = profile.layers.map { spec ->
                val path = "sample_engine/${profile.assetDirectory}/${spec.assetName}"
                val data = assetManager.open(path, AssetManager.ACCESS_STREAMING).use(WavPcmDecoder::decode)
                LoopVoice(spec, data)
            }
            val decodedBytes = voices.sumOf { it.data.monoSamples.size.toLong() * Float.SIZE_BYTES }
            return SampleEngineRenderer(outputSampleRate, profile, voices, decodedBytes)
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PcmLoopData>,
            profile: EngineSampleProfile = EngineSampleProfiles.default,
        ): SampleEngineRenderer {
            val voices = profile.layers.map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" })
            }
            return SampleEngineRenderer(
                outputSampleRate,
                profile,
                voices,
                voices.sumOf { it.data.monoSamples.size.toLong() * Float.SIZE_BYTES },
            )
        }

        private const val SAMPLE_HEADROOM = 0.18
        private const val SILENCE_GAIN = 0.00001
        private const val DIAGNOSTIC_BLOCK_INTERVAL = 10L
        private const val RPM_RESPONSE_SECONDS = 0.016
        private const val THROTTLE_RESPONSE_SECONDS = 0.010
    }
}

private fun softClip(value: Double): Double = value / (1.0 + abs(value))
