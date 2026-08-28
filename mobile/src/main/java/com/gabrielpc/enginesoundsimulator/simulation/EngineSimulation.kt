package com.gabrielpc.enginesoundsimulator.simulation

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
        val SAMPLE_BANK_ENGINE = EngineProfile(
            name = "Catalog engine",
            idleRpm = 1_040.0,
            redlineRpm = 8_200.0,
            limiterRpm = 8_350.0,
            upshiftRpm = 8_200.0,
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
            gearRatios = EngineTuning.DEFAULT_GEARS.toDoubleArray(),
            upshiftDurationSeconds = 0.060,
            downshiftDurationSeconds = 0.150,
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
    val rawSpeedKmh: Double,
)

/** Seqlock-backed primitive state published by the 200 Hz core. */
internal class RealtimeDrivetrainState {
    @Volatile private var revision = 0L
    var rpm = 0.0
        private set
    var gear = 1
        private set
    var speedKmh = 0.0
        private set
    var smoothedThrottle = 0.0
        private set
    var smoothedBrake = 0.0
        private set
    var engineLoad = 0.0
        private set
    var isShifting = false
        private set
    var shiftDirection = ShiftDirection.NONE
        private set
    var shiftProgress = 0.0
        private set
    var shiftSerial = 0L
        private set
    var limiterActive = false
        private set
    var accelerationMps2 = 0.0
        private set
    var rawSpeedKmh = 0.0
        private set

    fun publish(
        rpm: Double,
        gear: Int,
        speedKmh: Double,
        smoothedThrottle: Double,
        smoothedBrake: Double,
        engineLoad: Double,
        isShifting: Boolean,
        shiftDirection: ShiftDirection,
        shiftProgress: Double,
        shiftSerial: Long,
        limiterActive: Boolean,
        accelerationMps2: Double,
        rawSpeedKmh: Double,
    ) {
        val next = revision + 1L
        revision = next
        this.rpm = rpm
        this.gear = gear
        this.speedKmh = speedKmh
        this.smoothedThrottle = smoothedThrottle
        this.smoothedBrake = smoothedBrake
        this.engineLoad = engineLoad
        this.isShifting = isShifting
        this.shiftDirection = shiftDirection
        this.shiftProgress = shiftProgress
        this.shiftSerial = shiftSerial
        this.limiterActive = limiterActive
        this.accelerationMps2 = accelerationMps2
        this.rawSpeedKmh = rawSpeedKmh
        revision = next + 1L
    }

