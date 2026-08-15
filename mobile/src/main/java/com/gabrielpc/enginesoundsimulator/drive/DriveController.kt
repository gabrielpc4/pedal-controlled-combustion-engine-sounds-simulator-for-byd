package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.enginesoundsimulator.audio.AudioChannelMode
import com.gabrielpc.enginesoundsimulator.audio.AudioOutputState
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioEngine
import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame
import com.gabrielpc.enginesoundsimulator.diagnostics.PersistentDiagnosticLog
import com.gabrielpc.enginesoundsimulator.simulation.DriverInput
import com.gabrielpc.enginesoundsimulator.simulation.DrivetrainState
import com.gabrielpc.enginesoundsimulator.simulation.EngineProfile
import com.gabrielpc.enginesoundsimulator.simulation.EngineSimulation
import com.gabrielpc.enginesoundsimulator.simulation.ShiftDirection
import com.gabrielpc.enginesoundsimulator.telemetry.BydSpeedReader
import com.gabrielpc.enginesoundsimulator.telemetry.ReaderState
import com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot
import com.gabrielpc.enginesoundsimulator.tuning.TuningConfig
import com.gabrielpc.enginesoundsimulator.tuning.TuningRepository
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
    val engineSoundEnabled: Boolean,
    val audio: AudioOutputState,
    val telemetry: TelemetrySnapshot,
    val tuning: TuningConfig,
)

/** Coordinates BYD/manual inputs, fixed-step drivetrain simulation, and the audio renderer. */
class DriveController(context: Context) {
    private val tuningRepository = TuningRepository(context.applicationContext)
    private val tuningConfig = AtomicReference(tuningRepository.load())
    private var appliedTuning = tuningConfig.get()
    private var profile = appliedTuning.toEngineProfile()
    private val simulation = EngineSimulation(profile)
    private val vehicleReader = BydSpeedReader(context.applicationContext)
    private val audioEngine = EngineAudioEngine(context.applicationContext)
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val manualInput = AtomicReference(ManualInput())
    private val selectedInputMode = AtomicReference(InputMode.AUTO)
    private val soundEnabled = AtomicBoolean(true)
    private var lastLoggedShiftSerial = simulation.state.shiftSerial
    private var lastShiftWasActive = false
    private var lastInputSignature = ""
    private var nextHeartbeatAtElapsedMs = 0L

    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var latest = DriveSnapshot(
        drivetrain = simulation.state,
        inputMode = InputMode.AUTO,
        activeInput = "SIM FALLBACK",
        throttle = 0.0,
        brake = 0.0,
        engineSoundEnabled = true,
        audio = AudioOutputState(),
        telemetry = TelemetrySnapshot(),
        tuning = appliedTuning,
    )

    init {
        PersistentDiagnosticLog.event(
            "drive_controller_created",
            "profile=${profile.name} redline_rpm=${profile.redlineRpm.roundToInt()} " +
                "upshift_rpm=${profile.upshiftRpm.roundToInt()} gears=${profile.gearRatios.size}",
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
            lastShiftWasActive = false
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
        tuningConfig.set(tuningRepository.reset())
        PersistentDiagnosticLog.event("tuning_reset")
    }

    fun cycleInputMode() {
        val modes = InputMode.entries
        val current = selectedInputMode.get()
        setInputMode(modes[(current.ordinal + 1) % modes.size])
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
            profile = tuning.toEngineProfile()
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
            ),
            dt,
        )
        recordDriveDiagnostics(drivetrain, input, mode, telemetry)
        val enabled = soundEnabled.get()
        audioEngine.update(
            EngineAudioFrame(
                rpm = drivetrain.rpm,
                throttle = drivetrain.smoothedThrottle,
                load = drivetrain.engineLoad,
                redlineRpm = profile.redlineRpm,
                cylinders = profile.cylinders,
                shiftSerial = drivetrain.shiftSerial,
                shifting = drivetrain.isShifting,
                limiterActive = drivetrain.limiterActive,
                enabled = enabled,
                tuning = tuning.audio,
            ),
        )
        latest = DriveSnapshot(
            drivetrain = drivetrain,
            inputMode = mode,
            activeInput = input.label,
            throttle = input.throttle,
            brake = input.brake,
            engineSoundEnabled = enabled,
            audio = audioEngine.state(),
            telemetry = telemetry,
            tuning = tuning,
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

        if (drivetrain.shiftSerial != lastLoggedShiftSerial) {
            val targetGear = when (drivetrain.shiftDirection) {
                ShiftDirection.UP -> drivetrain.gear + 1
                ShiftDirection.DOWN -> drivetrain.gear - 1
                ShiftDirection.NONE -> drivetrain.gear
            }
            PersistentDiagnosticLog.event(
                "shift_started",
                "serial=${drivetrain.shiftSerial} direction=${drivetrain.shiftDirection.name} " +
                    "from_gear=${drivetrain.gear} target_gear=$targetGear " +
                    "rpm=${drivetrain.rpm.roundToInt()} speed_kmh=${drivetrain.speedKmh.roundToInt()} " +
                    "throttle_pct=${(input.throttle * 100.0).roundToInt()} " +
                    "brake_pct=${(input.brake * 100.0).roundToInt()} source=${input.label}",
            )
            lastLoggedShiftSerial = drivetrain.shiftSerial
        }

        if (lastShiftWasActive && !drivetrain.isShifting) {
            PersistentDiagnosticLog.event(
                "shift_completed",
                "serial=${drivetrain.shiftSerial} gear=${drivetrain.gear} " +
                    "rpm=${drivetrain.rpm.roundToInt()} speed_kmh=${drivetrain.speedKmh.roundToInt()}",
            )
        }
        lastShiftWasActive = drivetrain.isShifting

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs >= nextHeartbeatAtElapsedMs) {
            nextHeartbeatAtElapsedMs = nowElapsedMs + DIAGNOSTIC_HEARTBEAT_INTERVAL_MS
            PersistentDiagnosticLog.event(
                "drive_heartbeat",
                "gear=${drivetrain.gear} rpm=${drivetrain.rpm.roundToInt()} " +
                    "speed_kmh=${drivetrain.speedKmh.roundToInt()} " +
                    "throttle_pct=${(input.throttle * 100.0).roundToInt()} " +
                    "brake_pct=${(input.brake * 100.0).roundToInt()} " +
                    "shifting=${drivetrain.isShifting} shift_serial=${drivetrain.shiftSerial} " +
                    "source=${input.label} reader=${telemetry.readerState.name}",
            )
        }
    }

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
        const val DIAGNOSTIC_HEARTBEAT_INTERVAL_MS = 1_000L
    }
}

private fun TuningConfig.toEngineProfile(): EngineProfile {
    val engine = engine.sanitized()
    return EngineProfile(
        name = "Apex V10",
        cylinders = 10,
        idleRpm = engine.idleRpm,
        redlineRpm = engine.redlineRpm,
        limiterRpm = engine.limiterRpm,
        upshiftRpm = engine.upshiftRpm,
        downshiftRpm = engine.downshiftRpm,
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
        simulatorCoastRegenMps2 = engine.simulatorCoastRegenMps2,
        finalDrive = engine.finalDrive,
        gearRatios = engine.gearRatios.toDoubleArray(),
        frontWheelTorqueCurve = engine.frontWheelTorqueCurve,
        rearWheelTorqueCurve = engine.rearWheelTorqueCurve,
        throttleCurve = engine.throttleCurve,
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
    val vehicleAvailable = telemetry.readerState == ReaderState.ACTIVE &&
        telemetry.accelerator.isValid && telemetry.brake.isValid

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
