package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.max

/** Shared combustion torque calculation for both the free-rev and loaded engine paths. */
internal fun combustionTorqueNm(
    calibration: FreeRevCalibration,
    rpm: Double,
    mappedThrottle: Double,
    idleRpm: Double,
    boost: Double = 0.0,
): Double {
    val throttle = mappedThrottle.coerceIn(0.0, 1.0)
    val powerTorque = interpolateTorqueCurve(calibration.torqueCurve, rpm) * (1.0 + boost)
    val coastTorque = combustionCoastTorqueNm(calibration, rpm, idleRpm)
    var torque = coastTorque + throttle * (powerTorque - coastTorque)
    if (rpm < idleRpm) {
        torque = max(torque, IDLE_STABILIZING_TORQUE_NM)
    }

    return torque
}

internal fun interpolateTorqueCurve(points: List<RpmTorquePoint>, input: Double): Double {
    if (input <= points.first().rpm) {
        return points.first().torqueNm
    }

    for (index in 1 until points.size) {
        val right = points[index]
        if (input <= right.rpm) {
            val left = points[index - 1]
            val fraction = (input - left.rpm) / (right.rpm - left.rpm)

            return left.torqueNm + (right.torqueNm - left.torqueNm) * fraction
        }
    }

    return points.last().torqueNm
}

private fun combustionCoastTorqueNm(
    calibration: FreeRevCalibration,
    rpm: Double,
    idleRpm: Double,
): Double {
    if (rpm <= idleRpm) {
        return 0.0
    }

    val reference = calibration.coastReferenceRpm
    val nonLinearity = calibration.coastNonLinearity
    val denominator = (1.0 - nonLinearity) * reference - idleRpm
    val linear = if (denominator == 0.0) {
        0.0
    } else {
        -calibration.coastReferenceTorqueNm / denominator
    }
    val nonlinearRpm = nonLinearity * reference
    val quadratic = if (nonlinearRpm == 0.0) {
        0.0
    } else {
        calibration.coastReferenceTorqueNm / (nonlinearRpm * nonlinearRpm)
    }
    val delta = rpm - idleRpm

    return linear * delta - quadratic * delta * delta
}

private const val IDLE_STABILIZING_TORQUE_NM = 15.0
