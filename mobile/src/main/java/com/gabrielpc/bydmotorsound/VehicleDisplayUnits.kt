package com.gabrielpc.bydmotorsound

import com.gabrielpc.bydmotorsound.tuning.EngineTuning
import com.gabrielpc.bydmotorsound.tuning.interpolateCurve
import kotlin.math.max
import kotlin.math.min

internal fun newtonMetersToKgfm(newtonMeters: Double): Double = newtonMeters / NEWTON_METERS_PER_KGFM

internal fun kgfmToNewtonMeters(kgfm: Double): Double = kgfm * NEWTON_METERS_PER_KGFM

/** UI-only conversion from measured wheel torque to an approximate motor-shaft kgfm rating. */
internal fun wheelNewtonMetersToMotorEquivalentKgfm(
    wheelNewtonMeters: Double,
    motorReductionRatio: Double,
): Double {
    return newtonMetersToKgfm(wheelNewtonMeters / motorReductionRatio)
}

internal fun motorEquivalentKgfmToWheelNewtonMeters(
    motorEquivalentKgfm: Double,
    motorReductionRatio: Double,
): Double {
    return kgfmToNewtonMeters(motorEquivalentKgfm) * motorReductionRatio
}

/** UI-only: metric PS/cv values are shown under the "HP" label. */
internal fun kilowattsToHorsepower(kilowatts: Double): Double = kilowatts * KILOWATTS_TO_DISPLAY_HORSEPOWER

internal fun horsepowerToKilowatts(horsepower: Double): Double = horsepower / KILOWATTS_TO_DISPLAY_HORSEPOWER

internal fun peakWheelPowerKw(engine: EngineTuning): Double {
    var maxPowerKw = 0.0
    repeat(401) { index ->
        val sample = sampleWheelPowerKw(engine, index / 400.0)
        maxPowerKw = max(maxPowerKw, sample)
    }
    return maxPowerKw
}

/**
 * UI-only scale so graphed wheel power peaks at the configured motor rating.
 * Internal simulation still uses physical wheel kW.
 */
internal fun wheelKilowattsToMotorEquivalentDisplayKw(
    wheelKilowatts: Double,
    motorPeakPowerKw: Double,
    peakWheelPowerKw: Double,
): Double {
    if (peakWheelPowerKw <= 1e-6) {
        return wheelKilowatts
    }
    return wheelKilowatts * (motorPeakPowerKw / peakWheelPowerKw)
}

private fun totalWheelTorque(engine: EngineTuning, normalizedSpeed: Double): Double =
    interpolateCurve(engine.frontWheelTorqueCurve, normalizedSpeed) * engine.frontPeakWheelTorqueNm +
        interpolateCurve(engine.rearWheelTorqueCurve, normalizedSpeed) * engine.rearPeakWheelTorqueNm

private fun sampleWheelPowerKw(engine: EngineTuning, normalizedSpeed: Double): Double {
    val rawTorque = totalWheelTorque(engine, normalizedSpeed)
    val wheelOmega = (normalizedSpeed * engine.topSpeedKmh / 3.6) / engine.wheelRadiusMeters
    val powerLimitedTorque = if (wheelOmega < 1.0) {
        rawTorque
    } else {
        engine.peakPowerKw * 1_000.0 * engine.drivetrainEfficiency / wheelOmega
    }
    val displayedTorque = min(rawTorque, powerLimitedTorque)
    return displayedTorque * wheelOmega / 1_000.0
}

private const val NEWTON_METERS_PER_KGFM = 9.80665

/** 1 kW expressed as metric horsepower (PS/cv), labeled "HP" in the UI. */
private const val KILOWATTS_TO_DISPLAY_HORSEPOWER = 1.3596216173039
