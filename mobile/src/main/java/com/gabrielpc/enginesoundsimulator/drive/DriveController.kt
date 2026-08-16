package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioChannelMode
import com.gabrielpc.enginesoundsimulator.audio.AudioOutputState
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.SelectedCarRepository
import com.gabrielpc.enginesoundsimulator.audio.SoundEffectsRepository
import com.gabrielpc.enginesoundsimulator.diagnostics.PersistentDiagnosticLog
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.ReaderState
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
import com.gabrielpc.enginesoundsimulator.tuning.withSampleProfile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

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
    private val selectedSampleProfile = AtomicReference(selectedCarRepository.load())
    private val enabledEffectMask = AtomicLong(soundEffectsRepository.loadEnabledMask(selectedSampleProfile.get()))
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
    private var lastInputSignature = ""
    private var nextHeartbeatAtElapsedMs = 0L
    private var lastEffectTelemetryProfile = ""
    private var lastEffectTriggerCount = 0L
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
    )

    init {
        audioEngine.setSampleProfile(selectedSampleProfile.get())
        PersistentDiagnosticLog.event(
            "drive_controller_created",
            "profile=${profile.name} redline_rpm=${profile.redlineRpm.roundToInt()} " +
                "mode=DIRECT_TACH sweet_spot_rpm=${profile.fullThrottleSweetSpotRpm.roundToInt()} " +
                "full_pedal_kick_rpm_per_s=${profile.fullThrottleKickRpmPerSecond.roundToInt()}",
        )
    }

    fun snapshot(): DriveSnapshot = latest.copy(audio = audioEngine.state())

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && loopThread?.isAlive == true) return
            val previous = loopThread
            if (previous?.isAlive == true) {
                previous.interrupt()
                if (!joinLoop(previous)) return
            }

            // Start each visible/controller session with a fresh source line and heartbeat.
            lastInputSignature = ""
            nextHeartbeatAtElapsedMs = 0L
            val runId = generation.incrementAndGet()
            running.set(true)
            val thread = Thread({ runLoop(runId) }, "drivetrain-simulation").apply { isDaemon = true }
            loopThread = thread
            try {
                vehicleReader.start()
                if (soundEnabled.get()) audioEngine.start()
                thread.start()
                PersistentDiagnosticLog.event(
                    "drive_controller_started",
                    "mode=${selectedInputMode.get().name} sound_enabled=${soundEnabled.get()}",
                )
            } catch (throwable: Throwable) {
                running.set(false)
                generation.incrementAndGet()
                loopThread = null
                vehicleReader.stop()
                audioEngine.stop()
                PersistentDiagnosticLog.recordThrowable("drive_controller_start_failed", throwable)
                throw throwable
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            PersistentDiagnosticLog.event("drive_controller_stopping")
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
            PersistentDiagnosticLog.event("drive_controller_stopped")
        }
    }

    fun setManualThrottle(value: Double) {
        manualInput.updateAndGet { it.copy(throttle = value.coerceIn(0.0, 1.0)) }
    }

    fun setManualBrake(value: Double) {
        manualInput.updateAndGet { it.copy(brake = value.coerceIn(0.0, 1.0)) }
    }

    fun setInputMode(mode: InputMode) {
        val previous = selectedInputMode.getAndSet(mode)
        if (previous != mode) {
            PersistentDiagnosticLog.event("input_mode_changed", "from=${previous.name} to=${mode.name}")
        }
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
        PersistentDiagnosticLog.event("tuning_reset")
    }

    fun selectPreviousCar() = selectAdjacentCar(-1)

    fun selectNextCar() = selectAdjacentCar(1)

    fun setSoundEffectEnabled(controlId: String, enabled: Boolean) {
        val selected = selectedSampleProfile.get()
        val updatedMask = soundEffectsRepository.setEnabled(selected, controlId, enabled)
        enabledEffectMask.set(updatedMask)
        PersistentDiagnosticLog.event(
            "sound_effect_toggled",
            "profile=${selected.id} effect=$controlId enabled=$enabled mask=$updatedMask",
        )
    }

    private fun selectAdjacentCar(offset: Int) {
        val previous = selectedSampleProfile.get()
        val selected = EngineSampleProfiles.adjacent(previous.id, offset)
        if (selected.id == previous.id) return
        selectedSampleProfile.set(selected)
        enabledEffectMask.set(soundEffectsRepository.loadEnabledMask(selected))
        selectedCarRepository.save(selected)
        val tuning = tuningConfig.get().withSampleProfile(selected)
        tuningConfig.set(tuning)
        tuningRepository.save(tuning)
        audioEngine.setSampleProfile(selected)
        PersistentDiagnosticLog.event(
            "car_profile_changed",
            "from=${previous.id} to=${selected.id} layers=${selected.layers.size} " +
                "sample_rate=${selected.outputSampleRate} rpm_domain=${selected.minimumRpm.toInt()}-${selected.maximumRpm.toInt()}",
        )
    }

    fun cycleInputMode() {
        val modes = InputMode.entries
        val current = selectedInputMode.get()
        setInputMode(modes[(current.ordinal + 1) % modes.size])
    }

    fun setTransmissionPosition(position: TransmissionPosition) {
        val previous = transmissionPosition.getAndSet(position)
        if (previous != position) {
            PersistentDiagnosticLog.event("transmission_position_changed", "from=${previous.name} to=${position.name}")
        }
    }

    fun restartVehicleReader() {
        vehicleReader.restart()
        PersistentDiagnosticLog.event("byd_reader_restart_requested")
    }

    fun toggleSound() {
        synchronized(lifecycleLock) {
            val enable = !soundEnabled.get()
            soundEnabled.set(enable)
            if (enable && running.get()) audioEngine.start() else audioEngine.stop()
            PersistentDiagnosticLog.event("sound_toggled", "enabled=$enable")
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
        PersistentDiagnosticLog.event("audio_channel_mode_changed", "from=${current.name} to=${selected.name}")
    }

    /** Runs a deterministic pedal program for on-device sample-renderer and telemetry validation. */
    fun runSampleAudioValidation() {
        synchronized(lifecycleLock) {
            if (validationThread.get()?.isAlive == true) {
                PersistentDiagnosticLog.warning("sample_validation_already_running")
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
                        PersistentDiagnosticLog.event("sample_validation_started")
                        var previousThrottle = 0.0
                        VALIDATION_STAGES.forEachIndexed { index, stage ->
                            PersistentDiagnosticLog.event(
                                "sample_validation_stage",
                                "index=$index throttle_pct=${(stage.throttle * 100.0).roundToInt()} " +
                                    "duration_ms=${stage.durationMs}",
                            )
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
                        completed = true
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        manualInput.set(ManualInput())
                        PersistentDiagnosticLog.event(
                            "sample_validation_finished",
                            "completed=$completed ${sampleAudioLogDetails(audioEngine.state())}",
                        )
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
            PersistentDiagnosticLog.event("drive_loop_started", "generation=$runId")
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
            PersistentDiagnosticLog.recordThrowable("drive_loop_failed", throwable, "generation=$runId")
            throw throwable
        } finally {
            PersistentDiagnosticLog.event("drive_loop_stopped", "generation=$runId")
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
                simulateCoastRegen = mode == InputMode.SIMULATOR,
                transmissionPosition = transmissionPosition.get(),
            ),
            dt,
        )
        recordDriveDiagnostics(drivetrain, input, mode, telemetry)
        val enabled = soundEnabled.get()
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = drivetrain.smoothedThrottle,
                enabled = enabled,
                enabledEffectMask = enabledEffectMask.get(),
                shiftSerial = 0L,
                shiftDirection = 0,
                tuning = tuning.audio,
            ),
        )
        val selectedCar = selectedSampleProfile.get()
        latest = DriveSnapshot(
            drivetrain = drivetrain,
            inputMode = mode,
            activeInput = input.label,
            throttle = input.throttle,
            brake = input.brake,
            transmissionPosition = transmissionPosition.get(),
            engineSoundEnabled = enabled,
            audio = audioEngine.state(),
            telemetry = telemetry,
            tuning = tuning,
            selectedCarId = selectedCar.id,
            selectedCarName = selectedCar.displayName,
            selectedCarPreviewAsset = selectedCar.previewAssetName,
            selectedCarIndex = EngineSampleProfiles.all.indexOf(selectedCar),
            availableCarCount = EngineSampleProfiles.all.size,
            soundEffects = soundEffectOptions(selectedCar, enabledEffectMask.get()),
        )
    }

    /**
     * Persists only state transitions plus a one-second heartbeat. The logger fsyncs entries, so
     * keeping this out of the 200 Hz hot path prevents diagnostic I/O from affecting audio or
     * simulation timing.
     */
    private fun recordDriveDiagnostics(
        drivetrain: DrivetrainState,
        input: ResolvedDriveInput,
        mode: InputMode,
        telemetry: TelemetrySnapshot,
    ) {
        val inputSignature = "${mode.name}|${input.label}|${telemetry.readerState.name}|" +
            "accelerator_valid=${telemetry.accelerator.isValid}|brake_valid=${telemetry.brake.isValid}|" +
            "speed_valid=${telemetry.speed.isValid}"
        if (inputSignature != lastInputSignature) {
            lastInputSignature = inputSignature
            PersistentDiagnosticLog.event("input_source_changed", inputSignature)
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs >= nextHeartbeatAtElapsedMs) {
            nextHeartbeatAtElapsedMs = nowElapsedMs + DIAGNOSTIC_HEARTBEAT_INTERVAL_MS
            val audio = audioEngine.state()
            if (audio.sampleProfile != lastEffectTelemetryProfile) {
                lastEffectTelemetryProfile = audio.sampleProfile
                lastEffectTriggerCount = audio.sampleEffectTriggers
            } else if (audio.sampleEffectTriggers > lastEffectTriggerCount) {
                PersistentDiagnosticLog.event(
                    "sample_effect_triggered",
                    "profile=${audio.sampleProfile} count=${audio.sampleEffectTriggers} " +
                        "delta=${audio.sampleEffectTriggers - lastEffectTriggerCount} " +
                        "active=${audio.sampleActiveEffects.replace(' ', '_')}",
                )
                lastEffectTriggerCount = audio.sampleEffectTriggers
            }
            PersistentDiagnosticLog.event(
                "drive_heartbeat",
                "mode=DIRECT_TACH rpm=${drivetrain.rpm.roundToInt()} " +
                    "speed_kmh=${drivetrain.speedKmh.roundToInt()} " +
                    "throttle_pct=${(input.throttle * 100.0).roundToInt()} " +
                    "brake_pct=${(input.brake * 100.0).roundToInt()} " +
                    "rpm_curve_permille=${(drivetrain.rpmProgressionFraction * 1_000.0).roundToInt()} " +
                    "rpm_push_per_s=${drivetrain.rpmPositiveForcePerSecond.roundToInt()} " +
                    "rpm_drag_per_s=${drivetrain.rpmNegativeForcePerSecond.roundToInt()} " +
                    "source=${input.label} reader=${telemetry.readerState.name} " +
                    "car_profile=${selectedSampleProfile.get().id} sample_status=${audio.sampleStatus} " +
                    "simulation_rpm=${drivetrain.rpm.roundToInt()} " +
                    "sample_target_rpm=${audio.sampleTargetRpm} sample_render_rpm=${audio.sampleRenderRpm} " +
                    "rpm_delta=${audio.sampleRenderRpm - drivetrain.rpm.roundToInt()} " +
                    "sample_loops=${audio.sampleLoadedLoops} " +
                    "sample_effects=${audio.sampleLoadedEffects} effect_mask=${enabledEffectMask.get()} " +
                    "effect_triggers=${audio.sampleEffectTriggers} active_effects=${audio.sampleActiveEffects} " +
                    "sample_throttle_pct=${(audio.sampleThrottle * 100.0).roundToInt()} " +
                    "layers=${audio.sampleActiveLayers.replace(' ', '_')} " +
                    "sample_frames=${audio.sampleFramesRendered} wraps=${audio.sampleLoopWraps} " +
                    "peak_milli=${(audio.samplePeak * 1_000.0).roundToInt()} " +
                    "over_range=${audio.sampleOverRangeSamples} underruns=${audio.underruns} " +
                    "startup_underruns=${audio.startupUnderruns} " +
                    "steady_underruns=${audio.steadyStateUnderruns}",
            )
        }
    }

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val DIAGNOSTIC_HEARTBEAT_INTERVAL_MS = 1_000L
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

