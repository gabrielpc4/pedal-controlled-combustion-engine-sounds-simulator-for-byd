package com.gabrielpc.enginesoundsimulator.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface AtlasEffectPcmRegion : AutoCloseable {
    val frameCount: Int
    val loopStart: Int
    val loopEndExclusive: Int

    fun sample(frame: Int, channel: Int): Double

    fun samplePcm16(frame: Int, channel: Int): Short
}

internal fun interface AtlasEffectPcmRegionFactory : AutoCloseable {
    /** Mapping and page warmup are worker/setup operations and are forbidden on the audio callback. */
    fun map(node: AtlasEffectNode, prewarm: Boolean): AtlasEffectPcmRegion

    override fun close() = Unit
}

/**
 * Shares one lazy mmap per currently referenced effect shard while retaining only hot node slices.
 * Headers are inspected eagerly; mapping, slice creation, page warmup, release, and unmap happen on
 * setup/background workers. Finite sources retain only their small PCM16 attack until triggered.
 */
internal class MappedAtlasEffectPcmRegionFactory(
    nodes: List<AtlasEffectNode>,
    private val fileResolver: AtlasShardFileResolver,
    private val maximumMappedShardsDuringTransition: Int,
) : AtlasEffectPcmRegionFactory {
    private val layoutsByShard = nodes.map(AtlasEffectNode::shardName)
        .distinct()
        .associateWith { shardName -> inspectCanonicalAtlasWav(fileResolver.fileFor(shardName)) }
    private val mappedShards = linkedMapOf<String, MappedAtlasShard>()
    private var closed = false
    private var activeRegions = 0
    private var peakActiveRegions = 0
    private var activeRegionBytes = 0L
    private var peakActiveRegionBytes = 0L

    init {
        require(maximumMappedShardsDuringTransition >= 0)
        require(nodes.isEmpty() || maximumMappedShardsDuringTransition > 0)
    }

    @Synchronized
    override fun map(node: AtlasEffectNode, prewarm: Boolean): AtlasEffectPcmRegion {
        check(!closed) { "Atlas effect region factory is closed" }
        val layout = requireNotNull(layoutsByShard[node.shardName])
        require(node.startFrame >= 0L && node.endFrameExclusive <= layout.frameCount)
        val frameCount = Math.toIntExact(node.endFrameExclusive - node.startFrame)
        val byteCount = Math.multiplyExact(frameCount, BYTES_PER_FRAME)
        val shard = mappedShards[node.shardName] ?: run {
            require(mappedShards.size < maximumMappedShardsDuringTransition) {
                "Atlas effect mapping exceeds its selected-perspective shard-transition proof"
            }
            MappedAtlasShard.open(
                fileResolver.fileFor(node.shardName),
                layout.dataOffsetBytes,
                layout.dataBytes,
            ).also { mappedShards[node.shardName] = it }
        }
        shard.retain()
        val slice = try {
            shard.readOnlySlice(node.startFrame * BYTES_PER_FRAME, byteCount.toLong())
        } catch (error: Throwable) {
            releaseShard(node.shardName, shard)
            throw error
        }
        try {
            if (prewarm) warmMappedPages(slice)
        } catch (error: Throwable) {
            releaseShard(node.shardName, shard)
            throw error
        }
        activeRegions += 1
        activeRegionBytes += byteCount
        peakActiveRegions = maxOf(peakActiveRegions, activeRegions)
        peakActiveRegionBytes = maxOf(peakActiveRegionBytes, activeRegionBytes)

        return MappedEffectPcmRegion(
            bytes = slice,
            frameCount = frameCount,
            loopStart = Math.toIntExact((node.loopStartFrame ?: node.startFrame) - node.startFrame),
            loopEndExclusive = Math.toIntExact(
                (node.loopEndFrameExclusive ?: node.endFrameExclusive) - node.startFrame,
            ),
            release = { releaseRegion(node.shardName, shard, byteCount.toLong()) },
        )
    }

    fun prepareFiniteSource(
        node: AtlasEffectNode,
        attackCacheFrames: Int = FINITE_ATTACK_CACHE_FRAMES,
    ): AtlasOneShotPcmSource {
        require(node.lifetime != AtlasEffectLifetime.CONTINUOUS)
        val layout = requireNotNull(layoutsByShard[node.shardName])
        val frameCount = Math.toIntExact(node.endFrameExclusive - node.startFrame)
        val cachedFrames = minOf(frameCount, attackCacheFrames)
        val attackBytes = ByteArray(Math.multiplyExact(cachedFrames, BYTES_PER_FRAME))
        RandomAccessFile(fileResolver.fileFor(node.shardName), "r").use { input ->
            input.seek(layout.dataOffsetBytes + node.startFrame * BYTES_PER_FRAME)
            input.readFully(attackBytes)
        }
        return AtlasOneShotPcmSource(
            mappedPcm = ByteBuffer.wrap(attackBytes).order(ByteOrder.LITTLE_ENDIAN),
            frameCount = frameCount,
            attackCacheFrames = attackCacheFrames,
            tailReaderFactory = AtlasOneShotPcmTailReaderFactory {
                val region = map(node, prewarm = false)
                object : AtlasOneShotPcmTailReader {
                    override fun sample(frame: Int, channel: Int): Short = region.samplePcm16(frame, channel)

                    override fun close() = region.close()
                }
            },
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        mappedShards.values.forEach(MappedAtlasShard::forceClose)
        mappedShards.clear()
        activeRegions = 0
        activeRegionBytes = 0L
    }

    @Synchronized
    private fun releaseShard(name: String, shard: MappedAtlasShard) {
        if (closed) return
        if (shard.release()) mappedShards.remove(name, shard)
    }

    @Synchronized
    private fun releaseRegion(name: String, shard: MappedAtlasShard, bytes: Long) {
        if (closed) return
        check(activeRegions > 0 && activeRegionBytes >= bytes)
        activeRegions -= 1
        activeRegionBytes -= bytes
        releaseShard(name, shard)
    }

    internal val activeMappedShardCount: Int
        @Synchronized get() = mappedShards.size
    internal val activeMappedRegionCount: Int
        @Synchronized get() = activeRegions
    internal val peakMappedRegionCount: Int
        @Synchronized get() = peakActiveRegions
    internal val activeMappedRegionBytes: Long
        @Synchronized get() = activeRegionBytes
    internal val peakMappedRegionBytes: Long
        @Synchronized get() = peakActiveRegionBytes

    private class MappedEffectPcmRegion(
        private val bytes: ByteBuffer,
        override val frameCount: Int,
        override val loopStart: Int,
        override val loopEndExclusive: Int,
        private val release: () -> Unit,
    ) : AtlasEffectPcmRegion {
        private var closed = false

        init {
            require(loopStart in 0 until loopEndExclusive && loopEndExclusive <= frameCount)
        }

        override fun sample(frame: Int, channel: Int): Double =
            samplePcm16(frame, channel).toDouble() / Short.MAX_VALUE

        override fun samplePcm16(frame: Int, channel: Int): Short {
            check(!closed)
            val byteIndex = (frame.coerceIn(0, frameCount - 1) * CHANNELS +
                channel.coerceIn(0, CHANNELS - 1)) * Short.SIZE_BYTES
            return bytes.getShort(byteIndex)
        }

        override fun close() {
            if (closed) return
            closed = true
            release()
        }
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * Short.SIZE_BYTES
        const val FINITE_ATTACK_CACHE_FRAMES = 4_096
    }
}
