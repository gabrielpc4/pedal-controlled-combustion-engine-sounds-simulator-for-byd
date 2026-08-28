package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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
    val syntheticRpmResponseSeconds: Double,
    val externalSpeedSmoothingSeconds: Double,
    val simulatorCoastRegenMps2: Double = 2.50,
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
            syntheticRpmResponseSeconds = 0.055,
            externalSpeedSmoothingSeconds = 0.12,
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
    val rawSpeedKmh: Double,
)

/**
 * EV longitudinal model with a speed-coupled fictional engine and automatic sound gearbox.
 * Integer BYD speed samples pass through a predictive critically-damped estimator before they
 * can move the tach or audio, so acceleration and deceleration remain continuous.
 */
class EngineSimulation(initialProfile: EngineProfile = EngineProfile.SAMPLE_BANK_ENGINE) {
    var profile: EngineProfile = initialProfile
        private set
    private var engineRpm = profile.idleRpm
    private var vehicleSpeedMps = 0.0
    private var simulatedPhysicalSpeedMps = 0.0
    private var rawExternalSpeedKmh = 0.0
    private var currentGearIndex = 0
    private var filteredThrottle = 0.0
    private var filteredBrake = 0.0
    private var activeShift: ActiveShift? = null
    private var shiftSerial = 0L
    private var secondsSinceShift = 10.0
    private var limiterLatched = false
    private var externalSpeedActive = false
    private var downshiftBoundaryKmhByGear = DoubleArray(profile.gearRatios.size)
    private val externalSpeedEstimator = QuantizedSpeedEstimator()

    val state: DrivetrainState get() = snapshot()

    fun updateProfile(updated: EngineProfile) {
        profile = updated
        currentGearIndex = currentGearIndex.coerceIn(0, profile.gearRatios.lastIndex)
        if (downshiftBoundaryKmhByGear.size != profile.gearRatios.size) {
            downshiftBoundaryKmhByGear = DoubleArray(profile.gearRatios.size)
        }
        engineRpm = engineRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        simulatedPhysicalSpeedMps = simulatedPhysicalSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
    }

    fun reset() {
        engineRpm = profile.idleRpm
        vehicleSpeedMps = 0.0
        simulatedPhysicalSpeedMps = 0.0
        rawExternalSpeedKmh = 0.0
        currentGearIndex = 0
        filteredThrottle = 0.0
        filteredBrake = 0.0
        activeShift = null
        shiftSerial = 0L
        secondsSinceShift = 10.0
        limiterLatched = false
        externalSpeedActive = false
        downshiftBoundaryKmhByGear.fill(0.0)
        externalSpeedEstimator.reset()
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val dt = deltaSeconds.coerceIn(1.0 / 1_000.0, 1.0 / 20.0)
        val rawThrottle = input.throttle.coerceIn(0.0, 1.0)
        val requestedThrottle = interpolateCurve(profile.throttleCurve, rawThrottle)
        filteredThrottle = approachExp(
            filteredThrottle,
            requestedThrottle,
            if (requestedThrottle > filteredThrottle) profile.throttleAttackSeconds else profile.throttleReleaseSeconds,
            dt,
        )
        filteredBrake = approachExp(
            filteredBrake,
            input.brake.coerceIn(0.0, 1.0),
            profile.brakeResponseSeconds,
            dt,
        )

        val externalKmh = input.externalSpeedKmh?.coerceAtLeast(0.0)
        if (externalKmh != null) {
            applyExternalSpeed(externalKmh, dt)
        } else {
            if (externalSpeedActive) {
                simulatedPhysicalSpeedMps = vehicleSpeedMps
                externalSpeedEstimator.reset()
            }
            externalSpeedActive = false
            integrateElectricVehicle(
                dt,
                input.transmissionPosition,
                input.simulateCoastRegen && rawThrottle <= PEDAL_RELEASE_THRESHOLD,
            )
            if (input.transmissionPosition == TransmissionPosition.PARK) {
                simulatedPhysicalSpeedMps = 0.0
                vehicleSpeedMps = 0.0
            }
            applyQuantizedSimulatorSpeed(dt)
        }

        activeShift?.let { updateShift(it, dt) }
        if (activeShift == null) secondsSinceShift += dt

        updateSampleRpm(dt, input.transmissionPosition)
        updateLimiterLatch()
        if (activeShift == null && input.transmissionPosition == TransmissionPosition.DRIVE) {
            chooseAutomaticShift()
        }
        return snapshot()
    }

