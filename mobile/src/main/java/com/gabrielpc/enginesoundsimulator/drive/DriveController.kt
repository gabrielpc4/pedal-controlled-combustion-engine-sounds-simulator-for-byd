package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Debug
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
import com.gabrielpc.enginesoundsimulator.audio.FmodUpdateRate
import com.gabrielpc.enginesoundsimulator.audio.FmodUpdateRateRepository
import com.gabrielpc.enginesoundsimulator.audio.ExteriorAudioModeRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioMixGainRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioMixGains
import com.gabrielpc.enginesoundsimulator.audio.CarEffectModes
import com.gabrielpc.enginesoundsimulator.audio.CarEffectModesRepository
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugScenarioOverride
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugTelemetry
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
import com.gabrielpc.enginesoundsimulator.telemetry.vehicleDriveSignalsAvailable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val primaryLabel: String, val secondaryLabel: String = "PEDALS") {
    RealPedals("REAL"),
    SimulatedPedals("SIMULATED"),
    ;

    val displayName: String get() = "$primaryLabel $secondaryLabel"
}

// In Hold Pedals mode, the bottom part of the touch track is an explicit release gesture. This
// keeps a latched value from requiring pixel-perfect travel back to zero before disengaging.
private const val HELD_PEDAL_RELEASE_THRESHOLD = 0.10

data class DriveSnapshot(
    val drivetrain: DrivetrainState,
    val inputSourcePrimary: String,
    val inputSourceSecondary: String,
    val inputSourceIsRealPedals: Boolean,
    /** True when simulated pedal percentages remain latched after the pointer is released. */
    val simulatedPedalsLatched: Boolean = false,
    val inputSourceFaded: Boolean,
    val throttle: Double,
    val brake: Double,
    val simulatedRegen: Double = 1.0,
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
    val backfireGain: Float = 1.0f,
    /** Session-only listening aid; when enabled native FMOD leaves only backfire events audible. */
    val backfireOnly: Boolean = false,
    /** Global backfire policy, deliberately independent of each car bank's authored thresholds. */
    val backfireSettings: BackfireSettings = BackfireSettings(),
    val shiftSoundSettings: ShiftSoundSettings = ShiftSoundSettings(),
    val transmissionSoundSettings: TransmissionSoundSettings = TransmissionSoundSettings(),
    val popsAndBangsEnabled: Boolean = true,
    val popsAndBangsOriginal: Boolean = false,
    val shiftSoundsEnabled: Boolean = true,
    val shiftSoundsOriginal: Boolean = true,
    val transmissionEnabled: Boolean = true,
    val turboEnabled: Boolean = true,
    val hasTurbo: Boolean = false,
    val soundPerspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    val transmissionLockedToVehicle: Boolean = false,
    val carAudioReady: Boolean = false,
    val manualShiftModeEnabled: Boolean = false,
    val fmodUpdateRateHz: Int = FmodUpdateRate.DEFAULT_HZ,
    val autoblipEnabled: Boolean = true,
    val exteriorPureAudio: Boolean = false,
    val userMessage: UserVisibleMessage? = null,
)

/** Runtime-only selection restored once an ADB diagnostic scenario ends. */
private data class DebugScenarioBaseline(
    val profile: FmodBankProfile,
    val perspective: EngineSoundPerspective,
)

