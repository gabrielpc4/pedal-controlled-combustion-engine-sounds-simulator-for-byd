package com.gabrielpc.enginesoundsimulator.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal class AtlasCellSelection {
    var lowerRpmIndex: Int = 0
    var upperRpmIndex: Int = 0
    var lowerThrottleIndex: Int = 0
    var upperThrottleIndex: Int = 0

    fun key(): Long = AtlasCellSelector.key(
        lowerRpmIndex,
        upperRpmIndex,
        lowerThrottleIndex,
        upperThrottleIndex,
    )
}

internal object AtlasCellSelector {
    fun select(
        rpmAxis: DoubleArray,
        throttleAxis: DoubleArray,
        rpm: Double,
        throttle: Double,
        destination: AtlasCellSelection,
    ) {
        destination.lowerRpmIndex = lowerIndex(rpmAxis, rpm)
        destination.upperRpmIndex = upperIndex(rpmAxis, destination.lowerRpmIndex, rpm)
        destination.lowerThrottleIndex = lowerIndex(throttleAxis, throttle)
        destination.upperThrottleIndex = upperIndex(throttleAxis, destination.lowerThrottleIndex, throttle)
    }

    fun key(r0: Int, r1: Int, t0: Int, t1: Int): Long {
        require(r0 in 0..MAX_AXIS_INDEX && r1 in 0..MAX_AXIS_INDEX)
        require(t0 in 0..MAX_AXIS_INDEX && t1 in 0..MAX_AXIS_INDEX)
        return (r0.toLong() shl 48) or (r1.toLong() shl 32) or (t0.toLong() shl 16) or t1.toLong()
    }

    fun decode(key: Long, destination: AtlasCellSelection) {
        destination.lowerRpmIndex = ((key ushr 48) and AXIS_MASK).toInt()
        destination.upperRpmIndex = ((key ushr 32) and AXIS_MASK).toInt()
        destination.lowerThrottleIndex = ((key ushr 16) and AXIS_MASK).toInt()
        destination.upperThrottleIndex = (key and AXIS_MASK).toInt()
    }

    private fun lowerIndex(axis: DoubleArray, rawValue: Double): Int {
        val value = rawValue.coerceIn(axis.first(), axis.last())
        var low = 0
        var high = axis.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (axis[middle] <= value) low = middle else high = middle - 1
        }
        return low
    }

    private fun upperIndex(axis: DoubleArray, lower: Int, rawValue: Double): Int = when {
        rawValue <= axis.first() -> 0
        rawValue >= axis.last() -> axis.lastIndex
        lower >= axis.lastIndex -> axis.lastIndex
        else -> lower + 1
    }

    private const val MAX_AXIS_INDEX = 0xffff
    private const val AXIS_MASK = 0xffffL
}

internal class HotAtlasCell(
    val key: Long,
    val lowerRpmIndex: Int,
    val upperRpmIndex: Int,
    val lowerThrottleIndex: Int,
    val upperThrottleIndex: Int,
    val lowerLower: AtlasNodeRegion,
    val upperLower: AtlasNodeRegion,
    val lowerUpper: AtlasNodeRegion,
    val upperUpper: AtlasNodeRegion,
) {
    private val readers = AtomicInteger(0)
    private val retired = AtomicBoolean(false)

    fun tryAcquire(): Boolean {
        if (retired.get()) return false
        readers.incrementAndGet()
        if (!retired.get()) return true
        readers.decrementAndGet()
        return false
    }

    fun release() {
        check(readers.decrementAndGet() >= 0) { "Atlas cell reader count became negative" }
    }

    fun retire() {
        retired.set(true)
    }

    fun isUnused(): Boolean = readers.get() == 0

    fun readerCount(): Int = readers.get()

    fun fillWeights(
        program: AtlasPerspectiveProgram,
        rpm: Double,
        throttle: Double,
        destination: DoubleArray,
    ) {
        require(destination.size >= 4)
        val rpmFraction = fraction(
            program.rpmAxis[lowerRpmIndex],
            program.rpmAxis[upperRpmIndex],
            rpm,
        )
        val throttleFraction = fraction(
            program.throttleAxis[lowerThrottleIndex],
            program.throttleAxis[upperThrottleIndex],
            throttle,
        )
        val lowerRpmWeight = 1.0 - rpmFraction
        val lowerThrottleWeight = 1.0 - throttleFraction
        destination[0] = lowerRpmWeight * lowerThrottleWeight
        destination[1] = rpmFraction * lowerThrottleWeight
        destination[2] = lowerRpmWeight * throttleFraction
        destination[3] = rpmFraction * throttleFraction
    }

    fun uniqueRegions(): List<AtlasNodeRegion> = listOf(
        lowerLower,
        upperLower,
        lowerUpper,
        upperUpper,
    ).distinctBy { it.nodeIndex }

    fun advance(targetRpm: Double) {
        lowerLower.advance(playbackRatio(targetRpm, lowerLower.nodeRpm))
        if (upperLower !== lowerLower) {
            upperLower.advance(playbackRatio(targetRpm, upperLower.nodeRpm))
        }
        if (lowerUpper !== lowerLower && lowerUpper !== upperLower) {
            lowerUpper.advance(playbackRatio(targetRpm, lowerUpper.nodeRpm))
        }
        if (upperUpper !== lowerLower && upperUpper !== upperLower && upperUpper !== lowerUpper) {
            upperUpper.advance(playbackRatio(targetRpm, upperUpper.nodeRpm))
        }
    }

    private fun playbackRatio(targetRpm: Double, nodeRpm: Double): Double =
        if (nodeRpm <= 0.0) 1.0 else (targetRpm / nodeRpm).coerceIn(0.10, 4.0)

    private fun fraction(lower: Double, upper: Double, value: Double): Double {
        if (lower == upper) return 0.0
        return ((value.coerceIn(lower, upper) - lower) / (upper - lower)).coerceIn(0.0, 1.0)
    }
}

