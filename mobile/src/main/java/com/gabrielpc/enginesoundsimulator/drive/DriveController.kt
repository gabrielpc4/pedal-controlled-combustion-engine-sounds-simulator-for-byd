package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioChannelMode
import com.gabrielpc.enginesoundsimulator.audio.AudioOutputState
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.LayerMixRepository
import com.gabrielpc.enginesoundsimulator.audio.LayerMixTrackState
import com.gabrielpc.enginesoundsimulator.audio.mixerDisplayName
import com.gabrielpc.enginesoundsimulator.audio.mixerTrackOrder
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import com.gabrielpc.enginesoundsimulator.audio.SoundEffectsRepository
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
import com.gabrielpc.enginesoundsimulator.tuning.withSampleProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class InputMode(val displayName: String) {
    AUTO("AUTO"),
    SIMULATOR("SIM"),
    VEHICLE("BYD LIVE"),
}

data class DriveSnapshot(
    val drivetrain: DrivetrainState,
    val inputMode: InputMode,
    val activeInput: String,
    val throttle: Double,
    val brake: Double,
    val transmissionPosition: TransmissionPosition,
    val engineSoundEnabled: Boolean,
    val audio: AudioOutputState,
    val telemetry: TelemetrySnapshot,
    val tuning: TuningConfig,
    val selectedCarId: String,
    val selectedCarName: String,
    val selectedCarPreviewAsset: String,
    val selectedCarIndex: Int,
    val availableCarCount: Int,
    val soundEffects: List<SoundEffectOption>,
    val soloSoundEffects: Boolean,
    val layerMixTracks: List<LayerMixTrackState> = emptyList(),
)

