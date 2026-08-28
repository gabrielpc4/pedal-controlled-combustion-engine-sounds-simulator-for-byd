package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDecodedMemoryLedgerTest {
    private val budget = DecodedAudioBudget(softBytes = 64L, hardBytes = 100L)

    @Test
    fun activePendingAndRetiredProfilesShareOneHardBudget() {
        val ledger = NativeDecodedMemoryLedger(budget)

        assertTrue(ledger.tryReserve(60L))
        ledger.transferReservation(reserved = 60L, actualResident = 60L)
        assertEquals(60L, ledger.residentBytes)

        // The current profile remains resident while the pending replacement decodes.
        assertTrue(ledger.tryReserve(40L))
        assertEquals(100L, ledger.occupiedBytes)
        ledger.transferReservation(reserved = 40L, actualResident = 40L)

        // Retiring only transfers ownership; native PCM remains counted until close.
        assertFalse(ledger.tryReserve(1L))
        ledger.releaseResident(60L)
        assertTrue(ledger.tryReserve(20L))
        assertEquals(60L, ledger.occupiedBytes)
    }

    @Test
    fun reservationTransferAccountsOnlyActualDecodedBytesAtomically() {
        val ledger = NativeDecodedMemoryLedger(budget)

        assertTrue(ledger.tryReserve(50L))
        ledger.transferReservation(reserved = 50L, actualResident = 44L)

        assertEquals(44L, ledger.residentBytes)
        assertEquals(0L, ledger.reservedBytes)
        assertEquals(44L, ledger.occupiedBytes)
    }

    @Test
    fun cancelledDecodeReleasesItsEntireReservation() {
        val ledger = NativeDecodedMemoryLedger(budget)

        assertTrue(ledger.tryReserve(80L))
        ledger.releaseReservation(80L)

        assertEquals(0L, ledger.occupiedBytes)
        assertTrue(ledger.tryReserve(100L))
    }
}
