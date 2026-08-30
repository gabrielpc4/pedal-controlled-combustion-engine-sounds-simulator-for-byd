package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioMixModeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioFocusEvent
import com.gabrielpc.enginesoundsimulator.audio.CarMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfile
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodCarSelectionRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodEventKind
import com.gabrielpc.enginesoundsimulator.audio.FmodEventMixRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodEventMixSettings
import com.gabrielpc.enginesoundsimulator.audio.FmodRenderedAudioValidationResult
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineIgnitionState
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
import com.gabrielpc.enginesoundsimulator.tuning.withFmodProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val primaryLabel: String, val secondaryLabel: String = "PEDALS") {
    RealPedals("REAL"),
    SimulatedPedals("SIMULATED"),
}

data class DriveSnapshot(
    val drivetrain: DrivetrainState,
    val inputSourcePrimary: String,
    val inputSourceSecondary: String,
    val inputSourceIsRealPedals: Boolean,
    val inputSourceFaded: Boolean,
    val throttle: Double,
    val brake: Double,
    val transmissionPosition: TransmissionPosition,
    val engineSoundEnabled: Boolean,
    val tuning: TuningConfig,
    val selectedCarId: String,
    val selectedCarName: String,
    val selectedCarPreviewAsset: String,
    val selectedCarIndex: Int,
    val availableCarCount: Int,
    val eventMixSettings: FmodEventMixSettings = FmodEventMixSettings.DEFAULT,
    val loadOnlyEnabled: Boolean = false,
    val coastOnlyEnabled: Boolean = false,
    val appMasterVolume: Double = AppMasterVolumeRepository.DEFAULT,
    val appMuted: Boolean = false,
    val carMasterVolume: Double = CarMasterVolumeRepository.DEFAULT,
    val transmissionLockedToVehicle: Boolean = false,
    val carAudioReady: Boolean = false,
    val engineStartLoading: Boolean = false,
    val manualShiftModeEnabled: Boolean = false,
    val userMessage: UserVisibleMessage? = null,
)

