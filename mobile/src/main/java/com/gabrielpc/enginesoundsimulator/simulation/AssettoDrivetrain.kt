package com.gabrielpc.enginesoundsimulator.simulation

import com.gabrielpc.enginesoundsimulator.drive.BackfireSettings
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class AssettoDrivetrainFrame(
    var rpm: Double,
    var speedMetersPerSecond: Double,
    var gear: Int,
    var drivetrainSpeedRadiansPerSecond: Double,
    var driverThrottle: Double,
    var effectiveThrottle: Double,
    var brake: Double,
    var clutch: Double,
    var boost: Double,
    var bov: Double,
    var bovDecaySeconds: Double,
    var limiterPulse: Boolean,
    var backfireTriggered: Boolean,
    /** Shared Alfa sample selected for this one-shot, or -1 when no backfire fired. */
    var backfireSampleIndex: Int = -1,
    var shiftStarted: Boolean,
    var shiftRejected: Boolean,
    var shifting: Boolean,
    var shiftDirection: Int,
    var shiftProgress: Double,
    var authoredShiftDurationSeconds: Double = 0.0,
    var effectiveShiftDurationSeconds: Double = 0.0,
    var authoredClutchDurationSeconds: Double = 0.0,
    var effectiveClutchDurationSeconds: Double = 0.0,
    var tractionLimitActive: Boolean,
    var tractionLimitPulse: Boolean,
    /** Manual mode held at redline long enough to request a return to automatic shifting. */
    var requestAutomaticShiftMode: Boolean = false,
)

/**
 * Straight-line Assetto drivetrain used by the Audio Lab, ported with the same
 * control ordering, three-millisecond timing and authored car parameters.
 *
 * Vehicle speed and FMOD drivetrain speed are intentionally separate. Vehicle speed is the
 * physical/documented road speed, while the FMOD speed is a derived internal value used to make
 * equal-speed gear mapping possible without changing the bank's authored RPM thresholds.
 */