/** Coordinates read-only inputs, the authored Assetto drivetrain, and FMOD. */
class DriveController(context: Context) {
    private val appContext = context.applicationContext
    private val selectedCarRepository = SelectedCarRepository(appContext)
    private val bankResolver = FmodBankResolver(appContext)
    // Package manifests are immutable while this controller is running. Keeping the installed
    // catalog out of the fixed-step simulation prevents disk reads and JSON parses on every
    // physical frame, which otherwise makes simulated acceleration run behind wall-clock time.
    private val installedProfileCache = AtomicReference(
        FmodBankProfiles.all.filter(bankResolver::isInstalled),
    )
    private val shiftModeRepository = ShiftModeRepository(appContext)
    private val soundPerspectiveRepository = EngineSoundPerspectiveRepository(appContext)
    private val audioMixGainRepository = AudioMixGainRepository(appContext)
    private val fmodUpdateRateRepository = FmodUpdateRateRepository(appContext)
    private val exteriorAudioModeRepository = ExteriorAudioModeRepository(appContext)
    private val backfireSettingsRepository = BackfireSettingsRepository(appContext)
    private val shiftSoundSettingsRepository = ShiftSoundSettingsRepository(appContext)
    private val transmissionSoundSettingsRepository = TransmissionSoundSettingsRepository(appContext)
    private val carEffectModesRepository = CarEffectModesRepository(appContext)
    private val autoblipRepository = AutoblipRepository(appContext)
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
    private val simulatedPedalsLatched = AtomicBoolean(false)
    private val simulatedRegen = AtomicReference(1.0)
    private val inputMode = AtomicReference(InputMode.RealPedals)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val uiActive = AtomicBoolean(false)
    private val audioInterrupted = AtomicBoolean(false)
    private val audioMuted = AtomicBoolean(false)
    // Deliberately session-only: this diagnostic/listening mode must never become a car preference.
    private val backfireOnly = AtomicBoolean(false)
    private val backfireSettings = AtomicReference(BackfireSettings())
    private val shiftSoundSettings = AtomicReference(ShiftSoundSettings())
    private val transmissionSoundSettings = AtomicReference(TransmissionSoundSettings())
    private val carEffectModes = AtomicReference(CarEffectModes())
    private val audioMixGains = AtomicReference(AudioMixGains())
    private val fmodUpdateRateHz = AtomicInteger(fmodUpdateRateRepository.load())
    private val autoblipEnabled = AtomicBoolean(autoblipRepository.load())
    private val exteriorPureAudio = AtomicBoolean(exteriorAudioModeRepository.load())
    /** Monotonic across the controller lifetime so audio-worker skips/repeats are measurable. */
    private val simulationFrameSerial = AtomicLong(0L)
    private var consumedDebugScenarioShiftSerial = 0L
    private var activeDebugScenarioId = 0L
    private var debugScenarioBaseline: DebugScenarioBaseline? = null

    @Volatile private var loopThread: Thread? = null
    @Volatile private var userMessage: UserVisibleMessage? = null
    private var nextUiSnapshotNanos = 0L
    @Volatile private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputSourcePrimary = InputMode.SimulatedPedals.primaryLabel,
        inputSourceSecondary = InputMode.SimulatedPedals.secondaryLabel,
        inputSourceIsRealPedals = false,
        simulatedPedalsLatched = false,
        inputSourceFaded = false,
        throttle = 0.0,
        brake = 0.0,
        simulatedRegen = 1.0,
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
        // Gain semantics changed from percentage-like 0..2 values to 1..10x. Deliberately discard
        // the old preference namespace rather than migrating values into the new scale.
        appContext.getSharedPreferences(AppPreferenceStores.AUDIO_MIX_GAINS_LEGACY, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        loadPhysics(selectedProfile.get())
        simulation.manualShiftEnabled = manualShiftEnabled.get()
        audioEngine.setFocusChangeListener(::handleAudioFocusChange)
        audioEngine.setFmodUpdateRateHz(fmodUpdateRateHz.get())
        audioEngine.setExteriorPureAudio(exteriorPureAudio.get())
        audioEngine.setSoundProgram(selectedProfile.get(), selectedPerspective.get())
        audioMixGains.set(audioMixGainRepository.load(selectedProfile.get()))
        audioEngine.setCategoryGains(audioMixGains.get())
        backfireSettings.set(backfireSettingsRepository.load())
        shiftSoundSettings.set(shiftSoundSettingsRepository.load())
        transmissionSoundSettings.set(transmissionSoundSettingsRepository.load())
        carEffectModes.set(carEffectModesRepository.load(selectedProfile.get()))
        simulation.updateBackfireSettings(backfireSettings.get())
        simulation.setUseOriginalBackfire(carEffectModes.get().popsAndBangsOriginal)
        simulation.updateAutoblipEnabled(autoblipEnabled.get())
        audioEngine.setBackfireAllowedSamples(backfireSettings.get().allowedSamples)
        audioEngine.setBackfireAudioEnabled(carEffectModes.get().popsAndBangsEnabled)
        audioEngine.setBackfireUseOriginal(carEffectModes.get().popsAndBangsOriginal)
        audioEngine.setShiftSoundEnabled(carEffectModes.get().shiftSoundsEnabled)
        audioEngine.setShiftSoundOverride(!carEffectModes.get().shiftSoundsOriginal)
        audioEngine.setShiftOverrideGain(shiftSoundSettings.get().overrideGain)
        audioEngine.setGlobalTransmissionGain(transmissionSoundSettings.get().globalGain)
        audioEngine.setTransmissionAudioEnabled(carEffectModes.get().transmissionEnabled)
        audioEngine.setTurboAudioEnabled(carEffectModes.get().turboEnabled)
    }