/** One atomic publication consumed once at the start of each 200 Hz simulation step. */
internal data class DriveRuntimeConfig(
    val selectedCar: FmodCarProfile,
    val tuning: TuningConfig,
    val carMasterVolume: Double,
) {
    fun selecting(profile: FmodCarProfile, volume: Double): DriveRuntimeConfig = copy(
        selectedCar = profile,
        tuning = tuning.withFmodProfile(profile),
        carMasterVolume = volume,
    )
}

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController internal constructor(
    context: Context,
    /** Test seam at the race boundary; production callers leave it null. */
    private val onDeferredStartBankObserved: ((String) -> Unit)? = null,
) {
    private val tuningRepository = TuningRepository(context.applicationContext)
    private val appMasterVolumeRepository = AppMasterVolumeRepository(context.applicationContext)
    private val carMasterVolumeRepository = CarMasterVolumeRepository(context.applicationContext)
    private val audioMixModeRepository = AudioMixModeRepository(context.applicationContext)
    private val eventMixRepository = FmodEventMixRepository(context.applicationContext)
    private val carSelectionRepository = FmodCarSelectionRepository(context.applicationContext)
    private val initialSelectedCar = carSelectionRepository.load()
    private val initialRuntimeConfig = DriveRuntimeConfig(
        selectedCar = initialSelectedCar,
        tuning = tuningRepository.load().withFmodProfile(initialSelectedCar),
        carMasterVolume = carMasterVolumeRepository.load(initialSelectedCar.id),
    )
    private val runtimeConfig = AtomicReference(initialRuntimeConfig)
    private val runtimeConfigLock = Any()
    private val eventMixSettings = AtomicReference(eventMixRepository.load())
    private val initialCoastOnlyEnabled = audioMixModeRepository.isCoastOnlyEnabled()
    private val coastOnlyEnabled = AtomicBoolean(initialCoastOnlyEnabled)
    private val loadOnlyEnabled = AtomicBoolean(
        !initialCoastOnlyEnabled && audioMixModeRepository.isLoadOnlyEnabled(),
    )
    private val manualShiftModeEnabled = AtomicBoolean(audioMixModeRepository.isManualShiftModeEnabled())
    private val appMasterVolume = AtomicReference(appMasterVolumeRepository.load())
    private val appMasterVolumeBeforeMute = AtomicReference<Double?>(null)
    private var appliedTuning = initialRuntimeConfig.tuning
    private var profile = appliedTuning.toEngineProfile(initialSelectedCar)
    private val simulation = EngineSimulation(profile)
    private val vehicleReader = BydSpeedReader(context.applicationContext)
    private val audioEngine = EngineAudioEngine(context.applicationContext)
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val simulatedPedalInput = AtomicReference(SimulatedPedalInput())
    private val selectedInputMode = AtomicReference(InputMode.RealPedals)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val uiActive = AtomicBoolean(false)
    private val audioInterrupted = AtomicBoolean(false)
    private val preInterruptionMasterVolume = AtomicReference<Double?>(null)
    private val lastAudioStartAttemptMs = AtomicLong(0L)
    /** Kept after the cosmetic shift ends so a delayed audio read retains its direction. */
    private var lastAudioShiftDirection = 1
    private var uiSnapshotElapsedSeconds = UI_SNAPSHOT_INTERVAL_SECONDS
    /** First engine start in this app process waits for FMOD bank/sample preloading. */
    private val sessionFirstStartPending = AtomicBoolean(true)
    private val awaitingFirstAudioLoad = AtomicBoolean(false)
    private val engineStartLoading = AtomicBoolean(false)

    @Volatile
    private var userVisibleMessage: UserVisibleMessage? = null

    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputSourcePrimary = InputMode.SimulatedPedals.primaryLabel,
        inputSourceSecondary = InputMode.SimulatedPedals.secondaryLabel,
        inputSourceIsRealPedals = false,
        inputSourceFaded = false,
        throttle = 0.0,
        brake = 0.0,
        transmissionPosition = TransmissionPosition.DRIVE,
        engineSoundEnabled = false,
        tuning = appliedTuning,
        selectedCarId = initialSelectedCar.id,
        selectedCarName = initialSelectedCar.displayName,
        selectedCarPreviewAsset = initialSelectedCar.previewAssetName,
        selectedCarIndex = FmodCarProfiles.indexOf(initialSelectedCar),
        availableCarCount = FmodCarProfiles.all.size,
        eventMixSettings = eventMixSettings.get(),
        loadOnlyEnabled = loadOnlyEnabled.get(),
        coastOnlyEnabled = coastOnlyEnabled.get(),
        manualShiftModeEnabled = manualShiftModeEnabled.get(),
        appMasterVolume = appMasterVolume.get(),
        carMasterVolume = initialRuntimeConfig.carMasterVolume,
    )

    init {
        tuningRepository.save(initialRuntimeConfig.tuning)
        audioEngine.setFocusChangeListener(::handleAudioFocusChange)
        audioEngine.setCarProfile(initialSelectedCar)
        simulation.manualShiftEnabled = manualShiftModeEnabled.get()
    }

    fun isRunning(): Boolean = running.get()

    fun setUiActive(active: Boolean) {
        uiActive.set(active)
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        val runtime = runtimeConfig.get()
        val selectedCar = runtime.selectedCar
        val ignitionActive = simulation.isEngineEngagedForUi()
        val coherentBase = base.copy(
            tuning = runtime.tuning,
            selectedCarId = selectedCar.id,
            selectedCarName = selectedCar.displayName,
            selectedCarPreviewAsset = selectedCar.previewAssetName,
            selectedCarIndex = FmodCarProfiles.indexOf(selectedCar),
            availableCarCount = FmodCarProfiles.all.size,
            carMasterVolume = runtime.carMasterVolume,
            carAudioReady = audioEngine.loadedCarProfileId() == selectedCar.id,
            engineStartLoading = engineStartLoading.get(),
            userMessage = userVisibleMessage,
        )
        if (!uiActive.get()) {
            return coherentBase.copy(
                engineSoundEnabled = ignitionActive,
                eventMixSettings = eventMixSettings.get(),
                loadOnlyEnabled = loadOnlyEnabled.get(),
                coastOnlyEnabled = coastOnlyEnabled.get(),
                manualShiftModeEnabled = manualShiftModeEnabled.get(),
            )
        }

        return coherentBase.copy(
            engineSoundEnabled = ignitionActive,
            eventMixSettings = eventMixSettings.get(),
            loadOnlyEnabled = loadOnlyEnabled.get(),
            coastOnlyEnabled = coastOnlyEnabled.get(),
            manualShiftModeEnabled = manualShiftModeEnabled.get(),
            carAudioReady = audioEngine.loadedCarProfileId() == selectedCar.id,
            engineStartLoading = engineStartLoading.get(),
            userMessage = userVisibleMessage,
            appMasterVolume = appMasterVolume.get(),
            appMuted = appMasterVolumeBeforeMute.get() != null,
        )
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && loopThread?.isAlive == true) return
            val previous = loopThread
            if (previous?.isAlive == true) {
                previous.interrupt()
                if (!joinLoop(previous)) return
            }

            // Start each visible/controller session with a fresh source line and heartbeat.
            val runId = generation.incrementAndGet()
            running.set(true)
            val thread = Thread({ runLoop(runId) }, "drivetrain-simulation").apply { isDaemon = true }
            loopThread = thread
            try {
                vehicleReader.start()
                thread.start()
                requestAutoSessionEngineStart()
            } catch (throwable: Throwable) {
                running.set(false)
                generation.incrementAndGet()
                loopThread = null
                vehicleReader.stop()
                audioEngine.stop()
                throw throwable
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            generation.incrementAndGet()
            val thread = loopThread
            thread?.interrupt()
            if (thread == null || joinLoop(thread)) loopThread = null
            vehicleReader.stop()
            audioEngine.stop()
            simulatedPedalInput.set(SimulatedPedalInput())
        }
    }

    fun setSimulatedPedalThrottle(value: Double) {
        simulatedPedalInput.updateAndGet { it.copy(throttle = value.coerceIn(0.0, 1.0)) }
    }

    fun setSimulatedPedalBrake(value: Double) {
        simulatedPedalInput.updateAndGet { it.copy(brake = value.coerceIn(0.0, 1.0)) }
    }

    fun setInputMode(mode: InputMode) {
        selectedInputMode.set(mode)
    }

    fun setTuning(config: TuningConfig) {
        val clean = config.sanitized()
        synchronized(runtimeConfigLock) {
            runtimeConfig.set(runtimeConfig.get().copy(tuning = clean))
            tuningRepository.save(clean)
        }
    }

    fun resetTuning() {
        synchronized(runtimeConfigLock) {
            val current = runtimeConfig.get()
            val clean = tuningRepository.reset().withFmodProfile(current.selectedCar)
            runtimeConfig.set(current.copy(tuning = clean))
            tuningRepository.save(clean)
        }
    }

    fun setFmodEventEnabled(kind: FmodEventKind, enabled: Boolean) {
        eventMixSettings.set(eventMixRepository.setEnabled(kind, enabled))
    }

    fun setFmodEventGainDb(kind: FmodEventKind, gainDb: Double) {
        eventMixSettings.set(eventMixRepository.setGainDb(kind, gainDb))
    }

    fun setCoastOnlyEnabled(enabled: Boolean) {
        audioMixModeRepository.setCoastOnlyEnabled(enabled)
        coastOnlyEnabled.set(enabled)
        if (enabled) loadOnlyEnabled.set(false)
    }

    fun setLoadOnlyEnabled(enabled: Boolean) {
        audioMixModeRepository.setLoadOnlyEnabled(enabled)
        loadOnlyEnabled.set(enabled)
        if (enabled) coastOnlyEnabled.set(false)
    }

    fun setManualShiftModeEnabled(enabled: Boolean) {
        audioMixModeRepository.setManualShiftModeEnabled(enabled)
        manualShiftModeEnabled.set(enabled)
        simulation.manualShiftEnabled = enabled
    }

    fun toggleManualShiftMode() {
        setManualShiftModeEnabled(!manualShiftModeEnabled.get())
    }

    fun requestManualUpshift(): Boolean {
        synchronized(lifecycleLock) {
            if (transmissionPosition.get() != TransmissionPosition.DRIVE) {
                return false
            }
            if (!simulation.isEngineEngagedForUi()) {
                return false
            }
            return simulation.requestManualUpshift()
        }
    }

    fun requestManualDownshift(): Boolean {
        synchronized(lifecycleLock) {
            if (transmissionPosition.get() != TransmissionPosition.DRIVE) {
                return false
            }
            if (!simulation.isEngineEngagedForUi()) {
                return false
            }
            return simulation.requestManualDownshift()
        }
    }

    fun handleShiftKey(keyCode: Int): Boolean {
        if (!manualShiftModeEnabled.get()) {
            return false
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                requestManualUpshift()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            -> {
                requestManualDownshift()
                return true
            }
            else -> return false
        }
    }

    fun setAppMasterVolume(volume: Double) {
        appMasterVolumeBeforeMute.set(null)
        appMasterVolume.set(appMasterVolumeRepository.save(volume))
    }

    fun decreaseAppMasterVolume() {
        setAppMasterVolume(appMasterVolume.get() - MASTER_VOLUME_STEP)
    }

    fun increaseAppMasterVolume() {
        setAppMasterVolume(appMasterVolume.get() + MASTER_VOLUME_STEP)
    }

    fun toggleAppMute() {
        val savedBeforeMute = appMasterVolumeBeforeMute.get()
        if (savedBeforeMute != null) {
            appMasterVolumeBeforeMute.set(null)
            appMasterVolume.set(appMasterVolumeRepository.save(savedBeforeMute))
            return
        }

        appMasterVolumeBeforeMute.set(appMasterVolume.get())
        appMasterVolume.set(AppMasterVolumeRepository.MIN)
    }

    fun setCarMasterVolume(volume: Double) {
        synchronized(runtimeConfigLock) {
            val current = runtimeConfig.get()
            val saved = carMasterVolumeRepository.save(current.selectedCar.id, volume)
            runtimeConfig.set(current.copy(carMasterVolume = saved))
        }
    }

    /** Runs the selected bank's deterministic off-screen PCM/callback audit on the FMOD worker. */
    fun requestRenderedAudioValidation(): Boolean = audioEngine.requestRenderedAudioValidation()

    fun isRenderedAudioValidationRunning(): Boolean =
        audioEngine.isRenderedAudioValidationRunning()

    fun consumeRenderedAudioValidation(): FmodRenderedAudioValidationResult? =
        audioEngine.consumeRenderedAudioValidation()

    fun resetAllCarMasterVolumes() {
        synchronized(runtimeConfigLock) {
            carMasterVolumeRepository.resetAll()
            val current = runtimeConfig.get()
            runtimeConfig.set(
                current.copy(carMasterVolume = carMasterVolumeRepository.load(current.selectedCar.id)),
            )
        }
    }

    fun selectPreviousCar(): Boolean {
        val currentIndex = FmodCarProfiles.indexOf(runtimeConfig.get().selectedCar)
        val targetIndex = (currentIndex - 1 + FmodCarProfiles.all.size) % FmodCarProfiles.all.size
        return selectCar(FmodCarProfiles.all[targetIndex].id)
    }

    fun selectNextCar(): Boolean {
        val currentIndex = FmodCarProfiles.indexOf(runtimeConfig.get().selectedCar)
        val targetIndex = (currentIndex + 1) % FmodCarProfiles.all.size
        return selectCar(FmodCarProfiles.all[targetIndex].id)
    }

    /** Atomically switches simulation metadata and asks FMOD to preload/restart the selected bank. */
    fun selectCar(profileId: String): Boolean {
        val target = FmodCarProfiles.findOrNull(profileId) ?: return false
        synchronized(lifecycleLock) {
            val selectedRuntime: DriveRuntimeConfig
            synchronized(runtimeConfigLock) {
                val current = runtimeConfig.get()
                if (current.selectedCar.id == target.id) return false

                carSelectionRepository.save(target)
                selectedRuntime = current.selecting(
                    profile = target,
                    volume = carMasterVolumeRepository.load(target.id),
                )
                runtimeConfig.set(selectedRuntime)
                tuningRepository.save(selectedRuntime.tuning)
                lastAudioStartAttemptMs.set(0L)
                userVisibleMessage = null

                latest = latest.copy(
                    tuning = selectedRuntime.tuning,
                    selectedCarId = target.id,
                    selectedCarName = target.displayName,
                    selectedCarPreviewAsset = target.previewAssetName,
                    selectedCarIndex = FmodCarProfiles.indexOf(target),
                    availableCarCount = FmodCarProfiles.all.size,
                    carMasterVolume = selectedRuntime.carMasterVolume,
                    carAudioReady = false,
                    userMessage = null,
                )
            }
            // Reset the loaded-profile marker before releasing the same boundary used by deferred
            // startup. The asynchronous bank work itself remains on EngineAudioEngine's worker.
            audioEngine.setCarProfile(target)
        }
        return true
    }

    fun selectSimulatedPedals() {
        selectedInputMode.set(InputMode.SimulatedPedals)
    }

    fun selectRealPedals() {
        if (vehicleReader.snapshot().vehiclePedalsAvailable()) {
            selectedInputMode.set(InputMode.RealPedals)
        }
    }

    fun toggleInputSource() {
        if (selectedInputMode.get() == InputMode.SimulatedPedals) {
            if (vehicleReader.snapshot().vehiclePedalsAvailable()) {
                selectedInputMode.set(InputMode.RealPedals)
            }
            return
        }

        selectedInputMode.set(InputMode.SimulatedPedals)
    }

    fun setTransmissionPosition(position: TransmissionPosition) {
        transmissionPosition.set(position)
    }

    fun dismissUserMessage() {
        synchronized(lifecycleLock) {
            userVisibleMessage = null
        }
    }

    fun toggleSound() {
        synchronized(lifecycleLock) {
            if (isDeferringFirstSessionEngineStart()) {
                cancelPendingFirstAudioLoad()
                return
            }
            if (simulation.isEngineEngagedForUi() && !simulation.isShutdownPending()) {
                simulation.requestShutdown()
            } else {
                requestEngineStart(fromStartStopButton = true)
            }
        }
    }

    private fun requestAutoSessionEngineStart() {
        synchronized(lifecycleLock) {
            if (simulation.isEngineEngagedForUi() || simulation.isShutdownPending()) {
                ensureAudioEngineRunning(force = true)
                return
            }

            if (sessionFirstStartPending.get()) {
                engineStartLoading.set(true)
                requestEngineStart(fromStartStopButton = false)
                return
            }

            engageEngineAtIdle(forceAudio = true)
        }
    }

    private fun requestEngineStart(fromStartStopButton: Boolean = false) {
        if (simulation.isEngineEngagedForUi() || simulation.isShutdownPending()) {
            return
        }

        if (sessionFirstStartPending.get()) {
            if (isDeferringFirstSessionEngineStart()) {
                if (fromStartStopButton) {
                    engineStartLoading.set(true)
                }
                return
            }

            val selectedCar = runtimeConfig.get().selectedCar
            if (isCarAudioLoaded(selectedCar.id)) {
                if (fromStartStopButton) {
                    engineStartLoading.set(true)
                }
                completeDeferredEngineStart(selectedCar.id)
                return
            }

            if (fromStartStopButton) {
                engineStartLoading.set(true)
            }
            awaitingFirstAudioLoad.set(true)
            ensureAudioEngineRunning(force = true)
            return
        }

        startEngine(forceAudio = true)
    }

    private fun completeDeferredEngineStart(loadedProfileId: String): Boolean {
        val selectedCar = runtimeConfig.get().selectedCar
        if (
            !sessionFirstStartPending.get() ||
            selectedCar.id != loadedProfileId ||
            !isCarAudioLoaded(loadedProfileId)
        ) {
            // A profile switch invalidated the observed load. Keep the first start pending until
            // the newly selected bank proves ready; never consume its authored ignition early.
            return false
        }
        awaitingFirstAudioLoad.set(false)
        engineStartLoading.set(false)
        sessionFirstStartPending.set(false)
        // A bank-authored ignition is driven by the existing RPM/fade state machine, but only
        // after that bank and its samples are ready. Cars without authored start material keep
        // the established quiet session startup instead of synthesizing a starter from run loops.
        if (selectedCar.hasEmbeddedEngineStart) {
            startEngine(forceAudio = true)
        } else {
            engageEngineAtIdle(forceAudio = true)
        }
        return true
    }

    private fun cancelPendingFirstAudioLoad() {
        awaitingFirstAudioLoad.set(false)
        engineStartLoading.set(false)
    }

    private fun isDeferringFirstSessionEngineStart(): Boolean {
        return awaitingFirstAudioLoad.get()
    }

    private fun isCarAudioLoaded(profileId: String): Boolean =
        audioEngine.loadedCarProfileId() == profileId

    private fun startEngine(forceAudio: Boolean) {
        simulation.startIgnition()
        if (forceAudio) {
            ensureAudioEngineRunning(force = true)
        }
    }

    private fun engageEngineAtIdle(forceAudio: Boolean) {
        simulation.engageAtIdle()
        if (forceAudio) {
            ensureAudioEngineRunning(force = true)
        }
    }

    private fun maybeAutoStartEngineFromThrottle(throttle: Double) {
        if (throttle <= AUTO_START_THROTTLE_THRESHOLD) {
            return
        }

        synchronized(lifecycleLock) {
            if (simulation.ignition == EngineIgnitionState.OFF) {
                requestEngineStart(fromStartStopButton = false)
            }
        }
    }

    private fun ensureAudioEngineRunning(force: Boolean = false) {
        if (!running.get()) {
            return
        }

        if (audioEngine.isAudioActive()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAudioStartAttemptMs.get() < AUDIO_RESTART_COOLDOWN_MS) {
            return
        }

        synchronized(lifecycleLock) {
            if (!running.get() || audioEngine.isAudioActive()) {
                return
            }

            lastAudioStartAttemptMs.set(now)
            audioEngine.setCarProfile(runtimeConfig.get().selectedCar)
            audioEngine.start()
        }
    }

    private fun runLoop(runId: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        var previousNanos = SystemClock.elapsedRealtimeNanos()
        var accumulatorSeconds = 0.0

        while (isCurrent(runId)) {
            if (audioInterrupted.get()) {
                LockSupport.parkNanos(INTERRUPTED_IDLE_NANOS)
                continue
            }

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val elapsedSeconds = ((nowNanos - previousNanos) / 1_000_000_000.0).coerceIn(0.0, 0.050)
            previousNanos = nowNanos
            accumulatorSeconds += elapsedSeconds

            while (accumulatorSeconds >= FIXED_STEP_SECONDS && isCurrent(runId)) {
                step(FIXED_STEP_SECONDS)
                accumulatorSeconds -= FIXED_STEP_SECONDS
            }

            val remaining = FIXED_STEP_NANOS - (SystemClock.elapsedRealtimeNanos() - nowNanos)
            if (remaining > 0L) LockSupport.parkNanos(remaining)
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId

    private fun joinLoop(thread: Thread): Boolean {
        if (thread === Thread.currentThread()) return false
        try {
            thread.join(LOOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    private fun step(dt: Double) {
        val runtime = runtimeConfig.get()
        val tuning = runtime.tuning
        val selectedCar = runtime.selectedCar
        if (tuning !== appliedTuning) {
            profile = tuning.toEngineProfile(selectedCar)
            simulation.updateProfile(profile)
            appliedTuning = tuning
        }
        val telemetry = vehicleReader.snapshot()
        val mode = selectedInputMode.get()
        val simulatedPedals = simulatedPedalInput.get()
        val input = resolveDriveInput(mode, telemetry, simulatedPedals.throttle, simulatedPedals.brake)
        maybeAutoStartEngineFromThrottle(input.throttle)
        if (awaitingFirstAudioLoad.get() && isCarAudioLoaded(selectedCar.id)) {
            onDeferredStartBankObserved?.invoke(selectedCar.id)
            synchronized(lifecycleLock) {
                if (awaitingFirstAudioLoad.get()) {
                    completeDeferredEngineStart(selectedCar.id)
                }
            }
        }
        handleAudioLoadFailures()
        if (simulation.isIgnitionActive()) {
            ensureAudioEngineRunning()
        } else if (audioEngine.isAudioActive() && !isDeferringFirstSessionEngineStart()) {
            audioEngine.stop()
        }
        val selectedTransmissionPosition = transmissionPosition.get()
        simulation.manualShiftEnabled = manualShiftModeEnabled.get()

        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                externalSpeedKmh = input.externalSpeedKmh,
                // Use the resolved source, not just the selected mode, for coast/regen behavior.
                simulateCoastRegen = input.usesSimulatedPedals,
                transmissionPosition = selectedTransmissionPosition,
            ),
            dt,
        )
        val audioEnabled = simulation.ignition == EngineIgnitionState.STOPPING ||
            simulation.isEngineAudioAudible()
        val selectedCarAudioReady = isCarAudioLoaded(selectedCar.id)
        when (drivetrain.shiftDirection) {
            ShiftDirection.UP -> lastAudioShiftDirection = 1
            ShiftDirection.DOWN -> lastAudioShiftDirection = -1
            ShiftDirection.NONE -> Unit
        }
        audioEngine.update(
            rpm = drivetrain.rpm,
            throttle = input.throttle,
            drivetrainSpeed = drivetrainAngularSpeedRadPerSecond(
                // Use the same continuous road-speed state that drives the tach. Real BYD
                // integer samples have already passed through QuantizedSpeedEstimator here.
                speedKmh = drivetrain.speedKmh,
                drivenWheelRadiusMeters = selectedCar.drivenWheelRadiusMeters,
            ),
            // Silence the outgoing bank while its asynchronous replacement is loading.
            enabled = audioEnabled && selectedCarAudioReady,
            masterGain = effectiveAudioMasterGain(
                tuning = tuning,
                carVolume = runtime.carMasterVolume,
                shutdownGain = simulation.ignitionAudioGain(),
            ),
            shiftSerial = drivetrain.shiftSerial,
            shiftDirection = lastAudioShiftDirection,
            isShifting = drivetrain.isShifting,
            shiftTargetRpm = drivetrain.shiftTargetRpm,
            limiterActive = drivetrain.limiterActive,
            loadOnlyEnabled = loadOnlyEnabled.get(),
            coastOnlyEnabled = coastOnlyEnabled.get(),
            eventMixSettings = eventMixSettings.get(),
        )
        if (uiActive.get() && runtimeConfig.get() === runtime) {
            uiSnapshotElapsedSeconds += dt
            if (uiSnapshotElapsedSeconds >= UI_SNAPSHOT_INTERVAL_SECONDS) {
                uiSnapshotElapsedSeconds = 0.0
                val vehicleAvailable = telemetry.vehiclePedalsAvailable()
                val inputUi = resolveInputSourceUi(
                    selectedMode = mode,
                    vehicleAvailable = vehicleAvailable,
                )
                latest = DriveSnapshot(
                    drivetrain = drivetrain,
                    inputSourcePrimary = inputUi.primaryLabel,
                    inputSourceSecondary = inputUi.secondaryLabel,
                    inputSourceIsRealPedals = inputUi.isRealPedals,
                    inputSourceFaded = inputUi.faded,
                    throttle = input.throttle,
                    brake = input.brake,
                    transmissionPosition = selectedTransmissionPosition,
                    engineSoundEnabled = simulation.isEngineEngagedForUi(),
                    tuning = tuning,
                    selectedCarId = selectedCar.id,
                    selectedCarName = selectedCar.displayName,
                    selectedCarPreviewAsset = selectedCar.previewAssetName,
                    selectedCarIndex = FmodCarProfiles.indexOf(selectedCar),
                    availableCarCount = FmodCarProfiles.all.size,
                    eventMixSettings = eventMixSettings.get(),
                    loadOnlyEnabled = loadOnlyEnabled.get(),
                    coastOnlyEnabled = coastOnlyEnabled.get(),
                    manualShiftModeEnabled = manualShiftModeEnabled.get(),
                    appMasterVolume = appMasterVolume.get(),
                    appMuted = appMasterVolumeBeforeMute.get() != null,
                    carMasterVolume = runtime.carMasterVolume,
                    transmissionLockedToVehicle = false,
                    carAudioReady = selectedCarAudioReady,
                    engineStartLoading = engineStartLoading.get(),
                    userMessage = userVisibleMessage,
                )
            }
        } else {
            uiSnapshotElapsedSeconds = UI_SNAPSHOT_INTERVAL_SECONDS
        }
    }

    private fun handleAudioLoadFailures() {
        val failure = audioEngine.consumeLoadFailure() ?: return
        val selectedCar = runtimeConfig.get().selectedCar
        if (failure.profileId != selectedCar.id) {
            return
        }

        synchronized(lifecycleLock) {
            if (awaitingFirstAudioLoad.get()) {
                cancelPendingFirstAudioLoad()
            }
            userVisibleMessage = UserVisibleMessage(
                id = SystemClock.elapsedRealtime(),
                title = "Engine audio failed to load",
                detail = "${selectedCar.displayName}: ${failure.detail}",
            )
        }
    }

    private fun handleAudioFocusChange(event: AudioFocusEvent) {
        when (event) {
            AudioFocusEvent.TRANSIENT_LOSS,
            AudioFocusEvent.TRANSIENT_DUCK,
            -> enterAudioInterruption()

            AudioFocusEvent.TRANSIENT_GAIN -> exitAudioInterruption()

            AudioFocusEvent.PERMANENT_LOSS -> {
                enterAudioInterruption()
                preInterruptionMasterVolume.set(null)
                synchronized(lifecycleLock) {
                    userVisibleMessage = UserVisibleMessage(
                        id = SystemClock.elapsedRealtime(),
                        title = "Engine audio interrupted",
                        detail = "Another app took permanent control of audio output.",
                    )
                }
            }
        }
    }

    private fun enterAudioInterruption() {
        if (audioInterrupted.compareAndSet(false, true)) {
            preInterruptionMasterVolume.compareAndSet(null, appMasterVolume.get())
        }
    }

    private fun exitAudioInterruption() {
        if (!audioInterrupted.compareAndSet(true, false)) {
            return
        }

        val restoredVolume = resolveInterruptionResumeVolume(
            savedVolume = preInterruptionMasterVolume.get(),
            resumeCap = INTERRUPTION_RESUME_VOLUME,
        )
        preInterruptionMasterVolume.set(null)
        appMasterVolume.set(restoredVolume)
    }

    private fun effectiveAudioMasterGain(
        tuning: TuningConfig,
        carVolume: Double,
        shutdownGain: Double = 1.0,
    ): Double {
        return (
            (appMasterVolume.get() / AppMasterVolumeRepository.DEFAULT) *
                (carVolume * tuning.audio.masterGain / CarMasterVolumeRepository.DEFAULT) *
                shutdownGain.coerceIn(0.0, 1.0)
            ).coerceIn(CarMasterVolumeRepository.MIN, CarMasterVolumeRepository.MAX)
    }

    private data class SimulatedPedalInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val UI_SNAPSHOT_INTERVAL_SECONDS = 1.0 / 30.0
        const val INTERRUPTED_IDLE_NANOS = 50_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val MASTER_VOLUME_STEP = 0.10
        const val INTERRUPTION_RESUME_VOLUME = 0.25
        const val AUDIO_RESTART_COOLDOWN_MS = 2_000L
        const val AUTO_START_THROTTLE_THRESHOLD = 0.10
    }
}

internal fun resolveInterruptionResumeVolume(
    savedVolume: Double?,
    resumeCap: Double,
): Double {
    val baseline = savedVolume ?: AppMasterVolumeRepository.DEFAULT
    return minOf(baseline, resumeCap).coerceIn(
        AppMasterVolumeRepository.MIN,
        AppMasterVolumeRepository.MAX,
    )
}

/** Converts the selected road-speed sample to Assetto's driven-wheel rad/s domain. */
internal fun drivetrainAngularSpeedRadPerSecond(
    speedKmh: Double,
    drivenWheelRadiusMeters: Double,
): Double {
    if (!speedKmh.isFinite() || !drivenWheelRadiusMeters.isFinite() || drivenWheelRadiusMeters <= 0.0) {
        return 0.0
    }
    return speedKmh / 3.6 / drivenWheelRadiusMeters
}

private fun TuningConfig.toEngineProfile(fmodProfile: FmodCarProfile): EngineProfile {
    val engine = engine.sanitized()
    return EngineProfile(
        name = fmodProfile.displayName,
        idleRpm = engine.idleRpm,
        redlineRpm = engine.redlineRpm,
        limiterRpm = engine.limiterRpm,
        upshiftRpm = engine.upshiftRpm,
        maxTorqueNm = engine.maxTorqueNm,
        peakPowerKw = engine.peakPowerKw,
        motorMaxRpm = engine.motorMaxRpm,
        motorReductionRatio = engine.motorReductionRatio,
        drivetrainEfficiency = engine.drivetrainEfficiency,
        frontPeakWheelTorqueNm = engine.frontPeakWheelTorqueNm,
        rearPeakWheelTorqueNm = engine.rearPeakWheelTorqueNm,
        tractionLimitMps2 = engine.tractionLimitMps2,
        vehicleMassKg = engine.vehicleMassKg,
        rotationalMassFactor = engine.rotationalMassFactor,
        wheelRadiusMeters = engine.wheelRadiusMeters,
        dragAreaM2 = engine.dragAreaM2,
        rollingResistanceCoefficient = engine.rollingResistanceCoefficient,
        topSpeedKmh = engine.topSpeedKmh,
        syntheticRpmResponseSeconds = engine.syntheticRpmResponseMs / 1_000.0,
        externalSpeedSmoothingSeconds = engine.externalSpeedSmoothingMs / 1_000.0,
        gearRatios = engine.gearRatios.toDoubleArray(),
        frontWheelTorqueCurve = engine.frontWheelTorqueCurve,
        rearWheelTorqueCurve = engine.rearWheelTorqueCurve,
        throttleCurve = engine.throttleCurve,
        throttleAttackSeconds = engine.throttleAttackMs / 1_000.0,
        throttleReleaseSeconds = engine.throttleReleaseMs / 1_000.0,
        upshiftDurationSeconds = engine.upshiftDurationMs / 1_000.0,
        downshiftDurationSeconds = engine.downshiftDurationMs / 1_000.0,
        shiftDwellSeconds = engine.shiftDwellMs / 1_000.0,
        secondToFirstDownshiftRpm = engine.secondToFirstDownshiftRpm,
        firstToSecondPartialThrottleUpshiftRpm = engine.firstToSecondPartialThrottleUpshiftRpm,
        secondGearEarlyShiftEnabled = engine.secondGearEarlyShiftEnabled,
    )
}

internal data class ResolvedDriveInput(
    val throttle: Double,
    val brake: Double,
    val externalSpeedKmh: Double?,
    val usesSimulatedPedals: Boolean,
)

/** Pure input arbitration kept separate so unavailable vehicle data can be fail-safe tested. */
internal fun resolveDriveInput(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
    simulatedPedalThrottle: Double,
    simulatedPedalBrake: Double,
): ResolvedDriveInput {
    val vehicleAvailable = telemetry.vehiclePedalsAvailable()

    if (vehicleAvailable && mode == InputMode.RealPedals) {
        return ResolvedDriveInput(
            throttle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid },
            usesSimulatedPedals = false,
        )
    }

    return ResolvedDriveInput(
        throttle = simulatedPedalThrottle.coerceIn(0.0, 1.0),
        brake = simulatedPedalBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        usesSimulatedPedals = true,
    )
}

internal data class InputSourceUiState(
    val primaryLabel: String,
    val secondaryLabel: String,
    val isRealPedals: Boolean,
    val faded: Boolean,
)

internal fun resolveInputSourceUi(
    selectedMode: InputMode,
    vehicleAvailable: Boolean,
): InputSourceUiState {
    val activeMode = when {
        selectedMode == InputMode.RealPedals && vehicleAvailable -> InputMode.RealPedals
        else -> InputMode.SimulatedPedals
    }

    return InputSourceUiState(
        primaryLabel = activeMode.primaryLabel,
        secondaryLabel = activeMode.secondaryLabel,
        isRealPedals = activeMode == InputMode.RealPedals,
        faded = !vehicleAvailable,
    )
}
