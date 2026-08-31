package com.gabrielpc.enginesoundsimulator.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * One background dispatcher shared by every continuous effect group in a renderer.
 *
 * The audio callback is the sole producer of [pendingGroupIndices]. Each group may be queued only
 * once, so the fixed queue can never exceed [maximumGroups]. The worker sleeps without polling and
 * visits only groups that requested work; a family with 35 continuous groups still owns one worker
 * thread rather than 35 thread stacks and 35 periodic wakeups.
 */
internal class AtlasContinuousEffectDispatcher(
    private val maximumGroups: Int,
    startWorker: Boolean = true,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val groups = arrayOfNulls<AtlasContinuousEffectHotSet>(maximumGroups.coerceAtLeast(1))
    private val pendingGroupIndices = IntArray(maximumGroups.coerceAtLeast(1))
    private val writeSequence = AtomicLong(0L)
    @Volatile private var readSequence = 0L
    private var registeredGroupCount = 0
    private val worker = Thread(::pump, "atlas-continuous-effect-dispatcher").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        require(maximumGroups >= 0)
        if (startWorker && maximumGroups > 0) worker.start()
    }

    internal fun register(group: AtlasContinuousEffectHotSet): Int {
        check(!closed.get())
        check(registeredGroupCount < maximumGroups) {
            "Continuous atlas group count exceeds the renderer's fixed dispatcher capacity"
        }
        val index = registeredGroupCount
        groups[index] = group
        registeredGroupCount += 1

        return index
    }

    /** Callback-only, allocation-free, bounded SPSC publication. */
    internal fun schedule(groupIndex: Int) {
        if (closed.get()) return
        val group = requireNotNull(groups[groupIndex])
        if (!group.markQueued()) return
        val write = writeSequence.get()
        check(write - readSequence < maximumGroups) {
            "Continuous atlas dispatcher exceeded its one-entry-per-group queue bound"
        }
        pendingGroupIndices[(write % pendingGroupIndices.size).toInt()] = groupIndex
        writeSequence.lazySet(write + 1L)
        LockSupport.unpark(worker)
    }

    internal val debugRegisteredGroupCount: Int get() = registeredGroupCount
    internal val debugWorkerThreadId: Long get() = worker.id
    internal val debugWorkerAlive: Boolean get() = worker.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LockSupport.unpark(worker)
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (!worker.isAlive) {
            // startWorker=false exists only for deterministic unit tests; no mapping can have been
            // opened in that mode, but close defensively keeps ownership explicit.
            var index = 0
            while (index < registeredGroupCount) {
                groups[index]?.closeOffCallback()
                index += 1
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun pump() {
        val retryIndices = IntArray(maximumGroups.coerceAtLeast(1))
        var retryRead = 0
        var retryWrite = 0
        var retrySize = 0
        try {
            while (!closed.get()) {
                val groupIndex = when {
                    readSequence < writeSequence.get() -> {
                        val value = pendingGroupIndices[(readSequence % pendingGroupIndices.size).toInt()]
                        readSequence += 1L
                        value
                    }
                    retrySize > 0 -> retryIndices[retryRead].also {
                        retryRead = (retryRead + 1) % retryIndices.size
                        retrySize -= 1
                    }
                    else -> {
                        LockSupport.park()
                        continue
                    }
                }
                val group = requireNotNull(groups[groupIndex])
                val signalBefore = group.workerSignalVersion
                val retry = group.processWorkerRequest()
                if (retry) {
                    check(retrySize < retryIndices.size)
                    retryIndices[retryWrite] = groupIndex
                    retryWrite = (retryWrite + 1) % retryIndices.size
                    retrySize += 1
                } else {
                    group.clearQueued()
                    // If the callback changed state while this group was marked queued, it could
                    // not enqueue a duplicate. Recover that race into the worker-local retry FIFO.
                    if (group.workerSignalVersion != signalBefore && group.markQueued()) {
                        check(retrySize < retryIndices.size)
                        retryIndices[retryWrite] = groupIndex
                        retryWrite = (retryWrite + 1) % retryIndices.size
                        retrySize += 1
                    }
                }
            }
        } finally {
            var index = 0
            while (index < registeredGroupCount) {
                groups[index]?.closeOffCallback()
                index += 1
            }
        }
    }
}

/**
 * Background-mapped PCM state for one continuous scheduling group.
 *
 * The callback publishes desired node indices through fixed seqlock storage. The shared worker
 * prepares the complete replacement set while the previous set remains valid, then publishes it
 * atomically. After the callback swaps voices and acknowledges, only the worker closes retired
 * regions. Mapping, prewarming, and unmapping therefore never run on the audio callback.
 */
internal class AtlasContinuousEffectHotSet(
    private val nodes: Array<AtlasEffectNode>,
    private val regionFactory: AtlasEffectPcmRegionFactory,
    private val maximumCurrentCorners: Int,
    private val dispatcher: AtlasContinuousEffectDispatcher,
) {
    internal class ReadySet internal constructor(maximumCorners: Int) {
        val nodeIndices = IntArray(maximumCorners)
        val regions = arrayOfNulls<AtlasEffectPcmRegion>(maximumCorners)
        var count = 0
            internal set
        var generation = 0
            internal set
    }

    private val queued = AtomicBoolean(false)
    private val requestSequence = AtomicInteger(0)
    private val signalVersion = AtomicInteger(0)
    private val acknowledgedGeneration = AtomicInteger(0)
    private val failures = AtomicInteger(0)
    private val requestedIndices = IntArray(maximumCurrentCorners)
    private val callbackLastRequestedIndices = IntArray(maximumCurrentCorners)
    private var callbackLastRequestedCount = 0
    private var callbackLastRequestedGeneration = 0
    @Volatile private var requestedCount = 0
    private val slots = arrayOf(ReadySet(maximumCurrentCorners), ReadySet(maximumCurrentCorners))
    @Volatile private var publishedSlotIndex = -1
    @Volatile private var publishedGeneration = 0
    @Volatile private var currentRegionCount = 0
    @Volatile private var transitionRegionCount = 0
    @Volatile private var peakTransitionRegionCount = 0
    private var currentSlot = -1
    private var stagingSlot = -1
    private var processedGeneration = 0
    private val dispatcherIndex: Int

    init {
        require(nodes.isNotEmpty())
        require(maximumCurrentCorners in 1..nodes.size)
        dispatcherIndex = dispatcher.register(this)
    }

    /** Callback-safe. Returns the stable generation representing this exact desired set. */
    fun request(nodeIndices: IntArray, count: Int): Int {
        require(count in 1..maximumCurrentCorners && nodeIndices.size >= count)
        var index = 0
        var unchanged = count == callbackLastRequestedCount
        while (index < count) {
            val nodeIndex = nodeIndices[index]
            require(nodeIndex in nodes.indices)
            unchanged = unchanged && callbackLastRequestedIndices[index] == nodeIndex
            index += 1
        }
        if (unchanged) return callbackLastRequestedGeneration

        requestSequence.incrementAndGet() // odd: worker must retry rather than observe a partial set.
        index = 0
        while (index < count) {
            val nodeIndex = nodeIndices[index]
            requestedIndices[index] = nodeIndex
            callbackLastRequestedIndices[index] = nodeIndex
            index += 1
        }
        requestedCount = count
        callbackLastRequestedCount = count
        val generation = requestSequence.incrementAndGet() // even publication barrier.
        callbackLastRequestedGeneration = generation
        signalVersion.incrementAndGet()
        dispatcher.schedule(dispatcherIndex)

        return generation
    }

    /** Callback-safe and allocation-free. The returned object remains valid until acknowledge. */
    fun readyFor(generation: Int): ReadySet? {
        if (publishedGeneration != generation) return null
        val slotIndex = publishedSlotIndex
        if (slotIndex < 0) return null
        val ready = slots[slotIndex]

        return ready.takeIf { it.generation == generation }
    }

    /** Callback-safe after every old voice using the previous ready set has been stopped. */
    fun acknowledge(generation: Int) {
        acknowledgedGeneration.lazySet(generation)
        signalVersion.incrementAndGet()
        dispatcher.schedule(dispatcherIndex)
    }

    /** Callback-safe after the group's voices have been stopped. */
    fun deactivate() {
        requestSequence.incrementAndGet()
        requestedCount = 0
        callbackLastRequestedCount = 0
        callbackLastRequestedGeneration = requestSequence.incrementAndGet()
        signalVersion.incrementAndGet()
        dispatcher.schedule(dispatcherIndex)
    }

    fun consumeFailureCount(): Int = failures.getAndSet(0)

    internal val debugCurrentRegionCount: Int get() = currentRegionCount
    internal val debugTransitionRegionCount: Int get() = transitionRegionCount
    internal val debugPeakTransitionRegionCount: Int get() = peakTransitionRegionCount
    internal val workerSignalVersion: Int get() = signalVersion.get()

    internal fun markQueued(): Boolean = queued.compareAndSet(false, true)

    internal fun clearQueued() {
        queued.set(false)
    }

    /** Worker-only. Returns true when this group needs an immediate fair retry. */
    internal fun processWorkerRequest(): Boolean {
        if (stagingSlot >= 0) {
            val staging = slots[stagingSlot]
            when {
                acknowledgedGeneration.get() == staging.generation -> {
                    if (currentSlot >= 0) closeSlot(slots[currentSlot])
                    currentSlot = stagingSlot
                    stagingSlot = -1
                    currentRegionCount = slots[currentSlot].count
                    transitionRegionCount = currentRegionCount
                }
                stableRequestGeneration() != staging.generation -> {
                    closeSlot(staging)
                    stagingSlot = -1
                    publishCurrentSlot()
                    transitionRegionCount = if (currentSlot >= 0) slots[currentSlot].count else 0
                }
                else -> return false
            }
        }

        val desired = workerDesiredIndices
        val request = copyStableRequest(desired) ?: return false
        if (request.generation == processedGeneration) return false
        processedGeneration = request.generation
        if (request.count == 0) {
            if (currentSlot >= 0) closeSlot(slots[currentSlot])
            currentSlot = -1
            publishedSlotIndex = -1
            publishedGeneration = request.generation
            currentRegionCount = 0
            transitionRegionCount = 0
            return false
        }

        val nextSlot = if (currentSlot == 0) 1 else 0
        val staging = slots[nextSlot]
        closeSlot(staging)
        var prepared = false
        try {
            requireDistinctDesiredNodes(desired, request.count)
            var index = 0
            while (index < request.count) {
                staging.nodeIndices[index] = desired[index]
                staging.regions[index] = regionFactory.map(nodes[desired[index]], prewarm = true)
                staging.count = index + 1
                transitionRegionCount = (if (currentSlot >= 0) slots[currentSlot].count else 0) + staging.count
                peakTransitionRegionCount = maxOf(peakTransitionRegionCount, transitionRegionCount)
                if (stableRequestGeneration() != request.generation) break
                index += 1
            }
            prepared = staging.count == request.count && stableRequestGeneration() == request.generation
        } catch (_: Throwable) {
            failures.incrementAndGet()
        }
        if (!prepared) {
            closeSlot(staging)
            transitionRegionCount = if (currentSlot >= 0) slots[currentSlot].count else 0
            return false
        }

        staging.generation = request.generation
        stagingSlot = nextSlot
        publishedSlotIndex = nextSlot
        publishedGeneration = request.generation
        return false
    }

    /** Expensive defensive validation belongs to the worker, not the realtime callback. */
    private fun requireDistinctDesiredNodes(desired: IntArray, count: Int) {
        var index = 0
        while (index < count) {
            var prior = 0
            while (prior < index) {
                require(desired[prior] != desired[index]) { "Continuous atlas hot set contains duplicate nodes" }
                prior += 1
            }
            index += 1
        }
    }

    internal fun closeOffCallback() {
        closeSlot(slots[0])
        closeSlot(slots[1])
        currentSlot = -1
        stagingSlot = -1
        publishedSlotIndex = -1
        currentRegionCount = 0
        transitionRegionCount = 0
    }

    private val workerDesiredIndices = IntArray(maximumCurrentCorners)

    private fun copyStableRequest(destination: IntArray): Request? {
        var attempt = 0
        while (attempt < MAXIMUM_SNAPSHOT_ATTEMPTS) {
            val before = requestSequence.get()
            if (before and 1 != 0) return null
            val count = requestedCount
            var index = 0
            while (index < count) {
                destination[index] = requestedIndices[index]
                index += 1
            }
            val after = requestSequence.get()
            if (before == after) return Request(after, count)
            attempt += 1
        }

        return null
    }

    private fun stableRequestGeneration(): Int {
        val before = requestSequence.get()
        if (before and 1 != 0) return Int.MIN_VALUE

        return if (before == requestSequence.get()) before else Int.MIN_VALUE
    }

    private fun publishCurrentSlot() {
        publishedSlotIndex = currentSlot
        publishedGeneration = if (currentSlot >= 0) slots[currentSlot].generation else 0
    }

    private fun closeSlot(slot: ReadySet) {
        var index = 0
        while (index < slot.count) {
            try {
                slot.regions[index]?.close()
            } catch (_: Throwable) {
                failures.incrementAndGet()
            }
            slot.regions[index] = null
            index += 1
        }
        slot.count = 0
        slot.generation = 0
    }

    private data class Request(val generation: Int, val count: Int)

    private companion object {
        const val MAXIMUM_SNAPSHOT_ATTEMPTS = 3
    }
}
