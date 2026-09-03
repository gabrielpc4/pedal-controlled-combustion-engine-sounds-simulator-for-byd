package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.telemetry.vehicleDriveSignalsAvailable

import kotlin.math.PI
import kotlin.math.abs

/**
 * Inputs accepted by the one authoritative Assetto drivetrain model.
 *
 * Naming convention in this file is intentional: `real...` means a value reported by
 * physical BYD telemetry, `documented...` means a value produced from the documented
 * car-data model, and `...Extrapolated...` means a presentation value reconstructed
 * from one of those sources. This keeps model values from being mistaken for live
 * vehicle values.
 */
data class DriverInput(
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    /** True only for the app's touch-pedal scenario; real BYD speed remains authoritative. */
    val simulatedPedals: Boolean = false,
    val realReportedRawSpeedKmh: Double? = null,
    val transmissionPosition: TransmissionPosition = TransmissionPosition.DRIVE,
)

enum class ShiftDirection { NONE, UP, DOWN }

data class DrivetrainState(
    val rpm: Double = 0.0,
    val gear: Int = 0,
    /** Raw/public speed: real BYD telemetry or the documented SIM value after truncation. */
    val smoothedThrottle: Double = 0.0,
    val audioThrottle: Double = 0.0,
    val smoothedBrake: Double = 0.0,
    val engineLoad: Double = 0.0,
    val isShifting: Boolean = false,
    val shiftDirection: ShiftDirection = ShiftDirection.NONE,
    val shiftProgress: Double = 0.0,
    val shiftSerial: Long = 0L,
    val limiterActive: Boolean = false,
    /** Continuous presentation speed extrapolated from the raw source for audible RPM/pitch. */
    val presentationSpeedKmh: Double = 0.0,
    /** Velocity of [presentationSpeedKmh], not a raw BYD or documented model measurement. */
    val presentationAccelerationKmhPerSecond: Double = 0.0,
    /** Real BYD raw speed or documented SIM raw speed; never the continuous FMOD pitch input. */
    val realOrDocumentedRawSpeedKmh: Double = 0.0,
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
    private var previousInputWasSimulated: Boolean? = null

    val state: DrivetrainState get() = latestState

    internal fun updateAssettoPhysics(updated: AssettoPhysics) {
        physics = updated
        drivetrain = AssettoDrivetrain(updated).also { it.reset(engineRunning = true) }
        presentationSpeedEstimator.reset()
        bydSealSimulatedPedalsMotion.reset()
        simulatedPedalsGearCalibration = SimulatedPedalsGearCalibration.from(updated)
        previousInputWasSimulated = null
        latestState = buildState(updated, drivetrain!!.frame(), 0.0, 0.0, 0.0, false)
    }

    fun reset() {
        drivetrain?.reset(engineRunning = true)
        presentationSpeedEstimator.reset()
        bydSealSimulatedPedalsMotion.reset()
        previousInputWasSimulated = null
        physics?.let { latestState = buildState(it, drivetrain!!.frame(), 0.0, 0.0, 0.0, false) }
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val activePhysics = physics ?: return latestState
        val activeDrivetrain = drivetrain ?: return latestState
        val dt = deltaSeconds.coerceIn(0.001, 0.020)
        val realReportedRawSpeedKmh = input.realReportedRawSpeedKmh
            ?.coerceAtLeast(0.0)
            ?.let(::truncateRawSpeedKmh)
        val enteringSimulatedPedals = input.simulatedPedals && previousInputWasSimulated != true
        val documentedModelSeedSpeedKmh = if (enteringSimulatedPedals) {
            // Switching input sources must not teleport the virtual car back to zero. Prefer the
            // last continuous presentation speed (the audible REAL-pedal estimate), then fall
            // back to the current raw sample. This is transition continuity only; the Seal model
            // remains the sole SIMULATED road-speed authority after this frame.
            latestState.presentationSpeedKmh
                .takeIf { it.isFinite() && it > 0.0 }
                ?: realReportedRawSpeedKmh
                ?: 0.0
        } else {
            null
        }
        val simulatedMotionFrame = if (input.simulatedPedals) {
            bydSealSimulatedPedalsMotion.step(
                throttle = input.throttle,
                brake = input.brake,
                transmissionPosition = input.transmissionPosition,
                deltaSeconds = dt,
                initialDocumentedContinuousSpeedKmh = documentedModelSeedSpeedKmh,
            )
        } else {
            null
        }
        previousInputWasSimulated = input.simulatedPedals
        // The documented Seal model keeps a continuous value internally. Only its raw/public
        // representation is truncated, matching the real BYD telemetry contract. FMOD receives
        // the extrapolated presentation value below, never the integer speed directly.
        val documentedContinuousSpeedKmh = simulatedMotionFrame?.documentedContinuousSpeedKmh
        val documentedRawSpeedKmh = documentedContinuousSpeedKmh?.let(::truncateRawSpeedKmh)
        val realOrDocumentedRawSpeedKmh = realReportedRawSpeedKmh ?: documentedRawSpeedKmh
        val realExtrapolatedPresentationSpeedKmh = if (realReportedRawSpeedKmh != null) {
            presentationSpeedEstimator.update(
                measurementKmh = realReportedRawSpeedKmh,
                throttle = input.throttle,
                brake = input.brake,
                dt = dt,
                responseSeconds = 0.120,
            )
        } else {
            null
        }
        val documentedExtrapolatedPresentationSpeedKmh = if (
            realReportedRawSpeedKmh == null && documentedRawSpeedKmh != null
        ) {
            presentationSpeedEstimator.update(
                measurementKmh = documentedRawSpeedKmh,
                throttle = input.throttle,
                brake = input.brake,
                dt = dt,
                responseSeconds = 0.120,
            )
        } else {
            null
        }
        if (realReportedRawSpeedKmh == null && documentedRawSpeedKmh == null) {
            presentationSpeedEstimator.reset()
        }
        val realOrDocumentedExtrapolatedPresentationSpeedKmh = realExtrapolatedPresentationSpeedKmh
            ?: documentedExtrapolatedPresentationSpeedKmh
        val drivetrainRawSpeedKmh = realOrDocumentedRawSpeedKmh
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
            externalSpeedMetersPerSecond = drivetrainRawSpeedKmh?.div(3.6),
            simulatedPedalsGearCalibration = gearCalibration,
            deltaSeconds = dt,
        )
        val audiblePresentationSpeedKmh = realOrDocumentedExtrapolatedPresentationSpeedKmh
            ?: frame.speedMetersPerSecond * 3.6
        val audiblePresentationVelocityKmhPerSecond = if (
            realOrDocumentedExtrapolatedPresentationSpeedKmh != null
        ) {
            presentationSpeedEstimator.presentationVelocityKmhPerSecond
        } else {
            0.0
        }
        latestState = buildState(
            activePhysics = activePhysics,
            frame = frame,
            presentationSpeedKmh = audiblePresentationSpeedKmh,
            presentationAccelerationKmhPerSecond = audiblePresentationVelocityKmhPerSecond,
            realOrDocumentedRawSpeedKmh = realOrDocumentedRawSpeedKmh
                ?: truncateRawSpeedKmh(frame.speedMetersPerSecond * 3.6),
            // Both input sources use the same audible reconstruction. The documented SIM model
            // keeps a fractional value only for accurate integration; its truncated raw value
            // reaches the drivetrain just like real BYD telemetry.
            usePresentationRoadSpeed = realOrDocumentedRawSpeedKmh != null,
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
        realOrDocumentedRawSpeedKmh: Double,
        usePresentationRoadSpeed: Boolean,
        simulatedPedalsGearCalibration: SimulatedPedalsGearCalibration? = null,
    ): DrivetrainState {
        val audibleRpm = if (usePresentationRoadSpeed && frame.gear != 0 && presentationSpeedKmh > 0.01) {
            val wheelOmega = presentationSpeedKmh / 3.6 / drivenWheelRadius(activePhysics)
            val ratio = audibleRatioDuringShift(frame, activePhysics, simulatedPedalsGearCalibration)
            (wheelOmega * ratio * 60.0 / (2.0 * PI)).coerceIn(activePhysics.engine.idleRpm, activePhysics.engine.limiterRpm)
        } else {
            frame.rpm.coerceAtLeast(activePhysics.engine.idleRpm)
        }
        val redline = activePhysics.engine.shiftLightsRpm.maxOrNull()
            ?: activePhysics.engine.limiterRpm
        return DrivetrainState(
            rpm = audibleRpm,
            gear = frame.gear,
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
            realOrDocumentedRawSpeedKmh = realOrDocumentedRawSpeedKmh,
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

    /**
     * The drivetrain is uncoupled while a shift clutch profile runs, but the selected target gear
     * remains visible so shift events never expose an artificial gear 0. Blend its road-coupled
     * ratios over the authored shift interval for the audible path; this does not alter torque,
     * shift timing, or automatic decisions, it only prevents a synthetic pitch cliff.
     */
    private fun audibleRatioDuringShift(
        frame: AssettoDrivetrainFrame,
        activePhysics: AssettoPhysics,
        calibration: SimulatedPedalsGearCalibration?,
    ): Double {
        val target = frame.gear
        val targetRatio = abs(
            (calibration?.ratioForGear(target, activePhysics.drivetrain)
                ?: activePhysics.drivetrain.ratioForGear(target)) * activePhysics.drivetrain.finalDrive,
        )
        if (frame.shiftDirection == 0) return targetRatio

        val source = if (frame.shiftDirection > 0) target - 1 else target + 1
        if (source == 0) return targetRatio
        val sourceRatio = abs(
            (calibration?.ratioForGear(source, activePhysics.drivetrain)
                ?: activePhysics.drivetrain.ratioForGear(source)) * activePhysics.drivetrain.finalDrive,
        )
        return sourceRatio + (targetRatio - sourceRatio) * frame.shiftProgress.coerceIn(0.0, 1.0)
    }
}

internal data class ResolvedDriveInput(
    val throttle: Double,
    val brake: Double,
    /** Non-null only when the physical BYD reported a valid raw speed sample. */
    val realReportedRawSpeedKmh: Double?,
    val label: String,
    val usesSimulatedPedals: Boolean,
)

internal fun resolveDriveInput(
    mode: com.gabrielpc.enginesoundsimulator.drive.InputMode,
    telemetry: com.gabrielpc.enginesoundsimulator.telemetry.TelemetrySnapshot,
    simulatedPedalThrottle: Double,
    simulatedPedalBrake: Double,
): ResolvedDriveInput {
    val vehicleAvailable = telemetry.vehicleDriveSignalsAvailable()
    if (vehicleAvailable && mode == com.gabrielpc.enginesoundsimulator.drive.InputMode.RealPedals) {
        return ResolvedDriveInput(
            throttle = normalizeVehicleThrottlePercent(telemetry.accelerator.value!!),
            brake = (telemetry.brake.value!! / 100.0).coerceIn(0.0, 1.0),
            realReportedRawSpeedKmh = telemetry.speed.value
                ?.takeIf { telemetry.speed.isValid }
                ?.let(::truncateRawSpeedKmh),
            label = com.gabrielpc.enginesoundsimulator.drive.InputMode.RealPedals.displayName,
            usesSimulatedPedals = false,
        )
    }
    return ResolvedDriveInput(
        throttle = simulatedPedalThrottle.coerceIn(0.0, 1.0),
        brake = simulatedPedalBrake.coerceIn(0.0, 1.0),
        realReportedRawSpeedKmh = null,
        label = com.gabrielpc.enginesoundsimulator.drive.InputMode.SimulatedPedals.displayName,
        usesSimulatedPedals = true,
    )
}

internal fun normalizeVehicleThrottlePercent(percent: Double): Double =
    if (percent >= 99.0) 1.0 else (percent / 100.0).coerceIn(0.0, 1.0)

internal fun truncateRawSpeedKmh(sourceSpeedKmh: Double): Double =
    kotlin.math.floor(sourceSpeedKmh.coerceAtLeast(0.0))
