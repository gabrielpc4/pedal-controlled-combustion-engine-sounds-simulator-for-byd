package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboSpoolTest {
    @Test
    fun ceramicTwinsStayQuietAtIdleThenSpoolWithThrottle() {
        val turbo = TurboSpoolModel()
        repeat(40) {
            turbo.update(0.02, rpm = 950.0, throttle = 1.0)
        }
        assertTrue(turbo.boost < 0.08)
        assertEquals(0.0, turbo.whistleGain(), 0.0)

        repeat(40) {
            turbo.update(0.02, rpm = 3_200.0, throttle = 1.0)
        }
        assertTrue(turbo.boost > 0.70)
        assertTrue(turbo.whistleGain() > 0.40)
        assertTrue(turbo.whistlePlaybackRatio() > 1.0)
    }

    @Test
    fun throttleLiftDumpsBoostAndOpensFlutter() {
        val turbo = TurboSpoolModel()
        repeat(50) {
            turbo.update(0.02, rpm = 4_500.0, throttle = 1.0)
        }
        val boostBeforeLift = turbo.boost
        assertTrue(boostBeforeLift > 0.70)

        turbo.update(0.02, rpm = 4_500.0, throttle = 0.0)
        assertTrue(turbo.consumeDumpPulse())
        assertTrue(!turbo.consumeDumpPulse())
        assertTrue(turbo.bovDecay > 0.25)
        assertTrue(turbo.bovDecay <= 0.55)
        assertTrue(turbo.flutterGain() > 0.25)
        assertEquals(turbo.bovDecay, turbo.flutterGain(), 0.0)

        repeat(40) {
            turbo.update(0.02, rpm = 4_500.0, throttle = 0.0)
        }
        assertTrue(turbo.boost < boostBeforeLift * 0.20)
        assertTrue(turbo.bovDecay < 0.08)
    }
}
