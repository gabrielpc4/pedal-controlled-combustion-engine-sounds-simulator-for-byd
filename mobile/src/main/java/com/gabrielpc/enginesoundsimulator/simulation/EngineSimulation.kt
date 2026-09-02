package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.telemetry.vehiclePedalsAvailable

import kotlin.math.PI
import kotlin.math.abs

/** Inputs accepted by the one authoritative Assetto drivetrain model. */
data class DriverInput(
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    /** True only for the app's touch-pedal scenario; real BYD speed remains authoritative. */
    val simulatedPedals: Boolean = false,
    val externalSpeedKmh: Double? = null,
    val transmissionPosition: TransmissionPosition = TransmissionPosition.DRIVE,
)

enum class ShiftDirection { NONE, UP, DOWN }

data class DrivetrainState(
    val rpm: Double = 0.0,
    val gear: Int = 0,
    val speedKmh: Double = 0.0,
    val smoothedThrottle: Double = 0.0,
    val audioThrottle: Double = 0.0,
    val smoothedBrake: Double = 0.0,
    val engineLoad: Double = 0.0,
    val isShifting: Boolean = false,
    val shiftDirection: ShiftDirection = ShiftDirection.NONE,
    val shiftProgress: Double = 0.0,
    val shiftSerial: Long = 0L,
    val limiterActive: Boolean = false,
    val presentationSpeedKmh: Double = 0.0,
    val presentationAccelerationKmhPerSecond: Double = 0.0,
    val rawSpeedKmh: Double = 0.0,
    val drivetrainSpeedRadiansPerSecond: Double = 0.0,
    val boost: Double = 0.0,
    val bov: Double = 0.0,
    val bovDecaySeconds: Double = 10.0,
    val limiterPulse: Boolean = false,
    val backfireTriggered: Boolean = false,
    val shiftStarted: Boolean = false,
    val shiftRejected: Boolean = false,
    val tractionLimitActive: Boolean = false,
    val tractionLimitPulse: Boolean = false,
    val tachometerMaximumRpm: Double = 0.0,
    val redlineRpm: Double = 0.0,
    val limiterRpm: Double = 0.0,
    val automaticUpshiftRpm: Double = 0.0,
    val automaticDownshiftRpm: Double = 0.0,
)

/**
 * Thin adapter around the authored Assetto Corsa drivetrain. It owns no
 * ignition state, synthetic torque, gear-speed table, or user calibration.
 */
class EngineSimulation {
    var manualShiftEnabled: Boolean = false

    private var physics: AssettoPhysics? = null
    private var drivetrain: AssettoDrivetrain? = null
    private var latestState = DrivetrainState()
    private val presentationSpeedEstimator = QuantizedPresentationSpeedEstimator()
    private val bydSealSimulatedPedalsMotion = BydSealSimulatedPedalsMotion()
    private var simulatedPedalsGearCalibration: SimulatedPedalsGearCalibration? = null

    val state: DrivetrainState get() = latestState

    internal fun updateAssettoPhysics(updated: AssettoPhysics) {
        physics = updated
        drivetrain = AssettoDrivetrain(updated).also { it.reset(engineRunning = true) }
        presentationSpeedEstimator.reset()
        bydSealSimulatedPedalsMotion.reset()
        simulatedPedalsGearCalibration = SimulatedPedalsGearCalibration.from(updated)
        latestState = buildState(updated, drivetrain!!.frame(), 0.0, 0.0, 0.0, false)
    }

