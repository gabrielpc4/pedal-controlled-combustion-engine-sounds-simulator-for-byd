package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

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
    val secondToFirstDownshiftRpm: Double = EngineTuning.DEFAULT_SECOND_TO_FIRST_DOWNSHIFT_RPM,
    /** 1st → 2nd upshift RPM when throttle is below [FULL_THROTTLE_UPSHIFT_THRESHOLD]. */
    val firstToSecondPartialThrottleUpshiftRpm: Double = EngineTuning.DEFAULT_FIRST_TO_SECOND_PARTIAL_UPSHIFT_RPM,
    /** When enabled, partial-throttle 1st → 2nd upshifts use [firstToSecondPartialThrottleUpshiftRpm]. */
    val secondGearEarlyShiftEnabled: Boolean = true,
) {
    companion object {
        private val bank = FmodCarProfiles.default
        val SKYLINE_R34 = EngineProfile(
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
    val isShifting: Boolean,
    val shiftDirection: ShiftDirection,
    /** Destination sampled when the current cosmetic shift began. */
    val shiftTargetRpm: Double,
    val shiftSerial: Long,
    val limiterActive: Boolean,
    val rawSpeedKmh: Double,
)

/**
 * EV longitudinal model with a speed-coupled fictional engine and automatic sound gearbox.
 * Real-car whole-km/h samples pass through a quantizer-aware interpolator before they can move the
 * tach or audio. Simulator physics stays continuous end-to-end; whole-km/h formatting is a
 * presentation concern and must never feed back into the synthetic tach.
 */
class EngineSimulation(initialProfile: EngineProfile = EngineProfile.SKYLINE_R34) {
    var profile: EngineProfile = initialProfile
        private set
    var manualShiftEnabled: Boolean = false
    private var ignitionState = EngineIgnitionState.OFF
    private var ignitionElapsedSeconds = 0.0
    private var shutdownElapsedSeconds = 0.0
    private var engineRpm = 0.0
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
    private var launchControlPhase = LaunchControlPhase.INACTIVE
    private var launchControlJitterPhase = 0.0
    private var launchControlArmedElapsedSeconds = 0.0
    private var launchControlArmedStartRpm = 0.0
    private var launchControlDisarmElapsedSeconds = 0.0
    private var launchControlDisarmStartRpm = 0.0
    private var launchControlTachCycleElapsedSeconds = 0.0
    private var launchControlTachCycleStartRpm = 0.0
    private var downshiftBoundaryKmhByGear = DoubleArray(profile.gearRatios.size)
    private var downshiftHysteresisKmhByGear = sortedDownshiftHysteresisKmhByGear(profile.gearRatios.size)
    private val externalSpeedEstimator = QuantizedSpeedEstimator()

    val state: DrivetrainState get() = snapshot()

    val ignition: EngineIgnitionState get() = ignitionState

    fun isIgnitionActive(): Boolean = ignitionState != EngineIgnitionState.OFF

    fun isEngineEngagedForUi(): Boolean {
        return ignitionState == EngineIgnitionState.STARTING || ignitionState == EngineIgnitionState.RUNNING
    }

    fun isShutdownPending(): Boolean = ignitionState == EngineIgnitionState.STOPPING

    fun isEngineAudioAudible(): Boolean = ignitionAudioGain() > 0.01

    fun ignitionAudioGain(): Double {
        return when (ignitionState) {
            EngineIgnitionState.OFF -> 0.0
            EngineIgnitionState.STARTING -> startupIgnitionAudioGain(ignitionElapsedSeconds)
            EngineIgnitionState.RUNNING -> 1.0
            EngineIgnitionState.STOPPING -> shutdownIgnitionAudioGain(shutdownElapsedSeconds)
        }
    }

    fun startIgnition() {
        if (ignitionState == EngineIgnitionState.RUNNING || ignitionState == EngineIgnitionState.STARTING) {
            return
        }

        ignitionState = EngineIgnitionState.STARTING
        ignitionElapsedSeconds = 0.0
        shutdownElapsedSeconds = 0.0
        engineRpm = 0.0
        limiterLatched = false
        shiftSerial = 0L
        resetLaunchControl()
    }

    /** Engage the engine at idle with no starter rev sequence (app launch, car swap). */
    fun engageAtIdle() {
        ignitionState = EngineIgnitionState.RUNNING
        ignitionElapsedSeconds = 0.0
        shutdownElapsedSeconds = 0.0
        engineRpm = profile.idleRpm
        limiterLatched = false
        resetLaunchControl()
    }

    fun isVehicleThrottleActive(): Boolean = ignitionState == EngineIgnitionState.RUNNING

    fun requestShutdown() {
        if (ignitionState == EngineIgnitionState.OFF || ignitionState == EngineIgnitionState.STOPPING) {
            return
        }

        ignitionState = EngineIgnitionState.STOPPING
        shutdownElapsedSeconds = 0.0
        currentGearIndex = 0
        activeShift = null
        secondsSinceShift = 10.0
        downshiftBoundaryKmhByGear.fill(0.0)
        limiterLatched = false
        resetLaunchControl()
    }

    /** @return true when shutdown finished and ignition returned to OFF. */
    fun advanceIgnition(dt: Double): Boolean {
        when (ignitionState) {
            EngineIgnitionState.OFF -> {
                engineRpm = 0.0
                limiterLatched = false
            }

            EngineIgnitionState.STARTING -> {
                ignitionElapsedSeconds += dt
                engineRpm = engineStartRpmAt(ignitionElapsedSeconds, profile.idleRpm)
                if (engineStartSettled(ignitionElapsedSeconds)) {
                    ignitionState = EngineIgnitionState.RUNNING
                    engineRpm = profile.idleRpm
                }
            }

            EngineIgnitionState.RUNNING -> Unit

            EngineIgnitionState.STOPPING -> {
                shutdownElapsedSeconds += dt
                engineRpm = approachExp(engineRpm, 0.0, SHUTDOWN_RPM_DECAY_SECONDS, dt).coerceAtLeast(0.0)
                limiterLatched = false
                val speedSettled = externalSpeedActive ||
                    simulatedPhysicalSpeedMps <= SHUTDOWN_SPEED_EPSILON_MPS
                if (engineRpm <= SHUTDOWN_RPM_EPSILON && speedSettled) {
                    engineRpm = 0.0
                    if (!externalSpeedActive) {
                        simulatedPhysicalSpeedMps = 0.0
                        vehicleSpeedMps = 0.0
                        rawExternalSpeedKmh = 0.0
                    }
                    ignitionState = EngineIgnitionState.OFF
                    return true
                }
            }
        }

        return false
    }

    fun updateProfile(updated: EngineProfile) {
        val gearboxChanged = profile.name != updated.name ||
            !profile.gearRatios.contentEquals(updated.gearRatios)
        profile = updated
        if (gearboxChanged) {
            // A shift belongs to the outgoing sound gearbox. Its target may not exist when a
            // seven-speed profile is replaced by a six-speed profile, so never carry it across.
            activeShift = null
            secondsSinceShift = 10.0
            limiterLatched = false
            resetLaunchControl()
        }
        currentGearIndex = currentGearIndex.coerceIn(0, profile.gearRatios.lastIndex)
        if (downshiftBoundaryKmhByGear.size != profile.gearRatios.size) {
            downshiftBoundaryKmhByGear = DoubleArray(profile.gearRatios.size)
            downshiftHysteresisKmhByGear = sortedDownshiftHysteresisKmhByGear(profile.gearRatios.size)
        }
        if (gearboxChanged) downshiftBoundaryKmhByGear.fill(0.0)
        engineRpm = when (ignitionState) {
            EngineIgnitionState.OFF -> 0.0
            EngineIgnitionState.STOPPING -> engineRpm.coerceAtLeast(0.0)
            else -> engineRpm.coerceIn(0.0, profile.limiterRpm)
        }
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        simulatedPhysicalSpeedMps = simulatedPhysicalSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
    }

    fun reset() {
        ignitionState = EngineIgnitionState.OFF
        ignitionElapsedSeconds = 0.0
        shutdownElapsedSeconds = 0.0
        engineRpm = 0.0
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
        resetLaunchControl()
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
        updateLaunchControl(
            rawThrottle = rawThrottle,
            transmissionPosition = input.transmissionPosition,
            externalSpeedActive = externalKmh != null || externalSpeedActive,
        )

        if (ignitionState == EngineIgnitionState.STOPPING && !externalSpeedActive && externalKmh == null) {
            applyShutdownBraking(dt)
            applyContinuousSimulatorSpeed()
        } else if (externalKmh != null) {
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
            applyContinuousSimulatorSpeed()
        }

        activeShift?.let { updateShift(it, dt) }
        if (activeShift == null) {
            secondsSinceShift += dt
        }

        advanceIgnition(dt)
        if (ignitionState == EngineIgnitionState.RUNNING) {
            updateSampleRpm(dt, input.transmissionPosition)
            updateLimiterLatch()
        }
        if (activeShift == null && input.transmissionPosition == TransmissionPosition.DRIVE &&
            ignitionState == EngineIgnitionState.RUNNING
        ) {
            if (manualShiftEnabled) {
                chooseManualIdleProtection()
            } else {
                chooseAutomaticShift()
            }
        }
        return snapshot()
    }

    /** Manual mode: upshift one gear when the driver requests it (steering wheel or UI). */
    fun requestManualUpshift(): Boolean {
        if (!manualShiftEnabled) {
            return false
        }
        if (ignitionState != EngineIgnitionState.RUNNING) {
            return false
        }
        if (activeShift != null) {
            return false
        }
        if (secondsSinceShift < profile.shiftDwellSeconds) {
            return false
        }
        if (currentGearIndex >= profile.gearRatios.lastIndex) {
            return false
        }
        beginShift(currentGearIndex + 1, ShiftDirection.UP)
        return true
    }

    /** Manual mode: downshift one gear when the driver requests it. */
    fun requestManualDownshift(): Boolean {
        if (!manualShiftEnabled) {
            return false
        }
        if (ignitionState != EngineIgnitionState.RUNNING) {
            return false
        }
        if (activeShift != null) {
            return false
        }
        if (secondsSinceShift < profile.shiftDwellSeconds) {
            return false
        }
        if (currentGearIndex <= 0) {
            return false
        }
        beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
        return true
    }

    private fun integrateElectricVehicle(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        applySimulatorRegen: Boolean,
    ) {
        val wheelTorque = wheelTorqueAtSpeed(profile, simulatedPhysicalSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val throttleDrive = if (LaunchControl.blocksDriveAtStandstill(simulatedPhysicalSpeedMps, filteredBrake)) {
            0.0
        } else {
            filteredThrottle * brakeOverride
        }
        val throttleConnected = transmissionPosition == TransmissionPosition.DRIVE && isVehicleThrottleActive()
        val requestedWheelTorque = if (throttleConnected) {
            wheelTorque * throttleDrive
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
            profile.vehicleMassKg * profile.tractionLimitMps2 * throttleDrive,
        )
        val serviceBrakeForce = filteredBrake * profile.vehicleMassKg * MAX_SERVICE_BRAKE_MPS2
        val regenerativeCoastForce = if (
            applySimulatorRegen && transmissionPosition == TransmissionPosition.DRIVE && simulatedPhysicalSpeedMps > 0.05
        ) {
            profile.vehicleMassKg * profile.simulatorCoastRegenMps2
        } else {
            0.0
        }
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 *
            simulatedPhysicalSpeedMps * simulatedPhysicalSpeedMps
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

    /** Full-service-brake deceleration while the engine shuts down. */
    private fun applyShutdownBraking(dt: Double) {
        val serviceBrakeForce = profile.vehicleMassKg * MAX_SERVICE_BRAKE_MPS2
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 *
            simulatedPhysicalSpeedMps * simulatedPhysicalSpeedMps
        val rollingResistance = if (simulatedPhysicalSpeedMps > 0.05) {
            profile.vehicleMassKg * GRAVITY_MPS2 * profile.rollingResistanceCoefficient
        } else {
            0.0
        }
        val acceleration = -(serviceBrakeForce + aerodynamicDrag + rollingResistance) /
            (profile.vehicleMassKg * profile.rotationalMassFactor)
        simulatedPhysicalSpeedMps = (simulatedPhysicalSpeedMps + acceleration * dt).coerceAtLeast(0.0)
        if (simulatedPhysicalSpeedMps < 0.04) {
            simulatedPhysicalSpeedMps = 0.0
        }
    }

    /** Keeps simulator motion in its native continuous domain; the dashboard rounds only text. */
    private fun applyContinuousSimulatorSpeed() {
        vehicleSpeedMps = simulatedPhysicalSpeedMps
        rawExternalSpeedKmh = simulatedPhysicalSpeedMps * 3.6
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
        if (ignitionState == EngineIgnitionState.OFF) {
            engineRpm = 0.0
            return
        }

        val safe = if (manualShiftEnabled) {
            profile.gearRatios.indices.filter { rpmForSpeed(it) >= MANUAL_IDLE_PROTECTION_RPM }
        } else {
            profile.gearRatios.indices.filter { rpmForSpeed(it) <= profile.redlineRpm * 0.92 }
        }
        currentGearIndex = if (manualShiftEnabled) {
            safe.lastOrNull() ?: 0
        } else {
            safe.firstOrNull() ?: profile.gearRatios.lastIndex
        }
        if (!manualShiftEnabled) {
            while (currentGearIndex < profile.gearRatios.lastIndex &&
                vehicleSpeedMps * 3.6 >= upshiftSpeedKmh(currentGearIndex)
            ) {
                val nextGear = currentGearIndex + 1
                downshiftBoundaryKmhByGear[nextGear] = upshiftSpeedKmh(currentGearIndex)
                currentGearIndex = nextGear
            }
        }
        engineRpm = rpmForSpeed(currentGearIndex)
        activeShift = null
        limiterLatched = false
        secondsSinceShift = 0.0
    }

    private fun updateSampleRpm(dt: Double, transmissionPosition: TransmissionPosition) {
        if (launchControlPhase != LaunchControlPhase.INACTIVE && transmissionPosition == TransmissionPosition.DRIVE) {
            updateLaunchControlRpm(dt)
            return
        }

        val target = when (transmissionPosition) {
            TransmissionPosition.DRIVE -> {
                val shift = activeShift
                when {
                    shift?.gearChanged == true -> {
                        rpmForSpeed(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                    }
                    shift?.direction == ShiftDirection.UP -> {
                        val cap = if (manualShiftEnabled) {
                            profile.limiterRpm
                        } else {
                            upshiftTriggerRpm(currentGearIndex)
                        }
                        min(rpmForSpeed(currentGearIndex), cap)
                    }
                    shift?.direction == ShiftDirection.DOWN -> {
                        rpmForSpeed(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                    }
                    else -> rpmForSpeed(currentGearIndex)
                }
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

    private fun chooseManualIdleProtection() {
        if (secondsSinceShift < profile.shiftDwellSeconds) {
            return
        }
        if (currentGearIndex <= 0) {
            return
        }
        if (rpmForSpeed(currentGearIndex) >= MANUAL_IDLE_PROTECTION_RPM) {
            return
        }
        beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
    }

    private fun chooseAutomaticShift() {
        if (secondsSinceShift < profile.shiftDwellSeconds) {
            return
        }

        val speedKmh = vehicleSpeedMps * 3.6
        val coupledRpmForShift = when {
            launchControlPhase == LaunchControlPhase.LAUNCHED &&
                currentGearIndex == 0 &&
                filteredThrottle >= FULL_THROTTLE_UPSHIFT_THRESHOLD -> {
                rpmForSpeed(currentGearIndex)
            }
            launchControlPhase == LaunchControlPhase.LAUNCHED -> {
                engineRpm
            }
            else -> {
                rpmForSpeed(currentGearIndex)
            }
        }
        if (currentGearIndex < profile.gearRatios.lastIndex &&
            filteredThrottle > SHIFT_THROTTLE_THRESHOLD &&
            (
                coupledRpmForShift >= upshiftTriggerRpm(currentGearIndex) ||
                    speedKmh >= upshiftTriggerSpeedKmh(currentGearIndex)
                )
        ) {
            beginShift(currentGearIndex + 1, ShiftDirection.UP)
            return
        }
        if (currentGearIndex > 0) {
            val previousUpshift = downshiftBoundaryKmhByGear[currentGearIndex]
                .takeIf { it > 0.0 }
                ?: upshiftSpeedKmh(currentGearIndex - 1)
            val demandDownshift = filteredThrottle > 0.78 &&
                speedKmh < previousUpshift - KICKDOWN_SPEED_MARGIN_KMH
            val shouldDownshift = if (currentGearIndex == 1) {
                // 2nd → 1st only: ignore the equal-band speed boundary and downshift at a fixed RPM.
                rpmForSpeed(currentGearIndex) <= profile.secondToFirstDownshiftRpm || demandDownshift
            } else {
                val hysteresisKmh = downshiftHysteresisKmhByGear[currentGearIndex]
                val downshiftSpeed = (previousUpshift - hysteresisKmh.toDouble()).coerceAtLeast(2.0)
                speedKmh <= downshiftSpeed || demandDownshift
            }
            if (shouldDownshift) {
                beginShift(currentGearIndex - 1, ShiftDirection.DOWN)
            }
        }
    }

    private fun upshiftSpeedKmh(gearIndex: Int): Double {
        return evenlySpacedUpshiftSpeedKmh(profile, gearIndex)
    }

    /**
     * Upshift as late as each car allows — just below its limiter latch, never above [EngineProfile.upshiftRpm].
     * In 1st gear with partial throttle, uses [EngineProfile.firstToSecondPartialThrottleUpshiftRpm]
     * when [EngineProfile.secondGearEarlyShiftEnabled] is true.
     */
    private fun upshiftTriggerRpm(gearIndex: Int): Double {
        val normalTrigger = upshiftTriggerRpmForProfile(profile)
        if (
            profile.secondGearEarlyShiftEnabled &&
            gearIndex == 0 &&
            filteredThrottle < FULL_THROTTLE_UPSHIFT_THRESHOLD
        ) {
            return profile.firstToSecondPartialThrottleUpshiftRpm
                .coerceAtMost(normalTrigger)
                .coerceAtLeast(profile.idleRpm + 500.0)
        }

        return normalTrigger
    }

    private fun upshiftTriggerSpeedKmh(gearIndex: Int): Double {
        val rpmSpan = (profile.redlineRpm - profile.idleRpm).coerceAtLeast(1.0)
        val triggerFraction = (upshiftTriggerRpm(gearIndex) - profile.idleRpm) / rpmSpan
        return upshiftSpeedKmh(gearIndex) * triggerFraction.coerceIn(0.0, 1.0)
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
        if (activeShift?.direction == ShiftDirection.UP) {
            limiterLatched = false
            return
        }

        if (manualShiftEnabled && currentGearIndex < profile.gearRatios.lastIndex) {
            val overspeeding = rawCoupledRpmForSpeed(currentGearIndex) >
                profile.limiterRpm - LIMITER_TRIGGER_MARGIN_RPM
            if (!overspeeding) {
                limiterLatched = false
                return
            }
        }

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

    private fun rawCoupledRpmForSpeed(gearIndex: Int): Double {
        val wheelRpm = vehicleSpeedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
        return profile.idleRpm + wheelRpm * evenlySpacedGearRatio(profile, gearIndex)
    }

    /** Coupled tach target for the current road speed and gear, with manual-mode limiter rules applied. */
    private fun rpmForSpeed(gearIndex: Int): Double {
        val raw = rawCoupledRpmForSpeed(gearIndex)
        val capped = raw.coerceAtMost(profile.limiterRpm)
        if (manualShiftEnabled && gearIndex < profile.gearRatios.lastIndex && raw > profile.limiterRpm) {
            return profile.limiterRpm
        }
        return capped.coerceAtLeast(profile.idleRpm)
    }

    private fun resetLaunchControl() {
        launchControlPhase = LaunchControlPhase.INACTIVE
        launchControlJitterPhase = 0.0
        launchControlArmedElapsedSeconds = 0.0
        launchControlArmedStartRpm = profile.idleRpm
        launchControlDisarmElapsedSeconds = 0.0
        launchControlDisarmStartRpm = profile.idleRpm
        launchControlTachCycleElapsedSeconds = 0.0
        launchControlTachCycleStartRpm = profile.idleRpm
    }

    private fun updateLaunchControl(
        rawThrottle: Double,
        transmissionPosition: TransmissionPosition,
        externalSpeedActive: Boolean,
    ) {
        val enabled = ignitionState == EngineIgnitionState.RUNNING &&
            transmissionPosition == TransmissionPosition.DRIVE &&
            !manualShiftEnabled &&
            !externalSpeedActive

        val previousPhase = launchControlPhase
        launchControlPhase = LaunchControl.advancePhase(
            phase = launchControlPhase,
            rawThrottle = rawThrottle,
            brake = filteredBrake,
            speedMps = vehicleSpeedMps,
            enabled = enabled,
        )
        if (launchControlPhase == LaunchControlPhase.ARMED && previousPhase != LaunchControlPhase.ARMED) {
            launchControlJitterPhase = 0.0
            launchControlArmedElapsedSeconds = 0.0
            launchControlArmedStartRpm = engineRpm
        }
        if (launchControlPhase == LaunchControlPhase.DISARMING && previousPhase == LaunchControlPhase.ARMED) {
            launchControlDisarmElapsedSeconds = 0.0
            launchControlDisarmStartRpm = engineRpm
        }
        if (launchControlPhase == LaunchControlPhase.LAUNCHED && previousPhase != LaunchControlPhase.LAUNCHED) {
            launchControlTachCycleElapsedSeconds = 0.0
            launchControlTachCycleStartRpm = engineRpm
        }
    }

    private fun updateLaunchControlRpm(dt: Double) {
        when (launchControlPhase) {
            LaunchControlPhase.INACTIVE -> Unit

            LaunchControlPhase.DISARMING -> {
                launchControlDisarmElapsedSeconds += dt
                val target = LaunchControl.disarmTargetRpm(
                    disarmElapsedSeconds = launchControlDisarmElapsedSeconds,
                    startRpm = launchControlDisarmStartRpm,
                    endRpm = launchControlArmedStartRpm,
                )
                engineRpm = approachExp(
                    engineRpm,
                    target,
                    LaunchControl.ARMED_RAMP_FOLLOW_SECONDS,
                    dt,
                ).coerceIn(
                    profile.idleRpm,
                    profile.limiterRpm,
                )
                if (launchControlDisarmElapsedSeconds >= LaunchControl.ARMED_RAMP_SECONDS) {
                    launchControlPhase = LaunchControlPhase.INACTIVE
                    engineRpm = launchControlArmedStartRpm.coerceIn(profile.idleRpm, profile.limiterRpm)
                }
            }

            LaunchControlPhase.ARMED -> {
                launchControlArmedElapsedSeconds += dt
                launchControlJitterPhase = launchControlJitterPhaseStep(dt, launchControlJitterPhase)
                val target = LaunchControl.armedTargetRpm(
                    armedElapsedSeconds = launchControlArmedElapsedSeconds,
                    jitterPhaseRadians = launchControlJitterPhase,
                    startRpm = launchControlArmedStartRpm,
                )
                val response = if (
                    launchControlArmedElapsedSeconds <
                        LaunchControl.ARMED_RAMP_SECONDS + LaunchControl.ARMED_SETTLE_SECONDS
                ) {
                    LaunchControl.ARMED_RAMP_FOLLOW_SECONDS
                } else {
                    LaunchControl.ARMED_JITTER_FOLLOW_SECONDS
                }
                engineRpm = approachExp(
                    engineRpm,
                    target,
                    response,
                    dt,
                ).coerceIn(
                    profile.idleRpm,
                    profile.limiterRpm,
                )
            }

            LaunchControlPhase.LAUNCHED -> {
                val shift = activeShift
                when {
                    shift != null -> {
                        val target = when {
                            shift.gearChanged -> {
                                rpmForSpeed(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                            }
                            shift.direction == ShiftDirection.UP -> {
                                min(
                                    rpmForSpeed(currentGearIndex),
                                    upshiftTriggerRpm(currentGearIndex),
                                )
                            }
                            shift.direction == ShiftDirection.DOWN -> {
                                rpmForSpeed(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                            }
                            else -> rpmForSpeed(currentGearIndex)
                        }
                        engineRpm = approachExp(
                            engineRpm,
                            target,
                            profile.syntheticRpmResponseSeconds,
                            dt,
                        ).coerceIn(profile.idleRpm, profile.limiterRpm)
                    }

                    LaunchControl.shouldPlayLaunchTachAnimation(currentGearIndex, filteredThrottle) -> {
                        launchControlTachCycleElapsedSeconds += dt
                        val target = LaunchControl.launchedTachTargetRpm(
                            cycleElapsedSeconds = launchControlTachCycleElapsedSeconds,
                            redlineRpm = profile.redlineRpm,
                            launchStartRpm = launchControlTachCycleStartRpm,
                        )
                        val response = if (target >= engineRpm) {
                            LaunchControl.LAUNCHED_TACH_REV_UP_FOLLOW_SECONDS
                        } else {
                            LaunchControl.LAUNCHED_TACH_BOUNCE_FOLLOW_SECONDS
                        }
                        engineRpm = approachExp(
                            engineRpm,
                            target,
                            response,
                            dt,
                        ).coerceIn(profile.idleRpm, profile.limiterRpm)
                    }

                    else -> {
                        val target = rpmForSpeed(currentGearIndex)
                        val response = if (filteredThrottle >= LaunchControl.FULL_THROTTLE_THRESHOLD) {
                            profile.syntheticRpmResponseSeconds
                        } else {
                            LaunchControl.LAUNCHED_ENGINE_BRAKE_RESPONSE_SECONDS
                        }
                        engineRpm = approachExp(
                            engineRpm,
                            target,
                            response,
                            dt,
                        ).coerceIn(profile.idleRpm, profile.limiterRpm)
                    }
                }
            }
        }
    }

    private fun snapshot(): DrivetrainState {
        val shift = activeShift
        return DrivetrainState(
            rpm = engineRpm,
            gear = currentGearIndex + 1,
            speedKmh = vehicleSpeedMps * 3.6,
            isShifting = shift != null,
            shiftDirection = shift?.direction ?: ShiftDirection.NONE,
            shiftTargetRpm = shift?.targetRpm ?: engineRpm,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            rawSpeedKmh = rawExternalSpeedKmh,
        )
    }

    companion object {
        private const val AIR_DENSITY_KG_M3 = 1.225
        private const val GRAVITY_MPS2 = 9.81
        private const val MAX_SERVICE_BRAKE_MPS2 = 11.2
        internal const val LIMITER_TRIGGER_MARGIN_RPM = 20.0
        private const val LIMITER_RELEASE_HYSTERESIS_RPM = 180.0
        private const val PEDAL_RELEASE_THRESHOLD = 0.001
        private const val SHIFT_THROTTLE_THRESHOLD = 0.10
        /** Pedal above this uses the car's normal upshift RPM even in 1st gear. */
        private const val FULL_THROTTLE_UPSHIFT_THRESHOLD = 0.98
        internal const val UPSHIFT_LIMITER_HEADROOM_RPM = 12.0
        /** Shift this many RPM before each car's configured upshift point so the run-up stays off the limiter layer. */
        internal const val UPSHIFT_EARLY_MARGIN_RPM = 80.0
        private const val KICKDOWN_SPEED_MARGIN_KMH = 10.0
        internal const val DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH = 4
        /** Neutral/Park rev-up: engine inertia spooling with no wheel load. */
        private const val NEUTRAL_REV_UP_RESPONSE_SECONDS = 0.55
        /** Neutral/Park rev-down: coasting back toward idle after lift-off. */
        private const val NEUTRAL_REV_DOWN_RESPONSE_SECONDS = 0.90
        private const val SHUTDOWN_RPM_DECAY_SECONDS = 0.58
        private const val SHUTDOWN_RPM_EPSILON = 12.0
        private const val SHUTDOWN_SPEED_EPSILON_MPS = 0.12
        /** Minimum coupled RPM manual mode keeps before auto-downshifting to avoid idle-layer bleed. */
        internal const val MANUAL_IDLE_PROTECTION_RPM = 2_400.0
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

/** Digitized A2MAC1 axle curves were sampled against a 180 km/h chart; keep that reference for physics. */
internal const val TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH = 180.0

/** Divides the configured road-speed range into one equal-width band per sound gear. */
internal fun evenlySpacedUpshiftSpeedKmh(profile: EngineProfile, gearIndex: Int): Double {
    val gearCount = profile.gearRatios.size.coerceAtLeast(1)
    return profile.topSpeedKmh * (gearIndex + 1).coerceIn(1, gearCount) / gearCount
}

/**
 * Upshift RPM for a car profile: each bank's shift point, nudged slightly early and capped below the limiter latch.
 */
internal fun upshiftTriggerRpmForProfile(profile: EngineProfile): Double {
    val latchRpm = profile.limiterRpm - EngineSimulation.LIMITER_TRIGGER_MARGIN_RPM
    val maxBeforeLimiter = latchRpm - EngineSimulation.UPSHIFT_LIMITER_HEADROOM_RPM
    val earlyShiftRpm = profile.upshiftRpm - EngineSimulation.UPSHIFT_EARLY_MARGIN_RPM

    return earlyShiftRpm
        .coerceAtMost(maxBeforeLimiter)
        .coerceAtLeast(profile.idleRpm + 500.0)
}

/** Speed (km/h) where [gearIndex] reaches [targetRpm] under the coupled synthetic tach model. */
internal fun speedKmhForCoupledRpm(profile: EngineProfile, gearIndex: Int, targetRpm: Double): Double {
    val ratio = evenlySpacedGearRatio(profile, gearIndex)
    val wheelRpm = (targetRpm - profile.idleRpm).coerceAtLeast(0.0) / ratio.coerceAtLeast(0.001)
    return wheelRpm * (2.0 * PI * profile.wheelRadiusMeters) / 60.0 * 3.6
}

/**
 * High gears keep no margin; lower gears keep up to the full 4 km/h.
 */
internal fun sortedDownshiftHysteresisKmhByGear(gearCount: Int): IntArray {
    val count = gearCount.coerceAtLeast(1)
    val hysteresis = IntArray(count)
    val downshiftCount = count - 1
    if (downshiftCount <= 0) {
        return hysteresis
    }

    for (gearIndex in 1 until count) {
        val downshiftIndex = gearIndex - 1
        val stepsFromHighestGear = downshiftCount - 1 - downshiftIndex
        hysteresis[gearIndex] = if (downshiftCount == 1) {
            EngineSimulation.DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH
        } else {
            (stepsFromHighestGear * EngineSimulation.DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH) / (downshiftCount - 1)
        }
    }

    return hysteresis
}

/** Makes each gear reach redline at the end of its equal-width speed band. */
internal fun evenlySpacedGearRatio(profile: EngineProfile, gearIndex: Int): Double {
    val boundaryKmh = evenlySpacedUpshiftSpeedKmh(profile, gearIndex)
    val boundaryWheelRpm = (boundaryKmh / 3.6) / (2.0 * PI * profile.wheelRadiusMeters) * 60.0
    val coupledRpm = (profile.redlineRpm - profile.idleRpm).coerceAtLeast(1.0)
    return coupledRpm / boundaryWheelRpm.coerceAtLeast(0.001)
}

/** Digitized total axle-output envelope, evaluated against normalized road speed. */
internal fun wheelTorqueAtSpeed(profile: EngineProfile, speedKmh: Double): Double {
    val normalizedSpeed = (speedKmh / TORQUE_CURVE_REFERENCE_TOP_SPEED_KMH).coerceIn(0.0, 1.0)
    return profile.frontPeakWheelTorqueNm * interpolateCurve(
        profile.frontWheelTorqueCurve,
        normalizedSpeed,
    ) + profile.rearPeakWheelTorqueNm * interpolateCurve(
        profile.rearWheelTorqueCurve,
        normalizedSpeed,
    )
}

/** Reconstructs continuous motion from the integer speed exposed by the BYD framework. */
internal class QuantizedSpeedEstimator {
    private var initialized = false
    private var quantizedInput = true
    private var estimateKmh = 0.0
    private var observedVelocityKmhPerSecond = 0.0
    private var previousMeasurementKmh = 0.0
    private var secondsSinceMeasurementChanged = 0.0
    private var crossingPredictionStale = false

    fun reset() {
        initialized = false
        quantizedInput = true
        estimateKmh = 0.0
        observedVelocityKmhPerSecond = 0.0
        previousMeasurementKmh = 0.0
        secondsSinceMeasurementChanged = 0.0
        crossingPredictionStale = false
    }

    fun update(measurementKmh: Double, dt: Double, responseSeconds: Double): Double {
        val measurement = measurementKmh.coerceAtLeast(0.0)
        if (!initialized) {
            initialized = true
            quantizedInput = isWholeKmh(measurement)
            estimateKmh = measurement
            previousMeasurementKmh = measurement
            return estimateKmh
        }

        // A firmware that exposes real fractional speed does not need quantizer reconstruction.
        // Once fractional data is observed, stay on the ordinary continuous follower until reset.
        if (quantizedInput && !isWholeKmh(measurement)) {
            quantizedInput = false
            observedVelocityKmhPerSecond = 0.0
            secondsSinceMeasurementChanged = 0.0
            crossingPredictionStale = false
        }
        if (!quantizedInput) {
            previousMeasurementKmh = measurement
            estimateKmh = approachExp(
                current = estimateKmh,
                target = measurement,
                timeConstant = responseSeconds.coerceIn(0.04, 0.80),
                dt = dt,
            ).coerceAtLeast(0.0)
            if (abs(estimateKmh - measurement) < 1.0e-6) estimateKmh = measurement
            return estimateKmh
        }

        secondsSinceMeasurementChanged += dt
        if (measurement != previousMeasurementKmh) {
            val elapsed = secondsSinceMeasurementChanged.coerceAtLeast(dt)
            val observedVelocity = ((measurement - previousMeasurementKmh) / elapsed).coerceIn(-45.0, 45.0)
            observedVelocityKmhPerSecond = if (observedVelocity * observedVelocityKmhPerSecond < 0.0) {
                observedVelocity
            } else {
                observedVelocityKmhPerSecond + (observedVelocity - observedVelocityKmhPerSecond) * 0.72
            }
            previousMeasurementKmh = measurement
            secondsSinceMeasurementChanged = 0.0
            crossingPredictionStale = false
        }

        if (abs(observedVelocityKmhPerSecond) > MINIMUM_TRACKED_VELOCITY_KMH_PER_SECOND) {
            val expectedCrossingSeconds = 1.0 / abs(observedVelocityKmhPerSecond)
            if (secondsSinceMeasurementChanged > expectedCrossingSeconds + STALE_CROSSING_GRACE_SECONDS) {
                crossingPredictionStale = true
            }
            if (crossingPredictionStale) {
                observedVelocityKmhPerSecond *= exp(-dt / STALE_VELOCITY_DECAY_SECONDS)
            }
        }

        val direction = when {
            observedVelocityKmhPerSecond > MINIMUM_TRACKED_VELOCITY_KMH_PER_SECOND -> 1.0
            observedVelocityKmhPerSecond < -MINIMUM_TRACKED_VELOCITY_KMH_PER_SECOND -> -1.0
            else -> 0.0
        }
        val target = if (direction == 0.0) {
            measurement
        } else {
            // An integer change identifies a quantizer boundary. Predict continuously from that
            // boundary using the measured time between crossings. The soft overrun covers a late
            // 20 ms vendor poll without letting a stale integer sample drift indefinitely.
            val boundaryKmh = measurement - direction * QUANTIZER_HALF_WIDTH_KMH
            val crossedBins = abs(observedVelocityKmhPerSecond) * secondsSinceMeasurementChanged
            val phase = if (crossedBins <= 1.0) {
                crossedBins
            } else {
                1.0 + MAXIMUM_POLL_OVERRUN_BINS *
                    (1.0 - exp(-(crossedBins - 1.0) * POLL_OVERRUN_DECAY))
            }
            (boundaryKmh + direction * phase).coerceAtLeast(0.0)
        }
        val crossingSeconds = if (direction == 0.0) {
            responseSeconds
        } else {
            1.0 / abs(observedVelocityKmhPerSecond)
        }
        val interpolationResponseSeconds = min(
            responseSeconds.coerceIn(0.04, 0.80) * 0.5,
            (crossingSeconds * 0.25).coerceAtLeast(0.035),
        )
        estimateKmh = approachExp(
            current = estimateKmh,
            target = target,
            timeConstant = interpolationResponseSeconds,
            dt = dt,
        ).coerceAtLeast(0.0)
        if (measurement == 0.0 && secondsSinceMeasurementChanged > 0.55 && estimateKmh < 0.04) {
            estimateKmh = 0.0
            observedVelocityKmhPerSecond = 0.0
        }
        return estimateKmh
    }

    private fun isWholeKmh(value: Double): Boolean = abs(value - round(value)) < 1.0e-6

    private companion object {
        const val QUANTIZER_HALF_WIDTH_KMH = 0.5
        const val MINIMUM_TRACKED_VELOCITY_KMH_PER_SECOND = 0.05
        const val MAXIMUM_POLL_OVERRUN_BINS = 0.08
        const val POLL_OVERRUN_DECAY = 3.0
        const val STALE_CROSSING_GRACE_SECONDS = 0.08
        const val STALE_VELOCITY_DECAY_SECONDS = 0.30
    }
}

private fun approachExp(current: Double, target: Double, timeConstant: Double, dt: Double): Double {
    if (timeConstant <= 0.0) return target
    val blend = 1.0 - kotlin.math.exp(-dt / timeConstant)
    return current + (target - current) * blend
}
