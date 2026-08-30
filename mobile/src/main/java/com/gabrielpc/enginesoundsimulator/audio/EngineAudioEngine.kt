package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
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
 * Lifecycle and audio-focus facade for the native FMOD Studio runtime.
 *
 * One worker owns the FMOD system for its entire lifetime and is the only thread that performs
 * regular Studio updates. Kotlin copies raw vehicle values through one short synchronized section;
 * the producer, worker snapshot, planner/interpolator state, and JNI buffer are all reused on their
 * hot paths.
 */
class EngineAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val desiredRunning = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val parametersLock = Any()
    private val parameters = EngineAudioFrame()
    private val selectedProfile = AtomicReference(FmodCarProfiles.default)
    private val loadedProfileId = AtomicReference<String?>(null)
    private val loadFailure = AtomicReference<AudioLoadFailure?>(null)
    private val focusMultiplier = AtomicReference(0.0)
    private val focusHeld = AtomicBoolean(false)
    private val mixerSuspended = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>(null)
    private val workerProfileId = AtomicReference<String?>(null)
    private val activeBridge = AtomicReference<FmodNativeBridge?>(null)
    private val profileSwitchDispatching = AtomicBoolean(false)
    private val validationRequested = AtomicBoolean(false)
    private val validationRunning = AtomicBoolean(false)
    private val validationResult = AtomicReference<FmodRenderedAudioValidationResult?>(null)

    @Volatile
    private var focusChangeListener: ((AudioFocusEvent) -> Unit)? = null

    @Volatile
    private var validationListener: ((FmodRenderedAudioValidationResult) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusMultiplier.set(1.0)
                    if (mixerSuspended.compareAndSet(true, false)) {
                        logFailure("resume FMOD mixer", activeBridge.get()?.resumeMixer())
                    }
                    focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_GAIN)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusMultiplier.set(DUCK_GAIN)
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_DUCK)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusMultiplier.set(0.0)
                if (mixerSuspended.compareAndSet(false, true)) {
                    logFailure("suspend FMOD mixer", activeBridge.get()?.suspendMixer())
                }
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_LOSS)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusMultiplier.set(0.0)
                focusHeld.set(false)
                desiredRunning.set(false)
                focusChangeListener?.invoke(AudioFocusEvent.PERMANENT_LOSS)
                synchronized(lifecycleLock) {
                    if (running.get() || workerThread.get() != null) stopLocked()
                }
            }
        }
    }

    fun loadedCarProfileId(): String? = loadedProfileId.get()

    /** Returns and clears the most recent profile startup/runtime failure. */
    internal fun consumeLoadFailure(): AudioLoadFailure? = loadFailure.getAndSet(null)

    internal fun diagnostics(): String = activeBridge.get()?.diagnostics() ?: "FMOD is not running."

    /** Queues one deterministic native render check on the FMOD worker. */
    fun requestRenderedAudioValidation(): Boolean {
        if (!running.get() || loadedProfileId.get() == null) return false
        if (validationRunning.get()) return false
        if (!validationRequested.compareAndSet(false, true)) return false
        // Close the hand-off race where the worker claimed the previous request after the
        // first running check but before this request was queued.
        if (validationRunning.get()) {
            validationRequested.compareAndSet(true, false)
            return false
        }
        validationResult.set(null)
        return true
    }

    fun isRenderedAudioValidationRunning(): Boolean =
        validationRequested.get() || validationRunning.get()

    fun latestRenderedAudioValidation(): FmodRenderedAudioValidationResult? = validationResult.get()

    fun consumeRenderedAudioValidation(): FmodRenderedAudioValidationResult? =
        validationResult.getAndSet(null)

    /** Listener is dispatched on Android's main thread. */
    fun setRenderedAudioValidationListener(
        listener: ((FmodRenderedAudioValidationResult) -> Unit)?,
    ) {
        validationListener = listener
    }

    fun isAudioActive(): Boolean = synchronized(lifecycleLock) {
        running.get() && workerThread.get()?.isAlive == true
    }

    fun update(frame: EngineAudioFrame) {
        synchronized(parametersLock) { parameters.overwrite(frame) }
    }

    /** Allocation-free publication path used by the 200 Hz drive loop. */
    fun update(
        rpm: Double,
        throttle: Double,
        drivetrainSpeed: Double,
        enabled: Boolean,
        masterGain: Double,
        shiftSerial: Long,
        shiftDirection: Int,
        isShifting: Boolean,
        shiftTargetRpm: Double,
        limiterActive: Boolean,
        loadOnlyEnabled: Boolean,
        coastOnlyEnabled: Boolean,
        eventMixSettings: FmodEventMixSettings,
    ) {
        synchronized(parametersLock) {
            parameters.overwrite(
                rpm = rpm,
                throttle = throttle,
                drivetrainSpeed = drivetrainSpeed,
                enabled = enabled,
                masterGain = masterGain,
                shiftSerial = shiftSerial,
                shiftDirection = shiftDirection,
                isShifting = isShifting,
                shiftTargetRpm = shiftTargetRpm,
                limiterActive = limiterActive,
                loadOnlyEnabled = loadOnlyEnabled,
                coastOnlyEnabled = coastOnlyEnabled,
                eventMixSettings = eventMixSettings,
            )
        }
    }

    fun setFocusChangeListener(listener: ((AudioFocusEvent) -> Unit)?) {
        focusChangeListener = listener
    }

    fun setCarProfile(profile: FmodCarProfile) {
        val previous = selectedProfile.getAndSet(profile)
        if (previous.id == profile.id) return
        // Never let the newly loaded bank observe an enabled frame calibrated for the old car.
        // DriveController will publish the current vehicle state again after the switch.
        synchronized(parametersLock) {
            parameters.overwrite(
                rpm = profile.idleRpm,
                throttle = 0.0,
                drivetrainSpeed = 0.0,
                enabled = false,
                masterGain = 1.0,
                shiftSerial = 0L,
                shiftDirection = 0,
                isShifting = false,
                shiftTargetRpm = profile.idleRpm,
                limiterActive = false,
                loadOnlyEnabled = false,
                coastOnlyEnabled = false,
                eventMixSettings = FmodEventMixSettings.DEFAULT,
            )
        }
        // Synchronous UI state, asynchronous stop/join/restart work.
        loadedProfileId.set(null)
        validationRequested.set(false)
        validationResult.set(null)
        if (desiredRunning.get()) dispatchProfileSwitch()
    }

    fun start() {
        desiredRunning.set(true)
        synchronized(lifecycleLock) {
            if (running.get() && workerThread.get()?.isAlive == true) return
            startLocked()
        }
    }

    fun stop() {
        desiredRunning.set(false)
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    private fun startLocked() {
        if (
            running.get() ||
            workerThread.get() != null ||
            activeBridge.get() != null ||
            focusHeld.get()
        ) {
            if (!stopLocked()) return
        }

        val profile = selectedProfile.get()
        focusMultiplier.set(0.0)
        val focusResult = runCatching { requestFocus() }
        if (!focusResult.getOrDefault(false)) {
            reportFailure(
                profile.id,
                focusResult.exceptionOrNull()?.message ?: "Audio focus was not granted by the system.",
            )
            return
        }

        focusHeld.set(true)
        focusMultiplier.set(1.0)
        mixerSuspended.set(false)
        loadFailure.set(null)
        running.set(true)
        val runId = generation.incrementAndGet()
        val thread = Thread({ controlLoop(runId, profile) }, WORKER_NAME).apply { isDaemon = true }
        workerProfileId.set(profile.id)
        workerThread.set(thread)
        try {
            thread.start()
        } catch (throwable: Throwable) {
            workerThread.compareAndSet(thread, null)
            workerProfileId.compareAndSet(profile.id, null)
            running.set(false)
            focusMultiplier.set(0.0)
            reportFailure(profile.id, throwable.message ?: "Could not start the FMOD control worker.")
            abandonFocusIfHeld()
        }
    }

    /** Must be called with [lifecycleLock] held. */
    private fun stopLocked(): Boolean {
        running.set(false)
        generation.incrementAndGet()
        validationRequested.set(false)
        activeBridge.get()?.cancelRenderedAudioValidation()
        val thread = workerThread.get()
        thread?.interrupt()

        var stopped = thread == null || !thread.isAlive
        if (!stopped && thread != null && thread !== Thread.currentThread()) {
            stopped = joinThread(thread, WORKER_JOIN_TIMEOUT_MS)
        }

        if (stopped) {
            workerThread.compareAndSet(thread, null)
            workerProfileId.set(null)
            activeBridge.getAndSet(null)?.close()
        }
        loadedProfileId.set(null)
        focusMultiplier.set(0.0)
        mixerSuspended.set(false)
        validationRunning.set(false)
        abandonFocusIfHeld()
        return stopped
    }

    private fun controlLoop(runId: Long, profile: FmodCarProfile) {
        var bridge: FmodNativeBridge? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val opened = FmodNativeBridge.open(appContext)
            bridge = opened.bridge
            if (bridge == null) {
                reportFailure(profile.id, opened.error ?: "FMOD could not be initialized.")
                return
            }
            if (!activeBridge.compareAndSet(null, bridge)) {
                throw IllegalStateException("another FMOD runtime is still active")
            }
            when (val result = bridge.loadBanks(profile.id)) {
                FmodNativeCallResult.Success -> Unit
                is FmodNativeCallResult.Failure -> {
                    reportFailure(profile.id, result.detail)
                    return
                }
            }
            if (!isCurrent(runId, profile.id)) return

            val planner = FmodControlPlanner(profile)
            val continuousParameters = FmodContinuousParameterInterpolator()
            val controlBuffer = FmodNativeBridge.allocateControlBuffer()
            val frameSnapshot = EngineAudioFrame(rpm = profile.idleRpm)
            loadedProfileId.set(profile.id)
            var previousNanos = SystemClock.elapsedRealtimeNanos()
            val updateScheduler = FmodControlUpdateScheduler(CONTROL_PERIOD_NANOS)
            updateScheduler.reset(previousNanos)

            while (isCurrent(runId, profile.id)) {
                if (
                    validationRequested.get() &&
                    validationRunning.compareAndSet(false, true)
                ) {
                    validationRequested.set(false)
                    val result = try {
                        bridge.validateRenderedAudio()
                    } finally {
                        validationRunning.set(false)
                    }
                    if (isCurrent(runId, profile.id)) {
                        validationResult.set(result)
                        mainHandler.post {
                            if (selectedProfile.get().id == result.profileId) {
                                validationListener?.invoke(result)
                            }
                        }
                    }
                    previousNanos = SystemClock.elapsedRealtimeNanos()
                    updateScheduler.reset(previousNanos)
                    continue
                }

                val remaining = updateScheduler.remainingUntilSubmission(
                    SystemClock.elapsedRealtimeNanos(),
                )
                if (remaining > 0L) {
                    LockSupport.parkNanos(remaining)
                    continue
                }

                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val dt = ((nowNanos - previousNanos) / NANOS_PER_SECOND).coerceIn(0.001, 0.100)
                previousNanos = nowNanos
                synchronized(parametersLock) { frameSnapshot.overwrite(parameters) }
                val state = continuousParameters.apply(
                    planner.update(frameSnapshot, dt),
                    dt,
                    isShifting = frameSnapshot.isShifting,
                    shiftSerial = frameSnapshot.shiftSerial,
                    shiftTargetRpm = frameSnapshot.shiftTargetRpm.toFloat(),
                )
                writeControlBuffer(controlBuffer, state, focusMultiplier.get())
                when (val updateResult = bridge.update(controlBuffer)) {
                    FmodNativeCallResult.Success -> Unit
                    is FmodNativeCallResult.Failure -> throw IllegalStateException(updateResult.detail)
                }
                // A delayed wakeup emits the latest continuous state once. Schedule from the
                // completed FMOD call, so preemption inside it cannot make several deadlines
                // collapse into one 64-frame mixer block as a pitch or turbo-boost step.
                updateScheduler.recordCompletedSubmission(SystemClock.elapsedRealtimeNanos())
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "FMOD control worker stopped for ${profile.id}", throwable)
            if (isCurrent(runId, profile.id)) {
                reportFailure(profile.id, throwable.message ?: throwable::class.java.simpleName)
            }
        } finally {
            loadedProfileId.compareAndSet(profile.id, null)
            activeBridge.compareAndSet(bridge, null)
            runCatching { bridge?.close() }
            workerThread.compareAndSet(Thread.currentThread(), null)
            workerProfileId.compareAndSet(profile.id, null)
            if (generation.get() == runId) {
                running.set(false)
                focusMultiplier.set(0.0)
                mixerSuspended.set(false)
                validationRunning.set(false)
                abandonFocusIfHeld()
            }
        }
    }

    private fun writeControlBuffer(
        buffer: ByteBuffer,
        state: FmodControlState,
        focusGain: Double,
    ) {
        val layout = FmodNativeBridge.ControlBufferLayout
        buffer.putInt(layout.SCHEMA_OFFSET, layout.SCHEMA_VERSION)
        buffer.putInt(layout.ENABLED_MASK_OFFSET, state.flags)
        buffer.putFloat(layout.RPM_OFFSET, state.rpm)
        buffer.putFloat(layout.ENGINE_THROTTLE_OFFSET, state.engineThrottle)
        buffer.putFloat(layout.BOOST_OFFSET, state.boost)
        buffer.putFloat(layout.BOV_OFFSET, state.bov)
        buffer.putFloat(layout.BOV_DECAY_OFFSET, state.bovDecaySeconds)
        buffer.putFloat(layout.LIMITER_DECAY_OFFSET, state.limiterDecaySeconds)
        buffer.putFloat(layout.MASTER_GAIN_OFFSET, state.masterGain * focusGain.coerceIn(0.0, 1.0).toFloat())
        buffer.putFloat(layout.ENGINE_GAIN_OFFSET, state.engineGain)
        buffer.putFloat(layout.TURBO_GAIN_OFFSET, state.turboGain)
        buffer.putFloat(layout.LIMITER_GAIN_OFFSET, state.limiterGain)
        buffer.putFloat(layout.SHIFT_GAIN_OFFSET, state.shiftGain)
        buffer.putFloat(layout.BACKFIRE_GAIN_OFFSET, state.backfireGain)
        buffer.putInt(layout.SHIFT_DIRECTION_OFFSET, state.shiftDirection)
        buffer.putInt(layout.RESERVED_OFFSET, 0)
        buffer.putLong(layout.SHIFT_SERIAL_OFFSET, state.shiftSerial)
        buffer.putLong(layout.LIMITER_SERIAL_OFFSET, state.limiterSerial)
        buffer.putLong(layout.BOV_SERIAL_OFFSET, state.bovSerial)
        buffer.putLong(layout.BACKFIRE_SERIAL_OFFSET, state.backfireSerial)
        buffer.putFloat(layout.DRIVETRAIN_SPEED_OFFSET, state.drivetrainSpeed)
        buffer.putFloat(layout.TRANSMISSION_THROTTLE_OFFSET, state.transmissionThrottle)
        buffer.putFloat(layout.TRANSMISSION_GAIN_OFFSET, state.transmissionGain)
        buffer.putFloat(layout.RESERVED_V2_OFFSET, 0f)
    }

    private fun isCurrent(runId: Long, profileId: String): Boolean =
        running.get() && generation.get() == runId && selectedProfile.get().id == profileId

    private fun dispatchProfileSwitch() {
        if (!profileSwitchDispatching.compareAndSet(false, true)) return
        Thread(
            {
                var stopTimedOut = false
                try {
                    while (desiredRunning.get()) {
                        val desiredProfileId = selectedProfile.get().id
                        synchronized(lifecycleLock) {
                            if (desiredRunning.get()) {
                                val currentProfileId = workerProfileId.get()
                                if (currentProfileId != desiredProfileId) {
                                    val hadWorker = running.get() || workerThread.get() != null
                                    if (hadWorker && !stopLocked()) {
                                        stopTimedOut = true
                                        desiredRunning.set(false)
                                        reportFailure(
                                            desiredProfileId,
                                            "The previous FMOD worker did not stop within " +
                                                "$WORKER_JOIN_TIMEOUT_MS ms; audio remains stopped.",
                                        )
                                    } else if (desiredRunning.get()) {
                                        // Re-read after the potentially blocking join so a B→C
                                        // request starts C directly instead of resurrecting B.
                                        startLocked()
                                    }
                                }
                            }
                        }
                        if (
                            stopTimedOut || !desiredRunning.get() ||
                            workerProfileId.get() == selectedProfile.get().id
                        ) {
                            break
                        }
                    }
                } finally {
                    profileSwitchDispatching.set(false)
                    if (
                        !stopTimedOut && desiredRunning.get() &&
                        workerProfileId.get() != selectedProfile.get().id
                    ) {
                        dispatchProfileSwitch()
                    }
                }
            },
            PROFILE_SWITCH_WORKER_NAME,
        ).apply { isDaemon = true }.start()
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

    private fun reportFailure(profileId: String, detail: String) {
        loadFailure.set(AudioLoadFailure(profileId, detail))
    }

    private fun logFailure(operation: String, result: FmodNativeCallResult?) {
        if (result is FmodNativeCallResult.Failure) {
            Log.e(TAG, "Could not $operation: ${result.detail}")
        }
    }

    private companion object {
        const val TAG = "EngineAudioEngine"
        const val WORKER_NAME = "engine-fmod-control"
        const val PROFILE_SWITCH_WORKER_NAME = "engine-fmod-profile-switch"
        const val NANOS_PER_SECOND = 1_000_000_000.0
        /** One FMOD command every 2.5 ms; 64-frame device blocks prevent normal command coalescing. */
        const val CONTROL_PERIOD_NANOS = 2_500_000L
        const val WORKER_JOIN_TIMEOUT_MS = 1_500L
        const val DUCK_GAIN = 0.20
    }
}

internal data class AudioLoadFailure(
    val profileId: String,
    val detail: String,
)