    fun reset() {
        drivetrain?.reset(engineRunning = true)
        presentationSpeedEstimator.reset()
        bydSealSimulatedPedalsMotion.reset()
        physics?.let { latestState = buildState(it, drivetrain!!.frame(), 0.0, 0.0, 0.0, false) }
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val activePhysics = physics ?: return latestState
        val activeDrivetrain = drivetrain ?: return latestState
        val dt = deltaSeconds.coerceIn(0.001, 0.020)
        val rawSpeed = input.externalSpeedKmh?.coerceAtLeast(0.0)?.let(::truncateRawSpeedKmh)
        val simulatedMotion = if (input.simulatedPedals) {
            bydSealSimulatedPedalsMotion.step(
                throttle = input.throttle,
                brake = input.brake,
                transmissionPosition = input.transmissionPosition,
                deltaSeconds = dt,
            )
        } else {
            bydSealSimulatedPedalsMotion.reset()
            null
        }
        val presentationSpeed = if (rawSpeed != null) {
            presentationSpeedEstimator.update(
                measurementKmh = rawSpeed,
                throttle = input.throttle,
                brake = input.brake,
                dt = dt,
                responseSeconds = 0.120,
            )
        } else {
            presentationSpeedEstimator.reset()
            simulatedMotion?.speedKmh
        }
        val roadSpeedKmh = rawSpeed ?: simulatedMotion?.speedKmh
        val gearCalibration = if (input.transmissionPosition == TransmissionPosition.DRIVE) {
            // Both input modes use the same presentation gearbox in D: each
            // gear spans an equal share of 0..190 km/h and reaches the authored
            // limiter at its upper boundary. P/N remains a pure free-rev path.
            simulatedPedalsGearCalibration
        } else {
            // P/N must remain a true free-rev path: the Lab uses zero gear ratio and
            // engine inertia alone here. Never let the SIM road-speed gear calibration
            // alter neutral or park, even if the selector changes while still moving.
            null
        }
        val frame = activeDrivetrain.step(
            throttle = input.throttle.coerceIn(0.0, 1.0),
            brake = input.brake.coerceIn(0.0, 1.0),
            transmissionPosition = input.transmissionPosition,
            automaticShifting = !manualShiftEnabled,
            externalSpeedMetersPerSecond = roadSpeedKmh?.div(3.6),
            simulatedPedalsGearCalibration = gearCalibration,
            deltaSeconds = dt,
        )
        val present = presentationSpeed ?: frame.speedMetersPerSecond * 3.6
        val presentAcceleration = if (presentationSpeed != null) {
            if (rawSpeed != null) presentationSpeedEstimator.presentationVelocityKmhPerSecond
            else simulatedMotion?.accelerationKmhPerSecond ?: 0.0
        } else {
            0.0
        }
        latestState = buildState(
            activePhysics = activePhysics,
            frame = frame,
            presentationSpeedKmh = present,
            presentationAccelerationKmhPerSecond = presentAcceleration,
            rawSpeedKmh = rawSpeed ?: truncateRawSpeedKmh(roadSpeedKmh ?: frame.speedMetersPerSecond * 3.6),
            usePresentationRoadSpeed = roadSpeedKmh != null,
            simulatedPedalsGearCalibration = gearCalibration,
        )
        return latestState
    }

    fun requestManualUpshift(): Boolean = manualShiftEnabled && drivetrain?.requestShift(1) == true

    fun requestManualDownshift(): Boolean = manualShiftEnabled && drivetrain?.requestShift(-1) == true

