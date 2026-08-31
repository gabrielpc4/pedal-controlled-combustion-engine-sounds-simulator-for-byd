package com.gabrielpc.enginesoundsimulator.audio

import java.io.File
import java.io.RandomAccessFile
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

internal fun interface AtlasShardFileResolver {
    fun fileFor(shardName: String): File
}

internal interface AtlasNodeRegion : AutoCloseable {
    val nodeIndex: Int
    val nodeRpm: Double
    val frameCount: Int

    fun sampleAt(program: AtlasEngineProgram, channel: Int): Double

    fun advance(playbackRatio: Double)

    fun alignToHistory(
        historyLeft: DoubleArray,
        historyRight: DoubleArray,
        historyFrames: Int,
        targetRpm: Double,
        program: AtlasEngineProgram,
        continueAfterHistory: Boolean,
    ) = Unit

    fun mixNextFramesInto(
        destinationLeft: DoubleArray,
        destinationRight: DoubleArray,
        frameCount: Int,
        targetRpm: Double,
        program: AtlasEngineProgram,
        gain: Double,
        clearDestination: Boolean,
    ) = Unit

    override fun close()
}

internal fun interface AtlasNodeRegionFactory : AutoCloseable {
    fun map(nodeIndex: Int, node: AtlasEngineNode): AtlasNodeRegion

    override fun close() = Unit
}

internal data class AtlasWavLayout(
    val dataOffsetBytes: Long,
    val dataBytes: Long,
    val frameCount: Long,
)