    fun isRunning(): Boolean = running.get()

    fun setUiActive(active: Boolean) { uiActive.set(active) }

    fun setMixerDiagnosticsActive(active: Boolean) {
        audioEngine.setMixerDiagnosticsActive(active)
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        return base.copy(
            engineSoundEnabled = audioEngine.isAudioActive(),
            audioMuted = audioMuted.get(),
            manualShiftModeEnabled = manualShiftEnabled.get(),
            fmodSources = if (uiActive.get() && audioEngine.isMixerDiagnosticsActive()) {
                audioEngine.sourceSnapshots()
            } else {
                emptyList()
            },
            transmissionGain = audioMixGains.get().transmission,
            gearShiftGain = audioMixGains.get().gearShift,
            turboGain = audioMixGains.get().turbo,
            backfireGain = audioMixGains.get().backfire,
            backfireOnly = backfireOnly.get(),
            backfireSettings = backfireSettings.get(),
            shiftSoundSettings = shiftSoundSettings.get(),
            transmissionSoundSettings = transmissionSoundSettings.get(),
            popsAndBangsEnabled = carEffectModes.get().popsAndBangsEnabled,
            popsAndBangsOriginal = carEffectModes.get().popsAndBangsOriginal,
            shiftSoundsEnabled = carEffectModes.get().shiftSoundsEnabled,
            shiftSoundsOriginal = carEffectModes.get().shiftSoundsOriginal,
            transmissionEnabled = carEffectModes.get().transmissionEnabled,
            turboEnabled = carEffectModes.get().turboEnabled,
            hasTurbo = activePhysics.get()?.engine?.turbos?.isNotEmpty() == true,
            fmodUpdateRateHz = fmodUpdateRateHz.get(),
            autoblipEnabled = autoblipEnabled.get(),
            exteriorPureAudio = exteriorPureAudio.get(),
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
            backfireOnly.set(false)
            audioEngine.setBackfireOnly(false)
            simulatedPedals.set(SimulatedPedalInput())
            simulatedRegen.set(1.0)
            simulatedPedalsLatched.set(false)
        }
    }

    fun setSimulatedPedalsLatched(enabled: Boolean) {
        simulatedPedalsLatched.set(enabled)
        if (!enabled) simulatedPedals.set(SimulatedPedalInput())
    }

    fun setSimulatedPedalThrottle(value: Double) {
        val clamped = value.coerceIn(0.0, 1.0)
        // PedalControl emits an exact zero from its pointer-release callback. In Hold Pedals
        // mode that callback must not clear the latched value; only an intentional low travel
        // sample (0 < value <= 10%) is the explicit release gesture.
        if (simulatedPedalsLatched.get() && clamped == 0.0) return
        val effective = if (simulatedPedalsLatched.get() && clamped <= HELD_PEDAL_RELEASE_THRESHOLD) {
            0.0
        } else {
            clamped
        }
        simulatedPedals.updateAndGet { it.copy(throttle = effective) }
    }

    fun setSimulatedPedalBrake(value: Double) {
        val clamped = value.coerceIn(0.0, 1.0)
        // See throttle above: distinguish the UI's pointer-release callback from low travel.
        if (simulatedPedalsLatched.get() && clamped == 0.0) return
        val effective = if (simulatedPedalsLatched.get() && clamped <= HELD_PEDAL_RELEASE_THRESHOLD) {
            0.0
        } else {
            clamped
        }
        simulatedPedals.updateAndGet { it.copy(brake = effective) }
    }

