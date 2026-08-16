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
    /** Full-pedal launches rapidly climb to this fun, audible part of the sample bank. */
    val fullThrottleSweetSpotRpm: Double = 5_200.0,
    /** Positive RPM force while full pedal is below [fullThrottleSweetSpotRpm]. */
    val fullThrottleKickRpmPerSecond: Double = 30_000.0,
    /** Retained for the real-car road model; SIM speed follows the fun tach directly. */
    val simulatorCoastRegenMps2: Double,
    /** Legacy physical-model data; direct-tach mode never selects or applies a ratio. */
    val gearRatios: DoubleArray,
    /** X is normalized road speed; Y is normalized front-axle wheel torque. */
    val frontWheelTorqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_FRONT_WHEEL_TORQUE_CURVE,
    /** X is normalized road speed; Y is normalized rear-axle wheel torque. */
    val rearWheelTorqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_REAR_WHEEL_TORQUE_CURVE,
    /** X is pedal position; Y is requested motor torque. */
    val throttleCurve: List<CurvePoint> = EngineTuning.DEFAULT_THROTTLE_CURVE,
    /** X is normalized fake RPM; Y is the positive tach-force multiplier. */
    val rpmProgressionCurve: List<CurvePoint> = EngineTuning.DEFAULT_RPM_PROGRESSION_CURVE,
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
            liftOffRpmDecelerationPerSecond = 1_000.0,
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
    val rpmProgressionFraction: Double,
    val rpmPositiveForcePerSecond: Double,
    val rpmNegativeForcePerSecond: Double,
)

/**
 * Fixed-step Seal Performance longitudinal model with an independent fake engine tachometer.
 *
 * External road speed stays external. In SIM mode, Drive is deliberately a direct, playful tach:
 * pedal force integrates fake RPM, full pedal launches to a sweet spot, and the displayed speed
 * follows that tach. Virtual gears are unlimited presentation events with no ratio stack and no
 * effect on road force.
 */
