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

enum class AudioFocusEvent {
    TRANSIENT_LOSS,
    TRANSIENT_GAIN,
    TRANSIENT_DUCK,
    PERMANENT_LOSS,
}

/** Streams the stereo sample-bank program to the vehicle media route. */
class EngineAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val parameters = AtomicReference(EngineAudioFrame())
    private val selectedProfile = AtomicReference(EngineSampleProfiles.default)
    private val loadedSampleProfileId = AtomicReference<String?>(null)
    private val coastLayerMixEnabled = AtomicBoolean(true)
    private val focusMultiplier = AtomicReference(0.0)
    private val focusHeld = AtomicBoolean(false)
    private val renderThread = AtomicReference<Thread?>(null)
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    @Volatile
    private var layerMeterBus: RealtimeLayerMeterBus? = null

    @Volatile
    private var focusChangeListener: ((AudioFocusEvent) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_GAIN)
                    focusMultiplier.set(1.0)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_DUCK)
                focusMultiplier.set(0.20)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_LOSS)
                focusMultiplier.set(0.0)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusChangeListener?.invoke(AudioFocusEvent.PERMANENT_LOSS)
                focusMultiplier.set(0.0)
                focusHeld.set(false)
                synchronized(lifecycleLock) {
                    if (running.get() || renderThread.get() != null) {
                        stopLocked()
                    }
                }
            }
        }
    }

    fun layerOutputMeters(): List<LayerOutputMeter> = layerMeterBus?.snapshot().orEmpty()

    fun loadedSampleProfileId(): String? = loadedSampleProfileId.get()

    fun update(frame: EngineAudioFrame) {
        parameters.set(frame)
    }

    fun setFocusChangeListener(listener: ((AudioFocusEvent) -> Unit)?) {
        focusChangeListener = listener
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
            loadedSampleProfileId.set(null)
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
            return
        }

        focusHeld.set(true)
        focusMultiplier.set(1.0)
        running.set(true)
        val runId = generation.incrementAndGet()
        val thread = Thread({ renderLoop(runId, sampleProfile) }, "engine-audio-renderer").apply { isDaemon = true }
        renderThread.set(thread)
        try {
            thread.start()
        } catch (throwable: Throwable) {
            renderThread.compareAndSet(thread, null)
            running.set(false)
            focusMultiplier.set(0.0)
            abandonFocusIfHeld()
        }
    }

    /** Must be called with [lifecycleLock] held. */
    private fun stopLocked(): Boolean {
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
        loadedSampleProfileId.set(null)
        abandonFocusIfHeld()
        return stopped
    }

    private fun renderLoop(runId: Long, sampleProfile: EngineSampleProfile) {
        var opened: OpenedTrack? = null

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
            } catch (_: Throwable) {
                return
            }
            // Exercise decode/mix/resampling code before AudioTrack starts so first-use class
            // loading and JIT work cannot starve the newly opened output buffer.
            val warmup = ShortArray(512)
            repeat(3) { sampleRenderer.render(parameters.get(), warmup, gain = 0.0) }
            if (!isCurrent(runId)) return
            loadedSampleProfileId.set(sampleProfile.id)
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val active = openTrack(sampleRate) ?: return
            opened = active
            if (!activeTrack.compareAndSet(null, active.track)) {
                throw IllegalStateException("another AudioTrack is still active")
            }
            if (!isCurrent(runId)) return

            // Queue one silent stereo burst before play() so first-use routing does not underrun.
            val primingBuffer = ShortArray(active.framesPerWrite * STEREO_CHANNEL_COUNT)
            if (!writeFully(active.track, primingBuffer, runId)) return
            if (!isCurrent(runId)) return
            active.track.play()
            if (active.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("AudioTrack did not enter PLAYING state")
            }

            val track = active.track
            val stereoProgram = ShortArray(active.framesPerWrite * STEREO_CHANNEL_COUNT)
            var writes = 0
            var lastUnderruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
            var effectiveBufferFrames = if (Build.VERSION.SDK_INT >= 24) {
                track.bufferSizeInFrames
            } else {
                active.capacityFrames
            }
            val meterBus = RealtimeLayerMeterBus(sampleRenderer.meterTrackIds)
            layerMeterBus = meterBus
            while (isCurrent(runId)) {
                val frame = parameters.get()
                sampleRenderer.render(frame, stereoProgram, focusMultiplier.get())
                if (!writeFully(track, stereoProgram, runId)) break
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
        } catch (_: Throwable) {
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
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun openTrack(sampleRate: Int): OpenedTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null

        val framesPerBurst = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.coerceIn(64, 2_048)
            ?: 256
        val bytesPerFrame = STEREO_CHANNEL_COUNT * Short.SIZE_BYTES
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
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
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

    private data class OpenedTrack(
        val track: AudioTrack,
        val framesPerWrite: Int,
        val capacityFrames: Int,
    )
    private companion object {
        const val RENDER_JOIN_TIMEOUT_MS = 750L
        const val RENDER_FORCE_RELEASE_JOIN_MS = 250L
        const val METER_PUBLISH_WRITE_INTERVAL = 3
        const val UNDERRUN_CHECK_WRITE_INTERVAL = 12
        const val STEREO_CHANNEL_COUNT = 2
    }
}