internal class AssettoDrivetrain(
    private var physics: AssettoPhysics,
    private var virtualGearProfile: VirtualGearProfile,
) {
    /**
     * Test branch behavior: keep the clutch disengaged in D so clutch-slip physics never fights
     * the driver, and always derive RPM from the mapped FMOD road speed in the current gear.
     */
    private val simplifiedDisengagedClutch = SIMPLIFIED_DISENGAGED_CLUTCH
    private var rpm = physics.engine.idleRpm
    private var speedMetersPerSecond = 0.0
    private var gear = 1
    private var sessionElapsedMilliseconds = 0.0
    private var clutchSignal = 0.0
    private var clutchSequence: List<AssettoCurvePoint> = emptyList()
    private var clutchSequenceElapsed = 0.0
    private var automaticGasCutoff = 0.0
    private var engineCutoff = 0.0
    private var shiftDirection = 0
    private var shiftTarget = 1
    private var shiftElapsed = 0.0
    private var shiftDuration = 0.0
    private var shiftStartRpm = 0.0
    private var authoredShiftDuration = 0.0
    private var authoredClutchDuration = 0.0
    private var effectiveClutchDuration = 0.0
    /** Prevents hard braking from chaining automatic downshifts back-to-back. */
    private var automaticDownshiftCooldownSeconds = 0.0
    private var shiftWasAutomatic = false
    private var manualShiftRequest = 0
    /**
     * Landing RPM recorded when each gear is selected by an upshift. Downshifts use this
     * remembered result instead of the bank's broad automatic downshift threshold so a gear is
     * held until it reaches the RPM where the preceding upshift originally landed it.
     */
    private var landingRpmByGear = DoubleArray(virtualGearProfile.virtualForwardGearCount + 1)
    private var previousTractionLimit = false
    private var turboQs = MutableList(physics.engine.turbos.size) { 0.0 }
    private var boost = 0.0
    private var bov = 0.0
    private var bovDecay = 10.0
    private var backfireArmed = false
    private var backfireReleaseTimer = 0.0
    private var previousBackfireThrottle = 0.0
    private var backfireSuppressedAfterShift = false
    private var backfireSettings = BackfireSettings()
    private var currentTransmissionPosition = TransmissionPosition.DRIVE
    private var nextBackfireSampleCursor = 0
    private var useOriginalBackfire = true
    private var limiterCounter = 0
    private var fmodDrivetrainSpeedMetersPerSecond = 0.0
    private var previousFmodWheelSpeed = 0.0
    // Launch control is deliberately kept in the drivetrain because this is where the
    // authoritative RPM, clutch, gear and wheel-coupling state lives. It is enabled by D input
    // from either pedal source and either shift mode; FMOD still receives resulting parameters.
    private var launchControlPhase = LaunchControlPhase.INACTIVE
    private var launchControlJitterPhase = 0.0
    private var launchControlArmedElapsedSeconds = 0.0
    private var launchControlArmedStartRpm = physics.engine.idleRpm
    private var launchControlDisarmElapsedSeconds = 0.0
    private var launchControlDisarmStartRpm = physics.engine.idleRpm
    private var launchControlTachCycleElapsedSeconds = 0.0
    private var launchControlTachCycleStartRpm = physics.engine.idleRpm
    private var cruisingShiftOffsetRpm = 0
    private var racingReturnMaxThrottle = 0.30
    private var racingReturnHoldSeconds = 10.0
    private var manualRedlineHoldSeconds = 1.0
    private var manualAutodownshiftRpm = 2_000.0
    private var automaticTransmissionMode = AutomaticTransmissionMode.CRUISING
    private var previousRawThrottle = 0.0
    private var lowThrottleElapsedSeconds = 0.0
    private var manualRedlineElapsedSeconds = 0.0
    private var requestAutomaticShiftMode = false
    private var driven = drivenAxle(physics.drivetrain.vehicle)
    private var aeroDrag = 0.0
    private var downforce = 0.0
    private val engineTorqueResult = EngineTorqueFrame()
    private val lastFrame = snapshot()

    fun updatePhysics(updated: AssettoPhysics) {
        physics = updated
        driven = drivenAxle(updated.drivetrain.vehicle)
        rpm = rpm.coerceIn(0.0, updated.engine.limiterRpm)
        gear = gear.coerceIn(0, virtualGearProfile.virtualForwardGearCount)
        landingRpmByGear = DoubleArray(virtualGearProfile.virtualForwardGearCount + 1)
        automaticDownshiftCooldownSeconds = 0.0
        shiftWasAutomatic = false
        turboQs = MutableList(updated.engine.turbos.size) { 0.0 }
        boost = 0.0
        bov = 0.0
        bovDecay = 10.0
        resetLaunchControl()
    }

    fun updateVirtualGearProfile(updated: VirtualGearProfile) {
        virtualGearProfile = updated
        gear = gear.coerceIn(0, updated.virtualForwardGearCount)
        landingRpmByGear = DoubleArray(updated.virtualForwardGearCount + 1)
    }

    fun updateBackfireSettings(updated: BackfireSettings) {
        backfireSettings = updated.normalized()
        backfireArmed = false
        backfireReleaseTimer = 0.0
        previousBackfireThrottle = 0.0
        backfireSuppressedAfterShift = false
        nextBackfireSampleCursor = 0
    }

    fun setUseOriginalBackfire(enabled: Boolean) { useOriginalBackfire = enabled }

    fun reset(engineRunning: Boolean) {
        rpm = if (engineRunning) physics.engine.idleRpm else 0.0
        speedMetersPerSecond = 0.0
        gear = 1
        sessionElapsedMilliseconds = 0.0
        clutchSignal = 0.0
        clutchSequence = emptyList()
        clutchSequenceElapsed = 0.0
        automaticGasCutoff = 0.0
        engineCutoff = 0.0
        shiftDirection = 0
        shiftTarget = 1
        shiftElapsed = 0.0
        shiftDuration = 0.0
        shiftStartRpm = if (engineRunning) physics.engine.idleRpm else 0.0
        authoredShiftDuration = 0.0
        authoredClutchDuration = 0.0
        effectiveClutchDuration = 0.0
        automaticDownshiftCooldownSeconds = 0.0
        shiftWasAutomatic = false
        manualShiftRequest = 0
        landingRpmByGear.fill(0.0)
        fmodDrivetrainSpeedMetersPerSecond = 0.0
        previousFmodWheelSpeed = 0.0
        resetLaunchControl()
        resetAutomaticTransmissionMode()
        manualRedlineElapsedSeconds = 0.0
        requestAutomaticShiftMode = false
        previousTractionLimit = false
        turboQs.fill(0.0)
        boost = 0.0
        bov = 0.0
        bovDecay = 10.0
        backfireArmed = false
        backfireReleaseTimer = 0.0
        previousBackfireThrottle = 0.0
        backfireSuppressedAfterShift = false
        limiterCounter = 0
        lastFrame.rpm = rpm
        lastFrame.speedMetersPerSecond = speedMetersPerSecond
        lastFrame.gear = gear
        lastFrame.drivetrainSpeedRadiansPerSecond = 0.0
        lastFrame.driverThrottle = 0.0
        lastFrame.effectiveThrottle = 0.0
        lastFrame.brake = 0.0
        lastFrame.clutch = clutchSignal
        lastFrame.boost = boost
        lastFrame.bov = bov
        lastFrame.bovDecaySeconds = bovDecay
        lastFrame.limiterPulse = false
        lastFrame.backfireTriggered = false
        lastFrame.backfireSampleIndex = -1
        lastFrame.shiftStarted = false
        lastFrame.shiftRejected = false
        lastFrame.shifting = shifting
        lastFrame.shiftDirection = shiftDirection
        lastFrame.shiftProgress = 0.0
        lastFrame.authoredShiftDurationSeconds = 0.0
        lastFrame.effectiveShiftDurationSeconds = 0.0
        lastFrame.authoredClutchDurationSeconds = 0.0
        lastFrame.effectiveClutchDurationSeconds = 0.0
        lastFrame.tractionLimitActive = false
        lastFrame.tractionLimitPulse = false
    }

    /**
     * Reconcile gear and RPM after the bank physics profile changes while the vehicle is still
     * moving. Road speed stays authoritative; gear is chosen from the virtual profile bands and
     * RPM follows the mapped FMOD speed in that gear.
     */
    fun seedFromRoadMotion(
        roadSpeedKmh: Double,
        fmodDrivetrainSpeedKmh: Double,
        transmissionPosition: TransmissionPosition,
    ) {
        currentTransmissionPosition = transmissionPosition
        if (transmissionPosition != TransmissionPosition.DRIVE) {
            reset(engineRunning = true)
            return
        }

        val cleanRoadKmh = roadSpeedKmh.coerceAtLeast(0.0)
        if (cleanRoadKmh <= 0.0) {
            reset(engineRunning = true)
            return
        }

        shiftDirection = 0
        shiftTarget = 1
        shiftElapsed = 0.0
        shiftDuration = 0.0
        shiftStartRpm = physics.engine.idleRpm
        authoredShiftDuration = 0.0
        authoredClutchDuration = 0.0
        effectiveClutchDuration = 0.0
        automaticDownshiftCooldownSeconds = 0.0
        shiftWasAutomatic = false
        manualShiftRequest = 0
        clutchSignal = 0.0
        clutchSequence = emptyList()
        clutchSequenceElapsed = 0.0
        automaticGasCutoff = 0.0
        engineCutoff = 0.0
        resetLaunchControl()

        speedMetersPerSecond = cleanRoadKmh / 3.6
        fmodDrivetrainSpeedMetersPerSecond = fmodDrivetrainSpeedKmh.coerceAtLeast(0.0) / 3.6
        previousFmodWheelSpeed = fmodDrivetrainSpeedMetersPerSecond / driven.radius

        val targetGear = virtualGearProfile.gearForRoadSpeedKmh(cleanRoadKmh)
            .coerceIn(1, virtualGearProfile.virtualForwardGearCount)
        gear = targetGear
        shiftTarget = targetGear

        landingRpmByGear.fill(0.0)
        val upshiftRpm = upshiftTriggerRpmForGear()
        for (indexedGear in 2..targetGear) {
            landingRpmByGear[indexedGear] = landingRpmAfterUpshift(
                fromGear = indexedGear - 1,
                upshiftRpm = upshiftRpm,
            )
        }

        rpm = if (shouldLockRpmToMappedRoadSpeed()) {
            coupledRpmForGear(gear)
        } else {
            physics.engine.idleRpm
        }
        if (physics.engine.limiterRpm > 0.0) {
            rpm = rpm.coerceAtMost(physics.engine.limiterRpm)
        }

        lastFrame.rpm = rpm
        lastFrame.speedMetersPerSecond = speedMetersPerSecond
        lastFrame.gear = gear
        lastFrame.drivetrainSpeedRadiansPerSecond = fmodDrivetrainSpeedMetersPerSecond / driven.radius
        lastFrame.driverThrottle = 0.0
        lastFrame.effectiveThrottle = 0.0
        lastFrame.brake = 0.0
        lastFrame.clutch = clutchSignal
        lastFrame.boost = boost
        lastFrame.bov = bov
        lastFrame.bovDecaySeconds = bovDecay
        lastFrame.limiterPulse = false
        lastFrame.backfireTriggered = false
        lastFrame.backfireSampleIndex = -1
        lastFrame.shiftStarted = false
        lastFrame.shiftRejected = false
        lastFrame.shifting = false
        lastFrame.shiftDirection = 0
        lastFrame.shiftProgress = 0.0
        lastFrame.authoredShiftDurationSeconds = 0.0
        lastFrame.effectiveShiftDurationSeconds = 0.0
        lastFrame.authoredClutchDurationSeconds = 0.0
        lastFrame.effectiveClutchDurationSeconds = 0.0
        lastFrame.tractionLimitActive = false
        lastFrame.tractionLimitPulse = false
    }

    fun requestShift(direction: Int): Boolean {
        if (direction !in -1..1 || direction == 0 || shifting) return false
        manualShiftRequest = direction
        return true
    }

    fun step(
        throttle: Double,
        brake: Double,
        transmissionPosition: TransmissionPosition,
        automaticShifting: Boolean,
        externalVehicleSpeedMetersPerSecond: Double?,
        fmodDrivetrainSpeedMetersPerSecond: Double?,
        deltaSeconds: Double,
        launchControlEnabled: Boolean,
        automaticTransmissionConfig: AutomaticTransmissionConfig,
    ): AssettoDrivetrainFrame {
        val dt = f32(deltaSeconds.coerceIn(0.0001, 0.050))
        cruisingShiftOffsetRpm = automaticTransmissionConfig.cruisingShiftOffsetRpm.coerceAtLeast(0)
        racingReturnMaxThrottle = automaticTransmissionConfig.racingReturnMaxThrottle.coerceIn(0.0, 1.0)
        racingReturnHoldSeconds = automaticTransmissionConfig.racingReturnHoldSeconds.coerceAtLeast(0.0)
        manualRedlineHoldSeconds = automaticTransmissionConfig.manualRedlineHoldSeconds.coerceAtLeast(0.0)
        manualAutodownshiftRpm = automaticTransmissionConfig.manualAutodownshiftRpm.coerceAtLeast(0.0)
        currentTransmissionPosition = transmissionPosition
        sessionElapsedMilliseconds += dt * 1_000.0
        externalVehicleSpeedMetersPerSecond?.let(::anchorVehicleSpeed)
        // In P/N the engine must remain a free-revving authored event. D is the only position
        // allowed to receive the derived FMOD drivetrain speed from the mapping layer.
        this.fmodDrivetrainSpeedMetersPerSecond = if (
            transmissionPosition == TransmissionPosition.DRIVE
        ) {
            (fmodDrivetrainSpeedMetersPerSecond ?: speedMetersPerSecond).coerceAtLeast(0.0)
        } else {
            0.0
        }

        if (transmissionPosition != TransmissionPosition.DRIVE) {
            if (gear != 0 || shifting) setGearImmediately(0)
            // A selector change to P/N cancels any D-only shift cut or clutch
            // profile immediately, so a free rev cannot inherit a prior shift.
            automaticGasCutoff = 0.0
            engineCutoff = 0.0
            clutchSequence = emptyList()
            clutchSequenceElapsed = 0.0
        } else if (gear == 0 && !shifting) {
            setGearImmediately(1)
        }

        var shiftStarted = false
        var shiftRejected = false
        var shiftCompleted = false
        automaticDownshiftCooldownSeconds = max(0.0, automaticDownshiftCooldownSeconds - dt)
        var eventDirection = if (shifting) shiftDirection else 0
        val rawGas = throttle.coerceIn(0.0, 1.0)
        val cleanBrake = brake.coerceIn(0.0, 1.0)
        updateLaunchControl(
            rawThrottle = rawGas,
            brake = cleanBrake,
            // The launch sequence is deliberately available in automatic and manual modes.
            // `automaticShifting` still controls only automatic gear decisions below.
            enabled = launchControlEnabled && transmissionPosition == TransmissionPosition.DRIVE,
        )
        updateAutomaticTransmissionMode(
            rawGas = rawGas,
            automaticShifting = automaticShifting,
            dt = dt,
        )
        updateManualAutodownshift(
            automaticShifting = automaticShifting,
            dt = dt,
        )
        updateAeroForSpeed(speedMetersPerSecond)

        val clutch = autoclutchStep(dt, rawGas, cleanBrake)
        var controlsGas = rawGas

        // Neutral and Park are free-revving positions. The zero gear used by
        // the drivetrain integrator must never be mistaken for a request to
        // select first gear when the engine reaches its authored shift RPM.
        val automaticRequest = if (transmissionPosition == TransmissionPosition.DRIVE) {
            automaticShiftDecision(
                controlsGas,
                clutch,
                automaticShifting,
                dt,
            )
        } else {
            0
        }
        if (automaticGasCutoff > 0.0) {
            automaticGasCutoff = f32(automaticGasCutoff - dt)
            controlsGas = 0.0
        }

        val requestedDirection = if (transmissionPosition == TransmissionPosition.DRIVE) {
            manualShiftRequest.takeIf { it != 0 } ?: automaticRequest
        } else {
            // Discard a stale request if the selector changed while a control
            // frame was in flight; P/N must not enter a synthetic shift cycle.
            0
        }
        val requestedManualShift = manualShiftRequest != 0
        manualShiftRequest = 0
        if (acceptShift(requestedDirection, clutch, dt, controlsGas)) {
            shiftStarted = true
            eventDirection = requestedDirection
            shiftWasAutomatic = !requestedManualShift
        } else if (requestedDirection != 0) {
            shiftRejected = true
        }

        if (shifting) {
            if (shiftDuration < shiftElapsed) {
                gear = shiftTarget
                eventDirection = shiftDirection
                shiftDirection = 0
                shiftCompleted = true
                if (eventDirection < 0 && shiftWasAutomatic) {
                    automaticDownshiftCooldownSeconds = AUTOMATIC_DOWNSHIFT_CHAIN_COOLDOWN_SECONDS
                }
            } else {
                shiftElapsed += dt
            }
        }

        val engineGas = if (engineCutoff > 0.0) 0.0 else controlsGas
        if (engineCutoff > 0.0) engineCutoff -= dt
        val engine = engineTorque(dt, controlsGas, engineGas)
        val vehicle = physics.drivetrain.vehicle
        val frontRadius = vehicle.frontWheelRadiusMeters.coerceAtLeast(1e-6)
        val rearRadius = vehicle.rearWheelRadiusMeters.coerceAtLeast(1e-6)
        // Road forces use the physical speed, but engine-to-wheel coupling uses the FMOD speed.
        // This lets 19 km/h of vehicle motion reach an authored 8,000 RPM shift point when the
        // equal-speed mapping says that first gear should occupy 0..19 km/h.
        val wheelSpeed = this.fmodDrivetrainSpeedMetersPerSecond / driven.radius
        val rollingForce = 2.0 * (
            vehicle.frontRollingResistance0 + vehicle.rearRollingResistance0 +
                (vehicle.frontRollingResistance1 + vehicle.rearRollingResistance1) *
                speedMetersPerSecond * speedMetersPerSecond
            )
        val frontBrake = 2.0 * vehicle.brakeMaximumTorque * vehicle.brakeFrontShare / frontRadius
        val rearBrake = 2.0 * vehicle.brakeMaximumTorque * (1.0 - vehicle.brakeFrontShare) / rearRadius
        var serviceBrakeForce = cleanBrake * (frontBrake + rearBrake)
        val totalNormal = vehicle.massKg * GRAVITY + downforce
        val brakeGrip = totalNormal * (
            vehicle.frontWeightFraction * vehicle.frontGripCoefficient +
                (1.0 - vehicle.frontWeightFraction) * vehicle.rearGripCoefficient
            )
        serviceBrakeForce = min(serviceBrakeForce, brakeGrip)
        val resistingForce = aeroDrag + rollingForce + serviceBrakeForce
        val effectiveMass = vehicle.massKg +
            2.0 * vehicle.frontWheelInertia / frontRadius.pow(2) +
            2.0 * vehicle.rearWheelInertia / rearRadius.pow(2)
        // Keep the selected gear visible during a shift, but briefly uncouple
        // the drivetrain exactly as the Lab does while the clutch changes.
        val ratio = abs(ratioForGear(physicsGear) * physics.drivetrain.finalDrive)
        var engineOmega = rpm * RADIAN_SECONDS_PER_RPM
        var driveForce = 0.0
        var clutchTorqueApplied = 0.0
        var requiredClutchTorque = 0.0
        var clutchCapacity = 0.0
        var gripCapacity = 0.0
        var tractionTorqueLimited = false
        if (ratio > 0.0 && clutch > 0.0) {
            val engineInertia = physics.engine.inertia + physics.drivetrain.gearboxInertia
            val slip = engineOmega - ratio * wheelSpeed
            val denominator = 1.0 / engineInertia +
                ratio * ratio / (effectiveMass * driven.radius * driven.radius)
            requiredClutchTorque = (
                slip / dt + engine.torque / engineInertia +
                    resistingForce * ratio / (effectiveMass * driven.radius)
                ) / denominator
            clutchCapacity = physics.drivetrain.clutchMaximumTorque * clutch.pow(1.5)
            val gripForce = driven.grip * totalNormal * driven.normalFraction
            gripCapacity = gripForce * driven.radius / ratio
            tractionTorqueLimited = engine.effectiveThrottle > 0.0 &&
                requiredClutchTorque > gripCapacity + 1e-6 && gripCapacity < clutchCapacity - 1e-6
            clutchTorqueApplied = min(
                clutchCapacity,
                min(gripCapacity, max(-clutchCapacity, requiredClutchTorque)),
            )
            driveForce = clutchTorqueApplied * ratio / driven.radius
            engineOmega += (engine.torque - clutchTorqueApplied) / engineInertia * dt
        } else {
            engineOmega += engine.torque / physics.engine.inertia.coerceAtLeast(0.001) * dt
        }

        if (externalVehicleSpeedMetersPerSecond == null) {
            val oldSpeed = speedMetersPerSecond
            speedMetersPerSecond = max(0.0, speedMetersPerSecond + (driveForce - resistingForce) / effectiveMass * dt)
            if (oldSpeed <= 0.0 && driveForce <= resistingForce) speedMetersPerSecond = 0.0
        }
        rpm = max(0.0, engineOmega * RPM_PER_RADIAN_SECOND)
        // LIMITER is a hard upper bound for the value sent to FMOD and the tachometer. This
        // applies equally to automatic and manual transmission: holding a gear at the limiter
        // now cuts torque without ever producing an above-limiter RPM sample.
        if (physics.engine.limiterRpm > 0.0) {
            rpm = rpm.coerceAtMost(physics.engine.limiterRpm)
            // In P/N there is no drivetrain load to absorb the limiter cut. Holding the
            // authored boundary while the pedal remains down prevents the repeated cut/coast
            // cycle from making a free-revving engine audibly bounce below redline.
            if (transmissionPosition != TransmissionPosition.DRIVE && rawGas > 0.0 && limiterCounter > 0) {
                rpm = physics.engine.limiterRpm
            }
        }
        updateLaunchControlRpm(dt, rawGas)
        if (shouldLockRpmToMappedRoadSpeed()) {
            applyMappedRoadSpeedRpm()
        }
        if (physics.engine.limiterRpm > 0.0) {
            rpm = rpm.coerceAtMost(physics.engine.limiterRpm)
        }
        previousFmodWheelSpeed = wheelSpeed
        val tractionActive = engine.effectiveThrottle > 0.0 && tractionTorqueLimited
        val tractionPulse = tractionActive && !previousTractionLimit
        previousTractionLimit = tractionActive
        // Reuse the frame object. The simulation has one consumer, so publishing these fields
        // in place removes one large allocation from every fixed-step update without changing
        // values.
        lastFrame.rpm = rpm
        lastFrame.speedMetersPerSecond = speedMetersPerSecond
        // Do not expose the internal neutral interval as gear 0 during a normal shift; effects
        // still use shiftStarted below.
        lastFrame.gear = if (shifting) shiftTarget else gear
        // Native FMOD receives this internal angular speed. The public vehicle speed remains
        // available separately through DrivetrainState.fmodDrivetrainSpeedKmh.
        lastFrame.drivetrainSpeedRadiansPerSecond = this.fmodDrivetrainSpeedMetersPerSecond / driven.radius
        lastFrame.driverThrottle = rawGas
        lastFrame.effectiveThrottle = engine.effectiveThrottle
        lastFrame.brake = cleanBrake
        lastFrame.clutch = clutch
        lastFrame.boost = boost
        lastFrame.bov = bov
        lastFrame.bovDecaySeconds = bovDecay
        lastFrame.limiterPulse = engine.limiterActive
        lastFrame.backfireTriggered = engine.backfire
        lastFrame.backfireSampleIndex = if (engine.backfire &&
            (backfireSettings.soundOnlyOverrideEnabled || !engine.naturalBankBackfire)
        ) {
            chooseBackfireSample()
        } else {
            -1
        }
        lastFrame.shiftStarted = shiftStarted
        lastFrame.shiftRejected = shiftRejected
        lastFrame.shifting = shifting
        lastFrame.shiftDirection = if (shiftStarted || shiftCompleted || shifting) eventDirection else 0
        // Preserve a final 1.0 sample so presentation code can finish a gear-ratio crossfade
        // without reintroducing an audible one-frame pitch step.
        lastFrame.shiftProgress = when {
            shifting -> (shiftElapsed / shiftDuration.coerceAtLeast(dt)).coerceIn(0.0, 1.0)
            shiftCompleted -> 1.0
            else -> 0.0
        }
        lastFrame.authoredShiftDurationSeconds = authoredShiftDuration
        lastFrame.effectiveShiftDurationSeconds = shiftDuration
        lastFrame.authoredClutchDurationSeconds = authoredClutchDuration
        lastFrame.effectiveClutchDurationSeconds = effectiveClutchDuration
        lastFrame.tractionLimitActive = tractionActive
        lastFrame.tractionLimitPulse = tractionPulse
        updateManualRedlineAutomaticMode(
            automaticShifting = automaticShifting,
            dt = dt,
        )
        if (launchControlPhase == LaunchControlPhase.LAUNCHED && gear > 1 && !shifting) {
            launchControlPhase = LaunchControlPhase.INACTIVE
        }
        lastFrame.requestAutomaticShiftMode = requestAutomaticShiftMode
        return lastFrame
    }

    fun frame(): AssettoDrivetrainFrame = lastFrame

    private val shifting: Boolean get() = shiftDirection != 0

    private fun anchorVehicleSpeed(speed: Double) {
        speedMetersPerSecond = speed.coerceAtLeast(0.0)
    }

    private fun setGearImmediately(target: Int) {
        ratioForGear(target)
        gear = target
        shiftDirection = 0
        shiftTarget = target
        shiftElapsed = 0.0
        shiftDuration = 0.0
        shiftStartRpm = rpm
        authoredShiftDuration = 0.0
        authoredClutchDuration = 0.0
        effectiveClutchDuration = 0.0
    }

    private fun autoclutchStep(dt: Double, gas: Double, brake: Double): Double {
        if (
            simplifiedDisengagedClutch &&
            currentTransmissionPosition == TransmissionPosition.DRIVE
        ) {
            clutchSequence = emptyList()
            clutchSequenceElapsed = 0.0
            clutchSignal = 0.0
            return 0.0
        }

        // In automatic D, a firm brake at walking speed means the driver is holding the car
        // stopped. The authored autoclutch rate is intentionally gentle for normal launches, but
        // letting that rate continue all the way to zero would keep the engine mechanically tied
        // to a stationary wheel while the presentation-speed estimate decays. Release immediately
        // in this one physical condition so the engine settles at its authored idle instead of
        // being dragged through zero RPM. This is a stop-protection invariant, not a new shift
        // threshold or a replacement for any bank clutch profile.
        if (
            gear == 1 &&
            !shifting &&
            brake >= STOPPED_CLUTCH_RELEASE_BRAKE &&
            speedMetersPerSecond <= STOPPED_CLUTCH_RELEASE_SPEED_MPS
        ) {
            clutchSequence = emptyList()
            clutchSequenceElapsed = 0.0
            clutchSignal = 0.0
            return 0.0
        }
        if (clutchSequence.isNotEmpty()) {
            clutchSignal = interpolateAssettoCurve(clutchSequence, clutchSequenceElapsed)
            clutchSequenceElapsed = f32(clutchSequenceElapsed + dt)
            if (clutchSequenceElapsed > clutchSequence.last().x) clutchSequence = emptyList()
            return clutchSignal.coerceIn(0.0, 1.0)
        }

        val spec = physics.drivetrain
        val controlGear = physicsGear
        val target = when {
            controlGear == -1 || controlGear == 1 -> when {
                rpm < spec.autoclutchMinimumRpm -> 0.0
                rpm > spec.autoclutchMaximumRpm -> 1.0
                else -> (rpm - spec.autoclutchMinimumRpm) /
                    (spec.autoclutchMaximumRpm - spec.autoclutchMinimumRpm).coerceAtLeast(1.0)
            }
            controlGear == 0 -> if (speedMetersPerSecond * 3.6 >= 5.0 || gas > 0.2) 1.0 else 0.0
            else -> if (rpm >= spec.autoclutchMinimumRpm) 1.0 else 0.0
        }
        val maximumStep = spec.autoclutchSpeed * dt
        clutchSignal += (target - clutchSignal).coerceIn(-maximumStep, maximumStep)
        return clutchSignal.coerceIn(0.0, 1.0)
    }

    private fun automaticShiftRpmForDecision(): Double {
        if (launchControlPhase == LaunchControlPhase.LAUNCHED) {
            // Launch tach animation can hold first-gear RPM at redline while road speed is still
            // near zero. Automatic upshifts must follow mapped wheel speed, not the staged RPM.
            return coupledRpmForGear(gear)
        }

        return rpm
    }

    private fun automaticShiftDecision(
        gas: Double,
        clutch: Double,
        enabled: Boolean,
        dt: Double,
    ): Int {
        if (!enabled || gear == -1 || shifting) return 0
        // While staging, launch control owns the engine RPM target. Do not let the automatic
        // threshold interpret the 5k staging ramp as a request to leave first gear before the
        // driver releases the brake.
        if (launchControlPhase == LaunchControlPhase.ARMED ||
            launchControlPhase == LaunchControlPhase.DISARMING
        ) {
            return 0
        }
        var request = 0
        if (simplifiedDisengagedClutch || clutch > 0.99 || gear == 0) {
            // Upshifts use the bank-authored automatic threshold. The equal-speed layer changes
            // only speed-to-RPM conversion.
            val shiftRpm = automaticShiftRpmForDecision()
            val upshiftRpm = effectiveUpshiftTriggerRpm()
            if (
                shiftRpm >= upshiftRpm &&
                gear < virtualGearProfile.virtualForwardGearCount &&
                gas > 0.2 && automaticGasCutoff <= 0.0
            ) {
                request = 1
                automaticGasCutoff = f32(physics.drivetrain.automaticGasCutoffSeconds)
            } else {
                val downshiftRpm = effectiveDownshiftRpmForCurrentGear()
                // Landing-RPM downshifts are an intentional app policy, but they must describe
                // a lift/braking phase rather than the tiny post-upshift settling error. When the
                // FMOD drivetrain speed is still increasing, a full-throttle car is accelerating
                // and cannot legitimately request the reverse shift immediately after an upshift.
                // The bank's RPM landing value remains the threshold once the documented/FMOD
                // speed is actually falling.
                // previousFmodWheelSpeed is stored as wheel angular speed (rad/s), so compare
                // it with the same normalized wheel quantity. Comparing raw m/s to rad/s here
                // would make every positive road speed look like a deceleration and would allow
                // the landing-RPM rule to chatter through every gear under full throttle.
                val currentFmodWheelSpeed = fmodDrivetrainSpeedMetersPerSecond / driven.radius
                val fmodDrivetrainSpeedDecreasing =
                    currentFmodWheelSpeed < previousFmodWheelSpeed - 1e-4
                if (
                    shiftRpm < downshiftRpm &&
                    gear > 1 && (simplifiedDisengagedClutch || clutch > 0.85) &&
                    (gas <= 0.2 || fmodDrivetrainSpeedDecreasing) &&
                    downshiftAllowed(gear - 1, dt) &&
                    automaticGasCutoff <= 0.0 &&
                    automaticDownshiftCooldownSeconds <= 0.0
                ) request = -1
            }
        }
        return request
    }

    private fun acceptShift(
        direction: Int,
        clutch: Double,
        dt: Double,
        gas: Double,
    ): Boolean {
        if (direction == 0 || shifting) return false
        val target = gear + direction
        val forwardGearCount = virtualGearProfile.virtualForwardGearCount
        if (target !in -1..forwardGearCount) return false
        if (direction < 0 && !downshiftAllowed(target, dt)) return false

        shiftDirection = direction
        shiftTarget = target
        shiftStartRpm = rpm
        shiftElapsed = 0.0
        authoredShiftDuration = if (direction > 0) physics.drivetrain.gearUpTimeSeconds
        else physics.drivetrain.gearDownTimeSeconds
        // Deliberate app-level divergence: fixed short timings make the controls feel
        // responsive, while FMOD still receives the same authored event triggers and
        // continuous parameters. The bank timing is retained above for diagnostics.
        shiftDuration = if (direction > 0) FIXED_UPSHIFT_SECONDS else FIXED_DOWNSHIFT_SECONDS
        if (direction > 0) {
            landingRpmByGear[target] = landingRpmAfterUpshift(
                fromGear = gear,
                upshiftRpm = upshiftTriggerRpmForGear(),
            )
        }
        if (direction > 0 && physics.drivetrain.autoCutoffTimeSeconds != 0.0) {
            engineCutoff = physics.drivetrain.autoCutoffTimeSeconds
        }
        val authoredProfile = if (direction > 0) {
            physics.drivetrain.autoclutchUpshiftProfile
        } else {
            physics.drivetrain.autoclutchDownshiftProfile
        }
        authoredClutchDuration = authoredProfile.maxOfOrNull { it.x } ?: 0.0
        if (physics.drivetrain.autoclutchOnChanges && clutch > 0.01 && !simplifiedDisengagedClutch) {
            // The authored downshift curve can keep the clutch open for ~1.5 s. Use a
            // compact equivalent curve so the drivetrain catches the new gear promptly;
            // this changes only host clutch timing, not FMOD gear/lift-off/turbo events.
            effectiveClutchDuration = shiftDuration
            clutchSequence = fixedClutchSequence(effectiveClutchDuration)
            clutchSequenceElapsed = 0.0
        }
        return true
    }

    /** True when RPM must follow mapped FMOD road speed in the current gear. */
    private fun shouldLockRpmToMappedRoadSpeed(): Boolean {
        if (!simplifiedDisengagedClutch) {
            return false
        }

        if (currentTransmissionPosition != TransmissionPosition.DRIVE) {
            return false
        }

        // Launch control owns RPM while staging or executing a launch. The simplified clutch path
        // otherwise pins first-gear RPM to idle whenever FMOD road speed is zero.
        if (launchControlPhase != LaunchControlPhase.INACTIVE) {
            return false
        }

        return gear >= 1
    }

    /**
     * RPM locked to mapped FMOD speed within the current gear band. Gear 1 starts at authored
     * idle when fmod speed is zero and rises immediately with road speed instead of staying at
     * idle until the raw ratio formula would exceed idle on its own.
     */
    private fun coupledRpmForGear(currentGear: Int): Double {
        if (currentGear < 1) {
            return physics.engine.idleRpm
        }

        val idle = physics.engine.idleRpm
        val upshiftRpm = upshiftTriggerRpmForGear()
        val forwardGearCount = virtualGearProfile.virtualForwardGearCount
        val fmodMps = fmodDrivetrainSpeedMetersPerSecond

        val lowerFmodMps = if (currentGear == 1) {
            0.0
        } else {
            fmodDrivetrainSpeedMpsAtRpm(upshiftRpm, currentGear - 1)
        }

        val upperRpm = if (currentGear >= forwardGearCount) {
            physics.engine.limiterRpm.coerceAtLeast(upshiftRpm)
        } else {
            upshiftRpm
        }
        val upperFmodMps = fmodDrivetrainSpeedMpsAtRpm(upperRpm, currentGear)

        val lowerRpm = if (currentGear == 1) {
            idle
        } else {
            landingRpmAfterUpshift(fromGear = currentGear - 1, upshiftRpm = upshiftRpm)
        }

        val fmodSpan = upperFmodMps - lowerFmodMps
        if (fmodSpan <= 1e-9) {
            return lowerRpm
        }

        // Do not clamp the lower end: when FMOD speed falls below this gear's band the RPM must
        // be allowed to drop under the landing value so automatic downshifts can fire.
        val fraction = (fmodMps - lowerFmodMps) / fmodSpan
        val interpolated = lowerRpm + fraction * (upperRpm - lowerRpm)
        return interpolated.coerceAtLeast(idle).let { rpm ->
            if (physics.engine.limiterRpm > 0.0) {
                rpm.coerceAtMost(physics.engine.limiterRpm)
            } else {
                rpm
            }
        }
    }

    /**
     * Follow mapped road speed exactly while driving. During a shift, linearly blend from the
     * RPM at shift request time to the target gear's coupled value over the shift duration.
     */
    private fun applyMappedRoadSpeedRpm() {
        if (shifting) {
            val target = coupledRpmForGear(shiftTarget)
            val progress = (shiftElapsed / shiftDuration.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            rpm = shiftStartRpm + (target - shiftStartRpm) * progress
        } else {
            rpm = coupledRpmForGear(gear)
        }

        if (physics.engine.limiterRpm > 0.0) {
            rpm = rpm.coerceAtMost(physics.engine.limiterRpm)
        }
    }

    /** Internal FMOD wheel speed that would produce [rpm] in [gearForRatio]. */
    private fun fmodDrivetrainSpeedMpsAtRpm(rpm: Double, gearForRatio: Int): Double {
        val ratio = abs(ratioForGear(gearForRatio) * physics.drivetrain.finalDrive)
        if (ratio <= 0.0) {
            return 0.0
        }

        return rpm * driven.radius / (ratio * RPM_PER_RADIAN_SECOND)
    }

    /** Bank-authored automatic upshift RPM, clamped to the hard limiter when one exists. */
    private fun upshiftTriggerRpmForGear(): Double {
        val authored = physics.drivetrain.automaticUpshiftRpm.toDouble()
        return if (physics.engine.limiterRpm > 0.0) {
            authored.coerceAtMost(physics.engine.limiterRpm)
        } else {
            authored
        }
    }

    private fun effectiveUpshiftTriggerRpm(): Double {
        val base = upshiftTriggerRpmForGear()
        if (automaticTransmissionMode == AutomaticTransmissionMode.RACING || cruisingShiftOffsetRpm <= 0) {
            return base
        }

        return AutomaticTransmissionPolicy.applyCruisingOffset(
            baseRpm = base,
            offsetRpm = cruisingShiftOffsetRpm,
            idleRpm = physics.engine.idleRpm,
        )
    }

    /** RPM that resulted from the preceding upshift at a shared wheel speed. */
    private fun landingRpmAfterUpshift(fromGear: Int, upshiftRpm: Double): Double {
        val fromRatio = abs(ratioForGear(fromGear))
        val targetRatio = abs(ratioForGear(fromGear + 1))
        if (fromRatio <= 1e-9 || targetRatio <= 1e-9) return upshiftRpm
        return (upshiftRpm * targetRatio / fromRatio).coerceAtLeast(physics.engine.idleRpm)
    }

    private fun downshiftRpmForCurrentGear(): Double {
        return landingRpmByGear.getOrNull(gear)
            ?.takeIf { it > 0.0 }
            ?: landingRpmAfterUpshift(
                fromGear = gear - 1,
                upshiftRpm = physics.drivetrain.automaticUpshiftRpm.toDouble(),
            )
    }

    private fun effectiveDownshiftRpmForCurrentGear(): Double {
        val base = downshiftRpmForCurrentGear()
        if (automaticTransmissionMode == AutomaticTransmissionMode.RACING || cruisingShiftOffsetRpm <= 0) {
            return base
        }

        return AutomaticTransmissionPolicy.applyCruisingOffset(
            baseRpm = base,
            offsetRpm = cruisingShiftOffsetRpm,
            idleRpm = physics.engine.idleRpm,
        )
    }

    private fun resetAutomaticTransmissionMode() {
        automaticTransmissionMode = AutomaticTransmissionMode.CRUISING
        previousRawThrottle = 0.0
        lowThrottleElapsedSeconds = 0.0
    }

    private fun updateAutomaticTransmissionMode(
        rawGas: Double,
        automaticShifting: Boolean,
        dt: Double,
    ) {
        if (currentTransmissionPosition != TransmissionPosition.DRIVE) {
            resetAutomaticTransmissionMode()
            previousRawThrottle = rawGas
            return
        }

        if (!automaticShifting) {
            previousRawThrottle = rawGas
            return
        }

        val throttleDelta = rawGas - previousRawThrottle
        val throttleRate = throttleDelta / dt.coerceAtLeast(1e-9)
        val stompDetected = rawGas >= AutomaticTransmissionPolicy.STOMP_MIN_THROTTLE &&
            throttleDelta >= AutomaticTransmissionPolicy.STOMP_MIN_DELTA &&
            throttleRate >= AutomaticTransmissionPolicy.STOMP_MIN_RATE_PER_SECOND

        if (
            automaticTransmissionMode == AutomaticTransmissionMode.CRUISING &&
            stompDetected &&
            launchControlPhase == LaunchControlPhase.INACTIVE
        ) {
            automaticTransmissionMode = AutomaticTransmissionMode.RACING
            lowThrottleElapsedSeconds = 0.0
            if (gear > 1 && !shifting) {
                manualShiftRequest = -1
            }
        }

        if (automaticTransmissionMode == AutomaticTransmissionMode.RACING) {
            if (rawGas <= racingReturnMaxThrottle) {
                lowThrottleElapsedSeconds += dt
                if (lowThrottleElapsedSeconds >= racingReturnHoldSeconds) {
                    automaticTransmissionMode = AutomaticTransmissionMode.CRUISING
                    lowThrottleElapsedSeconds = 0.0
                }
            } else {
                lowThrottleElapsedSeconds = 0.0
            }
        }

        previousRawThrottle = rawGas
    }

    private fun updateManualAutodownshift(
        automaticShifting: Boolean,
        dt: Double,
    ) {
        if (automaticShifting || currentTransmissionPosition != TransmissionPosition.DRIVE) {
            return
        }

        if (launchControlPhase != LaunchControlPhase.INACTIVE) {
            return
        }

        if (
            rpm < manualAutodownshiftRpm &&
            gear > 1 &&
            !shifting &&
            automaticDownshiftCooldownSeconds <= 0.0 &&
            downshiftAllowed(gear - 1, dt)
        ) {
            manualShiftRequest = -1
        }
    }

    private fun updateManualRedlineAutomaticMode(
        automaticShifting: Boolean,
        dt: Double,
    ) {
        requestAutomaticShiftMode = false

        if (automaticShifting || currentTransmissionPosition != TransmissionPosition.DRIVE) {
            manualRedlineElapsedSeconds = 0.0
            return
        }

        if (launchControlPhase != LaunchControlPhase.INACTIVE) {
            manualRedlineElapsedSeconds = 0.0
            return
        }

        val redlineThreshold = effectiveRedlineThresholdRpm()
        if (rpm >= redlineThreshold) {
            manualRedlineElapsedSeconds += dt
            if (manualRedlineElapsedSeconds >= manualRedlineHoldSeconds) {
                manualRedlineElapsedSeconds = 0.0
                automaticTransmissionMode = AutomaticTransmissionMode.RACING
                lowThrottleElapsedSeconds = 0.0
                requestAutomaticShiftMode = true
            }
        } else {
            manualRedlineElapsedSeconds = 0.0
        }
    }

    private fun effectiveRedlineThresholdRpm(): Double {
        if (physics.engine.limiterRpm > 0.0) {
            return physics.engine.limiterRpm
        }

        return upshiftTriggerRpmForGear().coerceAtLeast(physics.engine.idleRpm)
    }

    private fun downshiftAllowed(target: Int, dt: Double): Boolean {
        val spec = physics.drivetrain
        if (!spec.downshiftProtection) return true
        if (target == 0 && spec.downshiftLocksNeutral && speedMetersPerSecond * 3.6 > 2.0) return false
        if (target <= 0) return true
        return projectedRpmForGear(target, dt) <= physics.engine.limiterRpm + spec.downshiftOverrevRpm
    }

    private fun projectedRpmForGear(
        target: Int,
        dt: Double,
    ): Double {
        val spec = physics.drivetrain
        val wheelSpeed = fmodDrivetrainSpeedMetersPerSecond / driven.radius
        val wheelAcceleration = max(0.0, (wheelSpeed - previousFmodWheelSpeed) / dt.coerceAtLeast(1e-9))
        // Match downshift protection to the effective fixed transition, otherwise the
        // protection calculation would still predict using the bank's slower timing.
        val shiftTime = FIXED_DOWNSHIFT_SECONDS
        val projected = wheelSpeed + shiftTime * wheelAcceleration
        return projected * abs(ratioForGear(target) * spec.finalDrive) * RPM_PER_RADIAN_SECOND
    }

    private fun ratioForGear(gear: Int): Double {
        if (gear in 1..virtualGearProfile.virtualForwardGearCount) {
            return virtualGearProfile.ratioForVirtualGear(gear)
        }

        return physics.drivetrain.ratioForGear(gear)
    }

    private fun resetLaunchControl() {
        launchControlPhase = LaunchControlPhase.INACTIVE
        launchControlJitterPhase = 0.0
        launchControlArmedElapsedSeconds = 0.0
        launchControlArmedStartRpm = physics.engine.idleRpm
        launchControlDisarmElapsedSeconds = 0.0
        launchControlDisarmStartRpm = physics.engine.idleRpm
        launchControlTachCycleElapsedSeconds = 0.0
        launchControlTachCycleStartRpm = physics.engine.idleRpm
    }

    private fun updateLaunchControl(
        rawThrottle: Double,
        brake: Double,
        enabled: Boolean,
    ) {
        val previousPhase = launchControlPhase
        launchControlPhase = LaunchControl.advancePhase(
            phase = launchControlPhase,
            rawThrottle = rawThrottle,
            brake = brake,
            speedMps = speedMetersPerSecond,
            enabled = enabled,
        )
        if (previousPhase != launchControlPhase) {
            Log.d(
                "LaunchControl",
                "phase=$previousPhase->$launchControlPhase throttle=$rawThrottle brake=$brake " +
                    "speedMps=$speedMetersPerSecond rpm=$rpm enabled=$enabled",
            )
        }
        if (launchControlPhase == LaunchControlPhase.ARMED && previousPhase != LaunchControlPhase.ARMED) {
            launchControlJitterPhase = 0.0
            launchControlArmedElapsedSeconds = 0.0
            launchControlArmedStartRpm = rpm
        }
        if (launchControlPhase == LaunchControlPhase.DISARMING && previousPhase == LaunchControlPhase.ARMED) {
            launchControlDisarmElapsedSeconds = 0.0
            launchControlDisarmStartRpm = rpm
        }
        if (launchControlPhase == LaunchControlPhase.LAUNCHED && previousPhase != LaunchControlPhase.LAUNCHED) {
            launchControlTachCycleElapsedSeconds = 0.0
            launchControlTachCycleStartRpm = rpm
        }
    }

    private fun updateLaunchControlRpm(dt: Double, rawThrottle: Double) {
        when (launchControlPhase) {
            LaunchControlPhase.INACTIVE -> Unit

            LaunchControlPhase.DISARMING -> {
                launchControlDisarmElapsedSeconds += dt
                val target = LaunchControl.disarmTargetRpm(
                    disarmElapsedSeconds = launchControlDisarmElapsedSeconds,
                    startRpm = launchControlDisarmStartRpm,
                    endRpm = launchControlArmedStartRpm,
                )
                // The legacy main-branch path replaced the natural free-rev integration while
                // disarming. Assign the target directly so the engine torque step cannot add RPM
                // again before the next launch-control sample.
                rpm = target.coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
                if (launchControlDisarmElapsedSeconds >= LaunchControl.ARMED_RAMP_SECONDS) {
                    launchControlPhase = LaunchControlPhase.INACTIVE
                    rpm = launchControlArmedStartRpm.coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
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
                // Launch control owns the RPM in this phase. Applying the target after the
                // authored torque integration mirrors the old replacement path and prevents the
                // natural torque from immediately undoing the staging clamp.
                rpm = target.coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
            }

            LaunchControlPhase.LAUNCHED -> {
                val shiftActive = shifting
                if (shiftActive) {
                    // During a gear change the authored drivetrain ratio and the app's fixed
                    // clutch profile remain authoritative; the tach animation resumes in first.
                    val target = if (shiftDirection > 0) {
                        rpm.coerceAtMost(upshiftTriggerRpmForGear())
                    } else {
                        rpm.coerceAtMost(physics.engine.limiterRpm)
                    }
                    rpm = approachRpm(target, FIXED_UPSHIFT_SECONDS, dt)
                } else if (LaunchControl.shouldPlayLaunchTachAnimation(gear - 1, rawThrottle)) {
                    launchControlTachCycleElapsedSeconds += dt
                    val target = LaunchControl.launchedTachTargetRpm(
                        cycleElapsedSeconds = launchControlTachCycleElapsedSeconds,
                        redlineRpm = physics.engine.limiterRpm,
                        launchStartRpm = launchControlTachCycleStartRpm,
                    )
                    rpm = target.coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
                } else {
                    val target = rpm.coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
                    rpm = approachRpm(target, LaunchControl.LAUNCHED_ENGINE_BRAKE_RESPONSE_SECONDS, dt)
                }
            }
        }
    }

    private fun approachRpm(target: Double, responseSeconds: Double, dt: Double): Double {
        val response = responseSeconds.coerceAtLeast(0.001)
        val alpha = (1.0 - kotlin.math.exp(-dt / response)).coerceIn(0.0, 1.0)
        return (rpm + (target - rpm) * alpha).coerceIn(physics.engine.idleRpm, physics.engine.limiterRpm)
    }

    private val physicsGear: Int
        get() {
            if (shifting) {
                return 0
            }

            return gear
        }

    private fun engineTorque(dt: Double, controlsGas: Double, engineGas: Double): EngineTorqueFrame {
        val engine = physics.engine
        val backfire = backfireSettings
        // Automatic shifting briefly cuts engine gas. That cut is not a driver lift-off and must
        // never arm or trigger the global backfire policy.
        val shiftThrottleCut = shifting || automaticGasCutoff > 0.0 || engineCutoff > 0.0
        // The app override is intentionally limited to moving D above first gear. Neutral, P,
        // and first gear must remain free of override backfire pulses.
        val overrideBackfireAllowed = gear >= 2 ||
            (backfire.allowParkNeutralOverride && currentTransmissionPosition != TransmissionPosition.DRIVE)
        if (!overrideBackfireAllowed) {
            backfireArmed = false
            backfireReleaseTimer = 0.0
        }
        if (shiftThrottleCut) {
            // A transmission cut is not a driver release. Require a fresh throttle run after the
            // shift before the global override can arm again.
            backfireArmed = false
            backfireReleaseTimer = 0.0
            backfireSuppressedAfterShift = true
        }
        if (!shiftThrottleCut && controlsGas > 0.10) {
            backfireSuppressedAfterShift = false
        }
        val authoredBackfire = physics.engine.backfire
        if (useOriginalBackfire && !shiftThrottleCut && previousBackfireThrottle >= authoredBackfire.triggerGas && controlsGas < previousBackfireThrottle) {
            backfireReleaseTimer = min(10.0, backfireReleaseTimer + dt)
        }
        val naturalBankBackfire = useOriginalBackfire && !shiftThrottleCut &&
            previousBackfireThrottle >= authoredBackfire.triggerGas &&
            controlsGas > 0.0 && controlsGas < authoredBackfire.maximumGas &&
            rpm > authoredBackfire.minimumRpm && rpm <= authoredBackfire.maximumRpm &&
            backfireReleaseTimer > 1.0
        if (overrideBackfireAllowed && controlsGas >= backfire.armThrottle) {
            backfireArmed = true
            backfireReleaseTimer = 0.0
            backfireSuppressedAfterShift = false
        }
        if (!shiftThrottleCut && backfireArmed && controlsGas <= backfire.releaseThrottle) {
            backfireReleaseTimer = min(10.0, backfireReleaseTimer + dt)
        } else if (backfireArmed) {
            backfireReleaseTimer = 0.0
        }
        // This intentionally replaces the bank's per-car RPM/gas gates with one global policy:
        // a clear attempted run followed by a configurable lift-off delay is the user-facing
        // definition of backfire, independent of how a particular bank authored its thresholds.
        val triggerBackfire = overrideBackfireAllowed && !shiftThrottleCut && !backfireSuppressedAfterShift && backfireArmed &&
            controlsGas <= backfire.releaseThrottle &&
            rpm >= backfire.minimumRpm && rpm <= backfire.maximumRpm &&
            backfireReleaseTimer >= backfire.releaseDelaySeconds
        if (triggerBackfire) {
            backfireArmed = false
            backfireReleaseTimer = 0.0
        }
        previousBackfireThrottle = if (shiftThrottleCut) previousBackfireThrottle else controlsGas

        val mapped = interpolateAssettoCurve(engine.throttleCurve, engineGas)
        val limiterSteps = if (engine.limiterHz > 0.0) {
            (1_000.0 / engine.limiterHz).toInt() / 3
        } else {
            50
        }
        // The limiter gate is evaluated at the beginning of this fixed physics step, while the
        // engine is integrated below. A previous version intentionally exposed one overshoot
        // sample; that made manual mode visibly exceed the car's hard limiter and let an
        // automatic UP value above LIMITER start a shift too late. At the exact boundary we arm
        // the authored limiter pulse, then the integration result is clamped to the boundary.
        if (engine.limiterRpm > 0.0 && rpm >= engine.limiterRpm) limiterCounter = max(1, limiterSteps)
        val limiterActive = limiterCounter > 0
        if (limiterActive) limiterCounter -= 1
        val effective = if (limiterActive) 0.0 else mapped

        if (engine.turbos.isNotEmpty()) {
            var totalBoost = 0.0
            for (index in engine.turbos.indices) {
                val turbo = engine.turbos[index]
                val input = (effective * rpm / turbo.referenceRpm.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val target = input.pow(turbo.gamma)
                var q = turboQs[index]
                val lag = if (target > q) turbo.lagUp else turbo.lagDown
                q += (dt * lag).coerceIn(0.0, 1.0) * (target - q)
                if (turbo.wastegate > 0.0 && turbo.maximumBoost * q > turbo.wastegate) {
                    q = turbo.wastegate / turbo.maximumBoost.coerceAtLeast(0.001)
                }
                turboQs[index] = q
                totalBoost += turbo.maximumBoost * q
            }
            boost = totalBoost
            bov = if (boost * (1.0 - effective) > engine.turbos.first().bovThreshold) 1.0 else 0.0
            bovDecay = if (bov > 0.0) 0.0 else min(10.0, bovDecay + dt)
        }

        val power = interpolateAssettoCurve(engine.torqueCurve, rpm) * (1.0 + boost)
        val coast = coastTorque(rpm)
        var torque = coast + effective * (power - coast)
        if (rpm < engine.idleRpm) torque = max(torque, 15.0)
        engineTorqueResult.torque = torque
        engineTorqueResult.effectiveThrottle = effective
        engineTorqueResult.limiterActive = limiterActive
        // With the global policy disabled, the app only reports the lift-off edge to FMOD. The
        // bank's own backfire event, automation, and source randomisation then decide whether
        // anything is audible; no extracted sample or app threshold is imposed in this mode.
        engineTorqueResult.backfire = triggerBackfire || naturalBankBackfire
        engineTorqueResult.naturalBankBackfire = naturalBankBackfire
        return engineTorqueResult
    }

    private fun chooseBackfireSample(): Int {
        val allowed = backfireSettings.allowedSamples.sorted()
        if (allowed.isEmpty()) return -1
        val sample = allowed[nextBackfireSampleCursor % allowed.size]
        nextBackfireSampleCursor = (nextBackfireSampleCursor + 1) % allowed.size
        return sample
    }

    private fun coastTorque(rpm: Double): Double {
        val engine = physics.engine
        if (rpm <= engine.idleRpm) return 0.0
        val denominator = (1.0 - engine.coastNonLinearity) * engine.coastReferenceRpm - engine.idleRpm
        val linear = if (denominator == 0.0) 0.0 else -engine.coastReferenceTorque / denominator
        val nonlinearRpm = engine.coastNonLinearity * engine.coastReferenceRpm
        val quadratic = if (nonlinearRpm == 0.0) 0.0 else engine.coastReferenceTorque / nonlinearRpm.pow(2)
        val delta = rpm - engine.idleRpm
        return linear * delta - quadratic * delta * delta
    }

    private fun updateAeroForSpeed(speed: Double) {
        val vehicle = physics.drivetrain.vehicle
        val speedKmh = speed * 3.6
        val pressure = 0.5 * vehicle.airDensityKgM3 * speed * speed
        var dragArea = 0.0
        var liftArea = 0.0
        vehicle.aeroSurfaces.forEach { surface ->
            val angle = surface.angleDegrees + if (surface.controllerSpeedCurve.isEmpty()) {
                0.0
            } else {
                interpolateAssettoCurve(surface.controllerSpeedCurve, speedKmh)
            }
            val area = surface.chord * surface.span
            dragArea += area * surface.dragGain * interpolateAssettoCurve(surface.dragCurve, angle)
            liftArea += area * surface.liftGain * interpolateAssettoCurve(surface.liftCurve, angle)
        }
        aeroDrag = max(0.0, pressure * dragArea)
        downforce = max(0.0, pressure * liftArea)
    }

    private fun drivenAxle(vehicle: AssettoVehicleSpec): DrivenAxle = when {
        physics.drivetrain.traction.equals("FWD", ignoreCase = true) -> DrivenAxle(
            vehicle.frontWheelRadiusMeters.coerceAtLeast(1e-6),
            vehicle.frontWeightFraction,
            vehicle.frontGripCoefficient,
        )
        physics.drivetrain.traction.startsWith("AWD", ignoreCase = true) -> DrivenAxle(
            0.5 * (vehicle.frontWheelRadiusMeters + vehicle.rearWheelRadiusMeters),
            1.0,
            vehicle.frontWeightFraction * vehicle.frontGripCoefficient +
                (1.0 - vehicle.frontWeightFraction) * vehicle.rearGripCoefficient,
        )
        else -> DrivenAxle(
            vehicle.rearWheelRadiusMeters.coerceAtLeast(1e-6),
            1.0 - vehicle.frontWeightFraction,
            vehicle.rearGripCoefficient,
        )
    }

    private fun snapshot() = AssettoDrivetrainFrame(
        rpm = rpm,
        speedMetersPerSecond = speedMetersPerSecond,
        gear = gear,
        drivetrainSpeedRadiansPerSecond = 0.0,
        driverThrottle = 0.0,
        effectiveThrottle = 0.0,
        brake = 0.0,
        clutch = clutchSignal,
        boost = boost,
        bov = bov,
        bovDecaySeconds = bovDecay,
        limiterPulse = false,
        backfireTriggered = false,
        shiftStarted = false,
        shiftRejected = false,
        shifting = shifting,
        shiftDirection = shiftDirection,
        shiftProgress = 0.0,
        tractionLimitActive = false,
        tractionLimitPulse = false,
    )

    private class EngineTorqueFrame(
        var torque: Double = 0.0,
        var effectiveThrottle: Double = 0.0,
        var limiterActive: Boolean = false,
        var backfire: Boolean = false,
        var naturalBankBackfire: Boolean = false,
    )

    private data class DrivenAxle(val radius: Double, val normalFraction: Double, val grip: Double)

    private companion object {
        const val GRAVITY = 9.81
        const val STOPPED_CLUTCH_RELEASE_BRAKE = 0.2
        const val STOPPED_CLUTCH_RELEASE_SPEED_MPS = 1.0
        // A brief automatic-only gap keeps hard braking audible as separate shifts instead of
        // chaining two downshifts immediately after one another. Authored shift duration is kept.
        const val AUTOMATIC_DOWNSHIFT_CHAIN_COOLDOWN_SECONDS = 0.12
        const val FIXED_UPSHIFT_SECONDS = 0.10
        const val FIXED_DOWNSHIFT_SECONDS = 0.15
        const val RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * PI)
        const val RADIAN_SECONDS_PER_RPM = 1.0 / RPM_PER_RADIAN_SECOND
        const val SIMPLIFIED_DISENGAGED_CLUTCH = true
    }
}

private fun fixedClutchSequence(durationSeconds: Double): List<AssettoCurvePoint> {
    val duration = durationSeconds.coerceAtLeast(0.003)
    return listOf(
        AssettoCurvePoint(0.0, 1.0),
        AssettoCurvePoint((duration * 0.10).coerceAtLeast(0.003), 0.0),
        AssettoCurvePoint(duration * 0.60, 0.0),
        AssettoCurvePoint(duration, 1.0),
    )
}

private fun f32(value: Double): Double = value.toFloat().toDouble()
