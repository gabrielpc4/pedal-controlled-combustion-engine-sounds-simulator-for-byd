package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.os.Debug
import android.os.Process
import android.util.Log
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugTelemetry
import com.gabrielpc.enginesoundsimulator.simulation.nativeFmodSpatialCoordinates
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val snapshotThread = AtomicReference<Thread?>(null)
    private val nativeSources = AtomicReference<List<FmodSourceState>>(emptyList())
    private val mixerDiagnosticsActive = AtomicBoolean(false)
    private val fmodUpdateRateHz = AtomicInteger(FmodUpdateRate.DEFAULT_HZ)
    private val limiterPulseSerial = AtomicLong(0L)
    private val backfirePulseSerial = AtomicLong(0L)
    private val rejectedShiftSerial = AtomicLong(0L)
    private val tractionPulseSerial = AtomicLong(0L)
    private val hostEngineGain = AtomicReference(1.0f)
    private val hostEffectsGain = AtomicReference(2.0f)
    private val categoryGains = AtomicReference(AudioMixGains())
    private val nativeEventMutes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val nativeEventSolos = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Incremented only when the UI changes an override; the worker sends the batch once. */
    private val nativeEventOverridesVersion = AtomicLong(0L)
    private val backfireOnly = AtomicBoolean(false)
    private val backfireAudioEnabled = AtomicBoolean(true)
    private val backfireAllowedSamplesMask = AtomicInteger(0b111111)
    private val shiftSoundOverride = AtomicBoolean(false)
    private val shiftSoundEnabled = AtomicBoolean(true)
    private val transmissionAudioEnabled = AtomicBoolean(true)
    private val turboAudioEnabled = AtomicBoolean(true)
    private val backfireUseOriginal = AtomicBoolean(false)
    private val exteriorPureAudio = AtomicBoolean(false)
    private var sentBackfireOnly = false
    private var sentExteriorPureAudio = false

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

    fun setFmodUpdateRateHz(rateHz: Int) {
        fmodUpdateRateHz.set(FmodUpdateRate.normalize(rateHz))
    }

    fun fmodUpdateRateHz(): Int = fmodUpdateRateHz.get()

    fun setMixerDiagnosticsActive(active: Boolean) {
        mixerDiagnosticsActive.set(active)
    }

    fun isMixerDiagnosticsActive(): Boolean = mixerDiagnosticsActive.get()

    fun setHostGains(engine: Float, effects: Float) {
        hostEngineGain.set(engine.coerceAtLeast(0f))
        hostEffectsGain.set(effects.coerceAtLeast(0f))
    }

    internal fun setCategoryGains(gains: AudioMixGains) {
        categoryGains.set(gains)
    }

    fun setEventMute(eventName: String, muted: Boolean) {
        if (muted) nativeEventMutes[eventName] = true else nativeEventMutes.remove(eventName)
        nativeEventOverridesVersion.incrementAndGet()
    }

    fun setEventSolo(eventName: String, solo: Boolean) {
        if (solo) nativeEventSolos[eventName] = true else nativeEventSolos.remove(eventName)
        nativeEventOverridesVersion.incrementAndGet()
    }

    fun setBackfireOnly(enabled: Boolean) { backfireOnly.set(enabled) }

    fun setBackfireAudioEnabled(enabled: Boolean) { backfireAudioEnabled.set(enabled) }

    fun setShiftSoundOverride(enabled: Boolean) { shiftSoundOverride.set(enabled) }

    fun setShiftSoundEnabled(enabled: Boolean) { shiftSoundEnabled.set(enabled) }

    fun setTransmissionAudioEnabled(enabled: Boolean) { transmissionAudioEnabled.set(enabled) }

    fun setTurboAudioEnabled(enabled: Boolean) { turboAudioEnabled.set(enabled) }

    fun setBackfireUseOriginal(enabled: Boolean) { backfireUseOriginal.set(enabled) }

    fun setExteriorPureAudio(enabled: Boolean) { exteriorPureAudio.set(enabled) }

    fun setBackfireAllowedSamples(samples: Set<Int>) {
        val mask = samples.fold(0) { result, sample ->
            if (sample in 1..4) result or (1 shl (sample - 1)) else result
        }
        // Keep one source available so a stale trigger cannot become silent while preferences
        // are being applied between simulation and audio-control ticks.
        backfireAllowedSamplesMask.set(if (mask == 0) 1 else mask)
    }

    fun loadedBankProfileId(): String? = loadedBankProfileId.get()

    internal fun consumeLoadFailure(): AudioLoadFailure? = loadFailure.getAndSet(null)

    fun isAudioActive(): Boolean = synchronized(lifecycleLock) {
        running.get() && controlThread.get()?.isAlive == true
    }

    fun update(frame: EngineAudioFrame) {
        parameters.set(frame)
        soundPerspective.set(frame.perspective)
        if (frame.limiterPulse) limiterPulseSerial.incrementAndGet()
        // A gear change has its own brief throttle cut. Never let that transport-level cut become
        // a backfire pulse, even if the simulation frame arrives at the audio worker one tick late.
        if (frame.backfireTriggered && !frame.isShifting && frame.shiftDirection == 0) {
            backfirePulseSerial.incrementAndGet()
        }
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
                    nativeEventOverridesVersion.incrementAndGet()
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
        nativeEventOverridesVersion.incrementAndGet()
        if (!runCatching(::requestFocus).getOrDefault(false)) {
            reportLoadFailure(profile.id, "Audio focus was not granted by the system.")
            return
        }

        focusHeld.set(true)
        nativeSources.set(emptyList())
        loadFailure.set(null)
        // A new native runtime starts with no diagnostic filter; force the desired state to be
        // resent even when the previous session ended with ONLY BACKFIRE enabled.
        sentBackfireOnly = false
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
        stopSnapshotThread()
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
        var nextControlNanos = lastTickNanos
        var lastShiftSerial = 0L
        var controlTickId = 0L
        var lastConsumedSimulationFrameId = 0L
        var consumedLimiterPulse = limiterPulseSerial.get()
        var consumedBackfirePulse = backfirePulseSerial.get()
        var consumedRejectedShift = rejectedShiftSerial.get()
        var consumedTractionPulse = tractionPulseSerial.get()
        var sentCategoryGains: AudioMixGains? = null
        var sentHostEngineGain: Float? = null
        var sentHostEffectsGain: Float? = null
        var sentNativeEventOverridesVersion = -1L
        var sentBackfireAllowedSamplesMask = -1
        var sentBackfireAudioEnabled = true
        var sentShiftSoundOverride = false
        var sentShiftSoundEnabled = true
        var sentTransmissionAudioEnabled = true
        var sentTurboAudioEnabled = true
        var sentBackfireUseOriginal = false
        var sentNativeDiagnosticsEnabled = DebugTelemetry.nativeDiagnosticsEnabled()
        var eventCatalogCaptured = false
        var diagnosticBankSha256: String? = null

        try {
            org.fmod.FMOD.init(appContext)
            val bankFiles = bankResolver.bankFiles(profile)
            val physics = bankResolver.physics(profile)
            val alfaBackfireDirectory = ensureAlfaBackfireSamples()
            val startupError = bridge.open(
                commonStringsBankPath = bankFiles.commonStrings.absolutePath,
                commonBankPath = bankFiles.common.absolutePath,
                carBankPath = bankFiles.car.absolutePath,
                alfaBackfireDirectory = alfaBackfireDirectory.absolutePath,
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
            lastTickNanos = System.nanoTime()
            nextControlNanos = lastTickNanos
            startSnapshotThread(runId, bridge)

            // Apply the persisted mode immediately after opening so the first exterior frame
            // cannot briefly use the authored spatial placement before the control loop runs.
            val requestedExteriorPureAudio = exteriorPureAudio.get()
            bridge.setExteriorPureAudio(requestedExteriorPureAudio)
            sentExteriorPureAudio = requestedExteriorPureAudio

            while (isCurrent(runId)) {
                val now = System.nanoTime()
                val controlPeriodNanos = FmodUpdateRate.periodNanos(fmodUpdateRateHz.get())
                if (now < nextControlNanos) {
                    LockSupport.parkNanos(nextControlNanos - now)
                    continue
                }
                val deadlineMissed = now > nextControlNanos + CONTROL_DEADLINE_TOLERANCE_NANOS
                // Do not run catch-up bursts when the process was descheduled. FMOD receives the
                // newest physics frame once and gets the real elapsed interval as dt.
                nextControlNanos = now + controlPeriodNanos
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
                val measurePerformance = DebugTelemetry.performanceEnabled()
                var hostGainCalls = 0
                var categoryGainCalls = 0
                var overrideBatchCalls = 0
                val requestedHostEngineGain = hostEngineGain.get()
                val requestedHostEffectsGain = hostEffectsGain.get()
                if (
                    requestedHostEngineGain != sentHostEngineGain ||
                    requestedHostEffectsGain != sentHostEffectsGain
                ) {
                    bridge.setHostGains(requestedHostEngineGain, requestedHostEffectsGain)
                    sentHostEngineGain = requestedHostEngineGain
                    sentHostEffectsGain = requestedHostEffectsGain
                    hostGainCalls = 1
                }
                val gains = categoryGains.get()
                if (gains != sentCategoryGains) {
                    bridge.setCategoryGains(gains.transmission, gains.gearShift, gains.turbo, gains.backfire)
                    sentCategoryGains = gains
                    categoryGainCalls = 1
                }
                val requestedNativeEventOverridesVersion = nativeEventOverridesVersion.get()
                if (requestedNativeEventOverridesVersion != sentNativeEventOverridesVersion) {
                    // Send the complete immutable meaning of the UI maps once per change. The
                    // native side applies all event volumes in one pass instead of reapplying the
                    // whole bank once for every card on every audio tick.
                    bridge.setEventOverrides(
                        mutedEvents = nativeEventMutes.keys.toTypedArray(),
                        soloEvents = nativeEventSolos.keys.toTypedArray(),
                    )
                    sentNativeEventOverridesVersion = requestedNativeEventOverridesVersion
                    overrideBatchCalls = 1
                }
                val requestedBackfireAllowedSamplesMask = backfireAllowedSamplesMask.get()
                if (requestedBackfireAllowedSamplesMask != sentBackfireAllowedSamplesMask) {
                    bridge.setBackfireAllowedSamples(requestedBackfireAllowedSamplesMask)
                    sentBackfireAllowedSamplesMask = requestedBackfireAllowedSamplesMask
                }
                val requestedBackfireAudioEnabled = backfireAudioEnabled.get()
                if (requestedBackfireAudioEnabled != sentBackfireAudioEnabled) {
                    bridge.setBackfireAudioEnabled(requestedBackfireAudioEnabled)
                    sentBackfireAudioEnabled = requestedBackfireAudioEnabled
                }
                val requestedShiftSoundOverride = shiftSoundOverride.get()
                if (requestedShiftSoundOverride != sentShiftSoundOverride) {
                    bridge.setShiftSoundOverride(requestedShiftSoundOverride)
                    sentShiftSoundOverride = requestedShiftSoundOverride
                }
                val requestedShiftSoundEnabled = shiftSoundEnabled.get()
                if (requestedShiftSoundEnabled != sentShiftSoundEnabled) {
                    bridge.setShiftSoundEnabled(requestedShiftSoundEnabled)
                    sentShiftSoundEnabled = requestedShiftSoundEnabled
                }
                val requestedTransmissionAudioEnabled = transmissionAudioEnabled.get()
                if (requestedTransmissionAudioEnabled != sentTransmissionAudioEnabled) {
                    bridge.setTransmissionAudioEnabled(requestedTransmissionAudioEnabled)
                    sentTransmissionAudioEnabled = requestedTransmissionAudioEnabled
                }
                val requestedTurboAudioEnabled = turboAudioEnabled.get()
                if (requestedTurboAudioEnabled != sentTurboAudioEnabled) {
                    bridge.setTurboAudioEnabled(requestedTurboAudioEnabled)
                    sentTurboAudioEnabled = requestedTurboAudioEnabled
                }
                val requestedBackfireUseOriginal = backfireUseOriginal.get()
                if (requestedBackfireUseOriginal != sentBackfireUseOriginal) {
                    bridge.setBackfireUseOriginal(requestedBackfireUseOriginal)
                    sentBackfireUseOriginal = requestedBackfireUseOriginal
                }
                val requestedBackfireOnly = backfireOnly.get()
                if (requestedBackfireOnly != sentBackfireOnly) {
                    bridge.setBackfireOnly(requestedBackfireOnly)
                    sentBackfireOnly = requestedBackfireOnly
                }
                val requestedExteriorPureAudio = exteriorPureAudio.get()
                if (requestedExteriorPureAudio != sentExteriorPureAudio) {
                    bridge.setExteriorPureAudio(requestedExteriorPureAudio)
                    sentExteriorPureAudio = requestedExteriorPureAudio
                }
                val currentLimiterPulse = limiterPulseSerial.get()
                val currentBackfirePulse = backfirePulseSerial.get()
                val currentRejectedShift = rejectedShiftSerial.get()
                val currentTractionPulse = tractionPulseSerial.get()
                val limiterPulseCount = (currentLimiterPulse - consumedLimiterPulse)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val backfirePulseCount = (currentBackfirePulse - consumedBackfirePulse)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val rejectedShiftCount = (currentRejectedShift - consumedRejectedShift)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val tractionPulseCount = (currentTractionPulse - consumedTractionPulse)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val shiftStartedCount = (frame.shiftSerial - lastShiftSerial)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val maximumBoost = frame.maximumBoost.coerceAtLeast(0.001)
                val audioWallStartedNanos = if (measurePerformance) System.nanoTime() else 0L
                val audioCpuStartedNanos = if (measurePerformance) Debug.threadCpuTimeNanos() else 0L
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
                    limiterPulseCount = limiterPulseCount,
                    shiftStartedCount = shiftStartedCount,
                    shiftDirection = frame.shiftDirection,
                    shiftRejectedCount = rejectedShiftCount,
                    backfirePulseCount = backfirePulseCount,
                    backfireSampleIndex = frame.backfireSampleIndex,
                    tractionActive = frame.tractionLimitActive,
                    tractionPulseCount = tractionPulseCount,
                    simulationFrameId = frame.simulationFrameId,
                )
                if (measurePerformance) {
                    DebugTelemetry.recordAudioPerformance(
                        cpuNanos = Debug.threadCpuTimeNanos() - audioCpuStartedNanos,
                        wallNanos = System.nanoTime() - audioWallStartedNanos,
                        hostGainCalls = hostGainCalls,
                        categoryGainCalls = categoryGainCalls,
                        overrideBatchCalls = overrideBatchCalls,
                        simulationFrameId = frame.simulationFrameId,
                        previousSimulationFrameId = lastConsumedSimulationFrameId,
                        limiterPulseCount = limiterPulseCount,
                        shiftPulseCount = shiftStartedCount,
                        rejectedShiftPulseCount = rejectedShiftCount,
                        backfirePulseCount = backfirePulseCount,
                        tractionPulseCount = tractionPulseCount,
                        deadlineMissed = deadlineMissed,
                    )
                }
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
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "FMOD bank control stopped for ${profile.id}", throwable)
            reportLoadFailure(profile.id, throwable.message ?: throwable::class.java.simpleName)
        } finally {
            loadedBankProfileId.set(null)
            stopSnapshotThread()
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

    private fun startSnapshotThread(runId: Long, bridge: NativeFmodBankBridge) {
        val thread = Thread({ mixerSnapshotLoop(runId, bridge) }, "fmod-mixer-snapshot").apply {
            isDaemon = true
        }
        snapshotThread.set(thread)
        runCatching(thread::start).onFailure {
            snapshotThread.compareAndSet(thread, null)
            Log.w(TAG, "Could not start FMOD mixer snapshot worker", it)
        }
    }

    private fun mixerSnapshotLoop(runId: Long, bridge: NativeFmodBankBridge) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var nextSnapshotNanos = 0L
        var nextDiagnosticsDrainNanos = 0L
        while (isCurrent(runId)) {
            if (!mixerDiagnosticsActive.get() && !DebugTelemetry.nativeDiagnosticsEnabled()) {
                LockSupport.parkNanos(INACTIVE_SNAPSHOT_PARK_NANOS)
                continue
            }

            val now = System.nanoTime()
            if (mixerDiagnosticsActive.get() && now >= nextSnapshotNanos) {
                val measurePerformance = DebugTelemetry.performanceEnabled()
                val snapshotWallStartedNanos = if (measurePerformance) System.nanoTime() else 0L
                val snapshotCpuStartedNanos = if (measurePerformance) Debug.threadCpuTimeNanos() else 0L
                nativeSources.set(parseNativeVoiceSnapshots(bridge.voiceSnapshots()))
                if (measurePerformance) {
                    DebugTelemetry.recordMixerSnapshotPerformance(
                        cpuNanos = Debug.threadCpuTimeNanos() - snapshotCpuStartedNanos,
                        wallNanos = System.nanoTime() - snapshotWallStartedNanos,
                    )
                }
                nextSnapshotNanos = now + SNAPSHOT_PERIOD_NANOS
            }
            if (DebugTelemetry.nativeDiagnosticsEnabled() && now >= nextDiagnosticsDrainNanos) {
                DebugTelemetry.recordNativeRecords(now, bridge.diagnosticRecords())
                nextDiagnosticsDrainNanos = now + DIAGNOSTIC_DRAIN_PERIOD_NANOS
            }
            LockSupport.parkNanos(SNAPSHOT_WORKER_PARK_NANOS)
        }
    }

    private fun stopSnapshotThread() {
        val thread = snapshotThread.getAndSet(null) ?: return
        thread.interrupt()
        if (thread !== Thread.currentThread()) joinThread(thread, SNAPSHOT_JOIN_TIMEOUT_MS)
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

    /** Copies the small shared Alfa one-shots once so the native FMOD core can load them for any car. */
    private fun ensureAlfaBackfireSamples(): File {
        val directory = File(appContext.filesDir, "alfa-backfire")
        directory.mkdirs()
        com.gabrielpc.enginesoundsimulator.drive.AlfaBackfireSources.indices.forEach { sample ->
            val sourceName = com.gabrielpc.enginesoundsimulator.drive.AlfaBackfireSources.names[sample - 1]
            val destination = File(directory, "$sourceName.wav")
            if (!destination.exists() || destination.length() == 0L) {
                appContext.assets.open("backfire/alfa/$sourceName.wav").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        listOf("shift_up", "shift_down").forEach { sourceName ->
            val destination = File(directory, "$sourceName.wav")
            if (!destination.exists() || destination.length() == 0L) {
                appContext.assets.open("shift/$sourceName.wav").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return directory
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
        const val SNAPSHOT_PERIOD_NANOS = 50_000_000L
        const val DIAGNOSTIC_DRAIN_PERIOD_NANOS = 40_000_000L
        const val CONTROL_JOIN_TIMEOUT_MS = 1_000L
        const val SNAPSHOT_JOIN_TIMEOUT_MS = 500L
        const val SNAPSHOT_WORKER_PARK_NANOS = 5_000_000L
        const val INACTIVE_SNAPSHOT_PARK_NANOS = 250_000_000L
        const val CONTROL_DEADLINE_TOLERANCE_NANOS = 500_000L
        const val MIN_CONTROL_STEP_SECONDS = 1.0 / 1_000.0
        const val MAX_CONTROL_STEP_SECONDS = 0.040
        const val DEBUG_DIGEST_BUFFER_BYTES = 256 * 1024
    }
}

internal data class AudioLoadFailure(
    val profileId: String,
    val detail: String,
)