    fun setSimulatedRegen(value: Double) { simulatedRegen.set(value.coerceIn(0.0, 1.0)) }

    fun setFmodUpdateRateHz(rateHz: Int) {
        val normalized = FmodUpdateRate.normalize(rateHz)
        fmodUpdateRateHz.set(normalized)
        fmodUpdateRateRepository.save(normalized)
        audioEngine.setFmodUpdateRateHz(normalized)
    }

    fun setAutoblipEnabled(enabled: Boolean) {
        autoblipEnabled.set(enabled)
        autoblipRepository.save(enabled)
        simulation.updateAutoblipEnabled(enabled)
    }

    fun setFmodHostGains(engine: Float, effects: Float) = audioEngine.setHostGains(engine, effects)

    fun setExteriorPureAudio(enabled: Boolean) {
        exteriorPureAudio.set(enabled)
        exteriorAudioModeRepository.save(enabled)
        audioEngine.setExteriorPureAudio(enabled)
    }

    fun setEffectEnabled(kind: EffectSoundKind, enabled: Boolean) {
        val updated = carEffectModes.get().withEnabled(kind, enabled)
        carEffectModes.set(updated)
        carEffectModesRepository.save(selectedProfile.get(), updated)
        when (kind) {
            EffectSoundKind.POPS_AND_BANGS -> audioEngine.setBackfireAudioEnabled(enabled)
            EffectSoundKind.SHIFT -> audioEngine.setShiftSoundEnabled(enabled)
            EffectSoundKind.TRANSMISSION -> audioEngine.setTransmissionAudioEnabled(enabled)
            EffectSoundKind.TURBO -> audioEngine.setTurboAudioEnabled(enabled)
        }
    }

    fun setEffectOriginal(kind: EffectSoundKind, original: Boolean) {
        val updated = carEffectModes.get().withOriginal(kind, original)
        carEffectModes.set(updated)
        carEffectModesRepository.save(selectedProfile.get(), updated)
        when (kind) {
            EffectSoundKind.POPS_AND_BANGS -> {
                audioEngine.setBackfireUseOriginal(original)
                simulation.setUseOriginalBackfire(original)
            }
            EffectSoundKind.SHIFT -> {
                audioEngine.setShiftSoundOverride(!original)
                shiftSoundSettings.set(ShiftSoundSettings(overrideEnabled = !original))
                shiftSoundSettingsRepository.save(shiftSoundSettings.get())
            }
            EffectSoundKind.TRANSMISSION, EffectSoundKind.TURBO -> Unit
        }
    }
    fun setFmodCategoryGains(transmission: Float, gearShift: Float, turbo: Float, backfire: Float) {
        // These trims are intentionally per-car and survive normal APK updates. Reset All is the
        // explicit opt-in that clears them, so selecting another car never carries a hidden mix.
        val gains = AudioMixGains(
            transmission.coerceIn(0.5f, 3.0f),
            gearShift.coerceIn(0.5f, 3.0f),
            turbo.coerceIn(0.5f, 3.0f),
            backfire.coerceIn(0.5f, 3.0f),
        )
        audioMixGains.set(gains)
        audioMixGainRepository.save(selectedProfile.get(), gains)
        audioEngine.setCategoryGains(gains)
    }

    fun setBackfireOnly(enabled: Boolean) {
        backfireOnly.set(enabled)
        audioEngine.setBackfireOnly(enabled)
    }

    fun setBackfireSettings(updated: BackfireSettings) {
        val normalized = updated.normalized()
        backfireSettings.set(normalized)
        backfireSettingsRepository.save(normalized)
        simulation.updateBackfireSettings(normalized)
        audioEngine.setBackfireAllowedSamples(normalized.allowedSamples)
        audioEngine.setBackfireAudioEnabled(carEffectModes.get().popsAndBangsEnabled)
        val currentGains = audioMixGains.get()
        if (currentGains.backfire != normalized.backfireGain) {
            val updatedGains = currentGains.copy(backfire = normalized.backfireGain)
            audioMixGains.set(updatedGains)
            audioMixGainRepository.save(selectedProfile.get(), updatedGains)
            audioEngine.setCategoryGains(updatedGains)
        }
    }

