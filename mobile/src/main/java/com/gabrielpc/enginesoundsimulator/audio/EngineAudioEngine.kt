package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.util.Log
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugTelemetry
import com.gabrielpc.enginesoundsimulator.simulation.nativeFmodSpatialCoordinates
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class AudioFocusEvent {
    TRANSIENT_LOSS,
    TRANSIENT_GAIN,
    TRANSIENT_DUCK,
    PERMANENT_LOSS,
}

/**
 * Runs the original Assetto Studio graph. The simulation owns every physical
 * signal; this class only transfers the latest fixed-step frame to FMOD.
 */
class EngineAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val bankResolver = FmodBankResolver(appContext)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val parameters = AtomicReference(EngineAudioFrame())
    private val selectedProfile = AtomicReference(FmodBankProfiles.default)
    private val soundPerspective = AtomicReference(EngineSoundPerspective.CABIN)
    private val loadedBankProfileId = AtomicReference<String?>(null)
    private val loadFailure = AtomicReference<AudioLoadFailure?>(null)
    private val focusHeld = AtomicBoolean(false)
    private val controlThread = AtomicReference<Thread?>(null)
    private val nativeSources = AtomicReference<List<FmodSourceState>>(emptyList())
    private val limiterPulseSerial = AtomicLong(0L)
    private val backfirePulseSerial = AtomicLong(0L)
    private val rejectedShiftSerial = AtomicLong(0L)
    private val tractionPulseSerial = AtomicLong(0L)
    private val hostEngineGain = AtomicReference(1.0f)
    private val hostEffectsGain = AtomicReference(2.0f)
    private val categoryGains = AtomicReference(AudioMixGains())
    private val nativeEventMutes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val nativeEventSolos = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Set by lifecycle/UI code; consumed only by the serialized native control worker. */
    private val clearNativeEventOverrides = AtomicBoolean(false)

    @Volatile
    private var focusChangeListener: ((AudioFocusEvent) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_GAIN)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_DUCK)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_LOSS)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld.set(false)
                focusChangeListener?.invoke(AudioFocusEvent.PERMANENT_LOSS)
                synchronized(lifecycleLock) { stopLocked() }
            }
        }
    }

    fun sourceSnapshots(): List<FmodSourceState> = nativeSources.get()

    fun setHostGains(engine: Float, effects: Float) {
        hostEngineGain.set(engine.coerceAtLeast(0f))
        hostEffectsGain.set(effects.coerceAtLeast(0f))
    }

    internal fun setCategoryGains(gains: AudioMixGains) {
        categoryGains.set(gains)
    }

    fun setEventMute(eventName: String, muted: Boolean) { nativeEventMutes[eventName] = muted }

    fun setEventSolo(eventName: String, solo: Boolean) { nativeEventSolos[eventName] = solo }

    fun loadedBankProfileId(): String? = loadedBankProfileId.get()

    internal fun consumeLoadFailure(): AudioLoadFailure? = loadFailure.getAndSet(null)

    fun isAudioActive(): Boolean = synchronized(lifecycleLock) {
        running.get() && controlThread.get()?.isAlive == true
    }

    fun update(frame: EngineAudioFrame) {
        parameters.set(frame)
        soundPerspective.set(frame.perspective)
        if (frame.limiterPulse) limiterPulseSerial.incrementAndGet()
        if (frame.backfireTriggered) backfirePulseSerial.incrementAndGet()
        if (frame.shiftRejected) rejectedShiftSerial.incrementAndGet()
        if (frame.tractionLimitPulse) tractionPulseSerial.incrementAndGet()
    }

    fun setFocusChangeListener(listener: ((AudioFocusEvent) -> Unit)?) {
        focusChangeListener = listener
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && controlThread.get()?.isAlive == true) return
            startLocked()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) { stopLocked() }
    }

    internal fun setSoundProgram(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
    ) {
        synchronized(lifecycleLock) {
            val profileChanged = selectedProfile.getAndSet(profile).id != profile.id
            val perspectiveChanged = soundPerspective.getAndSet(perspective) != perspective
            if (!profileChanged) {
                if (perspectiveChanged) {
                    // Mixer cards are rebuilt for the new listener/event topology. Reset their
                    // host-only mute/solo state too, so no invisible override crosses cabin and
                    // exterior views while the authored FMOD mix itself stays untouched.
                    nativeEventMutes.clear()
                    nativeEventSolos.clear()
                    clearNativeEventOverrides.set(true)
                }
                return
            }

            loadedBankProfileId.set(null)
            nativeSources.set(emptyList())
            if (running.get() || controlThread.get()?.isAlive == true) {
                stopLocked()
                startLocked()
            }
        }
    }

    private fun startLocked() {
        if (running.get() || controlThread.get() != null || focusHeld.get()) stopLocked()

        val profile = selectedProfile.get()
        nativeEventMutes.clear()
        nativeEventSolos.clear()
        if (!runCatching(::requestFocus).getOrDefault(false)) {
            reportLoadFailure(profile.id, "Audio focus was not granted by the system.")
            return
        }

        focusHeld.set(true)
        nativeSources.set(emptyList())
        loadFailure.set(null)
        running.set(true)
        val runId = generation.incrementAndGet()
        val thread = Thread(
            { controlLoop(runId, profile) },
            "fmod-bank-control",
        ).apply { isDaemon = true }
        controlThread.set(thread)
        runCatching(thread::start).onFailure {
            controlThread.compareAndSet(thread, null)
            running.set(false)
            reportLoadFailure(profile.id, "Could not start the FMOD control worker.")
            abandonFocusIfHeld()
        }
    }

    /** Must be called with [lifecycleLock] held. */
    private fun stopLocked() {
        running.set(false)
        generation.incrementAndGet()
        val thread = controlThread.get()
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) joinThread(thread, CONTROL_JOIN_TIMEOUT_MS)
        if (thread == null || !thread.isAlive) controlThread.compareAndSet(thread, null)
            loadedBankProfileId.set(null)
            nativeSources.set(emptyList())
            nativeEventMutes.clear()
            nativeEventSolos.clear()
        abandonFocusIfHeld()
    }

    private fun controlLoop(runId: Long, profile: FmodBankProfile) {
        val bridge = NativeFmodBankBridge()
        var opened = false
        var lastTickNanos = System.nanoTime()
        var nextSnapshotNanos = lastTickNanos
        var lastShiftSerial = 0L
        var controlTickId = 0L
        var lastConsumedSimulationFrameId = 0L
        var consumedLimiterPulse = limiterPulseSerial.get()
        var consumedBackfirePulse = backfirePulseSerial.get()
        var consumedRejectedShift = rejectedShiftSerial.get()
        var consumedTractionPulse = tractionPulseSerial.get()
        var sentCategoryGains: AudioMixGains? = null
        var sentNativeDiagnosticsEnabled = DebugTelemetry.nativeDiagnosticsEnabled()
        var eventCatalogCaptured = false
        var nextDiagnosticsDrainNanos = lastTickNanos
        var diagnosticBankSha256: String? = null

        try {
            org.fmod.FMOD.init(appContext)
            val bankFiles = bankResolver.bankFiles(profile)
            val physics = bankResolver.physics(profile)
            val startupError = bridge.open(
                commonStringsBankPath = bankFiles.commonStrings.absolutePath,
                commonBankPath = bankFiles.common.absolutePath,
                carBankPath = bankFiles.car.absolutePath,
                perspective = soundPerspective.get().ordinal,
                hasTurbo = physics.engine.turbos.isNotEmpty(),
                idleRpm = physics.engine.idleRpm.toFloat(),
                spatial = physics.nativeFmodSpatialCoordinates(),
                // Debug capture is explicitly armed through ADB. It replaces the previous
                // always-on Logcat trace so normal debug drives do not format high-rate strings.
                diagnosticsEnabled = sentNativeDiagnosticsEnabled,
            )
            if (startupError != null) {
                reportLoadFailure(profile.id, startupError)
                return
            }
            opened = true
            loadedBankProfileId.set(profile.id)
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            while (isCurrent(runId)) {
                val now = System.nanoTime()
                val dt = ((now - lastTickNanos).coerceAtLeast(1L) / 1_000_000_000.0)
                    .coerceIn(MIN_CONTROL_STEP_SECONDS, MAX_CONTROL_STEP_SECONDS)
                lastTickNanos = now

                val requestedNativeDiagnostics = DebugTelemetry.nativeDiagnosticsEnabled()
                if (requestedNativeDiagnostics != sentNativeDiagnosticsEnabled) {
                    bridge.setDiagnosticsEnabled(requestedNativeDiagnostics)
                    sentNativeDiagnosticsEnabled = requestedNativeDiagnostics
                    eventCatalogCaptured = false
                }
                if (sentNativeDiagnosticsEnabled && !eventCatalogCaptured) {
                    val bankSha256 = diagnosticBankSha256 ?: runCatching {
                        debugBankSha256(bankFiles.car)
                    }.getOrElse {
                        // A capture without a digest must be rejected by the offline importer,
                        // but diagnostics must never interrupt a live FMOD control worker.
                        "unavailable"
                    }.also { diagnosticBankSha256 = it }
                    DebugTelemetry.recordBankContext(profile.id, bankSha256)
                    DebugTelemetry.recordBankEventCatalog(now, bridge.eventCatalog())
                    eventCatalogCaptured = true
                }

                val frame = parameters.get()
                bridge.setHostGains(hostEngineGain.get(), hostEffectsGain.get())
                val gains = categoryGains.get()
                if (gains != sentCategoryGains) {
                    bridge.setCategoryGains(gains.transmission, gains.gearShift, gains.turbo)
                    sentCategoryGains = gains
                }
                if (clearNativeEventOverrides.getAndSet(false)) {
                    // The native runtime owns independent event maps. Clearing only Kotlin's
                    // maps would leave a removed M/S control silently applied in FMOD.
                    bridge.clearEventOverrides()
                }
                nativeEventMutes.forEach { (name, value) -> bridge.setEventMute(name, value) }
                nativeEventSolos.forEach { (name, value) -> bridge.setEventSolo(name, value) }
                val currentLimiterPulse = limiterPulseSerial.get()
                val currentBackfirePulse = backfirePulseSerial.get()
                val currentRejectedShift = rejectedShiftSerial.get()
                val currentTractionPulse = tractionPulseSerial.get()
                val maximumBoost = frame.maximumBoost.coerceAtLeast(0.001)
                val error = bridge.update(
                    dt = dt.toFloat(),
                    rpm = frame.rpm.coerceAtLeast(1.0).toFloat(),
                    drivetrainSpeed = frame.drivetrainSpeedRadiansPerSecond.toFloat(),
                    throttle = frame.throttle.coerceIn(0.0, 1.0).toFloat(),
                    perspective = frame.perspective.ordinal,
                    boost = (frame.boost / maximumBoost).coerceAtLeast(0.0).toFloat(),
                    boostAbsolute = frame.boost.coerceAtLeast(0.0).toFloat(),
                    bov = frame.bov.coerceAtLeast(0.0).toFloat(),
                    bovDecay = frame.bovDecaySeconds.coerceAtLeast(0.0).toFloat(),
                    gear = frame.gear,
                    isShifting = frame.isShifting,
                    shiftProgress = frame.shiftProgress.coerceIn(0.0, 1.0).toFloat(),
                    shiftSerial = frame.shiftSerial,
                    limiterPulse = currentLimiterPulse != consumedLimiterPulse,
                    shiftStarted = frame.shiftSerial != lastShiftSerial,
                    shiftDirection = frame.shiftDirection,
                    shiftRejected = currentRejectedShift != consumedRejectedShift,
                    backfireTriggered = currentBackfirePulse != consumedBackfirePulse,
                    tractionActive = frame.tractionLimitActive,
                    tractionPulse = currentTractionPulse != consumedTractionPulse,
                    simulationFrameId = frame.simulationFrameId,
                )
                controlTickId += 1L
                DebugTelemetry.recordAudioConsumption(
                    timestampNanos = now,
                    controlTickId = controlTickId,
                    previousSimulationFrameId = lastConsumedSimulationFrameId,
                    frame = frame,
                )
                if (frame.simulationFrameId > 0L) {
                    lastConsumedSimulationFrameId = frame.simulationFrameId
                }
                lastShiftSerial = frame.shiftSerial
                consumedLimiterPulse = currentLimiterPulse
                consumedBackfirePulse = currentBackfirePulse
                consumedRejectedShift = currentRejectedShift
                consumedTractionPulse = currentTractionPulse
                if (error != null) {
                    reportLoadFailure(profile.id, error)
                    return
                }

                if (now >= nextSnapshotNanos) {
                    nativeSources.set(parseNativeVoiceSnapshots(bridge.voiceSnapshots()))
                    nextSnapshotNanos = now + SNAPSHOT_PERIOD_NANOS
                }
                if (sentNativeDiagnosticsEnabled && now >= nextDiagnosticsDrainNanos) {
                    DebugTelemetry.recordNativeRecords(now, bridge.diagnosticRecords())
                    nextDiagnosticsDrainNanos = now + DIAGNOSTIC_DRAIN_PERIOD_NANOS
                }
                sleepUntilNextControlTick(now)
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "FMOD bank control stopped for ${profile.id}", throwable)
            reportLoadFailure(profile.id, throwable.message ?: throwable::class.java.simpleName)
        } finally {
            loadedBankProfileId.set(null)
            if (opened) bridge.close()
            runCatching { org.fmod.FMOD.close() }
            nativeSources.set(emptyList())
            controlThread.compareAndSet(Thread.currentThread(), null)
            if (generation.get() == runId) {
                running.set(false)
                abandonFocusIfHeld()
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun sleepUntilNextControlTick(tickStartedNanos: Long) {
        val remaining = CONTROL_PERIOD_NANOS - (System.nanoTime() - tickStartedNanos)
        if (remaining > 0L) LockSupport.parkNanos(remaining)
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean = audioManager.requestAudioFocus(
        focusListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN,
    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    private fun abandonFocusIfHeld() {
        if (focusHeld.compareAndSet(true, false)) runCatching { audioManager.abandonAudioFocus(focusListener) }
    }

    private fun joinThread(thread: Thread, timeoutMs: Long) {
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun reportLoadFailure(profileId: String, detail: String) {
        loadFailure.set(AudioLoadFailure(profileId, detail))
    }

    /**
     * Inventory traces must prove which exact installed bank generated their lifecycle evidence.
     * This deliberately runs once only after an explicit debug capture is armed, never per frame.
     */
    private fun debugBankSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEBUG_DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "EngineAudioEngine"
        const val CONTROL_PERIOD_NANOS = 3_000_000L
        const val SNAPSHOT_PERIOD_NANOS = 50_000_000L
        const val DIAGNOSTIC_DRAIN_PERIOD_NANOS = 40_000_000L
        const val CONTROL_JOIN_TIMEOUT_MS = 1_000L
        const val MIN_CONTROL_STEP_SECONDS = 1.0 / 1_000.0
        const val MAX_CONTROL_STEP_SECONDS = 0.040
        const val DEBUG_DIGEST_BUFFER_BYTES = 256 * 1024
    }
}

internal data class AudioLoadFailure(
    val profileId: String,
    val detail: String,
)