private fun sampleAudioLogDetails(audio: AudioOutputState): String =
    "sample_status=${audio.sampleStatus} sample_target_rpm=${audio.sampleTargetRpm} " +
        "sample_render_rpm=${audio.sampleRenderRpm} " +
        "sample_loops=${audio.sampleLoadedLoops} layers=${audio.sampleActiveLayers.replace(' ', '_')} " +
        "sample_frames=${audio.sampleFramesRendered} wraps=${audio.sampleLoopWraps} " +
        "peak_milli=${(audio.samplePeak * 1_000.0).roundToInt()} " +
        "over_range=${audio.sampleOverRangeSamples} underruns=${audio.underruns} " +
        "startup_underruns=${audio.startupUnderruns} steady_underruns=${audio.steadyStateUnderruns}"

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
        driveRpmAccelerationPerSecond = engine.driveRpmAccelerationPerSecond,
        fullThrottleSweetSpotRpm = engine.fullThrottleSweetSpotRpm,
        fullThrottleKickRpmPerSecond = engine.fullThrottleKickRpmPerSecond,
        liftOffRpmDecelerationPerSecond = engine.liftOffRpmDecelerationPerSecond,
        brakeRpmDecelerationPerSecond = engine.brakeRpmDecelerationPerSecond,
        simulatorCoastRegenMps2 = engine.simulatorCoastRegenMps2,
        gearRatios = engine.gearRatios.toDoubleArray(),
        frontWheelTorqueCurve = engine.frontWheelTorqueCurve,
        rearWheelTorqueCurve = engine.rearWheelTorqueCurve,
        throttleCurve = engine.throttleCurve,
        rpmProgressionCurve = engine.rpmProgressionCurve,
        throttleAttackSeconds = engine.throttleAttackMs / 1_000.0,
        throttleReleaseSeconds = engine.throttleReleaseMs / 1_000.0,
        brakeResponseSeconds = engine.brakeResponseMs / 1_000.0,
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
        )
    }

    if (mode == InputMode.VEHICLE) {
        return ResolvedDriveInput(
            throttle = 0.0,
            brake = 0.0,
            externalSpeedKmh = null,
            label = "BYD UNAVAILABLE",
        )
    }

    return ResolvedDriveInput(
        throttle = simulatorThrottle.coerceIn(0.0, 1.0),
        brake = simulatorBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        label = "SIM PEDALS",
    )
}