/** Opens and validates shard headers during renderer setup, never from realtime rendering. */
internal class MappedAtlasNodeRegionFactory(
    program: AtlasPerspectiveProgram,
    private val fileResolver: AtlasShardFileResolver,
    private val maximumMappedShardsDuringCellTransition: Int,
) : AtlasNodeRegionFactory {
    init {
        require(maximumMappedShardsDuringCellTransition > 0) { "Atlas engine mmap shard bound is invalid" }
    }
    private val layoutsByShard: Map<String, AtlasWavLayout> = program.nodes
        .flatMap { node ->
            listOf(
                node.shardName,
                node.modePrograms.loadOnly.shardName,
                node.modePrograms.coastOnly.shardName,
            )
        }
        .distinct()
        .associateWith { shardName -> inspectCanonicalAtlasWav(fileResolver.fileFor(shardName)) }
    private val mappedShards = linkedMapOf<String, MappedAtlasShard>()

    @Synchronized
    override fun map(nodeIndex: Int, node: AtlasEngineNode): AtlasNodeRegion {
        val layout = requireNotNull(layoutsByShard[node.shardName])
        require(node.endFrameExclusive <= layout.frameCount) { "Atlas node exceeds ${node.shardName}" }
        require(node.modePrograms.loadOnly.endFrameExclusive <= layout.frameCount) {
            "Atlas LOAD_ONLY program exceeds ${node.shardName}"
        }
        require(node.modePrograms.coastOnly.endFrameExclusive <= layout.frameCount) {
            "Atlas COAST_ONLY program exceeds ${node.shardName}"
        }
        val byteCount = Math.multiplyExact(node.frameCount, BYTES_PER_FRAME.toLong())
        require(byteCount in 1..Int.MAX_VALUE.toLong()) { "Atlas node is too large to map" }
        val shard = mappedShards[node.shardName] ?: run {
            require(mappedShards.size < maximumMappedShardsDuringCellTransition) {
                "Atlas engine mapping exceeds the pack's proven cell-transition shard bound"
            }
            MappedAtlasShard.open(
                fileResolver.fileFor(node.shardName),
                layout.dataOffsetBytes,
                layout.dataBytes,
            ).also { mappedShards[node.shardName] = it }
        }
        shard.retain()
        val fullView = shard.readOnlySlice(node.startFrame * BYTES_PER_FRAME, byteCount)
        val loadOnlyView = shard.readOnlySlice(
            node.modePrograms.loadOnly.startFrame * BYTES_PER_FRAME,
            byteCount,
        )
        val coastOnlyView = shard.readOnlySlice(
            node.modePrograms.coastOnly.startFrame * BYTES_PER_FRAME,
            byteCount,
        )
        warmMappedPages(fullView)
        warmMappedPages(loadOnlyView)
        warmMappedPages(coastOnlyView)

        return MappedAtlasNodeRegion(
            nodeIndex = nodeIndex,
            nodeRpm = node.rpm,
            fullBytes = fullView,
            loadOnlyBytes = loadOnlyView,
            coastOnlyBytes = coastOnlyView,
            frameCount = node.frameCount.toInt(),
            loopStartFrame = ((node.loopStartFrame ?: node.startFrame) - node.startFrame).toInt(),
            loopEndFrameExclusive = ((node.loopEndFrameExclusive ?: node.endFrameExclusive) - node.startFrame).toInt(),
            phaseOffsetFrames = node.phaseOffsetFrames,
            releaseMapping = { releaseShard(node.shardName, shard) },
        )
    }

    @Synchronized
    override fun close() {
        mappedShards.values.forEach(MappedAtlasShard::forceClose)
        mappedShards.clear()
    }

    @Synchronized
    private fun releaseShard(name: String, shard: MappedAtlasShard) {
        if (shard.release()) {
            mappedShards.remove(name, shard)
        }
    }

    private class MappedAtlasNodeRegion(
        override val nodeIndex: Int,
        override val nodeRpm: Double,
        private val fullBytes: ByteBuffer,
        private val loadOnlyBytes: ByteBuffer,
        private val coastOnlyBytes: ByteBuffer,
        override val frameCount: Int,
        private val loopStartFrame: Int,
        private val loopEndFrameExclusive: Int,
        private val phaseOffsetFrames: Double,
        private val releaseMapping: () -> Unit,
    ) : AtlasNodeRegion {
        private var phase = wrapPhase(loopStartFrame + phaseOffsetFrames)
        private var closed = false

        init {
            require(loopStartFrame in 0 until loopEndFrameExclusive)
            require(loopEndFrameExclusive <= frameCount)
        }

        override fun sampleAt(program: AtlasEngineProgram, channel: Int): Double = sampleAt(
            bytesFor(program),
            channel,
        )

        private fun sampleAt(bytes: ByteBuffer, channel: Int): Double {
            val frame = phase.toInt()
            val fraction = phase - frame
            val y0 = pcm(bytes, channel, resolveFrame(frame - 1))
            val y1 = pcm(bytes, channel, resolveFrame(frame))
            val y2 = pcm(bytes, channel, resolveFrame(frame + 1))
            val y3 = pcm(bytes, channel, resolveFrame(frame + 2))
            return atlasCubicSample(y0, y1, y2, y3, fraction)
        }

        override fun advance(playbackRatio: Double) {
            phase += playbackRatio.coerceIn(MINIMUM_PLAYBACK_RATIO, MAXIMUM_PLAYBACK_RATIO)
            if (phase >= loopEndFrameExclusive) {
                val loopLength = loopEndFrameExclusive - loopStartFrame
                phase = loopStartFrame + (phase - loopEndFrameExclusive) % loopLength
            }
        }

        override fun alignToHistory(
            historyLeft: DoubleArray,
            historyRight: DoubleArray,
            historyFrames: Int,
            targetRpm: Double,
            program: AtlasEngineProgram,
            continueAfterHistory: Boolean,
        ) {
            if (historyFrames < CORRELATION_WINDOW_FRAMES) return
            val ratio = if (nodeRpm <= 0.0) 1.0 else {
                (targetRpm / nodeRpm).coerceIn(MINIMUM_PLAYBACK_RATIO, MAXIMUM_PLAYBACK_RATIO)
            }
            val coarseOffset = bestCorrelationOffset(
                minimumOffset = -CORRELATION_SEARCH_FRAMES,
                maximumOffset = CORRELATION_SEARCH_FRAMES,
                offsetStride = COARSE_OFFSET_STRIDE_FRAMES,
                referenceFrameStride = COARSE_REFERENCE_FRAME_STRIDE,
                ratio = ratio,
                program = program,
                historyLeft = historyLeft,
                historyRight = historyRight,
            ) ?: return
            val bestOffset = bestCorrelationOffset(
                minimumOffset = maxOf(-CORRELATION_SEARCH_FRAMES, coarseOffset - FINE_SEARCH_HALF_WIDTH_FRAMES),
                maximumOffset = minOf(CORRELATION_SEARCH_FRAMES, coarseOffset + FINE_SEARCH_HALF_WIDTH_FRAMES),
                offsetStride = 1,
                referenceFrameStride = 1,
                ratio = ratio,
                program = program,
                historyLeft = historyLeft,
                historyRight = historyRight,
            ) ?: coarseOffset
            val continuation = if (continueAfterHistory) CORRELATION_WINDOW_FRAMES else 0
            phase = wrapPhase(loopStartFrame + phaseOffsetFrames + bestOffset + continuation * ratio)
        }

        override fun mixNextFramesInto(
            destinationLeft: DoubleArray,
            destinationRight: DoubleArray,
            frameCount: Int,
            targetRpm: Double,
            program: AtlasEngineProgram,
            gain: Double,
            clearDestination: Boolean,
        ) {
            require(frameCount <= destinationLeft.size && frameCount <= destinationRight.size)
            val ratio = if (nodeRpm <= 0.0) 1.0 else {
                (targetRpm / nodeRpm).coerceIn(MINIMUM_PLAYBACK_RATIO, MAXIMUM_PLAYBACK_RATIO)
            }
            val bytes = bytesFor(program)
            var candidatePhase = phase
            var frame = 0
            while (frame < frameCount) {
                val floor = kotlin.math.floor(candidatePhase)
                val base = floor.toInt()
                val fraction = candidatePhase - floor
                val left = interpolateAt(bytes, 0, base, fraction) * gain
                val right = interpolateAt(bytes, 1, base, fraction) * gain
                if (clearDestination) {
                    destinationLeft[frame] = left
                    destinationRight[frame] = right
                } else {
                    destinationLeft[frame] += left
                    destinationRight[frame] += right
                }
                candidatePhase += ratio
                frame += 1
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            releaseMapping()
        }

        private fun pcm(bytes: ByteBuffer, channel: Int, frame: Int): Double {
            val byteIndex = (frame * CHANNELS + channel.coerceIn(0, CHANNELS - 1)) * Short.SIZE_BYTES
            return bytes.getShort(byteIndex).toDouble() / Short.MAX_VALUE
        }

        private fun resolveFrame(frame: Int): Int {
            val loopLength = loopEndFrameExclusive - loopStartFrame
            return when {
                frame >= loopEndFrameExclusive -> loopStartFrame + (frame - loopEndFrameExclusive) % loopLength
                frame < loopStartFrame -> loopEndFrameExclusive - 1 - ((loopStartFrame - 1 - frame) % loopLength)
                else -> frame
            }
        }

        private fun bestCorrelationOffset(
            minimumOffset: Int,
            maximumOffset: Int,
            offsetStride: Int,
            referenceFrameStride: Int,
            ratio: Double,
            program: AtlasEngineProgram,
            historyLeft: DoubleArray,
            historyRight: DoubleArray,
        ): Int? {
            var bestScore = Double.NEGATIVE_INFINITY
            var bestOffset = 0
            var offset = minimumOffset
            while (offset <= maximumOffset) {
                val score = correlationScore(
                    offset,
                    referenceFrameStride,
                    ratio,
                    program,
                    historyLeft,
                    historyRight,
                ) ?: return null
                if (score > bestScore ||
                    (score == bestScore && (
                        kotlin.math.abs(offset) < kotlin.math.abs(bestOffset) ||
                            (kotlin.math.abs(offset) == kotlin.math.abs(bestOffset) && offset < bestOffset)
                        ))
                ) {
                    bestScore = score
                    bestOffset = offset
                }
                offset += offsetStride
            }
            return bestOffset
        }

        private fun correlationScore(
            offset: Int,
            referenceFrameStride: Int,
            ratio: Double,
            program: AtlasEngineProgram,
            historyLeft: DoubleArray,
            historyRight: DoubleArray,
        ): Double? {
            var candidatePhase = loopStartFrame + phaseOffsetFrames + offset
            var dot = 0.0
            var historyEnergy = 0.0
            var candidateEnergy = 0.0
            var sampleCount = 0
            var frame = 0
            val bytes = bytesFor(program)
            while (frame < CORRELATION_WINDOW_FRAMES) {
                val base = kotlin.math.floor(candidatePhase).toInt()
                val fraction = candidatePhase - kotlin.math.floor(candidatePhase)
                val left = interpolateAt(bytes, 0, base, fraction)
                val right = interpolateAt(bytes, 1, base, fraction)
                dot += historyLeft[frame] * left + historyRight[frame] * right
                historyEnergy += historyLeft[frame] * historyLeft[frame] +
                    historyRight[frame] * historyRight[frame]
                candidateEnergy += left * left + right * right
                sampleCount += CHANNELS
                candidatePhase += ratio * referenceFrameStride
                frame += referenceFrameStride
            }
            if (kotlin.math.sqrt(historyEnergy / sampleCount) < MINIMUM_CORRELATION_RMS) return null
            return dot / kotlin.math.sqrt(maxOf(1.0e-20, historyEnergy * candidateEnergy))
        }

        private fun interpolateAt(
            bytes: ByteBuffer,
            channel: Int,
            base: Int,
            fraction: Double,
        ): Double {
            val y0 = pcm(bytes, channel, resolveFrame(base - 1))
            val y1 = pcm(bytes, channel, resolveFrame(base))
            val y2 = pcm(bytes, channel, resolveFrame(base + 1))
            val y3 = pcm(bytes, channel, resolveFrame(base + 2))
            return atlasCubicSample(y0, y1, y2, y3, fraction)
        }

        private fun bytesFor(program: AtlasEngineProgram): ByteBuffer = when (program) {
            AtlasEngineProgram.FULL -> fullBytes
            AtlasEngineProgram.LOAD_ONLY -> loadOnlyBytes
            AtlasEngineProgram.COAST_ONLY -> coastOnlyBytes
        }

        private fun wrapPhase(raw: Double): Double {
            val loopLength = loopEndFrameExclusive - loopStartFrame
            var relative = (raw - loopStartFrame) % loopLength
            if (relative < 0.0) relative += loopLength
            return loopStartFrame + relative
        }
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * Short.SIZE_BYTES
        const val MINIMUM_PLAYBACK_RATIO = 0.10
        const val MAXIMUM_PLAYBACK_RATIO = 4.0
        const val CORRELATION_WINDOW_FRAMES = 960
        const val CORRELATION_SEARCH_FRAMES = 960
        const val COARSE_OFFSET_STRIDE_FRAMES = 8
        const val COARSE_REFERENCE_FRAME_STRIDE = 4
        const val FINE_SEARCH_HALF_WIDTH_FRAMES = 8
        const val MINIMUM_CORRELATION_RMS = 0.001
    }
}

internal fun atlasCubicSample(
    y0: Double,
    y1: Double,
    y2: Double,
    y3: Double,
    fraction: Double,
): Double {
    val a0 = y3 - y2 - y0 + y1
    val a1 = y0 - y1 - a0
    val a2 = y2 - y0
    return a0 * fraction * fraction * fraction + a1 * fraction * fraction + a2 * fraction + y1
}

internal class MappedAtlasShard private constructor(
    private val mapping: NativeAtlasMapping,
) {
    private var references = 0
    private var closed = false

    fun retain() {
        check(!closed)
        references += 1
    }

    fun release(): Boolean {
        check(references > 0)
        references -= 1
        if (references == 0) {
            forceClose()
            return true
        }
        return false
    }

    fun readOnlySlice(offset: Long, length: Long): ByteBuffer {
        require(offset >= 0L && length > 0L && offset + length <= mapping.length)
        require(offset <= Int.MAX_VALUE && length <= Int.MAX_VALUE && offset + length <= Int.MAX_VALUE)
        return mapping.buffer.duplicate().apply {
            position(offset.toInt())
            limit((offset + length).toInt())
        }.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun forceClose() {
        if (closed) return
        closed = true
        references = 0
        mapping.close()
    }

    companion object {
        fun open(file: File, dataOffset: Long, dataBytes: Long): MappedAtlasShard =
            MappedAtlasShard(NativeAtlasMapping.open(file, dataOffset, dataBytes))
    }
}

private class NativeAtlasMapping private constructor(
    val buffer: ByteBuffer,
    private val fileOffset: Long,
    val length: Long,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        NativeAtlasMemory.unmap(buffer, fileOffset, length)
        NativeAtlasMapRegistry.mappingClosed()
    }

    companion object {
        fun open(file: File, offset: Long, length: Long): NativeAtlasMapping {
            require(length in 1..Int.MAX_VALUE.toLong()) { "Atlas shard PCM is too large to map" }
            NativeAtlasMapRegistry.reserveMapping()
            var rawBuffer: ByteBuffer? = null
            try {
                rawBuffer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    NativeAtlasMemory.map(descriptor.fd, offset, length)
                }
                val buffer = rawBuffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
                return NativeAtlasMapping(buffer, offset, length)
            } catch (error: Throwable) {
                rawBuffer?.let { buffer -> runCatching { NativeAtlasMemory.unmap(buffer, offset, length) } }
                NativeAtlasMapRegistry.mappingClosed()
                throw error
            }
        }
    }
}

