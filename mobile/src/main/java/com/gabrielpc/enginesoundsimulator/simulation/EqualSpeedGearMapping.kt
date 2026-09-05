package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.PI
import kotlin.math.abs

/**
 * Maps the documented road-speed bands to the internal drivetrain speed consumed by FMOD.
 *
 * This is not a second shift controller. The selected bank still owns the RPM thresholds,
 * limiter, ratios, clutch profiles, and shift durations. The mapping only lets the physical
 * speed bands occupy equal portions of the 0..190 km/h range while preserving the bank's RPM
 * trajectory inside and across those bands.
 *
 * The important detail is that the boundary values are shared by adjacent gears and the same
 * piecewise curve is used regardless of the currently selected gear. At an equal speed boundary,
 * the internal wheel speed therefore cannot jump merely because a shift completed. The authored
 * ratio change is then the only RPM change caused by the new gear. The previous implementation
 * selected a different extrapolated line for every gear; during a 4->3 shift that made FMOD
 * speed jump when the shift flag cleared, which sounded like an RPM pulse even though the road
 * speed was still falling.
 */
internal data class EqualSpeedGearMapping(
    private val documentedPhysicalBoundarySpeedsKmh: List<Double>,
    private val documentedFmodBoundarySpeedsKmh: List<Double>,
) {
    val forwardGearCount: Int get() = documentedPhysicalBoundarySpeedsKmh.size - 1

    /**
     * Convert documented vehicle speed into the internal FMOD drivetrain speed. The segment is
     * deliberately extrapolated outside the 0..190 km/h range so braking continues to move the
     * engine instead of sticking to a boundary. This function intentionally has no gear argument:
     * changing gear must change the authored ratio, not select a second speed curve.
     */
    fun fmodDrivetrainSpeedKmh(vehicleSpeedKmh: Double): Double {
        val cleanVehicleSpeedKmh = vehicleSpeedKmh.coerceAtLeast(0.0)
        if (forwardGearCount <= 0) return cleanVehicleSpeedKmh

        val lowerBoundaryIndex = when {
            cleanVehicleSpeedKmh <= documentedPhysicalBoundarySpeedsKmh.first() -> 0
            cleanVehicleSpeedKmh >= documentedPhysicalBoundarySpeedsKmh.last() ->
                documentedPhysicalBoundarySpeedsKmh.lastIndex - 1
            else -> documentedPhysicalBoundarySpeedsKmh.indexOfLast { it <= cleanVehicleSpeedKmh }
                .coerceIn(0, documentedPhysicalBoundarySpeedsKmh.lastIndex - 1)
        }
        val upperBoundaryIndex = lowerBoundaryIndex + 1
        val documentedPhysicalSpanKmh = (
            documentedPhysicalBoundarySpeedsKmh[upperBoundaryIndex] -
                documentedPhysicalBoundarySpeedsKmh[lowerBoundaryIndex]
            ).coerceAtLeast(SPEED_EPSILON)
        val documentedFmodSpanKmh = (
            documentedFmodBoundarySpeedsKmh[upperBoundaryIndex] -
                documentedFmodBoundarySpeedsKmh[lowerBoundaryIndex]
            )
        val documentedSegmentFraction = (
            cleanVehicleSpeedKmh - documentedPhysicalBoundarySpeedsKmh[lowerBoundaryIndex]
            ) / documentedPhysicalSpanKmh
        return (
            documentedFmodBoundarySpeedsKmh[lowerBoundaryIndex] +
                documentedFmodSpanKmh * documentedSegmentFraction
            ).coerceAtLeast(0.0)
    }

    companion object {
        private const val TOP_SPEED_KMH = 190.0
        private const val SPEED_EPSILON = 1e-6

        /**
         * Build the mapping from the documented/original physics for the selected bank.
         *
         * Each physical boundary is equally spaced, but each internal boundary is calculated
         * from the authored RPM that belongs there. Intermediate boundaries use the bank's
         * automatic upshift RPM; the final boundary uses its limiter RPM. This means the mapping
         * reaches the authored shift point at the requested physical speed without replacing any
         * authored shift decision.
         */
        fun from(
            documentedPhysics: AssettoPhysics,
            virtualGearProfile: VirtualGearProfile,
        ): EqualSpeedGearMapping {
            val authored = documentedPhysics.drivetrain
            val gearCount = virtualGearProfile.virtualForwardGearCount
            val wheelRadius = virtualGearProfile.wheelRadiusMeters
            val documentedUpshiftRpm = authored.automaticUpshiftRpm
                .toDouble()
                .takeIf { it > 0.0 }
                ?: documentedPhysics.engine.limiterRpm
            val documentedLimiterRpm = documentedPhysics.engine.limiterRpm
                .takeIf { it > 0.0 }
                ?: documentedUpshiftRpm

            val documentedPhysicalBoundarySpeedsKmh = virtualGearProfile.physicalBoundarySpeedsKmh
            val documentedFmodBoundarySpeedsKmh = (0..gearCount).map { boundaryIndex ->
                if (boundaryIndex == 0) {
                    0.0
                } else {
                    val gear = boundaryIndex
                    val boundaryRpm = if (gear == gearCount) {
                        documentedLimiterRpm
                    } else {
                        documentedUpshiftRpm
                    }
                    internalFmodSpeedKmhForRpm(
                        rpm = boundaryRpm,
                        authoredRatio = virtualGearProfile.ratioForVirtualGear(gear),
                        finalDrive = virtualGearProfile.finalDrive,
                        wheelRadius = wheelRadius,
                    )
                }
            }
            return EqualSpeedGearMapping(
                documentedPhysicalBoundarySpeedsKmh = documentedPhysicalBoundarySpeedsKmh,
                documentedFmodBoundarySpeedsKmh = documentedFmodBoundarySpeedsKmh,
            )
        }

        private fun internalFmodSpeedKmhForRpm(
            rpm: Double,
            authoredRatio: Double,
            finalDrive: Double,
            wheelRadius: Double,
        ): Double {
            val totalRatio = abs(authoredRatio * finalDrive)
            if (totalRatio <= SPEED_EPSILON) return 0.0
            val engineRadiansPerSecond = rpm * 2.0 * PI / 60.0
            val wheelRadiansPerSecond = engineRadiansPerSecond / totalRatio
            return wheelRadiansPerSecond * wheelRadius * 3.6
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
