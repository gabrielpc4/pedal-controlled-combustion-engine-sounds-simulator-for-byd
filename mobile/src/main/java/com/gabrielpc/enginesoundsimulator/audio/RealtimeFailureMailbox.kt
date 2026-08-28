package com.gabrielpc.enginesoundsimulator.audio

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.max

/**
 * Fixed-capacity, single-producer/single-consumer mailbox for realtime failures.
 *
 * [publish] performs only primitive atomic stores and stores the already-existing Throwable
 * reference as deferred diagnostic payload. It creates no record, formats no text and takes no
 * monitor. [poll] is called exclusively by the non-realtime decoder/retirement worker and may
 * allocate the presentation record used by diagnostics.
 */
internal class RealtimeFailureMailbox(capacity: Int = DEFAULT_CAPACITY) {
    private val slotCount = capacity.also { require(it > 0) }
    private val publishedSequence = AtomicLong(0L)
    private val slotSequences = AtomicLongArray(slotCount)
    private val failureCodes = AtomicIntegerArray(slotCount)
    private val runIds = AtomicLongArray(slotCount)
    private val throwables = AtomicReferenceArray<Throwable?>(slotCount)

    // Single-consumer field: only the decoder/retirement worker calls poll().
    private var consumedSequence = 0L

    fun publish(failureCode: Int, runId: Long, throwable: Throwable) {
        val sequence = publishedSequence.incrementAndGet()
        val slot = slotFor(sequence)
        failureCodes.lazySet(slot, failureCode)
        runIds.lazySet(slot, runId)
        throwables.lazySet(slot, throwable)
        // Publish the slot last so poll() cannot observe a partial record.
        slotSequences.set(slot, sequence)
    }

    fun poll(): RealtimeFailureRecord? {
        val newest = publishedSequence.get()
        if (newest <= consumedSequence) return null
        val next = max(consumedSequence + 1L, newest - slotCount + 1L)
        val slot = slotFor(next)
        if (slotSequences.get(slot) != next) return null
        val throwable = throwables.getAndSet(slot, null) ?: return null
        val dropped = (next - consumedSequence - 1L).coerceAtLeast(0L)
        consumedSequence = next
        return RealtimeFailureRecord(
            sequence = next,
            failureCode = failureCodes.get(slot),
            runId = runIds.get(slot),
            throwable = throwable,
            droppedBefore = dropped,
        )
    }

    private fun slotFor(sequence: Long): Int = ((sequence - 1L) % slotCount).toInt()

    private companion object {
        const val DEFAULT_CAPACITY = 4
    }
}

internal data class RealtimeFailureRecord(
    val sequence: Long,
    val failureCode: Int,
    val runId: Long,
    val throwable: Throwable,
    val droppedBefore: Long,
)

