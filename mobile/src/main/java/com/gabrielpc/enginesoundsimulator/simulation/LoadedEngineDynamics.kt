package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Presentation-only loaded engine model.
 *
 * The road-speed RPM remains the clutch synchronization point, while combustion torque and
 * crank/gearbox inertia can briefly move the audible engine ahead of it. This mirrors the Audio
 * Lab's separate engine and wheel angular velocities without changing the EV's real road speed.
 */
internal class LoadedEngineDynamics(
    private var calibration: FreeRevCalibration,
) {
    private var clutchCapacityNm = clutchCapacityFor(calibration)

    fun updateCalibration(updated: FreeRevCalibration) {
        calibration = updated
        clutchCapacityNm = clutchCapacityFor(updated)
    }

    fun step(
        rpm: Double,
        coupledRpm: Double,
        rawThrottle: Double,
        idleRpm: Double,
        limiterRpm: Double,
        upshiftRpm: Double,
        gearIndex: Int,
        gearCount: Int,
        dt: Double,
    ): Double {
        val synchronizedRpm = coupledRpm.coerceIn(idleRpm, limiterRpm)
        val lastGearIndex = (gearCount - 1).coerceAtLeast(0)
        val safeGearIndex = gearIndex.coerceIn(0, lastGearIndex)
        val gearProgress = if (lastGearIndex == 0) {
            0.0
        } else {
            safeGearIndex.toDouble() / lastGearIndex
        }
        val couplingDamping = FIRST_GEAR_COUPLING_DAMPING +
            (TOP_GEAR_COUPLING_DAMPING - FIRST_GEAR_COUPLING_DAMPING) * gearProgress
        val maximumSlipRpm = FIRST_GEAR_MAXIMUM_SLIP_RPM +
            (TOP_GEAR_MAXIMUM_SLIP_RPM - FIRST_GEAR_MAXIMUM_SLIP_RPM) * gearProgress
        val shiftHeadroomRpm = if (safeGearIndex < lastGearIndex) {
            (upshiftRpm - UPSHIFT_HEADROOM_RPM - synchronizedRpm).coerceAtLeast(0.0)
        } else {
            limiterRpm - synchronizedRpm
        }
        val upperRpm = synchronizedRpm + min(maximumSlipRpm, shiftHeadroomRpm)
        val lowerRpm = max(idleRpm, synchronizedRpm - MAXIMUM_ENGINE_BRAKE_SLIP_RPM)
        val effectiveInertia = (calibration.engineInertia + GEARBOX_INPUT_INERTIA).coerceAtLeast(MINIMUM_INERTIA)
        val throttle = rawThrottle.coerceIn(0.0, 1.0)
        val mappedThrottle = interpolateTorqueCurve(calibration.throttleCurve, throttle)
        var integratedRpm = rpm.coerceIn(lowerRpm, max(lowerRpm, upperRpm))
        var remainingSeconds = dt.coerceAtLeast(0.0)

        while (remainingSeconds > 0.0) {
            val stepSeconds = min(PHYSICS_STEP_SECONDS, remainingSeconds)
            val engineTorque = combustionTorqueNm(
                calibration = calibration,
                rpm = integratedRpm,
                mappedThrottle = mappedThrottle,
                idleRpm = idleRpm,
            )
            val slipRadiansPerSecond = (integratedRpm - synchronizedRpm) * RADIANS_PER_SECOND_PER_RPM
            val clutchTorque = (slipRadiansPerSecond * couplingDamping)
                .coerceIn(-clutchCapacityNm, clutchCapacityNm)
            val angularAcceleration = (engineTorque - clutchTorque) / effectiveInertia
            integratedRpm += angularAcceleration * RPM_PER_RADIAN_SECOND * stepSeconds
            integratedRpm = integratedRpm.coerceIn(lowerRpm, max(lowerRpm, upperRpm))
            remainingSeconds -= stepSeconds
        }

        return integratedRpm.coerceIn(idleRpm, limiterRpm)
    }

    private fun clutchCapacityFor(calibration: FreeRevCalibration): Double {
        var maximumTorqueNm = 0.0
        for (point in calibration.torqueCurve) {
            maximumTorqueNm = max(maximumTorqueNm, point.torqueNm)
        }

        return (maximumTorqueNm * CLUTCH_CAPACITY_MULTIPLIER).coerceAtLeast(MINIMUM_CLUTCH_CAPACITY_NM)
    }

    private companion object {
        const val PHYSICS_STEP_SECONDS = 0.003
        const val GEARBOX_INPUT_INERTIA = 0.020
        const val MINIMUM_INERTIA = 0.001
        const val FIRST_GEAR_COUPLING_DAMPING = 1.60
        const val TOP_GEAR_COUPLING_DAMPING = 3.00
        const val FIRST_GEAR_MAXIMUM_SLIP_RPM = 1_550.0
        const val TOP_GEAR_MAXIMUM_SLIP_RPM = 650.0
        const val MAXIMUM_ENGINE_BRAKE_SLIP_RPM = 45.0
        const val UPSHIFT_HEADROOM_RPM = 100.0
        const val CLUTCH_CAPACITY_MULTIPLIER = 2.0
        const val MINIMUM_CLUTCH_CAPACITY_NM = 300.0
        const val RADIANS_PER_SECOND_PER_RPM = 2.0 * PI / 60.0
        const val RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * PI)
    }
}
