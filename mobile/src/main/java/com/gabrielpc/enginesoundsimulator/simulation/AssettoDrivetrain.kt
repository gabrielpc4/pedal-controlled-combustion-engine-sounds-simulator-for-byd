package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class AssettoDrivetrainFrame(
    val rpm: Double,
    val speedMetersPerSecond: Double,
    val gear: Int,
    val drivetrainSpeedRadiansPerSecond: Double,
    val driverThrottle: Double,
    val effectiveThrottle: Double,
    val brake: Double,
    val clutch: Double,
    val boost: Double,
    val bov: Double,
    val bovDecaySeconds: Double,
    val limiterPulse: Boolean,
    val backfireTriggered: Boolean,
    val shiftStarted: Boolean,
    val shiftRejected: Boolean,
    val shifting: Boolean,
    val shiftDirection: Int,
    val shiftProgress: Double,
    val tractionLimitActive: Boolean,
    val tractionLimitPulse: Boolean,
)

/**
 * Straight-line Assetto drivetrain used by the Audio Lab, ported with the same
 * control ordering, three-millisecond timing and authored car parameters.
 */
internal class AssettoDrivetrain(private var physics: AssettoPhysics) {
    private var rpm = physics.engine.idleRpm
    private var speedMetersPerSecond = 0.0
    private var gear = 1
    private var sessionElapsedMilliseconds = 0.0
    private var clutchSignal = 0.0
    private var clutchSequence: List<AssettoCurvePoint> = emptyList()
    private var clutchSequenceElapsed = 0.0
    private var autoblipStartMilliseconds: Double? = null
    private var automaticGasCutoff = 0.0
    private var engineCutoff = 0.0
    private var shiftDirection = 0
    private var shiftTarget = 1
    private var shiftElapsed = 0.0
    private var shiftDuration = 0.0
    private var manualShiftRequest = 0
    private var previousWheelSpeed = 0.0
    private var previousTractionLimit = false
    private var turboQs = MutableList(physics.engine.turbos.size) { 0.0 }
    private var boost = 0.0
    private var bov = 0.0
    private var bovDecay = 10.0
    private var backfirePeakGas = 0.6
    private var backfireArmLevel = physics.engine.backfire.triggerGas
    private var backfireFireBelow = 0.25
    private var backfireArmed = false
    private var backfireTimer = 0.0
    private var limiterCounter = 0
    private var lastFrame = snapshot()

    fun updatePhysics(updated: AssettoPhysics) {
        physics = updated
        rpm = rpm.coerceIn(0.0, updated.engine.limiterRpm)
        gear = gear.coerceIn(0, updated.drivetrain.forwardRatios.size)
        turboQs = MutableList(updated.engine.turbos.size) { 0.0 }
        boost = 0.0
        bov = 0.0
        bovDecay = 10.0
    }

