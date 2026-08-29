package com.gabrielpc.enginesoundsimulator.simulation

enum class EngineIgnitionState {
    OFF,
    STARTING,
    RUNNING,
    STOPPING,
}

internal const val ENGINE_START_PEAK_RPM = 5_000.0

internal fun engineStartRpmAt(elapsedSeconds: Double, idleRpm: Double): Double {
    val crankEndSeconds = 0.35
    val catchEndSeconds = 0.55
    val blipEndSeconds = 0.95
    val settleEndSeconds = 2.0
    val peakRpm = ENGINE_START_PEAK_RPM

    return when {
        elapsedSeconds <= 0.0 -> 0.0

        elapsedSeconds < crankEndSeconds -> {
            val fraction = elapsedSeconds / crankEndSeconds
            idleRpm * 0.28 * fraction
        }

        elapsedSeconds < catchEndSeconds -> {
            val fraction = smoothstep((elapsedSeconds - crankEndSeconds) / (catchEndSeconds - crankEndSeconds))
            lerp(idleRpm * 0.28, idleRpm, fraction)
        }

        elapsedSeconds < blipEndSeconds -> {
            val fraction = smoothstep((elapsedSeconds - catchEndSeconds) / (blipEndSeconds - catchEndSeconds))
            lerp(idleRpm, peakRpm, fraction)
        }

        elapsedSeconds < settleEndSeconds -> {
            val fraction = smoothstep((elapsedSeconds - blipEndSeconds) / (settleEndSeconds - blipEndSeconds))
            lerp(peakRpm, idleRpm, fraction)
        }

        else -> idleRpm
    }
}

internal fun engineStartSettled(elapsedSeconds: Double): Boolean = elapsedSeconds >= 2.0

internal fun shutdownSecondsUntilZeroRpm(startRpm: Double, decaySeconds: Double, epsilon: Double): Double {
    val rpm = startRpm.coerceAtLeast(epsilon)
    return decaySeconds * kotlin.math.ln(rpm / epsilon)
}

private fun lerp(start: Double, end: Double, fraction: Double): Double {
    return start + (end - start) * fraction.coerceIn(0.0, 1.0)
}

private fun smoothstep(fraction: Double): Double {
    val clamped = fraction.coerceIn(0.0, 1.0)
    return clamped * clamped * (3.0 - 2.0 * clamped)
}