internal object NativeAtlasMapRegistry {
    private val active = AtomicInteger(0)
    private val limit = AtomicInteger(2)

    val activeMappings: Int get() = active.get()
    internal val configuredLimit: Int get() = limit.get()

    fun configureExactLimit(maximumMappings: Int) {
        require(maximumMappings > 0) { "Atlas mmap bound must be positive" }
        check(active.get() == 0) { "Cannot change atlas mmap bound while a renderer is active" }
        limit.set(maximumMappings)
    }

    fun reserveMapping() {
        while (true) {
            val current = active.get()
            check(current < limit.get()) { "Atlas pack's proven mmap shard bound was exceeded" }
            if (active.compareAndSet(current, current + 1)) return
        }
    }

    fun mappingClosed() {
        check(active.decrementAndGet() >= 0) { "Atlas mapping count became negative" }
    }
}

internal object NativeAtlasMemory {
    init {
        System.loadLibrary("atlas_pcm")
    }

    @JvmStatic
    external fun map(fileDescriptor: Int, offset: Long, length: Long): ByteBuffer

    @JvmStatic
    external fun unmap(buffer: ByteBuffer, offset: Long, length: Long)
}

internal fun inspectCanonicalAtlasWav(file: File): AtlasWavLayout {
    require(file.isFile) { "Atlas shard is missing: ${file.name}" }
    RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 44L) { "Atlas WAV is truncated: ${file.name}" }
        require(input.readAscii(4) == "RIFF") { "Atlas shard is not RIFF: ${file.name}" }
        input.readUnsignedIntLittleEndian()
        require(input.readAscii(4) == "WAVE") { "Atlas shard is not WAVE: ${file.name}" }
        var formatSeen = false
        while (input.filePointer + 8L <= input.length()) {
            val chunkId = input.readAscii(4)
            val chunkSize = input.readUnsignedIntLittleEndian()
            val chunkStart = input.filePointer
            val chunkEnd = chunkStart + chunkSize
            require(chunkEnd <= input.length()) { "Atlas WAV chunk is truncated: ${file.name}" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16L) { "Atlas WAV fmt is truncated: ${file.name}" }
                    val format = input.readUnsignedShortLittleEndian()
                    val channels = input.readUnsignedShortLittleEndian()
                    val sampleRate = input.readUnsignedIntLittleEndian()
                    input.readUnsignedIntLittleEndian()
                    val blockAlign = input.readUnsignedShortLittleEndian()
                    val bits = input.readUnsignedShortLittleEndian()
                    require(format == 1 && channels == 2 && sampleRate == 48_000L && blockAlign == 4 && bits == 16) {
                        "Atlas shard must be PCM16/48k/stereo: ${file.name}"
                    }
                    formatSeen = true
                }

                "data" -> {
                    require(formatSeen) { "Atlas WAV data precedes fmt: ${file.name}" }
                    require(chunkSize > 0L && chunkSize % 4L == 0L) { "Atlas PCM size is invalid: ${file.name}" }
                    return AtlasWavLayout(
                        dataOffsetBytes = chunkStart,
                        dataBytes = chunkSize,
                        frameCount = chunkSize / 4L,
                    )
                }
            }
            input.seek(chunkEnd + (chunkSize and 1L))
        }
    }

    throw IllegalArgumentException("Atlas WAV has no data chunk: ${file.name}")
}

internal fun warmMappedPages(buffer: ByteBuffer) {
    var checksum = 0
    var offset = 0
    while (offset < buffer.limit()) {
        checksum = checksum xor buffer.get(offset).toInt()
        offset += 4_096
    }
    if (buffer.limit() > 0) checksum = checksum xor buffer.get(buffer.limit() - 1).toInt()
    MappedPageWarmupSink.value = checksum
}

private object MappedPageWarmupSink {
    @Volatile
    var value: Int = 0
}

private fun RandomAccessFile.readAscii(count: Int): String {
    val bytes = ByteArray(count)
    readFully(bytes)
    return String(bytes, Charsets.US_ASCII)
}

private fun RandomAccessFile.readUnsignedShortLittleEndian(): Int {
    val first = readUnsignedByte()
    val second = readUnsignedByte()
    return first or (second shl 8)
}

private fun RandomAccessFile.readUnsignedIntLittleEndian(): Long {
    val bytes = ByteArray(4)
    readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
}