/** One background mapper; the audio thread exchanges only primitive keys and ready snapshots. */
internal class AtlasHotCellLoader(
    private val program: AtlasPerspectiveProgram,
    private val factory: AtlasNodeRegionFactory,
    initialRpm: Double,
    initialThrottle: Double,
    initialEngineProgram: AtlasEngineProgram = AtlasEngineProgram.FULL,
    private val outputHistory: AtlasOutputHistory = AtlasOutputHistory(),
    private val initialLoadTimeoutMillis: Long = INITIAL_LOAD_TIMEOUT_MS,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val requestedKey: AtomicLong
    private val published = AtomicReference<HotAtlasCell?>(null)
    private val failure = AtomicReference<Throwable?>(null)
    private val initialReady = CountDownLatch(1)
    private val worker: Thread
    private val correlationLeft = DoubleArray(AtlasOutputHistory.CAPACITY_FRAMES)
    private val correlationRight = DoubleArray(AtlasOutputHistory.CAPACITY_FRAMES)
    private val coldWeights = DoubleArray(4)
    private val coldCornerOrder = intArrayOf(0, 2, 1, 3)
    private val retirements = java.util.ArrayDeque<RetiredAtlasCell>()

    @Volatile
    private var requestedTargetRpm = initialRpm

    @Volatile
    private var requestedTargetThrottle = initialThrottle

    @Volatile
    private var requestedEngineProgram = initialEngineProgram

    init {
        require(initialLoadTimeoutMillis > 0L) { "Initial atlas load timeout must be positive" }
        val initial = AtlasCellSelection()
        AtlasCellSelector.select(program.rpmAxis, program.throttleAxis, initialRpm, initialThrottle, initial)
        requestedKey = AtomicLong(initial.key())
        worker = Thread(::runWorker, "atlas-cell-prefetch").apply {
            isDaemon = true
            start()
        }
        try {
            check(initialReady.await(initialLoadTimeoutMillis, TimeUnit.MILLISECONDS)) {
                "Timed out mapping the first atlas cell"
            }
            failure.get()?.let { throw it }
            checkNotNull(published.get()) { "First atlas cell was not mapped" }
        } catch (error: Throwable) {
            running.set(false)
            worker.interrupt()
            joinWorkerBeforeResourceRelease()
            published.getAndSet(null)?.uniqueRegions()?.forEach(AtlasNodeRegion::close)
            factory.close()
            throw error
        }
    }

    fun request(
        key: Long,
        targetRpm: Double,
        targetThrottle: Double,
        engineProgram: AtlasEngineProgram,
    ) {
        requestedTargetRpm = targetRpm
        requestedTargetThrottle = targetThrottle
        requestedEngineProgram = engineProgram
        requestedKey.lazySet(key)
    }

    fun acquireCurrentOrThrow(): HotAtlasCell {
        while (true) {
            failure.get()?.let { throw it }
            val cell = checkNotNull(published.get()) { "Atlas cell is not ready" }
            if (!cell.tryAcquire()) continue
            if (published.get() === cell) return cell
            cell.release()
        }
    }

    fun release(cell: HotAtlasCell) {
        cell.release()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.interrupt()
        joinWorkerBeforeResourceRelease()
        val current = published.getAndSet(null)
        current?.retire()
        check(current == null || current.isUnused()) { "Atlas renderer closed with an active audio reader" }
        while (retirements.isNotEmpty()) {
            closeRetirement(retirements.removeFirst())
        }
        current?.uniqueRegions()?.forEach(AtlasNodeRegion::close)
        factory.close()
    }

    private fun joinWorkerBeforeResourceRelease() {
        if (worker === Thread.currentThread()) return
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun runWorker() {
        var loadedKey = Long.MIN_VALUE
        try {
            while (running.get()) {
                val wanted = requestedKey.get()
                if (wanted != loadedKey) {
                    val next = mapCell(
                        wanted,
                        published.get(),
                        requestedTargetRpm,
                        requestedTargetThrottle,
                        requestedEngineProgram,
                    )
                    val previous = published.getAndSet(next)
                    if (previous != null) {
                        previous.retire()
                        retirements.addLast(RetiredAtlasCell(previous, next))
                    }
                    loadedKey = wanted
                    initialReady.countDown()
                    reapRetirements()
                    continue
                }
                reapRetirements()
                LockSupport.parkNanos(IDLE_PARK_NANOS)
            }
        } catch (error: Throwable) {
            if (running.get()) failure.compareAndSet(null, error)
            initialReady.countDown()
        }
    }

    private fun mapCell(
        key: Long,
        previous: HotAtlasCell?,
        targetRpm: Double,
        targetThrottle: Double,
        engineProgram: AtlasEngineProgram,
    ): HotAtlasCell {
        val coordinates = AtlasCellSelection()
        AtlasCellSelector.decode(key, coordinates)
        val previousByIndex = previous?.uniqueRegions()?.associateBy { it.nodeIndex }.orEmpty()
        val mappedByIndex = linkedMapOf<Int, AtlasNodeRegion>()

        val historyFrames = outputHistory.copyChronologicalTo(correlationLeft, correlationRight)

        fun region(rpmIndex: Int, throttleIndex: Int): AtlasNodeRegion {
            val nodeIndex = program.nodeIndex(rpmIndex, throttleIndex)
            return mappedByIndex.getOrPut(nodeIndex) {
                previousByIndex[nodeIndex] ?: factory.map(nodeIndex, program.nodes[nodeIndex]).also { mapped ->
                    mapped.alignToHistory(
                        correlationLeft,
                        correlationRight,
                        historyFrames,
                        targetRpm,
                        engineProgram,
                        continueAfterHistory = true,
                    )
                }
            }
        }

        val cell = HotAtlasCell(
            key = key,
            lowerRpmIndex = coordinates.lowerRpmIndex,
            upperRpmIndex = coordinates.upperRpmIndex,
            lowerThrottleIndex = coordinates.lowerThrottleIndex,
            upperThrottleIndex = coordinates.upperThrottleIndex,
            lowerLower = region(coordinates.lowerRpmIndex, coordinates.lowerThrottleIndex),
            upperLower = region(coordinates.upperRpmIndex, coordinates.lowerThrottleIndex),
            lowerUpper = region(coordinates.lowerRpmIndex, coordinates.upperThrottleIndex),
            upperUpper = region(coordinates.upperRpmIndex, coordinates.upperThrottleIndex),
        )
        if (previous == null) alignColdStart(cell, targetRpm, targetThrottle, engineProgram)
        return cell
    }

    private fun alignColdStart(
        cell: HotAtlasCell,
        targetRpm: Double,
        targetThrottle: Double,
        engineProgram: AtlasEngineProgram,
    ) {
        cell.fillWeights(program, targetRpm, targetThrottle, coldWeights)
        val regions = arrayOf(cell.lowerLower, cell.upperLower, cell.lowerUpper, cell.upperUpper)
        sortColdCornersByWeight()
        val referenceIndex = coldCornerOrder[0]
        val reference = regions[referenceIndex]
        reference.mixNextFramesInto(
            correlationLeft,
            correlationRight,
            AtlasOutputHistory.CAPACITY_FRAMES,
            targetRpm,
            engineProgram,
            coldWeights[referenceIndex],
            clearDestination = true,
        )
        var orderIndex = 0
        while (orderIndex < coldCornerOrder.size) {
            val index = coldCornerOrder[orderIndex]
            val region = regions[index]
            var duplicateOfPrior = false
            var priorOrderIndex = 0
            while (priorOrderIndex < orderIndex) {
                if (regions[coldCornerOrder[priorOrderIndex]] === region) {
                    duplicateOfPrior = true
                    break
                }
                priorOrderIndex += 1
            }
            if (index != referenceIndex && !duplicateOfPrior) {
                region.alignToHistory(
                    correlationLeft,
                    correlationRight,
                    AtlasOutputHistory.CAPACITY_FRAMES,
                    targetRpm,
                    engineProgram,
                    continueAfterHistory = false,
                )
                if (coldWeights[index] > 0.0) {
                    region.mixNextFramesInto(
                        correlationLeft,
                        correlationRight,
                        AtlasOutputHistory.CAPACITY_FRAMES,
                        targetRpm,
                        engineProgram,
                        coldWeights[index],
                        clearDestination = false,
                    )
                }
            }
            orderIndex += 1
        }
    }

    /** Oracle order: greatest raw weight, then lower RPM, then lower throttle. */
    private fun sortColdCornersByWeight() {
        coldCornerOrder[0] = 0
        coldCornerOrder[1] = 2
        coldCornerOrder[2] = 1
        coldCornerOrder[3] = 3
        var index = 1
        while (index < coldCornerOrder.size) {
            val value = coldCornerOrder[index]
            var destination = index
            while (destination > 0 && coldWeights[value] > coldWeights[coldCornerOrder[destination - 1]]) {
                coldCornerOrder[destination] = coldCornerOrder[destination - 1]
                destination -= 1
            }
            coldCornerOrder[destination] = value
            index += 1
        }
    }

    private fun reapRetirements() {
        val iterator = retirements.iterator()
        while (iterator.hasNext()) {
            val retirement = iterator.next()
            if (retirement.previous.isUnused()) {
                closeRetirement(retirement)
                iterator.remove()
            }
        }
    }

    private fun closeRetirement(retirement: RetiredAtlasCell) {
        check(retirement.previous.isUnused()) {
            "Retired atlas cell ${retirement.previous.key} still has " +
                "${retirement.previous.readerCount()} readers"
        }
        val retained = retirement.next.uniqueRegions().mapTo(hashSetOf()) { it.nodeIndex }
        retirement.previous.uniqueRegions()
            .filterNot { it.nodeIndex in retained }
            .forEach(AtlasNodeRegion::close)
    }

    private data class RetiredAtlasCell(
        val previous: HotAtlasCell,
        val next: HotAtlasCell,
    )

    private companion object {
        const val INITIAL_LOAD_TIMEOUT_MS = 15_000L
        const val IDLE_PARK_NANOS = 1_000_000L
    }
}

/** Lock-free, allocation-free audio-writer history with one background snapshot reader. */
internal class AtlasOutputHistory {
    private val left = DoubleArray(CAPACITY_FRAMES)
    private val right = DoubleArray(CAPACITY_FRAMES)

    @Volatile
    private var sequence = 0

    @Volatile
    private var writeIndex = 0

    @Volatile
    private var count = 0

    fun beginBlock() {
        sequence += 1
    }

    fun append(leftSample: Double, rightSample: Double) {
        left[writeIndex] = leftSample
        right[writeIndex] = rightSample
        writeIndex = (writeIndex + 1) % CAPACITY_FRAMES
        if (count < CAPACITY_FRAMES) count += 1
    }

    fun endBlock() {
        sequence += 1
    }

    fun copyChronologicalTo(destinationLeft: DoubleArray, destinationRight: DoubleArray): Int {
        require(destinationLeft.size >= CAPACITY_FRAMES && destinationRight.size >= CAPACITY_FRAMES)
        while (true) {
            val before = sequence
            if (before and 1 != 0) {
                LockSupport.parkNanos(SNAPSHOT_RETRY_NANOS)
                continue
            }
            val copiedCount = count
            val copiedWriteIndex = writeIndex
            val start = if (copiedCount == CAPACITY_FRAMES) copiedWriteIndex else 0
            var index = 0
            while (index < copiedCount) {
                val sourceIndex = (start + index) % CAPACITY_FRAMES
                destinationLeft[index] = left[sourceIndex]
                destinationRight[index] = right[sourceIndex]
                index += 1
            }
            val after = sequence
            if (before == after && after and 1 == 0) return copiedCount
        }
    }

    companion object {
        const val CAPACITY_FRAMES = 960
        private const val SNAPSHOT_RETRY_NANOS = 100_000L
    }
}
