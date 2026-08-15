package com.gabrielpc.enginesoundsimulator

import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayUnitsTest {
    @Test
    fun sealRatingsConvertToRequestedDisplayUnits() {
        assertEquals(68.32, newtonMetersToKgfm(670.0), 0.01)
        assertEquals(323.25, newtonMetersToKgfm(3_170.0), 0.01)
        assertEquals(405.34, newtonMetersToKgfm(3_975.0), 0.01)
        assertEquals(531.0, kilowattsToHorsepower(390.0), 1.0)
    }

    @Test
    fun wheelTorqueMotorEquivalentUsesConfiguredReduction() {
        val reduction = 10.81
        assertEquals(29.91, wheelNewtonMetersToMotorEquivalentKgfm(3_170.0, reduction), 0.05)
        assertEquals(37.48, wheelNewtonMetersToMotorEquivalentKgfm(3_975.0, reduction), 0.05)
        assertEquals(67.40, wheelNewtonMetersToMotorEquivalentKgfm(7_145.0, reduction), 0.05)
        assertEquals(
            3_170.0,
            motorEquivalentKgfmToWheelNewtonMeters(29.91, reduction),
            1.0,
        )
    }

    @Test
    fun wheelPowerDisplayScalesToMotorPeakRating() {
        val engine = EngineTuning()
        val peakWheelKw = peakWheelPowerKw(engine)
        val displayAtPeakKw = wheelKilowattsToMotorEquivalentDisplayKw(
            wheelKilowatts = peakWheelKw,
            motorPeakPowerKw = engine.peakPowerKw,
            peakWheelPowerKw = peakWheelKw,
        )
        assertEquals(390.0, displayAtPeakKw, 0.5)
        assertEquals(531.0, kilowattsToHorsepower(displayAtPeakKw), 1.0)
    }

    @Test
    fun displayConversionsRoundTripWithoutChangingStoredSiValues() {
        listOf(0.0, 150.0, 670.0, 3_170.0, 7_000.0).forEach { newtonMeters ->
            assertEquals(
                newtonMeters,
                kgfmToNewtonMeters(newtonMetersToKgfm(newtonMeters)),
                1e-9,
            )
        }
        listOf(0.0, 100.0, 390.0, 800.0).forEach { kilowatts ->
            assertEquals(
                kilowatts,
                horsepowerToKilowatts(kilowattsToHorsepower(kilowatts)),
                1e-9,
            )
        }
    }
}
