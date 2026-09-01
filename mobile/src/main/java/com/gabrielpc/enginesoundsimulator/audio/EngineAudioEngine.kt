package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.exp

enum class AudioFocusEvent {
    TRANSIENT_LOSS,
    TRANSIENT_GAIN,
    TRANSIENT_DUCK,
    PERMANENT_LOSS,
}

/**
 * Drives the original Studio events from one installed FMOD bank. The control
 * worker receives presentation RPM only, never the truncated raw vehicle speed.
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
    private val primaryLayerSource = AtomicReference(PrimaryEngineLayerSource.LOAD)
    private val loadedBankProfileId = AtomicReference<String?>(null)
    private val loadFailure = AtomicReference<AudioLoadFailure?>(null)
    private val focusMultiplier = AtomicReference(0.0)
    private val focusHeld = AtomicBoolean(false)
    private val controlThread = AtomicReference<Thread?>(null)
    private val nativeMeters = AtomicReference<List<LayerOutputMeter>>(emptyList())

    @Volatile
    private var focusChangeListener: ((AudioFocusEvent) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusHeld.get() && running.get()) {
                    focusMultiplier.set(1.0)
                    focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_GAIN)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusMultiplier.set(0.20)
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_DUCK)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusMultiplier.set(0.0)
                focusChangeListener?.invoke(AudioFocusEvent.TRANSIENT_LOSS)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusMultiplier.set(0.0)
                focusHeld.set(false)
                focusChangeListener?.invoke(AudioFocusEvent.PERMANENT_LOSS)
                synchronized(lifecycleLock) {
                    stopLocked()
                }
            }
        }
    }

    fun layerOutputMeters(): List<LayerOutputMeter> = nativeMeters.get()

    fun loadedBankProfileId(): String? = loadedBankProfileId.get()

    internal fun consumeLoadFailure(): AudioLoadFailure? = loadFailure.getAndSet(null)

    fun isAudioActive(): Boolean = synchronized(lifecycleLock) {
        running.get() && controlThread.get()?.isAlive == true
    }

    fun update(frame: EngineAudioFrame) {
        parameters.set(frame)
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
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    internal fun setSoundProgram(
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
        source: PrimaryEngineLayerSource,
    ) {
        synchronized(lifecycleLock) {
            val resolvedPerspective = profile.resolvedPerspective(perspective)
            val changed = selectedProfile.getAndSet(profile).id != profile.id ||
                soundPerspective.getAndSet(resolvedPerspective) != resolvedPerspective ||
                primaryLayerSource.getAndSet(source) != source
            if (!changed) return

            loadedBankProfileId.set(null)
            if (running.get() || controlThread.get()?.isAlive == true) {
                stopLocked()
                startLocked()
            }
        }
    }

    internal fun setPrimaryLayerSource(source: PrimaryEngineLayerSource) {
        synchronized(lifecycleLock) {
            if (primaryLayerSource.getAndSet(source) == source) return
            if (running.get() || controlThread.get()?.isAlive == true) {
                stopLocked()
                startLocked()
            }
        }
    }

    private fun startLocked() {
        if (running.get() || controlThread.get() != null || focusHeld.get()) {
            stopLocked()
        }

        val profile = selectedProfile.get()
        val focusGranted = runCatching(::requestFocus).getOrDefault(false)
        if (!focusGranted) {
            reportLoadFailure(profile.id, "Audio focus was not granted by the system.")
            return
        }

        focusHeld.set(true)
        focusMultiplier.set(1.0)
        nativeMeters.set(emptyList())
        loadFailure.set(null)
        running.set(true)
        val runId = generation.incrementAndGet()
        val perspective = profile.resolvedPerspective(soundPerspective.get())
        val source = primaryLayerSource.get()
        val thread = Thread(
            { controlLoop(runId, profile, perspective, source) },
            "fmod-bank-control",
        ).apply { isDaemon = true }
        controlThread.set(thread)
        runCatching(thread::start).onFailure {
            controlThread.compareAndSet(thread, null)
            running.set(false)
            focusMultiplier.set(0.0)
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
        if (thread != null && thread !== Thread.currentThread()) {
            joinThread(thread, CONTROL_JOIN_TIMEOUT_MS)
        }
        if (thread == null || !thread.isAlive) {
            controlThread.compareAndSet(thread, null)
        }
        loadedBankProfileId.set(null)
        nativeMeters.set(emptyList())
        focusMultiplier.set(0.0)
        abandonFocusIfHeld()
    }

    private fun controlLoop(
        runId: Long,
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
        source: PrimaryEngineLayerSource,
    ) {
        val bridge = NativeFmodBankBridge()
        val smoother = FmodControlSmoother(profile.idleRpm)
        val turbo = TurboSpoolModel()
        val overrun = FmodOverrunTrigger()
        var opened = false
        var lastTickNanos = System.nanoTime()

        try {
            org.fmod.FMOD.init(appContext)
            val bankFiles = bankResolver.bankFiles(profile)
            val startupError = bridge.open(
                commonStringsBankPath = bankFiles.commonStrings.absolutePath,
                commonBankPath = bankFiles.common.absolutePath,
                carBankPath = bankFiles.car.absolutePath,
                perspective = perspective.ordinal,
                source = source.nativeValue,
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

                val frame = parameters.get()
                val controls = smoother.advance(frame, dt)
                turbo.update(
                    dt = dt,
                    rpm = controls.rpm,
                    throttle = controls.throttle,
                    attackMultiplier = frame.turboSpoolAttackMultiplier,
                )
                val triggerOverrun = overrun.update(frame, controls.throttle, dt)
                val gains = FmodEventGains.from(frame, focusMultiplier.get())
                val error = bridge.update(
                    rpm = controls.rpm.toFloat(),
                    throttle = controls.throttle.toFloat(),
                    masterGain = gains.master.toFloat(),
                    loadGain = gains.load.toFloat(),
                    coastGain = gains.coast.toFloat(),
                    transmissionGain = gains.transmission.toFloat(),
                    turboGain = gains.turbo.toFloat(),
                    limiterGain = gains.limiter.toFloat(),
                    shiftGain = gains.shift.toFloat(),
                    overrunGain = gains.overrun.toFloat(),
                    boost = turbo.boost.toFloat(),
                    bovDecay = turbo.bovDecay.toFloat(),
                    shiftSerial = frame.shiftSerial,
                    shiftDirection = frame.shiftDirection,
                    triggerOverrun = triggerOverrun,
                )
                if (error != null) {
                    reportLoadFailure(profile.id, error)
                    return
                }
                nativeMeters.set(nativeOutputMeters(bridge.outputMeters()))
                sleepUntilNextControlTick(now)
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "FMOD bank control stopped for ${profile.id}", throwable)
            reportLoadFailure(profile.id, throwable.message ?: throwable::class.java.simpleName)
        } finally {
            loadedBankProfileId.set(null)
            if (opened) bridge.close()
            runCatching { org.fmod.FMOD.close() }
            nativeMeters.set(emptyList())
            controlThread.compareAndSet(Thread.currentThread(), null)
            if (generation.get() == runId) {
                running.set(false)
                focusMultiplier.set(0.0)
                abandonFocusIfHeld()
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun sleepUntilNextControlTick(tickStartedNanos: Long) {
        val elapsed = System.nanoTime() - tickStartedNanos
        val remaining = CONTROL_PERIOD_NANOS - elapsed
        if (remaining > 0L) {
            LockSupport.parkNanos(remaining)
        }
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

    private companion object {
        const val TAG = "EngineAudioEngine"
        const val CONTROL_PERIOD_NANOS = 4_000_000L
        const val CONTROL_JOIN_TIMEOUT_MS = 1_000L
        const val MIN_CONTROL_STEP_SECONDS = 1.0 / 1_000.0
        const val MAX_CONTROL_STEP_SECONDS = 0.040
    }
}

/**
 * Smooths only presentation values already produced by EngineSimulation. It
 * never sees raw km/h, so a whole-km/h telemetry edge cannot become an FMOD
 * parameter step.
 */
