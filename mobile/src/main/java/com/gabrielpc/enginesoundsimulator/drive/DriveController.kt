package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioFocusEvent
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfile
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodBankResolver
import com.gabrielpc.enginesoundsimulator.audio.FmodSourceState
import com.gabrielpc.enginesoundsimulator.audio.AudioMixGainRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioMixGains
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysics
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.simulation.resolveDriveInput
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.resolveTransmissionControl
import com.gabrielpc.enginesoundsimulator.telemetry.transmissionFollowsVehicle
import com.gabrielpc.enginesoundsimulator.telemetry.vehicleDriveSignalsAvailable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val primaryLabel: String, val secondaryLabel: String = "PEDALS") {
    RealPedals("REAL"),
    SimulatedPedals("SIMULATED"),
    ;

    val displayName: String get() = "$primaryLabel $secondaryLabel"
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
    val audioMuted: Boolean = false,
    val selectedCarId: String,
    val selectedCarName: String,
    val selectedCarPreviewAsset: String,
    val selectedCarIndex: Int,
    val availableCarCount: Int,
    val fmodSources: List<FmodSourceState> = emptyList(),
    val transmissionGain: Float = 1.0f,
    val gearShiftGain: Float = 1.0f,
    val turboGain: Float = 1.0f,
    val soundPerspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    val transmissionLockedToVehicle: Boolean = false,
    val carAudioReady: Boolean = false,
    val manualShiftModeEnabled: Boolean = false,
    val userMessage: UserVisibleMessage? = null,
)

/** Coordinates read-only inputs, the authored Assetto drivetrain, and FMOD. */
class DriveController(context: Context) {
    private val appContext = context.applicationContext
    private val selectedCarRepository = SelectedCarRepository(appContext)
    private val bankResolver = FmodBankResolver(appContext)
    // Package manifests are immutable while this controller is running. Keeping the installed
    // catalog out of the 3 ms simulation step prevents dozens of disk reads and JSON parses per
    // frame, which otherwise makes simulated acceleration run behind wall-clock time.
    private val installedProfileCache = AtomicReference(
        FmodBankProfiles.all.filter(bankResolver::isInstalled),
    )
    private val shiftModeRepository = ShiftModeRepository(appContext)
    private val soundPerspectiveRepository = EngineSoundPerspectiveRepository(appContext)
    private val audioMixGainRepository = AudioMixGainRepository(appContext)
    private val selectedProfile = AtomicReference(
        selectedCarRepository.load().takeIf { candidate ->
            installedProfileCache.get().any { it.id == candidate.id }
        }
            ?: installedProfileCache.get().firstOrNull()
            ?: FmodBankProfiles.default,
    )
    private val selectedPerspective = AtomicReference(soundPerspectiveRepository.load(selectedProfile.get()))
    private val manualShiftEnabled = AtomicBoolean(shiftModeRepository.isManualEnabled())
    private val activePhysics = AtomicReference<AssettoPhysics?>(null)
    private val simulation = EngineSimulation()
    private val vehicleReader = BydSpeedReader(appContext)
    private val audioEngine = EngineAudioEngine(appContext)
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val simulatedPedals = AtomicReference(SimulatedPedalInput())
    private val inputMode = AtomicReference(InputMode.RealPedals)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val uiActive = AtomicBoolean(false)
    private val audioInterrupted = AtomicBoolean(false)
    private val audioMuted = AtomicBoolean(false)
    private val audioMixGains = AtomicReference(AudioMixGains())

