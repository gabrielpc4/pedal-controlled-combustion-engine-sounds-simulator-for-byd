package com.gabrielpc.bydmotorsound

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayUnitsTest {
    @Test
    fun sealRatingsConvertToRequestedDisplayUnits() {
        assertEquals(68.32, newtonMetersToKgfm(670.0), 0.01)
        assertEquals(323.25, newtonMetersToKgfm(3_170.0), 0.01)
        assertEquals(405.34, newtonMetersToKgfm(3_975.0), 0.01)
        assertEquals(523.0, kilowattsToHorsepower(390.0), 0.5)
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
