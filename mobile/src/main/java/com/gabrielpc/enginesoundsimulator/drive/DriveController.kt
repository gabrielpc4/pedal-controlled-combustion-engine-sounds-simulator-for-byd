package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioMixModeRepository
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
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

enum class InputMode(val displayName: String) {
    PREFER_BYD("BYD LIVE"),
    SIMULATOR("SIM"),
}

data class DriveSnapshot(
    val drivetrain: DrivetrainState,
    val inputSourceName: String,
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
    val carMasterVolume: Double = CarMasterVolumeRepository.DEFAULT,
    val transmissionLockedToVehicle: Boolean = false,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(context: Context) {
    private val tuningRepository = TuningRepository(context.applicationContext)
    private val selectedCarRepository = SelectedCarRepository(context.applicationContext)
    private val layerMixRepository = LayerMixRepository(context.applicationContext)
    private val carMasterVolumeRepository = CarMasterVolumeRepository(context.applicationContext)
    private val audioMixModeRepository = AudioMixModeRepository(context.applicationContext)
    private val selectedSampleProfile = AtomicReference(selectedCarRepository.load())
    private val layerMixControls = AtomicReference(layerMixRepository.load(selectedCarRepository.load()))
    private val coastLayerMixEnabled = AtomicBoolean(audioMixModeRepository.isCoastLayerMixEnabled())
    private val tuningConfig = AtomicReference(tuningRepository.load())
    private val carMasterVolume = AtomicReference(carMasterVolumeRepository.load(selectedCarRepository.load().id))
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile(selectedSampleProfile.get())
    private val simulation = EngineSimulation(profile)
    private val vehicleReader = BydSpeedReader(context.applicationContext)
    private val audioEngine = EngineAudioEngine(context.applicationContext)
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val manualInput = AtomicReference(ManualInput())
    private val selectedInputMode = AtomicReference(InputMode.PREFER_BYD)
    private val vehiclePreviewUntilElapsedMs = AtomicLong(0L)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val soundEnabled = AtomicBoolean(true)

    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputSourceName = "SIM",
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
        carMasterVolume = carMasterVolume.get(),
    )

    init {
        audioEngine.setCoastLayerMixEnabled(coastLayerMixEnabled.get())
        audioEngine.setSampleProfile(selectedSampleProfile.get())
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        return base.copy(
            layerMixTracks = buildLayerMixTracks(
                selectedSampleProfile.get(),
                layerMixControls.get(),
                audioEngine.layerOutputMeters(),
                coastLayerMixEnabled.get(),
            ),
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
                if (soundEnabled.get()) audioEngine.start()
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
            manualInput.set(ManualInput())
        }
    }

    fun setManualThrottle(value: Double) {
        manualInput.updateAndGet { it.copy(throttle = value.coerceIn(0.0, 1.0)) }
    }

    fun setManualBrake(value: Double) {
        manualInput.updateAndGet { it.copy(brake = value.coerceIn(0.0, 1.0)) }
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

    fun setCarMasterVolume(volume: Double) {
        val profileId = selectedSampleProfile.get().id
        carMasterVolume.set(carMasterVolumeRepository.save(profileId, volume))
    }

    fun decreaseCarMasterVolume() {
        setCarMasterVolume(carMasterVolume.get() - MASTER_VOLUME_STEP)
    }

    fun increaseCarMasterVolume() {
        setCarMasterVolume(carMasterVolume.get() + MASTER_VOLUME_STEP)
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
        selectedSampleProfile.set(selected)
        layerMixControls.set(layerMixRepository.load(selected))
        carMasterVolume.set(carMasterVolumeRepository.load(selected.id))
        selectedCarRepository.save(selected)
        val tuning = tuningConfig.get().withSampleProfile(selected)
        tuningConfig.set(tuning)
        tuningRepository.save(tuning)
        audioEngine.setSampleProfile(selected)
    }

    fun toggleInputSource() {
        val telemetry = vehicleReader.snapshot()
        val vehicleAvailable = telemetry.vehiclePedalsAvailable()
        val now = SystemClock.elapsedRealtime()
        val previewActive = vehiclePreviewUntilElapsedMs.get() > now

        if (previewActive) {
            vehiclePreviewUntilElapsedMs.set(0L)
            return
        }

        if (vehicleAvailable) {
            selectedInputMode.set(
                if (selectedInputMode.get() == InputMode.SIMULATOR) {
                    InputMode.PREFER_BYD
                } else {
                    InputMode.SIMULATOR
                },
            )
            return
        }

        if (!vehicleAvailable) {
            vehiclePreviewUntilElapsedMs.set(now + VEHICLE_INPUT_PREVIEW_MS)
            return
        }
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
            val enable = !soundEnabled.get()
            soundEnabled.set(enable)
            if (enable && running.get()) audioEngine.start() else audioEngine.stop()
        }
    }

    private fun runLoop(runId: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        var previousNanos = SystemClock.elapsedRealtimeNanos()
        var accumulatorSeconds = 0.0

        while (isCurrent(runId)) {
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
        clearExpiredVehiclePreview()
        val telemetry = vehicleReader.snapshot()
        val mode = selectedInputMode.get()
        val manual = manualInput.get()
        val input = resolveDriveInput(mode, telemetry, manual.throttle, manual.brake)
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
                simulateCoastRegen = input.isSimulator,
                transmissionPosition = transmissionControl.position,
            ),
            dt,
        )
        val enabled = soundEnabled.get()
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = drivetrain.smoothedThrottle,
                enabled = enabled,
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = when (drivetrain.shiftDirection) {
                    ShiftDirection.UP -> 1
                    ShiftDirection.DOWN -> -1
                    ShiftDirection.NONE -> 0
                },
                tuning = effectiveAudioTuning(tuning),
                layerMix = layerMixControls.get(),
                coastLayerMixEnabled = coastLayerMixEnabled.get(),
            ),
        )
        val selectedCar = selectedSampleProfile.get()
        val vehicleAvailable = telemetry.vehiclePedalsAvailable()
        val previewActive = vehiclePreviewUntilElapsedMs.get() > SystemClock.elapsedRealtime()
        val inputUi = resolveInputSourceUi(
            mode = mode,
            vehicleAvailable = vehicleAvailable,
            previewActive = previewActive,
        )
        latest = DriveSnapshot(
            drivetrain = drivetrain,
            inputSourceName = inputUi.displayName,
            inputSourceFaded = inputUi.faded,
            throttle = input.throttle,
            brake = input.brake,
            transmissionPosition = transmissionControl.position,
            engineSoundEnabled = enabled,
            tuning = tuning,
            selectedCarId = selectedCar.id,
            selectedCarName = selectedCar.displayName,
            selectedCarPreviewAsset = selectedCar.previewAssetName,
            selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedCar),
            availableCarCount = EngineSampleProfiles.all.size,
            coastLayerMixEnabled = coastLayerMixEnabled.get(),
        legacyThrottleMixEnabled = !coastLayerMixEnabled.get(),
            carMasterVolume = carMasterVolume.get(),
            transmissionLockedToVehicle = transmissionControl.lockedToVehicle,
        )
    }

    private fun effectiveAudioTuning(tuning: TuningConfig) = tuning.audio.copy(
        masterGain = (
            carMasterVolume.get() * tuning.audio.masterGain / CarMasterVolumeRepository.DEFAULT
            ).coerceIn(CarMasterVolumeRepository.MIN, CarMasterVolumeRepository.MAX),
    )

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private fun clearExpiredVehiclePreview() {
        val deadline = vehiclePreviewUntilElapsedMs.get()
        if (deadline in 1..SystemClock.elapsedRealtime()) {
            vehiclePreviewUntilElapsedMs.set(0L)
        }
    }

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val MASTER_VOLUME_STEP = 0.10
        const val VEHICLE_INPUT_PREVIEW_MS = 2_000L
    }
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
    val isSimulator: Boolean,
)

