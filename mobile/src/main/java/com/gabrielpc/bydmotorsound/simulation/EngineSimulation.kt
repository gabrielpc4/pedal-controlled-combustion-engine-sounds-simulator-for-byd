package com.gabrielpc.bydmotorsound.simulation

import com.gabrielpc.bydmotorsound.tuning.CurvePoint
import com.gabrielpc.bydmotorsound.tuning.EngineTuning
import com.gabrielpc.bydmotorsound.tuning.interpolateCurve
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class EngineProfile(
    val name: String,
    val cylinders: Int,
    val idleRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    val upshiftRpm: Double,
    val downshiftRpm: Double,
    val maxTorqueNm: Double,
    val peakPowerKw: Double,
    val motorMaxRpm: Double,
    val motorReductionRatio: Double,
    val drivetrainEfficiency: Double,
    val tractionLimitMps2: Double,
    val vehicleMassKg: Double,
    val wheelRadiusMeters: Double,
    val dragAreaM2: Double,
    val rollingResistanceCoefficient: Double,
    val topSpeedKmh: Double,
    val syntheticRpmResponseSeconds: Double,
    /** These ratios shape only the synthetic sound and tachometer. */
    val finalDrive: Double,
    val gearRatios: DoubleArray,
    /** X is normalized electric-motor speed; Y is normalized motor torque. */
    val torqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_TORQUE_CURVE,
    /** X is pedal position; Y is requested motor torque. */
    val throttleCurve: List<CurvePoint> = EngineTuning.DEFAULT_THROTTLE_CURVE,
    val throttleAttackSeconds: Double = 0.060,
    val throttleReleaseSeconds: Double = 0.090,
    val brakeResponseSeconds: Double = 0.055,
    val upshiftDurationSeconds: Double = 0.270,
    val downshiftDurationSeconds: Double = 0.340,
    val shiftDwellSeconds: Double = 0.450,
) {
    companion object {
        val APEX_V10 = EngineProfile(
            name = "Apex V10",
            cylinders = 10,
            idleRpm = 950.0,
            redlineRpm = 8_600.0,
            limiterRpm = 8_850.0,
            upshiftRpm = 8_250.0,
            downshiftRpm = 2_250.0,
            maxTorqueNm = 670.0,
            peakPowerKw = 390.0,
            motorMaxRpm = 16_000.0,
            motorReductionRatio = 10.81,
            drivetrainEfficiency = 0.92,
            tractionLimitMps2 = 8.0,
            vehicleMassKg = 2_185.0,
            wheelRadiusMeters = 0.347,
            dragAreaM2 = 0.504,
            rollingResistanceCoefficient = 0.010,
            topSpeedKmh = 180.0,
            syntheticRpmResponseSeconds = 0.035,
            finalDrive = 3.82,
            gearRatios = doubleArrayOf(3.14, 2.10, 1.57, 1.24, 1.02, 0.84, 0.69),
        )
    }
}

data class DriverInput(
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    /** When present, road speed comes from the real car rather than this virtual vehicle. */
    val externalSpeedKmh: Double? = null,
)

enum class ShiftDirection { NONE, UP, DOWN }

data class DrivetrainState(
    val rpm: Double,
    val gear: Int,
    val speedKmh: Double,
    val smoothedThrottle: Double,
    val smoothedBrake: Double,
    val engineLoad: Double,
    val isShifting: Boolean,
    val shiftDirection: ShiftDirection,
    val shiftProgress: Double,
    val shiftSerial: Long,
    val limiterActive: Boolean,
    val accelerationMps2: Double,
)

/**
 * Fixed-step Seal Performance longitudinal model with a synthetic multi-gear sound layer.
 *
 * Road acceleration comes from the electric motors' torque/power envelope, fixed reduction,
 * vehicle mass, rolling resistance and aerodynamic drag. The displayed RPM and gears never feed
 * back into wheel torque, so a sound shift cannot add combustion-engine lag or interrupt drive.
 */
