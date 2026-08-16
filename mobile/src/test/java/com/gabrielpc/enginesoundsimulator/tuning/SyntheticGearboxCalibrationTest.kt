package com.gabrielpc.enginesoundsimulator.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticGearboxCalibrationTest {
    @Test
    fun topGearHitsRedlineAtConfiguredTopSpeed() {
        val gears = SyntheticGearboxCalibration.computeGearRatios()
        val rpm = SyntheticGearboxCalibration.roadCoupledRpm(
            speedKmh = 190.0,
            gearRatio = gears.last(),
            idleRpm = 950.0,
            finalDrive = 3.82,
            wheelRadiusMeters = 0.347,
        )

        assertEquals(8_600.0, rpm, 1.0)
    }

    @Test
    fun gearRatiosDescendGeometrically() {
        val gears = SyntheticGearboxCalibration.computeGearRatios()

        assertEquals(7, gears.size)
        assertTrue(gears.zipWithNext().all { (left, right) -> left > right })
        gears.zipWithNext().forEach { (left, right) ->
            val step = right / left
            assertTrue("adjacent ratios should step down gradually", step in 0.82..0.90)
        }
    }
}
