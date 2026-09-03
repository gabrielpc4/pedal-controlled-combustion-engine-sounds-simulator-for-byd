package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.abs

/**
 * Maps the vehicle's road speed to the internal speed consumed by the FMOD drivetrain model.
 *
 * This is intentionally not a second set of shift rules. The authored Assetto data still owns
 * every RPM threshold, limiter, gear count, clutch profile, and shift duration. The mapping only
 * changes the speed-to-RPM conversion so a vehicle travelling at the same road speed occupies a
 * predictable fraction of the shared 0..190 km/h range in every selected car.
 *
 * `vehicleSpeedKmh` is the real or documented vehicle speed. `fmodDrivetrainSpeedKmh` is a
 * derived, internal value and must never be displayed as the vehicle's speed or sent back to
 * telemetry. Keeping the names separate prevents this presentation contract from being mistaken
 * for a physical speed measurement by the next maintainer.
 */
internal data class EqualSpeedGearMapping(
    private val equalSpeedRatios: List<Double>,
) {
    val forwardGearCount: Int get() = equalSpeedRatios.size

    /**
     * Convert a road speed into the FMOD drivetrain speed for one authored gear.
     *
     * For intermediate gears the ratio is chosen so that the bank's authored upshift RPM is
     * reached at the upper edge of the gear's equal 190 km/h band. The final gear uses the bank's
     * limiter RPM at 190 km/h. Shift decisions still compare the resulting authored RPM with the
     * bank's authored up/down thresholds; no speed boundary performs a shift by itself.
     */
    fun fmodDrivetrainSpeedKmh(
        vehicleSpeedKmh: Double,
        gear: Int,
        authored: AssettoDrivetrainSpec,
    ): Double {
        val cleanVehicleSpeedKmh = vehicleSpeedKmh.coerceAtLeast(0.0)
        if (gear !in 1..equalSpeedRatios.size) return cleanVehicleSpeedKmh

        val authoredRatio = abs(authored.ratioForGear(gear) * authored.finalDrive)
        val equalSpeedRatio = abs(equalSpeedRatios[gear - 1] * authored.finalDrive)
        if (authoredRatio <= RATIO_EPSILON || equalSpeedRatio <= RATIO_EPSILON) {
            return cleanVehicleSpeedKmh
        }

        return (cleanVehicleSpeedKmh * equalSpeedRatio / authoredRatio)
            .coerceAtLeast(0.0)
    }

    companion object {
        private const val TOP_SPEED_KMH = 190.0
        private const val RATIO_EPSILON = 1e-6

        /**
         * Build the mapping from the documented/original physics for the selected bank.
         *
         * The authored upshift RPM is used as the reference for every intermediate band and the
         * authored limiter is used for the final band. These values are only used to derive the
         * speed conversion; they are not copied into the mapping as replacement shift rules. The
         * drivetrain continues to compare its live RPM against the same authored thresholds.
         */
        fun from(documentedPhysics: AssettoPhysics): EqualSpeedGearMapping {
            val authored = documentedPhysics.drivetrain
            val gearCount = authored.forwardRatios.size.coerceAtLeast(1)
            val finalDrive = authored.finalDrive.takeIf { abs(it) > RATIO_EPSILON } ?: 1.0
            val wheelRadius = drivenWheelRadius(documentedPhysics)
            val documentedLimiterRpm = documentedPhysics.engine.limiterRpm
            val documentedUpshiftRpm = authored.automaticUpshiftRpm
                .toDouble()
                .takeIf { it > 0.0 }
                ?: documentedLimiterRpm
            val equalSpeedRatios = (1..gearCount).map { gear ->
                val gearMaximumSpeedMps = TOP_SPEED_KMH * gear / gearCount / 3.6
                val wheelRadiansPerSecond = gearMaximumSpeedMps / wheelRadius
                val documentedBoundaryRpm = if (gear == gearCount) {
                    documentedLimiterRpm
                } else {
                    documentedUpshiftRpm
                }
                documentedBoundaryRpm * (2.0 * PI / 60.0) /
                    wheelRadiansPerSecond.coerceAtLeast(RATIO_EPSILON) /
                    finalDrive
            }
            return EqualSpeedGearMapping(equalSpeedRatios)
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
