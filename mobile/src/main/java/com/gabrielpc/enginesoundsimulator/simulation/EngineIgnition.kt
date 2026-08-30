package com.gabrielpc.enginesoundsimulator.simulation

enum class EngineIgnitionState {
    OFF,
    STARTING,
    RUNNING,
    STOPPING,
}

internal const val ENGINE_START_PEAK_RPM = 5_000.0
internal const val ENGINE_START_CRANK_END_SECONDS = 0.35
internal const val ENGINE_START_CATCH_END_SECONDS = 0.55
internal const val ENGINE_START_BLIP_END_SECONDS = 0.95
internal const val ENGINE_START_SETTLE_END_SECONDS = 2.0
internal const val ENGINE_START_AUDIO_OPEN_SECONDS = 0.28
internal const val ENGINE_START_AUDIO_FADE_SECONDS = 0.04

internal fun engineStartRpmAt(elapsedSeconds: Double, idleRpm: Double): Double {
    val peakRpm = ENGINE_START_PEAK_RPM

    return when {
        elapsedSeconds <= 0.0 -> 0.0

        elapsedSeconds < ENGINE_START_CRANK_END_SECONDS -> {
            val fraction = elapsedSeconds / ENGINE_START_CRANK_END_SECONDS
            idleRpm * 0.28 * fraction
        }

        elapsedSeconds < ENGINE_START_CATCH_END_SECONDS -> {
            val fraction = smoothstep(
                (elapsedSeconds - ENGINE_START_CRANK_END_SECONDS) /
                    (ENGINE_START_CATCH_END_SECONDS - ENGINE_START_CRANK_END_SECONDS),
            )
            lerp(idleRpm * 0.28, idleRpm, fraction)
        }

        elapsedSeconds < ENGINE_START_BLIP_END_SECONDS -> {
            val fraction = smoothstep(
                (elapsedSeconds - ENGINE_START_CATCH_END_SECONDS) /
                    (ENGINE_START_BLIP_END_SECONDS - ENGINE_START_CATCH_END_SECONDS),
            )
            lerp(idleRpm, peakRpm, fraction)
        }

        elapsedSeconds < ENGINE_START_SETTLE_END_SECONDS -> {
            val fraction = smoothstep(
                (elapsedSeconds - ENGINE_START_BLIP_END_SECONDS) /
                    (ENGINE_START_SETTLE_END_SECONDS - ENGINE_START_BLIP_END_SECONDS),
            )
            lerp(peakRpm, idleRpm, fraction)
        }

        else -> idleRpm
    }
}

internal fun engineStartSettled(elapsedSeconds: Double): Boolean =
    elapsedSeconds >= ENGINE_START_SETTLE_END_SECONDS

/** Mute during crank; open quickly once the starter catches, before the rev blip. */
internal fun startupIgnitionAudioGain(elapsedSeconds: Double): Double {
    if (elapsedSeconds <= ENGINE_START_AUDIO_OPEN_SECONDS) {
        return 0.0
    }

    if (elapsedSeconds >= ENGINE_START_AUDIO_OPEN_SECONDS + ENGINE_START_AUDIO_FADE_SECONDS) {
        return 1.0
    }

    val fraction = (
        elapsedSeconds - ENGINE_START_AUDIO_OPEN_SECONDS
        ) / ENGINE_START_AUDIO_FADE_SECONDS

    return smoothstep(fraction)
}

/** Audible fade on shutdown — straight linear ramp, no easing curve. */
internal const val SHUTDOWN_AUDIO_FADE_SECONDS = 0.70

internal fun shutdownIgnitionAudioGain(elapsedSeconds: Double): Double {
    if (elapsedSeconds >= SHUTDOWN_AUDIO_FADE_SECONDS) {
        return 0.0
    }

    if (elapsedSeconds <= 0.0) {
        return 1.0
    }

    val fraction = (elapsedSeconds / SHUTDOWN_AUDIO_FADE_SECONDS).coerceIn(0.0, 1.0)

    return 1.0 - fraction
}

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

