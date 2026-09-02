package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.abs

/**
 * SIMULATED PEDALS keeps the chosen bank's engine model but maps its sound gears to the Seal's
 * 190 km/h road-speed range. Every forward gear occupies the same road-speed span and reaches
 * the bank's limiter at the top of that span.
 */
internal data class SimulatedPedalsGearCalibration(
    val forwardRatios: List<Double>,
    val limiterRpm: Double,
    val gearUpTimeSeconds: Double = 0.095,
    val gearDownTimeSeconds: Double = 0.220,
) {
    val forwardGearCount: Int get() = forwardRatios.size

    fun ratioForGear(gear: Int, authored: AssettoDrivetrainSpec): Double = when {
        gear == -1 -> authored.reverseRatio
        gear == 0 -> 0.0
        gear in 1..forwardRatios.size -> forwardRatios[gear - 1]
        else -> error("Gear $gear is outside the simulated-pedals drivetrain")
    }

    fun automaticDownshiftRpm(currentGear: Int): Double {
        if (currentGear <= 1) return 0.0

        // A single 6% gap applies to every boundary; there is deliberately no 2nd→1st exception.
        return limiterRpm * (currentGear - 1).toDouble() / currentGear * DOWNSHIFT_BOUNDARY_FRACTION
    }

    companion object {
        const val TOP_SPEED_KMH = 190.0
        private const val DOWNSHIFT_BOUNDARY_FRACTION = 0.94

        fun from(physics: AssettoPhysics): SimulatedPedalsGearCalibration {
            val authored = physics.drivetrain
            val gearCount = authored.forwardRatios.size.coerceAtLeast(1)
            val finalDrive = authored.finalDrive.takeIf { abs(it) > 1e-6 } ?: 1.0
            val wheelRadius = drivenWheelRadius(physics)
            val limiterRadiansPerSecond = physics.engine.limiterRpm * (2.0 * PI / 60.0)
            val ratios = (1..gearCount).map { gear ->
                val gearMaximumSpeedMps = TOP_SPEED_KMH * gear / gearCount / 3.6
                val wheelRadiansPerSecond = gearMaximumSpeedMps / wheelRadius
                limiterRadiansPerSecond / wheelRadiansPerSecond.coerceAtLeast(1e-6) / finalDrive
            }
            return SimulatedPedalsGearCalibration(ratios, physics.engine.limiterRpm)
        }
    }
}

internal fun drivenWheelRadius(physics: AssettoPhysics): Double {
    val vehicle = physics.drivetrain.vehicle
    return when {
        physics.drivetrain.traction.equals("FWD", true) -> vehicle.frontWheelRadiusMeters
        physics.drivetrain.traction.startsWith("AWD", true) ->
            (vehicle.frontWheelRadiusMeters + vehicle.rearWheelRadiusMeters) * 0.5
        else -> vehicle.rearWheelRadiusMeters
    }.coerceAtLeast(1e-6)
}
