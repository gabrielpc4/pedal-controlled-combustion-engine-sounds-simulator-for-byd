package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTransientEventStateTest {
    @Test
    fun eventStartedInsideTriggersThenEveryExitReentryTriggersAgain() {
        val state = EngineTransientEventState(requiresEventStartInside = true)
        assertTrue(state.update(true))
        assertFalse(state.lastTriggerWasParameterRegionReentry)
        assertFalse(state.update(true))
        assertFalse(state.update(false))
        assertFalse(state.update(false))
        assertTrue(state.update(true))
        assertTrue(state.lastTriggerWasParameterRegionReentry)
        assertFalse(state.update(true))
        assertFalse(state.update(false))
        assertTrue(state.update(true))
    }

    @Test
    fun eventStartedOutsideRemainsDisabledUntilExplicitRestart() {
        val state = EngineTransientEventState(requiresEventStartInside = true)
        assertFalse(state.update(false))
        assertFalse(state.update(true))
        assertFalse(state.update(false))
        assertFalse(state.update(true))

        state.restartEvent()
        assertTrue(state.update(true))
    }

    @Test
    fun fixedGeometryEventStartTriggersExactlyOnce() {
        val state = EngineTransientEventState(requiresEventStartInside = false)
        assertTrue(state.update(false))
        assertFalse(state.update(true))
        assertFalse(state.update(false))
        state.restartEvent()
        assertTrue(state.update(true))
    }

    @Test
    fun jumpAcrossWithoutSampledInsideStateDoesNotTrigger() {
        val state = EngineTransientEventState(requiresEventStartInside = true)
        assertTrue(state.update(true))
        assertFalse(state.update(false))
        assertFalse(state.update(false))
        assertTrue(state.update(true))
    }
}
