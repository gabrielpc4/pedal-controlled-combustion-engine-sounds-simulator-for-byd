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
    val engineInertiaKgM2: Double,
    val vehicleMassKg: Double,
    val wheelRadiusMeters: Double,
    val finalDrive: Double,
    val gearRatios: DoubleArray,
    val torqueCurve: List<CurvePoint> = EngineTuning.DEFAULT_TORQUE_CURVE,
    val throttleCurve: List<CurvePoint> = EngineTuning.DEFAULT_THROTTLE_CURVE,
    val throttleAttackSeconds: Double = 0.075,
    val throttleReleaseSeconds: Double = 0.140,
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
            maxTorqueNm = 585.0,
            engineInertiaKgM2 = 0.42,
            vehicleMassKg = 1_640.0,
            wheelRadiusMeters = 0.337,
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
 * Fixed-step, game-oriented longitudinal powertrain simulation.
 *
 * It deliberately models state (engine inertia, wheel speed, converter/clutch coupling, torque
 * interruption and gear synchronization) rather than mapping pedal percentage directly to RPM.
 * The model is compact enough for Android but follows the same separation used by driving games:
 * driver controls -> engine torque -> transmission -> tyre force -> vehicle speed -> coupled RPM.
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
    private var launchClutchEngagement = 0.0
    private var limiterLatched = false
    private var externalSpeedActive = false
    private var lastAcceleration = 0.0

    val state: DrivetrainState
        get() = snapshot()

    fun updateProfile(updated: EngineProfile) {
        profile = updated
        currentGearIndex = currentGearIndex.coerceIn(0, profile.gearRatios.lastIndex)
        engineRpm = engineRpm.coerceIn(profile.idleRpm * 0.72, profile.limiterRpm)
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
        launchClutchEngagement = 0.0
        limiterLatched = false
        externalSpeedActive = false
        lastAcceleration = 0.0
    }

    fun update(input: DriverInput, deltaSeconds: Double): DrivetrainState {
        val dt = deltaSeconds.coerceIn(1.0 / 1_000.0, 1.0 / 20.0)
        val requestedThrottle = input.throttle.coerceIn(0.0, 1.0)
        val requestedEngineTorque = interpolateCurve(profile.throttleCurve, requestedThrottle)
        val requestedBrake = input.brake.coerceIn(0.0, 1.0)

        // A fast attack makes tip-in feel immediate; slower release preserves drivetrain inertia.
        filteredThrottle = approachExp(
            filteredThrottle,
            requestedEngineTorque,
            if (requestedEngineTorque > filteredThrottle) {
                profile.throttleAttackSeconds
            } else {
                profile.throttleReleaseSeconds
            },
            dt,
        )
        filteredBrake = approachExp(filteredBrake, requestedBrake, profile.brakeResponseSeconds, dt)

        val externalMps = input.externalSpeedKmh?.coerceAtLeast(0.0)?.div(3.6)
        if (externalMps != null) {
            applyExternalSpeed(externalMps, dt)
        } else {
            externalSpeedActive = false
        }

        if (shift == null) secondsSinceShift += dt

        val currentShift = shift
        val shiftControl = if (currentShift == null) {
            ShiftControl()
        } else {
            updateShift(currentShift, dt)
        }

        val gearRatio = profile.gearRatios[currentGearIndex]
        val totalRatio = gearRatio * profile.finalDrive
        val wheelRpm = vehicleSpeedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
        val gearboxRpm = wheelRpm * totalRatio

        val launchClutchTarget = when {
            vehicleSpeedMps > 2.5 || gearboxRpm >= profile.idleRpm -> 1.0
            else -> ((engineRpm - profile.idleRpm) / LAUNCH_RPM_SPAN).coerceIn(0.0, 1.0)
        }
        launchClutchEngagement = approachLinear(
            current = launchClutchEngagement,
            target = launchClutchTarget,
            maxDelta = (if (launchClutchTarget > launchClutchEngagement) 4.0 else 12.0) * dt,
        )
        val clutchEngagement = launchClutchEngagement * shiftControl.clutchFactor

        val torqueCurve = torqueCurve(engineRpm)
        updateLimiterLatch()
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val effectiveThrottle = filteredThrottle * shiftControl.torqueFactor * brakeOverride
        val combustionTorque = if (limiterLatched) {
            0.0
        } else {
            profile.maxTorqueNm * torqueCurve * effectiveThrottle
        }
        val frictionTorque = 24.0 + 0.0058 * engineRpm
        val pumpingTorque = (1.0 - effectiveThrottle) * 82.0 *
            ((engineRpm - profile.idleRpm) / (profile.redlineRpm - profile.idleRpm)).coerceIn(0.0, 1.0)
        val idleAssist = (1.0 - effectiveThrottle / 0.16).coerceIn(0.0, 1.0)
        val frictionAtIdle = 24.0 + 0.0058 * profile.idleRpm
        val idleTorque = if (engineRpm < profile.idleRpm * 1.12) {
            idleAssist * (frictionAtIdle + (profile.idleRpm - engineRpm) * 0.55)
                .coerceIn(0.0, 150.0)
        } else {
            0.0
        }

        val engineOmega = rpmToRadiansPerSecond(engineRpm)
        val gearboxOmega = rpmToRadiansPerSecond(gearboxRpm)
        val clutchCapacity = profile.maxTorqueNm * 1.45 * clutchEngagement
        val revMatchTorque = shiftControl.revMatchTargetRpm?.let { targetRpm ->
            (REV_MATCH_STIFFNESS * rpmToRadiansPerSecond(targetRpm - engineRpm))
                .coerceIn(0.0, profile.maxTorqueNm * 0.65)
        } ?: 0.0
        val engineTorqueBeforeClutch = combustionTorque + idleTorque + revMatchTorque -
            frictionTorque - pumpingTorque
        val rawClutchTorque = CLUTCH_STIFFNESS * (engineOmega - gearboxOmega)
        val managingLaunchSlip = externalMps == null && currentGearIndex == 0 && shift == null &&
            requestedThrottle > 0.0 && engineRpm > gearboxRpm + LAUNCH_SLIP_MARGIN_RPM
        val positiveClutchCapacity = if (managingLaunchSlip) {
            val minimumRiseRpmPerSecond = LAUNCH_MIN_RISE_RPM_PER_SECOND +
                LAUNCH_THROTTLE_RISE_RPM_PER_SECOND * filteredThrottle
            val accelerationReserveTorque = profile.engineInertiaKgM2 *
                rpmToRadiansPerSecond(minimumRiseRpmPerSecond)
            min(clutchCapacity, (engineTorqueBeforeClutch - accelerationReserveTorque).coerceAtLeast(0.0))
        } else {
            clutchCapacity
        }
        val clutchTorque = rawClutchTorque.coerceIn(-clutchCapacity, positiveClutchCapacity)
        val netEngineTorque = engineTorqueBeforeClutch - clutchTorque
        val rpmDerivative = (netEngineTorque / profile.engineInertiaKgM2 * 60.0 / (2.0 * PI))
            .coerceIn(-7_500.0, 8_000.0)
        engineRpm = (engineRpm + rpmDerivative * dt)
            .coerceIn(profile.idleRpm * 0.72, profile.limiterRpm)
        updateLimiterLatch()

        if (externalMps == null) {
            integrateVirtualVehicle(
                clutchTorque = clutchTorque,
                totalRatio = totalRatio,
                brake = filteredBrake,
                dt = dt,
            )
        }

        if (shift == null) {
            val emergencyUpshift = needsEmergencyUpshift()
            if (emergencyUpshift || secondsSinceShift >= profile.shiftDwellSeconds) {
                chooseAutomaticShift(emergencyUpshift)
            }
        }

        return snapshot()
    }

    private fun integrateVirtualVehicle(
        clutchTorque: Double,
        totalRatio: Double,
        brake: Double,
        dt: Double,
    ) {
        val previousSpeedMps = vehicleSpeedMps
        val transmittedTorque = clutchTorque * totalRatio * 0.90
        val driveForce = transmittedTorque / profile.wheelRadiusMeters
        val serviceBrakeForce = brake * profile.vehicleMassKg * 11.2
        val aerodynamicDrag = 0.5 * 1.225 * 0.67 * vehicleSpeedMps.pow(2)
        val rollingResistance = if (vehicleSpeedMps > 0.05) profile.vehicleMassKg * 9.81 * 0.014 else 0.0
        val netForce = driveForce - serviceBrakeForce - aerodynamicDrag - rollingResistance
        val acceleration = netForce / profile.vehicleMassKg
        vehicleSpeedMps = (vehicleSpeedMps + acceleration * dt).coerceAtLeast(0.0)
        if (vehicleSpeedMps < 0.04 && driveForce < rollingResistance + serviceBrakeForce) {
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

    /**
     * A live vehicle can connect while already travelling at motorway speed. Starting that sample in
     * first gear would momentarily command an impossible engine speed, so choose the tallest gear
     * that still leaves useful engine speed without over-revving.
     */
    private fun synchronizeToExternalSpeed() {
        val wheelRpm = wheelRpmForSpeed(vehicleSpeedMps)
        val maximumSynchronizationRpm = profile.redlineRpm * 0.92
        val minimumCruiseRpm = max(profile.idleRpm * 1.15, profile.downshiftRpm * 0.90)
        val safeGears = profile.gearRatios.indices.filter { gearIndex ->
            wheelRpm * profile.gearRatios[gearIndex] * profile.finalDrive <= maximumSynchronizationRpm
        }
        val cruiseGears = safeGears.filter { gearIndex ->
            wheelRpm * profile.gearRatios[gearIndex] * profile.finalDrive >= minimumCruiseRpm
        }
        currentGearIndex = cruiseGears.lastOrNull()
            ?: safeGears.firstOrNull()
            ?: profile.gearRatios.lastIndex

        val synchronizedRpm = wheelRpm * profile.gearRatios[currentGearIndex] * profile.finalDrive
        engineRpm = max(profile.idleRpm, synchronizedRpm)
            .coerceAtMost(profile.limiterRpm - LIMITER_TRIGGER_MARGIN_RPM)
        launchClutchEngagement = if (vehicleSpeedMps > 0.25) 1.0 else 0.0
        limiterLatched = false
        shift = null
        secondsSinceShift = 0.0
    }

    private fun needsEmergencyUpshift(): Boolean {
        if (currentGearIndex >= profile.gearRatios.lastIndex) return false
        val projectedRpm = wheelRpmForSpeed(vehicleSpeedMps) *
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
        val projectedLowerRpm = max(profile.idleRpm, wheelRpm * lowerRatio * profile.finalDrive)
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
            fromGearIndex = currentGearIndex,
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

    private fun updateShift(active: ActiveShift, dt: Double): ShiftControl {
        active.elapsed += dt
        val progress = (active.elapsed / active.duration).coerceIn(0.0, 1.0)
        if (!active.gearChanged && progress >= 0.38) {
            currentGearIndex = active.targetGearIndex
            active.gearChanged = true
        }

        val torqueFactor = when {
            progress < 0.20 -> lerp(1.0, 0.06, progress / 0.20)
            progress < 0.64 -> 0.06
            else -> lerp(0.06, 1.0, (progress - 0.64) / 0.36)
        }
        val clutchFactor = when {
            progress < 0.18 -> lerp(1.0, 0.04, progress / 0.18)
            progress < 0.62 -> 0.04
            else -> lerp(0.04, 1.0, (progress - 0.62) / 0.38)
        }

        val wheelRpm = wheelRpmForSpeed(vehicleSpeedMps)
        val newCoupledRpm = max(
            profile.idleRpm,
            wheelRpm * profile.gearRatios[active.targetGearIndex] * profile.finalDrive,
        )
        val revMatchTargetRpm = if (
            active.direction == ShiftDirection.DOWN && progress in 0.24..0.70
        ) {
            newCoupledRpm.coerceAtMost(profile.redlineRpm * 0.98)
        } else {
            null
        }

        if (progress >= 1.0) {
            shift = null
            // Dwell is measured from completed clutch re-engagement, not from shift initiation.
            secondsSinceShift = 0.0
        }
        return ShiftControl(torqueFactor, clutchFactor, revMatchTargetRpm)
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

    private fun torqueCurve(rpm: Double): Double {
        val normalizedRpm = (rpm / profile.limiterRpm).coerceIn(0.0, 1.0)
        val points = profile.torqueCurve
        if (points.isEmpty()) return 0.0
        if (normalizedRpm <= points.first().x) return points.first().y
        for (index in 0 until points.lastIndex) {
            val left = points[index]
            val right = points[index + 1]
            if (normalizedRpm <= right.x) {
                val fraction = (normalizedRpm - left.x) / (right.x - left.x).coerceAtLeast(0.0001)
                return lerp(left.y, right.y, smoothStep(fraction))
            }
        }
        return points.last().y
    }

    private fun snapshot(): DrivetrainState {
        val currentShift = shift
        return DrivetrainState(
            rpm = engineRpm,
            gear = currentGearIndex + 1,
            speedKmh = vehicleSpeedMps * 3.6,
            smoothedThrottle = filteredThrottle,
            smoothedBrake = filteredBrake,
            engineLoad = (filteredThrottle * (0.35 + 0.65 * torqueCurve(engineRpm))).coerceIn(0.0, 1.0),
            isShifting = currentShift != null,
            shiftDirection = currentShift?.direction ?: ShiftDirection.NONE,
            shiftProgress = currentShift?.let { (it.elapsed / it.duration).coerceIn(0.0, 1.0) } ?: 0.0,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            accelerationMps2 = lastAcceleration,
        )
    }

    private data class ActiveShift(
        val fromGearIndex: Int,
        val targetGearIndex: Int,
        val direction: ShiftDirection,
        var elapsed: Double,
        val duration: Double,
        var gearChanged: Boolean,
    )

    private data class ShiftControl(
        val torqueFactor: Double = 1.0,
        val clutchFactor: Double = 1.0,
        val revMatchTargetRpm: Double? = null,
    )

    companion object {
        private const val LAUNCH_RPM_SPAN = 2_500.0
        private const val LAUNCH_SLIP_MARGIN_RPM = 80.0
        private const val LAUNCH_MIN_RISE_RPM_PER_SECOND = 150.0
        private const val LAUNCH_THROTTLE_RISE_RPM_PER_SECOND = 600.0
        private const val CLUTCH_STIFFNESS = 10.0
        private const val REV_MATCH_STIFFNESS = 1.5
        private const val EMERGENCY_UPSHIFT_REDLINE_FRACTION = 0.97
        private const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val MAX_REPORTED_ACCELERATION = 15.0
        private const val EXTERNAL_ACCELERATION_FILTER_SECONDS = 0.10
    }
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}

private fun approachLinear(current: Double, target: Double, maxDelta: Double): Double = when {
    target > current -> min(target, current + maxDelta)
    target < current -> max(target, current - maxDelta)
    else -> current
}

private fun rpmToRadiansPerSecond(rpm: Double): Double = rpm * 2.0 * PI / 60.0

private fun lerp(start: Double, end: Double, fraction: Double): Double =
    start + (end - start) * fraction.coerceIn(0.0, 1.0)

private fun smoothStep(value: Double): Double {
    val x = value.coerceIn(0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)
}
