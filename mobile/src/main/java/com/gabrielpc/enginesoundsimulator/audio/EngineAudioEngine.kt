package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.gabrielpc.enginesoundsimulator.catalog.InstalledSoundFamily
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import java.io.InterruptedIOException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

data class AudioOutputState(
    val running: Boolean = false,
    val sampleStatus: String = "OFFLINE",
    val activeChannels: Int = 0,
    val activeLayout: String = "OFFLINE",
    val sampleRate: Int = 0,
    val framesPerWrite: Int = 0,
    val bufferFrames: Int = 0,
    val targetBufferMilliseconds: Int = 50,
    val queuedFrames: Int = 0,
    val bufferAdjustmentCount: Int = 0,
    val bufferResizeFailures: Int = 0,
    val lastBufferAdjustment: String = "none",
    val renderP99Micros: Int = 0,
    val renderP99LowerMicros: Int = 0,
    val renderMaxMicros: Int = 0,
    val renderSamples: Long = 0L,
    val steadyRenderP99Micros: Int = 0,
    val steadyRenderP99LowerMicros: Int = 0,
    val steadyRenderMaxMicros: Int = 0,
    val steadyRenderSamples: Long = 0L,
    val transitionRenderP99Micros: Int = 0,
    val transitionRenderP99LowerMicros: Int = 0,
    val transitionRenderMaxMicros: Int = 0,
    val transitionRenderSamples: Long = 0L,
    val sessionId: Int = 0,
    val routedDevice: String = "none",
    val advertisedChannels: String = "fixed stereo",
    val underruns: Int = 0,
    val startupUnderruns: Int = 0,
    val steadyStateUnderruns: Int = 0,
    val focusGranted: Boolean = false,
    val sampleProfile: String = "none",
    val sampleLoadedLoops: Int = 0,
    val sampleLoadedEffects: Int = 0,
    val sampleDecodedBytes: Long = 0L,
    val nativeResidentBytes: Long = 0L,
    val nativeReservedBytes: Long = 0L,
    val nativeSoftBudgetBytes: Long = 0L,
    val nativeHardBudgetBytes: Long = 0L,
    val sampleTargetRpm: Int = 0,
    val sampleRenderRpm: Int = 0,
    val sampleThrottle: Double = 0.0,
    val sampleActiveLayers: String = "none",
    val samplePlaying: List<PlayingSampleLabel> = emptyList(),
    val layerOutputMeters: List<LayerOutputMeter> = emptyList(),
    val sampleFramesRendered: Long = 0L,
    val sampleLoopWraps: Long = 0L,
    val samplePeak: Double = 0.0,
    val sampleOverRangeSamples: Long = 0L,
    val sampleEffectTriggers: Long = 0L,
    val sampleActiveEffects: String = "none",
    val sampleTurboControllerGain: Double = 1.0,
    val sampleGlobalVoiceBudget: Int = 0,
    val sampleGlobalLogicalVoices: Int = 0,
    val sampleGlobalRealVoices: Int = 0,
    val sampleGlobalVirtualVoices: Int = 0,
    val sampleGlobalRejectedTriggers: Long = 0L,
    val sampleGlobalStolenLogicalVoices: Long = 0L,
    val authoredForwardRatios: String = "none",
    val authoredFinalDrive: Double? = null,
    val alternateGearSetCount: Int = 0,
    val alternateGearOptionCount: Int = 0,
    val alternateGearSetFiles: String = "none",
    val alternateGearVariants: String = "none",
    val hybridMetadataStatus: String = "none",
    val authoredQuirkPolicies: String = "none",
    val packLoadStatus: String = "BUILT_IN",
    val packLoadFamily: String? = null,
    val packLoadCar: String? = null,
    val packLoadError: String? = null,
    val sampleError: String? = null,
    val error: String? = null,
)

