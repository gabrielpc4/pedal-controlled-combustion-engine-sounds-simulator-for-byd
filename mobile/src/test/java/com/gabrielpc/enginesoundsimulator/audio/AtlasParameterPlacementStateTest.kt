package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasParameterPlacementStateTest {
    @Test
    fun membershipIsInclusiveAndRequiresEverySpanOnEveryParameter() {
        val placement = AtlasParameterPlacementEntry(
            axes = arrayOf(
                axis("rpms", span(1_000.0, 4_000.0), span(500.0, 5_000.0)),
                axis("throttle", span(0.4, 0.7)),
            ),
        )
        var rpm = 1_000.0
        var throttle = 0.4
        assertTrue(placement.contains { parameter -> if (parameter == "rpms") rpm else throttle })

        rpm = 4_000.0
        throttle = 0.7
        assertTrue(placement.contains { parameter -> if (parameter == "rpms") rpm else throttle })

        throttle = Math.nextUp(0.7)
        assertFalse(placement.contains { parameter -> if (parameter == "rpms") rpm else throttle })
        throttle = 0.5
        rpm = 4_500.0
        assertFalse(placement.contains { parameter -> if (parameter == "rpms") rpm else throttle })

        val authoredExclusiveEnd = AtlasParameterPlacementEntry(
            axes = arrayOf(axis("rpms", span(1_000.0, 4_000.0, includeEnd = false))),
        )
        assertFalse(authoredExclusiveEnd.contains { 4_000.0 })
    }

    @Test
    fun initialInsideStartsOnceAndExitArmsReentryInEitherDirection() {
        val state = AtlasParameterPlacementState()
        assertTrue(state.wouldEnter(nextInside = true))
        assertTrue(state.wouldEnter(nextInside = true))
        assertTrue(state.update(nextInside = true))
        assertFalse(state.wouldEnter(nextInside = true))
        assertFalse(state.update(nextInside = true))
        assertFalse(state.update(nextInside = false))
        assertTrue(state.wouldEnter(nextInside = true))
        assertTrue(state.update(nextInside = true))
        assertFalse(state.update(nextInside = false))
        assertFalse(state.update(nextInside = false))
        assertTrue(state.update(nextInside = true))
        state.reset()
        assertTrue(state.update(nextInside = true))
        assertFalse(state.update(nextInside = true))

        val initiallyOutside = AtlasParameterPlacementState()
        assertFalse(initiallyOutside.update(nextInside = false))
        assertTrue(initiallyOutside.update(nextInside = true))
    }

    @Test
    fun pendingInitialEntryIsAppliedOnlyAfterTheNewActivationReset() {
        val state = AtlasParameterPlacementState()
        val preparedInside = true
        assertTrue(state.wouldEnter(preparedInside))

        state.reset()
        assertTrue(state.update(preparedInside))
        assertFalse(state.wouldEnter(preparedInside))
        assertFalse(state.update(preparedInside))
    }

    private fun axis(parameter: String, vararg spans: AtlasParameterPlacementSpan) =
        AtlasParameterPlacementAxis(
            parameter,
            "parameter-guid",
            "layout-guid",
            AtlasHostParameterValue.Constant(0.0),
            arrayOf(*spans),
        )

    private fun span(start: Double, end: Double, includeEnd: Boolean = true) = AtlasParameterPlacementSpan(
        start = start,
        end = end,
        includeEnd = includeEnd,
        parameterGuid = "parameter-guid",
        layoutGuid = "layout-guid",
        instrumentGuid = "instrument-guid",
    )
}