/** Pure input arbitration kept separate so unavailable vehicle data can be fail-safe tested. */
internal fun resolveDriveInput(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
    simulatorThrottle: Double,
    simulatorBrake: Double,
): ResolvedDriveInput {
    val vehicleAvailable = telemetry.vehiclePedalsAvailable()

    if (vehicleAvailable && mode == InputMode.PREFER_BYD) {
        return ResolvedDriveInput(
            throttle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid },
            label = "BYD PEDALS",
            isSimulator = false,
        )
    }

    return ResolvedDriveInput(
        throttle = simulatorThrottle.coerceIn(0.0, 1.0),
        brake = simulatorBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        label = "SIM PEDALS",
        isSimulator = true,
    )
}

internal data class InputSourceUiState(
    val displayName: String,
    val faded: Boolean,
)

internal fun resolveInputSourceUi(
    mode: InputMode,
    vehicleAvailable: Boolean,
    previewActive: Boolean,
): InputSourceUiState {
    if (previewActive) {
        return InputSourceUiState(
            displayName = InputMode.PREFER_BYD.displayName,
            faded = true,
        )
    }

    if (mode == InputMode.PREFER_BYD && vehicleAvailable) {
        return InputSourceUiState(
            displayName = InputMode.PREFER_BYD.displayName,
            faded = false,
        )
    }

    return InputSourceUiState(
        displayName = InputMode.SIMULATOR.displayName,
        faded = false,
    )
}
