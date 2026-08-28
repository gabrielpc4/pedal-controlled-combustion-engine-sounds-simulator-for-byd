package com.gabrielpc.enginesoundsimulator.tuning

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
            throttleCurve = listOf(
                CurvePoint(0.8, 0.4),
                CurvePoint(0.2, 0.9),
                CurvePoint(0.6, 0.1),
            ),
        ).sanitized()

        assertEquals(5_000.0, result.maxRpm, 0.0)
        assertTrue(result.idleRpm < result.upshiftRpm)
        assertTrue(result.upshiftRpm <= result.redlineRpm)
        assertTrue(result.redlineRpm <= result.limiterRpm)
        assertTrue(result.limiterRpm <= result.maxRpm)
        assertEquals(CurvePoint(0.0, 0.0), result.throttleCurve.first())
        assertEquals(CurvePoint(1.0, 1.0), result.throttleCurve.last())
        assertTrue(result.throttleCurve.zipWithNext().all { (left, right) -> left.x < right.x })
    }

    @Test
    fun sanitizerKeepsGearRatiosDescending() {
        val result = EngineTuning(gearRatios = listOf(2.0, 3.0, 1.5, 1.8, 0.7)).sanitized()

        assertEquals(5, result.gearRatios.size)
        assertTrue(result.gearRatios.zipWithNext().all { (left, right) -> left > right })
        assertTrue(result.gearRatios.all { it in 0.45..8.0 })
    }

    @Test
    fun sanitizerPreservesOfficialHighIdleFastShiftAndFirstGearValues() {
        val result = EngineTuning(
            idleRpm = 4_000.0,
            maxRpm = 19_300.0,
            redlineRpm = 18_800.0,
            limiterRpm = 18_800.0,
            upshiftRpm = 18_500.0,
            upshiftDurationMs = 15.000001,
            downshiftDurationMs = 20.000001,
            gearRatios = listOf(5.09, 3.20, 2.10, 1.40),
        ).sanitized()

        assertEquals(4_000.0, result.idleRpm, 0.0)
        assertEquals(19_300.0, result.maxRpm, 0.0)
        assertEquals(18_500.0, result.upshiftRpm, 0.0)
        assertEquals(15.000001, result.upshiftDurationMs, 0.0)
        assertEquals(20.000001, result.downshiftDurationMs, 0.0)
        assertEquals(listOf(5.09, 3.20, 2.10, 1.40), result.gearRatios)
    }

    @Test
    fun audioLevelsAreBoundedBeforeReachingRealtimeRenderer() {
        val clean = AudioTuning(masterGain = -1.0).sanitized()

        assertEquals(0.0, clean.masterGain, 0.0)
    }
}