internal class FmodControlSmoother(initialRpm: Double) {
    private var rpm = initialRpm
    private var throttle = 0.0

    fun advance(frame: EngineAudioFrame, dt: Double): FmodControlValues {
        val tuning = frame.tuning.sanitized()
        rpm = follow(rpm, frame.rpm.coerceAtLeast(0.0), tuning.rpmSmoothingMs, dt)
        throttle = follow(throttle, frame.throttle.coerceIn(0.0, 1.0), tuning.throttleSmoothingMs, dt)
        return FmodControlValues(rpm = rpm, throttle = throttle)
    }

    private fun follow(current: Double, target: Double, timeMs: Double, dt: Double): Double {
        val timeSeconds = (timeMs / 1_000.0).coerceAtLeast(0.001)
        val fraction = 1.0 - exp(-dt / timeSeconds)
        return current + (target - current) * fraction
    }
}

internal data class FmodControlValues(
    val rpm: Double,
    val throttle: Double,
)

/** A native-bank backfire needs a deliberate pull, then a meaningful lift. */
private class FmodOverrunTrigger {
    private var accumulatedThrottleSeconds = 0.0
    private var previousThrottle = 0.0

    fun update(frame: EngineAudioFrame, throttle: Double, dt: Double): Boolean {
        if (throttle >= MINIMUM_SERIOUS_THROTTLE) {
            accumulatedThrottleSeconds = (accumulatedThrottleSeconds + dt).coerceAtMost(MAX_CHARGE_SECONDS)
        } else {
            accumulatedThrottleSeconds = (accumulatedThrottleSeconds - dt * CHARGE_DECAY_PER_SECOND).coerceAtLeast(0.0)
        }

        val triggered = frame.popsAndBangsEnabled &&
            frame.throttleLiftEffectsEnabled &&
            accumulatedThrottleSeconds >= REQUIRED_CHARGE_SECONDS &&
            previousThrottle - throttle >= REQUIRED_LIFT_DROP
        if (triggered) {
            accumulatedThrottleSeconds = 0.0
        }
        previousThrottle = throttle
        return triggered
    }

