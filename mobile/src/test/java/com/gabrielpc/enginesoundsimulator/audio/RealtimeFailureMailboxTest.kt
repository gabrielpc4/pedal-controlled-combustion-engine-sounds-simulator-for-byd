package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RealtimeFailureMailboxTest {
    @Test
    fun `realtime publisher transfers existing throwable and primitive fields in order`() {
        val mailbox = RealtimeFailureMailbox(capacity = 4)
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        mailbox.publish(failureCode = 7, runId = 101L, throwable = first)
        mailbox.publish(failureCode = 8, runId = 102L, throwable = second)

        mailbox.poll()!!.also { failure ->
            assertEquals(1L, failure.sequence)
            assertEquals(7, failure.failureCode)
            assertEquals(101L, failure.runId)
            assertSame(first, failure.throwable)
            assertEquals(0L, failure.droppedBefore)
        }
        mailbox.poll()!!.also { failure ->
            assertEquals(2L, failure.sequence)
            assertEquals(8, failure.failureCode)
            assertEquals(102L, failure.runId)
            assertSame(second, failure.throwable)
            assertEquals(0L, failure.droppedBefore)
        }
        assertNull(mailbox.poll())
    }

    @Test
    fun `fixed mailbox reports overwritten failures without allocating on publisher`() {
        val mailbox = RealtimeFailureMailbox(capacity = 2)
        val failures = List(4) { index -> IllegalStateException("failure-$index") }

        failures.forEachIndexed { index, throwable ->
            mailbox.publish(failureCode = 1, runId = index.toLong(), throwable = throwable)
        }

        mailbox.poll()!!.also { failure ->
            assertEquals(3L, failure.sequence)
            assertEquals(2L, failure.runId)
            assertSame(failures[2], failure.throwable)
            assertEquals(2L, failure.droppedBefore)
        }
        mailbox.poll()!!.also { failure ->
            assertEquals(4L, failure.sequence)
            assertEquals(3L, failure.runId)
            assertSame(failures[3], failure.throwable)
            assertEquals(0L, failure.droppedBefore)
        }
        assertNull(mailbox.poll())
    }
}
