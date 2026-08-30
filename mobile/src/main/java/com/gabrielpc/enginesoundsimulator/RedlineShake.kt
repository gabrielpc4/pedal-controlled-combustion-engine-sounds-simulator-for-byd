package com.gabrielpc.enginesoundsimulator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

data class RedlineShakeMotion(
    val needleTipAngleJitterDegrees: Float = 0f,
    val interiorTranslation: Offset = Offset.Zero,
)

internal fun redlineShakeIntensity(
    rpm: Double,
    redlineRpm: Double,
    maxRpm: Double,
    limiterActive: Boolean,
): Float {
    if (limiterActive) {
        return 1f
    }

    if (rpm < redlineRpm) {
        return 0f
    }

    val redZoneSpan = (maxRpm - redlineRpm).coerceAtLeast(1.0)
    val deepInRed = ((rpm - redlineRpm) / redZoneSpan).coerceIn(0.0, 1.0)

    return (0.35 + deepInRed * 0.55).toFloat()
}

@Composable
fun rememberRedlineShakeMotion(intensity: Float): RedlineShakeMotion {
    var motion by remember { mutableStateOf(RedlineShakeMotion()) }

    LaunchedEffect(intensity > 0f) {
        if (intensity <= 0f) {
            motion = RedlineShakeMotion()
            return@LaunchedEffect
        }

        while (true) {
            withFrameNanos { frameTimeNanos ->
                val t = frameTimeNanos * 1e-9
                val scale = intensity
                val tipJitter = (
                    sin(t * 132.0) * 1.85 +
                        sin(t * 191.0) * 1.15 +
                        cos(t * 247.0) * 0.72
                    ).toFloat() * scale
                val translationX = (
                    sin(t * 57.2) * 2.2 +
                        cos(t * 81.5) * 1.4
                    ).toFloat() * scale
                val translationY = (
                    cos(t * 49.8) * 1.8 +
                        sin(t * 74.6) * 1.2
                    ).toFloat() * scale

                motion = RedlineShakeMotion(
                    needleTipAngleJitterDegrees = tipJitter,
                    interiorTranslation = Offset(translationX, translationY),
                )
            }
        }
    }

    return if (intensity <= 0f) {
        RedlineShakeMotion()
    } else {
        motion
    }
}
