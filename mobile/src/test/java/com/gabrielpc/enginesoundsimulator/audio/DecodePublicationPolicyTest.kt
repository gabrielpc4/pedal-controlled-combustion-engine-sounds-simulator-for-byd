package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodePublicationPolicyTest {
    @Test
    fun `only the current live decode may publish activation or failure state`() {
        assertTrue(
            DecodePublicationPolicy.canPublish(
                closed = false,
                requestSerial = 7L,
                currentSerial = 7L,
                cancelled = false,
                newerRequestQueued = false,
            ),
        )
        assertFalse(current(requestSerial = 6L, currentSerial = 7L))
        assertFalse(current(cancelled = true))
        assertFalse(current(newerRequestQueued = true))
        assertFalse(current(closed = true))
    }

    private fun current(
        closed: Boolean = false,
        requestSerial: Long = 7L,
        currentSerial: Long = 7L,
        cancelled: Boolean = false,
        newerRequestQueued: Boolean = false,
    ) = DecodePublicationPolicy.canPublish(
        closed,
        requestSerial,
        currentSerial,
        cancelled,
        newerRequestQueued,
    )
}
