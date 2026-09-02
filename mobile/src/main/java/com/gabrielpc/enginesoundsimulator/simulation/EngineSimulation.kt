package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.tuning.CurvePoint
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import com.gabrielpc.enginesoundsimulator.tuning.interpolateCurve
import kotlin.math.PI
import kotlin.math.floor
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
    /** Crankshaft-only calibration used while Park or Neutral disconnects the sound engine. */
    val freeRevCalibration: FreeRevCalibration = FreeRevCalibration.forEngine(
        name = name,
        idleRpm = idleRpm,
        limiterRpm = limiterRpm,
        maxTorqueNm = maxTorqueNm,
    ),
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
        /** Deterministic fallback used only before an installed v2 physics profile is loaded. */
        private val bank = FmodBankProfiles.find("lamborghini_huracan_trofeo_evo2_cabin")
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

internal fun EngineProfile.withAssettoPhysics(physics: AssettoPhysics): EngineProfile {
    val engine = physics.engine
    val drivetrain = physics.drivetrain
    val vehicle = drivetrain.vehicle
    val maximumTorque = engine.torqueCurve.maxOfOrNull(AssettoCurvePoint::y) ?: maxTorqueNm
    return copy(
        idleRpm = engine.idleRpm,
        redlineRpm = min(engine.limiterRpm - 100.0, drivetrain.automaticUpshiftRpm.toDouble())
            .coerceAtLeast(engine.idleRpm + 500.0),
        limiterRpm = engine.limiterRpm,
        upshiftRpm = drivetrain.automaticUpshiftRpm.toDouble().coerceAtMost(engine.limiterRpm),
        maxTorqueNm = maximumTorque,
        vehicleMassKg = vehicle.massKg,
        wheelRadiusMeters = when {
            drivetrain.traction.equals("FWD", ignoreCase = true) -> vehicle.frontWheelRadiusMeters
            drivetrain.traction.startsWith("AWD", ignoreCase = true) ->
                0.5 * (vehicle.frontWheelRadiusMeters + vehicle.rearWheelRadiusMeters)
            else -> vehicle.rearWheelRadiusMeters
        },
        gearRatios = drivetrain.forwardRatios.toDoubleArray(),
        upshiftDurationSeconds = drivetrain.gearUpTimeSeconds,
        downshiftDurationSeconds = drivetrain.gearDownTimeSeconds,
        freeRevCalibration = FreeRevCalibration.forEngine(
            name = name,
            idleRpm = engine.idleRpm,
            limiterRpm = engine.limiterRpm,
            maxTorqueNm = maximumTorque,
        ),
    )
}

data class DriverInput(
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    /** When present, road speed comes from the real car rather than this virtual vehicle. */
    val externalSpeedKmh: Double? = null,
    /** Applies simulator-only coast regen when integrating virtual road speed. */
    val simulateCoastRegen: Boolean = false,
    val transmissionPosition: TransmissionPosition = TransmissionPosition.DRIVE,
    /** Scales only virtual propulsion, leaving pedal-driven RPM and audio untouched. */
    val simulatedDriveForceScale: Double = 1.0,
    /** Scales only virtual lift-off regen; passive drag and service braking remain physical. */
    val simulatedCoastRegenScale: Double = 1.0,
    /** Virtual uphill grade applied continuously in simulated-pedal mode only. */
    val simulatedUphillDragGrade: Double = 0.0,
)

enum class ShiftDirection { NONE, UP, DOWN }

data class DrivetrainState(
    val rpm: Double,
    val gear: Int,
    val speedKmh: Double,
    val smoothedThrottle: Double,
    /** Direct pedal value used by audio, independent of EV torque smoothing. */
    val audioThrottle: Double,
    val smoothedBrake: Double,
    val engineLoad: Double,
    val isShifting: Boolean,
    val shiftDirection: ShiftDirection,
    val shiftProgress: Double,
    val shiftSerial: Long,
    val limiterActive: Boolean,
    /** Continuous road-speed reconstruction used by the tach and audio pitch. */
    val presentationSpeedKmh: Double,
    /** Signed presentation-speed acceleration/deceleration rate in km/h per second. */
    val presentationAccelerationKmhPerSecond: Double,
    val rawSpeedKmh: Double,
    /** Exact angular speed passed to Assetto's transmission event, in rad/s. */
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
)