data class SoundEffectOption(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(context: Context) {
    private val tuningRepository = TuningRepository(context.applicationContext)
    private val selectedCarRepository = SelectedCarRepository(context.applicationContext)
    private val soundEffectsRepository = SoundEffectsRepository(context.applicationContext)
    private val layerMixRepository = LayerMixRepository(context.applicationContext)
    private val selectedSampleProfile = AtomicReference(selectedCarRepository.load())
    private val layerMixControls = AtomicReference(layerMixRepository.load(selectedCarRepository.load()))
    private val enabledEffectMask = AtomicLong(soundEffectsRepository.loadEnabledMask(selectedSampleProfile.get()))
    private val soloEffects = AtomicBoolean(soundEffectsRepository.loadSoloEffects(selectedSampleProfile.get()))
    private val tuningConfig = AtomicReference(tuningRepository.load())
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile(selectedSampleProfile.get())
    private val simulation = EngineSimulation(profile)
    private val vehicleReader = BydSpeedReader(context.applicationContext)
    private val audioEngine = EngineAudioEngine(context.applicationContext)
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val manualInput = AtomicReference(ManualInput())
    private val selectedInputMode = AtomicReference(InputMode.AUTO)
    private val transmissionPosition = AtomicReference(TransmissionPosition.DRIVE)
    private val soundEnabled = AtomicBoolean(true)
    private val debugPanelVisible = AtomicBoolean(false)
    private val validationThread = AtomicReference<Thread?>(null)

    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputMode = InputMode.AUTO,
        activeInput = "SIM FALLBACK",
        throttle = 0.0,
        brake = 0.0,
        transmissionPosition = TransmissionPosition.DRIVE,
        engineSoundEnabled = true,
        audio = AudioOutputState(),
        telemetry = TelemetrySnapshot(),
        tuning = appliedTuning,
        selectedCarId = selectedSampleProfile.get().id,
        selectedCarName = selectedSampleProfile.get().displayName,
        selectedCarPreviewAsset = selectedSampleProfile.get().previewAssetName,
        selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedSampleProfile.get()),
        availableCarCount = EngineSampleProfiles.all.size,
        soundEffects = soundEffectOptions(selectedSampleProfile.get(), enabledEffectMask.get()),
        soloSoundEffects = soloEffects.get(),
    )

    init {
        audioEngine.setSampleProfile(selectedSampleProfile.get())
    }

    fun setDebugPanelVisible(visible: Boolean) {
        debugPanelVisible.set(visible)
    }

    fun snapshot(): DriveSnapshot {
        val base = latest
        val liveAudio = audioEngine.state()
        if (!debugPanelVisible.get()) {
            return base.copy(
                audio = base.audio.copy(
                    requestedMode = liveAudio.requestedMode,
                    activeChannels = liveAudio.activeChannels,
                    running = liveAudio.running,
                    sampleStatus = liveAudio.sampleStatus,
                    sampleError = liveAudio.sampleError,
                    error = liveAudio.error,
                    samplePlaying = liveAudio.samplePlaying,
                    layerOutputMeters = liveAudio.layerOutputMeters,
                ),
                layerMixTracks = buildLayerMixTracks(
                    selectedSampleProfile.get(),
                    layerMixControls.get(),
                    liveAudio.layerOutputMeters.associate { it.id to it.outputLevel },
                ),
            )
        }
        return base.copy(
            telemetry = vehicleReader.snapshot(),
            audio = liveAudio,
            layerMixTracks = buildLayerMixTracks(
                selectedSampleProfile.get(),
                layerMixControls.get(),
                liveAudio.layerOutputMeters.associate { it.id to it.outputLevel },
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
                DebugEventLog.recordThrowable("drive_controller_start_failed", throwable)
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
            validationThread.getAndSet(null)?.let { validation ->
                validation.interrupt()
                joinLoop(validation)
            }
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

    fun setSoundEffectEnabled(controlId: String, enabled: Boolean) {
        val selected = selectedSampleProfile.get()
        val updatedMask = soundEffectsRepository.setEnabled(selected, controlId, enabled)
        enabledEffectMask.set(updatedMask)
    }

    fun setSoloSoundEffects(enabled: Boolean) {
        val selected = selectedSampleProfile.get()
        soundEffectsRepository.setSoloEffects(selected, enabled)
        soloEffects.set(enabled)
    }

    private fun selectAdjacentCar(offset: Int) {
        val previous = selectedSampleProfile.get()
        val selected = EngineSampleProfiles.adjacent(previous.id, offset)
        if (selected.id == previous.id) return
        applySelectedCar(selected)
    }

    private fun applySelectedCar(selected: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile) {
        selectedSampleProfile.set(selected)
        enabledEffectMask.set(soundEffectsRepository.loadEnabledMask(selected))
        soloEffects.set(soundEffectsRepository.loadSoloEffects(selected))
        layerMixControls.set(layerMixRepository.load(selected))
        selectedCarRepository.save(selected)
        val tuning = tuningConfig.get().withSampleProfile(selected)
        tuningConfig.set(tuning)
        tuningRepository.save(tuning)
        audioEngine.setSampleProfile(selected)
    }

    fun cycleInputMode() {
        val modes = InputMode.entries
        val current = selectedInputMode.get()
        setInputMode(modes[(current.ordinal + 1) % modes.size])
    }

    fun setTransmissionPosition(position: TransmissionPosition) {
        transmissionPosition.set(position)
    }

    fun restartVehicleReader() {
        vehicleReader.restart()
    }

    fun toggleSound() {
        synchronized(lifecycleLock) {
            val enable = !soundEnabled.get()
            soundEnabled.set(enable)
            if (enable && running.get()) audioEngine.start() else audioEngine.stop()
        }
    }

    fun cycleChannelMode() {
        val order = listOf(
            AudioChannelMode.AUTO,
            AudioChannelMode.SURROUND_7_1,
            AudioChannelMode.SURROUND_5_1,
            AudioChannelMode.QUAD,
            AudioChannelMode.STEREO,
        )
        val current = audioEngine.state().requestedMode
        val selected = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
        audioEngine.setChannelMode(selected)
    }

    /** Runs a deterministic pedal program for on-device sample-renderer and telemetry validation. */
    fun runSampleAudioValidation() {
        synchronized(lifecycleLock) {
            if (validationThread.get()?.isAlive == true) {
                DebugEventLog.warning("sample_validation_already_running")
                return
            }
            selectedInputMode.set(InputMode.SIMULATOR)
            transmissionPosition.set(TransmissionPosition.DRIVE)
            manualInput.set(ManualInput())
            if (!soundEnabled.getAndSet(true) && running.get()) audioEngine.start()

            val validation = Thread(
                {
                    var completed = false
                    try {
                        var previousThrottle = 0.0
                        VALIDATION_STAGES.forEach { stage ->
                            val stageStarted = SystemClock.elapsedRealtime()
                            while (SystemClock.elapsedRealtime() - stageStarted < stage.durationMs) {
                                val elapsed = SystemClock.elapsedRealtime() - stageStarted
                                val ramp = (elapsed / VALIDATION_RAMP_MS.toDouble()).coerceIn(0.0, 1.0)
                                val throttle = previousThrottle + (stage.throttle - previousThrottle) * ramp
                                manualInput.set(ManualInput(throttle = throttle, brake = 0.0))
                                Thread.sleep(VALIDATION_UPDATE_MS)
                            }
                            previousThrottle = stage.throttle
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        manualInput.set(ManualInput())
                        validationThread.compareAndSet(Thread.currentThread(), null)
                    }
                },
                "sample-audio-validation",
            ).apply { isDaemon = true }
            validationThread.set(validation)
            validation.start()
        }
    }

    private fun runLoop(runId: Long) {
        try {
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
        } catch (throwable: Throwable) {
            DebugEventLog.recordThrowable("drive_loop_failed", throwable, "generation=$runId")
            throw throwable
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
        val manual = manualInput.get()
        val input = resolveDriveInput(mode, telemetry, manual.throttle, manual.brake)

        val drivetrain = simulation.update(
            DriverInput(
                throttle = input.throttle,
                brake = input.brake,
                externalSpeedKmh = input.externalSpeedKmh,
                // AUTO falls back to the same SIM pedals when BYD input is unavailable.
                // Use the resolved source, not just the selected mode, for its speed behavior.
                simulateCoastRegen = input.isSimulator,
                transmissionPosition = transmissionPosition.get(),
            ),
            dt,
        )
        val enabled = soundEnabled.get()
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = drivetrain.smoothedThrottle,
                enabled = enabled,
                enabledEffectMask = enabledEffectMask.get(),
                soloEffects = soloEffects.get(),
                shiftSerial = drivetrain.shiftSerial,
                shiftDirection = when (drivetrain.shiftDirection) {
                    ShiftDirection.UP -> 1
                    ShiftDirection.DOWN -> -1
                    ShiftDirection.NONE -> 0
                },
                tuning = tuning.audio,
                layerMix = layerMixControls.get(),
            ),
        )
        val selectedCar = selectedSampleProfile.get()
        val outputLevels = audioEngine.state().layerOutputMeters.associate { it.id to it.outputLevel }
        val debugVisible = debugPanelVisible.get()
        latest = DriveSnapshot(
            drivetrain = drivetrain,
            inputMode = mode,
            activeInput = input.label,
            throttle = input.throttle,
            brake = input.brake,
            transmissionPosition = transmissionPosition.get(),
            engineSoundEnabled = enabled,
            audio = if (debugVisible) audioEngine.state() else latest.audio,
            telemetry = if (debugVisible) telemetry else latest.telemetry,
            tuning = tuning,
            selectedCarId = selectedCar.id,
            selectedCarName = selectedCar.displayName,
            selectedCarPreviewAsset = selectedCar.previewAssetName,
            selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedCar),
            availableCarCount = EngineSampleProfiles.all.size,
            soundEffects = soundEffectOptions(selectedCar, enabledEffectMask.get()),
            soloSoundEffects = soloEffects.get(),
            layerMixTracks = buildLayerMixTracks(selectedCar, layerMixControls.get(), outputLevels),
        )
    }

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val VALIDATION_RAMP_MS = 500L
        const val VALIDATION_UPDATE_MS = 50L
        val VALIDATION_STAGES = listOf(
            ValidationStage(throttle = 0.25, durationMs = 2_500L),
            ValidationStage(throttle = 0.55, durationMs = 3_000L),
            ValidationStage(throttle = 1.00, durationMs = 9_000L),
            ValidationStage(throttle = 0.00, durationMs = 5_000L),
        )
    }

    private data class ValidationStage(val throttle: Double, val durationMs: Long)
}