    fun setShiftSoundSettings(updated: ShiftSoundSettings) {
        shiftSoundSettings.set(updated)
        shiftSoundSettingsRepository.save(updated)
        audioEngine.setShiftSoundOverride(updated.overrideEnabled)
        audioEngine.setShiftOverrideGain(updated.overrideGain)
    }

    fun setTransmissionSoundSettings(updated: TransmissionSoundSettings) {
        val normalized = updated.copy(globalGain = updated.globalGain.coerceIn(0.25f, 1.0f))
        transmissionSoundSettings.set(normalized)
        transmissionSoundSettingsRepository.save(normalized)
        audioEngine.setGlobalTransmissionGain(normalized.globalGain)
    }

    fun resetAllPreferences() {
        audioMixGainRepository.resetAll()
        appContext.getSharedPreferences(AppPreferenceStores.SELECTED_CAR, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(AppPreferenceStores.SHIFT_MODE, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(AppPreferenceStores.ENGINE_SOUND_PERSPECTIVE, Context.MODE_PRIVATE).edit().clear().apply()
        appContext.getSharedPreferences(AppPreferenceStores.CAR_PICKER_GROUP, Context.MODE_PRIVATE).edit().clear().apply()
        backfireSettingsRepository.reset()
        shiftSoundSettingsRepository.reset()
        transmissionSoundSettingsRepository.reset()
        autoblipRepository.reset()
        carEffectModesRepository.resetAll()
        fmodUpdateRateRepository.reset()
        exteriorAudioModeRepository.reset()
        audioMixGains.set(AudioMixGains())
        fmodUpdateRateHz.set(FmodUpdateRate.DEFAULT_HZ)
        exteriorPureAudio.set(false)
        backfireSettings.set(BackfireSettings())
        shiftSoundSettings.set(ShiftSoundSettings())
        transmissionSoundSettings.set(TransmissionSoundSettings())
        autoblipEnabled.set(true)
        simulation.updateAutoblipEnabled(true)
        carEffectModes.set(CarEffectModes())
        simulation.updateBackfireSettings(backfireSettings.get())
        setBackfireOnly(false)
        audioEngine.setBackfireAudioEnabled(true)
        audioEngine.setBackfireUseOriginal(false)
        audioEngine.setShiftSoundEnabled(true)
        audioEngine.setShiftSoundOverride(false)
        audioEngine.setGlobalTransmissionGain(transmissionSoundSettings.get().globalGain)
        audioEngine.setTransmissionAudioEnabled(true)
        audioEngine.setTurboAudioEnabled(true)
        selectedProfile.set(installedProfiles().firstOrNull() ?: FmodBankProfiles.default)
        selectedPerspective.set(EngineSoundPerspective.CABIN)
        audioEngine.setCategoryGains(AudioMixGains())
        audioEngine.setFmodUpdateRateHz(FmodUpdateRate.DEFAULT_HZ)
        audioEngine.setExteriorPureAudio(false)
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
    fun setTransmissionPosition(position: TransmissionPosition) { transmissionPosition.set(position) }
    fun selectRealPedals() { if (vehicleReader.snapshot().vehicleDriveSignalsAvailable()) inputMode.set(InputMode.RealPedals) }
    fun toggleInputSource() {
        if (inputMode.get() == InputMode.RealPedals) inputMode.set(InputMode.SimulatedPedals)
        else if (vehicleReader.snapshot().vehicleDriveSignalsAvailable()) inputMode.set(InputMode.RealPedals)
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
            val modes = carEffectModesRepository.load(profile)
            carEffectModes.set(modes)
            audioEngine.setBackfireAudioEnabled(modes.popsAndBangsEnabled)
            audioEngine.setBackfireUseOriginal(modes.popsAndBangsOriginal)
            audioEngine.setShiftSoundEnabled(modes.shiftSoundsEnabled)
            audioEngine.setShiftSoundOverride(!modes.shiftSoundsOriginal)
            audioEngine.setTransmissionAudioEnabled(modes.transmissionEnabled)
            audioEngine.setTurboAudioEnabled(modes.turboEnabled)
            // This is intentionally reset per car because it is a temporary listening filter,
            // not part of the authored mix or a persistent vehicle preference.
            setBackfireOnly(false)
            audioEngine.setCategoryGains(audioMixGains.get())
            loadPhysics(profile)
            simulation.reset()
            audioEngine.setSoundProgram(profile, selectedPerspective.get())
        }
    }

    private fun loadPhysics(profile: FmodBankProfile) {
        val physics = runCatching { bankResolver.physics(profile) }.getOrNull()
        activePhysics.set(physics)
        if (physics != null) {
            simulation.updateAssettoPhysics(physics)
            simulation.updateBackfireSettings(backfireSettings.get())
        } else userMessage = UserVisibleMessage(
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
            val simulationRateHz = fmodUpdateRateHz.get()
            val simulationStepSeconds = FmodUpdateRate.stepSeconds(simulationRateHz)
            val simulationStepNanos = FmodUpdateRate.periodNanos(simulationRateHz)
            while (accumulatorSeconds >= simulationStepSeconds && isCurrent(runId)) {
                step(simulationStepSeconds)
                accumulatorSeconds -= simulationStepSeconds
            }
            val remaining = simulationStepNanos - (SystemClock.elapsedRealtimeNanos() - now)
            if (remaining > 0) LockSupport.parkNanos(remaining)
        }
    }

    private fun step(dt: Double) {
        val telemetry = vehicleReader.snapshot()
        val scenario = DebugTelemetry.scenarioOverride(SystemClock.elapsedRealtimeNanos())
        if (scenario != null) applyDebugScenario(scenario)
        else restoreDebugScenarioIfNeeded()
        val mode = scenario?.inputModeOrdinal
            ?.let { InputMode.entries.getOrNull(it) }
            ?: inputMode.get()
        val pedals = simulatedPedals.get()
        val input = resolveDriveInput(
            mode,
            telemetry,
            scenario?.throttle ?: pedals.throttle,
            scenario?.brake ?: pedals.brake,
        )
        val selectedTransmissionPosition = scenario?.transmissionPositionOrdinal
            ?.let { TransmissionPosition.entries.getOrNull(it) }
            ?: transmissionPosition.get()
        val transmission = resolveTransmissionControl(mode, telemetry, selectedTransmissionPosition)
        if (transmission.lockedToVehicle) transmissionPosition.set(transmission.position)
        simulation.manualShiftEnabled = scenario?.manualModeEnabled ?: manualShiftEnabled.get()
        if (
            scenario != null &&
            scenario.manualShiftSerial != 0L &&
            scenario.manualShiftSerial != consumedDebugScenarioShiftSerial
        ) {
            consumedDebugScenarioShiftSerial = scenario.manualShiftSerial
            when (scenario.manualShiftDirection) {
                1 -> simulation.requestManualUpshift()
                -1 -> simulation.requestManualDownshift()
            }
        }
        val measurePerformance = DebugTelemetry.performanceEnabled()
        val simulationWallStartedNanos = if (measurePerformance) System.nanoTime() else 0L
        val simulationCpuStartedNanos = if (measurePerformance) Debug.threadCpuTimeNanos() else 0L
        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                simulatedPedals = input.usesSimulatedPedals,
                realReportedRawSpeedKmh = input.realReportedRawSpeedKmh,
                transmissionPosition = transmission.position,
                simulatedRegen = simulatedRegen.get(),
            ),
            dt,
        )
        if (measurePerformance) {
            DebugTelemetry.recordSimulationPerformance(
                cpuNanos = Debug.threadCpuTimeNanos() - simulationCpuStartedNanos,
                wallNanos = System.nanoTime() - simulationWallStartedNanos,
            )
        }
        val simulationFrameId = simulationFrameSerial.incrementAndGet()
        val shiftDirection = when (drivetrain.shiftDirection) {
            ShiftDirection.UP -> 1
            ShiftDirection.DOWN -> -1
            ShiftDirection.NONE -> 0
        }
        val frameTimestampNanos = SystemClock.elapsedRealtimeNanos()
        DebugTelemetry.recordSimulation(
            timestampNanos = frameTimestampNanos,
            simulationFrameId = simulationFrameId,
            profileId = selectedProfile.get().id,
            inputMode = mode.name,
            perspectiveOrdinal = selectedPerspective.get().ordinal,
            rawSpeedKmh = drivetrain.realOrDocumentedRawSpeedKmh,
            presentationSpeedKmh = drivetrain.presentationSpeedKmh,
            presentationAccelerationKmhPerSecond = drivetrain.presentationAccelerationKmhPerSecond,
            fmodDrivetrainSpeedKmh = drivetrain.fmodDrivetrainSpeedKmh,
            rpm = drivetrain.rpm,
            gear = drivetrain.gear,
            clutch = drivetrain.clutch,
            transmissionPosition = transmission.position.ordinal,
            throttle = input.throttle,
            brake = input.brake,
            boost = drivetrain.boost,
            bov = drivetrain.bov,
            bovDecaySeconds = drivetrain.bovDecaySeconds,
            isShifting = drivetrain.isShifting,
            shiftProgress = drivetrain.shiftProgress,
            shiftSerial = drivetrain.shiftSerial,
            shiftDirection = shiftDirection,
            limiterPulse = drivetrain.limiterPulse,
            backfireTriggered = drivetrain.backfireTriggered,
            tractionLimitActive = drivetrain.tractionLimitActive,
            tractionLimitPulse = drivetrain.tractionLimitPulse,
        )
        // Debug-only listening mode is controlled by ADB and mutes continuous/limiter events in
        // native FMOD while preserving backfire instances for audibility measurements.
        audioEngine.setBackfireOnly(backfireOnly.get() || DebugTelemetry.backfireOnly())
        audioEngine.update(
            EngineAudioFrame(
                simulationFrameId = simulationFrameId,
                rpm = drivetrain.rpm,
                throttle = drivetrain.audioThrottle,
                rawSpeedKmh = drivetrain.realOrDocumentedRawSpeedKmh,
                presentationSpeedKmh = drivetrain.presentationSpeedKmh,
                presentationAccelerationKmhPerSecond = drivetrain.presentationAccelerationKmhPerSecond,
                brake = drivetrain.smoothedBrake,
                clutch = drivetrain.clutch,
                transmissionPosition = transmission.position.ordinal,
                gear = drivetrain.gear,
                isShifting = drivetrain.isShifting,
                shiftProgress = drivetrain.shiftProgress,
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = shiftDirection,
                limiterPulse = drivetrain.limiterPulse,
                backfireTriggered = drivetrain.backfireTriggered,
                backfireSampleIndex = drivetrain.backfireSampleIndex,
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
        if (uiActive.get() && frameTimestampNanos >= nextUiSnapshotNanos) {
            latest = DriveSnapshot(
                drivetrain = drivetrain,
                inputSourcePrimary = sourceUi.primaryLabel,
                inputSourceSecondary = sourceUi.secondaryLabel,
                inputSourceIsRealPedals = sourceUi.isRealPedals,
                simulatedPedalsLatched = simulatedPedalsLatched.get(),
                inputSourceFaded = sourceUi.faded,
                throttle = input.throttle,
                brake = input.brake,
                simulatedRegen = simulatedRegen.get(),
                transmissionPosition = transmission.position,
                engineSoundEnabled = audioEngine.isAudioActive(),
                audioMuted = audioMuted.get(),
                selectedCarId = selected.id,
                selectedCarName = selected.displayName,
                selectedCarPreviewAsset = selected.previewAssetName,
                selectedCarIndex = installedProfiles().indexOf(selected),
                availableCarCount = installedProfiles().size,
                soundPerspective = selectedPerspective.get(),
                autoblipEnabled = autoblipEnabled.get(),
                transmissionLockedToVehicle = transmission.lockedToVehicle,
                carAudioReady = audioEngine.loadedBankProfileId() == selected.id,
                userMessage = userMessage,
            )
            nextUiSnapshotNanos = frameTimestampNanos + UI_SNAPSHOT_PERIOD_NANOS
        }
        handleAudioLoadFailures()
    }

    /**
     * The debug scenario must never modify normal selections or saved preferences. It is only an
     * ADB-driven input source used to make repeated bank audits reproducible on the same APK.
     */
    private fun applyDebugScenario(scenario: DebugScenarioOverride) {
        if (activeDebugScenarioId != scenario.scenarioId) {
            // The ADB runner is allowed to change in-memory runtime selection for a repeatable
            // audit, but it must leave the driver's saved car and listener choice untouched.
            debugScenarioBaseline = DebugScenarioBaseline(
                profile = selectedProfile.get(),
                perspective = selectedPerspective.get(),
            )
            activeDebugScenarioId = scenario.scenarioId
        }
        val requestedProfile = FmodBankProfiles.find(scenario.profileId)
            ?.takeIf(bankResolver::isInstalled)
        if (requestedProfile != null && requestedProfile.id != selectedProfile.get().id) {
            synchronized(lifecycleLock) {
                if (requestedProfile.id != selectedProfile.get().id) {
                    selectedProfile.set(requestedProfile)
                    audioMixGains.set(audioMixGainRepository.load(requestedProfile))
                    audioEngine.setCategoryGains(audioMixGains.get())
                    loadPhysics(requestedProfile)
                    simulation.reset()
                    selectedPerspective.set(EngineSoundPerspective.CABIN)
                    audioEngine.setSoundProgram(requestedProfile, EngineSoundPerspective.CABIN)
                    consumedDebugScenarioShiftSerial = 0L
                }
            }
        }
        val requestedPerspective = EngineSoundPerspective.entries.getOrNull(scenario.perspectiveOrdinal)
            ?: EngineSoundPerspective.CABIN
        if (requestedPerspective != selectedPerspective.get()) {
            selectedPerspective.set(requestedPerspective)
            audioEngine.setSoundProgram(selectedProfile.get(), requestedPerspective)
        }
        if (scenario.forceAuthoredBankEffects) {
            // The modded-bank audit must exercise authored gear/backfire events rather than the
            // driver's persistent replacement samples. This is debug-scenario-only and the
            // saved per-car controls are restored as soon as the scenario finishes.
            audioEngine.setBackfireAudioEnabled(true)
            audioEngine.setBackfireUseOriginal(true)
            audioEngine.setShiftSoundEnabled(true)
            audioEngine.setShiftSoundOverride(false)
            audioEngine.setTransmissionAudioEnabled(true)
            audioEngine.setTurboAudioEnabled(true)
        }
    }

    private fun restoreDebugScenarioIfNeeded() {
        val baseline = debugScenarioBaseline ?: return
        debugScenarioBaseline = null
        activeDebugScenarioId = 0L
        consumedDebugScenarioShiftSerial = 0L

        synchronized(lifecycleLock) {
            if (baseline.profile.id != selectedProfile.get().id) {
                selectedProfile.set(baseline.profile)
                audioMixGains.set(audioMixGainRepository.load(baseline.profile))
                audioEngine.setCategoryGains(audioMixGains.get())
                loadPhysics(baseline.profile)
                simulation.reset()
            }
            if (baseline.perspective != selectedPerspective.get()) {
                selectedPerspective.set(baseline.perspective)
            }
            audioEngine.setSoundProgram(selectedProfile.get(), selectedPerspective.get())
            val restoredModes = carEffectModesRepository.load(selectedProfile.get())
            carEffectModes.set(restoredModes)
            audioEngine.setBackfireAudioEnabled(restoredModes.popsAndBangsEnabled)
            audioEngine.setBackfireUseOriginal(restoredModes.popsAndBangsOriginal)
            audioEngine.setShiftSoundEnabled(restoredModes.shiftSoundsEnabled)
            audioEngine.setShiftSoundOverride(!restoredModes.shiftSoundsOriginal)
            audioEngine.setTransmissionAudioEnabled(restoredModes.transmissionEnabled)
            audioEngine.setTurboAudioEnabled(restoredModes.turboEnabled)
        }
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
        const val UI_SNAPSHOT_PERIOD_NANOS = 16_666_667L
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
