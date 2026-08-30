package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.pow

data class RpmTorquePoint(
    val rpm: Double,
    val torqueNm: Double,
)

data class FreeRevTurboCalibration(
    val lagDown: Double,
    val lagUp: Double,
    val maximumBoost: Double,
    val wastegateBoost: Double,
    val referenceRpm: Double,
    val gamma: Double,
)

/** Parameters for the engine-only path used in Park and Neutral. */
data class FreeRevCalibration(
    val engineInertia: Double,
    val torqueCurve: List<RpmTorquePoint>,
    val coastReferenceRpm: Double,
    val coastReferenceTorqueNm: Double,
    val coastNonLinearity: Double,
    val limiterHz: Double,
    val turbo: FreeRevTurboCalibration? = null,
    val throttleCurve: List<RpmTorquePoint> = listOf(
        RpmTorquePoint(0.0, 0.0),
        RpmTorquePoint(1.0, 1.0),
    ),
) {
    init {
        require(engineInertia > 0.0)
        require(torqueCurve.size >= 2)
        require(torqueCurve.zipWithNext().all { (left, right) -> left.rpm < right.rpm })
        require(throttleCurve.size >= 2)
        require(throttleCurve.zipWithNext().all { (left, right) -> left.rpm < right.rpm })
    }

    companion object {
        fun forEngine(
            name: String,
            idleRpm: Double,
            limiterRpm: Double,
            maxTorqueNm: Double,
        ): FreeRevCalibration {
            if (name == SKYLINE_R34_NAME) {
                return skylineR34()
            }

            val peakTorque = (maxTorqueNm * 0.50).coerceAtLeast(140.0)
            val span = (limiterRpm - idleRpm).coerceAtLeast(1_000.0)
            return FreeRevCalibration(
                engineInertia = 0.22,
                torqueCurve = listOf(
                    RpmTorquePoint(idleRpm, peakTorque * 0.28),
                    RpmTorquePoint(idleRpm + span * 0.28, peakTorque * 0.76),
                    RpmTorquePoint(idleRpm + span * 0.58, peakTorque),
                    RpmTorquePoint(limiterRpm, peakTorque * 0.70),
                ),
                coastReferenceRpm = limiterRpm * 0.85,
                coastReferenceTorqueNm = peakTorque * 0.22,
                coastNonLinearity = 0.0,
                limiterHz = 12.0,
            )
        }

        private fun skylineR34() = FreeRevCalibration(
            engineInertia = 0.18,
            torqueCurve = listOf(
                RpmTorquePoint(1_000.0, 90.0),
                RpmTorquePoint(2_000.0, 180.0),
                RpmTorquePoint(3_500.0, 310.0),
                RpmTorquePoint(5_000.0, 360.0),
                RpmTorquePoint(6_500.0, 340.0),
                RpmTorquePoint(8_200.0, 250.0),
            ),
            coastReferenceRpm = 7_000.0,
            coastReferenceTorqueNm = 80.0,
            coastNonLinearity = 0.0,
            limiterHz = 12.0,
            turbo = FreeRevTurboCalibration(
                lagDown = 0.98,
                lagUp = 0.98,
                maximumBoost = 1.0,
                wastegateBoost = 1.0,
                referenceRpm = 5_000.0,
                gamma = 1.0,
            ),
        )

        private const val SKYLINE_R34_NAME = "Nissan Skyline GT-R R34"
    }
}

internal data class FreeRevFrame(
    val rpm: Double,
    val throttle: Double,
    val boost: Double,
    val limiterActive: Boolean,
)

/**
 * Engine-only integrator for the Park/Neutral path.
 *
 * Its ordering mirrors the Audio Lab: fuel cut, turbo update, torque blend,
 * then crankshaft integration with no gearbox inertia or road coupling.
 */
internal class FreeRevEngineDynamics(
    private var calibration: FreeRevCalibration,
) {
    private var turboCharge = 0.0
    private var limiterCutRemainingSeconds = 0.0

    fun reset() {
        turboCharge = 0.0
        limiterCutRemainingSeconds = 0.0
    }

    fun updateCalibration(updated: FreeRevCalibration) {
        calibration = updated
        reset()
    }

    fun step(
        rpm: Double,
        rawThrottle: Double,
        idleRpm: Double,
        limiterRpm: Double,
        dt: Double,
    ): FreeRevFrame {
        val stepSeconds = dt.coerceIn(MINIMUM_STEP_SECONDS, MAXIMUM_STEP_SECONDS)
        val throttle = rawThrottle.coerceIn(0.0, 1.0)
        val mappedThrottle = interpolateTorqueCurve(calibration.throttleCurve, throttle)
        if (rpm > limiterRpm) {
            limiterCutRemainingSeconds = limiterCutDurationSeconds(calibration.limiterHz)
        }
        val limiterActive = limiterCutRemainingSeconds > 0.0
        if (limiterActive) {
            limiterCutRemainingSeconds = (limiterCutRemainingSeconds - stepSeconds).coerceAtLeast(0.0)
        }
        val effectiveThrottle = if (limiterActive) 0.0 else mappedThrottle
        val boost = updateTurbo(effectiveThrottle, rpm, stepSeconds)
        val netTorque = combustionTorqueNm(
            calibration = calibration,
            rpm = rpm,
            mappedThrottle = effectiveThrottle,
            idleRpm = idleRpm,
            boost = boost,
        )
        val angularAcceleration = netTorque / calibration.engineInertia.coerceAtLeast(MINIMUM_INERTIA)
        val nextRpm = (rpm + angularAcceleration * RPM_PER_RADIAN_SECOND * stepSeconds).coerceAtLeast(idleRpm)

        return FreeRevFrame(
            rpm = nextRpm,
            throttle = throttle,
            boost = boost,
            limiterActive = limiterActive,
        )
    }

    private fun updateTurbo(throttle: Double, rpm: Double, dt: Double): Double {
        val turbo = calibration.turbo ?: return 0.0
        val target = (throttle * rpm / turbo.referenceRpm.coerceAtLeast(1.0))
            .coerceIn(0.0, 1.0)
            .pow(turbo.gamma)
        val lag = if (target > turboCharge) turbo.lagUp else turbo.lagDown
        turboCharge += (dt * lag).coerceIn(0.0, 1.0) * (target - turboCharge)
        val boost = turbo.maximumBoost * turboCharge
        if (turbo.wastegateBoost > 0.0 && boost > turbo.wastegateBoost) {
            turboCharge = turbo.wastegateBoost / turbo.maximumBoost.coerceAtLeast(0.001)
        }
        return turbo.maximumBoost * turboCharge
    }

    private fun limiterCutDurationSeconds(limiterHz: Double): Double {
        val intervalMs = if (limiterHz > 0.0) (1_000.0 / limiterHz).toInt() else 50
        return (intervalMs / 3).coerceAtLeast(1) * FIXED_REFERENCE_STEP_SECONDS
    }

    private companion object {
        const val RPM_PER_RADIAN_SECOND = 60.0 / (2.0 * PI)
        const val FIXED_REFERENCE_STEP_SECONDS = 0.003
        const val MINIMUM_STEP_SECONDS = 0.0001
        const val MAXIMUM_STEP_SECONDS = 0.020
        const val MINIMUM_INERTIA = 0.001
    }
}
