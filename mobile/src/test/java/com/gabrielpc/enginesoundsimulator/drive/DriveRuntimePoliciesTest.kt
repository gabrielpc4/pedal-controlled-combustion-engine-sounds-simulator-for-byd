package com.gabrielpc.enginesoundsimulator.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRuntimePoliciesTest {
    @Test
    fun `explicit reopen during fade queues a new runtime only after old teardown`() {
        val coordinator = DriveRuntimeStopRestartCoordinator()

        assertTrue(coordinator.requestStop())
        assertEquals(
            ExplicitStartDisposition.QUEUED_AFTER_TEARDOWN,
            coordinator.requestExplicitStart(),
        )
        assertTrue(coordinator.isStopping())
        assertTrue(coordinator.beginTeardown())
        assertEquals(TeardownDisposition.START_NEW_RUNTIME, coordinator.completeTeardown())
        assertFalse(coordinator.isStopping())
    }

    @Test
    fun `explicit reopen racing in-flight close cannot reuse active controller`() {
        val coordinator = DriveRuntimeStopRestartCoordinator()
        var activeControllerId: Int? = 41
        var closedControllerId: Int? = null
        var nextControllerId = 42

        assertTrue(coordinator.requestStop())
        assertTrue(coordinator.beginTeardown())
        assertEquals(
            ExplicitStartDisposition.QUEUED_AFTER_TEARDOWN,
            coordinator.requestExplicitStart(),
        )

        // Mirrors DriveRuntimeService.closeControllerRuntimeAsync: ownership is detached and the
        // close finishes before completeTeardown is allowed to request construction.
        closedControllerId = activeControllerId
        activeControllerId = null
        assertEquals(41, closedControllerId)
        assertEquals(TeardownDisposition.START_NEW_RUNTIME, coordinator.completeTeardown())
        activeControllerId = nextControllerId++

        assertEquals(42, activeControllerId)
        assertTrue(activeControllerId != closedControllerId)
    }

    @Test
    fun `second Stop cancels a reopen queued during teardown`() {
        val coordinator = DriveRuntimeStopRestartCoordinator()

        assertTrue(coordinator.requestStop())
        assertEquals(
            ExplicitStartDisposition.QUEUED_AFTER_TEARDOWN,
            coordinator.requestExplicitStart(),
        )
        assertFalse(coordinator.requestStop())
        assertTrue(coordinator.beginTeardown())
        assertEquals(TeardownDisposition.STOP_SERVICE, coordinator.completeTeardown())
    }

    @Test
    fun `persisted explicit start closes delivery race before ACTION_START callback`() {
        val coordinator = DriveRuntimeStopRestartCoordinator()

        assertTrue(coordinator.requestStop())
        assertTrue(coordinator.beginTeardown())
        assertEquals(
            TeardownDisposition.START_NEW_RUNTIME,
            coordinator.completeTeardown(externalRestartRequested = true),
        )
    }

    @Test
    fun `completed Stop is no longer a pending fade or teardown`() {
        val coordinator = DriveRuntimeStopRestartCoordinator()

        assertTrue(coordinator.requestStop())
        assertTrue(coordinator.isStopping())
        assertTrue(coordinator.beginTeardown())
        assertTrue(coordinator.isStopping())
        assertEquals(TeardownDisposition.STOP_SERVICE, coordinator.completeTeardown())

        // A late stale started-service delivery must call stopSelf instead of waiting for a fade
        // callback that has already completed.
        assertFalse(coordinator.isStopping())
        assertFalse(coordinator.requestStop())
    }

    @Test
    fun `background notification and Stop reads never construct UI snapshots`() {
        val runtime = FakePrimitiveRuntime()

        repeat(1_000) {
            assertEquals(
                "Tatuus FA01",
                DriveRuntimeBackgroundReadPolicy.notificationCarName(runtime, "fallback"),
            )
            assertEquals(
                300L,
                DriveRuntimeBackgroundReadPolicy.shutdownFadeMillis(
                    runtime = runtime,
                    minimumMillis = 200L,
                    maximumMillis = 2_500L,
                    timeConstants = 5.0,
                ),
            )
        }

        assertEquals(0L, runtime.uiSnapshotBuildCount())
        assertEquals(1_000, runtime.carNameReads)
        assertEquals(1_000, runtime.fadeReads)
    }

    @Test
    fun `background fade calculation clamps invalid and extreme tuning`() {
        assertEquals(
            200L,
            DriveRuntimeBackgroundReadPolicy.shutdownFadeMillis(
                runtime = FakePrimitiveRuntime(fadeMillis = Double.NaN),
                minimumMillis = 200L,
                maximumMillis = 2_500L,
                timeConstants = 5.0,
            ),
        )
        assertEquals(
            2_500L,
            DriveRuntimeBackgroundReadPolicy.shutdownFadeMillis(
                runtime = FakePrimitiveRuntime(fadeMillis = 5_000.0),
                minimumMillis = 200L,
                maximumMillis = 2_500L,
                timeConstants = 5.0,
            ),
        )
    }

    @Test
    fun `persisted startup mute creates neither decoder work nor AudioTrack ownership`() {
        assertFalse(DriveAudioResourcePolicy.shouldStartOnControllerStart(soundEnabled = false))
        assertFalse(DriveAudioResourcePolicy.shouldPrepareSelectedProfile(audioRuntimeStarted = false))

        // Runtime mute is intentionally different: after playback has begun, car changes retain
        // the decoded phase-preserving audio graph even while its enabled gain is faded to zero.
        assertTrue(DriveAudioResourcePolicy.shouldPrepareSelectedProfile(audioRuntimeStarted = true))
        assertTrue(DriveAudioResourcePolicy.shouldStartOnControllerStart(soundEnabled = true))
    }

    private class FakePrimitiveRuntime(
        private val fadeMillis: Double = 60.0,
    ) : DriveRuntimePrimitiveState {
        var carNameReads = 0
        var fadeReads = 0
        private var snapshotBuilds = 0L

        override fun selectedCarDisplayName(): String {
            carNameReads += 1
            return "Tatuus FA01"
        }

        override fun shutdownFadeTimeConstantMillis(): Double {
            fadeReads += 1
            return fadeMillis
        }

        override fun uiSnapshotBuildCount(): Long = snapshotBuilds
    }
}
