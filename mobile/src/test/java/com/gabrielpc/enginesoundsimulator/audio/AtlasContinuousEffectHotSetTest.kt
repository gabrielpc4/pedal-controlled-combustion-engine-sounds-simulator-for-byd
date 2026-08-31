package com.gabrielpc.enginesoundsimulator.audio

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasContinuousEffectHotSetTest {
    @Test
    fun replacementIsPreparedAtomicallyAndOldRegionsCloseOnlyAfterCallbackAcknowledges() {
        val factory = RecordingRegionFactory()
        val dispatcher = AtlasContinuousEffectDispatcher(maximumGroups = 1)
        val hotSet = AtlasContinuousEffectHotSet(
            nodes = Array(4, ::node),
            regionFactory = factory,
            maximumCurrentCorners = 2,
            dispatcher = dispatcher,
        )
        try {
            val firstGeneration = hotSet.request(intArrayOf(0, 1), 2)
            val first = awaitReady(hotSet, firstGeneration)
            assertEquals(listOf(0, 1), first.nodeIndices.take(first.count))
            assertEquals(2, factory.activeRegions.get())
            assertEquals(0, factory.closedRegions.get())
            hotSet.acknowledge(firstGeneration)
            waitUntil { hotSet.debugCurrentRegionCount == 2 }

            val secondGeneration = hotSet.request(intArrayOf(2, 3), 2)
            val second = awaitReady(hotSet, secondGeneration)
            assertEquals(listOf(2, 3), second.nodeIndices.take(second.count))
            assertEquals(4, factory.activeRegions.get())
            assertEquals(0, factory.closedRegions.get())
            assertEquals(4, hotSet.debugPeakTransitionRegionCount)

            hotSet.acknowledge(secondGeneration)
            waitUntil { hotSet.debugCurrentRegionCount == 2 && factory.closedRegions.get() == 2 }
            assertEquals(2, factory.activeRegions.get())

            // The renderer stops its voices before this callback-safe publication.
            hotSet.deactivate()
            waitUntil { hotSet.debugCurrentRegionCount == 0 && factory.activeRegions.get() == 0 }
        } finally {
            dispatcher.close()
        }

        assertEquals(setOf(dispatcher.debugWorkerThreadId), factory.mapThreadIds)
        assertEquals(setOf(dispatcher.debugWorkerThreadId), factory.closeThreadIds)
    }

    @Test
    fun supersededUnacknowledgedSetIsReleasedWithoutDroppingTheNewRequest() {
        val factory = RecordingRegionFactory()
        val dispatcher = AtlasContinuousEffectDispatcher(maximumGroups = 1)
        val hotSet = AtlasContinuousEffectHotSet(
            nodes = Array(2, ::node),
            regionFactory = factory,
            maximumCurrentCorners = 1,
            dispatcher = dispatcher,
        )
        try {
            val firstGeneration = hotSet.request(intArrayOf(0), 1)
            assertNotNull(awaitReady(hotSet, firstGeneration))

            val secondGeneration = hotSet.request(intArrayOf(1), 1)
            val second = awaitReady(hotSet, secondGeneration)
            assertEquals(1, second.nodeIndices[0])
            assertTrue(factory.closedRegions.get() >= 1)
            hotSet.acknowledge(secondGeneration)
            waitUntil { hotSet.debugCurrentRegionCount == 1 }
        } finally {
            dispatcher.close()
        }
        assertEquals(0, factory.activeRegions.get())
    }

    @Test
    fun thirtyFiveGroupsShareOneSleepingDispatcherWorker() {
        val groupCount = 35
        val factory = RecordingRegionFactory()
        val dispatcher = AtlasContinuousEffectDispatcher(maximumGroups = groupCount)
        val groups = Array(groupCount) {
            AtlasContinuousEffectHotSet(
                nodes = arrayOf(node(it)),
                regionFactory = factory,
                maximumCurrentCorners = 1,
                dispatcher = dispatcher,
            )
        }
        try {
            val generations = IntArray(groupCount) { index -> groups[index].request(intArrayOf(0), 1) }
            var index = 0
            while (index < groupCount) {
                awaitReady(groups[index], generations[index])
                groups[index].acknowledge(generations[index])
                index += 1
            }
            waitUntil { groups.all { it.debugCurrentRegionCount == 1 } }
            assertEquals(groupCount, dispatcher.debugRegisteredGroupCount)
            assertTrue(dispatcher.debugWorkerAlive)
            assertEquals(setOf(dispatcher.debugWorkerThreadId), factory.mapThreadIds)
        } finally {
            dispatcher.close()
        }

        assertEquals(0, factory.activeRegions.get())
        assertEquals(setOf(dispatcher.debugWorkerThreadId), factory.closeThreadIds)
    }

    private fun awaitReady(
        hotSet: AtlasContinuousEffectHotSet,
        generation: Int,
    ): AtlasContinuousEffectHotSet.ReadySet {
        var result: AtlasContinuousEffectHotSet.ReadySet? = null
        waitUntil {
            result = hotSet.readyFor(generation)
            result != null
        }

        return requireNotNull(result)
    }

    private fun node(index: Int) = AtlasEffectNode(
        parameters = mapOf("rpms" to index.toDouble()),
        lifetime = AtlasEffectLifetime.CONTINUOUS,
        hostGainClass = AtlasHostGainClass.EFFECT_EVENT,
        requiredAuthoredBindingKey = "binding:" + index.toString(16).padStart(64, '0'),
        requiredSourceGuid = "source-$index",
        shardName = "effect-$index.wav",
        startFrame = 0,
        endFrameExclusive = 64,
        loopStartFrame = 0,
        loopEndFrameExclusive = 64,
    )

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out")
            Thread.yield()
        }
    }

    private class RecordingRegionFactory : AtlasEffectPcmRegionFactory {
        val activeRegions = AtomicInteger(0)
        val closedRegions = AtomicInteger(0)
        val mapThreadIds = Collections.synchronizedSet(linkedSetOf<Long>())
        val closeThreadIds = Collections.synchronizedSet(linkedSetOf<Long>())

        override fun map(node: AtlasEffectNode, prewarm: Boolean): AtlasEffectPcmRegion {
            assertTrue(prewarm)
            mapThreadIds += Thread.currentThread().id
            activeRegions.incrementAndGet()
            return object : AtlasEffectPcmRegion {
                private var closed = false
                override val frameCount = 64
                override val loopStart = 0
                override val loopEndExclusive = 64

                override fun sample(frame: Int, channel: Int): Double = 0.0

                override fun samplePcm16(frame: Int, channel: Int): Short = 0

                override fun close() {
                    if (closed) return
                    closed = true
                    closeThreadIds += Thread.currentThread().id
                    activeRegions.decrementAndGet()
                    closedRegions.incrementAndGet()
                }
            }
        }
    }
}
