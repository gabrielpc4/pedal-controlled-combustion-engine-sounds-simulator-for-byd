package com.gabrielpc.enginesoundsimulator.audio

import android.content.res.AssetManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class SampleRendererDiagnostics(
    val profileId: String = SampleEngineManifest.PROFILE_ID,
    val loadedLoops: Int = 0,
    val decodedBytes: Long = 0,
    val mappedAudioRpm: Int = 0,
    val loadGainDb: Double = -120.0,
    val coastGainDb: Double = -120.0,
    val activeLayers: String = "none",
    val framesRendered: Long = 0,
    val loopWraps: Long = 0,
    val peak: Double = 0.0,
    val overRangeSamples: Long = 0,
)

internal class SampleEngineRenderer private constructor(
    private val outputSampleRate: Int,
    private val voices: List<LoopVoice>,
    private val decodedBytes: Long,
) {
    private val voicesByTrack = SampleTrack.entries.associateWith { track ->
        voices.filter { it.spec.track == track }
    }
    private var smoothedRpm = 950.0
    private var smoothedThrottle = 0.0
    private var masterGain = 0.0
    private var enabledGain = 0.0
    private var framesRendered = 0L
    private var loopWraps = 0L
    private var overRangeSamples = 0L
    private var renderedBlocks = 0L
    private var diagnostics = SampleRendererDiagnostics(
        loadedLoops = voices.size,
        decodedBytes = decodedBytes,
    )

    fun diagnostics(): SampleRendererDiagnostics = diagnostics

    fun render(target: EngineAudioFrame, output: ShortArray, gain: Double) {
        val blockSeconds = output.size.toDouble() / outputSampleRate
        val rpmAlpha = 1.0 - exp(-blockSeconds / 0.016)
        val throttleAlpha = 1.0 - exp(-blockSeconds / 0.010)
        smoothedRpm += (target.rpm.coerceAtLeast(250.0) - smoothedRpm) * rpmAlpha
        smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * throttleAlpha

        val redline = target.redlineRpm.coerceAtLeast(1_000.0)
        val audioRpm = (smoothedRpm / redline * SampleEngineManifest.AUTHORED_MAX_RPM)
            .coerceIn(150.0, SampleEngineManifest.AUTHORED_MAX_RPM)
        val loadDb = automationDecibels(SampleEngineManifest.loadThrottleCurve, smoothedThrottle)
        val coastDb = automationDecibels(SampleEngineManifest.coastThrottleCurve, smoothedThrottle)
        val extraDb = automationDecibels(SampleEngineManifest.extraThrottleCurve, smoothedThrottle)
        val trackGains = doubleArrayOf(dbToGain(loadDb), dbToGain(coastDb), dbToGain(extraDb))

        updateVoiceTargets(audioRpm, trackGains)
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
                    if (voice.advance()) loopWraps += 1
                }
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
            val active = voices
                .asSequence()
                .filter { it.targetGain > 0.006 }
                .sortedByDescending { it.targetGain }
                .take(6)
                .joinToString(",") { voice ->
                    "${voice.spec.id}@${(voice.playbackRatio * 100.0).toInt()}%/${(voice.targetGain * 100.0).toInt()}%"
                }
                .ifBlank { "none" }
            diagnostics = SampleRendererDiagnostics(
                loadedLoops = voices.size,
                decodedBytes = decodedBytes,
                mappedAudioRpm = audioRpm.toInt(),
                loadGainDb = loadDb,
                coastGainDb = coastDb,
                activeLayers = active,
                framesRendered = framesRendered,
                loopWraps = loopWraps,
                peak = blockPeak,
                overRangeSamples = overRangeSamples,
            )
        }
    }

    private fun updateVoiceTargets(audioRpm: Double, trackGains: DoubleArray) {
        for (track in SampleTrack.entries) {
            val trackVoices = voicesByTrack.getValue(track)
            var squaredWeightSum = 0.0
            for (index in trackVoices.indices) {
                val voice = trackVoices[index]
                voice.rawWeight = regionWeight(trackVoices, index, audioRpm)
                squaredWeightSum += voice.rawWeight * voice.rawWeight
            }
            // Keep the selected track at a stable level even where the recovered trigger regions
            // overlap asymmetrically or one neighboring region ends before another.
            val normalization = sqrt(squaredWeightSum).coerceAtLeast(0.000001)
            for (index in trackVoices.indices) {
                val voice = trackVoices[index]
                voice.targetGain = voice.rawWeight / normalization * trackGains[track.ordinal]
                voice.playbackRatio = voice.spec.playbackRatio(audioRpm)
                voice.phaseIncrement = voice.data.sampleRate.toDouble() / outputSampleRate * voice.playbackRatio
            }
        }
    }

    private fun regionWeight(trackVoices: List<LoopVoice>, index: Int, rpm: Double): Double {
        val current = trackVoices[index].spec
        if (rpm < current.startRpm || rpm > current.endRpm) return 0.0
        var weight = 1.0
        val previous = trackVoices.getOrNull(index - 1)?.spec
        if (previous != null && previous.endRpm > current.startRpm && rpm < previous.endRpm) {
            val progress = ((rpm - current.startRpm) / (previous.endRpm - current.startRpm)).coerceIn(0.0, 1.0)
            weight *= sin(progress * PI * 0.5)
        }
        val next = trackVoices.getOrNull(index + 1)?.spec
        if (next != null && next.startRpm < current.endRpm && rpm > next.startRpm) {
            val progress = ((rpm - next.startRpm) / (current.endRpm - next.startRpm)).coerceIn(0.0, 1.0)
            weight *= cos(progress * PI * 0.5)
        }
        return weight
    }

    private class LoopVoice(val spec: SampleLoopSpec, val data: PcmLoopData) {
        var phase = 0.0
        var phaseIncrement = 1.0
        var playbackRatio = 1.0
        var gain = 0.0
        var targetGain = 0.0
        var rawWeight = 0.0

        fun readCubic(): Double {
            val samples = data.monoSamples
            val frame = phase.toInt()
            val fraction = phase - frame
            val y0 = samples[wrap(frame - 1)].toDouble()
            val y1 = samples[frame].toDouble()
            val y2 = samples[wrap(frame + 1)].toDouble()
            val y3 = samples[wrap(frame + 2)].toDouble()
            val a0 = y3 - y2 - y0 + y1
            val a1 = y0 - y1 - a0
            val a2 = y2 - y0
            return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
        }

        fun advance(): Boolean {
            phase += phaseIncrement
            if (phase < data.monoSamples.size) return false
            phase %= data.monoSamples.size.toDouble()
            return true
        }

        private fun wrap(index: Int): Int {
            val size = data.monoSamples.size
            val wrapped = index % size
            return if (wrapped < 0) wrapped + size else wrapped
        }
    }

    companion object {
        fun load(assetManager: AssetManager, outputSampleRate: Int): SampleEngineRenderer {
            val voices = SampleEngineManifest.loops.map { spec ->
                val data = assetManager.open("sample_engine/${spec.assetName}", AssetManager.ACCESS_STREAMING)
                    .use(WavPcmDecoder::decode)
                LoopVoice(spec, data)
            }
            val decodedBytes = voices.sumOf { it.data.monoSamples.size.toLong() * Float.SIZE_BYTES }
            return SampleEngineRenderer(outputSampleRate, voices, decodedBytes)
        }

        internal fun fromDecoded(
            outputSampleRate: Int,
            decoded: Map<String, PcmLoopData>,
        ): SampleEngineRenderer {
            val voices = SampleEngineManifest.loops.map { spec ->
                LoopVoice(spec, requireNotNull(decoded[spec.assetName]) { "Missing ${spec.assetName}" })
            }
            return SampleEngineRenderer(
                outputSampleRate,
                voices,
                voices.sumOf { it.data.monoSamples.size.toLong() * Float.SIZE_BYTES },
            )
        }

        private const val SAMPLE_HEADROOM = 0.42
        private const val SILENCE_GAIN = 0.00001
        private const val DIAGNOSTIC_BLOCK_INTERVAL = 10L
    }
}

private fun dbToGain(decibels: Double): Double = 10.0.pow(decibels / 20.0)

private fun softClip(value: Double): Double = value / (1.0 + abs(value))
