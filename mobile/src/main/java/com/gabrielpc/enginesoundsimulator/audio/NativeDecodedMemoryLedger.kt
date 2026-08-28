package com.gabrielpc.enginesoundsimulator.audio

/**
 * Accounts for all decoded native PCM owned by the engine, including active,
 * pending, and retired profiles, plus the exact allocation reserved for the
 * one background decoder. All mutations happen off the audio thread.
 *
 * Reservation-to-resident transfer is one synchronized operation so diagnostic
 * snapshots can never temporarily double-count or under-count the same bytes.
 */
internal class NativeDecodedMemoryLedger(
    val budget: DecodedAudioBudget,
) {
    @Volatile
    var residentBytes: Long = 0L
        private set

    @Volatile
    var reservedBytes: Long = 0L
        private set

    val occupiedBytes: Long
        get() = synchronized(this) { Math.addExact(residentBytes, reservedBytes) }

    @Synchronized
    fun tryReserve(bytes: Long): Boolean {
        require(bytes > 0L) { "Native decode reservation must be positive" }
        require(bytes <= budget.hardBytes) {
            "Decoded family requires $bytes bytes; hard budget is ${budget.hardBytes}"
        }
        val occupied = Math.addExact(residentBytes, reservedBytes)
        if (bytes > budget.hardBytes - occupied) return false
        reservedBytes = Math.addExact(reservedBytes, bytes)
        return true
    }

    /** Converts one reservation to resident ownership without increasing peak accounting. */
    @Synchronized
    fun transferReservation(reserved: Long, actualResident: Long) {
        require(reserved > 0L) { "Transferred reservation must be positive" }
        require(actualResident in 0L..reserved) { "Native decode exceeded its reservation" }
        check(reserved <= reservedBytes) { "Transferred reservation is not outstanding" }
        reservedBytes -= reserved
        residentBytes = Math.addExact(residentBytes, actualResident)
        check(Math.addExact(residentBytes, reservedBytes) <= budget.hardBytes) {
            "Native decoded-audio hard budget was exceeded"
        }
    }

    @Synchronized
    fun releaseReservation(bytes: Long) {
        require(bytes > 0L) { "Released reservation must be positive" }
        check(bytes <= reservedBytes) { "Released reservation is not outstanding" }
        reservedBytes -= bytes
    }

    @Synchronized
    fun releaseResident(bytes: Long) {
        require(bytes >= 0L) { "Released resident bytes must not be negative" }
        check(bytes <= residentBytes) { "Released PCM is not resident" }
        residentBytes -= bytes
    }
}