/**
 * EV longitudinal model with a load-responsive fictional engine and automatic sound gearbox.
 * Integer BYD speed samples keep one estimate for drivetrain decisions and a separate
 * quantization-aware presentation estimate for road-coupled RPM. Combustion torque can move the
 * audible crank ahead of that presentation speed while the real vehicle speed stays authoritative.
 */
class EngineSimulation(initialProfile: EngineProfile = EngineProfile.SAMPLE_BANK_ENGINE) {
    var profile: EngineProfile = initialProfile
        private set
    var manualShiftEnabled: Boolean = false
    @Volatile
    var loadResponsiveRpmEnabled: Boolean = true
    @Volatile
    var throttleRpmBumpEnabled: Boolean = false
    private var ignitionState = EngineIgnitionState.OFF
    private var ignitionElapsedSeconds = 0.0
    private var shutdownElapsedSeconds = 0.0
    private var engineRpm = 0.0
    private var freeRevDynamics = FreeRevEngineDynamics(profile.freeRevCalibration)
    private var loadedEngineDynamics = LoadedEngineDynamics(profile.freeRevCalibration)
    private var freeRevLimiterPulse = false
    private var audioThrottle = 0.0
    private var previousThrottleForRpmBump = 0.0
    private var throttleRpmBumpProgress = 0.0
    private var vehicleSpeedMps = 0.0
    private var presentationSpeedMps = 0.0
    private var simulatedPhysicalSpeedMps = 0.0
    private var rawExternalSpeedKmh = 0.0
    private var currentGearIndex = 0
    private var filteredThrottle = 0.0
    private var filteredBrake = 0.0
    private var activeShift: ActiveShift? = null
    private var shiftSerial = 0L
    private var ignitionShiftCueDirection = ShiftDirection.NONE
    private var startupShiftCueFired = false
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
    private val presentationSpeedEstimator = QuantizedPresentationSpeedEstimator()
    private var assettoPhysics: AssettoPhysics? = null
    private var assettoDrivetrain: AssettoDrivetrain? = null
    private var assettoFrame: AssettoDrivetrainFrame? = null

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
        freeRevDynamics.reset()
        freeRevLimiterPulse = false
        audioThrottle = 0.0
        resetThrottleRpmBump()
        limiterLatched = false
        shiftSerial = 0L
        ignitionShiftCueDirection = ShiftDirection.NONE
        startupShiftCueFired = false
        resetLaunchControl()
        assettoDrivetrain?.reset(engineRunning = true)
        assettoFrame = assettoDrivetrain?.frame()
    }

    /** Engage the engine at idle with no starter rev sequence (app launch, car swap). */
    fun engageAtIdle() {
        ignitionState = EngineIgnitionState.RUNNING
        ignitionElapsedSeconds = 0.0
        shutdownElapsedSeconds = 0.0
        engineRpm = profile.idleRpm
        freeRevDynamics.reset()
        freeRevLimiterPulse = false
        audioThrottle = 0.0
        resetThrottleRpmBump()
        limiterLatched = false
        resetLaunchControl()
        assettoDrivetrain?.engageAtIdle()
        assettoFrame = assettoDrivetrain?.frame()
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
        resetThrottleRpmBump()
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
                val previousElapsed = ignitionElapsedSeconds
                ignitionElapsedSeconds += dt
                if (
                    !startupShiftCueFired &&
                    previousElapsed < ENGINE_START_CATCH_END_SECONDS &&
                    ignitionElapsedSeconds >= ENGINE_START_CATCH_END_SECONDS
                ) {
                    shiftSerial += 1
                    ignitionShiftCueDirection = ShiftDirection.UP
                    startupShiftCueFired = true
                }
                engineRpm = engineStartRpmAt(ignitionElapsedSeconds, profile.idleRpm)
                if (engineStartSettled(ignitionElapsedSeconds)) {
                    ignitionState = EngineIgnitionState.RUNNING
                    engineRpm = profile.idleRpm
                    ignitionShiftCueDirection = ShiftDirection.NONE
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
                        presentationSpeedMps = 0.0
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
        profile = updated
        freeRevDynamics.updateCalibration(updated.freeRevCalibration)
        loadedEngineDynamics.updateCalibration(updated.freeRevCalibration)
        freeRevLimiterPulse = false
        audioThrottle = 0.0
        resetThrottleRpmBump()
        currentGearIndex = currentGearIndex.coerceIn(0, profile.gearRatios.lastIndex)
        if (downshiftBoundaryKmhByGear.size != profile.gearRatios.size) {
            downshiftBoundaryKmhByGear = DoubleArray(profile.gearRatios.size)
            downshiftHysteresisKmhByGear = sortedDownshiftHysteresisKmhByGear(profile.gearRatios.size)
        }
        engineRpm = when (ignitionState) {
            EngineIgnitionState.OFF -> 0.0
            EngineIgnitionState.STOPPING -> engineRpm.coerceAtLeast(0.0)
            else -> engineRpm.coerceIn(0.0, profile.limiterRpm)
        }
        vehicleSpeedMps = vehicleSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        presentationSpeedMps = presentationSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
        simulatedPhysicalSpeedMps = simulatedPhysicalSpeedMps.coerceAtMost(maximumVehicleSpeedMps())
    }

    internal fun updateAssettoPhysics(updated: AssettoPhysics) {
        assettoPhysics = updated
        val drivetrain = assettoDrivetrain
        if (drivetrain == null) {
            assettoDrivetrain = AssettoDrivetrain(updated)
        } else {
            drivetrain.updatePhysics(updated)
        }
        assettoDrivetrain?.reset(ignitionState != EngineIgnitionState.OFF)
        assettoFrame = assettoDrivetrain?.frame()
        engineRpm = if (ignitionState == EngineIgnitionState.OFF) 0.0 else updated.engine.idleRpm
        currentGearIndex = 0
        activeShift = null
        limiterLatched = false
    }

    fun reset() {
        ignitionState = EngineIgnitionState.OFF
        ignitionElapsedSeconds = 0.0
        shutdownElapsedSeconds = 0.0
        engineRpm = 0.0
        freeRevDynamics.reset()
        freeRevLimiterPulse = false
        audioThrottle = 0.0
        resetThrottleRpmBump()
        vehicleSpeedMps = 0.0
        presentationSpeedMps = 0.0
        simulatedPhysicalSpeedMps = 0.0
        rawExternalSpeedKmh = 0.0
        currentGearIndex = 0
        filteredThrottle = 0.0
        filteredBrake = 0.0
        activeShift = null
        shiftSerial = 0L
        ignitionShiftCueDirection = ShiftDirection.NONE
        startupShiftCueFired = false
        secondsSinceShift = 10.0
        limiterLatched = false
        externalSpeedActive = false
        downshiftBoundaryKmhByGear.fill(0.0)
        externalSpeedEstimator.reset()
        presentationSpeedEstimator.reset()
        resetLaunchControl()
        assettoDrivetrain?.reset(engineRunning = false)
        assettoFrame = assettoDrivetrain?.frame()
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

        val externalKmh = input.externalSpeedKmh
            ?.coerceAtLeast(0.0)
            ?.let(::truncateRawSpeedKmh)

        if (assettoDrivetrain != null && ignitionState == EngineIgnitionState.RUNNING) {
            return updateAssettoDrivetrain(input, rawThrottle, externalKmh, dt)
        }
        updateLaunchControl(
            rawThrottle = rawThrottle,
            transmissionPosition = input.transmissionPosition,
            externalSpeedActive = externalKmh != null || externalSpeedActive,
        )

        if (ignitionState == EngineIgnitionState.STOPPING && !externalSpeedActive && externalKmh == null) {
            applyShutdownBraking(dt)
            applyQuantizedSimulatorSpeed(dt, rawThrottle, input.brake)
        } else if (externalKmh != null) {
            applyExternalSpeed(externalKmh, rawThrottle, input.brake, dt)
        } else {
            if (externalSpeedActive) {
                simulatedPhysicalSpeedMps = vehicleSpeedMps
                externalSpeedEstimator.reset()
                presentationSpeedEstimator.reset()
            }
            externalSpeedActive = false
            integrateElectricVehicle(
                dt,
                input.transmissionPosition,
                input.simulateCoastRegen && rawThrottle <= PEDAL_RELEASE_THRESHOLD,
                input.simulatedDriveForceScale,
                input.simulatedCoastRegenScale,
                input.simulatedUphillDragGrade,
            )
            if (input.transmissionPosition == TransmissionPosition.PARK) {
                simulatedPhysicalSpeedMps = 0.0
                vehicleSpeedMps = 0.0
                presentationSpeedMps = 0.0
            }
            applyQuantizedSimulatorSpeed(dt, rawThrottle, input.brake)
        }

        activeShift?.let { updateShift(it, dt) }
        if (activeShift == null) {
            secondsSinceShift += dt
        }

        advanceIgnition(dt)
        if (ignitionState == EngineIgnitionState.RUNNING) {
            updateSampleRpm(dt, input.transmissionPosition, rawThrottle)
            updateLimiterLatch(input.transmissionPosition)
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
        assettoDrivetrain?.let { return it.requestShift(1) }
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
        assettoDrivetrain?.let { return it.requestShift(-1) }
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

    private fun updateAssettoDrivetrain(
        input: DriverInput,
        rawThrottle: Double,
        externalSpeedKmh: Double?,
        dt: Double,
    ): DrivetrainState {
        val drivetrain = requireNotNull(assettoDrivetrain)
        val externalAnchor = if (externalSpeedKmh != null) {
            applyExternalSpeed(externalSpeedKmh, rawThrottle, input.brake, dt)
            presentationSpeedMps
        } else {
            if (externalSpeedActive) {
                simulatedPhysicalSpeedMps = vehicleSpeedMps
                externalSpeedEstimator.reset()
                presentationSpeedEstimator.reset()
            }
            externalSpeedActive = false
            if (input.transmissionPosition == TransmissionPosition.PARK) 0.0 else null
        }

        val frame = drivetrain.step(
            throttle = rawThrottle,
            brake = input.brake,
            transmissionPosition = input.transmissionPosition,
            automaticShifting = !manualShiftEnabled,
            externalSpeedMetersPerSecond = externalAnchor,
            simulatedUphillGrade = if (externalSpeedKmh == null) input.simulatedUphillDragGrade else 0.0,
            simulatedRegenStrength = if (externalSpeedKmh == null && input.simulateCoastRegen) {
                input.simulatedCoastRegenScale
            } else {
                0.0
            },
            deltaSeconds = dt,
        )
        assettoFrame = frame

        if (externalSpeedKmh == null) {
            simulatedPhysicalSpeedMps = frame.speedMetersPerSecond
            applyQuantizedSimulatorSpeed(dt, rawThrottle, input.brake)
        }
        engineRpm = frame.rpm
        audioThrottle = rawThrottle
        filteredThrottle = rawThrottle
        filteredBrake = input.brake.coerceIn(0.0, 1.0)
        limiterLatched = frame.limiterPulse
        currentGearIndex = (frame.gear - 1).coerceAtLeast(0)
        activeShift = null
        if (frame.shiftStarted) shiftSerial += 1
        return snapshot()
    }

    private fun integrateElectricVehicle(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        applySimulatorRegen: Boolean,
        driveForceScale: Double,
        coastRegenScale: Double,
        uphillDragGrade: Double,
    ) {
        val axleTorque = axleWheelTorqueAtSpeed(profile, simulatedPhysicalSpeedMps * 3.6)
        val brakeOverride = (1.0 - filteredBrake).coerceIn(0.0, 1.0)
        val throttleDrive = if (LaunchControl.blocksDriveAtStandstill(simulatedPhysicalSpeedMps, filteredBrake)) {
            0.0
        } else {
            filteredThrottle * brakeOverride
        }
        val throttleConnected = transmissionPosition == TransmissionPosition.DRIVE && isVehicleThrottleActive()
        val requestedWheelTorque = if (throttleConnected) {
            axleTorque.totalNm * throttleDrive
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
        val fullDriveForce = min(
            uncappedDriveForce,
            profile.vehicleMassKg * profile.tractionLimitMps2 * throttleDrive,
        )
        val driveForce = fullDriveForce * driveForceScale.coerceIn(0.0, 1.0)
        val serviceBrakeForce = filteredBrake * profile.vehicleMassKg * MAX_SERVICE_BRAKE_MPS2
        val regenerativeCoastForce = if (
            applySimulatorRegen && transmissionPosition == TransmissionPosition.DRIVE && simulatedPhysicalSpeedMps > 0.05
        ) {
            profile.vehicleMassKg * profile.simulatorCoastRegenMps2 * coastRegenScale.coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 * simulatedPhysicalSpeedMps.pow(2)
        val rollingResistance = if (simulatedPhysicalSpeedMps > 0.05 || driveForce > 0.0) {
            profile.vehicleMassKg * GRAVITY_MPS2 * profile.rollingResistanceCoefficient
        } else {
            0.0
        }
        val uphillForce = profile.vehicleMassKg * GRAVITY_MPS2 * uphillDragGrade.coerceIn(0.0, 0.30)
        val acceleration = (driveForce - serviceBrakeForce - regenerativeCoastForce - aerodynamicDrag - rollingResistance - uphillForce) /
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
        val aerodynamicDrag = 0.5 * AIR_DENSITY_KG_M3 * profile.dragAreaM2 * simulatedPhysicalSpeedMps.pow(2)
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

    /** Feeds SIM through the same truncated whole-km/h boundary exposed by the BYD framework. */
    private fun applyQuantizedSimulatorSpeed(
        dt: Double,
        throttle: Double,
        brake: Double,
    ) {
        rawExternalSpeedKmh = truncateRawSpeedKmh(simulatedPhysicalSpeedMps * 3.6)
        vehicleSpeedMps = externalSpeedEstimator.update(
            measurementKmh = rawExternalSpeedKmh,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        ) / 3.6
        presentationSpeedMps = presentationSpeedEstimator.update(
            measurementKmh = rawExternalSpeedKmh,
            throttle = throttle,
            brake = brake,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        ) / 3.6
    }

    private fun applyExternalSpeed(
        externalSpeedKmh: Double,
        throttle: Double,
        brake: Double,
        dt: Double,
    ) {
        rawExternalSpeedKmh = externalSpeedKmh
        val continuousKmh = externalSpeedEstimator.update(
            measurementKmh = externalSpeedKmh,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        )
        vehicleSpeedMps = continuousKmh / 3.6
        presentationSpeedMps = presentationSpeedEstimator.update(
            measurementKmh = externalSpeedKmh,
            throttle = throttle,
            brake = brake,
            dt = dt,
            responseSeconds = profile.externalSpeedSmoothingSeconds,
        ) / 3.6
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
            profile.gearRatios.indices.filter { decisionRpmForGear(it) >= MANUAL_IDLE_PROTECTION_RPM }
        } else {
            profile.gearRatios.indices.filter { decisionRpmForGear(it) <= profile.redlineRpm * 0.92 }
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
        engineRpm = presentationRpmForGear(currentGearIndex)
        activeShift = null
        limiterLatched = false
        secondsSinceShift = 0.0
    }

    private fun updateSampleRpm(
        dt: Double,
        transmissionPosition: TransmissionPosition,
        rawThrottle: Double,
    ) {
        audioThrottle = rawThrottle.coerceIn(0.0, 1.0)
        if (launchControlPhase != LaunchControlPhase.INACTIVE && transmissionPosition == TransmissionPosition.DRIVE) {
            updateLaunchControlRpm(dt)
            return
        }

        if (transmissionPosition != TransmissionPosition.DRIVE) {
            resetThrottleRpmBump()
            updateFreeRevRpm(dt)
            return
        }

        freeRevLimiterPulse = false
        val shift = activeShift
        if (shift == null) {
            val roadCoupledRpm = presentationRpmForGear(currentGearIndex)
            val coupledRpm = roadCoupledRpm + throttleRpmBumpRpm(
                roadCoupledRpm = roadCoupledRpm,
                rawThrottle = audioThrottle,
                dt = dt,
            )
            engineRpm = if (loadResponsiveRpmEnabled) {
                loadedEngineDynamics.step(
                    rpm = engineRpm,
                    coupledRpm = coupledRpm,
                    rawThrottle = audioThrottle,
                    idleRpm = profile.idleRpm,
                    limiterRpm = profile.limiterRpm,
                    upshiftRpm = profile.upshiftRpm,
                    gearIndex = currentGearIndex,
                    gearCount = profile.gearRatios.size,
                    dt = dt,
                )
            } else {
                approachExp(
                    current = engineRpm,
                    target = coupledRpm,
                    timeConstant = profile.syntheticRpmResponseSeconds,
                    dt = dt,
                ).coerceIn(profile.idleRpm, profile.limiterRpm)
            }
            return
        }

        resetThrottleRpmBump()

        val target = when {
            shift.gearChanged -> {
                presentationRpmForGear(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
            }
            shift.direction == ShiftDirection.UP -> {
                val cap = if (manualShiftEnabled) {
                    profile.limiterRpm
                } else {
                    upshiftTriggerRpm(currentGearIndex)
                }
                min(presentationRpmForGear(currentGearIndex), cap)
            }
            shift.direction == ShiftDirection.DOWN -> {
                presentationRpmForGear(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
            }
            else -> presentationRpmForGear(currentGearIndex)
        }
        val response = when {
            shift.gearChanged -> (shift.durationSeconds * 0.30).coerceAtLeast(0.018)
            else -> profile.syntheticRpmResponseSeconds
        }
        engineRpm = approachExp(engineRpm, target, response, dt).coerceIn(profile.idleRpm, profile.limiterRpm)
    }

    /**
     * Optional one-shot presentation cue: a pedal press over 20% temporarily uses the RPM that
     * the next km/h would produce. It decays smoothly and never changes road speed or shifting.
     */
    private fun throttleRpmBumpRpm(
        roadCoupledRpm: Double,
        rawThrottle: Double,
        dt: Double,
    ): Double {
        val crossedThreshold = previousThrottleForRpmBump <= THROTTLE_RPM_BUMP_THRESHOLD &&
            rawThrottle > THROTTLE_RPM_BUMP_THRESHOLD
        previousThrottleForRpmBump = rawThrottle
        if (!throttleRpmBumpEnabled) {
            throttleRpmBumpProgress = 0.0
            return 0.0
        }
        if (crossedThreshold) {
            throttleRpmBumpProgress = 1.0
        }
        throttleRpmBumpProgress = approachExp(
            current = throttleRpmBumpProgress,
            target = 0.0,
            timeConstant = THROTTLE_RPM_BUMP_DECAY_SECONDS,
            dt = dt,
        )
        if (throttleRpmBumpProgress <= THROTTLE_RPM_BUMP_SETTLE_PROGRESS) {
            throttleRpmBumpProgress = 0.0
            return 0.0
        }

        val nextKmhRpm = rpmForSpeed(
            gearIndex = currentGearIndex,
            speedMps = presentationSpeedMps + THROTTLE_RPM_BUMP_SPEED_METERS_PER_SECOND,
        ).coerceAtMost(profile.limiterRpm)
        return (nextKmhRpm - roadCoupledRpm).coerceAtLeast(0.0) * throttleRpmBumpProgress
    }

    private fun resetThrottleRpmBump() {
        previousThrottleForRpmBump = 0.0
        throttleRpmBumpProgress = 0.0
    }

    /** P/N runs its own 3 ms crankshaft integration so it matches the Audio Lab control cadence. */
    private fun updateFreeRevRpm(dt: Double) {
        var remainingSeconds = dt
        freeRevLimiterPulse = false
        while (remainingSeconds > 0.0) {
            val stepSeconds = min(FREE_REV_STEP_SECONDS, remainingSeconds)
            val frame = freeRevDynamics.step(
                rpm = engineRpm,
                rawThrottle = audioThrottle,
                idleRpm = profile.idleRpm,
                limiterRpm = profile.limiterRpm,
                dt = stepSeconds,
            )
            engineRpm = frame.rpm
            freeRevLimiterPulse = freeRevLimiterPulse || frame.limiterActive
            remainingSeconds -= stepSeconds
        }
    }

    private fun chooseManualIdleProtection() {
        if (secondsSinceShift < profile.shiftDwellSeconds) {
            return
        }
        if (currentGearIndex <= 0) {
            return
        }
        if (decisionRpmForGear(currentGearIndex) >= MANUAL_IDLE_PROTECTION_RPM) {
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
                decisionRpmForGear(currentGearIndex)
            }
            launchControlPhase == LaunchControlPhase.LAUNCHED -> {
                engineRpm
            }
            else -> {
                decisionRpmForGear(currentGearIndex)
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
                speedKmh <= firstToSecondDownshiftSpeedKmh(previousUpshift) || demandDownshift
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
     * Keep 2nd engaged across the point where 1st selected it.
     *
     * The configured 4,000 RPM point still applies whenever it is lower, but it must never
     * overlap the real 1st → 2nd upshift speed — especially when the speed signal is quantized.
     */
    private fun firstToSecondDownshiftSpeedKmh(previousUpshiftKmh: Double): Double {
        val configuredRpmSpeed = speedKmhForCoupledRpm(
            profile,
            gearIndex = 1,
            targetRpm = profile.secondToFirstDownshiftRpm,
        )
        val stableSpeed = previousUpshiftKmh - FIRST_TO_SECOND_DOWNSHIFT_HYSTERESIS_KMH
        return min(configuredRpmSpeed, stableSpeed).coerceAtLeast(2.0)
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
        val rpmSpan = (profile.upshiftRpm - profile.idleRpm).coerceAtLeast(1.0)
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

    private fun updateLimiterLatch(transmissionPosition: TransmissionPosition) {
        if (transmissionPosition != TransmissionPosition.DRIVE) {
            limiterLatched = freeRevLimiterPulse
            return
        }
        if (activeShift?.direction == ShiftDirection.UP) {
            limiterLatched = false
            return
        }

        if (manualShiftEnabled && currentGearIndex < profile.gearRatios.lastIndex) {
            val overspeeding = decisionRawCoupledRpmForGear(currentGearIndex) >
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

    private fun decisionRawCoupledRpmForGear(gearIndex: Int): Double {
        return rawCoupledRpmForSpeed(gearIndex, vehicleSpeedMps)
    }

    private fun rawCoupledRpmForSpeed(gearIndex: Int, speedMps: Double): Double {
        return coupledRpmAtSpeedKmh(profile, gearIndex, speedMps * 3.6)
    }

    private fun decisionRpmForGear(gearIndex: Int): Double {
        return rpmForSpeed(gearIndex, vehicleSpeedMps)
    }

    /** Audible and tach target from reconstructed presentation speed. */
    private fun presentationRpmForGear(gearIndex: Int): Double {
        return rpmForSpeed(gearIndex, presentationSpeedMps)
    }

    private fun rpmForSpeed(gearIndex: Int, speedMps: Double): Double {
        val raw = rawCoupledRpmForSpeed(gearIndex, speedMps)
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
                                presentationRpmForGear(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                            }
                            shift.direction == ShiftDirection.UP -> {
                                min(
                                    presentationRpmForGear(currentGearIndex),
                                    upshiftTriggerRpm(currentGearIndex),
                                )
                            }
                            shift.direction == ShiftDirection.DOWN -> {
                                presentationRpmForGear(shift.targetGearIndex).coerceAtMost(profile.limiterRpm)
                            }
                            else -> presentationRpmForGear(currentGearIndex)
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
                        val target = presentationRpmForGear(currentGearIndex)
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
        val assetto = assettoFrame
        if (assetto != null && ignitionState == EngineIgnitionState.RUNNING) {
            return DrivetrainState(
                rpm = engineRpm,
                gear = assetto.gear,
                speedKmh = vehicleSpeedMps * 3.6,
                smoothedThrottle = filteredThrottle,
                audioThrottle = audioThrottle,
                smoothedBrake = filteredBrake,
                engineLoad = assetto.effectiveThrottle.coerceIn(0.0, 1.0),
                isShifting = assetto.shifting,
                shiftDirection = when {
                    assetto.shiftDirection > 0 -> ShiftDirection.UP
                    assetto.shiftDirection < 0 -> ShiftDirection.DOWN
                    else -> ShiftDirection.NONE
                },
                shiftProgress = assetto.shiftProgress,
                shiftSerial = shiftSerial,
                limiterActive = assetto.limiterPulse,
                presentationSpeedKmh = presentationSpeedMps * 3.6,
                presentationAccelerationKmhPerSecond = presentationSpeedEstimator.presentationVelocityKmhPerSecond,
                rawSpeedKmh = rawExternalSpeedKmh,
                drivetrainSpeedRadiansPerSecond = assetto.drivetrainSpeedRadiansPerSecond,
                boost = assetto.boost,
                bov = assetto.bov,
                bovDecaySeconds = assetto.bovDecaySeconds,
                limiterPulse = assetto.limiterPulse,
                backfireTriggered = assetto.backfireTriggered,
                shiftStarted = assetto.shiftStarted,
                shiftRejected = assetto.shiftRejected,
                tractionLimitActive = assetto.tractionLimitActive,
                tractionLimitPulse = assetto.tractionLimitPulse,
            )
        }
        val shift = activeShift
        val axleTorque = axleWheelTorqueAtSpeed(profile, vehicleSpeedMps * 3.6)
        val peakWheelTorque = profile.frontPeakWheelTorqueNm + profile.rearPeakWheelTorqueNm
        val wheelTorqueFraction = (axleTorque.totalNm / peakWheelTorque).coerceIn(0.0, 1.0)
        return DrivetrainState(
            rpm = engineRpm,
            gear = currentGearIndex + 1,
            speedKmh = vehicleSpeedMps * 3.6,
            smoothedThrottle = filteredThrottle,
            audioThrottle = audioThrottle,
            smoothedBrake = filteredBrake,
            engineLoad = (filteredThrottle * (0.35 + 0.65 * wheelTorqueFraction)).coerceIn(0.0, 1.0),
            isShifting = shift != null,
            shiftDirection = shift?.direction ?: ignitionShiftCueDirection,
            shiftProgress = shift?.let { (it.elapsedSeconds / it.durationSeconds).coerceIn(0.0, 1.0) } ?: 0.0,
            shiftSerial = shiftSerial,
            limiterActive = limiterLatched,
            presentationSpeedKmh = presentationSpeedMps * 3.6,
            presentationAccelerationKmhPerSecond = presentationSpeedEstimator.presentationVelocityKmhPerSecond,
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
        private const val THROTTLE_RPM_BUMP_THRESHOLD = 0.20
        private const val THROTTLE_RPM_BUMP_SPEED_METERS_PER_SECOND = 1.0 / 3.6
        private const val THROTTLE_RPM_BUMP_DECAY_SECONDS = 0.38
        private const val THROTTLE_RPM_BUMP_SETTLE_PROGRESS = 0.01
        private const val SHIFT_THROTTLE_THRESHOLD = 0.10
        /** Pedal above this uses the car's normal upshift RPM even in 1st gear. */
        private const val FULL_THROTTLE_UPSHIFT_THRESHOLD = 0.98
        internal const val UPSHIFT_LIMITER_HEADROOM_RPM = 12.0
        /** Shift this many RPM before each car's configured upshift point so the run-up stays off the limiter layer. */
        internal const val UPSHIFT_EARLY_MARGIN_RPM = 80.0
        private const val KICKDOWN_SPEED_MARGIN_KMH = 10.0
        /** Separate the 2nd → 1st boundary from the speed where 1st selected 2nd. */
        private const val FIRST_TO_SECOND_DOWNSHIFT_HYSTERESIS_KMH = 4.0
        internal const val DOWNSHIFT_SPEED_HYSTERESIS_MAX_KMH = 4
        private const val FREE_REV_STEP_SECONDS = 0.003
        private const val SHUTDOWN_RPM_DECAY_SECONDS = 0.58
        private const val SHUTDOWN_RPM_EPSILON = 12.0
        private const val SHUTDOWN_SPEED_EPSILON_MPS = 0.12
        /** Minimum coupled RPM manual mode keeps before auto-downshifting to avoid idle-layer bleed. */
        internal const val MANUAL_IDLE_PROTECTION_RPM = 2_400.0
    }

    private data class ActiveShift(
        val targetGearIndex: Int,
        val direction: ShiftDirection,
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

/** Road-coupled engine RPM before redline and limiter handling. */
internal fun coupledRpmAtSpeedKmh(profile: EngineProfile, gearIndex: Int, speedKmh: Double): Double {
    val speedMps = speedKmh.coerceAtLeast(0.0) / 3.6
    val wheelRpm = speedMps / (2.0 * PI * profile.wheelRadiusMeters) * 60.0

    return profile.idleRpm + wheelRpm * evenlySpacedGearRatio(profile, gearIndex)
}

/** BYD and simulated RAW speed use the lower whole km/h for every `[N, N + 1)` interval. */
internal fun truncateRawSpeedKmh(speedKmh: Double): Double = floor(speedKmh.coerceAtLeast(0.0))

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

/** Smooths the truncated integer speed used for authoritative drivetrain decisions. */
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

        val zeroHasSettled = measurement == 0.0 && secondsSinceMeasurementChanged > 0.55
        val intervalTarget = when {
            zeroHasSettled -> 0.0
            observedVelocityKmhPerSecond > 0.20 -> measurement + 0.90
            observedVelocityKmhPerSecond < -0.20 -> measurement + 0.10
            else -> measurement
        }
        val target = intervalTarget.coerceAtLeast(0.0)
        val omega = 2.0 / responseSeconds.coerceIn(0.08, 0.80)
        val acceleration = omega * omega * (target - estimateKmh) - 2.0 * omega * estimateVelocityKmhPerSecond
        val previousEstimate = estimateKmh
        estimateVelocityKmhPerSecond = (estimateVelocityKmhPerSecond + acceleration * dt).coerceIn(-45.0, 45.0)
        estimateKmh = (estimateKmh + estimateVelocityKmhPerSecond * dt).coerceAtLeast(0.0)
        if ((previousEstimate <= target && estimateKmh > target) || (previousEstimate >= target && estimateKmh < target)) {
            estimateKmh = target
            estimateVelocityKmhPerSecond = 0.0
        }
        if (zeroHasSettled && estimateKmh < 0.04) {
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
