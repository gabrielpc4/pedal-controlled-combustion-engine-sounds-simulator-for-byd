package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.SyntheticRpmMode
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
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
    val maxTorqueNm: Double,
    val peakPowerKw: Double,
    val motorMaxRpm: Double,
    val motorReductionRatio: Double,
    val drivetrainEfficiency: Double,
    val frontPeakWheelTorqueNm: Double,
    val rearPeakWheelTorqueNm: Double,
    val tractionLimitMps2: Double,
    val vehicleMassKg: Double,
    val rotationalMassFactor: Double,
    val wheelRadiusMeters: Double,
    val dragAreaM2: Double,
    val rollingResistanceCoefficient: Double,
    val topSpeedKmh: Double,
    val syntheticRpmMode: SyntheticRpmMode,
    val syntheticRpmResponseSeconds: Double,
    /** Constant deceleration applied on lift-off while integrating virtual speed in SIM mode. */
    val simulatorCoastRegenMps2: Double,
    /** These ratios shape only the synthetic sound and tachometer. */
    val finalDrive: Double,
    val gearRatios: DoubleArray,
    /** X is normalized road speed; Y is normalized front-axle wheel torque. */
    val frontWheelTorqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_FRONT_WHEEL_TORQUE_CURVE,
    /** X is normalized road speed; Y is normalized rear-axle wheel torque. */
    val rearWheelTorqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_REAR_WHEEL_TORQUE_CURVE,
    /** X is pedal position; Y is requested motor torque. */
    val throttleCurve: List<CurvePoint> = EngineTuning.DEFAULT_THROTTLE_CURVE,
    val throttleAttackSeconds: Double = 0.120,
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
            maxTorqueNm = 670.0,
            peakPowerKw = 390.0,
            motorMaxRpm = 16_000.0,
            motorReductionRatio = 10.81,
            drivetrainEfficiency = 0.92,
            frontPeakWheelTorqueNm = 3_170.0,
            rearPeakWheelTorqueNm = 3_975.0,
            tractionLimitMps2 = 10.0,
            vehicleMassKg = 2_185.0,
            rotationalMassFactor = 1.10,
            wheelRadiusMeters = 0.347,
            dragAreaM2 = 0.504,
            rollingResistanceCoefficient = 0.010,
            topSpeedKmh = 180.0,
            syntheticRpmMode = SyntheticRpmMode.ROAD_COUPLED,
            syntheticRpmResponseSeconds = 0.035,
            simulatorCoastRegenMps2 = 0.50,
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
    /** Applies simulator-only coast regen when integrating virtual road speed. */
    val simulateCoastRegen: Boolean = false,
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
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
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
            integrateElectricVehicle(dt, applyCoastRegen = input.simulateCoastRegen)
        }

        shift?.let { updateShift(it, dt) }
        if (shift == null) secondsSinceShift += dt

        val pedalReleased = requestedThrottle <= PEDAL_RELEASE_THRESHOLD
        updateSyntheticRpm(dt)
        updateLimiterLatch()

        if (shift == null) {
            val emergencyUpshift = needsEmergencyUpshift()
            val promptLiftOffDownshift = pedalReleased &&
                currentGearIndex > 0 &&
                engineRpm <= downshiftThresholdRpm(currentGearIndex)
            if (emergencyUpshift || promptLiftOffDownshift || secondsSinceShift >= profile.shiftDwellSeconds) {
                chooseAutomaticShift(emergencyUpshift = emergencyUpshift)
            }
        }

        return snapshot()
    }

    private fun integrateElectricVehicle(dt: Double, applyCoastRegen: Boolean) {
        val previousSpeedMps = vehicleSpeedMps
        val axleTorque = axleWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val requestedWheelTorque = axleTorque.totalNm * filteredThrottle * brakeOverride
        val wheelOmega = vehicleSpeedMps / profile.wheelRadiusMeters
        val powerLimitedWheelTorque = if (wheelOmega < 1.0) {
            requestedWheelTorque
        } else {
            profile.peakPowerKw * 1_000.0 * profile.drivetrainEfficiency / wheelOmega
        }
        val deliveredWheelTorque = min(requestedWheelTorque, powerLimitedWheelTorque)
        val uncappedDriveForce = deliveredWheelTorque / profile.wheelRadiusMeters
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
        val coastRegenForce = if (
            applyCoastRegen &&
            filteredThrottle <= COAST_REGEN_THROTTLE_THRESHOLD &&
            filteredBrake <= COAST_REGEN_BRAKE_THRESHOLD &&
            vehicleSpeedMps > 0.05
        ) {
            profile.vehicleMassKg * profile.simulatorCoastRegenMps2
        } else {
            0.0
        }
        val acceleration = (driveForce - serviceBrakeForce - aerodynamicDrag - rollingResistance - coastRegenForce) /
            (profile.vehicleMassKg * profile.rotationalMassFactor)
        vehicleSpeedMps = (vehicleSpeedMps + acceleration * dt)
            .coerceIn(0.0, maximumVehicleSpeedMps())
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
        val minimumCruiseRpm = profile.idleRpm * 1.15
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
        engineRpm = when (profile.syntheticRpmMode) {
            SyntheticRpmMode.FREE_REV -> freeRevRpmTarget()
            SyntheticRpmMode.ROAD_COUPLED -> roadCoupledRpmTarget()
        }
        limiterLatched = false
        shift = null
        secondsSinceShift = 0.0
    }

    private fun updateSyntheticRpm(dt: Double) {
        val activeShift = shift
        val shiftRevTransition = activeShift?.gearChanged == true
        val targetRpm = if (shiftRevTransition) {
            activeShift.rpmTarget
        } else {
            when (profile.syntheticRpmMode) {
                SyntheticRpmMode.FREE_REV -> freeRevRpmTarget()
                SyntheticRpmMode.ROAD_COUPLED -> roadCoupledRpmTarget()
            }
        }
        engineRpm = approachExp(
            current = engineRpm,
            target = targetRpm,
            timeConstant = profile.syntheticRpmResponseSeconds,
            dt = dt,
        ).coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    private fun roadCoupledRpmTarget(): Double {
        val coupled = wheelRpmForSpeed(vehicleSpeedMps) *
            profile.gearRatios[currentGearIndex] * profile.finalDrive
        return (profile.idleRpm + coupled).coerceAtMost(profile.limiterRpm)
    }

    /** Throttle-driven revs independent of road speed, like a combustion engine in neutral. */
    private fun freeRevRpmTarget(): Double {
        val revSpan = profile.redlineRpm - profile.idleRpm
        return profile.idleRpm + filteredThrottle.coerceIn(0.0, 1.0) * revSpan
    }

    private fun syntheticRpmTarget(): Double {
        return when (profile.syntheticRpmMode) {
            SyntheticRpmMode.FREE_REV -> freeRevRpmTarget()
            SyntheticRpmMode.ROAD_COUPLED -> roadCoupledRpmTarget()
        }
    }

    private fun needsEmergencyUpshift(): Boolean {
        if (currentGearIndex >= profile.gearRatios.lastIndex) return false

        val emergencyThreshold = profile.redlineRpm * EMERGENCY_UPSHIFT_REDLINE_FRACTION
        if (engineRpm >= emergencyThreshold) return true

        if (profile.syntheticRpmMode == SyntheticRpmMode.FREE_REV) {
            return false
        }

        val projectedRpm = profile.idleRpm + wheelRpmForSpeed(vehicleSpeedMps) *
            profile.gearRatios[currentGearIndex] * profile.finalDrive
        return projectedRpm >= emergencyThreshold
    }

    private fun chooseAutomaticShift(
        emergencyUpshift: Boolean = false,
    ) {
        val canUpshift = currentGearIndex < profile.gearRatios.lastIndex
        val normalUpshift = engineRpm >= profile.upshiftRpm && filteredThrottle > 0.10
        if (canUpshift && (emergencyUpshift || normalUpshift)) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }

        if (currentGearIndex == 0) return
        val lowerRatio = profile.gearRatios[currentGearIndex - 1]
        val currentRatio = profile.gearRatios[currentGearIndex]
        val projectedLowerRpm = profile.idleRpm +
            (engineRpm - profile.idleRpm) * lowerRatio / currentRatio
        val roadProjectedLowerRpm = profile.idleRpm +
            wheelRpmForSpeed(vehicleSpeedMps) * lowerRatio * profile.finalDrive
        val maximumSafeDownshiftRpm = min(profile.upshiftRpm + 100.0, profile.redlineRpm * 0.98)
        val safeDownshift = projectedLowerRpm <= maximumSafeDownshiftRpm &&
            roadProjectedLowerRpm <= profile.limiterRpm + MAX_DOWNSHIFT_ROAD_OVERRUN_RPM
        val lowRpm = engineRpm <= downshiftThresholdRpm(currentGearIndex)
        val brakingDownshift = filteredBrake > 0.20 && engineRpm < 3_650.0
        val kickdown = filteredThrottle > 0.78 && engineRpm < 4_600.0
        if (safeDownshift && (lowRpm || brakingDownshift || kickdown)) {
            beginShift(
                targetGearIndex = currentGearIndex - 1,
                direction = ShiftDirection.DOWN,
            )
        }
    }

    private fun downshiftThresholdRpm(gearIndex: Int): Double =
        postUpshiftLandingRpm(profile, gearIndex)

    private fun beginShift(
        targetGearIndex: Int,
        direction: ShiftDirection,
    ) {
        val currentRatio = profile.gearRatios[currentGearIndex]
        val targetRatio = profile.gearRatios[targetGearIndex]
        shift = ActiveShift(
            targetGearIndex = targetGearIndex,
            direction = direction,
            elapsed = 0.0,
            duration = if (direction == ShiftDirection.UP) {
                profile.upshiftDurationSeconds
            } else {
                profile.downshiftDurationSeconds
            },
            rpmTarget = (profile.idleRpm +
                (engineRpm - profile.idleRpm) * targetRatio / currentRatio)
                .coerceIn(profile.idleRpm, profile.limiterRpm),
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

    private fun maximumVehicleSpeedMps(): Double {
        val configuredLimit = profile.topSpeedKmh / 3.6
        val motorSpeedLimit = profile.motorMaxRpm / profile.motorReductionRatio *
            (2.0 * PI * profile.wheelRadiusMeters) / 60.0
        return min(configuredLimit, motorSpeedLimit)
    }

    private fun snapshot(): DrivetrainState {
        val currentShift = shift
        val axleTorque = axleWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val peakWheelTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
        val wheelTorqueFraction = (axleTorque.totalNm / peakWheelTorque).coerceIn(0.0, 1.0)
        return DrivetrainState(
            rpm = engineRpm,
            gear = currentGearIndex + 1,
            speedKmh = vehicleSpeedMps * 3.6,
            smoothedThrottle = filteredThrottle,
            smoothedBrake = filteredBrake,
            engineLoad = (filteredThrottle * (0.35 + 0.65 * wheelTorqueFraction)).coerceIn(0.0, 1.0),
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
        val rpmTarget: Double,
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
        private const val MAX_DOWNSHIFT_ROAD_OVERRUN_RPM = 1_000.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val COAST_REGEN_THROTTLE_THRESHOLD = 0.02
        private const val COAST_REGEN_BRAKE_THRESHOLD = 0.02
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
    }
}

internal data class AxleWheelTorque(val frontNm: Double, val rearNm: Double) {
    val totalNm: Double get() = frontNm + rearNm
    val rearShare: Double get() = if (totalNm > 0.0) rearNm / totalNm else 0.0
}

/** RPM reached in [gearIndex] at the same road speed that triggered the preceding upshift. */
internal fun postUpshiftLandingRpm(profile: EngineProfile, gearIndex: Int): Double {
    if (gearIndex <= 0 || gearIndex > profile.gearRatios.lastIndex) {
        return profile.idleRpm
    }
    val previousRatio = profile.gearRatios[gearIndex - 1]
    val currentRatio = profile.gearRatios[gearIndex]
    return profile.idleRpm + (profile.upshiftRpm - profile.idleRpm) * currentRatio / previousRatio
}

/** Digitized axle-output envelope, evaluated against normalized road speed. */
internal fun axleWheelTorqueAtSpeed(profile: EngineProfile, speedKmh: Double): AxleWheelTorque {
    val normalizedSpeed = (speedKmh / profile.topSpeedKmh).coerceIn(0.0, 1.0)
    return AxleWheelTorque(
        frontNm = profile.frontPeakWheelTorqueNm * interpolateCurve(
            profile.frontWheelTorqueCurve,
            normalizedSpeed,
        ),
        rearNm = profile.rearPeakWheelTorqueNm * interpolateCurve(
            profile.rearWheelTorqueCurve,
            normalizedSpeed,
        ),
    )
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}
