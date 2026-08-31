package com.gabrielpc.enginesoundsimulator.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasOneShotStreamingTest {
    @Test
    fun coldMultiPageSourceIsReadExactlyByWorkerAndNeverByCallback() {
        val frames = 32_768 // 128 KiB, deliberately crosses many 4 KiB mmap pages.
        val source = source(frames, attackFrames = 96)
        AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 16_384).use { pool ->
            val voice = pool.voice(0)
            assertTrue(voice.begin(source))
            waitUntil { voice.playbackReady }

            // Even the cached attack is mixed into the Float ring by the worker. The callback
            // consumes one prepared logical stream and never loops over interpolation corners.
            repeat(96) { frame ->
                assertEquals(normalizedPattern(frame, 0), voice.sample(0), 1.0e-7)
                assertEquals(normalizedPattern(frame, 1), voice.sample(1), 1.0e-7)
                voice.advance()
            }

            waitUntil { source.debugBackgroundReadCount >= 2 * (96 + 12_000) }
            repeat(12_000) { frameOffset ->
                val frame = frameOffset + 96
                assertEquals(normalizedPattern(frame, 0), voice.sample(0), 1.0e-7)
                assertEquals(normalizedPattern(frame, 1), voice.sample(1), 1.0e-7)
                voice.advance()
            }
            assertEquals(0, pool.consumeUnderrunFrameCount())
            assertTrue(source.debugBackgroundReadCount > 0)
        }
    }

    @Test
    fun cachedAttackStartsAtFrameZeroEvenWhenWorkerNeverStarts() {
        val source = source(frameCount = 2_048, attackFrames = 64)
        AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 256, startWorker = false).use { pool ->
            val voice = pool.voice(0)
            assertTrue(voice.begin(source))
            assertTrue(voice.playbackReady)
            assertEquals(normalizedPattern(0, 0), voice.sample(0), 1.0e-7)
            assertEquals(normalizedPattern(0, 1), voice.sample(1), 1.0e-7)
            voice.advance()
            repeat(63) { frameOffset ->
                val frame = frameOffset + 1
                assertEquals(normalizedPattern(frame, 0), voice.sample(0), 1.0e-7)
                voice.advance()
            }
            assertEquals(0, pool.consumeUnderrunFrameCount())

            // The first uncached frame is the first one allowed to underrun; frame zero is never
            // delayed or shifted by worker scheduling.
            assertEquals(0.0, voice.sample(0), 0.0)
            assertEquals(0, source.debugBackgroundReadCount)
            assertEquals(1, pool.consumeUnderrunFrameCount())
        }
    }

    @Test
    fun cancellationDoesNotReuseRingUntilWorkerOwnsAndRetiresIt() {
        val first = source(frameCount = 10_000, attackFrames = 64)
        val second = source(frameCount = 10_000, attackFrames = 64)
        AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 256).use { pool ->
            val voice = pool.voice(0)
            assertTrue(voice.begin(first))
            voice.cancel()
            // A hot callback cannot wait for the producer, so it rejects safely.
            assertFalse(voice.begin(second))
            waitUntil { voice.readyForStart }
            assertTrue(voice.begin(second))
            assertEquals(1, pool.consumeStartRejectedCount())
        }
    }

    @Test
    fun retiringFirstSlotDoesNotBlockAReadySecondVoice() {
        val first = source(frameCount = 10_000, attackFrames = 64)
        val second = source(frameCount = 10_000, attackFrames = 64)
        AtlasOneShotStreamPool(voiceCount = 2, ringFramesPerVoice = 256).use { pool ->
            val retiring = pool.voice(0)
            val ready = pool.voice(1)
            assertTrue(retiring.begin(first))
            retiring.cancel()

            assertFalse(retiring.readyForStart)
            assertTrue(ready.readyForStart)
            assertTrue(ready.begin(second))
        }
    }

    @Test
    fun closeStopsTheOnlyMappedPcmReaderAndRejectsSubsequentStarts() {
        val source = source(frameCount = 32_768, attackFrames = 64)
        val pool = AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 16_384)
        val voice = pool.voice(0)
        assertTrue(voice.begin(source))
        waitUntil { source.debugBackgroundReadCount > 0 }

        pool.close()
        val readsAfterClose = source.debugBackgroundReadCount
        repeat(100) { Thread.yield() }

        assertEquals(readsAfterClose, source.debugBackgroundReadCount)
        assertFalse(voice.begin(source))
    }

    @Test
    fun oneLogicalRingMixesEveryCornerWithoutPcm16ClippingOrPerCornerRings() {
        val first = constantSource(value = 24_000, frameCount = 2_048, attackFrames = 64)
        val second = constantSource(value = 20_000, frameCount = 2_048, attackFrames = 64)
        AtlasOneShotStreamPool(
            voiceCount = 1,
            ringFramesPerVoice = 256,
            maximumContributorsPerVoice = 4,
        ).use { pool ->
            assertEquals(256L * 2L * Float.SIZE_BYTES, pool.allocatedRingBytes)
            val voice = pool.voice(0)
            val sources = arrayOf<AtlasOneShotPcmSource?>(first, second, null, null)
            val gains = doubleArrayOf(1.0, 1.0, 0.0, 0.0)
            assertTrue(voice.begin(sources, gains, contributorCount = 2))
            waitUntil { voice.playbackReady }

            // The unclipped contributor sum is intentionally above normalized PCM full scale.
            val expected = (24_000.0 + 20_000.0) / Short.MAX_VALUE
            assertTrue(expected > 1.0)
            assertEquals(expected, voice.sample(0), 1.0e-7)
            voice.advance()

            repeat(63) { voice.advance() }
            waitUntil { first.debugBackgroundReadCount > 0 && second.debugBackgroundReadCount > 0 }
            assertEquals(expected, voice.sample(0), 1.0e-7)
            assertEquals(0, pool.consumeUnderrunFrameCount())
        }
    }

    @Test
    fun finiteInterpolationCanComposeMoreThanFourCornersIntoOneLogicalVoice() {
        val contributors = Array(8) { index ->
            constantSource(value = (2_000 + index * 500).toShort(), frameCount = 512, attackFrames = 64)
        }
        AtlasOneShotStreamPool(
            voiceCount = 1,
            ringFramesPerVoice = 256,
            maximumContributorsPerVoice = contributors.size,
        ).use { pool ->
            assertEquals(256L * 2L * Float.SIZE_BYTES, pool.allocatedRingBytes)
            val gains = DoubleArray(contributors.size) { index -> (index + 1) / 36.0 }
            val sources = Array<AtlasOneShotPcmSource?>(contributors.size) { contributors[it] }
            assertTrue(pool.voice(0).begin(sources, gains, contributors.size))
            waitUntil { pool.voice(0).playbackReady }

            val expected = contributors.indices.sumOf { index ->
                (2_000.0 + index * 500.0) / Short.MAX_VALUE * gains[index]
            }
            assertEquals(expected, pool.voice(0).sample(0), 1.0e-7)
        }
    }

    @Test
    fun logicalStartCopiesReusableContributorScratchAtomically() {
        val first = constantSource(value = 8_000, frameCount = 512, attackFrames = 64)
        val replacement = constantSource(value = 30_000, frameCount = 512, attackFrames = 64)
        AtlasOneShotStreamPool(
            voiceCount = 1,
            ringFramesPerVoice = 256,
            maximumContributorsPerVoice = 2,
        ).use { pool ->
            val voice = pool.voice(0)
            val sources = arrayOf<AtlasOneShotPcmSource?>(first, null)
            val gains = doubleArrayOf(0.5, 0.0)
            assertTrue(voice.begin(sources, gains, contributorCount = 1))

            sources[0] = replacement
            gains[0] = 3.0
            waitUntil { first.debugBackgroundReadCount > 0 }

            val workerReadsBeforeCallback = first.debugBackgroundReadCount
            assertEquals(4_000.0 / Short.MAX_VALUE, voice.sample(0), 1.0e-7)
            assertEquals(workerReadsBeforeCallback, first.debugBackgroundReadCount)
            assertEquals(0, replacement.debugBackgroundReadCount)
        }
    }

    @Test
    fun workerPrefetchesTailBeforeCachedAttackBoundaryWithoutLosingFrameZero() {
        val source = source(frameCount = 8_192, attackFrames = 4_096)
        AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 12_288).use { pool ->
            val voice = pool.voice(0)
            assertTrue(voice.begin(source))
            assertEquals(4_096, voice.debugAttackPlaybackExclusive)
            assertEquals(normalizedPattern(0, 0), voice.sample(0), 1.0e-7)

            waitUntil { voice.debugProducedExclusive > voice.debugAttackPlaybackExclusive }
            repeat(4_160) { frame ->
                assertEquals(normalizedPattern(frame, 0), voice.sample(0), 1.0e-7)
                assertEquals(normalizedPattern(frame, 1), voice.sample(1), 1.0e-7)
                voice.advance()
            }
            assertEquals(0, pool.consumeUnderrunFrameCount())
            assertTrue(source.debugBackgroundReadCount > 0)
        }
    }

    @Test
    fun workerTracksOnlyActiveSlotsInsteadOfScanningTheLogicalSafetySpace() {
        val source = source(frameCount = 8_192, attackFrames = 64)
        AtlasOneShotStreamPool(
            voiceCount = 512,
            ringFramesPerVoice = 256,
        ).use { pool ->
            assertEquals(0, pool.debugActiveWorkerSlots)
            val voice = pool.voice(511)
            assertTrue(voice.begin(source))
            waitUntil { pool.debugActiveWorkerSlots == 1 }
            assertEquals(1, pool.debugActiveWorkerSlots)

            voice.cancel()
            waitUntil { pool.debugActiveWorkerSlots == 0 }
        }
    }

    @Test
    fun ringFullWorkerSleepsUntilConsumptionReleasesAChunk() {
        val source = source(frameCount = 8_192, attackFrames = 512)
        AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 256).use { pool ->
            val voice = pool.voice(0)
            assertTrue(voice.begin(source))
            waitUntil { voice.playbackReady && pool.debugWorkerParkCount >= 2L }
            val visitsWhileFull = pool.debugWorkerVisitCount
            Thread.sleep(25L)
            assertEquals(visitsWhileFull, pool.debugWorkerVisitCount)

            // The prepared ring begins after the 512-frame attack. Consuming attack frames does
            // not free ring storage; 128 ring frames do.
            repeat(640) { frame ->
                assertEquals(normalizedPattern(frame, 0), voice.sample(0), 1.0e-7)
                assertEquals(normalizedPattern(frame, 1), voice.sample(1), 1.0e-7)
                voice.advance()
            }
            waitUntil { pool.debugWorkerVisitCount > visitsWhileFull }
            assertEquals(0, pool.consumeUnderrunFrameCount())
        }
    }

    @Test
    fun closeReleasesAnOpenedTailReaderBeforeReturning() {
        val activeReaders = AtomicInteger(0)
        val closedReaders = AtomicInteger(0)
        val attack = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(64) {
                putShort(1_000)
                putShort(1_000)
            }
            flip()
        }
        val source = AtlasOneShotPcmSource(
            mappedPcm = attack,
            frameCount = 8_192,
            attackCacheFrames = 64,
            tailReaderFactory = AtlasOneShotPcmTailReaderFactory {
                activeReaders.incrementAndGet()
                object : AtlasOneShotPcmTailReader {
                    private var closed = false

                    override fun sample(frame: Int, channel: Int): Short = 1_000

                    override fun close() {
                        if (closed) return
                        closed = true
                        activeReaders.decrementAndGet()
                        closedReaders.incrementAndGet()
                    }
                }
            },
        )
        val pool = AtlasOneShotStreamPool(voiceCount = 1, ringFramesPerVoice = 256)
        assertTrue(pool.voice(0).begin(source))
        waitUntil { activeReaders.get() == 1 }

        pool.close()

        assertEquals(0, activeReaders.get())
        assertEquals(1, closedReaders.get())
    }

    private fun source(frameCount: Int, attackFrames: Int): AtlasOneShotPcmSource {
        val bytes = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { frame ->
            bytes.putShort(pattern(frame, 0))
            bytes.putShort(pattern(frame, 1))
        }
        bytes.flip()
        return AtlasOneShotPcmSource(bytes, frameCount, attackFrames)
    }

    private fun constantSource(value: Short, frameCount: Int, attackFrames: Int): AtlasOneShotPcmSource {
        val bytes = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) {
            bytes.putShort(value)
            bytes.putShort(value)
        }
        bytes.flip()

        return AtlasOneShotPcmSource(bytes, frameCount, attackFrames)
    }

    private fun pattern(frame: Int, channel: Int): Short =
        ((frame * 37 + channel * 19) % Short.MAX_VALUE).toShort()

    private fun normalizedPattern(frame: Int, channel: Int): Double =
        pattern(frame, channel).toDouble() / Short.MAX_VALUE

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out")
            Thread.yield()
        }
    }
}