    private fun buildState(
        activePhysics: AssettoPhysics,
        frame: AssettoDrivetrainFrame,
        presentationSpeedKmh: Double,
        presentationAccelerationKmhPerSecond: Double,
        rawSpeedKmh: Double,
        usePresentationRoadSpeed: Boolean,
        simulatedPedalsGearCalibration: SimulatedPedalsGearCalibration? = null,
    ): DrivetrainState {
        val audibleRpm = if (usePresentationRoadSpeed && frame.gear != 0 && presentationSpeedKmh > 0.01) {
            val wheelOmega = presentationSpeedKmh / 3.6 / drivenWheelRadius(activePhysics)
            val ratio = abs(
                (simulatedPedalsGearCalibration?.ratioForGear(frame.gear, activePhysics.drivetrain)
                    ?: activePhysics.drivetrain.ratioForGear(frame.gear)) * activePhysics.drivetrain.finalDrive,
            )
            (wheelOmega * ratio * 60.0 / (2.0 * PI)).coerceIn(activePhysics.engine.idleRpm, activePhysics.engine.limiterRpm)
        } else {
            frame.rpm.coerceAtLeast(activePhysics.engine.idleRpm)
        }
        val redline = activePhysics.engine.shiftLightsRpm.maxOrNull()
            ?: activePhysics.engine.limiterRpm
        return DrivetrainState(
            rpm = audibleRpm,
            gear = frame.gear,
            speedKmh = rawSpeedKmh,
            smoothedThrottle = frame.effectiveThrottle,
            audioThrottle = frame.driverThrottle,
            smoothedBrake = frame.brake,
            engineLoad = frame.effectiveThrottle,
            isShifting = frame.shifting,
            shiftDirection = when {
                frame.shiftDirection > 0 -> ShiftDirection.UP
                frame.shiftDirection < 0 -> ShiftDirection.DOWN
                else -> ShiftDirection.NONE
            },
            shiftProgress = frame.shiftProgress,
            shiftSerial = latestState.shiftSerial + if (frame.shiftStarted) 1 else 0,
            limiterActive = frame.limiterPulse,
            presentationSpeedKmh = presentationSpeedKmh,
            presentationAccelerationKmhPerSecond = presentationAccelerationKmhPerSecond,
            rawSpeedKmh = rawSpeedKmh,
            drivetrainSpeedRadiansPerSecond = frame.drivetrainSpeedRadiansPerSecond,
            boost = frame.boost,
            bov = frame.bov,
            bovDecaySeconds = frame.bovDecaySeconds,
            limiterPulse = frame.limiterPulse,
            backfireTriggered = frame.backfireTriggered,
            shiftStarted = frame.shiftStarted,
            shiftRejected = frame.shiftRejected,
            tractionLimitActive = frame.tractionLimitActive,
            tractionLimitPulse = frame.tractionLimitPulse,
            tachometerMaximumRpm = activePhysics.engine.tachometerMaximumRpm,
            redlineRpm = redline,
            limiterRpm = activePhysics.engine.limiterRpm,
            automaticUpshiftRpm = simulatedPedalsGearCalibration?.limiterRpm
                ?: activePhysics.drivetrain.automaticUpshiftRpm.toDouble(),
            automaticDownshiftRpm = simulatedPedalsGearCalibration?.automaticDownshiftRpm(frame.gear)
                ?: activePhysics.drivetrain.automaticDownshiftRpm.toDouble(),
        )
    }
}

internal data class ResolvedDriveInput(
    val throttle: Double,
    val brake: Double,
    val externalSpeedKmh: Double?,
    val label: String,
    val usesSimulatedPedals: Boolean,
)

internal fun resolveDriveInput(
    mode: com.gabrielpc.enginesoundsimulator.drive.InputMode,
    telemetry: com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot,
    simulatedPedalThrottle: Double,
    simulatedPedalBrake: Double,
): ResolvedDriveInput {
    val vehicleAvailable = telemetry.vehiclePedalsAvailable()
    if (vehicleAvailable && mode == com.gabrielpc.enginesoundsimulator.drive.InputMode.RealPedals) {
        return ResolvedDriveInput(
            throttle = normalizeVehicleThrottlePercent(telemetry.accelerator.value!!),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            externalSpeedKmh = telemetry.speed.value?.takeIf { telemetry.speed.isValid }?.let(::truncateRawSpeedKmh),
            label = com.gabrielpc.enginesoundsimulator.drive.InputMode.RealPedals.displayName,
            usesSimulatedPedals = false,
        )
    }
    return ResolvedDriveInput(
        throttle = simulatedPedalThrottle.coerceIn(0.0, 1.0),
        brake = simulatedPedalBrake.coerceIn(0.0, 1.0),
        externalSpeedKmh = null,
        label = com.gabrielpc.enginesoundsimulator.drive.InputMode.SimulatedPedals.displayName,
        usesSimulatedPedals = true,
    )
}

internal fun normalizeVehicleThrottlePercent(percent: Double): Double =
    if (percent >= 99.0) 1.0 else (percent / 100.0).coerceIn(0.0, 1.0)

internal fun truncateRawSpeedKmh(speedKmh: Double): Double =
    kotlin.math.floor(speedKmh.coerceAtLeast(0.0))