    fun reset(engineRunning: Boolean) {
        rpm = if (engineRunning) physics.engine.idleRpm else 0.0
        speedMetersPerSecond = 0.0
        gear = 1
        sessionElapsedMilliseconds = 0.0
        clutchSignal = 0.0
        clutchSequence = emptyList()
        clutchSequenceElapsed = 0.0
        autoblipStartMilliseconds = null
        automaticGasCutoff = 0.0
        engineCutoff = 0.0
        shiftDirection = 0
        shiftTarget = 1
        shiftElapsed = 0.0
        shiftDuration = 0.0
        manualShiftRequest = 0
        previousWheelSpeed = 0.0
        previousTractionLimit = false
        turboQs.fill(0.0)
        boost = 0.0
        bov = 0.0
        bovDecay = 10.0
        backfirePeakGas = 0.6
        backfireArmLevel = physics.engine.backfire.triggerGas
        backfireFireBelow = 0.25
        backfireArmed = false
        backfireTimer = 0.0
        limiterCounter = 0
        lastFrame = snapshot()
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
        externalSpeedMetersPerSecond: Double?,
        simulatedPedalsGearCalibration: SimulatedPedalsGearCalibration?,
        deltaSeconds: Double,
    ): AssettoDrivetrainFrame {
        val dt = f32(deltaSeconds.coerceIn(0.0001, 0.020))
        sessionElapsedMilliseconds += dt * 1_000.0
        externalSpeedMetersPerSecond?.let(::anchorSpeed)

        if (transmissionPosition != TransmissionPosition.DRIVE) {
            if (gear != 0 || shifting) setGearImmediately(0, simulatedPedalsGearCalibration)
            // A selector change to P/N cancels any D-only shift cut or clutch
            // profile immediately, so a free rev cannot inherit a prior shift.
            automaticGasCutoff = 0.0
            engineCutoff = 0.0
            clutchSequence = emptyList()
            clutchSequenceElapsed = 0.0
            autoblipStartMilliseconds = null
        } else if (gear == 0 && !shifting) {
            setGearImmediately(1, simulatedPedalsGearCalibration)
        }

        var shiftStarted = false
        var shiftRejected = false
        var shiftCompleted = false
        var eventDirection = if (shifting) shiftDirection else 0
        val rawGas = throttle.coerceIn(0.0, 1.0)
        val cleanBrake = brake.coerceIn(0.0, 1.0)
        val (aeroDrag, downforce) = aeroForSpeed(speedMetersPerSecond)

        val clutch = autoclutchStep(dt, rawGas)
        var controlsGas = rawGas
        val autoblipStarted = autoblipStartMilliseconds
        if (autoblipStarted != null && physics.drivetrain.autoblipProfileMilliseconds.isNotEmpty()) {
            val elapsed = sessionElapsedMilliseconds - autoblipStarted
            if (elapsed >= 0.0 && elapsed < physics.drivetrain.autoblipProfileMilliseconds.last().x) {
                controlsGas = max(
                    controlsGas,
                    interpolateAssettoCurve(physics.drivetrain.autoblipProfileMilliseconds, elapsed),
                )
            }
        }

        // Neutral and Park are free-revving positions. The zero gear used by
        // the drivetrain integrator must never be mistaken for a request to
        // select first gear when the engine reaches its authored shift RPM.
        val automaticRequest = if (transmissionPosition == TransmissionPosition.DRIVE) {
            automaticShiftDecision(
                controlsGas,
                clutch,
                automaticShifting,
                simulatedPedalsGearCalibration,
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
        manualShiftRequest = 0
        if (acceptShift(requestedDirection, clutch, simulatedPedalsGearCalibration, dt)) {
            shiftStarted = true
            eventDirection = requestedDirection
        } else if (requestedDirection != 0) {
            shiftRejected = true
        }

        if (shifting) {
            if (shiftDuration < shiftElapsed) {
                gear = shiftTarget
                eventDirection = shiftDirection
                shiftDirection = 0
                shiftCompleted = true
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
        val driven = drivenAxle(vehicle)
        val wheelSpeed = speedMetersPerSecond / driven.radius
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
        val ratio = abs(ratioForGear(gear, simulatedPedalsGearCalibration) * physics.drivetrain.finalDrive)
        var engineOmega = rpm * RADIAN_SECONDS_PER_RPM
        var driveForce = 0.0
        var tractionTorqueLimited = false
        if (ratio > 0.0 && clutch > 0.0) {
            val engineInertia = physics.engine.inertia + physics.drivetrain.gearboxInertia
            val slip = engineOmega - ratio * wheelSpeed
            val denominator = 1.0 / engineInertia +
                ratio * ratio / (effectiveMass * driven.radius * driven.radius)
            val requiredTorque = (
                slip / dt + engine.torque / engineInertia +
                    resistingForce * ratio / (effectiveMass * driven.radius)
                ) / denominator
            val clutchCapacity = physics.drivetrain.clutchMaximumTorque * clutch.pow(1.5)
            val gripForce = driven.grip * totalNormal * driven.normalFraction
            val gripCapacity = gripForce * driven.radius / ratio
            tractionTorqueLimited = engine.effectiveThrottle > 0.0 &&
                requiredTorque > gripCapacity + 1e-6 && gripCapacity < clutchCapacity - 1e-6
            val clutchTorque = min(clutchCapacity, min(gripCapacity, max(-clutchCapacity, requiredTorque)))
            driveForce = clutchTorque * ratio / driven.radius
            engineOmega += (engine.torque - clutchTorque) / engineInertia * dt
        } else {
            engineOmega += engine.torque / physics.engine.inertia.coerceAtLeast(0.001) * dt
        }

        if (externalSpeedMetersPerSecond == null) {
            val oldSpeed = speedMetersPerSecond
            speedMetersPerSecond = max(0.0, speedMetersPerSecond + (driveForce - resistingForce) / effectiveMass * dt)
            if (oldSpeed <= 0.0 && driveForce <= resistingForce) speedMetersPerSecond = 0.0
        }
        rpm = max(0.0, engineOmega * RPM_PER_RADIAN_SECOND)
        previousWheelSpeed = wheelSpeed
        val tractionActive = engine.effectiveThrottle > 0.0 && tractionTorqueLimited
        val tractionPulse = tractionActive && !previousTractionLimit
        previousTractionLimit = tractionActive
        lastFrame = AssettoDrivetrainFrame(
            rpm = rpm,
            speedMetersPerSecond = speedMetersPerSecond,
            gear = gear,
            drivetrainSpeedRadiansPerSecond = speedMetersPerSecond / driven.radius,
            driverThrottle = rawGas,
            effectiveThrottle = engine.effectiveThrottle,
            brake = cleanBrake,
            clutch = clutch,
            boost = boost,
            bov = bov,
            bovDecaySeconds = bovDecay,
            limiterPulse = engine.limiterActive,
            backfireTriggered = engine.backfire,
            shiftStarted = shiftStarted,
            shiftRejected = shiftRejected,
            shifting = shifting,
            shiftDirection = if (shiftStarted || shiftCompleted || shifting) eventDirection else 0,
            shiftProgress = if (shifting) (shiftElapsed / shiftDuration.coerceAtLeast(dt)).coerceIn(0.0, 1.0) else 0.0,
            tractionLimitActive = tractionActive,
            tractionLimitPulse = tractionPulse,
        )
        return lastFrame
    }

    fun frame(): AssettoDrivetrainFrame = lastFrame

    private val shifting: Boolean get() = shiftDirection != 0

    private fun anchorSpeed(speed: Double) {
        speedMetersPerSecond = speed.coerceAtLeast(0.0)
        previousWheelSpeed = speedMetersPerSecond / drivenAxle(physics.drivetrain.vehicle).radius
    }

    private fun setGearImmediately(target: Int, calibration: SimulatedPedalsGearCalibration?) {
        ratioForGear(target, calibration)
        gear = target
        shiftDirection = 0
        shiftTarget = target
        shiftElapsed = 0.0
        shiftDuration = 0.0
    }

    private fun autoclutchStep(dt: Double, gas: Double): Double {
        if (clutchSequence.isNotEmpty()) {
            clutchSignal = interpolateAssettoCurve(clutchSequence, clutchSequenceElapsed)
            clutchSequenceElapsed = f32(clutchSequenceElapsed + dt)
            if (clutchSequenceElapsed > clutchSequence.last().x) clutchSequence = emptyList()
            return clutchSignal.coerceIn(0.0, 1.0)
        }

        val spec = physics.drivetrain
        val target = when {
            gear == -1 || gear == 1 -> when {
                rpm < spec.autoclutchMinimumRpm -> 0.0
                rpm > spec.autoclutchMaximumRpm -> 1.0
                else -> (rpm - spec.autoclutchMinimumRpm) /
                    (spec.autoclutchMaximumRpm - spec.autoclutchMinimumRpm).coerceAtLeast(1.0)
            }
            gear == 0 -> if (speedMetersPerSecond * 3.6 >= 5.0 || gas > 0.2) 1.0 else 0.0
            else -> if (rpm >= spec.autoclutchMinimumRpm) 1.0 else 0.0
        }
        val maximumStep = spec.autoclutchSpeed * dt
        clutchSignal += (target - clutchSignal).coerceIn(-maximumStep, maximumStep)
        return clutchSignal.coerceIn(0.0, 1.0)
    }

    private fun automaticShiftDecision(
        gas: Double,
        clutch: Double,
        enabled: Boolean,
        calibration: SimulatedPedalsGearCalibration?,
        dt: Double,
    ): Int {
        if (!enabled || gear == -1 || shifting) return 0
        var request = 0
        if (clutch > 0.99 || gear == 0) {
            val shiftRpm = calibration?.let { roadCoupledRpm(gear, it) } ?: rpm
            val upshiftRpm = calibration?.limiterRpm ?: physics.drivetrain.automaticUpshiftRpm.toDouble()
            if (
                shiftRpm >= upshiftRpm &&
                gear < (calibration?.forwardGearCount ?: physics.drivetrain.forwardRatios.size) &&
                gas > 0.2 && automaticGasCutoff <= 0.0
            ) {
                request = 1
                automaticGasCutoff = f32(physics.drivetrain.automaticGasCutoffSeconds)
            } else {
                val downshiftRpm = calibration?.automaticDownshiftRpm(gear)
                    ?: physics.drivetrain.automaticDownshiftRpm.toDouble()
                if (
                    shiftRpm < downshiftRpm &&
                    gear > 1 && clutch > 0.85 &&
                    downshiftAllowed(gear - 1, calibration, dt) &&
                    automaticGasCutoff <= 0.0
                ) request = -1
            }
        }
        return request
    }

    private fun acceptShift(
        direction: Int,
        clutch: Double,
        calibration: SimulatedPedalsGearCalibration?,
        dt: Double,
    ): Boolean {
        if (direction == 0 || shifting) return false
        val target = gear + direction
        val forwardGearCount = calibration?.forwardGearCount ?: physics.drivetrain.forwardRatios.size
        if (target !in -1..forwardGearCount) return false
        if (direction < 0 && !downshiftAllowed(target, calibration, dt)) return false

        shiftDirection = direction
        shiftTarget = target
        shiftElapsed = 0.0
        shiftDuration = if (direction > 0) {
            calibration?.gearUpTimeSeconds ?: physics.drivetrain.gearUpTimeSeconds
        } else {
            calibration?.gearDownTimeSeconds ?: physics.drivetrain.gearDownTimeSeconds
        }
        gear = 0
        if (direction > 0 && physics.drivetrain.autoCutoffTimeSeconds != 0.0) {
            engineCutoff = physics.drivetrain.autoCutoffTimeSeconds
        }
        val profile = if (direction > 0) {
            physics.drivetrain.autoclutchUpshiftProfile
        } else {
            physics.drivetrain.autoclutchDownshiftProfile
        }
        if (physics.drivetrain.autoclutchOnChanges && clutch > 0.01 && profile.isNotEmpty()) {
            clutchSequence = profile
            clutchSequenceElapsed = 0.0
        }
        if (direction < 0 && clutch > 1.0 / PI) autoblipStartMilliseconds = sessionElapsedMilliseconds
        return true
    }

    private fun downshiftAllowed(target: Int, calibration: SimulatedPedalsGearCalibration?, dt: Double): Boolean {
        val spec = physics.drivetrain
        if (!spec.downshiftProtection) return true
        if (target == 0 && spec.downshiftLocksNeutral && speedMetersPerSecond * 3.6 > 2.0) return false
        if (target <= 0) return true
        return projectedRpmForGear(target, calibration, dt) <= physics.engine.limiterRpm + spec.downshiftOverrevRpm
    }

    private fun projectedRpmForGear(
        target: Int,
        calibration: SimulatedPedalsGearCalibration?,
        dt: Double,
    ): Double {
        val spec = physics.drivetrain
        val wheelSpeed = speedMetersPerSecond / drivenAxle(spec.vehicle).radius
        val wheelAcceleration = max(0.0, (wheelSpeed - previousWheelSpeed) / dt.coerceAtLeast(1e-9))
        val shiftTime = calibration?.gearDownTimeSeconds ?: spec.gearDownTimeSeconds
        val projected = wheelSpeed + shiftTime * wheelAcceleration
        return projected * abs(ratioForGear(target, calibration) * spec.finalDrive) * RPM_PER_RADIAN_SECOND
    }

    private fun roadCoupledRpm(gear: Int, calibration: SimulatedPedalsGearCalibration): Double {
        if (gear <= 0) return rpm
        val wheelSpeed = speedMetersPerSecond / drivenAxle(physics.drivetrain.vehicle).radius
        return wheelSpeed *
            abs(ratioForGear(gear, calibration) * physics.drivetrain.finalDrive) *
            RPM_PER_RADIAN_SECOND
    }

    private fun ratioForGear(gear: Int, calibration: SimulatedPedalsGearCalibration?): Double =
        calibration?.ratioForGear(gear, physics.drivetrain) ?: physics.drivetrain.ratioForGear(gear)

    private fun engineTorque(dt: Double, controlsGas: Double, engineGas: Double): EngineTorqueFrame {
        val engine = physics.engine
        val backfire = engine.backfire
        if (controlsGas > backfirePeakGas && controlsGas != 0.0) {
            backfirePeakGas = controlsGas
            backfireArmLevel = backfire.triggerGas * controlsGas
            backfireFireBelow = backfire.maximumGas * controlsGas
        }
        if (controlsGas > backfireArmLevel) backfireArmed = true
        val triggerBackfire = backfireArmed && controlsGas > 0.0 && controlsGas < backfireFireBelow &&
            rpm > backfire.minimumRpm && rpm <= backfire.maximumRpm && backfireTimer > 1.0
        if (triggerBackfire) {
            backfireArmed = false
        } else if (backfireArmed) {
            backfireTimer = min(10.0, backfireTimer + dt)
        }

        val mapped = interpolateAssettoCurve(engine.throttleCurve, engineGas)
        val limiterSteps = if (engine.limiterHz > 0.0) {
            (1_000.0 / engine.limiterHz).toInt() / 3
        } else {
            50
        }
        if (engine.limiterRpm > 0.0 && rpm > engine.limiterRpm) limiterCounter = max(1, limiterSteps)
        val limiterActive = limiterCounter > 0
        if (limiterActive) limiterCounter -= 1
        val effective = if (limiterActive) 0.0 else mapped

        if (engine.turbos.isNotEmpty()) {
            boost = engine.turbos.mapIndexed { index, turbo ->
                val input = (effective * rpm / turbo.referenceRpm.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val target = input.pow(turbo.gamma)
                var q = turboQs[index]
                val lag = if (target > q) turbo.lagUp else turbo.lagDown
                q += (dt * lag).coerceIn(0.0, 1.0) * (target - q)
                if (turbo.wastegate > 0.0 && turbo.maximumBoost * q > turbo.wastegate) {
                    q = turbo.wastegate / turbo.maximumBoost.coerceAtLeast(0.001)
                }
                turboQs[index] = q
                turbo.maximumBoost * q
            }.sum()
            bov = if (boost * (1.0 - effective) > engine.turbos.first().bovThreshold) 1.0 else 0.0
            bovDecay = if (bov > 0.0) 0.0 else min(10.0, bovDecay + dt)
        }

        val power = interpolateAssettoCurve(engine.torqueCurve, rpm) * (1.0 + boost)
        val coast = coastTorque(rpm)
        var torque = coast + effective * (power - coast)
        if (rpm < engine.idleRpm) torque = max(torque, 15.0)
        return EngineTorqueFrame(torque, effective, limiterActive, triggerBackfire)
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

    private fun aeroForSpeed(speed: Double): Pair<Double, Double> {
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
        return max(0.0, pressure * dragArea) to max(0.0, pressure * liftArea)
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

    private data class EngineTorqueFrame(
        val torque: Double,
        val effectiveThrottle: Double,
        val limiterActive: Boolean,
        val backfire: Boolean,
    )

    private data class DrivenAxle(val radius: Double, val normalFraction: Double, val grip: Double)

    private companion object {
        const val GRAVITY = 9.81
        const val RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * PI)
        const val RADIAN_SECONDS_PER_RPM = 1.0 / RPM_PER_RADIAN_SECOND
    }
}

private fun f32(value: Double): Double = value.toFloat().toDouble()
