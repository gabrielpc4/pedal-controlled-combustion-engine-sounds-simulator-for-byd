package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

enum class AudioChannelMode(val displayName: String) {
    AUTO("AUTO"),
    STEREO("STEREO"),
    QUAD("QUAD"),
    SURROUND_5_1("5.1"),
    SURROUND_7_1("7.1"),
}

data class AudioOutputState(
    val running: Boolean = false,
    val requestedMode: AudioChannelMode = AudioChannelMode.AUTO,
    val sampleStatus: String = "OFFLINE",
    val activeChannels: Int = 0,
    val sampleError: String? = null,
    val error: String? = null,
)

/** Streams the required sample-bank engine program into every negotiated logical output channel. */
class EngineAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val parameters = AtomicReference(EngineAudioFrame())
    private val selectedProfile = AtomicReference(EngineSampleProfiles.default)
    private val coastLayerMixEnabled = AtomicBoolean(true)
    private val requestedMode = AtomicReference(AudioChannelMode.AUTO)
    private val focusMultiplier = AtomicReference(0.0)
    private val focusHeld = AtomicBoolean(false)
    private val outputState = AtomicReference(
        AudioOutputState(),
    )
    private val renderThread = AtomicReference<Thread?>(null)
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    @Volatile
    private var layerMeterBus: RealtimeLayerMeterBus? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusMultiplier.set(1.0)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusMultiplier.set(0.20)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusMultiplier.set(0.0)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusMultiplier.set(0.0)
                focusHeld.set(false)
                synchronized(lifecycleLock) {
                    if (running.get() || renderThread.get() != null) {
                        stopLocked(error = "Audio focus lost")
                    }
                }
            }
        }
    }

    fun state(): AudioOutputState = outputState.get()

    fun layerOutputMeters(): List<LayerOutputMeter> = layerMeterBus?.snapshot().orEmpty()

    fun update(frame: EngineAudioFrame) {
        parameters.set(frame)
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && renderThread.get()?.isAlive == true) return
            startLocked()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    fun setChannelMode(mode: AudioChannelMode) {
        synchronized(lifecycleLock) {
            val changed = requestedMode.getAndSet(mode) != mode
            // Make the UI/control plane reflect the requested mode before a potentially slow restart.
            outputState.updateAndGet { it.copy(requestedMode = mode) }
            if (!changed) return

            val shouldRestart = running.get() || renderThread.get()?.isAlive == true
            if (shouldRestart && stopLocked()) {
                startLocked()
            }
        }
    }

    internal fun setCoastLayerMixEnabled(enabled: Boolean) {
        synchronized(lifecycleLock) {
            val changed = coastLayerMixEnabled.getAndSet(enabled) != enabled
            if (!changed) {
                return
            }
            val shouldRestart = running.get() || renderThread.get()?.isAlive == true
            if (shouldRestart && stopLocked()) {
                startLocked()
            }
        }
    }

    internal fun setSampleProfile(profile: EngineSampleProfile) {
        synchronized(lifecycleLock) {
            val changed = selectedProfile.getAndSet(profile).id != profile.id
            if (!changed) return
            val shouldRestart = running.get() || renderThread.get()?.isAlive == true
            if (shouldRestart && stopLocked()) startLocked()
        }
    }

    private fun startLocked() {
        if (
            running.get() ||
            renderThread.get() != null ||
            activeTrack.get() != null ||
            focusHeld.get()
        ) {
            if (!stopLocked()) return
        }

        val sampleProfile = selectedProfile.get()
        layerMeterBus = null
        focusMultiplier.set(0.0)
        val focusResult = runCatching { requestFocus() }
        val focusGranted = focusResult.getOrDefault(false)
        if (!focusGranted) {
            running.set(false)
            outputState.updateAndGet {
                it.copy(
                    running = false,
                    requestedMode = requestedMode.get(),
                    sampleStatus = "OFFLINE",
                    activeChannels = 0,
                    error = focusResult.exceptionOrNull()?.let { failure ->
                        "Audio focus request failed: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                    } ?: "Audio focus request denied",
                )
            }
            return
        }

        focusHeld.set(true)
        focusMultiplier.set(1.0)
        running.set(true)
        val runId = generation.incrementAndGet()
        outputState.set(
            AudioOutputState(
                running = true,
                requestedMode = requestedMode.get(),
                sampleStatus = "STARTING",
            ),
        )

        val thread = Thread({ renderLoop(runId, sampleProfile) }, "engine-audio-renderer").apply { isDaemon = true }
        renderThread.set(thread)
        try {
            thread.start()
        } catch (throwable: Throwable) {
            renderThread.compareAndSet(thread, null)
            running.set(false)
            focusMultiplier.set(0.0)
            abandonFocusIfHeld()
            outputState.updateAndGet {
                it.copy(
                    running = false,
                    sampleStatus = "OFFLINE",
                    error = "Audio renderer start failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                )
            }
        }
    }

    /** Must be called with [lifecycleLock] held. */
    private fun stopLocked(error: String? = null): Boolean {
        running.set(false)
        generation.incrementAndGet()

        val thread = renderThread.get()
        val track = activeTrack.get()
        unblockTrack(track)
        thread?.interrupt()

        var stopped = thread == null || !thread.isAlive
        val liveThread = thread?.takeIf { it.isAlive }
        if (!stopped && liveThread != null && liveThread !== Thread.currentThread()) {
            stopped = joinThread(liveThread, RENDER_JOIN_TIMEOUT_MS)
            if (!stopped) {
                // A vendor AudioTrack implementation may ignore interrupt/pause while write() blocks.
                // Release is the last-resort unblock, and the renderer's duplicate release is guarded.
                activeTrack.compareAndSet(track, null)
                runCatching { track?.release() }
                liveThread.interrupt()
                stopped = joinThread(liveThread, RENDER_FORCE_RELEASE_JOIN_MS)
            }
        }
        if (stopped && track != null && activeTrack.compareAndSet(track, null)) {
            releaseTrack(track)
        }
        if (stopped) renderThread.compareAndSet(thread, null)

        focusMultiplier.set(0.0)
        layerMeterBus = null
        abandonFocusIfHeld()
        outputState.updateAndGet { previous ->
            previous.copy(
                running = false,
                requestedMode = requestedMode.get(),
                sampleStatus = "OFFLINE",
                activeChannels = 0,
                error = error ?: if (stopped) previous.error else {
                    "Audio renderer did not stop within ${RENDER_JOIN_TIMEOUT_MS + RENDER_FORCE_RELEASE_JOIN_MS} ms"
                },
            )
        }
        return stopped
    }

    private fun renderLoop(runId: Long, sampleProfile: EngineSampleProfile) {
        val mode = requestedMode.get()
        var opened: OpenedTrack? = null
        var failure: String? = null

        try {
            // Profiles normally match their authored rate. Huracan is rendered app-side to the
            // BYD route's native 48 kHz so its 44.1 kHz bank never enters the vendor resampler.
            val sampleRate = sampleProfile.playbackSampleRate
            val sampleRenderer = try {
                SampleEngineRenderer.load(
                    appContext.assets,
                    sampleRate,
                    sampleProfile,
                    coastLayerMixEnabled = coastLayerMixEnabled.get(),
                )
            } catch (throwable: Throwable) {
                failure = "Required sample bank unavailable: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                outputState.updateAndGet {
                    it.copy(sampleStatus = "ERROR", sampleError = failure, error = failure)
                }
                return
            }
            // Exercise decode/mix/resampling code before AudioTrack starts so first-use class
            // loading and JIT work cannot starve the newly opened output buffer.
            val warmup = ShortArray(512)
            repeat(3) { sampleRenderer.render(parameters.get(), warmup, gain = 0.0) }
            if (!isCurrent(runId)) return
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val candidates = channelCandidates(mode, advertisedMaxChannels())
            var lastFailure: String? = null

            for (candidate in candidates) {
                if (!isCurrent(runId)) return
                var candidateTrack: OpenedTrack? = null
                try {
                    candidateTrack = openTrack(candidate, sampleRate)
                    if (candidateTrack == null) {
                        lastFailure = "${candidate.label}: unsupported by AudioTrack"
                        continue
                    }
                    if (!activeTrack.compareAndSet(null, candidateTrack.track)) {
                        throw IllegalStateException("another AudioTrack is still active")
                    }
                    if (!isCurrent(runId)) throw IllegalStateException("renderer cancelled")

                    // Queue a silent burst before play(). This warms the path without advancing the
                    // engine model, and prevents the predictable first-buffer startup underrun.
                    val primingBuffer = ShortArray(candidateTrack.framesPerWrite * candidate.channelCount * 2)
                    if (!writeFully(candidateTrack.track, primingBuffer, runId)) {
                        throw IllegalStateException("renderer cancelled during priming")
                    }
                    if (!isCurrent(runId)) throw IllegalStateException("renderer cancelled before play")
                    candidateTrack.track.play()
                    if (candidateTrack.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        throw IllegalStateException("AudioTrack did not enter PLAYING state")
                    }
                    opened = candidateTrack
                    break
                } catch (throwable: Throwable) {
                    candidateTrack?.track?.let { track ->
                        activeTrack.compareAndSet(track, null)
                        releaseTrack(track)
                    }
                    if (!isCurrent(runId)) return
                    lastFailure = "${candidate.label}: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                }
            }

            val active = opened
            if (active == null) {
                failure = lastFailure ?: "No AudioTrack layout initialized"
                outputState.updateAndGet {
                    it.copy(
                        requestedMode = mode,
                        error = failure,
                    )
                }
                return
            }

            val track = active.track
            val stereoProgram = ShortArray(active.framesPerWrite * 2)
            val interleaved = ShortArray(active.framesPerWrite * active.layout.channelCount)
            val duplicationGain = when (active.layout.channelCount) {
                8 -> 0.23
                6 -> 0.27
                4 -> 0.38
                else -> 1.0
            }
            var writes = 0
            var lastUnderruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
            var effectiveBufferFrames = if (Build.VERSION.SDK_INT >= 24) {
                track.bufferSizeInFrames
            } else {
                active.capacityFrames
            }
            outputState.set(
                AudioOutputState(
                    running = true,
                    requestedMode = mode,
                    sampleStatus = "ACTIVE",
                    activeChannels = track.channelCount,
                ),
            )
            val meterBus = RealtimeLayerMeterBus(sampleRenderer.meterTrackIds)
            layerMeterBus = meterBus
            while (isCurrent(runId)) {
                val frame = parameters.get()
                val renderGain = duplicationGain * focusMultiplier.get()
                sampleRenderer.render(frame, stereoProgram, renderGain)
                mapStereoAcrossChannels(stereoProgram, interleaved, active.layout.channelCount)
                if (!writeFully(track, interleaved, runId)) break
                writes += 1
                if (writes % METER_PUBLISH_WRITE_INTERVAL == 0) {
                    meterBus.publish(sampleRenderer, frame)
                }
                if (writes % UNDERRUN_CHECK_WRITE_INTERVAL == 0) {
                    val currentUnderruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
                    if (Build.VERSION.SDK_INT >= 24 && currentUnderruns > lastUnderruns) {
                        val requestedFrames = (effectiveBufferFrames + active.framesPerWrite)
                            .coerceAtMost(active.capacityFrames)
                        if (requestedFrames > effectiveBufferFrames) {
                            val appliedFrames = runCatching {
                                track.setBufferSizeInFrames(requestedFrames)
                            }.getOrDefault(effectiveBufferFrames)
                            if (appliedFrames > 0) effectiveBufferFrames = appliedFrames
                        }
                    }
                    lastUnderruns = currentUnderruns
                }
            }
        } catch (throwable: Throwable) {
            if (isCurrent(runId)) {
                failure = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            }
        } finally {
            opened?.track?.let { track ->
                activeTrack.compareAndSet(track, null)
                releaseTrack(track)
            }
            renderThread.compareAndSet(Thread.currentThread(), null)
            if (generation.get() == runId) {
                running.set(false)
                focusMultiplier.set(0.0)
                layerMeterBus = null
                abandonFocusIfHeld()
                outputState.updateAndGet {
                    it.copy(
                        running = false,
                        activeChannels = 0,
                        sampleStatus = if (failure != null) "ERROR" else "OFFLINE",
                        error = failure ?: it.error ?: "Audio renderer stopped unexpectedly",
                    )
                }
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun openTrack(layout: ChannelLayout, sampleRate: Int): OpenedTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            layout.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null

        val framesPerBurst = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.coerceIn(64, 2_048)
            ?: 256
        val bytesPerFrame = layout.channelCount * 2
        val minimumFrames = (minBytes + bytesPerFrame - 1) / bytesPerFrame
        val capacityFrames = max(minimumFrames, framesPerBurst * 4)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT in 24..25) setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(layout.channelMask)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(capacityFrames * bytesPerFrame)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { track.setBufferSizeInFrames(max(framesPerBurst * 2, minimumFrames)) }
        }
        return OpenedTrack(
            track = track,
            layout = layout,
            framesPerWrite = framesPerBurst,
            capacityFrames = capacityFrames,
        )
    }

    private fun writeFully(track: AudioTrack, samples: ShortArray, runId: Long): Boolean {
        var offset = 0
        while (offset < samples.size && isCurrent(runId)) {
            val written = track.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) throw IllegalStateException("AudioTrack.write returned $written")
            offset += written
        }
        return offset == samples.size
    }

    private fun unblockTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    private fun releaseTrack(track: AudioTrack) {
        unblockTrack(track)
        runCatching { track.release() }
    }

    private fun joinThread(thread: Thread, timeoutMs: Long): Boolean {
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    private fun advertisedMaxChannels(): Int {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var maxChannels = 0
        devices.forEach { device ->
            val counts = device.channelCounts.filter { it > 0 }
            maxChannels = max(maxChannels, counts.maxOrNull() ?: 0)
        }
        return maxChannels
    }

    private fun channelCandidates(mode: AudioChannelMode, advertisedMax: Int): List<ChannelLayout> {
        val forced = when (mode) {
            AudioChannelMode.SURROUND_7_1 -> LAYOUT_7_1
            AudioChannelMode.SURROUND_5_1 -> LAYOUT_5_1
            AudioChannelMode.QUAD -> LAYOUT_QUAD
            AudioChannelMode.STEREO -> LAYOUT_STEREO
            AudioChannelMode.AUTO -> when {
                advertisedMax >= 8 -> LAYOUT_7_1
                advertisedMax >= 6 -> LAYOUT_5_1
                advertisedMax >= 4 -> LAYOUT_QUAD
                else -> LAYOUT_STEREO
            }
        }
        val all = listOf(LAYOUT_7_1, LAYOUT_5_1, LAYOUT_QUAD, LAYOUT_STEREO)
        return listOf(forced) + all.filter { it.channelCount < forced.channelCount }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    private fun abandonFocusIfHeld() {
        if (focusHeld.compareAndSet(true, false)) {
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
    }

    private data class ChannelLayout(val channelMask: Int, val channelCount: Int, val label: String)
    private data class OpenedTrack(
        val track: AudioTrack,
        val layout: ChannelLayout,
        val framesPerWrite: Int,
        val capacityFrames: Int,
    )
    private companion object {
        const val RENDER_JOIN_TIMEOUT_MS = 750L
        const val RENDER_FORCE_RELEASE_JOIN_MS = 250L
        const val METER_PUBLISH_WRITE_INTERVAL = 3
        const val UNDERRUN_CHECK_WRITE_INTERVAL = 12
        val LAYOUT_7_1 = ChannelLayout(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 8, "7.1 MIRROR")
        val LAYOUT_5_1 = ChannelLayout(AudioFormat.CHANNEL_OUT_5POINT1, 6, "5.1 MIRROR")
        val LAYOUT_QUAD = ChannelLayout(AudioFormat.CHANNEL_OUT_QUAD, 4, "QUAD MIRROR")
        val LAYOUT_STEREO = ChannelLayout(AudioFormat.CHANNEL_OUT_STEREO, 2, "STEREO / CABIN DSP")
    }
}

internal fun mapStereoAcrossChannels(stereo: ShortArray, output: ShortArray, channelCount: Int) {
    require(channelCount == 2 || channelCount == 4 || channelCount == 6 || channelCount == 8)
    require(stereo.size % 2 == 0)
    require(output.size >= stereo.size / 2 * channelCount)
    var outputIndex = 0
    for (frame in 0 until stereo.size / 2) {
        val left = stereo[frame * 2]
        val right = stereo[frame * 2 + 1]
        val center = ((left.toInt() + right.toInt()) / 2).toShort()
        when (channelCount) {
            2 -> {
                output[outputIndex++] = left
                output[outputIndex++] = right
            }
            4 -> repeat(2) {
                output[outputIndex++] = left
                output[outputIndex++] = right
            }
            6 -> {
                output[outputIndex++] = left   // FL
                output[outputIndex++] = right  // FR
                output[outputIndex++] = center // FC
                output[outputIndex++] = center // LFE; logical mirror, OEM DSP decides bass routing
                output[outputIndex++] = left   // BL
                output[outputIndex++] = right  // BR
            }
            8 -> {
                output[outputIndex++] = left   // FL
                output[outputIndex++] = right  // FR
                output[outputIndex++] = center // FC
                output[outputIndex++] = center // LFE
                output[outputIndex++] = left   // BL
                output[outputIndex++] = right  // BR
                output[outputIndex++] = left   // SL
                output[outputIndex++] = right  // SR
            }
        }
    }
}
