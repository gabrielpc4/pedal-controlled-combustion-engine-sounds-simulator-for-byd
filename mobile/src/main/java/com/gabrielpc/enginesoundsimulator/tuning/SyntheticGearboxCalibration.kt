package com.gabrielpc.enginesoundsimulator.tuning

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.round

/**
 * Derives presentation gear ratios so the top gear hits [redlineRpm] exactly at [topSpeedKmh],
 * with roughly even upshift speed spacing between adjacent ratios.
 */
object SyntheticGearboxCalibration {
    /** Typical step between consecutive synthetic ratios (~15% drop per upshift). */
    private const val RATIO_STEP = 0.856

    fun wheelRpmForSpeedKmh(speedKmh: Double, wheelRadiusMeters: Double): Double {
        val speedMps = speedKmh / 3.6
        return speedMps / (2.0 * PI * wheelRadiusMeters) * 60.0
    }

    fun roadCoupledRpm(
        speedKmh: Double,
        gearRatio: Double,
        idleRpm: Double,
        finalDrive: Double,
        wheelRadiusMeters: Double,
    ): Double {
        val wheelRpm = wheelRpmForSpeedKmh(speedKmh, wheelRadiusMeters)
        return idleRpm + wheelRpm * gearRatio * finalDrive
    }

    fun computeGearRatios(
        gearCount: Int = 7,
        idleRpm: Double = 950.0,
        redlineRpm: Double = 8_600.0,
        topSpeedKmh: Double = 190.0,
        finalDrive: Double = 3.82,
        wheelRadiusMeters: Double = 0.347,
    ): List<Double> {
        require(gearCount >= 2)

        val wheelRpmAtTop = wheelRpmForSpeedKmh(topSpeedKmh, wheelRadiusMeters)
        val topRatioExact = (redlineRpm - idleRpm) / (wheelRpmAtTop * finalDrive)
        val firstRatio = topRatioExact / RATIO_STEP.pow(gearCount - 1)

        return List(gearCount) { index ->
            if (index == gearCount - 1) {
                topRatioExact
            } else {
                roundRatio(firstRatio * RATIO_STEP.pow(index.toDouble()))
            }
        }
    }

    private fun roundRatio(value: Double): Double = round(value * 1_000.0) / 1_000.0
}
