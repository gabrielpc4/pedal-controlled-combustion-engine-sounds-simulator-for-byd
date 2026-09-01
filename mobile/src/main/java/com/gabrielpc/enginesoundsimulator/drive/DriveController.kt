package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores
import com.gabrielpc.enginesoundsimulator.audio.AudioMixModeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioFocusEvent
import com.gabrielpc.enginesoundsimulator.audio.CarEffectGainRepository
import com.gabrielpc.enginesoundsimulator.audio.CarEffectModeRepository
import com.gabrielpc.enginesoundsimulator.audio.CarMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspectiveRepository
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixRepository
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.audio.LayerOutputMeter
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSource
import com.gabrielpc.enginesoundsimulator.audio.PrimaryEngineLayerSourceRepository
import com.gabrielpc.enginesoundsimulator.audio.ProgramLayerGains
import com.gabrielpc.enginesoundsimulator.audio.mixerDisplayName
import com.gabrielpc.enginesoundsimulator.audio.mixerTrackOrder
import com.gabrielpc.enginesoundsimulator.audio.SampleLayerRole
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineIgnitionState
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.resolveTransmissionControl
import com.gabrielpc.enginesoundsimulator.telemetry.transmissionFollowsVehicle
import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
import com.gabrielpc.enginesoundsimulator.tuning.withSampleProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val primaryLabel: String, val secondaryLabel: String = "PEDALS") {
    RealPedals("REAL"),
    SimulatedPedals("SIMULATED"),
    ;

    val displayName: String
        get() = "$primaryLabel $secondaryLabel"
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
    val layerMixTracks: List<LayerMixTrackState> = emptyList(),
    val programLayerGains: ProgramLayerGains = ProgramLayerGains(),
    val loadOnlyProgram: Boolean = true,
    val primaryLayerSource: PrimaryEngineLayerSource = PrimaryEngineLayerSource.LOAD,
    val canUseCoastAsPrimary: Boolean = false,
    val soundPerspective: EngineSoundPerspective = EngineSoundPerspective.CABIN,
    val hasExteriorProgram: Boolean = false,
    val appMasterVolume: Double = AppMasterVolumeRepository.DEFAULT,
    val appMuted: Boolean = false,
    val carMasterVolume: Double = CarMasterVolumeRepository.DEFAULT,
    val transmissionLockedToVehicle: Boolean = false,
    val carAudioReady: Boolean = false,
    val engineStartLoading: Boolean = false,
    val popsAndBangsEnabled: Boolean = false,
    val popsAndBangsGain: Double = EngineAudioFrame.DEFAULT_POPS_AND_BANGS_GAIN,
    val sharedShiftSoundsEnabled: Boolean = false,
    val sharedShiftSoundsGain: Double = EngineAudioFrame.DEFAULT_SHARED_SHIFT_SOUNDS_GAIN,
    val transmissionEnabled: Boolean = true,
    val transmissionGain: Double = EngineAudioFrame.DEFAULT_TRANSMISSION_GAIN,
    val hasTurboSounds: Boolean = false,
    val turboSoundsEnabled: Boolean = true,
    val turboSoundsGain: Double = EngineAudioFrame.DEFAULT_TURBO_SOUNDS_GAIN,
    val loadResponsiveRpmEnabled: Boolean = false,
    val throttleRpmBumpEnabled: Boolean = false,
    val simulatedCoastRegenStrength: Double = SimulatedPedalTestRepository.DEFAULT_COAST_REGEN_STRENGTH,
    val simulatedUphillDragGrade: Double = SimulatedPedalTestRepository.DEFAULT_UPHILL_DRAG_GRADE,
    val manualShiftModeEnabled: Boolean = false,
    val userMessage: UserVisibleMessage? = null,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(context: Context) {
    private val appContext = context.applicationContext
    private val tuningRepository = TuningRepository(appContext)
    private val selectedCarRepository = SelectedCarRepository(appContext)
    private val layerMixRepository = LayerMixRepository(appContext)
    private val appMasterVolumeRepository = AppMasterVolumeRepository(appContext)
    private val carMasterVolumeRepository = CarMasterVolumeRepository(appContext)
    private val carEffectGainRepository = CarEffectGainRepository(appContext)
    private val carEffectModeRepository = CarEffectModeRepository(appContext)
    private val audioMixModeRepository = AudioMixModeRepository(appContext)
    private val driveBehaviorRepository = DriveBehaviorRepository(appContext)
    private val simulatedPedalTestRepository = SimulatedPedalTestRepository(appContext)
    private val selectedSampleProfile = AtomicReference(selectedCarRepository.load())
    private val soundPerspectiveRepository = EngineSoundPerspectiveRepository(appContext)
    private val soundPerspective = AtomicReference(soundPerspectiveRepository.load(selectedSampleProfile.get()))
    private val primaryLayerSourceRepository = PrimaryEngineLayerSourceRepository(appContext)
    private val primaryLayerSource = AtomicReference(
        primaryLayerSourceRepository.load(selectedSampleProfile.get(), soundPerspective.get()),
    )
    private val layerMixControls = AtomicReference(layerMixRepository.load(selectedCarRepository.load()))
    private val programLayerGains = AtomicReference(
        layerMixRepository.loadProgramLayerGains(selectedCarRepository.load(), soundPerspective.get()),
    )
    private val popsAndBangsEnabled = AtomicBoolean(
        carEffectModeRepository.popsAndBangsEnabled(selectedCarRepository.load().id),
    )
    private val popsAndBangsGain = AtomicReference(
        carEffectGainRepository.popsAndBangsGain(selectedCarRepository.load().id),
    )
    private val sharedShiftSoundsEnabled = AtomicBoolean(
        carEffectModeRepository.sharedShiftSoundsEnabled(selectedCarRepository.load().id),
    )
    private val sharedShiftSoundsGain = AtomicReference(
        carEffectGainRepository.sharedShiftSoundsGain(selectedCarRepository.load().id),
    )
    private val transmissionEnabled = AtomicBoolean(
        carEffectModeRepository.transmissionEnabled(selectedCarRepository.load().id),
    )
    private val transmissionGain = AtomicReference(
        carEffectGainRepository.transmissionGain(selectedCarRepository.load().id),
    )
    private val turboSoundsEnabled = AtomicBoolean(
        carEffectModeRepository.turboSoundsEnabled(selectedCarRepository.load().id),
    )
    private val turboSoundsGain = AtomicReference(
        carEffectGainRepository.turboSoundsGain(selectedCarRepository.load().id),
    )
    private val loadResponsiveRpmEnabled = AtomicBoolean(
        driveBehaviorRepository.loadResponsiveRpmEnabled(),
    )
    private val throttleRpmBumpEnabled = AtomicBoolean(
        driveBehaviorRepository.throttleRpmBumpEnabled(),
    )
    private val simulatedCoastRegenStrength = AtomicReference(simulatedPedalTestRepository.coastRegenStrength())
    private val simulatedUphillDragGrade = AtomicReference(simulatedPedalTestRepository.uphillDragGrade())
    private val manualShiftModeEnabled = AtomicBoolean(audioMixModeRepository.isManualShiftModeEnabled())
    private val tuningConfig = AtomicReference(tuningRepository.load())
    private val appMasterVolume = AtomicReference(appMasterVolumeRepository.load())
    private val appMasterVolumeBeforeMute = AtomicReference<Double?>(null)
    private val carMasterVolume = AtomicReference(carMasterVolumeRepository.load(selectedCarRepository.load().id))
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile(selectedSampleProfile.get())
    private val simulation = EngineSimulation(profile)
    private val vehicleReader = BydSpeedReader(appContext)
    private val audioEngine = EngineAudioEngine(appContext)
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
    /** First engine start in this app process waits for sample decode before ignition/rev logic. */
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
        selectedCarId = selectedSampleProfile.get().id,
        selectedCarName = selectedSampleProfile.get().displayName,
        selectedCarPreviewAsset = selectedSampleProfile.get().previewAssetName,
        selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedSampleProfile.get()),
        availableCarCount = EngineSampleProfiles.all.size,
        loadOnlyProgram = true,
        primaryLayerSource = primaryLayerSource.get(),
        canUseCoastAsPrimary = selectedSampleProfile.get().supportsPrimaryLayerSource(
            PrimaryEngineLayerSource.COAST,
            soundPerspective.get(),
        ),
        soundPerspective = soundPerspective.get(),
        hasExteriorProgram = selectedSampleProfile.get().hasExteriorProgram,
        popsAndBangsEnabled = popsAndBangsEnabled.get(),
        popsAndBangsGain = popsAndBangsGain.get(),
        sharedShiftSoundsEnabled = sharedShiftSoundsEnabled.get(),
        sharedShiftSoundsGain = sharedShiftSoundsGain.get(),
        transmissionEnabled = transmissionEnabled.get(),
        transmissionGain = transmissionGain.get(),
        hasTurboSounds = selectedSampleProfile.get().hasTurboSounds(soundPerspective.get()),
        turboSoundsEnabled = turboSoundsEnabled.get(),
        turboSoundsGain = turboSoundsGain.get(),
        loadResponsiveRpmEnabled = loadResponsiveRpmEnabled.get(),
        throttleRpmBumpEnabled = throttleRpmBumpEnabled.get(),
        programLayerGains = programLayerGains.get(),
        simulatedCoastRegenStrength = simulatedCoastRegenStrength.get(),
        simulatedUphillDragGrade = simulatedUphillDragGrade.get(),
        manualShiftModeEnabled = manualShiftModeEnabled.get(),
        appMasterVolume = appMasterVolume.get(),
        carMasterVolume = carMasterVolume.get(),
    )

    init {
        audioEngine.setFocusChangeListener(::handleAudioFocusChange)
        audioEngine.setSoundProgram(selectedSampleProfile.get(), soundPerspective.get(), primaryLayerSource.get())
        simulation.manualShiftEnabled = manualShiftModeEnabled.get()
        simulation.loadResponsiveRpmEnabled = loadResponsiveRpmEnabled.get()
        simulation.throttleRpmBumpEnabled = throttleRpmBumpEnabled.get()
    }

    fun isRunning(): Boolean = running.get()

    fun setUiActive(active: Boolean) {
        uiActive.set(active)
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        val ignitionActive = simulation.isEngineEngagedForUi()
        if (!uiActive.get()) {
            return base.copy(
                engineSoundEnabled = ignitionActive,
                popsAndBangsEnabled = popsAndBangsEnabled.get(),
                popsAndBangsGain = popsAndBangsGain.get(),
                sharedShiftSoundsEnabled = sharedShiftSoundsEnabled.get(),
                sharedShiftSoundsGain = sharedShiftSoundsGain.get(),
                transmissionEnabled = transmissionEnabled.get(),
                transmissionGain = transmissionGain.get(),
                turboSoundsEnabled = turboSoundsEnabled.get(),
                turboSoundsGain = turboSoundsGain.get(),
                loadResponsiveRpmEnabled = loadResponsiveRpmEnabled.get(),
                throttleRpmBumpEnabled = throttleRpmBumpEnabled.get(),
                programLayerGains = programLayerGains.get(),
                simulatedCoastRegenStrength = simulatedCoastRegenStrength.get(),
                simulatedUphillDragGrade = simulatedUphillDragGrade.get(),
                manualShiftModeEnabled = manualShiftModeEnabled.get(),
            )
        }

        val selectedId = selectedSampleProfile.get().id
        return base.copy(
            engineSoundEnabled = ignitionActive,
            popsAndBangsEnabled = popsAndBangsEnabled.get(),
            popsAndBangsGain = popsAndBangsGain.get(),
            sharedShiftSoundsEnabled = sharedShiftSoundsEnabled.get(),
            sharedShiftSoundsGain = sharedShiftSoundsGain.get(),
            transmissionEnabled = transmissionEnabled.get(),
            transmissionGain = transmissionGain.get(),
            turboSoundsEnabled = turboSoundsEnabled.get(),
            turboSoundsGain = turboSoundsGain.get(),
            loadResponsiveRpmEnabled = loadResponsiveRpmEnabled.get(),
            throttleRpmBumpEnabled = throttleRpmBumpEnabled.get(),
            programLayerGains = programLayerGains.get(),
            simulatedCoastRegenStrength = simulatedCoastRegenStrength.get(),
            simulatedUphillDragGrade = simulatedUphillDragGrade.get(),
            manualShiftModeEnabled = manualShiftModeEnabled.get(),
            layerMixTracks = buildLayerMixTracks(
                selectedSampleProfile.get(),
                layerMixControls.get(),
                audioEngine.layerOutputMeters(),
                loadOnlyProgram = true,
                primaryLayerSource = primaryLayerSource.get(),
                perspective = soundPerspective.get(),
            ),
            carAudioReady = audioEngine.loadedSampleProfileId() == selectedId,
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
        tuningConfig.set(clean)
        tuningRepository.save(clean)
    }

    fun resetAllPreferences() {
        synchronized(lifecycleLock) {
            val keepEngineRunning = simulation.isEngineEngagedForUi()
            val allCleared = AppPreferenceStores.clearAll(appContext)
            val defaultProfile = EngineSampleProfiles.default
            val defaultPerspective = EngineSoundPerspective.CABIN
            val defaultSource = PrimaryEngineLayerSource.LOAD
            val defaultTuning = TuningConfig.DEFAULT.withSampleProfile(defaultProfile)

            selectedSampleProfile.set(defaultProfile)
            soundPerspective.set(defaultPerspective)
            primaryLayerSource.set(defaultSource)
            layerMixControls.set(layerMixRepository.load(defaultProfile))
            appMasterVolume.set(AppMasterVolumeRepository.DEFAULT)
            appMasterVolumeBeforeMute.set(null)
            carMasterVolume.set(CarMasterVolumeRepository.DEFAULT)
            popsAndBangsEnabled.set(false)
            popsAndBangsGain.set(EngineAudioFrame.DEFAULT_POPS_AND_BANGS_GAIN)
            sharedShiftSoundsEnabled.set(false)
            sharedShiftSoundsGain.set(EngineAudioFrame.DEFAULT_SHARED_SHIFT_SOUNDS_GAIN)
            transmissionEnabled.set(true)
            transmissionGain.set(EngineAudioFrame.DEFAULT_TRANSMISSION_GAIN)
            turboSoundsEnabled.set(true)
            turboSoundsGain.set(EngineAudioFrame.DEFAULT_TURBO_SOUNDS_GAIN)
            loadResponsiveRpmEnabled.set(false)
            throttleRpmBumpEnabled.set(false)
            simulatedCoastRegenStrength.set(SimulatedPedalTestRepository.DEFAULT_COAST_REGEN_STRENGTH)
            simulatedUphillDragGrade.set(SimulatedPedalTestRepository.DEFAULT_UPHILL_DRAG_GRADE)
            manualShiftModeEnabled.set(false)
            tuningConfig.set(defaultTuning)
            profile = defaultTuning.toEngineProfile(defaultProfile)
            simulation.updateProfile(profile)
            simulation.loadResponsiveRpmEnabled = false
            simulation.throttleRpmBumpEnabled = false
            simulation.manualShiftEnabled = false
            if (keepEngineRunning) {
                simulation.engageAtIdle()
            }
            audioEngine.setSoundProgram(defaultProfile, defaultPerspective, defaultSource)
            userVisibleMessage = if (allCleared) {
                null
            } else {
                UserVisibleMessage(
                    id = SystemClock.elapsedRealtime(),
                    title = "Some settings could not be reset",
                    detail = "Restart the app and try RESET again.",
                )
            }
        }
    }

    fun selectPreviousCar() = selectAdjacentCar(-1)

    fun selectNextCar() = selectAdjacentCar(1)

    fun selectCar(profileId: String) {
        val selected = EngineSampleProfiles.find(profileId)
        applySelectedCar(selected)
    }

    fun setLayerMixVolume(trackId: String, volume: Double) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setVolume(profile, trackId, volume))
    }

    fun setLayerMixMuted(trackId: String, muted: Boolean) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setMuted(profile, trackId, muted))
    }

    fun setLayerMixSolo(trackId: String, solo: Boolean) {
        val profile = selectedSampleProfile.get()
        layerMixControls.set(layerMixRepository.setSolo(profile, trackId, solo))
    }

    fun setLoadProgramLayerGain(gain: Double) {
        setProgramLayerGain(SampleLayerRole.LOAD, gain)
    }

    fun setCoastProgramLayerGain(gain: Double) {
        setProgramLayerGain(SampleLayerRole.COAST, gain)
    }

    private fun setProgramLayerGain(role: SampleLayerRole, gain: Double) {
        programLayerGains.set(
            layerMixRepository.setProgramLayerGain(
                selectedSampleProfile.get(),
                soundPerspective.get(),
                role,
                gain,
            ),
        )
    }

    fun setPrimaryLayerSource(source: PrimaryEngineLayerSource) {
        val profile = selectedSampleProfile.get()
        val perspective = soundPerspective.get()
        val resolved = primaryLayerSourceRepository.save(profile, perspective, source)
        if (primaryLayerSource.getAndSet(resolved) != resolved) {
            audioEngine.setPrimaryLayerSource(resolved)
        }
    }

    fun setSoundPerspective(perspective: EngineSoundPerspective) {
        val profile = selectedSampleProfile.get()
        val resolvedPerspective = soundPerspectiveRepository.save(profile, perspective)
        val resolvedSource = primaryLayerSourceRepository.load(profile, resolvedPerspective)
        soundPerspective.set(resolvedPerspective)
        programLayerGains.set(layerMixRepository.loadProgramLayerGains(profile, resolvedPerspective))
        primaryLayerSource.set(resolvedSource)
        audioEngine.setSoundProgram(profile, resolvedPerspective, resolvedSource)
    }

    fun setPopsAndBangsEnabled(enabled: Boolean) {
        popsAndBangsEnabled.set(
            carEffectModeRepository.savePopsAndBangsEnabled(selectedSampleProfile.get().id, enabled),
        )
    }

    fun setPopsAndBangsGain(gain: Double) {
        val clamped = gain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        popsAndBangsGain.set(
            carEffectGainRepository.savePopsAndBangsGain(selectedSampleProfile.get().id, clamped),
        )
    }

    fun togglePopsAndBangs() {
        setPopsAndBangsEnabled(!popsAndBangsEnabled.get())
    }

    fun setSharedShiftSoundsEnabled(enabled: Boolean) {
        sharedShiftSoundsEnabled.set(
            carEffectModeRepository.saveSharedShiftSoundsEnabled(selectedSampleProfile.get().id, enabled),
        )
    }

    fun setSharedShiftSoundsGain(gain: Double) {
        val clamped = gain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        sharedShiftSoundsGain.set(
            carEffectGainRepository.saveSharedShiftSoundsGain(selectedSampleProfile.get().id, clamped),
        )
    }

    fun toggleSharedShiftSounds() {
        setSharedShiftSoundsEnabled(!sharedShiftSoundsEnabled.get())
    }

    fun setTransmissionEnabled(enabled: Boolean) {
        transmissionEnabled.set(
            carEffectModeRepository.saveTransmissionEnabled(selectedSampleProfile.get().id, enabled),
        )
    }

    fun setTransmissionGain(gain: Double) {
        val clamped = gain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        transmissionGain.set(
            carEffectGainRepository.saveTransmissionGain(selectedSampleProfile.get().id, clamped),
        )
    }

    fun toggleTransmission() {
        setTransmissionEnabled(!transmissionEnabled.get())
    }

    fun setTurboSoundsEnabled(enabled: Boolean) {
        turboSoundsEnabled.set(
            carEffectModeRepository.saveTurboSoundsEnabled(selectedSampleProfile.get().id, enabled),
        )
    }

    fun setTurboSoundsGain(gain: Double) {
        val clamped = gain.coerceIn(EngineAudioFrame.MIN_TURBO_SOUNDS_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
        turboSoundsGain.set(
            carEffectGainRepository.saveTurboSoundsGain(selectedSampleProfile.get().id, clamped),
        )
    }

    fun toggleTurboSounds() {
        setTurboSoundsEnabled(!turboSoundsEnabled.get())
    }

    fun setLoadResponsiveRpmEnabled(enabled: Boolean) {
        val saved = driveBehaviorRepository.saveLoadResponsiveRpmEnabled(enabled)
        loadResponsiveRpmEnabled.set(saved)
        simulation.loadResponsiveRpmEnabled = saved
    }

    fun setThrottleRpmBumpEnabled(enabled: Boolean) {
        val saved = driveBehaviorRepository.saveThrottleRpmBumpEnabled(enabled)
        throttleRpmBumpEnabled.set(saved)
        simulation.throttleRpmBumpEnabled = saved
    }

    fun setSimulatedCoastRegenStrength(strength: Double) {
        simulatedCoastRegenStrength.set(simulatedPedalTestRepository.saveCoastRegenStrength(strength))
    }

    fun setSimulatedUphillDragGrade(grade: Double) {
        simulatedUphillDragGrade.set(simulatedPedalTestRepository.saveUphillDragGrade(grade))
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
        val profileId = selectedSampleProfile.get().id
        carMasterVolume.set(carMasterVolumeRepository.save(profileId, volume))
    }

    private fun selectAdjacentCar(offset: Int) {
        val previous = selectedSampleProfile.get()
        val selected = EngineSampleProfiles.adjacent(previous.id, offset)
        if (selected.id == previous.id) return
        applySelectedCar(selected)
    }

    private fun applySelectedCar(selected: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile) {
        synchronized(lifecycleLock) {
            val keepEngineRunning = simulation.isEngineEngagedForUi()

            selectedSampleProfile.set(selected)
            soundPerspective.set(soundPerspectiveRepository.load(selected))
            primaryLayerSource.set(primaryLayerSourceRepository.load(selected, soundPerspective.get()))
            layerMixControls.set(layerMixRepository.load(selected))
            programLayerGains.set(layerMixRepository.loadProgramLayerGains(selected, soundPerspective.get()))
            carMasterVolume.set(carMasterVolumeRepository.load(selected.id))
            popsAndBangsGain.set(carEffectGainRepository.popsAndBangsGain(selected.id))
            sharedShiftSoundsGain.set(carEffectGainRepository.sharedShiftSoundsGain(selected.id))
            popsAndBangsEnabled.set(carEffectModeRepository.popsAndBangsEnabled(selected.id))
            sharedShiftSoundsEnabled.set(carEffectModeRepository.sharedShiftSoundsEnabled(selected.id))
            transmissionEnabled.set(carEffectModeRepository.transmissionEnabled(selected.id))
            transmissionGain.set(carEffectGainRepository.transmissionGain(selected.id))
            turboSoundsEnabled.set(carEffectModeRepository.turboSoundsEnabled(selected.id))
            turboSoundsGain.set(carEffectGainRepository.turboSoundsGain(selected.id))
            selectedCarRepository.save(selected)
            val tuning = tuningConfig.get().withSampleProfile(selected)
            tuningConfig.set(tuning)
            tuningRepository.save(tuning)
            profile = tuning.toEngineProfile(selected)
            simulation.updateProfile(profile)
            if (keepEngineRunning) {
                simulation.engageAtIdle()
            }
            audioEngine.setSoundProgram(selected, soundPerspective.get(), primaryLayerSource.get())
        }
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
        val telemetry = vehicleReader.snapshot()
        if (telemetry.transmissionFollowsVehicle(selectedInputMode.get(), telemetry)) {
            return
        }
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

            if (isSelectedCarAudioLoaded()) {
                if (fromStartStopButton) {
                    engineStartLoading.set(true)
                }
                beginPostLoadEngineStartDelay()
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

    private fun beginPostLoadEngineStartDelay() {
        awaitingFirstAudioLoad.set(false)
        completeDeferredEngineStart()
    }

    private fun completeDeferredEngineStart() {
        awaitingFirstAudioLoad.set(false)
        engineStartLoading.set(false)
        sessionFirstStartPending.set(false)
        engageEngineAtIdle(forceAudio = true)
    }

    private fun cancelPendingFirstAudioLoad() {
        awaitingFirstAudioLoad.set(false)
        engineStartLoading.set(false)
    }

    private fun isDeferringFirstSessionEngineStart(): Boolean {
        return awaitingFirstAudioLoad.get()
    }

    private fun isSelectedCarAudioLoaded(): Boolean {
        return audioEngine.loadedSampleProfileId() == selectedSampleProfile.get().id
    }

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
            audioEngine.setSoundProgram(
                selectedSampleProfile.get(),
                soundPerspective.get(),
                primaryLayerSource.get(),
            )
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
        val tuning = tuningConfig.get()
        if (tuning !== appliedTuning) {
            profile = tuning.toEngineProfile(selectedSampleProfile.get())
            simulation.updateProfile(profile)
            appliedTuning = tuning
        }
        val telemetry = vehicleReader.snapshot()
        val mode = selectedInputMode.get()
        val simulatedPedals = simulatedPedalInput.get()
        val input = resolveDriveInput(mode, telemetry, simulatedPedals.throttle, simulatedPedals.brake)
        maybeAutoStartEngineFromThrottle(input.throttle)
        if (awaitingFirstAudioLoad.get() && isSelectedCarAudioLoaded()) {
            synchronized(lifecycleLock) {
                if (awaitingFirstAudioLoad.get() && isSelectedCarAudioLoaded()) {
                    beginPostLoadEngineStartDelay()
                }
            }
        }
        handleAudioLoadFailures()
        if (simulation.isIgnitionActive()) {
            ensureAudioEngineRunning()
        } else if (audioEngine.isAudioActive() && !isDeferringFirstSessionEngineStart()) {
            audioEngine.stop()
        }
        val transmissionControl = resolveTransmissionControl(
            mode = mode,
            telemetry = telemetry,
            manualPosition = transmissionPosition.get(),
        )
        if (transmissionControl.lockedToVehicle) {
            transmissionPosition.set(transmissionControl.position)
        }
        simulation.manualShiftEnabled = manualShiftModeEnabled.get()

        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                externalSpeedKmh = input.externalSpeedKmh,
                // Use the resolved source, not just the selected mode, for coast/regen behavior.
                simulateCoastRegen = input.usesSimulatedPedals,
                transmissionPosition = transmissionControl.position,
                simulatedDriveForceScale = if (input.usesSimulatedPedals) {
                    SIMULATED_PEDALS_DRIVE_FORCE_SCALE
                } else {
                    1.0
                },
                simulatedCoastRegenScale = if (input.usesSimulatedPedals) {
                    SIMULATED_PEDALS_COAST_REGEN_SCALE * simulatedCoastRegenStrength.get()
                } else {
                    1.0
                },
                simulatedUphillDragGrade = if (input.usesSimulatedPedals) {
                    simulatedUphillDragGrade.get()
                } else {
                    0.0
                },
            ),
            dt,
        )
        val audioEnabled = simulation.ignition == EngineIgnitionState.STOPPING ||
            simulation.isEngineAudioAudible()
        val freeRev = transmissionControl.position != TransmissionPosition.DRIVE
        val audioThrottle = if (simulation.ignition == EngineIgnitionState.STARTING) {
            (drivetrain.rpm / profile.redlineRpm.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) * 0.9
        } else {
            drivetrain.audioThrottle
        }
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = audioThrottle,
                enabled = audioEnabled,
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = when (drivetrain.shiftDirection) {
                    ShiftDirection.UP -> 1
                    ShiftDirection.DOWN -> -1
                    ShiftDirection.NONE -> 0
                },
                isShifting = drivetrain.isShifting,
                tuning = effectiveAudioTuning(tuning, simulation.ignitionAudioGain()),
                layerMix = layerMixControls.get(),
                programLayerGains = programLayerGains.get(),
                loadOnlyProgram = true,
                primaryLayerSource = primaryLayerSource.get(),
                popsAndBangsEnabled = popsAndBangsEnabled.get(),
                popsAndBangsGain = popsAndBangsGain.get(),
                throttleLiftEffectsEnabled = !freeRev,
                turboSpoolAttackMultiplier = if (freeRev) {
                    FREE_REV_TURBO_ATTACK_MULTIPLIER
                } else {
                    1.0
                },
                sharedShiftSoundsEnabled = sharedShiftSoundsEnabled.get(),
                sharedShiftSoundsGain = sharedShiftSoundsGain.get(),
                transmissionEnabled = transmissionEnabled.get(),
                transmissionGain = transmissionGain.get(),
                turboSoundsEnabled = turboSoundsEnabled.get(),
                turboSoundsGain = turboSoundsGain.get(),
            ),
        )
        val selectedCar = selectedSampleProfile.get()
        val vehicleAvailable = telemetry.vehiclePedalsAvailable()
        val inputUi = resolveInputSourceUi(
            selectedMode = mode,
            vehicleAvailable = vehicleAvailable,
        )
        if (uiActive.get()) {
            latest = DriveSnapshot(
                drivetrain = drivetrain,
                inputSourcePrimary = inputUi.primaryLabel,
                inputSourceSecondary = inputUi.secondaryLabel,
                inputSourceIsRealPedals = inputUi.isRealPedals,
                inputSourceFaded = inputUi.faded,
                throttle = input.throttle,
                brake = input.brake,
                transmissionPosition = transmissionControl.position,
                engineSoundEnabled = simulation.isEngineEngagedForUi(),
                tuning = tuning,
                selectedCarId = selectedCar.id,
                selectedCarName = selectedCar.displayName,
                selectedCarPreviewAsset = selectedCar.previewAssetName,
                selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedCar),
                availableCarCount = EngineSampleProfiles.all.size,
                loadOnlyProgram = true,
                programLayerGains = programLayerGains.get(),
                primaryLayerSource = primaryLayerSource.get(),
                canUseCoastAsPrimary = selectedCar.supportsPrimaryLayerSource(
                    PrimaryEngineLayerSource.COAST,
                    soundPerspective.get(),
                ),
                soundPerspective = soundPerspective.get(),
                hasExteriorProgram = selectedCar.hasExteriorProgram,
                popsAndBangsEnabled = popsAndBangsEnabled.get(),
                popsAndBangsGain = popsAndBangsGain.get(),
                sharedShiftSoundsEnabled = sharedShiftSoundsEnabled.get(),
                sharedShiftSoundsGain = sharedShiftSoundsGain.get(),
                transmissionEnabled = transmissionEnabled.get(),
                transmissionGain = transmissionGain.get(),
                hasTurboSounds = selectedCar.hasTurboSounds(soundPerspective.get()),
                turboSoundsEnabled = turboSoundsEnabled.get(),
                turboSoundsGain = turboSoundsGain.get(),
                loadResponsiveRpmEnabled = loadResponsiveRpmEnabled.get(),
                throttleRpmBumpEnabled = throttleRpmBumpEnabled.get(),
                manualShiftModeEnabled = manualShiftModeEnabled.get(),
                appMasterVolume = appMasterVolume.get(),
                appMuted = appMasterVolumeBeforeMute.get() != null,
                carMasterVolume = carMasterVolume.get(),
                transmissionLockedToVehicle = transmissionControl.lockedToVehicle,
                carAudioReady = isSelectedCarAudioLoaded(),
                engineStartLoading = engineStartLoading.get(),
                userMessage = userVisibleMessage,
            )
        }
    }

    private fun handleAudioLoadFailures() {
        val failure = audioEngine.consumeLoadFailure() ?: return
        if (failure.profileId != selectedSampleProfile.get().id) {
            return
        }

        synchronized(lifecycleLock) {
            if (awaitingFirstAudioLoad.get()) {
                cancelPendingFirstAudioLoad()
            }
            userVisibleMessage = UserVisibleMessage(
                id = SystemClock.elapsedRealtime(),
                title = "Engine audio failed to load",
                detail = "${selectedSampleProfile.get().displayName}: ${failure.detail}",
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

    private fun effectiveAudioTuning(tuning: TuningConfig, shutdownGain: Double = 1.0) = tuning.audio.copy(
        masterGain = (
            (appMasterVolume.get() / AppMasterVolumeRepository.DEFAULT) *
                (carMasterVolume.get() * tuning.audio.masterGain / CarMasterVolumeRepository.DEFAULT) *
                shutdownGain.coerceIn(0.0, 1.0)
            ).coerceIn(CarMasterVolumeRepository.MIN, CarMasterVolumeRepository.MAX),
    )

    private data class SimulatedPedalInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val INTERRUPTED_IDLE_NANOS = 50_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val MASTER_VOLUME_STEP = 0.10
        const val INTERRUPTION_RESUME_VOLUME = 0.25
        const val AUDIO_RESTART_COOLDOWN_MS = 2_000L
        const val AUTO_START_THROTTLE_THRESHOLD = 0.10
        const val FREE_REV_TURBO_ATTACK_MULTIPLIER = 10.0
        const val SIMULATED_PEDALS_DRIVE_FORCE_SCALE = 1.0
        const val SIMULATED_PEDALS_COAST_REGEN_SCALE = 1.0
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

private fun buildLayerMixTracks(
    profile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile,
    controls: Map<String, LayerMixControl>,
    outputLevels: List<LayerOutputMeter>,
    loadOnlyProgram: Boolean,
    primaryLayerSource: PrimaryEngineLayerSource,
    perspective: EngineSoundPerspective,
): List<LayerMixTrackState> {
    val program = profile.program(perspective)
    return profile.mixerTrackOrder(perspective).mapNotNull { (trackId, sortGroup) ->
        val control = controls[trackId] ?: LayerMixControl.DEFAULT
        val layer = program.layers.firstOrNull { it.id == trackId }
        val effect = program.effects.firstOrNull { it.id == trackId }
        when {
            profile.appliesLoadOnlyProgram(loadOnlyProgram, perspective) &&
                primaryLayerSource == PrimaryEngineLayerSource.LOAD &&
                layer?.role == SampleLayerRole.COAST -> null
            profile.appliesLoadOnlyProgram(loadOnlyProgram, perspective) &&
                primaryLayerSource == PrimaryEngineLayerSource.COAST &&
                layer?.role == SampleLayerRole.LOAD -> null
            layer != null -> LayerMixTrackState(
                id = trackId,
                displayName = layer.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels.firstOrNull { it.id == trackId }?.outputLevel ?: 0.0,
                isEffect = false,
                showVolumeSlider = if (profile.appliesLoadOnlyProgram(loadOnlyProgram, perspective)) {
                    primaryLayerSource == PrimaryEngineLayerSource.FMOD_MIX ||
                        (layer.role != SampleLayerRole.COAST && layer.role != SampleLayerRole.LOAD)
                } else {
                    true
                },
                isLoadLayer = layer.role == SampleLayerRole.LOAD,
            )
            effect != null -> LayerMixTrackState(
                id = trackId,
                displayName = effect.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels.firstOrNull { it.id == trackId }?.outputLevel ?: 0.0,
                isEffect = true,
                showVolumeSlider = true,
            )
            else -> null
        }
    }
}

private fun TuningConfig.toEngineProfile(sampleProfile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile): EngineProfile {
    val engine = engine.sanitized()
    return EngineProfile(
        name = sampleProfile.displayName,
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
    val label: String,
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
            throttle = normalizeVehicleThrottlePercent(telemetry.accelerator.value!!),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid },
            label = InputMode.RealPedals.displayName,
            usesSimulatedPedals = false,
        )
    }

    return ResolvedDriveInput(
        throttle = simulatedPedalThrottle.coerceIn(0.0, 1.0),
        brake = simulatedPedalBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        label = InputMode.SimulatedPedals.displayName,
        usesSimulatedPedals = true,
    )
}

/**
 * The vehicle reports a calibrated pedal range ending at 99%, so preserve its full-pedal intent
 * for launch control and every other full-throttle rule.
 */
internal fun normalizeVehicleThrottlePercent(percent: Double): Double {
    return if (percent >= VEHICLE_FULL_THROTTLE_PERCENT) {
        1.0
    } else {
        (percent / 100.0).coerceIn(0.0, 1.0)
    }
}

private const val VEHICLE_FULL_THROTTLE_PERCENT = 99.0

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
