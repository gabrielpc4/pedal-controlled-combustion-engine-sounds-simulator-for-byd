package com.gabrielpc.bydmotorsound.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningConfigTest {
    @Test
    fun interpolationTracksEditableControlPoints() {
        val curve = listOf(
            CurvePoint(0.0, 0.0),
            CurvePoint(0.5, 0.8),
            CurvePoint(1.0, 1.0),
        )

        assertEquals(0.4, interpolateCurve(curve, 0.25), 0.0001)
        assertEquals(0.9, interpolateCurve(curve, 0.75), 0.0001)
    }

    @Test
    fun sanitizerMaintainsSafeRpmAndThrottleEnvelope() {
        val result = EngineTuning(
            idleRpm = 2_500.0,
            maxRpm = 3_000.0,
            redlineRpm = 14_000.0,
            limiterRpm = 15_000.0,
            upshiftRpm = 15_000.0,
            downshiftRpm = 9_000.0,
            throttleCurve = listOf(
                CurvePoint(0.8, 0.4),
                CurvePoint(0.2, 0.9),
                CurvePoint(0.6, 0.1),
            ),
        ).sanitized()

        assertEquals(6_000.0, result.maxRpm, 0.0)
        assertTrue(result.idleRpm < result.upshiftRpm)
        assertTrue(result.upshiftRpm < result.redlineRpm)
        assertTrue(result.redlineRpm <= result.limiterRpm)
        assertTrue(result.limiterRpm < result.maxRpm)
        assertTrue(result.downshiftRpm < result.upshiftRpm)
        assertEquals(CurvePoint(0.0, 0.0), result.throttleCurve.first())
        assertEquals(CurvePoint(1.0, 1.0), result.throttleCurve.last())
        assertTrue(result.throttleCurve.zipWithNext().all { (left, right) -> left.x < right.x })
    }

    @Test
    fun sanitizerKeepsGearRatiosDescending() {
        val result = EngineTuning(gearRatios = listOf(2.0, 3.0, 1.5, 1.8, 0.7)).sanitized()

        assertEquals(5, result.gearRatios.size)
        assertTrue(result.gearRatios.zipWithNext().all { (left, right) -> left > right })
        assertTrue(result.gearRatios.all { it in 0.45..5.0 })
    }

    @Test
    fun audioLevelsAreBoundedBeforeReachingRealtimeRenderer() {
        val clean = AudioTuning(masterGain = -1.0, exhaustLevel = 8.0, harmonic5 = 3.0).sanitized()

        assertEquals(0.0, clean.masterGain, 0.0)
        assertEquals(1.5, clean.exhaustLevel, 0.0)
        assertEquals(1.5, clean.harmonic5, 0.0)
    }
}