class EngineSimulation(
    initialProfile: EngineProfile = EngineProfile.APEX_V10,
) {
    var profile: EngineProfile = initialProfile
        private set
    private var engineRpm = profile.idleRpm
    private var vehicleSpeedMps = 0.0
    private var currentGearIndex = 0
    private var filteredThrottle = 0.0
    private var filteredBrake = 0.0
    private var shift: ActiveShift? = null
    private var shiftSerial = 0L
    private var secondsSinceShift = 10.0
    private var limiterLatched = false
    private var externalSpeedActive = false
    private var lastAcceleration = 0.0

    val state: DrivetrainState
        get() = snapshot()

    fun updateProfile(updated: EngineProfile) {
        profile = updated
        currentGearIndex = currentGearIndex.coerceIn(0, profile.gearRatios.lastIndex)
        engineRpm = engineRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(profile.topSpeedKmh / 3.6)
    }

    fun reset() {
        engineRpm = profile.idleRpm
        vehicleSpeedMps = 0.0
        currentGearIndex = 0
        filteredThrottle = 0.0
        filteredBrake = 0.0
        shift = null
        shiftSerial = 0L
        secondsSinceShift = 10.0
        limiterLatched = false
        externalSpeedActive = false
        lastAcceleration = 0.0
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val dt = deltaSeconds.coerceIn(1.0 / 1_000.0, 1.0 / 20.0)
        val requestedThrottle = interpolateCurve(
            profile.throttleCurve,
            input.throttle.coerceIn(0.0, 1.0),
        )
        filteredThrottle = approachExp(
            filteredThrottle,
            requestedThrottle,
            if (requestedThrottle > filteredThrottle) {
                profile.throttleAttackSeconds
            } else {
                profile.throttleReleaseSeconds
            },
            dt,
        )
        filteredBrake = approachExp(
            filteredBrake,
            input.brake.coerceIn(0.0, 1.0),
            profile.brakeResponseSeconds,
            dt,
        )

        val externalMps = input.externalSpeedKmh?.coerceAtLeast(0.0)?.div(3.6)
        if (externalMps != null) {
            applyExternalSpeed(externalMps, dt)
        } else {
            externalSpeedActive = false
            integrateElectricVehicle(dt)
        }

        shift?.let { updateShift(it, dt) }
        if (shift == null) secondsSinceShift += dt

        updateSyntheticRpm(dt)
        updateLimiterLatch()

        if (shift == null) {
            val emergencyUpshift = needsEmergencyUpshift()
            if (emergencyUpshift || secondsSinceShift >= profile.shiftDwellSeconds) {
                chooseAutomaticShift(emergencyUpshift)
            }
        }

        return snapshot()
    }

    private fun integrateElectricVehicle(dt: Double) {
        val previousSpeedMps = vehicleSpeedMps
        val motorRpm = motorRpmForSpeed(vehicleSpeedMps)
        val availableTorque = motorTorqueAtRpm(profile, motorRpm)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val driveTorque = availableTorque * filteredThrottle * brakeOverride
        val uncappedDriveForce = driveTorque * profile.motorReductionRatio *
            profile.drivetrainEfficiency / profile.wheelRadiusMeters
        val driveForce = min(
            uncappedDriveForce,
            profile.vehicleMassKg * profile.tractionLimitMps2 * filteredThrottle,
        )
        val serviceBrakeForce = filteredBrake * profile.vehicleMassKg * MAX_SERVICE_BRAKE_MPS2
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 * vehicleSpeedMps.pow(2)
        val rollingResistance = if (vehicleSpeedMps > 0.05 || driveForce > 0.0) {
            profile.vehicleMassKg * GRAVITY_MPS2 * profile.rollingResistanceCoefficient
        } else {
            0.0
        }
        val acceleration = (driveForce - serviceBrakeForce - aerodynamicDrag - rollingResistance) /
            profile.vehicleMassKg
        vehicleSpeedMps = (vehicleSpeedMps + acceleration * dt)
            .coerceIn(0.0, profile.topSpeedKmh / 3.6)
        if (vehicleSpeedMps < 0.04 && driveForce <= rollingResistance + serviceBrakeForce) {
            vehicleSpeedMps = 0.0
        }
        lastAcceleration = ((vehicleSpeedMps - previousSpeedMps) / max(dt, 0.001))
            .coerceIn(-MAX_REPORTED_ACCELERATION, MAX_REPORTED_ACCELERATION)
    }

    private fun applyExternalSpeed(externalSpeedMps: Double, dt: Double) {
        val previousSpeedMps = vehicleSpeedMps
        vehicleSpeedMps = externalSpeedMps

        if (!externalSpeedActive) {
            synchronizeToExternalSpeed()
            lastAcceleration = 0.0
        } else {
            val measuredAcceleration = ((vehicleSpeedMps - previousSpeedMps) / max(dt, 0.001))
                .coerceIn(-MAX_REPORTED_ACCELERATION, MAX_REPORTED_ACCELERATION)
            lastAcceleration = approachExp(
                current = lastAcceleration,
                target = measuredAcceleration,
                timeConstant = EXTERNAL_ACCELERATION_FILTER_SECONDS,
                dt = dt,
            ).coerceIn(-MAX_REPORTED_ACCELERATION, MAX_REPORTED_ACCELERATION)
        }
        externalSpeedActive = true
    }

    /** A live vehicle can connect while already travelling, so start in a safe synthetic gear. */
    private fun synchronizeToExternalSpeed() {
        val wheelRpm = wheelRpmForSpeed(vehicleSpeedMps)
        val maximumSynchronizationRpm = profile.redlineRpm * 0.92
        val minimumCruiseRpm = max(profile.idleRpm * 1.15, profile.downshiftRpm * 0.90)
        val safeGears = profile.gearRatios.indices.filter { gearIndex ->
            profile.idleRpm + wheelRpm * profile.gearRatios[gearIndex] * profile.finalDrive <=
                maximumSynchronizationRpm
        }
        val cruiseGears = safeGears.filter { gearIndex ->
            profile.idleRpm + wheelRpm * profile.gearRatios[gearIndex] * profile.finalDrive >=
                minimumCruiseRpm
        }
        currentGearIndex = cruiseGears.lastOrNull()
            ?: safeGears.firstOrNull()
            ?: profile.gearRatios.lastIndex
        engineRpm = syntheticRpmTarget()
        limiterLatched = false
        shift = null
        secondsSinceShift = 0.0
    }

    private fun updateSyntheticRpm(dt: Double) {
        engineRpm = approachExp(
            current = engineRpm,
            target = syntheticRpmTarget(),
            timeConstant = profile.syntheticRpmResponseSeconds,
            dt = dt,
        ).coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    private fun syntheticRpmTarget(): Double {
        val coupled = wheelRpmForSpeed(vehicleSpeedMps) *
            profile.gearRatios[currentGearIndex] * profile.finalDrive
        return (profile.idleRpm + coupled).coerceAtMost(profile.limiterRpm)
    }

    private fun needsEmergencyUpshift(): Boolean {
        if (currentGearIndex >= profile.gearRatios.lastIndex) return false
        val projectedRpm = profile.idleRpm + wheelRpmForSpeed(vehicleSpeedMps) *
            profile.gearRatios[currentGearIndex] * profile.finalDrive
        return projectedRpm >= profile.redlineRpm * EMERGENCY_UPSHIFT_REDLINE_FRACTION ||
            engineRpm >= profile.redlineRpm * EMERGENCY_UPSHIFT_REDLINE_FRACTION
    }

    private fun chooseAutomaticShift(emergencyUpshift: Boolean = false) {
        val canUpshift = currentGearIndex < profile.gearRatios.lastIndex
        val normalUpshift = engineRpm >= profile.upshiftRpm && filteredThrottle > 0.10
        if (canUpshift && (emergencyUpshift || normalUpshift)) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }

        if (currentGearIndex == 0) return
        val lowerRatio = profile.gearRatios[currentGearIndex - 1]
        val wheelRpm = wheelRpmForSpeed(vehicleSpeedMps)
        val projectedLowerRpm = profile.idleRpm + wheelRpm * lowerRatio * profile.finalDrive
        val safeDownshift = projectedLowerRpm < profile.redlineRpm * 0.94
        val lowRpm = engineRpm < profile.downshiftRpm
        val brakingDownshift = filteredBrake > 0.20 && engineRpm < 3_650.0
        val kickdown = filteredThrottle > 0.78 && engineRpm < 4_600.0
        if (safeDownshift && (lowRpm || brakingDownshift || kickdown)) {
            beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
        }
    }

    private fun beginShift(targetGearIndex: Int, direction: ShiftDirection) {
        shift = ActiveShift(
            targetGearIndex = targetGearIndex,
            direction = direction,
            elapsed = 0.0,
            duration = if (direction == ShiftDirection.UP) {
                profile.upshiftDurationSeconds
            } else {
                profile.downshiftDurationSeconds
            },
            gearChanged = false,
        )
        secondsSinceShift = 0.0
        shiftSerial += 1
    }

    private fun updateShift(active: ActiveShift, dt: Double) {
        active.elapsed += dt
        val progress = (active.elapsed / active.duration).coerceIn(0.0, 1.0)
        if (!active.gearChanged && progress >= 0.38) {
            currentGearIndex = active.targetGearIndex
            active.gearChanged = true
        }
        if (progress >= 1.0) {
            shift = null
            secondsSinceShift = 0.0
        }
    }

    private fun updateLimiterLatch() {
        limiterLatched = if (limiterLatched) {
            engineRpm > profile.limiterRpm - LIMITER_RELEASE_HYSTERESIS_RPM
        } else {
            engineRpm >= profile.limiterRpm - LIMITER_TRIGGER_MARGIN_RPM
        }
    }

    private fun wheelRpmForSpeed(speedMps: Double): Double =
        speedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0

    private fun motorRpmForSpeed(speedMps: Double): Double =
        wheelRpmForSpeed(speedMps) * profile.motorReductionRatio

    private fun snapshot(): DrivetrainState {
        val currentShift = shift
        val motorTorqueFraction = interpolateCurve(
            profile.torqueCurve,
            (motorRpmForSpeed(vehicleSpeedMps) / profile.motorMaxRpm).coerceIn(0.0, 1.0),
        )
        return DrivetrainState(
            rpm = engineRpm,
            gear = currentGearIndex + 1,
            speedKmh = vehicleSpeedMps * 3.6,
            smoothedThrottle = filteredThrottle,
            smoothedBrake = filteredBrake,
            engineLoad = (filteredThrottle * (0.35 + 0.65 * motorTorqueFraction)).coerceIn(0.0, 1.0),
            isShifting = currentShift != null,
            shiftDirection = currentShift?.direction ?: ShiftDirection.NONE,
            shiftProgress = currentShift?.let { (it.elapsed / it.duration).coerceIn(0.0, 1.0) } ?: 0.0,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            accelerationMps2 = lastAcceleration,
        )
    }

    private data class ActiveShift(
        val targetGearIndex: Int,
        val direction: ShiftDirection,
        var elapsed: Double,
        val duration: Double,
        var gearChanged: Boolean,
    )

    companion object {
        private const val AIR_DENSITY_KG_M3 = 1.225
        private const val GRAVITY_MPS2 = 9.81
        private const val MAX_SERVICE_BRAKE_MPS2 = 11.2
        private const val EMERGENCY_UPSHIFT_REDLINE_FRACTION = 0.97
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val MAX_REPORTED_ACCELERATION = 15.0
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
    }
}

/** Torque available from the editable motor curve, bounded by the configured peak-power envelope. */
internal fun motorTorqueAtRpm(profile: EngineProfile, motorRpm: Double): Double {
    val rpm = motorRpm.coerceAtLeast(0.0)
    val curveTorque = profile.maxTorqueNm * interpolateCurve(
        profile.torqueCurve,
        (rpm / profile.motorMaxRpm).coerceIn(0.0, 1.0),
    )
    val powerLimitedTorque = if (rpm < 1.0) {
        profile.maxTorqueNm
    } else {
        profile.peakPowerKw * 9_549.0 / rpm
    }
    return min(curveTorque, powerLimitedTorque).coerceAtLeast(0.0)
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}
