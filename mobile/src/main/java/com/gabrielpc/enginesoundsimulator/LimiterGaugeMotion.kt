package com.gabrielpc.enginesoundsimulator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
internal fun rememberLimiterGaugeRpmJitter(
    active: Boolean,
    amplitudeFraction: Float = 0.009f,
): Float {
    val transition = rememberInfiniteTransition(label = "limiterGaugeRpmJitter")
    val fastWave by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fastWave",
    )
    val slowWave by transition.animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 39, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slowWave",
    )

    if (!active) {
        return 0f
    }

    return (fastWave * 0.62f + slowWave * 0.38f) * amplitudeFraction
}
