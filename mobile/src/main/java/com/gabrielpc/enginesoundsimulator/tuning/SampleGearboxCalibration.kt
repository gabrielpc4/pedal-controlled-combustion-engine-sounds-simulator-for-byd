package com.gabrielpc.enginesoundsimulator.tuning

import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.round

/**
 * Derives presentation gear ratios so the top gear reaches [redlineRpm] at [topSpeedKmh],
 * with roughly even sound-RPM drops between adjacent ratios.
 */
object SampleGearboxCalibration {
    /** Typical step between consecutive simulated ratios (~15% drop per upshift). */
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
        idleRpm: Double = EngineSampleProfiles.default.idleRpm,
        redlineRpm: Double = EngineSampleProfiles.default.redlineRpm,
        topSpeedKmh: Double = 190.0,
        finalDrive: Double = EngineSampleProfiles.default.finalDrive,
        wheelRadiusMeters: Double = 0.347,
    ): List<Double> {
        require(gearCount >= 2)

        val wheelRpmAtTop = wheelRpmForSpeedKmh(topSpeedKmh, wheelRadiusMeters)
        val topRatioExact = (redlineRpm - idleRpm) / (wheelRpmAtTop * finalDrive)
        val firstRatio = topRatioExact / RATIO_STEP.pow(gearCount - 1)

        return List(gearCount) { index ->
            if (index == gearCount - 1) topRatioExact else roundRatio(firstRatio * RATIO_STEP.pow(index.toDouble()))
        }
    }

    private fun roundRatio(value: Double): Double = round(value * 1_000.0) / 1_000.0
}
