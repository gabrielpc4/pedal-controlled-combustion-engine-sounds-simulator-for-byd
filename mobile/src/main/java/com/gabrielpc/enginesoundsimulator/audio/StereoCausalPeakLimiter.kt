package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs

/** Allocation-free, zero-delay stereo-linked limiter required by the atlas host-mix contract. */
internal class StereoCausalPeakLimiter(
    private val ceilingLinear: Double,
    private val releaseFrames: Int,
) {
    private var gain = 1.0

    init {
        require(ceilingLinear in 0.0..1.0)
        require(releaseFrames > 0)
    }

    fun process(left: Double, right: Double, destination: DoubleArray) {
        require(destination.size >= 2)
        val detectorPeak = maxOf(abs(left), abs(right))
        val targetGain = if (detectorPeak <= ceilingLinear || detectorPeak == 0.0) {
            1.0
        } else {
            ceilingLinear / detectorPeak
        }
        gain = if (targetGain < gain) {
            targetGain
        } else {
            minOf(targetGain, gain + (1.0 - gain) / releaseFrames)
        }
        destination[0] = left * gain
        destination[1] = right * gain
    }

    fun reset() {
        gain = 1.0
    }
}