private fun soundEffectOptions(
    profile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile,
    mask: Long,
): List<SoundEffectOption> = profile.effectControls.map { control ->
    SoundEffectOption(
        id = control.id,
        displayName = control.displayName,
        description = control.description,
        enabled = mask and control.bit != 0L,
    )
}

private fun buildLayerMixTracks(
    profile: com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfile,
    controls: Map<String, LayerMixControl>,
    outputLevels: Map<String, Double>,
): List<LayerMixTrackState> {
    val layerById = profile.layers.associateBy { it.id }
    val effectById = profile.effects.associateBy { it.id }
    return profile.mixerTrackOrder().mapNotNull { (trackId, sortGroup) ->
        val control = controls[trackId] ?: LayerMixControl.DEFAULT
        val layer = layerById[trackId]
        val effect = effectById[trackId]
        when {
            layer != null -> LayerMixTrackState(
                id = trackId,
                displayName = layer.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels[trackId] ?: 0.0,
                isEffect = false,
            )
            effect != null -> LayerMixTrackState(
                id = trackId,
                displayName = effect.mixerDisplayName(),
                sortGroup = sortGroup,
                userVolume = control.volume,
                muted = control.muted,
                solo = control.solo,
                outputLevel = outputLevels[trackId] ?: 0.0,
                isEffect = true,
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

    if (vehicleAvailable && mode != InputMode.SIMULATOR) {
        return ResolvedDriveInput(
            throttle = (telemetry.accelerator.value!! / 100.0).coerceIn(0.0, 1.0),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid },
            label = "BYD PEDALS",
            isSimulator = false,
        )
    }

    if (mode == InputMode.VEHICLE) {
        return ResolvedDriveInput(
            throttle = 0.0,
            brake = 0.0,
            externalSpeedKmh = null,
            label = "BYD UNAVAILABLE",
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