    private fun integrateElectricVehicle(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        applySimulatorRegen: Boolean,
    ) {
        val axleTorque = axleWheelTorqueAtSpeed(profile, simulatedPhysicalSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val throttleConnected = transmissionPosition == TransmissionPosition.DRIVE
        val requestedWheelTorque = if (throttleConnected) {
            axleTorque.totalNm * filteredThrottle * brakeOverride
        } else {
            0.0
        }
        val wheelOmega = simulatedPhysicalSpeedMps / profile.wheelRadiusMeters
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
        val regenerativeCoastForce = if (
            applySimulatorRegen && transmissionPosition == TransmissionPosition.DRIVE && simulatedPhysicalSpeedMps > 0.05
        ) {
            profile.vehicleMassKg * profile.simulatorCoastRegenMps2
        } else {
            0.0
        }
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 * simulatedPhysicalSpeedMps.pow(2)
        val rollingResistance = if (simulatedPhysicalSpeedMps > 0.05 || driveForce > 0.0) {
            profile.vehicleMassKg * GRAVITY_MPS2 * profile.rollingResistanceCoefficient
        } else {
            0.0
        }
        val acceleration = (driveForce - serviceBrakeForce - regenerativeCoastForce - aerodynamicDrag - rollingResistance) /
            (profile.vehicleMassKg * profile.rotationalMassFactor)
        simulatedPhysicalSpeedMps = (simulatedPhysicalSpeedMps + acceleration * dt)
            .coerceIn(0.0, maximumVehicleSpeedMps())
        if (simulatedPhysicalSpeedMps < 0.04 && driveForce <= rollingResistance + serviceBrakeForce) {
            simulatedPhysicalSpeedMps = 0.0
        }
    }

    /** Feeds SIM through the same whole-km/h boundary exposed by the BYD framework. */
    private fun applyQuantizedSimulatorSpeed(dt: Double) {
        rawExternalSpeedKmh = (simulatedPhysicalSpeedMps * 3.6).roundToInt().toDouble()
        vehicleSpeedMps = externalSpeedEstimator.update(
            measurementKmh = rawExternalSpeedKmh,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        ) / 3.6
    }

    private fun applyExternalSpeed(externalSpeedKmh: Double, dt: Double) {
        rawExternalSpeedKmh = externalSpeedKmh
        val continuousKmh = externalSpeedEstimator.update(
            measurementKmh = externalSpeedKmh,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        )
        vehicleSpeedMps = continuousKmh / 3.6
        if (!externalSpeedActive) {
            synchronizeToRoadSpeed()
        }
        externalSpeedActive = true
    }

    private fun synchronizeToRoadSpeed() {
        val safe = profile.gearRatios.indices.filter { rpmForSpeed(it) <= profile.redlineRpm * 0.92 }
        currentGearIndex = safe.firstOrNull() ?: profile.gearRatios.lastIndex
        while (currentGearIndex < profile.gearRatios.lastIndex &&
            vehicleSpeedMps * 3.6 >= upshiftSpeedKmh(currentGearIndex)
        ) {
            val nextGear = currentGearIndex + 1
            downshiftBoundaryKmhByGear[nextGear] = upshiftSpeedKmh(currentGearIndex)
            currentGearIndex = nextGear
        }
        engineRpm = rpmForSpeed(currentGearIndex)
        activeShift = null
        limiterLatched = false
        secondsSinceShift = 0.0
    }

    private fun updateSampleRpm(dt: Double, transmissionPosition: TransmissionPosition) {
        val target = when (transmissionPosition) {
            TransmissionPosition.DRIVE -> {
                val shift = activeShift
                if (shift?.gearChanged == true) shift.targetRpm else rpmForSpeed(currentGearIndex)
            }
            TransmissionPosition.NEUTRAL, TransmissionPosition.PARK -> freeRevRpmTarget()
        }
        val response = when {
            transmissionPosition != TransmissionPosition.DRIVE && target >= engineRpm -> NEUTRAL_REV_UP_RESPONSE_SECONDS
            transmissionPosition != TransmissionPosition.DRIVE -> NEUTRAL_REV_DOWN_RESPONSE_SECONDS
            activeShift?.gearChanged == true -> (activeShift!!.durationSeconds * 0.30).coerceAtLeast(0.018)
            else -> profile.syntheticRpmResponseSeconds
        }
        engineRpm = approachExp(engineRpm, target, response, dt).coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    /** Throttle-driven revs independent of road speed, like a combustion engine in neutral. */
    private fun freeRevRpmTarget(): Double {
        val revSpan = profile.redlineRpm - profile.idleRpm
        return profile.idleRpm + filteredThrottle.coerceIn(0.0, 1.0) * revSpan
    }

    private fun chooseAutomaticShift() {
        if (secondsSinceShift < profile.shiftDwellSeconds) return
        val speedKmh = vehicleSpeedMps * 3.6
        if (currentGearIndex < profile.gearRatios.lastIndex &&
            rpmForSpeed(currentGearIndex) >= profile.redlineRpm * EMERGENCY_UPSHIFT_RPM_FRACTION
        ) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }
        if (currentGearIndex < profile.gearRatios.lastIndex &&
            filteredThrottle > SHIFT_THROTTLE_THRESHOLD &&
            speedKmh >= upshiftSpeedKmh(currentGearIndex)
        ) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }
        if (currentGearIndex > 0) {
            val previousUpshift = downshiftBoundaryKmhByGear[currentGearIndex]
                .takeIf { it > 0.0 }
                ?: upshiftSpeedKmh(currentGearIndex - 1)
            val downshiftSpeed = (previousUpshift - DOWNSHIFT_SPEED_HYSTERESIS_KMH).coerceAtLeast(2.0)
            val demandDownshift = filteredThrottle > 0.78 &&
                speedKmh < previousUpshift - KICKDOWN_SPEED_MARGIN_KMH
            if (speedKmh <= downshiftSpeed || demandDownshift) {
                beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
            }
        }
    }

