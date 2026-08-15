package com.gabrielpc.bydmotorsound.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.gabrielpc.bydmotorsound.diagnostics.PersistentDiagnosticLog
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
    val activeChannels: Int = 0,
    val activeLayout: String = "OFFLINE",
    val sampleRate: Int = 0,
    val framesPerWrite: Int = 0,
    val bufferFrames: Int = 0,
    val sessionId: Int = 0,
    val routedDevice: String = "none",
    val advertisedChannels: String = "unknown",
    val underruns: Int = 0,
    val focusGranted: Boolean = false,
    val error: String? = null,
)

/** Streams one synthesized engine program into every negotiated logical output channel. */
class EngineAudioEngine(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val parameters = AtomicReference(EngineAudioFrame())
    private val requestedMode = AtomicReference(AudioChannelMode.AUTO)
    private val focusMultiplier = AtomicReference(0.0)
    private val focusHeld = AtomicBoolean(false)
    private val outputState = AtomicReference(AudioOutputState())
    private val renderThread = AtomicReference<Thread?>(null)
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusMultiplier.set(1.0)
                    outputState.updateAndGet { it.copy(focusGranted = true) }
                    PersistentDiagnosticLog.event("audio_focus_gained")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusMultiplier.set(0.20)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                PersistentDiagnosticLog.warning("audio_focus_duck")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusMultiplier.set(0.0)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                PersistentDiagnosticLog.warning("audio_focus_transient_loss")
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusMultiplier.set(0.0)
                focusHeld.set(false)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                PersistentDiagnosticLog.warning("audio_focus_lost")
                synchronized(lifecycleLock) {
                    if (running.get() || renderThread.get() != null) {
                        stopLocked(error = "Audio focus lost")
                    }
                }
            }
        }
    }

    fun state(): AudioOutputState = outputState.get()

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

    private fun startLocked() {
        if (
            running.get() ||
            renderThread.get() != null ||
            activeTrack.get() != null ||
            focusHeld.get()
        ) {
            if (!stopLocked()) return
        }

        PersistentDiagnosticLog.event("audio_start_requested", "mode=${requestedMode.get().name}")
        focusMultiplier.set(0.0)
        val focusResult = runCatching { requestFocus() }
        val focusGranted = focusResult.getOrDefault(false)
        if (!focusGranted) {
            focusResult.exceptionOrNull()?.let { failure ->
                PersistentDiagnosticLog.recordThrowable(
                    "audio_focus_request_failed",
                    failure,
                    "mode=${requestedMode.get().name}",
                )
            } ?: PersistentDiagnosticLog.warning("audio_focus_denied", "mode=${requestedMode.get().name}")
            running.set(false)
            outputState.updateAndGet {
                it.copy(
                    running = false,
                    requestedMode = requestedMode.get(),
                    activeChannels = 0,
                    activeLayout = "OFFLINE",
                    focusGranted = false,
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
                activeLayout = "STARTING",
                focusGranted = true,
            ),
        )

        val thread = Thread({ renderLoop(runId) }, "engine-audio-renderer").apply { isDaemon = true }
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
                    activeLayout = "OFFLINE",
                    focusGranted = false,
                    error = "Audio renderer start failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                )
            }
            PersistentDiagnosticLog.recordThrowable("audio_renderer_start_failed", throwable)
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
        abandonFocusIfHeld()
        outputState.updateAndGet { previous ->
            previous.copy(
                running = false,
                requestedMode = requestedMode.get(),
                activeChannels = 0,
                activeLayout = "OFFLINE",
                sampleRate = 0,
                framesPerWrite = 0,
                bufferFrames = 0,
                sessionId = 0,
                routedDevice = "none",
                focusGranted = false,
                error = error ?: if (stopped) previous.error else {
                    "Audio renderer did not stop within ${RENDER_JOIN_TIMEOUT_MS + RENDER_FORCE_RELEASE_JOIN_MS} ms"
                },
            )
        }
        PersistentDiagnosticLog.event(
            "audio_stopped",
            "clean=$stopped reason=${error ?: "requested"}",
        )
        return stopped
    }

    private fun renderLoop(runId: Long) {
        val mode = requestedMode.get()
        var opened: OpenedTrack? = null
        var failure: String? = null

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val sampleRate = nativeSampleRate()
            val advertised = advertisedChannelSummary()
            val candidates = channelCandidates(mode, advertised.maxChannels)
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
                    val primingBuffer = ShortArray(candidateTrack.framesPerWrite * candidate.channelCount)
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
                        advertisedChannels = advertised.description,
                        error = failure,
                    )
                }
                PersistentDiagnosticLog.warning("audio_track_open_failed", "mode=${mode.name} error=$failure")
                return
            }

            val track = active.track
            val mono = ShortArray(active.framesPerWrite)
            val interleaved = ShortArray(active.framesPerWrite * active.layout.channelCount)
            val synthesizer = EngineSynthesizer(sampleRate)
            val duplicationGain = when (active.layout.channelCount) {
                8 -> 0.23
                6 -> 0.27
                4 -> 0.38
                else -> 0.66
            }
            var writes = 0
            outputState.updateAndGet { previous ->
                AudioOutputState(
                    running = true,
                    requestedMode = mode,
                    activeChannels = track.channelCount,
                    activeLayout = active.layout.label,
                    sampleRate = track.sampleRate,
                    framesPerWrite = active.framesPerWrite,
                    bufferFrames = if (Build.VERSION.SDK_INT >= 24) track.bufferSizeInFrames else active.capacityFrames,
                    sessionId = track.audioSessionId,
                    routedDevice = routedDeviceName(track),
                    advertisedChannels = advertised.description,
                    underruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0,
                    focusGranted = previous.focusGranted,
                )
            }
            PersistentDiagnosticLog.event(
                "audio_track_active",
                "mode=${mode.name} layout=${active.layout.label} logical_channels=${track.channelCount} " +
                    "sample_rate=${track.sampleRate} buffer_frames=" +
                    "${if (Build.VERSION.SDK_INT >= 24) track.bufferSizeInFrames else active.capacityFrames} " +
                    "route=${routedDeviceName(track)} session=${track.audioSessionId}",
            )

            while (isCurrent(runId)) {
                synthesizer.render(parameters.get(), mono, duplicationGain * focusMultiplier.get())
                duplicateAcrossChannels(mono, interleaved, active.layout.channelCount)
                if (!writeFully(track, interleaved, runId)) break
                writes += 1
                if (writes % 48 == 0) {
                    outputState.updateAndGet {
                        it.copy(
                            routedDevice = routedDeviceName(track),
                            underruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0,
                        )
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (isCurrent(runId)) {
                failure = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                PersistentDiagnosticLog.recordThrowable("audio_renderer_failed", throwable, "mode=${mode.name}")
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
                abandonFocusIfHeld()
                outputState.updateAndGet {
                    it.copy(
                        running = false,
                        activeChannels = 0,
                        activeLayout = "OFFLINE",
                        focusGranted = false,
                        error = failure ?: it.error ?: "Audio renderer stopped unexpectedly",
                    )
                }
                PersistentDiagnosticLog.warning(
                    "audio_renderer_stopped",
                    "mode=${mode.name} error=${failure ?: "unexpected_stop"}",
                )
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

    private fun nativeSampleRate(): Int = audioManager
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()
        ?.takeIf { it in 22_050..192_000 }
        ?: 48_000

    private fun advertisedChannelSummary(): AdvertisedChannels {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var maxChannels = 0
        val descriptions = devices.map { device ->
            val counts = device.channelCounts.filter { it > 0 }
            maxChannels = max(maxChannels, counts.maxOrNull() ?: 0)
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: audioDeviceTypeName(device.type)
            "$name:${if (counts.isEmpty()) "arbitrary" else counts.joinToString("/")}ch"
        }
        return AdvertisedChannels(maxChannels, descriptions.ifEmpty { listOf("no output device metadata") }.joinToString())
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
    private data class AdvertisedChannels(val maxChannels: Int, val description: String)

    private companion object {
        const val RENDER_JOIN_TIMEOUT_MS = 750L
        const val RENDER_FORCE_RELEASE_JOIN_MS = 250L
        val LAYOUT_7_1 = ChannelLayout(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 8, "7.1 MIRROR")
        val LAYOUT_5_1 = ChannelLayout(AudioFormat.CHANNEL_OUT_5POINT1, 6, "5.1 MIRROR")
        val LAYOUT_QUAD = ChannelLayout(AudioFormat.CHANNEL_OUT_QUAD, 4, "QUAD MIRROR")
        val LAYOUT_STEREO = ChannelLayout(AudioFormat.CHANNEL_OUT_STEREO, 2, "STEREO / CABIN DSP")
    }
}

internal fun duplicateAcrossChannels(mono: ShortArray, output: ShortArray, channelCount: Int) {
    require(channelCount > 0)
    require(output.size >= mono.size * channelCount)
    var outputIndex = 0
    for (sample in mono) {
        repeat(channelCount) { output[outputIndex++] = sample }
    }
}

private fun routedDeviceName(track: AudioTrack): String {
    val device = track.routedDevice ?: return "default route"
    val label = device.productName?.toString()?.takeIf { it.isNotBlank() }
        ?: audioDeviceTypeName(device.type)
    return "$label (#${device.id})"
}

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
    AudioDeviceInfo.TYPE_BUS -> "vehicle bus"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> "audio device type $type"
}