    /** Allocated only when a visible/presentation caller asks for a snapshot. */
    fun snapshot(): DrivetrainState {
        while (true) {
            val before = revision
            if (before and 1L != 0L) continue
            val result = DrivetrainState(
                rpm, gear, speedKmh, smoothedThrottle, smoothedBrake, engineLoad,
                isShifting, shiftDirection, shiftProgress, shiftSerial, limiterActive,
                accelerationMps2, rawSpeedKmh,
            )
            if (revision == before) return result
        }
    }
}

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
    private var lastAcceleration = 0.0
    /** RPM at which each gear landed when it was selected by an upshift. */
    private var downshiftLandingRpmByGear = DoubleArray(profile.gearRatios.size)
    private val externalSpeedEstimator = QuantizedSpeedEstimator()

    val state: DrivetrainState get() = snapshot()

    fun updateProfile(updated: EngineProfile) {
        profile = updated
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        simulatedPhysicalSpeedMps = simulatedPhysicalSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        // Landing RPMs belong to the previous ratio set even when the new car has
        // the same number of gears. Rebuild them and choose the appropriate gear
        // from road speed on every car/profile change.
        downshiftLandingRpmByGear = DoubleArray(profile.gearRatios.size)
        synchronizeToRoadSpeed()
        engineRpm = engineRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
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
        lastAcceleration = 0.0
        downshiftLandingRpmByGear.fill(0.0)
        externalSpeedEstimator.reset()
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        updateInternal(
            input.throttle, input.brake, input.externalSpeedKmh,
            input.simulateCoastRegen, input.transmissionPosition, deltaSeconds,
        )
        return snapshot()
    }

    internal fun updateRealtime(
        throttle: Double,
        brake: Double,
        externalSpeedKmh: Double?,
        simulateCoastRegen: Boolean,
        transmissionPosition: TransmissionPosition,
        deltaSeconds: Double,
        destination: RealtimeDrivetrainState,
    ) {
        updateInternal(
            throttle, brake, externalSpeedKmh, simulateCoastRegen,
            transmissionPosition, deltaSeconds,
        )
        publishSnapshot(destination)
    }

    internal fun publishSnapshot(destination: RealtimeDrivetrainState) {
        val shift = activeShift
        val totalWheelTorque = totalWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val peakWheelTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
        val wheelTorqueFraction = (totalWheelTorque / peakWheelTorque).coerceIn(0.0, 1.0)
        destination.publish(
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
            accelerationMps2 = lastAcceleration,
            rawSpeedKmh = rawExternalSpeedKmh,
        )
    }

    private fun updateInternal(
        throttle: Double,
        brake: Double,
        externalSpeedKmh: Double?,
        simulateCoastRegen: Boolean,
        transmissionPosition: TransmissionPosition,
        deltaSeconds: Double,
    ) {
        val dt = deltaSeconds.coerceIn(1.0 / 1_000.0, 1.0 / 20.0)
        val rawThrottle = throttle.coerceIn(0.0, 1.0)
        val requestedThrottle = interpolateCurve(profile.throttleCurve, rawThrottle)
        filteredThrottle = approachExp(
            filteredThrottle,
            requestedThrottle,
            if (requestedThrottle > filteredThrottle) profile.throttleAttackSeconds else profile.throttleReleaseSeconds,
            dt,
        )
        filteredBrake = approachExp(
            filteredBrake,
            brake.coerceIn(0.0, 1.0),
            profile.brakeResponseSeconds,
            dt,
        )

        val externalKmh = externalSpeedKmh?.coerceAtLeast(0.0)
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
                transmissionPosition,
                simulateCoastRegen && rawThrottle <= PEDAL_RELEASE_THRESHOLD,
            )
            if (transmissionPosition == TransmissionPosition.PARK) {
                simulatedPhysicalSpeedMps = 0.0
                vehicleSpeedMps = 0.0
                lastAcceleration = 0.0
            }
            applyQuantizedSimulatorSpeed(dt)
        }

        activeShift?.let { updateShift(it, dt) }
        if (activeShift == null) secondsSinceShift += dt

        updateSampleRpm(dt, transmissionPosition)
        updateLimiterLatch()
        if (activeShift == null && transmissionPosition == TransmissionPosition.DRIVE) {
            chooseAutomaticShift()
        }
    }

    private fun integrateElectricVehicle(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        applySimulatorRegen: Boolean,
    ) {
        val previousSpeedMps = simulatedPhysicalSpeedMps
        val totalWheelTorque = totalWheelTorqueAtSpeed(profile, simulatedPhysicalSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val throttleConnected = transmissionPosition == TransmissionPosition.DRIVE
        val requestedWheelTorque = if (throttleConnected) {
            totalWheelTorque * filteredThrottle * brakeOverride
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
        lastAcceleration = ((simulatedPhysicalSpeedMps - previousSpeedMps) / max(dt, 0.001))
            .coerceIn(-MAX_REPORTED_ACCELERATION, MAX_REPORTED_ACCELERATION)
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
        val previousSpeedMps = vehicleSpeedMps
        rawExternalSpeedKmh = externalSpeedKmh
        val continuousKmh = externalSpeedEstimator.update(
            measurementKmh = externalSpeedKmh,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        )
        vehicleSpeedMps = continuousKmh / 3.6
        if (!externalSpeedActive) {
            synchronizeToRoadSpeed()
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

    private fun synchronizeToRoadSpeed() {
        val safe = profile.gearRatios.indices.filter { rpmForSpeed(it) <= profile.redlineRpm * 0.92 }
        currentGearIndex = safe.firstOrNull() ?: profile.gearRatios.lastIndex
        while (currentGearIndex < profile.gearRatios.lastIndex &&
            vehicleSpeedMps * 3.6 >= upshiftSpeedKmh(currentGearIndex)
        ) {
            val nextGear = currentGearIndex + 1
            downshiftLandingRpmByGear[nextGear] = calculatedUpshiftLandingRpm(profile, currentGearIndex)
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
            val landingRpm = downshiftLandingRpmByGear[currentGearIndex]
                .takeIf { it > 0.0 }
                ?: calculatedUpshiftLandingRpm(profile, currentGearIndex - 1)
            val releasedThrottleDownshift = filteredThrottle <= RELEASE_DOWNSHIFT_THROTTLE_MAX &&
                rpmForSpeed(currentGearIndex) <= landingRpm
            val previousUpshiftSpeed = upshiftSpeedKmh(currentGearIndex - 1)
            val demandDownshift = filteredThrottle > 0.78 &&
                speedKmh < previousUpshiftSpeed - KICKDOWN_SPEED_MARGIN_KMH &&
                rpmForSpeed(currentGearIndex - 1) < profile.redlineRpm
            if (releasedThrottleDownshift || demandDownshift) {
                beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
            }
        }
    }

    private fun upshiftSpeedKmh(gearIndex: Int): Double {
        return presentationUpshiftSpeedKmh(profile, gearIndex)
    }

    private fun beginShift(targetGearIndex: Int, direction: ShiftDirection) {
        val duration = if (direction == ShiftDirection.UP) profile.upshiftDurationSeconds else profile.downshiftDurationSeconds
        val targetRpm = rpmForSpeed(targetGearIndex)
        if (direction == ShiftDirection.UP) {
            // Use the requested ratio calculation exactly, independent of one simulation tick of
            // speed overshoot. No RPM compensation or hysteresis is added later.
            downshiftLandingRpmByGear[targetGearIndex] =
                calculatedUpshiftLandingRpm(profile, currentGearIndex)
        }
        activeShift = ActiveShift(
            targetGearIndex = targetGearIndex,
            direction = direction,
            targetRpm = targetRpm,
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
        return presentationRpmAtSpeed(profile, gearIndex, vehicleSpeedMps * 3.6)
    }

    private fun snapshot(): DrivetrainState {
        val shift = activeShift
        val totalWheelTorque = totalWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val peakWheelTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
        val wheelTorqueFraction = (totalWheelTorque / peakWheelTorque).coerceIn(0.0, 1.0)
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
            accelerationMps2 = lastAcceleration,
            rawSpeedKmh = rawExternalSpeedKmh,
        )
    }

    companion object {
        private const val AIR_DENSITY_KG_M3 = 1.225
        private const val GRAVITY_MPS2 = 9.81
        private const val MAX_SERVICE_BRAKE_MPS2 = 11.2
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val MAX_REPORTED_ACCELERATION = 15.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
        private const val SHIFT_THROTTLE_THRESHOLD = 0.10
        private const val EMERGENCY_UPSHIFT_RPM_FRACTION = 0.98
        private const val RELEASE_DOWNSHIFT_THROTTLE_MAX = 0.10
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

/**
 * Scales the selected combustion car's real relative ratios onto the independent Seal road-speed
 * model. Top gear reaches the configured presentation upshift RPM at the configured top speed;
 * every lower gear keeps the imported ratio spacing and therefore its real RPM drop.
 */
internal fun presentationFinalDrive(profile: EngineProfile): Double {
    val topGear = profile.gearRatios.lastOrNull()?.coerceAtLeast(0.001) ?: 1.0
    val topSpeedMps = (profile.topSpeedKmh / 3.6).coerceAtLeast(0.001)
    val wheelRpm = topSpeedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
    return profile.upshiftRpm.coerceAtLeast(profile.idleRpm) / (wheelRpm * topGear).coerceAtLeast(0.001)
}

internal fun presentationUpshiftSpeedKmh(profile: EngineProfile, gearIndex: Int): Double {
    val ratios = profile.gearRatios
    if (ratios.isEmpty()) return profile.topSpeedKmh
    val index = gearIndex.coerceIn(0, ratios.lastIndex)
    val ratio = ratios[index].coerceAtLeast(0.001)
    val topRatio = ratios.last().coerceAtLeast(0.001)
    return (profile.topSpeedKmh * topRatio / ratio).coerceIn(2.0, profile.topSpeedKmh)
}

internal fun presentationRpmAtSpeed(profile: EngineProfile, gearIndex: Int, speedKmh: Double): Double {
    val ratios = profile.gearRatios
    if (ratios.isEmpty()) return profile.idleRpm
    val index = gearIndex.coerceIn(0, ratios.lastIndex)
    val wheelRpm = (speedKmh.coerceAtLeast(0.0) / 3.6) /
        (2.0 * PI * profile.wheelRadiusMeters) * 60.0
    val coupledRpm = wheelRpm * ratios[index].coerceAtLeast(0.001) * presentationFinalDrive(profile)
    return coupledRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
}

/** Exact ratio-based RPM reached in the next gear at a normal upshift; no hysteresis is applied. */
internal fun calculatedUpshiftLandingRpm(profile: EngineProfile, fromGearIndex: Int): Double {
    val ratios = profile.gearRatios
    if (ratios.size < 2) return profile.idleRpm
    val from = fromGearIndex.coerceIn(0, ratios.lastIndex - 1)
    return (profile.upshiftRpm * ratios[from + 1].coerceAtLeast(0.001) /
        ratios[from].coerceAtLeast(0.001)).coerceIn(profile.idleRpm, profile.limiterRpm)
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

/** Allocation-free total used by the 200 Hz EV and publication path. */
private fun totalWheelTorqueAtSpeed(profile: EngineProfile, speedKmh: Double): Double {
    val normalizedSpeed = (speedKmh / TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH).coerceIn(0.0, 1.0)
    return profile.frontPeakWheelTorqueNm * interpolateCurve(profile.frontWheelTorqueCurve, normalizedSpeed) +
        profile.rearPeakWheelTorqueNm * interpolateCurve(profile.rearWheelTorqueCurve, normalizedSpeed)
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
