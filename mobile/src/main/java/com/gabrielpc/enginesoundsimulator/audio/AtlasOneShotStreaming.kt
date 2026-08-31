package com.gabrielpc.enginesoundsimulator.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

internal fun interface AtlasOneShotPcmTailReaderFactory {
    fun open(): AtlasOneShotPcmTailReader
}

internal interface AtlasOneShotPcmTailReader : AutoCloseable {
    fun sample(frame: Int, channel: Int): Short
}

/**
 * Prepared PCM for a finite atlas capture.
 *
 * `mappedPcm` is deliberately never read by the audio callback.  Preparing the source copies a
 * short attack into ordinary memory while the program is opened; the streaming worker is the only
 * code allowed to touch later mmap pages.  This keeps a cold multi-page backfire/gear capture from
 * turning into a page fault on AudioTrack's realtime thread without retaining the whole capture in
 * the Java heap.
 */
internal class AtlasOneShotPcmSource(
    mappedPcm: ByteBuffer,
    val frameCount: Int,
    attackCacheFrames: Int = DEFAULT_ATTACK_CACHE_FRAMES,
    private val tailReaderFactory: AtlasOneShotPcmTailReaderFactory = AtlasOneShotPcmTailReaderFactory {
        ByteBufferTailReader(mappedPcm, frameCount)
    },
) {
    private val attackSource = mappedPcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private val cachedFrames = minOf(frameCount, attackCacheFrames)
    private val attackPcm = ShortArray(cachedFrames * CHANNELS)
    private val backgroundReads = AtomicInteger(0)

    init {
        require(frameCount > 0) { "One-shot source has no PCM frames" }
        require(attackSource.remaining() >= cachedFrames * BYTES_PER_FRAME) { "One-shot attack PCM is truncated" }
        require(attackCacheFrames > 0) { "One-shot attack cache must be positive" }
        var sampleIndex = 0
        while (sampleIndex < attackPcm.size) {
            attackPcm[sampleIndex] = attackSource.getShort(sampleIndex * Short.SIZE_BYTES)
            sampleIndex += 1
        }
    }

    internal val attackFrameCount: Int get() = cachedFrames
    internal val debugBackgroundReadCount: Int get() = backgroundReads.get()

    /** Heap-only attack lookup used by the non-realtime materialization worker. */
    internal fun cachedSample(frame: Int, channel: Int): Short =
        attackPcm[frame * CHANNELS + channel.coerceIn(0, CHANNELS - 1)]

    /** Worker-only normalized lookup which deliberately does not clamp the later weighted mix. */
    internal fun cachedSampleNormalized(frame: Int, channel: Int): Double =
        cachedSample(frame, channel).toDouble() / Short.MAX_VALUE

    /** Opens one overlap-safe tail view only from [AtlasOneShotStreamPool]'s worker. */
    internal fun openTailReader(): AtlasOneShotPcmTailReader = tailReaderFactory.open()

    /** Called only by the streaming worker after its per-voice tail view is ready. */
    internal fun readTailSample(reader: AtlasOneShotPcmTailReader, frame: Int, channel: Int): Short {
        check(frame >= cachedFrames) { "Attack frames must come from the prepared cache" }
        backgroundReads.incrementAndGet()
        return reader.sample(frame, channel)
    }

    private class ByteBufferTailReader(mappedPcm: ByteBuffer, frameCount: Int) : AtlasOneShotPcmTailReader {
        private val bytes = mappedPcm.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

        init {
            require(bytes.remaining() >= frameCount * BYTES_PER_FRAME) { "One-shot PCM tail is truncated" }
        }

        override fun sample(frame: Int, channel: Int): Short =
            bytes.getShort((frame * CHANNELS + channel.coerceIn(0, CHANNELS - 1)) * Short.SIZE_BYTES)

        override fun close() = Unit
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * Short.SIZE_BYTES
        const val DEFAULT_ATTACK_CACHE_FRAMES = 4_096 // 85 ms at 48 kHz; bounded per source.
    }
}

/**
 * Fixed voice pool for finite effect PCM.  All buffers are allocated once during program setup.
 *
 * A caller obtains one [Voice] for every already allocated effect voice.  `begin`, `sample`, and
 * `advance` are callback-safe: they never lock, allocate, wait, map, or read a source ByteBuffer.
 * The sole producer is a low-priority worker which stays ahead of the consumer with a bounded
 * ring. The callback starts at frame zero directly from each contributor's pre-armed heap attack;
 * this is bounded by the compiled contributor count and never waits for worker scheduling. The
 * worker starts at the first frame not covered by every still-active attack cache and must fill the
 * ring before that boundary. If it does not, only the late tail is silenced and diagnosed.
 */
