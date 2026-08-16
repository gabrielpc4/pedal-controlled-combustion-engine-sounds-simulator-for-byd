package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class EngineProfile(
    val name: String,
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
    val driveRpmAccelerationPerSecond: Double,
    val liftOffRpmDecelerationPerSecond: Double,
    val brakeRpmDecelerationPerSecond: Double,
    /** Constant deceleration applied on lift-off while integrating virtual speed in SIM mode. */
    val simulatorCoastRegenMps2: Double,
    /** These ratios shape only the sample playback RPM and tachometer. */
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
    val shiftDwellSeconds: Double = 0.150,
) {
    companion object {
        private val bank = EngineSampleProfiles.default
        val SAMPLE_BANK_ENGINE = EngineProfile(
            name = bank.displayName,
            idleRpm = bank.idleRpm,
            redlineRpm = bank.redlineRpm,
            limiterRpm = bank.limiterRpm,
            upshiftRpm = bank.upshiftRpm,
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
            topSpeedKmh = 190.0,
            driveRpmAccelerationPerSecond = 6_500.0,
            liftOffRpmDecelerationPerSecond = 5_500.0,
            brakeRpmDecelerationPerSecond = 8_500.0,
            simulatorCoastRegenMps2 = 2.50,
            gearRatios = bank.gearRatios.toDoubleArray(),
            upshiftDurationSeconds = bank.upshiftDurationSeconds,
            downshiftDurationSeconds = bank.downshiftDurationSeconds,
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
    val transmissionPosition: TransmissionPosition = TransmissionPosition.DRIVE,
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
    val rpmPowerFraction: Double,
    val rpmPositiveForcePerSecond: Double,
    val rpmNegativeForcePerSecond: Double,
)

/**
 * Fixed-step Seal Performance longitudinal model with an independent fake engine tachometer.
 *
 * Road acceleration comes from the electric motors' torque/power envelope, fixed reduction,
 * vehicle mass, rolling resistance and aerodynamic drag. In Drive, pedal force integrates RPM as
 * an independent state; road speed only selects the Seal propulsion-power envelope that scales
 * positive RPM force. Speed can never target, floor, synchronize, or otherwise dictate RPM.
 */
class EngineSimulation(
    initialProfile: EngineProfile = EngineProfile.SAMPLE_BANK_ENGINE,
) {
    var profile: EngineProfile = initialProfile
        private set
    private var engineRpm = profile.idleRpm
    private var vehicleSpeedMps = 0.0
    private var currentGearIndex = 0
    private var filteredThrottle = 0.0
    private var filteredGaugeThrottle = 0.0
    private var filteredBrake = 0.0
    private var shift: ActiveShift? = null
    private var shiftSerial = 0L
    private var secondsSinceShift = 10.0
    private var limiterLatched = false
    private var externalSpeedActive = false
    private var lastAcceleration = 0.0
    private var lastRpmPowerFraction = 0.0
    private var lastRpmPositiveForce = 0.0
    private var lastRpmNegativeForce = 0.0

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
        filteredGaugeThrottle = 0.0
        filteredBrake = 0.0
        shift = null
        shiftSerial = 0L
        secondsSinceShift = 10.0
        limiterLatched = false
        externalSpeedActive = false
        lastAcceleration = 0.0
        lastRpmPowerFraction = 0.0
        lastRpmPositiveForce = 0.0
        lastRpmNegativeForce = 0.0
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val dt = deltaSeconds.coerceIn(1.0 / 1_000.0, 1.0 / 20.0)
        val rawThrottle = input.throttle.coerceIn(0.0, 1.0)
        val requestedThrottle = interpolateCurve(
            profile.throttleCurve,
            rawThrottle,
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
        filteredGaugeThrottle = approachExp(
            filteredGaugeThrottle,
            rawThrottle,
            if (rawThrottle > filteredGaugeThrottle) profile.throttleAttackSeconds else profile.throttleReleaseSeconds,
            dt,
        )

        val externalMps = input.externalSpeedKmh?.coerceAtLeast(0.0)?.div(3.6)
        if (externalMps != null) {
            applyExternalSpeed(externalMps, dt)
        } else {
            externalSpeedActive = false
            integrateElectricVehicle(
                dt = dt,
                applyCoastRegen = input.simulateCoastRegen,
                transmissionPosition = input.transmissionPosition,
            )
            if (input.transmissionPosition == TransmissionPosition.PARK) {
                vehicleSpeedMps = 0.0
                lastAcceleration = 0.0
            }
        }

        shift?.let { updateShift(it, dt) }
        if (shift == null) secondsSinceShift += dt

        val pedalReleased = rawThrottle <= PEDAL_RELEASE_THRESHOLD
        updateSampleRpm(dt, input.transmissionPosition, pedalReleased)
        updateLimiterLatch()

        if (shift == null && input.transmissionPosition == TransmissionPosition.DRIVE) {
            val promptLiftOffDownshift = pedalReleased &&
                currentGearIndex > 0 &&
                engineRpm <= downshiftThresholdRpm(currentGearIndex)
            if (promptLiftOffDownshift || secondsSinceShift >= profile.shiftDwellSeconds) {
                chooseAutomaticShift(allowDownshift = pedalReleased || filteredBrake > 0.05)
            }
        }

        return snapshot()
    }

    private fun integrateElectricVehicle(
        dt: Double,
        applyCoastRegen: Boolean,
        transmissionPosition: TransmissionPosition,
    ) {
        val previousSpeedMps = vehicleSpeedMps
        val axleTorque = axleWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val throttleConnected = transmissionPosition == TransmissionPosition.DRIVE
        val requestedWheelTorque = if (throttleConnected) {
            axleTorque.totalNm * filteredThrottle * brakeOverride
        } else {
            0.0
        }
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

    private fun applyExternalSpeed(
        externalSpeedMps: Double,
        dt: Double,
    ) {
        val previousSpeedMps = vehicleSpeedMps
        vehicleSpeedMps = externalSpeedMps

        if (!externalSpeedActive) {
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

    private fun updateSampleRpm(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        pedalReleased: Boolean,
    ) {
        val activeShift = shift
        val shiftRevTransition = activeShift?.gearChanged == true
        if (shiftRevTransition) {
            lastRpmPowerFraction = propulsionPowerFractionAtSpeed(profile, vehicleSpeedMps * 3.6)
            lastRpmPositiveForce = 0.0
            lastRpmNegativeForce = 0.0
            engineRpm = approachExp(
                current = engineRpm,
                target = activeShift.rpmTarget,
                timeConstant = (activeShift.duration * SHIFT_RPM_RESPONSE_FRACTION).coerceAtLeast(0.012),
                dt = dt,
            ).coerceIn(profile.idleRpm, profile.limiterRpm)
            return
        }

        if (transmissionPosition == TransmissionPosition.DRIVE) {
            val powerFraction = propulsionPowerFractionAtSpeed(profile, vehicleSpeedMps * 3.6)
            val powerForce = if (!pedalReleased && !limiterLatched) {
                profile.driveRpmAccelerationPerSecond *
                    filteredGaugeThrottle *
                    powerFraction *
                    (1.0 - filteredBrake)
            } else {
                0.0
            }
            val liftOffForce = if (pedalReleased) profile.liftOffRpmDecelerationPerSecond else 0.0
            val brakeForce = profile.brakeRpmDecelerationPerSecond * filteredBrake
            val limiterCutForce = if (limiterLatched) LIMITER_CUT_RPM_DECELERATION_PER_SECOND else 0.0
            val negativeForce = liftOffForce + brakeForce + limiterCutForce
            lastRpmPowerFraction = powerFraction
            lastRpmPositiveForce = powerForce
            lastRpmNegativeForce = negativeForce
            engineRpm = (engineRpm + (powerForce - negativeForce) * dt)
                .coerceIn(profile.idleRpm, profile.limiterRpm)
            return
        }

        lastRpmPowerFraction = 0.0
        lastRpmPositiveForce = 0.0
        lastRpmNegativeForce = 0.0
        val targetRpm = freeRevRpmTarget()
        engineRpm = approachExp(
            current = engineRpm,
            target = targetRpm,
            timeConstant = if (targetRpm >= engineRpm) {
                NEUTRAL_REV_UP_RESPONSE_SECONDS
            } else {
                NEUTRAL_REV_DOWN_RESPONSE_SECONDS
            },
            dt = dt,
        ).coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    /** Throttle-driven revs independent of road speed, like a combustion engine in neutral. */
    private fun freeRevRpmTarget(): Double {
        val revSpan = profile.redlineRpm - profile.idleRpm
        return profile.idleRpm + filteredThrottle.coerceIn(0.0, 1.0) * revSpan
    }

    private fun chooseAutomaticShift(
        allowDownshift: Boolean,
    ) {
        val canUpshift = currentGearIndex < profile.gearRatios.lastIndex
        val normalUpshift = engineRpm >= profile.upshiftRpm && filteredThrottle > 0.10
        if (canUpshift && normalUpshift) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }

        if (currentGearIndex == 0 || !allowDownshift) return
        val lowerRatio = profile.gearRatios[currentGearIndex - 1]
        val currentRatio = profile.gearRatios[currentGearIndex]
        val projectedLowerRpm = profile.idleRpm +
            (engineRpm - profile.idleRpm) * lowerRatio / currentRatio
        val maximumSafeDownshiftRpm = min(profile.upshiftRpm + 100.0, profile.redlineRpm * 0.98)
        val safeDownshift = projectedLowerRpm <= maximumSafeDownshiftRpm
        val lowRpm = engineRpm <= downshiftThresholdRpm(currentGearIndex)
        if (safeDownshift && lowRpm) {
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
            rpmPowerFraction = lastRpmPowerFraction,
            rpmPositiveForcePerSecond = lastRpmPositiveForce,
            rpmNegativeForcePerSecond = lastRpmNegativeForce,
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
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val LIMITER_CUT_RPM_DECELERATION_PER_SECOND = 4_000.0
        private const val MAX_REPORTED_ACCELERATION = 15.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val COAST_REGEN_THROTTLE_THRESHOLD = 0.02
        private const val COAST_REGEN_BRAKE_THRESHOLD = 0.02
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
        private const val SHIFT_RPM_RESPONSE_FRACTION = 0.18
        /** Neutral/Park rev-up: engine inertia spooling with no wheel load. */
        private const val NEUTRAL_REV_UP_RESPONSE_SECONDS = 0.55
        /** Neutral/Park rev-down: coasting back toward idle after lift-off. */
        private const val NEUTRAL_REV_DOWN_RESPONSE_SECONDS = 0.90
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

/** Digitized A2MAC1 axle curves were sampled against a 180 km/h chart; keep that reference for physics. */
internal const val TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH = 180.0

/** Digitized axle-output envelope, evaluated against normalized road speed. */
internal fun axleWheelTorqueAtSpeed(profile: EngineProfile, speedKmh: Double): AxleWheelTorque {
    val normalizedSpeed = (speedKmh / TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH).coerceIn(0.0, 1.0)
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

/**
 * Normalized positive force available to the fake tachometer at a road speed.
 *
 * Above launch this is delivered wheel power divided by the configured peak wheel power. At zero
 * speed physical power is necessarily zero despite maximum EV torque, so the first 30 km/h blend
 * in the measured torque envelope. This prevents a mathematically correct but unusable tachometer
 * that cannot begin revving from rest.
 */
internal fun propulsionPowerFractionAtSpeed(profile: EngineProfile, speedKmh: Double): Double {
    val cleanSpeedKmh = speedKmh.coerceIn(0.0, profile.topSpeedKmh)
    val speedMps = cleanSpeedKmh / 3.6
    val wheelOmega = speedMps / profile.wheelRadiusMeters
    val axleTorque = axleWheelTorqueAtSpeed(profile, cleanSpeedKmh).totalNm
    val peakAxleTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
    val peakWheelPowerWatts = profile.peakPowerKw * 1_000.0 * profile.drivetrainEfficiency
    val powerLimitedTorque = if (wheelOmega < 1.0) {
        axleTorque
    } else {
        peakWheelPowerWatts / wheelOmega
    }
    val deliveredTorque = min(axleTorque, powerLimitedTorque)
    val powerFraction = if (peakWheelPowerWatts <= 0.0) {
        0.0
    } else {
        deliveredTorque * wheelOmega / peakWheelPowerWatts
    }
    val launchBlend = (1.0 - cleanSpeedKmh / LAUNCH_POWER_BRIDGE_END_KMH).coerceIn(0.0, 1.0)
    val launchTorqueFraction = if (peakAxleTorque <= 0.0) 0.0 else axleTorque / peakAxleTorque
    return max(powerFraction, launchTorqueFraction * launchBlend).coerceIn(0.0, 1.0)
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}

private const val LAUNCH_POWER_BRIDGE_END_KMH = 30.0
