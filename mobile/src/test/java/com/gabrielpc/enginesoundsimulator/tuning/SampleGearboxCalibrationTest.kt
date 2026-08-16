package com.gabrielpc.enginesoundsimulator.tuning

import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleGearboxCalibrationTest {
    @Test
    fun topGearHitsRedlineAtConfiguredTopSpeed() {
        val profile = EngineSampleProfiles.default
        val gears = SampleGearboxCalibration.computeGearRatios()
        val rpm = SampleGearboxCalibration.roadCoupledRpm(
            speedKmh = 190.0,
            gearRatio = gears.last(),
            idleRpm = profile.idleRpm,
            finalDrive = profile.finalDrive,
            wheelRadiusMeters = 0.347,
        )

        assertEquals(profile.redlineRpm, rpm, 1.0)
    }

    @Test
    fun gearRatiosDescendGeometrically() {
        val gears = SampleGearboxCalibration.computeGearRatios()

        assertEquals(7, gears.size)
        assertTrue(gears.zipWithNext().all { (left, right) -> left > right })
        gears.zipWithNext().forEach { (left, right) ->
            val step = right / left
            assertTrue("adjacent ratios should step down gradually", step in 0.82..0.90)
        }
    }
}