    @Volatile private var loopThread: Thread? = null
    @Volatile private var userMessage: UserVisibleMessage? = null
    @Volatile private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputSourcePrimary = InputMode.SimulatedPedals.primaryLabel,
        inputSourceSecondary = InputMode.SimulatedPedals.secondaryLabel,
        inputSourceIsRealPedals = false,
        inputSourceFaded = false,
        throttle = 0.0,
        brake = 0.0,
        transmissionPosition = TransmissionPosition.DRIVE,
        engineSoundEnabled = false,
        selectedCarId = selectedProfile.get().id,
        selectedCarName = selectedProfile.get().displayName,
        selectedCarPreviewAsset = selectedProfile.get().previewAssetName,
        selectedCarIndex = installedProfiles().indexOf(selectedProfile.get()),
        availableCarCount = installedProfiles().size,
        soundPerspective = selectedPerspective.get(),
    )

    init {
        loadPhysics(selectedProfile.get())
        simulation.manualShiftEnabled = manualShiftEnabled.get()
        audioEngine.setFocusChangeListener(::handleAudioFocusChange)
        audioEngine.setSoundProgram(selectedProfile.get(), selectedPerspective.get())
        audioMixGains.set(audioMixGainRepository.load(selectedProfile.get()))
        audioEngine.setCategoryGains(audioMixGains.get())
    }

    fun isRunning(): Boolean = running.get()

    fun setUiActive(active: Boolean) { uiActive.set(active) }

    fun snapshot(): DriveSnapshot {
        val base = latest
        return base.copy(
            engineSoundEnabled = audioEngine.isAudioActive(),
            audioMuted = audioMuted.get(),
            manualShiftModeEnabled = manualShiftEnabled.get(),
            fmodSources = if (uiActive.get()) audioEngine.sourceSnapshots() else emptyList(),
            transmissionGain = audioMixGains.get().transmission,
            gearShiftGain = audioMixGains.get().gearShift,
            turboGain = audioMixGains.get().turbo,
            carAudioReady = audioEngine.loadedBankProfileId() == selectedProfile.get().id,
            userMessage = userMessage,
        )
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && loopThread?.isAlive == true) return
            refreshInstalledProfileCache()
            loopThread?.let { thread ->
                thread.interrupt()
                joinLoop(thread)
            }
            val runId = generation.incrementAndGet()
            running.set(true)
            val thread = Thread({ runLoop(runId) }, "drivetrain-simulation").apply { isDaemon = true }
            loopThread = thread
            try {
                vehicleReader.start()
                if (!audioMuted.get()) audioEngine.start()
                thread.start()
            } catch (error: Throwable) {
                running.set(false)
                generation.incrementAndGet()
                vehicleReader.stop()
                audioEngine.stop()
                throw error
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            generation.incrementAndGet()
            loopThread?.interrupt()
            loopThread?.let(::joinLoop)
            loopThread = null
            vehicleReader.stop()
            audioEngine.stop()
            simulatedPedals.set(SimulatedPedalInput())
        }
    }

    fun setSimulatedPedalThrottle(value: Double) { simulatedPedals.updateAndGet { it.copy(throttle = value.coerceIn(0.0, 1.0)) } }
    fun setSimulatedPedalBrake(value: Double) { simulatedPedals.updateAndGet { it.copy(brake = value.coerceIn(0.0, 1.0)) } }

    fun setFmodHostGains(engine: Float, effects: Float) = audioEngine.setHostGains(engine, effects)
    fun setFmodCategoryGains(transmission: Float, gearShift: Float, turbo: Float) {
        // These trims are intentionally per-car and survive normal APK updates. Reset All is the
        // explicit opt-in that clears them, so selecting another car never carries a hidden mix.
        val gains = AudioMixGains(transmission, gearShift, turbo)
        audioMixGains.set(gains)
        audioMixGainRepository.save(selectedProfile.get(), gains)
        audioEngine.setCategoryGains(gains)
    }

    fun resetAllPreferences() {
        audioMixGainRepository.resetAll()
        appContext.getSharedPreferences(AppPreferenceStores.SELECTED_CAR, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(AppPreferenceStores.SHIFT_MODE, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(AppPreferenceStores.ENGINE_SOUND_PERSPECTIVE, Context.MODE_PRIVATE).edit().clear().apply()
        audioMixGains.set(AudioMixGains())
        selectedProfile.set(installedProfiles().firstOrNull() ?: FmodBankProfiles.default)
        selectedPerspective.set(EngineSoundPerspective.CABIN)
        audioEngine.setCategoryGains(AudioMixGains())
        simulation.reset()
        audioEngine.setSoundProgram(selectedProfile.get(), selectedPerspective.get())
    }
    fun setFmodEventMute(eventName: String, muted: Boolean) = audioEngine.setEventMute(eventName, muted)
    fun setFmodEventSolo(eventName: String, solo: Boolean) = audioEngine.setEventSolo(eventName, solo)
    fun setInputMode(mode: InputMode) { inputMode.set(mode) }

    /**
     * Muting stops FMOD completely. Unmuting deliberately performs a full stop/start cycle so
     * stale event instances, voices, and decoder state cannot survive the user's reset gesture.
     */
    fun toggleAudioMute(): Boolean = synchronized(lifecycleLock) {
        val shouldMute = !audioMuted.get()
        audioMuted.set(shouldMute)
        if (shouldMute) {
            audioEngine.stop()
        } else if (running.get() && !audioInterrupted.get()) {
            audioEngine.stop()
            audioEngine.start()
        }
        shouldMute
    }
    fun selectSimulatedPedals() { inputMode.set(InputMode.SimulatedPedals) }
    fun selectRealPedals() { if (vehicleReader.snapshot().vehicleDriveSignalsAvailable()) inputMode.set(InputMode.RealPedals) }
    fun toggleInputSource() {
        if (inputMode.get() == InputMode.RealPedals) inputMode.set(InputMode.SimulatedPedals)
        else if (vehicleReader.snapshot().vehicleDriveSignalsAvailable()) inputMode.set(InputMode.RealPedals)
    }

    fun setTransmissionPosition(position: TransmissionPosition) {
        if (!vehicleReader.snapshot().transmissionFollowsVehicle(inputMode.get())) transmissionPosition.set(position)
    }

    fun setSoundPerspective(perspective: EngineSoundPerspective) {
        val profile = selectedProfile.get()
        selectedPerspective.set(soundPerspectiveRepository.save(profile, perspective))
        audioEngine.setSoundProgram(profile, perspective)
    }

    fun selectPreviousCar() { selectAdjacentCar(-1) }
    fun selectNextCar() { selectAdjacentCar(1) }
    fun selectCar(profileId: String) {
        FmodBankProfiles.find(profileId).takeIf(bankResolver::isInstalled)?.let(::applySelectedCar)
    }

    fun toggleManualShiftMode() {
        val enabled = !manualShiftEnabled.get()
        shiftModeRepository.setManualEnabled(enabled)
        manualShiftEnabled.set(enabled)
        simulation.manualShiftEnabled = enabled
    }

    fun requestManualUpshift(): Boolean = synchronized(lifecycleLock) {
        if (transmissionPosition.get() != TransmissionPosition.DRIVE) false else simulation.requestManualUpshift()
    }

    fun requestManualDownshift(): Boolean = synchronized(lifecycleLock) {
        if (transmissionPosition.get() != TransmissionPosition.DRIVE) false else simulation.requestManualDownshift()
    }

    fun handleShiftKey(keyCode: Int): Boolean {
        if (!manualShiftEnabled.get()) return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> requestManualUpshift()
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> requestManualDownshift()
            else -> false
        }
    }

    fun dismissUserMessage() { userMessage = null }

    private fun selectAdjacentCar(offset: Int) {
        val installed = installedProfiles()
        if (installed.isEmpty()) return
        val current = installed.indexOfFirst { it.id == selectedProfile.get().id }.coerceAtLeast(0)
        applySelectedCar(installed[(current + offset).mod(installed.size)])
    }

    private fun applySelectedCar(profile: FmodBankProfile) {
        synchronized(lifecycleLock) {
            selectedProfile.set(profile)
            selectedCarRepository.save(profile)
            selectedPerspective.set(soundPerspectiveRepository.load(profile))
            audioMixGains.set(audioMixGainRepository.load(profile))
            audioEngine.setCategoryGains(audioMixGains.get())
            loadPhysics(profile)
            simulation.reset()
            audioEngine.setSoundProgram(profile, selectedPerspective.get())
        }
    }

    private fun loadPhysics(profile: FmodBankProfile) {
        val physics = runCatching { bankResolver.physics(profile) }.getOrNull()
        activePhysics.set(physics)
        if (physics != null) simulation.updateAssettoPhysics(physics)
        else userMessage = UserVisibleMessage(
            id = SystemClock.elapsedRealtime(),
            title = "Car audio is not installed",
            detail = "Install the package group containing ${profile.displayName} in the audio installer.",
        )
    }

    private fun installedProfiles(): List<FmodBankProfile> =
        installedProfileCache.get()

    private fun refreshInstalledProfileCache() {
        installedProfileCache.set(FmodBankProfiles.all.filter(bankResolver::isInstalled))
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
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = ((now - previousNanos) / 1_000_000_000.0).coerceIn(0.0, 0.050)
            previousNanos = now
            accumulatorSeconds += elapsed
            while (accumulatorSeconds >= FIXED_STEP_SECONDS && isCurrent(runId)) {
                step(FIXED_STEP_SECONDS)
                accumulatorSeconds -= FIXED_STEP_SECONDS
            }
            val remaining = FIXED_STEP_NANOS - (SystemClock.elapsedRealtimeNanos() - now)
            if (remaining > 0) LockSupport.parkNanos(remaining)
        }
    }

    private fun step(dt: Double) {
        val telemetry = vehicleReader.snapshot()
        val mode = inputMode.get()
        val pedals = simulatedPedals.get()
        val input = resolveDriveInput(mode, telemetry, pedals.throttle, pedals.brake)
        val transmission = resolveTransmissionControl(mode, telemetry, transmissionPosition.get())
        if (transmission.lockedToVehicle) transmissionPosition.set(transmission.position)
        simulation.manualShiftEnabled = manualShiftEnabled.get()
        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                simulatedPedals = input.usesSimulatedPedals,
                realReportedRawSpeedKmh = input.realReportedRawSpeedKmh,
                transmissionPosition = transmission.position,
            ),
            dt,
        )
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = drivetrain.audioThrottle,
                gear = drivetrain.gear,
                isShifting = drivetrain.isShifting,
                shiftProgress = drivetrain.shiftProgress,
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = when (drivetrain.shiftDirection) {
                    ShiftDirection.UP -> 1
                    ShiftDirection.DOWN -> -1
                    ShiftDirection.NONE -> 0
                },
                limiterPulse = drivetrain.limiterPulse,
                backfireTriggered = drivetrain.backfireTriggered,
                shiftRejected = drivetrain.shiftRejected,
                tractionLimitActive = drivetrain.tractionLimitActive,
                tractionLimitPulse = drivetrain.tractionLimitPulse,
                drivetrainSpeedRadiansPerSecond = drivetrain.drivetrainSpeedRadiansPerSecond,
                boost = drivetrain.boost,
                maximumBoost = activePhysics.get()?.engine?.turbos?.sumOf { it.maximumBoost } ?: 0.0,
                bov = drivetrain.bov,
                bovDecaySeconds = drivetrain.bovDecaySeconds,
                perspective = selectedPerspective.get(),
            ),
        )
        val selected = selectedProfile.get()
        val sourceUi = resolveInputSourceUi(mode, telemetry.vehicleDriveSignalsAvailable())
        if (uiActive.get()) {
            latest = DriveSnapshot(
                drivetrain = drivetrain,
                inputSourcePrimary = sourceUi.primaryLabel,
                inputSourceSecondary = sourceUi.secondaryLabel,
                inputSourceIsRealPedals = sourceUi.isRealPedals,
                inputSourceFaded = sourceUi.faded,
                throttle = input.throttle,
                brake = input.brake,
                transmissionPosition = transmission.position,
                engineSoundEnabled = audioEngine.isAudioActive(),
                audioMuted = audioMuted.get(),
                selectedCarId = selected.id,
                selectedCarName = selected.displayName,
                selectedCarPreviewAsset = selected.previewAssetName,
                selectedCarIndex = installedProfiles().indexOf(selected),
                availableCarCount = installedProfiles().size,
                soundPerspective = selectedPerspective.get(),
                transmissionLockedToVehicle = transmission.lockedToVehicle,
                carAudioReady = audioEngine.loadedBankProfileId() == selected.id,
                userMessage = userMessage,
            )
        }
        handleAudioLoadFailures()
    }

    private fun handleAudioLoadFailures() {
        val failure = audioEngine.consumeLoadFailure() ?: return
        if (failure.profileId == selectedProfile.get().id) {
            userMessage = UserVisibleMessage(
                id = SystemClock.elapsedRealtime(),
                title = "Engine audio failed to load",
                detail = "${selectedProfile.get().displayName}: ${failure.detail}",
            )
        }
    }

    private fun handleAudioFocusChange(event: AudioFocusEvent) {
        when (event) {
            AudioFocusEvent.TRANSIENT_LOSS, AudioFocusEvent.TRANSIENT_DUCK -> {
                audioInterrupted.set(true)
                audioEngine.stop()
            }
            AudioFocusEvent.TRANSIENT_GAIN -> {
                audioInterrupted.set(false)
                if (running.get() && !audioMuted.get()) audioEngine.start()
            }
            AudioFocusEvent.PERMANENT_LOSS -> {
                audioInterrupted.set(true)
                audioEngine.stop()
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean = running.get() && generation.get() == runId
    private fun joinLoop(thread: Thread) { if (thread !== Thread.currentThread()) runCatching { thread.join(500L) } }

    private data class SimulatedPedalInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 0.003
        const val FIXED_STEP_NANOS = 3_000_000L
        const val INTERRUPTED_IDLE_NANOS = 50_000_000L
    }
}

internal fun resolveInputSourceUi(selectedMode: InputMode, vehicleAvailable: Boolean): InputSourceUiState {
    val activeMode = if (selectedMode == InputMode.RealPedals && vehicleAvailable) selectedMode else InputMode.SimulatedPedals
    return InputSourceUiState(
        primaryLabel = activeMode.primaryLabel,
        secondaryLabel = activeMode.secondaryLabel,
        isRealPedals = activeMode == InputMode.RealPedals,
        faded = !vehicleAvailable,
    )
}

internal data class InputSourceUiState(
    val primaryLabel: String,
    val secondaryLabel: String,
    val isRealPedals: Boolean,
    val faded: Boolean,
)