    private fun upshiftSpeedKmh(gearIndex: Int): Double {
        return evenlySpacedUpshiftSpeedKmh(profile, gearIndex)
    }

    private fun beginShift(targetGearIndex: Int, direction: ShiftDirection) {
        val duration = if (direction == ShiftDirection.UP) profile.upshiftDurationSeconds else profile.downshiftDurationSeconds
        if (direction == ShiftDirection.UP) {
            downshiftBoundaryKmhByGear[targetGearIndex] = vehicleSpeedMps * 3.6
        }
        activeShift = ActiveShift(
            targetGearIndex = targetGearIndex,
            direction = direction,
            targetRpm = rpmForSpeed(targetGearIndex),
            durationSeconds = duration,
        )
        shiftSerial += 1
        secondsSinceShift = 0.0
    }

    private fun updateShift(shift: ActiveShift, dt: Double) {
        shift.elapsedSeconds += dt
        val progress = (shift.elapsedSeconds / shift.durationSeconds).coerceIn(0.0, 1.0)
        if (!shift.gearChanged && progress >= 0.38) {
            currentGearIndex = shift.targetGearIndex
            shift.gearChanged = true
        }
        if (progress >= 1.0) {
            activeShift = null
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

    private fun rpmForSpeed(gearIndex: Int): Double {
        val wheelRpm = vehicleSpeedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
        return (profile.idleRpm + wheelRpm * evenlySpacedGearRatio(profile, gearIndex))
            .coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    private fun snapshot(): DrivetrainState {
        val shift = activeShift
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
            isShifting = shift != null,
            shiftDirection = shift?.direction ?: ShiftDirection.NONE,
            shiftProgress = shift?.let { (it.elapsedSeconds / it.durationSeconds).coerceIn(0.0, 1.0) } ?: 0.0,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            rawSpeedKmh = rawExternalSpeedKmh,
        )
    }

    companion object {
        private const val AIR_DENSITY_KG_M3 = 1.225
        private const val GRAVITY_MPS2 = 9.81
        private const val MAX_SERVICE_BRAKE_MPS2 = 11.2
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val SHIFT_THROTTLE_THRESHOLD = 0.10
        private const val EMERGENCY_UPSHIFT_RPM_FRACTION = 0.98
        private const val DOWNSHIFT_SPEED_HYSTERESIS_KMH = 4.0
        private const val KICKDOWN_SPEED_MARGIN_KMH = 10.0
        /** Neutral/Park rev-up: engine inertia spooling with no wheel load. */
        private const val NEUTRAL_REV_UP_RESPONSE_SECONDS = 0.55
        /** Neutral/Park rev-down: coasting back toward idle after lift-off. */
        private const val NEUTRAL_REV_DOWN_RESPONSE_SECONDS = 0.90
    }

    private data class ActiveShift(
        val targetGearIndex: Int,
        val direction: ShiftDirection,
        val targetRpm: Double,
        val durationSeconds: Double,
        var elapsedSeconds: Double = 0.0,
        var gearChanged: Boolean = false,
    )
}

internal data class AxleWheelTorque(val frontNm: Double, val rearNm: Double) {
    val totalNm: Double get() = frontNm + rearNm
    val rearShare: Double get() = if (totalNm > 0.0) rearNm / totalNm else 0.0
}

/** Digitized A2MAC1 axle curves were sampled against a 180 km/h chart; keep that reference for physics. */
internal const val TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH = 180.0

/** Divides the configured road-speed range into one equal-width band per sound gear. */
internal fun evenlySpacedUpshiftSpeedKmh(profile: EngineProfile, gearIndex: Int): Double {
    val gearCount = profile.gearRatios.size.coerceAtLeast(1)
    return profile.topSpeedKmh * (gearIndex + 1).coerceIn(1, gearCount) / gearCount
}

/** Makes each gear reach the normal shift RPM at the end of its equal-width speed band. */
internal fun evenlySpacedGearRatio(profile: EngineProfile, gearIndex: Int): Double {
    val boundaryKmh = evenlySpacedUpshiftSpeedKmh(profile, gearIndex)
    val boundaryWheelRpm = (boundaryKmh / 3.6) / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
    val coupledRpm = (profile.upshiftRpm - profile.idleRpm).coerceAtLeast(1.0)
    return coupledRpm / boundaryWheelRpm.coerceAtLeast(0.001)
}

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

/** Reconstructs continuous motion from the integer speed exposed by the BYD framework. */
internal class QuantizedSpeedEstimator {
    private var initialized = false
    private var estimateKmh = 0.0
    private var estimateVelocityKmhPerSecond = 0.0
    private var observedVelocityKmhPerSecond = 0.0
    private var previousMeasurementKmh = 0.0
    private var secondsSinceMeasurementChanged = 0.0

    fun reset() {
        initialized = false
        estimateKmh = 0.0
        estimateVelocityKmhPerSecond = 0.0
        observedVelocityKmhPerSecond = 0.0
        previousMeasurementKmh = 0.0
        secondsSinceMeasurementChanged = 0.0
    }

    fun update(measurementKmh: Double, dt: Double, responseSeconds: Double): Double {
        val measurement = measurementKmh.coerceAtLeast(0.0)
        if (!initialized) {
            initialized = true
            estimateKmh = measurement
            previousMeasurementKmh = measurement
            return estimateKmh
        }

        secondsSinceMeasurementChanged += dt
        if (measurement != previousMeasurementKmh) {
            val elapsed = secondsSinceMeasurementChanged.coerceAtLeast(dt)
            val observedVelocity = ((measurement - previousMeasurementKmh) / elapsed).coerceIn(-45.0, 45.0)
            if (observedVelocity * estimateVelocityKmhPerSecond < 0.0) {
                estimateVelocityKmhPerSecond = 0.0
            }
            observedVelocityKmhPerSecond = if (observedVelocity * observedVelocityKmhPerSecond < 0.0) {
                observedVelocity
            } else {
                observedVelocityKmhPerSecond + (observedVelocity - observedVelocityKmhPerSecond) * 0.72
            }
            previousMeasurementKmh = measurement
            secondsSinceMeasurementChanged = 0.0
        }

        val directionOffset = when {
            observedVelocityKmhPerSecond > 0.20 -> 0.45
            observedVelocityKmhPerSecond < -0.20 -> -0.45
            else -> 0.0
        }
        val target = (measurement + directionOffset).coerceAtLeast(0.0)
        val omega = 2.0 / responseSeconds.coerceIn(0.08, 0.80)
        val acceleration = omega * omega * (target - estimateKmh) - 2.0 * omega * estimateVelocityKmhPerSecond
        val previousEstimate = estimateKmh
        estimateVelocityKmhPerSecond = (estimateVelocityKmhPerSecond + acceleration * dt).coerceIn(-45.0, 45.0)
        estimateKmh = (estimateKmh + estimateVelocityKmhPerSecond * dt).coerceAtLeast(0.0)
        if ((previousEstimate <= target && estimateKmh > target) || (previousEstimate >= target && estimateKmh < target)) {
            estimateKmh = target
            estimateVelocityKmhPerSecond = 0.0
        }
        if (measurement == 0.0 && secondsSinceMeasurementChanged > 0.55 && estimateKmh < 0.04) {
            estimateKmh = 0.0
            estimateVelocityKmhPerSecond = 0.0
        }
        return estimateKmh
    }
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}