    private companion object {
        const val MINIMUM_SERIOUS_THROTTLE = 0.40
        const val REQUIRED_CHARGE_SECONDS = 1.0
        const val CHARGE_DECAY_PER_SECOND = 0.30
        const val MAX_CHARGE_SECONDS = 2.0
        const val REQUIRED_LIFT_DROP = 0.20
    }
}

private data class FmodEventGains(
    val master: Double,
    val load: Double,
    val coast: Double,
    val transmission: Double,
    val turbo: Double,
    val limiter: Double,
    val shift: Double,
    val overrun: Double,
) {
    companion object {
        fun from(frame: EngineAudioFrame, focusGain: Double): FmodEventGains {
            val mix = FmodMixControls(frame.layerMix)
            val enabled = if (frame.enabled) 1.0 else 0.0
            val master = (frame.tuning.masterGain * focusGain * enabled).coerceIn(0.0, 1.2)
            val programGains = frame.programLayerGains.sanitized()
            return FmodEventGains(
                master = master,
                load = mix.gain("engine_load") * programGains.load,
                coast = mix.gain("engine_coast") * programGains.coast,
                transmission = if (frame.transmissionEnabled) mix.gain("transmission") * frame.transmissionGain else 0.0,
                turbo = if (frame.turboSoundsEnabled) mix.gain("turbo") * frame.turboSoundsGain else 0.0,
                limiter = mix.gain("limiter"),
                shift = if (frame.shiftSoundsEnabled) mix.gain("gear") * frame.shiftSoundsGain else 0.0,
                overrun = if (frame.popsAndBangsEnabled) mix.gain("overrun") * frame.popsAndBangsGain else 0.0,
            )
        }
    }
}

private class FmodMixControls(private val controls: Map<String, LayerMixControl>) {
    private val hasSolo = controls.values.any(LayerMixControl::solo)

    fun gain(id: String): Double {
        val control = controls[id] ?: LayerMixControl.DEFAULT
        if (control.muted || (hasSolo && !control.solo)) return 0.0
        return control.volume.coerceIn(LayerMixControl.MIN_GAIN_MULTIPLIER, LayerMixControl.MAX_GAIN_MULTIPLIER)
    }
}

internal data class AudioLoadFailure(
    val profileId: String,
    val detail: String,
)
