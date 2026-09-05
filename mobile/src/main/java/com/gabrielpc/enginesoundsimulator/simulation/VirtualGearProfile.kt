package com.gabrielpc.enginesoundsimulator.simulation

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Virtual forward-gear profile layered over bank-authored drivetrain data.
 *
 * Extends the bank's ratio list to [virtualForwardGearCount], builds non-uniform physical
 * speed bands anchored at 60 km/h in 4th gear (reference for 10 gears), and exposes safe
 * ratio lookup for simulation and FMOD speed mapping.
 */
internal data class VirtualGearProfile(
    val virtualForwardGearCount: Int,
    val synthesizedRatios: List<Double>,
    val physicalBoundarySpeedsKmh: List<Double>,
    val finalDrive: Double,
    val wheelRadiusMeters: Double,
) {
    init {
        require(virtualForwardGearCount in MIN_VIRTUAL_GEARS..MAX_VIRTUAL_GEARS) {
            "Virtual gear count must be between $MIN_VIRTUAL_GEARS and $MAX_VIRTUAL_GEARS"
        }
        require(synthesizedRatios.size == virtualForwardGearCount) {
            "Expected $virtualForwardGearCount synthesized ratios, got ${synthesizedRatios.size}"
        }
        require(physicalBoundarySpeedsKmh.size == virtualForwardGearCount + 1) {
            "Expected ${virtualForwardGearCount + 1} physical boundaries"
        }
    }

    fun ratioForVirtualGear(gear: Int): Double {
        if (gear == -1) {
            error("Reverse ratio is not part of the virtual forward profile")
        }
        if (gear == 0) {
            return 0.0
        }
        if (gear !in 1..virtualForwardGearCount) {
            error("Virtual gear $gear is outside 1..$virtualForwardGearCount")
        }
        return synthesizedRatios[gear - 1]
    }

    companion object {
        const val MIN_VIRTUAL_GEARS = 6
        const val MAX_VIRTUAL_GEARS = 10
        const val DEFAULT_VIRTUAL_GEARS = 10
        private const val REFERENCE_GEAR_COUNT = 10
        private const val REFERENCE_ANCHOR_GEAR = 4
        private const val REFERENCE_ANCHOR_SPEED_KMH = 60.0
        private const val REFERENCE_ANCHOR_BAND_SPAN_KMH = 25.0
        private const val REFERENCE_ANCHOR_BAND_FRACTION = 0.15
        private const val TOP_SPEED_KMH = 190.0

        fun from(
            physics: AssettoPhysics,
            virtualGearCount: Int,
        ): VirtualGearProfile {
            val count = virtualGearCount.coerceIn(MIN_VIRTUAL_GEARS, MAX_VIRTUAL_GEARS)
            val wheelRadius = drivenWheelRadius(physics)
            val ratios = synthesizeForwardRatios(
                authoredRatios = physics.drivetrain.forwardRatios,
                virtualCount = count,
            )
            val boundaries = physicalBoundarySpeedsKmh(count)
            return VirtualGearProfile(
                virtualForwardGearCount = count,
                synthesizedRatios = ratios,
                physicalBoundarySpeedsKmh = boundaries,
                finalDrive = physics.drivetrain.finalDrive,
                wheelRadiusMeters = wheelRadius,
            )
        }

        internal fun synthesizeForwardRatios(
            authoredRatios: List<Double>,
            virtualCount: Int,
        ): List<Double> {
            require(authoredRatios.isNotEmpty()) { "Bank must provide at least one forward ratio" }
            if (virtualCount <= authoredRatios.size) {
                return authoredRatios.take(virtualCount)
            }

            val result = authoredRatios.toMutableList()
            val lastIndex = authoredRatios.lastIndex
            val logStep = if (lastIndex >= 1 && authoredRatios[lastIndex - 1] > 0.0) {
                ln(abs(authoredRatios[lastIndex]) / abs(authoredRatios[lastIndex - 1]))
            } else {
                ln(0.82)
            }
            var currentRatio = abs(authoredRatios[lastIndex])
            while (result.size < virtualCount) {
                currentRatio *= kotlin.math.exp(logStep)
                result.add(currentRatio)
            }
            return result
        }

        internal fun physicalBoundarySpeedsKmh(virtualGearCount: Int): List<Double> {
            val scale = virtualGearCount.toDouble() / REFERENCE_GEAR_COUNT
            val anchorGear = (REFERENCE_ANCHOR_GEAR * scale).roundToInt()
                .coerceIn(2, virtualGearCount - 2)
            val anchorSpeed = REFERENCE_ANCHOR_SPEED_KMH * scale
            val anchorSpan = REFERENCE_ANCHOR_BAND_SPAN_KMH * scale
            val anchorLow = anchorSpeed - REFERENCE_ANCHOR_BAND_FRACTION * anchorSpan
            val anchorHigh = anchorLow + anchorSpan

            val boundaries = DoubleArray(virtualGearCount + 1)
            boundaries[0] = 0.0
            boundaries[virtualGearCount] = TOP_SPEED_KMH
            boundaries[anchorGear - 1] = anchorLow.coerceIn(0.0, TOP_SPEED_KMH)
            boundaries[anchorGear] = anchorHigh.coerceIn(boundaries[anchorGear - 1], TOP_SPEED_KMH)

            if (anchorGear > 1) {
                for (index in 1 until anchorGear - 1) {
                    boundaries[index] = boundaries[anchorGear - 1] * index / (anchorGear - 1)
                }
            }

            val upperSegmentCount = virtualGearCount - anchorGear
            if (upperSegmentCount > 0) {
                for (offset in 1 until upperSegmentCount) {
                    val index = anchorGear + offset
                    boundaries[index] = boundaries[anchorGear] +
                        (TOP_SPEED_KMH - boundaries[anchorGear]) * offset / upperSegmentCount
                }
            }

            return boundaries.toList()
        }
    }
}