/** Streams one decoded engine program as fixed PCM16, 48 kHz stereo. */
class EngineAudioEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val workerRunning = AtomicBoolean(true)
    private val generation = AtomicLong(0)
    private val parameters = RealtimeEngineAudioParameters()
    private val popsAndBangsAuditionSerial = AtomicLong(0L)
    private val selectedProfile = AtomicReference(SILENT_CATALOG_PROFILE)
    private val focusGainBits = AtomicLong(0.0.toRawBits())
    private val focusHeld = AtomicBoolean(false)
    private val outputState = AtomicReference(
        AudioOutputState(sampleProfile = SILENT_CATALOG_PROFILE.id),
    )
    private val renderThread = AtomicReference<Thread?>(null)
    private val activeTrack = AtomicReference<AudioTrack?>(null)
    private val activeRenderer = AtomicReference<SampleEngineRenderer?>(null)
    private val runtimeCounters = AtomicReference<TrackRuntimeCounters?>(null)
    private val decodeBudget = DecodedAudioBudget.forDevice(appContext)
    private val decodeGeneration = AtomicLong(0L)
    private val queuedDecode = AtomicReference<SoundDecodeRequest?>(null)
    private val activeDecode = AtomicReference<SoundDecodeRequest?>(null)
    private val pendingNativeProfile = AtomicReference<PreparedNativeSoundProfile?>(null)
    private val pendingSilentRenderer = AtomicReference<SampleEngineRenderer?>(null)
    private val activeNativeProfile = AtomicReference<PreparedNativeSoundProfile?>(null)
    private val retiredNativeProfiles = AtomicReference<PreparedNativeSoundProfile?>(null)
    private val realtimeFailures = RealtimeFailureMailbox()
    private val nativeMemory = NativeDecodedMemoryLedger(decodeBudget)
    private val swapScratch = ShortArray(MAX_FRAMES_PER_BURST * OUTPUT_CHANNEL_COUNT)
    private val decodeWorker = Thread(::decodeWorkerLoop, "engine-pack-decoder").apply {
        isDaemon = true
        start()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    setFocusGain(1.0)
                    outputState.updateAndGet { it.copy(focusGranted = true) }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                setFocusGain(DUCK_GAIN)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                DebugEventLog.warning("audio_focus_duck")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                setFocusGain(0.0)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                DebugEventLog.warning("audio_focus_transient_loss")
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // Keep simulation/render phase alive, but stay silent until start() explicitly
                // obtains focus again. A permanent loss must never auto-resume on its own.
                setFocusGain(0.0)
                focusHeld.set(false)
                outputState.updateAndGet { it.copy(focusGranted = false) }
                DebugEventLog.warning("audio_focus_lost")
            }
        }
    }

    /** Presentation snapshots are assembled on the caller, never on the realtime thread. */
    fun state(): AudioOutputState {
        val base = outputState.get()
        val counters = runtimeCounters.get()
        val sample = activeRenderer.get()?.diagnostics()
        val native = activeNativeProfile.get()
        val authored = selectedProfile.get().authoredCarMetadata
        return base.copy(
            bufferFrames = counters?.bufferFrames ?: base.bufferFrames,
            targetBufferMilliseconds = counters?.bufferTargetMilliseconds ?: base.targetBufferMilliseconds,
            queuedFrames = counters?.queuedFrames ?: base.queuedFrames,
            bufferAdjustmentCount = counters?.bufferAdjustmentCount ?: base.bufferAdjustmentCount,
            bufferResizeFailures = counters?.bufferResizeFailures ?: base.bufferResizeFailures,
            lastBufferAdjustment = counters?.lastBufferAdjustment?.diagnosticLabel ?: base.lastBufferAdjustment,
            renderP99Micros = counters?.renderTiming?.overall?.percentile99Micros() ?: base.renderP99Micros,
            renderP99LowerMicros = counters?.renderTiming?.overall?.percentile99LowerBoundMicros()
                ?: base.renderP99LowerMicros,
            renderMaxMicros = counters?.renderTiming?.overall?.maximumNanos?.div(NANOS_PER_MICROSECOND)?.toInt()
                ?: base.renderMaxMicros,
            renderSamples = counters?.renderTiming?.overall?.sampleCount ?: base.renderSamples,
            steadyRenderP99Micros = counters?.renderTiming?.steady?.percentile99Micros()
                ?: base.steadyRenderP99Micros,
            steadyRenderP99LowerMicros = counters?.renderTiming?.steady?.percentile99LowerBoundMicros()
                ?: base.steadyRenderP99LowerMicros,
            steadyRenderMaxMicros = counters?.renderTiming?.steady?.maximumNanos
                ?.div(NANOS_PER_MICROSECOND)?.toInt() ?: base.steadyRenderMaxMicros,
            steadyRenderSamples = counters?.renderTiming?.steady?.sampleCount ?: base.steadyRenderSamples,
            transitionRenderP99Micros = counters?.renderTiming?.transition?.percentile99Micros()
                ?: base.transitionRenderP99Micros,
            transitionRenderP99LowerMicros = counters?.renderTiming?.transition
                ?.percentile99LowerBoundMicros() ?: base.transitionRenderP99LowerMicros,
            transitionRenderMaxMicros = counters?.renderTiming?.transition?.maximumNanos
                ?.div(NANOS_PER_MICROSECOND)?.toInt() ?: base.transitionRenderMaxMicros,
            transitionRenderSamples = counters?.renderTiming?.transition?.sampleCount
                ?: base.transitionRenderSamples,
            underruns = counters?.underruns ?: base.underruns,
            startupUnderruns = counters?.startupUnderruns ?: base.startupUnderruns,
            steadyStateUnderruns = counters?.steadyStateUnderruns ?: base.steadyStateUnderruns,
            sampleTargetRpm = sample?.targetRpm ?: base.sampleTargetRpm,
            sampleProfile = sample?.profileId ?: base.sampleProfile,
            sampleLoadedLoops = sample?.loadedLoops ?: base.sampleLoadedLoops,
            sampleLoadedEffects = sample?.loadedEffects ?: base.sampleLoadedEffects,
            sampleDecodedBytes = sample?.decodedBytes ?: base.sampleDecodedBytes,
            nativeResidentBytes = nativeMemory.residentBytes,
            nativeReservedBytes = nativeMemory.reservedBytes,
            nativeSoftBudgetBytes = decodeBudget.softBytes,
            nativeHardBudgetBytes = decodeBudget.hardBytes,
            sampleRenderRpm = sample?.renderRpm ?: base.sampleRenderRpm,
            sampleThrottle = sample?.throttle ?: base.sampleThrottle,
            sampleActiveLayers = sample?.activeLayers ?: base.sampleActiveLayers,
            samplePlaying = sample?.playingSamples ?: base.samplePlaying,
            layerOutputMeters = sample?.layerOutputMeters ?: base.layerOutputMeters,
            sampleFramesRendered = sample?.framesRendered ?: base.sampleFramesRendered,
            sampleLoopWraps = sample?.loopWraps ?: base.sampleLoopWraps,
            samplePeak = sample?.peak ?: base.samplePeak,
            sampleOverRangeSamples = sample?.overRangeSamples ?: base.sampleOverRangeSamples,
            sampleEffectTriggers = sample?.effectTriggers ?: base.sampleEffectTriggers,
            sampleActiveEffects = sample?.activeEffects ?: base.sampleActiveEffects,
            sampleTurboControllerGain = sample?.turboControllerGain ?: base.sampleTurboControllerGain,
            sampleGlobalVoiceBudget = sample?.globalVoiceBudget ?: base.sampleGlobalVoiceBudget,
            sampleGlobalLogicalVoices = sample?.globalLogicalVoices
                ?: base.sampleGlobalLogicalVoices,
            sampleGlobalRealVoices = sample?.globalRealVoices ?: base.sampleGlobalRealVoices,
            sampleGlobalVirtualVoices = sample?.globalVirtualVoices
                ?: base.sampleGlobalVirtualVoices,
            sampleGlobalRejectedTriggers = sample?.globalRejectedTriggers
                ?: base.sampleGlobalRejectedTriggers,
            sampleGlobalStolenLogicalVoices = sample?.globalStolenLogicalVoices
                ?: base.sampleGlobalStolenLogicalVoices,
            authoredForwardRatios = authored.defaultForwardRatiosDiagnostic,
            authoredFinalDrive = authored.defaultFinalDrive,
            alternateGearSetCount = authored.alternateGearSets.size,
            alternateGearOptionCount = authored.alternateOptionCount,
            alternateGearSetFiles = authored.alternateSourceFiles,
            alternateGearVariants = authored.alternateGearDiagnostic,
            hybridMetadataStatus = authored.hybridDiagnostic,
            authoredQuirkPolicies = authored.quirkDiagnostic,
            packLoadStatus = if (native != null) "ACTIVE" else base.packLoadStatus,
            packLoadFamily = native?.familyId ?: base.packLoadFamily,
            packLoadCar = native?.carId ?: base.packLoadCar,
        )
    }

    fun update(frame: EngineAudioFrame) {
        parameters.write(frame)
    }

    /** Allocation-free command publication from the 200 Hz driving core. */
    internal fun updateCore(
        rpm: Double,
        drivetrainRpm: Double,
        physicalPedal: Double,
        throttle: Double,
        enabled: Boolean,
        enabledEffectMask: Long,
        soloEffects: Boolean,
        shiftSerial: Long,
        shiftDirection: Int,
        isShifting: Boolean,
        gear: Int,
        limiterActive: Boolean,
        tuning: com.gabrielpc.enginesoundsimulator.tuning.AudioTuning,
        layerMix: Map<String, LayerMixControl>,
    ) = parameters.write(
        rpm, drivetrainRpm, physicalPedal, throttle, enabled, enabledEffectMask, soloEffects,
        shiftSerial, shiftDirection, isShifting, gear, limiterActive, tuning, layerMix,
    )

    /** Uses the same renderer trigger path as a naturally detected overrun event. */
    fun auditionPopsAndBangs() {
        popsAndBangsAuditionSerial.incrementAndGet()
    }

    /** Queues one complete pack decode; the current renderer remains active until atomic activation. */
    internal fun setInstalledFamily(family: InstalledSoundFamily, carId: String) {
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Audio engine is closed" }
            val profile = family.manifest.engineSampleProfileFor(carId, family.previewFile(carId)?.absolutePath)
            selectedProfile.set(profile)
            val request = SoundDecodeRequest(
                serial = decodeGeneration.incrementAndGet(), family = family, carId = carId,
                cancellation = NativeDecodeCancellation(),
            )
            activeDecode.get()?.cancellation?.cancel()
            queuedDecode.getAndSet(request)?.let(::discardDecodeRequest)
            pendingNativeProfile.getAndSet(null)?.let(::retireNativeProfile)
            // Do not leave the previously selected car audible under the new
            // car's tachometer while this asynchronous decode is in flight (or
            // after a corrupt-pack failure). Fade to the new profile's silent
            // placeholder; the complete native family is activated atomically.
            pendingSilentRenderer.set(
                SampleEngineRenderer.fromDecoded(
                    OUTPUT_SAMPLE_RATE,
                    emptyMap(),
                    profile.silentPlaceholder(),
                ),
            )
            outputState.updateAndGet {
                it.copy(
                    packLoadStatus = "LOADING", packLoadFamily = family.manifest.familyId,
                    packLoadCar = carId, packLoadError = null,
                )
            }
            LockSupport.unpark(decodeWorker)
        }
    }

    internal fun cancelInstalledFamilyLoad() {
        synchronized(lifecycleLock) {
            decodeGeneration.incrementAndGet()
            activeDecode.get()?.cancellation?.cancel()
            queuedDecode.getAndSet(null)?.let(::discardDecodeRequest)
            pendingNativeProfile.getAndSet(null)?.let(::retireNativeProfile)
            outputState.updateAndGet { it.copy(packLoadStatus = "BUILT_IN", packLoadError = null) }
        }
    }

    /** Selects metadata for an uninstalled car and fades any previous pack to phase-preserving silence. */
    internal fun selectUninstalledProfile(profile: EngineSampleProfile) {
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Audio engine is closed" }
            require(profile.requiredAssets.isEmpty()) { "Uninstalled profile must not reference packaged audio" }
            decodeGeneration.incrementAndGet()
            activeDecode.get()?.cancellation?.cancel()
            queuedDecode.getAndSet(null)?.let(::discardDecodeRequest)
            pendingNativeProfile.getAndSet(null)?.let(::retireNativeProfile)
            selectedProfile.set(profile)
            pendingSilentRenderer.set(SampleEngineRenderer.fromDecoded(OUTPUT_SAMPLE_RATE, emptyMap(), profile))
            outputState.updateAndGet {
                it.copy(
                    sampleProfile = profile.id, packLoadStatus = "UNINSTALLED",
                    packLoadCar = profile.id, packLoadError = null,
                )
            }
        }
    }

    fun start() {
        check(!closed.get()) { "Audio engine is closed" }
        synchronized(lifecycleLock) {
            if (running.get() && renderThread.get()?.isAlive == true) {
                if (!focusHeld.get()) reacquireFocusLocked()
                return
            }
            startLocked()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    /** Final service teardown. Unlike [stop], this permanently releases the decoder worker. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) { stopLocked() }
        decodeGeneration.incrementAndGet()
        activeDecode.get()?.cancellation?.cancel()
        queuedDecode.getAndSet(null)?.let(::discardDecodeRequest)
        pendingNativeProfile.getAndSet(null)?.let(::retireNativeProfile)
        pendingSilentRenderer.set(null)
        workerRunning.set(false)
        LockSupport.unpark(decodeWorker)
        if (decodeWorker !== Thread.currentThread()) joinThread(decodeWorker, DECODE_WORKER_JOIN_TIMEOUT_MS)
    }

    internal fun setSampleProfile(profile: EngineSampleProfile) {
        synchronized(lifecycleLock) {
            val changed = selectedProfile.getAndSet(profile).id != profile.id
            outputState.updateAndGet { it.copy(sampleProfile = profile.id) }
            if (!changed) return
            val shouldRestart = running.get() || renderThread.get()?.isAlive == true
            if (shouldRestart && stopLocked()) startLocked()
        }
    }

    private fun decodeWorkerLoop() {
        while (workerRunning.get()) {
            drainRealtimeFailures()
            drainRetiredProfiles()
            val request = queuedDecode.getAndSet(null)
            if (request == null) {
                LockSupport.park()
                continue
            }
            activeDecode.set(request)
            var decodedProfile: PreparedNativeSoundProfile? = null
            var decodedProfileIsResident = false
            try {
                reserveDecodeBytes(request)
                val prepared = NativeSoundFamilyLoader.decode(
                    request.family,
                    request.carId,
                    decodeBudget,
                    request.cancellation,
                )
                decodedProfile = prepared
                transferDecodeReservation(request, prepared)
                decodedProfileIsResident = true
                // Initialize renderer state away from the realtime thread. The silent
                // passes touch every control path but do not alter the active renderer.
                val warmup = ShortArray(WARMUP_FRAMES * OUTPUT_CHANNEL_COUNT)
                val warmupFrame = MutableEngineAudioFrame()
                parameters.readInto(warmupFrame)
                repeat(WARMUP_PASSES) {
                    prepared.renderer.render(warmupFrame, warmup, gain = 0.0)
                }
                if (!publishDecodedProfileIfCurrent(request, prepared)) {
                    releaseNativeProfile(prepared)
                    decodedProfile = null
                    decodedProfileIsResident = false
                } else {
                    decodedProfile = null
                    decodedProfileIsResident = false
                }
            } catch (_: InterruptedIOException) {
                // A superseded selection is expected and must not replace the active pack.
            } catch (throwable: Throwable) {
                if (publishDecodeFailureIfCurrent(request, throwable)) {
                    DebugEventLog.recordThrowable("sound_pack_decode_failed", throwable, "car=${request.carId}")
                }
            } finally {
                decodedProfile?.let { abandoned ->
                    if (decodedProfileIsResident) {
                        releaseNativeProfile(abandoned)
                    } else {
                        // The exact reservation still represents this allocation
                        // until releaseDecodeReservation() below.
                        abandoned.closeOnce()
                    }
                }
                releaseDecodeReservation(request)
                activeDecode.compareAndSet(request, null)
                request.cancellation.close()
            }
        }
        queuedDecode.getAndSet(null)?.let(::discardDecodeRequest)
        pendingNativeProfile.getAndSet(null)?.let(::releaseNativeProfile)
        drainRealtimeFailures()
        drainRetiredProfiles()
    }

    /** Formats and persists realtime failures only on the decoder/retirement worker. */
    private fun drainRealtimeFailures() {
        while (true) {
            val failure = realtimeFailures.poll() ?: return
            if (failure.droppedBefore > 0L) {
                DebugEventLog.warning(
                    "audio_renderer_failure_mailbox_overflow",
                    "dropped=${failure.droppedBefore}",
                )
            }
            val description = failure.throwable.let { throwable ->
                "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            }
            if (generation.get() == failure.runId) {
                outputState.updateAndGet { previous ->
                    previous.copy(
                        running = false,
                        activeChannels = 0,
                        activeLayout = "OFFLINE",
                        sampleStatus = "ERROR",
                        focusGranted = false,
                        error = description,
                    )
                }
            }
            DebugEventLog.recordThrowable(
                "audio_renderer_failed",
                failure.throwable,
                "code=${failure.failureCode} run_id=${failure.runId}",
            )
            DebugEventLog.warning(
                "audio_renderer_stopped",
                "code=${failure.failureCode} run_id=${failure.runId} error=$description",
            )
        }
    }

    /**
     * Serial validation and publication share [lifecycleLock] with car selection. Without that
     * ordering, an old decode could pass its serial check, lose the CPU to a newer selection that
     * clears pending state, then publish the old car after the clear.
     */
    private fun publishDecodedProfileIfCurrent(
        request: SoundDecodeRequest,
        prepared: PreparedNativeSoundProfile,
    ): Boolean = synchronized(lifecycleLock) {
        if (!DecodePublicationPolicy.canPublish(
                closed = closed.get(),
                requestSerial = request.serial,
                currentSerial = decodeGeneration.get(),
                cancelled = request.cancellation.isCancelled(),
                newerRequestQueued = queuedDecode.get() != null,
            )
        ) {
            return@synchronized false
        }
        pendingNativeProfile.getAndSet(prepared)?.let(::releaseNativeProfile)
        outputState.updateAndGet {
            it.copy(
                packLoadStatus = "READY",
                packLoadFamily = prepared.familyId,
                packLoadCar = prepared.carId,
                packLoadError = null,
            )
        }
        true
    }

    /** Prevents a superseded decode failure from replacing the newer car's LOADING/READY state. */
    private fun publishDecodeFailureIfCurrent(
        request: SoundDecodeRequest,
        throwable: Throwable,
    ): Boolean = synchronized(lifecycleLock) {
        if (!DecodePublicationPolicy.canPublish(
                closed = closed.get(),
                requestSerial = request.serial,
                currentSerial = decodeGeneration.get(),
                cancelled = request.cancellation.isCancelled(),
                newerRequestQueued = queuedDecode.get() != null,
            )
        ) {
            return@synchronized false
        }
        outputState.updateAndGet {
            it.copy(
                packLoadStatus = "ERROR",
                packLoadError = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
            )
        }
        true
    }

    private fun discardDecodeRequest(request: SoundDecodeRequest) {
        request.cancellation.cancel()
        request.cancellation.close()
    }

    /**
     * Reserves peak native memory before libFLAC allocates. Car selection has
     * already queued a fade-to-silence, so a too-large old+new overlap drains
     * naturally instead of exceeding the hard budget or deadlocking activation.
     */
    private fun reserveDecodeBytes(request: SoundDecodeRequest) {
        val requested = request.family.manifest.totalDecodedBytes
        NativeSoundFamilyLoader.validateDecodedBudget(requested, decodeBudget)
        while (workerRunning.get()) {
            checkDecodeCurrent(request)
            drainRetiredProfiles()
            if (nativeMemory.tryReserve(requested)) {
                request.reservedBytes = requested
                return
            } else {
                LockSupport.parkNanos(NATIVE_BUDGET_RETRY_NANOS)
            }
        }
        throw InterruptedIOException("Sound-family decoder stopped")
    }

    private fun checkDecodeCurrent(request: SoundDecodeRequest) {
        if (
            Thread.currentThread().isInterrupted || request.cancellation.isCancelled() ||
            request.serial != decodeGeneration.get() || queuedDecode.get() != null
        ) {
            throw InterruptedIOException("Sound-family decode cancelled")
        }
    }

    private fun transferDecodeReservation(
        request: SoundDecodeRequest,
        prepared: PreparedNativeSoundProfile,
    ) {
        val reserved = request.reservedBytes
        check(reserved > 0L && prepared.decodedBytes <= reserved) { "Native decode exceeded its reservation" }
        nativeMemory.transferReservation(reserved, prepared.decodedBytes)
        request.reservedBytes = 0L
    }

    private fun releaseDecodeReservation(request: SoundDecodeRequest) {
        val reserved = request.reservedBytes
        if (reserved == 0L) return
        request.reservedBytes = 0L
        nativeMemory.releaseReservation(reserved)
    }

    private fun releaseNativeProfile(profile: PreparedNativeSoundProfile) {
        if (profile.closeOnce()) nativeMemory.releaseResident(profile.decodedBytes)
    }

    /** Lock-free enqueue: safe for the audio thread; native frees happen on decodeWorker. */
    private fun retireNativeProfile(profile: PreparedNativeSoundProfile) {
        if (!profile.markRetirementQueued()) return
        do {
            profile.retireNext = retiredNativeProfiles.get()
        } while (!retiredNativeProfiles.compareAndSet(profile.retireNext, profile))
        LockSupport.unpark(decodeWorker)
    }

    private fun drainRetiredProfiles() {
        var profile = retiredNativeProfiles.getAndSet(null)
        while (profile != null) {
            val next = profile.retireNext
            profile.retireNext = null
            releaseNativeProfile(profile)
            profile = next
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
        setFocusGain(0.0)
        if (!reacquireFocusLocked()) {
            running.set(false)
            outputState.updateAndGet {
                it.copy(
                    running = false,
                    sampleStatus = "OFFLINE",
                    activeChannels = 0,
                    activeLayout = "OFFLINE",
                    focusGranted = false,
                    error = "Audio focus request denied",
                )
            }
            return
        }

        running.set(true)
        val runId = generation.incrementAndGet()
        outputState.set(
            AudioOutputState(
                running = true,
                sampleStatus = "STARTING",
                activeLayout = "STARTING",
                focusGranted = true,
                sampleProfile = sampleProfile.id,
            ),
        )

        val thread = Thread({ renderLoop(runId, sampleProfile) }, "engine-audio-renderer").apply { isDaemon = true }
        renderThread.set(thread)
        try {
            thread.start()
        } catch (throwable: Throwable) {
            renderThread.compareAndSet(thread, null)
            running.set(false)
            setFocusGain(0.0)
            abandonFocusIfHeld()
            outputState.updateAndGet {
                it.copy(
                    running = false,
                    activeLayout = "OFFLINE",
                    sampleStatus = "OFFLINE",
                    focusGranted = false,
                    error = "Audio renderer start failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                )
            }
            DebugEventLog.recordThrowable("audio_renderer_start_failed", throwable)
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
                activeTrack.compareAndSet(track, null)
                runCatching { track?.release() }
                liveThread.interrupt()
                stopped = joinThread(liveThread, RENDER_FORCE_RELEASE_JOIN_MS)
            }
        }
        if (stopped && track != null && activeTrack.compareAndSet(track, null)) releaseTrack(track)
        if (stopped) renderThread.compareAndSet(thread, null)
        activeRenderer.set(null)
        runtimeCounters.set(null)

        setFocusGain(0.0)
        abandonFocusIfHeld()
        outputState.updateAndGet { previous ->
            previous.copy(
                running = false,
                sampleStatus = "OFFLINE",
                activeChannels = 0,
                activeLayout = "OFFLINE",
                sampleRate = 0,
                framesPerWrite = 0,
                bufferFrames = 0,
                queuedFrames = 0,
                sessionId = 0,
                routedDevice = "none",
                focusGranted = false,
                error = error ?: if (stopped) previous.error else {
                    "Audio renderer did not stop within ${RENDER_JOIN_TIMEOUT_MS + RENDER_FORCE_RELEASE_JOIN_MS} ms"
                },
            )
        }
        return stopped
    }

    private fun renderLoop(runId: Long, sampleProfile: EngineSampleProfile) {
        var opened: OpenedTrack? = null
        var renderer: SampleEngineRenderer? = null
        var nativeProfile: PreparedNativeSoundProfile? = null
        var swapOldRenderer: SampleEngineRenderer? = null
        var swapOldNative: PreparedNativeSoundProfile? = null
        var realtimeFailurePublished = false

        try {
            // The base APK deliberately contains no sample media. If a selected
            // family finished decoding before AudioTrack starts, activate it here
            // without a silent first burst or a needless pack crossfade.
            val preparedAtStart = pendingNativeProfile.getAndSet(null)
            val preparedSilence = pendingSilentRenderer.getAndSet(null)
            nativeProfile = preparedAtStart
            val sampleRenderer = preparedAtStart?.renderer ?: preparedSilence
                ?: SampleEngineRenderer.fromDecoded(
                    OUTPUT_SAMPLE_RATE,
                    emptyMap(),
                    sampleProfile.silentPlaceholder(),
                )
            renderer = sampleRenderer
            val initialDiagnostics = sampleRenderer.diagnostics()
            val warmup = ShortArray(WARMUP_FRAMES * OUTPUT_CHANNEL_COUNT)
            val realtimeFrame = MutableEngineAudioFrame()
            parameters.readInto(realtimeFrame)
            repeat(WARMUP_PASSES) { sampleRenderer.render(realtimeFrame, warmup, gain = 0.0) }
            if (!isCurrent(runId)) return

            val active = openTrack()
            opened = active
            if (!activeTrack.compareAndSet(null, active.track)) {
                throw IllegalStateException("another AudioTrack is still active")
            }
            if (!isCurrent(runId)) throw IllegalStateException("renderer cancelled")

            val primingFrames = active.bufferFrames.coerceAtLeast(active.framesPerWrite)
            val primingBuffer = ShortArray(primingFrames * OUTPUT_CHANNEL_COUNT)
            if (!writeFully(active.track, primingBuffer, runId)) {
                throw IllegalStateException("renderer cancelled during priming")
            }
            if (!isCurrent(runId)) throw IllegalStateException("renderer cancelled before play")
            active.track.play()
            if (active.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("AudioTrack did not enter PLAYING state")
            }

            val counters = TrackRuntimeCounters(
                bufferFrames = active.track.bufferSizeInFrames,
                bufferTargetMilliseconds = active.adaptiveBuffer.targetMilliseconds,
            )
            runtimeCounters.set(counters)
            activeRenderer.set(sampleRenderer)
            activeNativeProfile.set(nativeProfile)
            outputState.updateAndGet { previous ->
                AudioOutputState(
                    running = true,
                    sampleStatus = "ACTIVE",
                    activeChannels = OUTPUT_CHANNEL_COUNT,
                    activeLayout = OUTPUT_LAYOUT_LABEL,
                    sampleRate = OUTPUT_SAMPLE_RATE,
                    framesPerWrite = active.framesPerWrite,
                    bufferFrames = counters.bufferFrames,
                    targetBufferMilliseconds = counters.bufferTargetMilliseconds,
                    sessionId = active.track.audioSessionId,
                    routedDevice = routedDeviceName(active.track),
                    advertisedChannels = "fixed stereo",
                    focusGranted = previous.focusGranted,
                    sampleProfile = sampleProfile.id,
                    sampleLoadedLoops = initialDiagnostics.loadedLoops,
                    sampleLoadedEffects = initialDiagnostics.loadedEffects,
                    sampleDecodedBytes = initialDiagnostics.decodedBytes,
                )
            }

            val stereoProgram = ShortArray(active.framesPerWrite * OUTPUT_CHANNEL_COUNT)
            val playbackHead = PlaybackHeadTracker()
            var totalFramesWritten = primingFrames.toLong()
            var writes = 0
            var startupCaptured = false
            var swapFramesRemaining = 0
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (isCurrent(runId)) {
                val preparedPack = pendingNativeProfile.getAndSet(null)
                val preparedSilent = pendingSilentRenderer.getAndSet(null)
                if (preparedPack != null || preparedSilent != null) {
                    swapOldNative?.let(::retireNativeProfile)
                    swapOldRenderer = renderer
                    swapOldNative = nativeProfile
                    renderer = preparedPack?.renderer ?: requireNotNull(preparedSilent)
                    nativeProfile = preparedPack
                    activeNativeProfile.set(preparedPack)
                    activeRenderer.set(renderer)
                    swapFramesRemaining = PACK_SWAP_FADE_FRAMES
                }
                val renderStarted = System.nanoTime()
                val transitionBurst = swapFramesRemaining > 0 && swapOldRenderer != null
                val currentRenderer = renderer ?: sampleRenderer
                parameters.readInto(realtimeFrame)
                currentRenderer.render(
                    realtimeFrame,
                    stereoProgram,
                    focusGain(),
                    popsAndBangsAuditionSerial.get(),
                )
                val fadingRenderer = swapOldRenderer
                if (swapFramesRemaining > 0 && fadingRenderer != null) {
                    fadingRenderer.render(
                        realtimeFrame,
                        swapScratch,
                        focusGain(),
                        popsAndBangsAuditionSerial.get(),
                        active.framesPerWrite,
                    )
                    val fadeStart = PACK_SWAP_FADE_FRAMES - swapFramesRemaining
                    var frame = 0
                    while (frame < active.framesPerWrite) {
                        val newWeight = ((fadeStart + frame).toDouble() / PACK_SWAP_FADE_FRAMES).coerceIn(0.0, 1.0)
                        val oldWeight = 1.0 - newWeight
                        val sampleIndex = frame * OUTPUT_CHANNEL_COUNT
                        stereoProgram[sampleIndex] = blendPcm16(
                            swapScratch[sampleIndex], stereoProgram[sampleIndex], oldWeight, newWeight,
                        )
                        stereoProgram[sampleIndex + 1] = blendPcm16(
                            swapScratch[sampleIndex + 1], stereoProgram[sampleIndex + 1], oldWeight, newWeight,
                        )
                        frame += 1
                    }
                    swapFramesRemaining -= active.framesPerWrite
                    if (swapFramesRemaining <= 0) {
                        swapOldNative?.let(::retireNativeProfile)
                        swapOldNative = null
                        swapOldRenderer = null
                    }
                }
                counters.renderTiming.record(System.nanoTime() - renderStarted, transitionBurst)
                if (!writeFully(active.track, stereoProgram, runId)) break
                totalFramesWritten += active.framesPerWrite
                writes += 1

                if (writes % BUFFER_OBSERVATION_WRITES == 0) {
                    val playedFrames = playbackHead.update(active.track.playbackHeadPosition)
                    val queued = (totalFramesWritten - playedFrames)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                    val underruns = active.track.underrunCount
                    if (!startupCaptured && writes >= STARTUP_UNDERRUN_WINDOW_WRITES) {
                        counters.startupUnderruns = underruns
                        startupCaptured = true
                    }
                    counters.queuedFrames = queued
                    counters.underruns = underruns
                    counters.steadyStateUnderruns = if (startupCaptured) {
                        (underruns - counters.startupUnderruns).coerceAtLeast(0)
                    } else {
                        0
                    }

                    val adjustment = active.adaptiveBuffer.observe(underruns, queued, System.nanoTime())
                    if (adjustment != BufferAdjustment.NONE) {
                        val requestedFrames = max(active.minimumFrames, active.adaptiveBuffer.targetFrames())
                        val resized = active.track.setBufferSizeInFrames(requestedFrames)
                        if (resized > 0) {
                            counters.bufferFrames = resized
                        } else {
                            counters.bufferResizeFailures += 1
                            counters.bufferFrames = active.track.bufferSizeInFrames
                        }
                        counters.bufferTargetMilliseconds = active.adaptiveBuffer.targetMilliseconds
                        counters.bufferAdjustmentCount = active.adaptiveBuffer.adjustmentCount
                        counters.lastBufferAdjustment = adjustment
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (isCurrent(runId)) {
                // Realtime path: no strings, stacktrace formatting, DebugEventLog monitor or
                // allocated diagnostic record. The existing Throwable reference and primitives
                // are consumed by the decoder/retirement worker.
                realtimeFailures.publish(RENDER_LOOP_FAILURE_CODE, runId, throwable)
                realtimeFailurePublished = true
                LockSupport.unpark(decodeWorker)
            }
        } finally {
            activeRenderer.compareAndSet(renderer, null)
            activeNativeProfile.compareAndSet(nativeProfile, null)
            // A stop can land inside the 30 ms family crossfade. Both native
            // programs then remain owned by this render invocation and must be
            // retired off the realtime thread.
            swapOldNative?.takeIf { it !== nativeProfile }?.let(::retireNativeProfile)
            nativeProfile?.let(::retireNativeProfile)
            runtimeCounters.set(null)
            opened?.track?.let { track ->
                activeTrack.compareAndSet(track, null)
                releaseTrack(track)
            }
            renderThread.compareAndSet(Thread.currentThread(), null)
            if (generation.get() == runId) {
                running.set(false)
                setFocusGain(0.0)
                abandonFocusIfHeld()
                outputState.updateAndGet {
                    it.copy(
                        running = false,
                        activeChannels = 0,
                        activeLayout = "OFFLINE",
                        sampleStatus = if (realtimeFailurePublished) "ERROR" else "OFFLINE",
                        focusGranted = false,
                        // The non-RT failure worker publishes the formatted error text.
                        error = it.error ?: if (realtimeFailurePublished) null else {
                            "Audio renderer stopped unexpectedly"
                        },
                    )
                }
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun openTrack(): OpenedTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "Fixed stereo output is unsupported (minBuffer=$minBytes)" }

        val framesPerBurst = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.coerceIn(MIN_FRAMES_PER_BURST, MAX_FRAMES_PER_BURST)
            ?: DEFAULT_FRAMES_PER_BURST
        val minimumFrames = (minBytes + OUTPUT_BYTES_PER_FRAME - 1) / OUTPUT_BYTES_PER_FRAME
        val adaptiveBuffer = AdaptiveAudioBuffer(OUTPUT_SAMPLE_RATE)
        val maximumAdaptiveFrames = millisecondsToFrames(MAX_BUFFER_MILLISECONDS)
        val capacityFrames = max(minimumFrames, maximumAdaptiveFrames)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT in 24..25) setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(capacityFrames * OUTPUT_BYTES_PER_FRAME)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Fixed stereo AudioTrack did not initialize")
        }
        val requestedInitialFrames = max(minimumFrames, adaptiveBuffer.targetFrames())
        val resizedFrames = track.setBufferSizeInFrames(requestedInitialFrames)
        val activeBufferFrames = if (resizedFrames > 0) resizedFrames else track.bufferSizeInFrames
        return OpenedTrack(
            track = track,
            framesPerWrite = framesPerBurst,
            minimumFrames = minimumFrames,
            bufferFrames = activeBufferFrames,
            adaptiveBuffer = adaptiveBuffer,
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
    private fun reacquireFocusLocked(): Boolean {
        if (focusHeld.get()) return true
        val focusResult = runCatching {
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        val granted = focusResult.getOrDefault(false)
        if (granted) {
            focusHeld.set(true)
            setFocusGain(1.0)
            outputState.updateAndGet { it.copy(focusGranted = true, error = null) }
        } else {
            setFocusGain(0.0)
            outputState.updateAndGet { it.copy(focusGranted = false) }
            focusResult.exceptionOrNull()?.let {
                DebugEventLog.recordThrowable("audio_focus_request_failed", it, "output=fixed_stereo")
            } ?: DebugEventLog.warning("audio_focus_denied", "output=fixed_stereo")
        }
        return granted
    }

    @Suppress("DEPRECATION")
    private fun abandonFocusIfHeld() {
        if (focusHeld.compareAndSet(true, false)) {
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
    }

    private fun setFocusGain(value: Double) {
        focusGainBits.set(value.toRawBits())
    }

    private fun focusGain(): Double = Double.fromBits(focusGainBits.get())

    /** Device-test visibility for the focus envelope; never used by the production render path. */
    internal fun focusGainForTests(): Double = focusGain()

    /** Device-test visibility for permanent-loss/reacquisition behavior. */
    internal fun focusHeldForTests(): Boolean = focusHeld.get()

    private fun millisecondsToFrames(milliseconds: Int): Int =
        ((OUTPUT_SAMPLE_RATE.toLong() * milliseconds + 999L) / 1_000L).toInt()

    private data class OpenedTrack(
        val track: AudioTrack,
        val framesPerWrite: Int,
        val minimumFrames: Int,
        val bufferFrames: Int,
        val adaptiveBuffer: AdaptiveAudioBuffer,
    )

    private data class SoundDecodeRequest(
        val serial: Long,
        val family: InstalledSoundFamily,
        val carId: String,
        val cancellation: NativeDecodeCancellation,
    ) {
        @Volatile var reservedBytes: Long = 0L
    }

    private class TrackRuntimeCounters(
        bufferFrames: Int,
        bufferTargetMilliseconds: Int,
    ) {
        @Volatile var bufferFrames = bufferFrames
        @Volatile var bufferTargetMilliseconds = bufferTargetMilliseconds
        @Volatile var queuedFrames = 0
        @Volatile var bufferAdjustmentCount = 0
        @Volatile var bufferResizeFailures = 0
        @Volatile var lastBufferAdjustment = BufferAdjustment.NONE
        @Volatile var underruns = 0
        @Volatile var startupUnderruns = 0
        @Volatile var steadyStateUnderruns = 0
        val renderTiming = RealtimeRenderTiming()
    }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val OUTPUT_CHANNEL_COUNT = 2
        const val OUTPUT_BYTES_PER_FRAME = OUTPUT_CHANNEL_COUNT * Short.SIZE_BYTES
        const val OUTPUT_LAYOUT_LABEL = "STEREO / CABIN DSP"
        const val MAX_BUFFER_MILLISECONDS = 80
        const val MIN_FRAMES_PER_BURST = 64
        const val MAX_FRAMES_PER_BURST = 2_048
        const val DEFAULT_FRAMES_PER_BURST = 256
        const val WARMUP_FRAMES = 256
        const val WARMUP_PASSES = 3
        const val NATIVE_BUDGET_RETRY_NANOS = 5_000_000L
        const val PACK_SWAP_FADE_FRAMES = OUTPUT_SAMPLE_RATE * 30 / 1_000
        const val BUFFER_OBSERVATION_WRITES = 16
        const val STARTUP_UNDERRUN_WINDOW_WRITES = 48
        const val DUCK_GAIN = 0.20
        const val NANOS_PER_MICROSECOND = 1_000L
        const val RENDER_JOIN_TIMEOUT_MS = 750L
        const val RENDER_FORCE_RELEASE_JOIN_MS = 250L
        const val DECODE_WORKER_JOIN_TIMEOUT_MS = 5_000L
        const val RENDER_LOOP_FAILURE_CODE = 1
    }
}

/**
 * A decode-in-flight renderer must contain no playable graph references. Keeping authored
 * one-shot programs while removing their leaf effects makes construction fail for every strict
 * V2 family that carries an ENGINE_EVENT program.
 */
internal fun EngineSampleProfile.silentPlaceholder(): EngineSampleProfile = copy(
    layers = emptyList(),
    effects = emptyList(),
    oneShotPrograms = emptyList(),
)

/** Pure half of the decode publication gate; synchronization is owned by [EngineAudioEngine]. */
internal object DecodePublicationPolicy {
    fun canPublish(
        closed: Boolean,
        requestSerial: Long,
        currentSerial: Long,
        cancelled: Boolean,
        newerRequestQueued: Boolean,
    ): Boolean = !closed && requestSerial == currentSerial && !cancelled && !newerRequestQueued
}

private fun blendPcm16(old: Short, new: Short, oldWeight: Double, newWeight: Double): Short =
    (old.toDouble() * oldWeight + new.toDouble() * newWeight)
        .toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()

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