internal class AtlasOneShotStreamPool(
    voiceCount: Int,
    ringFramesPerVoice: Int = DEFAULT_RING_FRAMES,
    maximumContributorsPerVoice: Int = 1,
    startWorker: Boolean = true,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val materializationFailures = AtomicInteger(0)
    private val workerVisitCount = AtomicLong(0L)
    private val workerParkCount = AtomicLong(0L)
    private val pendingStarts = StartQueue(voiceCount)
    private val slots = Array(voiceCount.coerceAtLeast(1)) {
        Slot(ringFramesPerVoice, maximumContributorsPerVoice, materializationFailures)
    }
    @Volatile private var activeWorkerSlotCount = 0
    private val worker = Thread(::pump, "atlas-one-shot-prefetch").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        require(voiceCount > 0) { "One-shot voice count must be positive" }
        require(ringFramesPerVoice >= MINIMUM_RING_FRAMES) { "One-shot ring is too small" }
        require(maximumContributorsPerVoice > 0) { "One-shot contributor bound must be positive" }
        if (startWorker) worker.start()
    }

    /** Float32 stereo storage per logical finite instance; contributors never own rings. */
    internal val allocatedRingBytes: Long =
        slots.size.toLong() * ringFramesPerVoice * CHANNELS * Float.SIZE_BYTES
    internal val debugActiveWorkerSlots: Int get() = activeWorkerSlotCount
    internal val debugWorkerVisitCount: Long get() = workerVisitCount.get()
    internal val debugWorkerParkCount: Long get() = workerParkCount.get()

    fun voice(index: Int): Voice = Voice(index, slots[index], closed, pendingStarts, worker)

    /** Number of starts refused because a cancelled slot has not yet been retired by the worker. */
    fun consumeStartRejectedCount(): Int = slots.sumOf { it.consumeStartRejectedCount() }

    /** Number of callback frames that were silent because a worker buffer was unexpectedly late. */
    fun consumeUnderrunFrameCount(): Int = slots.sumOf { it.consumeUnderrunFrameCount() }

    /** Tail mapping/read failures detected off the callback; the failed logical voice is retired. */
    fun consumeMaterializationFailureCount(): Int = materializationFailures.getAndSet(0)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        slots.forEach(Slot::cancel)
        LockSupport.unpark(worker)
        var interrupted = false
        while (worker.isAlive) {
            try {
                // Closing happens off the realtime callback. Do not unmap a shard until the
                // only worker allowed to touch it has actually stopped, including if the closer
                // itself is interrupted.
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        slots.forEach(Slot::closeOffCallback)
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal class Voice internal constructor(
        private val slotIndex: Int,
        private val slot: Slot,
        private val poolClosed: AtomicBoolean,
        private val pendingStarts: StartQueue,
        private val worker: Thread,
    ) {
        /**
         * Begins a finite capture at frame zero.  A false result is a visible rejection, never a
         * synchronous fallback to mmap I/O.
         */
        fun begin(source: AtlasOneShotPcmSource): Boolean {
            if (poolClosed.get()) return false
            val generation = slot.begin(source)
            if (generation == START_REJECTED) return false
            pendingStarts.offer(slotIndex, generation)
            LockSupport.unpark(worker)

            return true
        }

        /**
         * Atomically copies one logical instance's contributor set into this preallocated slot.
         * The source/gain arrays belong to reusable scheduler scratch state and may be changed as
         * soon as this method returns.
         */
        fun begin(
            sources: Array<AtlasOneShotPcmSource?>,
            gains: DoubleArray,
            contributorCount: Int,
        ): Boolean {
            if (poolClosed.get()) return false
            val generation = slot.begin(sources, gains, contributorCount)
            if (generation == START_REJECTED) return false
            pendingStarts.offer(slotIndex, generation)
            LockSupport.unpark(worker)

            return true
        }

        /** Callback-safe unclipped normalized lookup. Call [advance] after both channels. */
        fun sample(channel: Int): Double = slot.sample(channel)

        fun advance() {
            if (slot.advance()) LockSupport.unpark(worker)
        }

        fun cancel() {
            if (slot.cancel()) LockSupport.unpark(worker)
        }

        val active: Boolean get() = slot.active
        val finished: Boolean get() = slot.finished
        /** True only while no worker can still write this voice's ring. */
        val readyForStart: Boolean get() = !poolClosed.get() && slot.readyForStart
        /** Frame zero is immediately available from the pre-armed heap attack cache. */
        val playbackReady: Boolean get() = slot.playbackReady
        internal val debugAttackPlaybackExclusive: Int get() = slot.attackPlaybackExclusive
        internal val debugProducedExclusive: Int get() = slot.producedFrameExclusive
    }

    private fun pump() {
        val activeIndices = IntArray(slots.size)
        val activeGenerations = IntArray(slots.size)
        val activePositionBySlot = IntArray(slots.size) { -1 }
        var activeCount = 0

        fun drainStarts() {
            while (pendingStarts.hasNext()) {
                val slotIndex = pendingStarts.nextSlotIndex()
                val generation = pendingStarts.nextGeneration()
                pendingStarts.remove()
                val existingPosition = activePositionBySlot[slotIndex]
                if (existingPosition >= 0) {
                    activeGenerations[existingPosition] = generation
                } else {
                    activeIndices[activeCount] = slotIndex
                    activeGenerations[activeCount] = generation
                    activePositionBySlot[slotIndex] = activeCount
                    activeCount += 1
                }
            }
            activeWorkerSlotCount = activeCount
        }

        try {
            while (!closed.get()) {
                drainStarts()
                var didWork = false
                var index = 0
                while (index < activeCount) {
                    val slotIndex = activeIndices[index]
                    workerVisitCount.incrementAndGet()
                    when (slots[slotIndex].prefetchOneChunk(activeGenerations[index])) {
                        PREFETCH_WORKED -> {
                            didWork = true
                            index += 1
                        }
                        PREFETCH_KEEP -> index += 1
                        PREFETCH_RETIRE -> {
                            activeCount -= 1
                            val movedSlotIndex = activeIndices[activeCount]
                            activeIndices[index] = movedSlotIndex
                            activeGenerations[index] = activeGenerations[activeCount]
                            activePositionBySlot[movedSlotIndex] = index
                            activePositionBySlot[slotIndex] = -1
                            activeWorkerSlotCount = activeCount
                        }
                    }
                }
                if (!didWork) {
                    workerParkCount.incrementAndGet()
                    LockSupport.park()
                }
            }
        } finally {
            slots.forEach(Slot::closeOffCallback)
            activeWorkerSlotCount = 0
        }
    }

    internal class Slot(
        ringFrames: Int,
        maximumContributors: Int,
        private val materializationFailures: AtomicInteger,
    ) {
        // One stereo Float32 frame per logical scheduling-group instance. Interpolation corners
        // are mixed into this ring by the worker and never allocate rings of their own.
        private val ring = FloatArray(ringFrames * CHANNELS)
        private val ringFrames = ringFrames
        private val workerWakeIntervalFrames = minOf(PREFETCH_CHUNK_FRAMES, maxOf(1, ringFrames / 2))
        private val sources = arrayOfNulls<AtlasOneShotPcmSource>(maximumContributors)
        private val tailReaders = arrayOfNulls<AtlasOneShotPcmTailReader>(maximumContributors)
        private val gains = DoubleArray(maximumContributors)
        private val state = AtomicInteger(IDLE)
        private val generation = AtomicInteger(0)
        private val rejectedStarts = AtomicInteger(0)
        private val underrunFrames = AtomicInteger(0)

        @Volatile private var contributorCount = 0
        @Volatile private var frameCount = 0
        @Volatile private var consumerFrame = 0
        @Volatile private var producedExclusive = 0
        @Volatile private var callbackFrame = 0
        @Volatile private var completed = false
        @Volatile private var directAttackExclusive = 0
        private var lastReportedUnderrunFrame = -1
        private var nextWorkerWakeConsumerFrame = workerWakeIntervalFrames

        val active: Boolean get() = state.get() == ACTIVE || state.get() == FAILED
        val finished: Boolean get() = completed
        val readyForStart: Boolean get() = state.get() == IDLE
        val playbackReady: Boolean get() = state.get() == ACTIVE
        val attackPlaybackExclusive: Int get() = directAttackExclusive
        val producedFrameExclusive: Int get() = producedExclusive

        fun begin(next: AtlasOneShotPcmSource): Int {
            if (!state.compareAndSet(IDLE, CONFIGURING)) {
                rejectedStarts.incrementAndGet()
                return START_REJECTED
            }
            sources[0] = next
            gains[0] = 1.0
            configure(1)
            val nextGeneration = generation.incrementAndGet()
            state.set(ACTIVE)

            return nextGeneration
        }

        fun begin(
            nextSources: Array<AtlasOneShotPcmSource?>,
            nextGains: DoubleArray,
            nextContributorCount: Int,
        ): Int {
            require(nextContributorCount in 1..sources.size) { "One-shot contributor count exceeds its bound" }
            require(nextSources.size >= nextContributorCount && nextGains.size >= nextContributorCount) {
                "One-shot contributor scratch is incomplete"
            }
            if (!state.compareAndSet(IDLE, CONFIGURING)) {
                rejectedStarts.incrementAndGet()
                return START_REJECTED
            }
            var index = 0
            try {
                while (index < nextContributorCount) {
                    val source = requireNotNull(nextSources[index]) { "One-shot contributor is missing" }
                    val gain = nextGains[index]
                    require(gain.isFinite()) { "One-shot contributor gain is invalid" }
                    sources[index] = source
                    gains[index] = gain
                    index += 1
                }
                while (index < sources.size) {
                    sources[index] = null
                    gains[index] = 0.0
                    index += 1
                }
                configure(nextContributorCount)
                val nextGeneration = generation.incrementAndGet()
                state.set(ACTIVE)
                return nextGeneration
            } catch (error: Throwable) {
                clearContributors()
                state.set(IDLE)
                throw error
            }
        }

        private fun configure(nextContributorCount: Int) {
            contributorCount = nextContributorCount
            var maximumFrames = 0
            var index = 0
            while (index < nextContributorCount) {
                val contributor = requireNotNull(sources[index])
                maximumFrames = maxOf(maximumFrames, contributor.frameCount)
                index += 1
            }
            frameCount = maximumFrames
            var firstUncachedFrame = maximumFrames
            index = 0
            while (index < nextContributorCount) {
                val contributor = requireNotNull(sources[index])
                if (contributor.attackFrameCount < contributor.frameCount) {
                    firstUncachedFrame = minOf(firstUncachedFrame, contributor.attackFrameCount)
                }
                index += 1
            }
            directAttackExclusive = firstUncachedFrame
            // The ring starts at the first frame not guaranteed by every still-active cache. Treat
            // that boundary as already consumed so the worker may fill a complete ring immediately
            // while the callback is playing the heap-only attack.
            consumerFrame = directAttackExclusive
            callbackFrame = 0
            producedExclusive = directAttackExclusive
            completed = false
            lastReportedUnderrunFrame = -1
            nextWorkerWakeConsumerFrame = directAttackExclusive + workerWakeIntervalFrames
        }

        fun sample(channel: Int): Double {
            val current = callbackFrame
            if (current >= frameCount) return 0.0
            val currentState = state.get()
            if (currentState != ACTIVE && currentState != FAILED) {
                reportUnderrun(current)
                return 0.0
            }
            if (current < directAttackExclusive) {
                return mixCachedAttackFrame(current, channel.coerceIn(0, 1))
            }
            // Volatile published end makes the preceding ring write visible to this callback.
            if (current < producedExclusive) {
                return ring[(current % ringFrames) * CHANNELS + channel.coerceIn(0, 1)].toDouble()
            }
            reportUnderrun(current)

            return 0.0
        }

        private fun reportUnderrun(frame: Int) {
            if (lastReportedUnderrunFrame != frame) {
                lastReportedUnderrunFrame = frame
                underrunFrames.incrementAndGet()
            }
        }

        fun advance(): Boolean {
            val currentState = state.get()
            if (currentState != ACTIVE && currentState != FAILED) return false
            val next = callbackFrame + 1
            callbackFrame = next
            consumerFrame = maxOf(next, directAttackExclusive)
            if (next >= frameCount) {
                completed = true
                // The producer might be filling its final chunk; it retires this slot lock-free.
                if (!state.compareAndSet(ACTIVE, CANCELLING)) {
                    state.compareAndSet(FAILED, CANCELLING)
                }
                return true
            }

            if (next >= nextWorkerWakeConsumerFrame) {
                nextWorkerWakeConsumerFrame += workerWakeIntervalFrames
                return true
            }

            return false
        }

        fun cancel(): Boolean {
            val cancelled = state.compareAndSet(ACTIVE, CANCELLING) ||
                state.compareAndSet(FAILED, CANCELLING)
            if (cancelled) completed = true

            return cancelled
        }

        fun prefetchOneChunk(expectedGeneration: Int): Int {
            return try {
                prefetchOneChunkOrThrow(expectedGeneration)
            } catch (_: Throwable) {
                materializationFailures.incrementAndGet()
                state.compareAndSet(ACTIVE, FAILED)
                PREFETCH_KEEP
            }
        }

        private fun prefetchOneChunkOrThrow(expectedGeneration: Int): Int {
            if (generation.get() != expectedGeneration) return PREFETCH_RETIRE
            when (state.get()) {
                IDLE -> return PREFETCH_RETIRE
                CANCELLING -> {
                    clearContributors()
                    state.compareAndSet(CANCELLING, IDLE)
                    return PREFETCH_RETIRE
                }
                ACTIVE -> Unit
                FAILED -> return PREFETCH_KEEP
                else -> return PREFETCH_KEEP
            }
            val readAt = producedExclusive
            if (readAt >= frameCount) return PREFETCH_KEEP
            val consumed = consumerFrame
            if (readAt - consumed >= ringFrames) return PREFETCH_KEEP
            val count = minOf(PREFETCH_CHUNK_FRAMES, frameCount - readAt, ringFrames - (readAt - consumed))
            var frameOffset = 0
            while (frameOffset < count) {
                val frame = readAt + frameOffset
                val ringBase = (frame % ringFrames) * CHANNELS
                ring[ringBase] = mixWorkerFrame(frame, 0).toFloat()
                ring[ringBase + 1] = mixWorkerFrame(frame, 1).toFloat()
                frameOffset += 1
            }
            // This write is release-like via volatile and happens after every ring sample is ready.
            producedExclusive = readAt + count
            return PREFETCH_WORKED
        }

        private fun mixCachedAttackFrame(frame: Int, channel: Int): Double {
            var mixed = 0.0
            var index = 0
            while (index < contributorCount) {
                val contributor = requireNotNull(sources[index])
                if (frame < contributor.frameCount) {
                    check(frame < contributor.attackFrameCount) {
                        "Finite attack boundary was derived inconsistently"
                    }
                    mixed += contributor.cachedSampleNormalized(frame, channel) * gains[index]
                }
                index += 1
            }

            return mixed
        }

        private fun mixWorkerFrame(frame: Int, channel: Int): Double {
            var mixed = 0.0
            var index = 0
            while (index < contributorCount) {
                val contributor = requireNotNull(sources[index])
                if (frame < contributor.frameCount) {
                    val sample = if (frame < contributor.attackFrameCount) {
                        contributor.cachedSampleNormalized(frame, channel)
                    } else {
                        val reader = tailReaders[index] ?: contributor.openTailReader().also { opened ->
                            tailReaders[index] = opened
                        }
                        contributor.readTailSample(reader, frame, channel).toDouble() / Short.MAX_VALUE
                    }
                    mixed += sample * gains[index]
                }
                index += 1
            }

            return mixed
        }

        private fun clearContributors() {
            var index = 0
            while (index < contributorCount) {
                try {
                    tailReaders[index]?.close()
                } catch (_: Throwable) {
                    materializationFailures.incrementAndGet()
                }
                tailReaders[index] = null
                sources[index] = null
                gains[index] = 0.0
                index += 1
            }
            contributorCount = 0
            frameCount = 0
            directAttackExclusive = 0
        }

        fun closeOffCallback() {
            clearContributors()
            completed = true
            state.set(IDLE)
        }

        fun consumeStartRejectedCount(): Int = rejectedStarts.getAndSet(0)
        fun consumeUnderrunFrameCount(): Int = underrunFrames.getAndSet(0)
    }

    private companion object {
        const val CHANNELS = 2
        const val IDLE = 0
        const val CONFIGURING = 1
        const val ACTIVE = 3
        const val CANCELLING = 4
        const val FAILED = 5
        const val START_REJECTED = Int.MIN_VALUE
        const val PREFETCH_KEEP = 0
        const val PREFETCH_WORKED = 1
        const val PREFETCH_RETIRE = 2
        const val MINIMUM_RING_FRAMES = 256
        const val DEFAULT_RING_FRAMES = 12_288 // 256 ms at 48 kHz, 96 KiB/voice.
        const val PREFETCH_CHUNK_FRAMES = 1_024
    }

    /** Single-producer/single-consumer start notifications backed only by fixed primitive arrays. */
    internal class StartQueue(capacity: Int) {
        private val slotIndices = IntArray(capacity)
        private val generations = IntArray(capacity)
        private val writeSequence = AtomicLong(0L)

        @Volatile
        private var readSequence = 0L

        fun offer(slotIndex: Int, generation: Int) {
            val write = writeSequence.get()
            check(write - readSequence < slotIndices.size) { "One-shot start queue exceeded the slot bound" }
            val position = (write % slotIndices.size).toInt()
            slotIndices[position] = slotIndex
            generations[position] = generation
            writeSequence.lazySet(write + 1L)
        }

        fun hasNext(): Boolean = readSequence < writeSequence.get()

        fun nextSlotIndex(): Int = slotIndices[(readSequence % slotIndices.size).toInt()]

        fun nextGeneration(): Int = generations[(readSequence % generations.size).toInt()]

        fun remove() {
            readSequence += 1L
        }
    }
}
