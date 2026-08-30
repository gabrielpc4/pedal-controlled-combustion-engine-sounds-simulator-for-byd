package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FmodContinuousParameterInterpolatorTest {
    @Test
    fun rpmStepBecomesManyMonotonicWorkerValues() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 800f)
        interpolator.apply(state, STEP)

        val values = ArrayList<Float>(400)
        repeat(400) {
            state.rpm = 6_800f
            values += interpolator.apply(state, STEP).rpm
        }

        assertTrue(values[0] > 800f && values[0] < 6_800f)
        assertTrue(values[1] > values[0] && values[1] < 6_800f)
        assertTrue(values.zipWithNext().all { (previous, current) -> current >= previous })
        assertTrue(values.count { it in 801f..<6_799f } > 100)
        assertEquals(6_800f, values.last(), 1f)
    }

    @Test
    fun twoHundredHertzSourceRampHasNoHeldPitchPlateaus() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 800f)
        var previous = interpolator.apply(state, STEP).rpm
        val deltas = ArrayList<Float>()

        repeat(20) { sourceStep ->
            val target = 800f + (sourceStep + 1) * 120f
            repeat(2) {
                state.rpm = target
                val current = interpolator.apply(state, STEP).rpm
                deltas += current - previous
                previous = current
            }
        }

        assertTrue(deltas.all { it > 0f })
        assertTrue(deltas.max() < 120f)
    }

    @Test
    fun twoHundredHertzSourceRampKeepsPitchVelocityContinuous() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 800f)
        var previous = interpolator.apply(state, STEP).rpm
        val deltas = ArrayList<Float>()

        repeat(80) { sourceStep ->
            val target = 800f + (sourceStep + 1) * 20f
            repeat(2) {
                state.rpm = target
                val current = interpolator.apply(state, STEP).rpm
                deltas += current - previous
                previous = current
            }
        }

        val settledDeltas = deltas.drop(40)
        val velocityChanges = settledDeltas.zipWithNext { first, second -> second - first }
        assertTrue(settledDeltas.all { it > 0f })
        assertTrue("largest per-tick velocity change=${velocityChanges.maxOf { abs(it) }}", velocityChanges.all {
            abs(it) < 0.75f
        })
    }

    @Test
    fun abruptLaunchCatchUpHasBoundedPerceptualPitchRate() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 800f)
        var previous = interpolator.apply(state, STEP).rpm
        val centsPerTick = ArrayList<Double>()

        // This matches the formerly audible synthetic-launch catch-up: the vehicle tach may
        // jump, but the bank must receive a continuous audio presentation trajectory.
        repeat(160) {
            state.rpm = 8_000f
            val current = interpolator.apply(state, STEP).rpm
            centsPerTick += 1_200.0 * kotlin.math.log2(current / previous)
            previous = current
        }

        assertTrue(centsPerTick.all { it > 0.0 })
        assertTrue("max cents/tick=${centsPerTick.maxOrNull()}", centsPerTick.maxOrNull()!! <= 15.01)
        val rateChanges = centsPerTick.zipWithNext { first, second -> second - first }
        assertTrue("max rate change=${rateChanges.maxOf { abs(it) }}", rateChanges.all { abs(it) <= 0.35 })
    }

    @Test
    fun regularAccelerationKeepsItsPerceptualStepBoundWhenTheWorkerIsLate() {
        listOf(STEP, 5.333e-3, 0.050).forEach { workerInterval ->
            val interpolator = FmodContinuousParameterInterpolator()
            val state = enabledState(rpm = 800f)
            var previous = interpolator.apply(state, STEP).rpm
            val centsPerDeliveredUpdate = ArrayList<Double>(160)

            repeat(160) {
                state.rpm = 8_000f
                val current = interpolator.apply(state, workerInterval).rpm
                centsPerDeliveredUpdate += abs(1_200.0 * kotlin.math.log2(current / previous))
                previous = current
            }

            assertTrue(
                "worker interval=$workerInterval emitted ${centsPerDeliveredUpdate.maxOrNull()} cents",
                centsPerDeliveredUpdate.all { it <= 15.01 },
            )
        }
    }

    @Test
    fun turboBoostTraversesTwentyMillisecondSourceTargetsWithoutLateWorkerJumps() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 3_400f).apply { boost = 0f }
        interpolator.apply(state, STEP)
        val values = ArrayList<Float>(80)

        repeat(80) { workerTick ->
            // The telemetry source holds each input for 20 ms while the audio worker publishes
            // eight 400 Hz control values. Turbo must still traverse those targets continuously.
            state.boost = ((workerTick / 8) + 1) / 10f
            values += interpolator.apply(state, STEP).boost
        }

        assertTrue(values.zipWithNext().all { (previous, current) -> current > previous })
        assertTrue(
            "max regular boost step=${values.zipWithNext { previous, current -> current - previous }.maxOrNull()}",
            values.zipWithNext().all { (previous, current) -> current - previous <= 0.00501f },
        )

        val previous = values.last()
        state.boost = 1f
        val afterFiftyMillisecondDelay = interpolator.apply(state, 0.050).boost
        assertTrue(afterFiftyMillisecondDelay - previous <= 0.00501f)
    }

    @Test
    fun downshiftKeepsClimbingToItsLatchedAudioTargetWhenTheSourceImmediatelyFalls() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 4_300f)
        interpolator.apply(state, STEP)

        val values = ArrayList<Float>(360)
        repeat(360) { tick ->
            val sourceStillShifting = tick < 24
            state.rpm = if (sourceStillShifting) 6_800f else 4_300f
            values += interpolator.apply(
                state = state,
                deltaSeconds = STEP,
                isShifting = sourceStillShifting,
                shiftSerial = 1L,
                shiftTargetRpm = 6_800f,
            ).rpm
        }

        val targetIndex = values.indexOfFirst { it >= 6_799f }
        assertTrue("audio trajectory never reached the downshift target", targetIndex >= 0)
        assertTrue(
            "audio pitch reversed before its shift target: ${values.take(targetIndex + 1)}",
            values.take(targetIndex + 1).zipWithNext().all { (previous, current) -> current > previous },
        )
        assertTrue("raw source must fall before audio reaches the latched target", targetIndex > 24)
        assertTrue(
            "shift exceeded the perceptual pitch-rate bound",
            values.zipWithNext().all { (previous, current) ->
                abs(1_200.0 * kotlin.math.log2(current / previous)) <= 15.01
            },
        )
        assertTrue("audio must eventually return to the new source RPM", values.last() < 6_600f)
    }

    @Test
    fun activeShiftWhenAudioInitializesStillLatchesItsTarget() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 4_300f)
        val initial = interpolator.apply(
            state = state,
            deltaSeconds = STEP,
            isShifting = true,
            shiftSerial = 7L,
            shiftTargetRpm = 6_800f,
        ).rpm
        assertEquals(4_300f, initial, 0f)

        val values = ArrayList<Float>(360)
        repeat(360) { tick ->
            state.rpm = 4_300f
            values += interpolator.apply(
                state = state,
                deltaSeconds = STEP,
                isShifting = tick < 24,
                shiftSerial = 7L,
                shiftTargetRpm = 6_800f,
            ).rpm
        }

        val targetIndex = values.indexOfFirst { it >= 6_799f }
        assertTrue("audio trajectory never reached the active shift target", targetIndex >= 0)
        assertTrue(values.take(targetIndex + 1).zipWithNext().all { (previous, current) -> current > previous })
    }

    @Test
    fun enablingSnapsToCurrentSourceAndLeavesEdgesUntouched() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 7_900f).apply {
            shiftSerial = 12L
            limiterSerial = 34L
            bovSerial = 56L
            backfireSerial = 78L
            shiftDirection = -1
        }

        interpolator.apply(state, STEP)

        assertEquals(7_900f, state.rpm, 0f)
        assertEquals(12L, state.shiftSerial)
        assertEquals(34L, state.limiterSerial)
        assertEquals(56L, state.bovSerial)
        assertEquals(78L, state.backfireSerial)
        assertEquals(-1, state.shiftDirection)
    }

    @Test
    fun drivetrainPitchUsesTheSameShortRamp() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 2_000f).apply { drivetrainSpeed = 0f }
        interpolator.apply(state, STEP)

        state.drivetrainSpeed = 240f
        val first = interpolator.apply(state, STEP).drivetrainSpeed
        state.drivetrainSpeed = 240f
        val second = interpolator.apply(state, STEP).drivetrainSpeed
        state.drivetrainSpeed = 240f
        val third = interpolator.apply(state, STEP).drivetrainSpeed

        assertTrue(first in 0f..240f && first != 0f && first != 240f)
        assertTrue(second > first && second < 240f)
        assertTrue(third > second && third < 240f)
    }

    @Test
    fun instantPedalReleaseCrossfadesEngineLoadInsteadOfHardSwitchingToCoast() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 6_000f).apply {
            engineThrottle = 1f
            transmissionThrottle = 1f
        }
        interpolator.apply(state, STEP)

        val values = ArrayList<Float>(144)
        repeat(144) {
            state.engineThrottle = 0f
            state.transmissionThrottle = 0f
            val presented = interpolator.apply(state, STEP)
            values += presented.engineThrottle
            assertEquals(presented.engineThrottle, presented.transmissionThrottle, 0f)
        }

        assertTrue(values.first() in 0f..1f && values.first() != 0f && values.first() != 1f)
        assertTrue(values.zipWithNext().all { (previous, current) -> current < previous })
        assertEquals(0.05f, values.last(), 0.002f)
    }

    @Test
    fun engineLoadAttackIsResponsiveButStillTraversesIntermediateMixValues() {
        val interpolator = FmodContinuousParameterInterpolator()
        val state = enabledState(rpm = 3_000f).apply { engineThrottle = 0f }
        interpolator.apply(state, STEP)

        state.engineThrottle = 1f
        val first = interpolator.apply(state, STEP).engineThrottle
        repeat(55) {
            state.engineThrottle = 1f
            interpolator.apply(state, STEP)
        }

        assertTrue(first in 0f..1f && first != 0f && first != 1f)
        assertTrue(state.engineThrottle > 0.98f)
    }

    private fun enabledState(rpm: Float) = FmodControlState().apply {
        flags = FmodControlState.FLAG_AUDIO_ENABLED or FmodControlState.FLAG_ENGINE_ENABLED
        this.rpm = rpm
    }

    private companion object {
        const val STEP = 1.0 / FmodControlPlanner.CONTROL_HZ
    }
}
