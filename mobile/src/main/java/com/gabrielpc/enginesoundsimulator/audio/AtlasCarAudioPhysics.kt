package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.pow

internal data class AtlasTurboStage(
    val index: Int,
    val lagUp: Double,
    val lagDown: Double,
    val maximumBoost: Double,
    val wastegate: Double,
    val referenceRpm: Double,
    val gamma: Double,
    val bovPressureThreshold: Double,
)

internal data class AtlasBackfirePhysics(
    val maximumGas: Double,
    val minimumRpm: Double,
    val maximumRpm: Double,
    val triggerGas: Double,
    val minimumIntentThrottle: Double,
    val minimumIntentSeconds: Double,
)

internal data class AtlasCarAudioPhysics(
    val turbos: List<AtlasTurboStage>,
    val turboBoostDivisor: Double,
    val backfire: AtlasBackfirePhysics,
    val limiterFrequencyHz: Double,
)

/** Assetto Corsa's per-stage turbo state and normalized FMOD control values. */
internal class AtlasTurboDynamics(
    private val physics: AtlasCarAudioPhysics,
) {
    private val stageCharge = DoubleArray(physics.turbos.size)
    private var previousBov = 0.0

    var boost: Double = 0.0
        private set

    var bov: Double = 0.0
        private set

    var bovDecay: Double = MAXIMUM_BOV_DECAY_SECONDS
        private set

    private var dumpPulse = false

    fun reset() {
        stageCharge.fill(0.0)
        previousBov = 0.0
        boost = 0.0
        bov = 0.0
        bovDecay = MAXIMUM_BOV_DECAY_SECONDS
        dumpPulse = false
    }

    fun update(
        dt: Double,
        rpm: Double,
        effectiveThrottle: Double,
        attackMultiplier: Double = 1.0,
    ) {
        if (physics.turbos.isEmpty()) {
            reset()
            return
        }
        val seconds = dt.coerceIn(0.0, MAXIMUM_UPDATE_SECONDS)
        val throttle = effectiveThrottle.coerceIn(0.0, 1.0)
        var physicalBoost = 0.0
        physics.turbos.forEachIndexed { index, turbo ->
            val input = (throttle * rpm.coerceAtLeast(0.0) / turbo.referenceRpm.coerceAtLeast(1.0))
                .coerceIn(0.0, 1.0)
            val target = input.pow(turbo.gamma)
            var charge = stageCharge[index]
            val lag = if (target > charge) {
                turbo.lagUp * attackMultiplier.coerceIn(MINIMUM_ATTACK_MULTIPLIER, MAXIMUM_ATTACK_MULTIPLIER)
            } else {
                turbo.lagDown
            }
            charge += (seconds * lag).coerceIn(0.0, 1.0) * (target - charge)
            if (turbo.wastegate > 0.0 && turbo.maximumBoost * charge > turbo.wastegate) {
                charge = turbo.wastegate / turbo.maximumBoost.coerceAtLeast(0.001)
            }
            stageCharge[index] = charge
            physicalBoost += turbo.maximumBoost * charge
        }
        boost = (physicalBoost / physics.turboBoostDivisor).coerceIn(0.0, 1.0)
        bov = if (physicalBoost * (1.0 - throttle) > physics.turbos.first().bovPressureThreshold) 1.0 else 0.0
        dumpPulse = dumpPulse || (bov > 0.0 && previousBov <= 0.0)
        bovDecay = if (bov > 0.0) 0.0 else (bovDecay + seconds).coerceAtMost(MAXIMUM_BOV_DECAY_SECONDS)
        previousBov = bov
    }

    fun consumeDumpPulse(): Boolean {
        val result = dumpPulse
        dumpPulse = false
        return result
    }

    private companion object {
        const val MINIMUM_ATTACK_MULTIPLIER = 0.25
        const val MAXIMUM_ATTACK_MULTIPLIER = 16.0
        const val MAXIMUM_UPDATE_SECONDS = 0.080
        const val MAXIMUM_BOV_DECAY_SECONDS = 10.0
    }
}