class EngineSimulation(
    initialProfile: EngineProfile = EngineProfile.SAMPLE_BANK_ENGINE,
) {
    var profile: EngineProfile = initialProfile
        private set
    private var engineRpm = profile.idleRpm
    private var vehicleSpeedMps = 0.0
    private var filteredThrottle = 0.0
    private var filteredGaugeThrottle = 0.0
    private var filteredBrake = 0.0
    private var virtualGear = 1
    private var activeShift: ActiveShift? = null
    private var shiftSerial = 0L
    private var secondsSinceShift = 10.0
    private var limiterLatched = false
    private var externalSpeedActive = false
    private var lastAcceleration = 0.0
    private var lastRpmProgressionFraction = 0.0
    private var lastRpmPositiveForce = 0.0
    private var lastRpmNegativeForce = 0.0

    val state: DrivetrainState
        get() = snapshot()

    fun updateProfile(updated: EngineProfile) {
        profile = updated
        engineRpm = engineRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
    }

    fun reset() {
        engineRpm = profile.idleRpm
        vehicleSpeedMps = 0.0
        filteredThrottle = 0.0
        filteredGaugeThrottle = 0.0
        filteredBrake = 0.0
        virtualGear = 1
        activeShift = null
        shiftSerial = 0L
        secondsSinceShift = 10.0
        limiterLatched = false
        externalSpeedActive = false
        lastAcceleration = 0.0
        lastRpmProgressionFraction = 0.0
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
        } else if (!input.simulateCoastRegen) {
            externalSpeedActive = false
            integrateElectricVehicle(
                dt = dt,
                transmissionPosition = input.transmissionPosition,
            )
            if (input.transmissionPosition == TransmissionPosition.PARK) {
                vehicleSpeedMps = 0.0
                lastAcceleration = 0.0
            }
        }

        val pedalReleased = rawThrottle <= PEDAL_RELEASE_THRESHOLD
        updateSampleRpm(
            dt = dt,
            transmissionPosition = input.transmissionPosition,
            pedalReleased = pedalReleased,
            fullPedal = rawThrottle >= FULL_PEDAL_THRESHOLD,
        )
        updateLimiterLatch()
        if (activeShift == null && input.transmissionPosition == TransmissionPosition.DRIVE) {
            chooseVirtualShift(pedalReleased)
        }
        if (externalMps == null && input.simulateCoastRegen) {
            synchronizeSimulatorSpeedToTach(dt, input.transmissionPosition)
        }

        return snapshot()
    }

    private fun integrateElectricVehicle(
        dt: Double,
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
        val acceleration = (driveForce - serviceBrakeForce - aerodynamicDrag - rollingResistance) /
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
        fullPedal: Boolean,
    ) {
        activeShift?.let { shift ->
            lastRpmProgressionFraction = rpmProgressionFractionAtRpm(profile, engineRpm)
            lastRpmPositiveForce = 0.0
            lastRpmNegativeForce = 0.0
            engineRpm = approachExp(
                current = engineRpm,
                target = shift.targetRpm,
                timeConstant = (shift.durationSeconds * SHIFT_RPM_RESPONSE_FRACTION).coerceAtLeast(0.012),
                dt = dt,
            ).coerceIn(profile.idleRpm, profile.limiterRpm)
            shift.elapsedSeconds += dt
            if (shift.elapsedSeconds >= shift.durationSeconds) {
                activeShift = null
                secondsSinceShift = 0.0
            }
            return
        }
        secondsSinceShift += dt
        if (transmissionPosition == TransmissionPosition.DRIVE) {
            val progressionFraction = rpmProgressionFractionAtRpm(profile, engineRpm)
            val fullPedalKick = fullPedal &&
                engineRpm < profile.fullThrottleSweetSpotRpm
            val positiveForce = if (fullPedalKick && !limiterLatched) {
                profile.fullThrottleKickRpmPerSecond
            } else if (!pedalReleased && !limiterLatched) {
                profile.driveRpmAccelerationPerSecond *
                    filteredGaugeThrottle *
                    progressionFraction *
                    (1.0 - filteredBrake)
            } else {
                0.0
            }
            val liftOffForce = if (pedalReleased) profile.liftOffRpmDecelerationPerSecond else 0.0
            val brakeForce = profile.brakeRpmDecelerationPerSecond * filteredBrake
            val limiterCutForce = if (limiterLatched) LIMITER_CUT_RPM_DECELERATION_PER_SECOND else 0.0
            val negativeForce = liftOffForce + brakeForce + limiterCutForce
            lastRpmProgressionFraction = progressionFraction
            lastRpmPositiveForce = positiveForce
            lastRpmNegativeForce = negativeForce
            engineRpm = (engineRpm + (positiveForce - negativeForce) * dt)
                .coerceIn(profile.idleRpm, profile.limiterRpm)
            return
        }

        lastRpmProgressionFraction = 0.0
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

    /** Unlimited presentation shifts: only RPM/audio/UI change, never ratios or wheel force. */
    private fun chooseVirtualShift(pedalReleased: Boolean) {
        if (secondsSinceShift < profile.shiftDwellSeconds) return
        if (!pedalReleased && filteredThrottle > SHIFT_THROTTLE_THRESHOLD && engineRpm >= profile.upshiftRpm) {
            virtualGear += 1
            beginVirtualShift(
                direction = ShiftDirection.UP,
                targetRpm = profile.fullThrottleSweetSpotRpm,
                durationSeconds = profile.upshiftDurationSeconds,
            )
            return
        }
        if (pedalReleased && virtualGear > 1 && engineRpm <= profile.fullThrottleSweetSpotRpm) {
            virtualGear -= 1
            beginVirtualShift(
                direction = ShiftDirection.DOWN,
                targetRpm = min(profile.upshiftRpm - DOWNSHIFT_HEADROOM_RPM, profile.limiterRpm - 180.0),
                durationSeconds = profile.downshiftDurationSeconds,
            )
        }
    }

    private fun beginVirtualShift(
        direction: ShiftDirection,
        targetRpm: Double,
        durationSeconds: Double,
    ) {
        activeShift = ActiveShift(
            direction = direction,
            targetRpm = targetRpm.coerceIn(profile.idleRpm, profile.limiterRpm),
            durationSeconds = durationSeconds,
        )
        shiftSerial += 1
        secondsSinceShift = 0.0
    }

    /** In SIM, visual speed is intentionally tied to fake RPM so lift-off decelerates both together. */
    private fun synchronizeSimulatorSpeedToTach(dt: Double, transmissionPosition: TransmissionPosition) {
        val previousSpeedMps = vehicleSpeedMps
        val rpmMappedSpeedMps = if (transmissionPosition == TransmissionPosition.DRIVE) {
            val rpmSpan = (profile.redlineRpm - profile.idleRpm).coerceAtLeast(1.0)
            val fraction = ((engineRpm - profile.idleRpm) / rpmSpan).coerceIn(0.0, 1.0)
            profile.topSpeedKmh / 3.6 * fraction
        } else {
            0.0
        }
        val targetSpeedMps = if (activeShift != null) {
            vehicleSpeedMps
        } else if (filteredThrottle > SPEED_HOLD_THROTTLE_THRESHOLD && filteredBrake < 0.01) {
            max(vehicleSpeedMps, rpmMappedSpeedMps)
        } else {
            rpmMappedSpeedMps
        }
        vehicleSpeedMps = targetSpeedMps
        lastAcceleration = ((vehicleSpeedMps - previousSpeedMps) / dt)
            .coerceIn(-MAX_REPORTED_ACCELERATION, MAX_REPORTED_ACCELERATION)
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
        val shift = activeShift
        val axleTorque = axleWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val peakWheelTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
        val wheelTorqueFraction = (axleTorque.totalNm / peakWheelTorque).coerceIn(0.0, 1.0)
        return DrivetrainState(
            rpm = engineRpm,
            gear = virtualGear,
            speedKmh = vehicleSpeedMps * 3.6,
            smoothedThrottle = filteredThrottle,
            smoothedBrake = filteredBrake,
            engineLoad = (filteredThrottle * (0.35 + 0.65 * wheelTorqueFraction)).coerceIn(0.0, 1.0),
            isShifting = shift != null,
            shiftDirection = shift?.direction ?: ShiftDirection.NONE,
            shiftProgress = shift?.let { (it.elapsedSeconds / it.durationSeconds).coerceIn(0.0, 1.0) } ?: 0.0,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            accelerationMps2 = lastAcceleration,
            rpmProgressionFraction = lastRpmProgressionFraction,
            rpmPositiveForcePerSecond = lastRpmPositiveForce,
            rpmNegativeForcePerSecond = lastRpmNegativeForce,
        )
    }

    companion object {
        private const val AIR_DENSITY_KG_M3 = 1.225
        private const val GRAVITY_MPS2 = 9.81
        private const val MAX_SERVICE_BRAKE_MPS2 = 11.2
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val LIMITER_CUT_RPM_DECELERATION_PER_SECOND = 4_000.0
        private const val MAX_REPORTED_ACCELERATION = 15.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
        private const val FULL_PEDAL_THRESHOLD = 0.96
        private const val SHIFT_THROTTLE_THRESHOLD = 0.10
        private const val SPEED_HOLD_THROTTLE_THRESHOLD = 0.01
        private const val DOWNSHIFT_HEADROOM_RPM = 400.0
        private const val SHIFT_RPM_RESPONSE_FRACTION = 0.18
        /** Neutral/Park rev-up: engine inertia spooling with no wheel load. */
        private const val NEUTRAL_REV_UP_RESPONSE_SECONDS = 0.55
        /** Neutral/Park rev-down: coasting back toward idle after lift-off. */
        private const val NEUTRAL_REV_DOWN_RESPONSE_SECONDS = 0.90
    }

    private data class ActiveShift(
        val direction: ShiftDirection,
        val targetRpm: Double,
        val durationSeconds: Double,
        var elapsedSeconds: Double = 0.0,
    )
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

/** Smooth fake-tach force curve, entirely independent from road speed and EV wheel power. */
internal fun rpmProgressionFractionAtRpm(profile: EngineProfile, rpm: Double): Double {
    val span = (profile.redlineRpm - profile.idleRpm).coerceAtLeast(1.0)
    val normalizedRpm = ((rpm - profile.idleRpm) / span).coerceIn(0.0, 1.0)
    return interpolateCurve(profile.rpmProgressionCurve, normalizedRpm).coerceIn(0.35, 1.15)
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}
