package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioMixModeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.AppMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.AudioFocusEvent
import com.gabrielpc.enginesoundsimulator.audio.CarMasterVolumeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixRepository
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.audio.LayerOutputMeter
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
    val coastLayerMixEnabled: Boolean = true,
    val legacyThrottleMixEnabled: Boolean = false,
    val appMasterVolume: Double = AppMasterVolumeRepository.DEFAULT,
    val appMuted: Boolean = false,
    val carMasterVolume: Double = CarMasterVolumeRepository.DEFAULT,
    val transmissionLockedToVehicle: Boolean = false,
    val carAudioReady: Boolean = false,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(context: Context) {
    private val tuningRepository = TuningRepository(context.applicationContext)
    private val selectedCarRepository = SelectedCarRepository(context.applicationContext)
    private val layerMixRepository = LayerMixRepository(context.applicationContext)
    private val appMasterVolumeRepository = AppMasterVolumeRepository(context.applicationContext)
    private val carMasterVolumeRepository = CarMasterVolumeRepository(context.applicationContext)
    private val audioMixModeRepository = AudioMixModeRepository(context.applicationContext)
    private val selectedSampleProfile = AtomicReference(selectedCarRepository.load())
    private val layerMixControls = AtomicReference(layerMixRepository.load(selectedCarRepository.load()))
    private val coastLayerMixEnabled = AtomicBoolean(audioMixModeRepository.isCoastLayerMixEnabled())
    private val tuningConfig = AtomicReference(tuningRepository.load())
    private val appMasterVolume = AtomicReference(appMasterVolumeRepository.load())
    private val appMasterVolumeBeforeMute = AtomicReference<Double?>(null)
    private val carMasterVolume = AtomicReference(carMasterVolumeRepository.load(selectedCarRepository.load().id))
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile(selectedSampleProfile.get())
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
        engineSoundEnabled = true,
        tuning = appliedTuning,
        selectedCarId = selectedSampleProfile.get().id,
        selectedCarName = selectedSampleProfile.get().displayName,
        selectedCarPreviewAsset = selectedSampleProfile.get().previewAssetName,
        selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedSampleProfile.get()),
        availableCarCount = EngineSampleProfiles.all.size,
        coastLayerMixEnabled = coastLayerMixEnabled.get(),
        legacyThrottleMixEnabled = !coastLayerMixEnabled.get(),
        appMasterVolume = appMasterVolume.get(),
        carMasterVolume = carMasterVolume.get(),
    )

    init {
        audioEngine.setCoastLayerMixEnabled(coastLayerMixEnabled.get())
        audioEngine.setFocusChangeListener(::handleAudioFocusChange)
        audioEngine.setSampleProfile(selectedSampleProfile.get())
        simulation.engageAtIdle()
    }

    fun isRunning(): Boolean = running.get()

    fun setUiActive(active: Boolean) {
        uiActive.set(active)
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        val ignitionActive = simulation.isEngineEngagedForUi()
        if (!uiActive.get()) {
            return base.copy(engineSoundEnabled = ignitionActive)
        }

        val selectedId = selectedSampleProfile.get().id
        return base.copy(
            engineSoundEnabled = ignitionActive,
            layerMixTracks = buildLayerMixTracks(
                selectedSampleProfile.get(),
                layerMixControls.get(),
                audioEngine.layerOutputMeters(),
                coastLayerMixEnabled.get(),
            ),
            carAudioReady = audioEngine.loadedSampleProfileId() == selectedId,
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
                audioEngine.setSampleProfile(selectedSampleProfile.get())
                audioEngine.start()
                thread.start()
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

    fun resetTuning() {
        val clean = tuningRepository.reset().withSampleProfile(selectedSampleProfile.get())
        tuningConfig.set(clean)
        tuningRepository.save(clean)
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

    fun setCoastLayerMixEnabled(enabled: Boolean) {
        audioMixModeRepository.setCoastLayerMixEnabled(enabled)
        coastLayerMixEnabled.set(enabled)
        audioEngine.setCoastLayerMixEnabled(enabled)
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

    fun resetAllCarMasterVolumes() {
        carMasterVolumeRepository.resetAll()
        carMasterVolume.set(carMasterVolumeRepository.load(selectedSampleProfile.get().id))
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
            layerMixControls.set(layerMixRepository.load(selected))
            carMasterVolume.set(carMasterVolumeRepository.load(selected.id))
            selectedCarRepository.save(selected)
            val tuning = tuningConfig.get().withSampleProfile(selected)
            tuningConfig.set(tuning)
            tuningRepository.save(tuning)
            profile = tuning.toEngineProfile(selected)
            simulation.updateProfile(profile)
            if (keepEngineRunning) {
                simulation.engageAtIdle()
            }
            audioEngine.setSampleProfile(selected)
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

    fun toggleSound() {
        synchronized(lifecycleLock) {
            if (!simulation.isIgnitionActive()) {
                simulation.startIgnition()
                ensureAudioEngineRunning(force = true)
            } else if (!simulation.isShutdownPending()) {
                simulation.requestShutdown()
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
            audioEngine.setSampleProfile(selectedSampleProfile.get())
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
        ensureAudioEngineRunning()
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
        val transmissionControl = resolveTransmissionControl(
            mode = mode,
            telemetry = telemetry,
            manualPosition = transmissionPosition.get(),
        )
        if (transmissionControl.lockedToVehicle) {
            transmissionPosition.set(transmissionControl.position)
        }

        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                externalSpeedKmh = input.externalSpeedKmh,
                // Use the resolved source, not just the selected mode, for coast/regen behavior.
                simulateCoastRegen = input.usesSimulatedPedals,
                transmissionPosition = transmissionControl.position,
            ),
            dt,
        )
        val audioEnabled = simulation.isEngineAudioAudible()
        val startupThrottle = if (simulation.ignition == EngineIgnitionState.STARTING) {
            (drivetrain.rpm / profile.redlineRpm.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) * 0.9
        } else {
            drivetrain.smoothedThrottle
        }
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = startupThrottle,
                enabled = audioEnabled,
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = when (drivetrain.shiftDirection) {
                    ShiftDirection.UP -> 1
                    ShiftDirection.DOWN -> -1
                    ShiftDirection.NONE -> 0
                },
                tuning = effectiveAudioTuning(tuning, simulation.shutdownAudioGain()),
                layerMix = layerMixControls.get(),
                coastLayerMixEnabled = coastLayerMixEnabled.get(),
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
                coastLayerMixEnabled = coastLayerMixEnabled.get(),
                legacyThrottleMixEnabled = !coastLayerMixEnabled.get(),
                appMasterVolume = appMasterVolume.get(),
                appMuted = appMasterVolumeBeforeMute.get() != null,
                carMasterVolume = carMasterVolume.get(),
                transmissionLockedToVehicle = transmissionControl.lockedToVehicle,
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
    coastMixEnabled: Boolean,
): List<LayerMixTrackState> {
    return profile.mixerTrackOrder().mapNotNull { (trackId, sortGroup) ->
        val control = controls[trackId] ?: LayerMixControl.DEFAULT
        val layer = profile.layers.firstOrNull { it.id == trackId }
        val effect = profile.effects.firstOrNull { it.id == trackId }
        when {
            coastMixEnabled && layer?.role == SampleLayerRole.LOAD -> null
            layer != null -> LayerMixTrackState(
                id = trackId,
                displayName = layer.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels.firstOrNull { it.id == trackId }?.outputLevel ?: 0.0,
                isEffect = false,
                showVolumeSlider = layer.role != SampleLayerRole.COAST && layer.role != SampleLayerRole.LOAD,
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
            throttle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0),
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
