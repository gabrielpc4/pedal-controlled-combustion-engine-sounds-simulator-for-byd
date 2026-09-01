package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboSpoolTest {
    @Test
    fun skylineTurboMatchesTheAudioLabLagAndReferenceRpm() {
        val turbo = TurboSpoolModel()
        repeat(40) {
            turbo.update(0.02, rpm = 950.0, throttle = 1.0)
        }
        assertEquals(0.104, turbo.boost, 0.012)
        assertTrue(turbo.whistleGain() > 0.0)

        repeat(40) {
            turbo.update(0.02, rpm = 3_200.0, throttle = 1.0)
        }
        assertEquals(0.397, turbo.boost, 0.025)
        assertTrue(turbo.whistleGain() > 0.10)
        assertTrue(turbo.whistlePlaybackRatio() > 0.90)
    }

    @Test
    fun throttleLiftDumpsBoostAndOpensFlutter() {
        val turbo = TurboSpoolModel()
        repeat(50) {
            turbo.update(0.02, rpm = 4_500.0, throttle = 1.0)
        }
        val boostBeforeLift = turbo.boost
        assertTrue(boostBeforeLift > 0.55)

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
        assertTrue(turbo.boost in boostBeforeLift * 0.40..boostBeforeLift * 0.50)
        assertTrue(turbo.bovDecay < 0.08)
    }

    @Test
    fun throttleLiftRequiresChargeButVentsAfterAUsablePull() {
        val turbo = TurboSpoolModel()

        turbo.update(0.02, rpm = 4_000.0, throttle = 1.0)
        turbo.update(0.02, rpm = 4_000.0, throttle = 0.0)
        assertFalse("a brief pedal touch must not vent", turbo.consumeDumpPulse())

        repeat(20) {
            turbo.update(0.02, rpm = 4_000.0, throttle = 1.0)
        }
        assertTrue(turbo.boost > TurboSpoolModel.DUMP_CHARGE_THRESHOLD)

        turbo.update(0.02, rpm = 4_000.0, throttle = 0.0)
        assertTrue("a charged turbo should vent on the same throttle lift", turbo.consumeDumpPulse())
    }

    @Test
    fun freeRevAttackMultiplierBuildsBoostMuchFasterWithoutChangingLiftDecay() {
        val driveTurbo = TurboSpoolModel()
        val freeRevTurbo = TurboSpoolModel()

        repeat(5) {
            driveTurbo.update(0.02, rpm = 4_000.0, throttle = 1.0)
            freeRevTurbo.update(0.02, rpm = 4_000.0, throttle = 1.0, attackMultiplier = 10.0)
        }

        assertTrue(freeRevTurbo.boost > driveTurbo.boost * 6.0)
        assertTrue(freeRevTurbo.boost > 0.50)

        val boostBeforeLift = freeRevTurbo.boost
        freeRevTurbo.update(0.02, rpm = 4_000.0, throttle = 0.0, attackMultiplier = 10.0)
        assertTrue(freeRevTurbo.boost < boostBeforeLift)
        assertTrue(freeRevTurbo.consumeDumpPulse())
    }
}
