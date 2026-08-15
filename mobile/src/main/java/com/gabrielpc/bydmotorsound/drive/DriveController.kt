package com.gabrielpc.bydmotorsound.drive

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gabrielpc.bydmotorsound.audio.AudioChannelMode
import com.gabrielpc.bydmotorsound.audio.AudioOutputState
import com.gabrielpc.bydmotorsound.audio.EngineAudioEngine
import com.gabrielpc.bydmotorsound.audio.EngineAudioFrame
import com.gabrielpc.bydmotorsound.simulation.DriverInput
import com.gabrielpc.bydmotorsound.simulation.DrivetrainState
import com.gabrielpc.bydmotorsound.simulation.EngineProfile
import com.gabrielpc.bydmotorsound.simulation.EngineSimulation
import com.gabrielpc.bydmotorsound.telemetry.BydSpeedReader
import com.gabrielpc.bydmotorsound.telemetry.ReaderState
import com.gabrielpc.bydmotorsound.telemetry.TelemetrySnapshot
import com.gabrielpc.bydmotorsound.tuning.TuningConfig
import com.gabrielpc.bydmotorsound.tuning.TuningRepository
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

    fun snapshot(): DriveSnapshot = latest.copy(audio = audioEngine.state())

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get() && loopThread?.isAlive == true) return
            val previous = loopThread
            if (previous?.isAlive == true) {
                previous.interrupt()
                if (!joinLoop(previous)) return
            }

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
        tuningConfig.set(tuningRepository.reset())
    }

    fun cycleInputMode() {
        val modes = InputMode.entries
        val current = selectedInputMode.get()
        selectedInputMode.set(modes[(current.ordinal + 1) % modes.size])
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
        audioEngine.setChannelMode(order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size])
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
            ),
            dt,
        )
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

    private data class ManualInput(val throttle: Double = 0.0, val brake: Double = 0.0)

    private companion object {
        const val FIXED_STEP_SECONDS = 1.0 / 200.0
        const val FIXED_STEP_NANOS = 5_000_000L
        const val LOOP_JOIN_TIMEOUT_MS = 500L
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
        tractionLimitMps2 = engine.tractionLimitMps2,
        vehicleMassKg = engine.vehicleMassKg,
        wheelRadiusMeters = engine.wheelRadiusMeters,
        dragAreaM2 = engine.dragAreaM2,
        rollingResistanceCoefficient = engine.rollingResistanceCoefficient,
        topSpeedKmh = engine.topSpeedKmh,
        syntheticRpmResponseSeconds = engine.syntheticRpmResponseMs / 1_000.0,
        finalDrive = engine.finalDrive,
        gearRatios = engine.gearRatios.toDoubleArray(),
        torqueCurve = engine.torqueCurve,
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
