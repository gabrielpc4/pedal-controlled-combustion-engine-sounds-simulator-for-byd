package com.gabrielpc.enginesoundsimulator.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRuntimeSessionPolicyTest {
    @Test
    fun `explicit dashboard start clears prior stop and allows sticky restoration`() {
        val stopped = DriveRuntimeSessionState(
            sessionRequested = false,
            stoppedByUser = true,
            soundEnabled = false,
        )

        val restarted = DriveRuntimeSessionPolicy.onExplicitStart(stopped)

        assertTrue(restarted.sessionRequested)
        assertFalse(restarted.stoppedByUser)
        assertFalse(restarted.soundEnabled)
        assertTrue(DriveRuntimeSessionPolicy.shouldRun(restarted))
    }

    @Test
    fun `task or notification stop suppresses sticky restoration`() {
        val active = DriveRuntimeSessionState(
            sessionRequested = true,
            stoppedByUser = false,
            soundEnabled = true,
        )

        val stopped = DriveRuntimeSessionPolicy.onUserStop(active)

        assertFalse(stopped.sessionRequested)
        assertTrue(stopped.stoppedByUser)
        assertFalse(DriveRuntimeSessionPolicy.shouldRun(stopped))
    }

    @Test
    fun `queued explicit Start delivered after newer Stop is rejected`() {
        // Dispatch commits the explicit request before Android queues ACTION_START.
        val dispatched = DriveRuntimeSessionPolicy.onExplicitStart(DriveRuntimeSessionState())
        assertTrue(DriveRuntimeSessionPolicy.acceptExplicitStartDelivery(dispatched))

        // Task removal/notification Stop is newer. Delivery of the older queued intent is now
        // observational only and cannot clear this marker or resurrect the runtime.
        val stoppedBeforeDelivery = DriveRuntimeSessionPolicy.onUserStop(dispatched)
        assertFalse(DriveRuntimeSessionPolicy.acceptExplicitStartDelivery(stoppedBeforeDelivery))
        assertFalse(DriveRuntimeSessionPolicy.shouldRun(stoppedBeforeDelivery))
    }

    @Test
    fun `genuine explicit reopen after Stop authorizes its queued delivery`() {
        val stopped = DriveRuntimeSessionPolicy.onUserStop(
            DriveRuntimeSessionPolicy.onExplicitStart(DriveRuntimeSessionState()),
        )

        val reopenedBeforeDelivery = DriveRuntimeSessionPolicy.onExplicitStart(stopped)

        assertTrue(DriveRuntimeSessionPolicy.acceptExplicitStartDelivery(reopenedBeforeDelivery))
        assertTrue(DriveRuntimeSessionPolicy.shouldRun(reopenedBeforeDelivery))
    }

    @Test
    fun `uncleared or incomplete session markers never restore`() {
        assertFalse(DriveRuntimeSessionPolicy.shouldRun(DriveRuntimeSessionState()))
        assertFalse(
            DriveRuntimeSessionPolicy.shouldRun(
                DriveRuntimeSessionState(sessionRequested = true, stoppedByUser = true),
            ),
        )
    }

    @Test
    fun `only the current requested initialization generation may publish`() {
        val active = DriveRuntimeSessionState(sessionRequested = true, stoppedByUser = false)

        assertTrue(
            DriveRuntimeInitializationPolicy.shouldPublish(
                completedGeneration = 4L,
                currentGeneration = 4L,
                sessionState = active,
                stopping = false,
                destroyed = false,
            ),
        )
        assertFalse(
            DriveRuntimeInitializationPolicy.shouldPublish(
                completedGeneration = 3L,
                currentGeneration = 4L,
                sessionState = active,
                stopping = false,
                destroyed = false,
            ),
        )
    }

    @Test
    fun `stop destruction and cleared session all reject late controller publication`() {
        val active = DriveRuntimeSessionState(sessionRequested = true, stoppedByUser = false)
        val stopped = DriveRuntimeSessionPolicy.onUserStop(active)

        assertFalse(DriveRuntimeInitializationPolicy.shouldPublish(1L, 1L, active, stopping = true, destroyed = false))
        assertFalse(DriveRuntimeInitializationPolicy.shouldPublish(1L, 1L, active, stopping = false, destroyed = true))
        assertFalse(DriveRuntimeInitializationPolicy.shouldPublish(1L, 1L, stopped, stopping = false, destroyed = false))
    }
}
