package com.gabrielpc.enginesoundsimulator

import org.junit.Assert.assertEquals
import org.junit.Test

class RedlineShakeTest {
    @Test
    fun shakeIntensityIsZeroBelowRedline() {
        val intensity = redlineShakeIntensity(
            rpm = 6_000.0,
            redlineRpm = 7_000.0,
            maxRpm = 8_000.0,
            limiterActive = false,
        )

        assertEquals(0f, intensity, 0f)
    }

    @Test
    fun shakeIntensityIsFullWhenLimiterIsActive() {
        val intensity = redlineShakeIntensity(
            rpm = 7_500.0,
            redlineRpm = 7_000.0,
            maxRpm = 8_000.0,
            limiterActive = true,
        )

        assertEquals(1f, intensity, 0f)
    }

    @Test
    fun shakeIntensityScalesInsideRedZone() {
        val atRedline = redlineShakeIntensity(
            rpm = 7_000.0,
            redlineRpm = 7_000.0,
            maxRpm = 8_000.0,
            limiterActive = false,
        )
        val nearMax = redlineShakeIntensity(
            rpm = 7_900.0,
            redlineRpm = 7_000.0,
            maxRpm = 8_000.0,
            limiterActive = false,
        )

        assertEquals(0.35f, atRedline, 0.001f)
        assertEquals(0.845f, nearMax, 0.001f)
    }
}
