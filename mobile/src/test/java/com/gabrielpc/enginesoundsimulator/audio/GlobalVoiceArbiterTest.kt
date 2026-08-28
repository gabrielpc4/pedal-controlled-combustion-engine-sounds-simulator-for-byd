package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalVoiceArbiterTest {
    @Test
    fun oneGlobalPoolRanksPriorityBeforeAudibilityAcrossFixedAndDynamicVoices() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(64, 128),
            fixedInitiallyActive = booleanArrayOf(true, true),
            programLaneLimits = intArrayOf(4),
            logicalVoiceLimit = 8,
            realVoiceBudget = 2,
        )
        arbiter.setFixedAudibility(0, 0.08)
        arbiter.setFixedAudibility(1, 100.0)
        val transient = arbiter.triggerDynamic(0, 7, 64, 0.12, frameCount = 4_800)
        arbiter.updateDynamicMix(transient, targetGain = 0.12, increment = 1.0)

        arbiter.rebalance()

        assertTrue(arbiter.isFixedReal(0))
        assertFalse("priority 128 loses before audibility is compared", arbiter.isFixedReal(1))
        assertTrue(arbiter.realSlotForDynamic(transient) >= 0)
        assertEquals(3, arbiter.activeLogicalVoices)
        assertEquals(2, arbiter.activeRealVoices)
        assertEquals(1, arbiter.activeVirtualVoices)
    }

    @Test
    fun newerMoreAudibleProgramDisplacesOlderVoiceInsteadOfCrossProgramFifo() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(4, 4),
            logicalVoiceLimit = 8,
            realVoiceBudget = 2,
        )
        val oldLowA = arbiter.triggerDynamic(0, 10, 64, 0.10, 4_800)
        val oldLowB = arbiter.triggerDynamic(0, 11, 64, 0.10, 4_800)
        val newerMid = arbiter.triggerDynamic(1, 20, 64, 0.14, 4_800)

        arbiter.rebalance()

        assertTrue(arbiter.realSlotForDynamic(oldLowA) >= 0)
        assertEquals(GlobalVoiceArbiter.NO_LOGICAL_VOICE, arbiter.realSlotForDynamic(oldLowB))
        assertTrue(arbiter.realSlotForDynamic(newerMid) >= 0)
    }

    @Test
    fun oracleScaleCrossProgramCaseSelectsAllAudibleMidAnd234OlderLowVoices() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(279, 22),
            logicalVoiceLimit = 301,
            realVoiceBudget = 256,
        )
        val low = IntArray(279) { index ->
            arbiter.triggerDynamic(0, index, 64, 0.11743039637804031, 27_140)
        }
        val mid = IntArray(22) { index ->
            arbiter.triggerDynamic(1, 1_000 + index, 64, 0.14132794737815857, 25_016)
        }

        arbiter.rebalance()

        assertEquals(301, arbiter.activeLogicalVoices)
        assertEquals(256, arbiter.activeRealVoices)
        assertEquals(45, arbiter.activeVirtualVoices)
        assertEquals(22, mid.count { arbiter.realSlotForDynamic(it) >= 0 })
        assertEquals(234, low.count { arbiter.realSlotForDynamic(it) >= 0 })
        assertTrue(low.take(234).all { arbiter.realSlotForDynamic(it) >= 0 })
        assertTrue(
            "equal low-program ties use the documented deterministic age fallback",
            low.drop(234).all {
                arbiter.realSlotForDynamic(it) == GlobalVoiceArbiter.NO_LOGICAL_VOICE
            },
        )
    }

    @Test
    fun virtualVoiceTimelineAdvancesAndPromotionRetainsPhaseAndGain() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(0),
            fixedInitiallyActive = booleanArrayOf(true),
            programLaneLimits = intArrayOf(2),
            logicalVoiceLimit = 4,
            realVoiceBudget = 1,
        )
        arbiter.setFixedAudibility(0, 0.01)
        val voice = arbiter.triggerDynamic(0, 3, 64, 0.8, frameCount = 4_800)
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.5)
        arbiter.rebalance()
        assertEquals(GlobalVoiceArbiter.NO_LOGICAL_VOICE, arbiter.realSlotForDynamic(voice))

        arbiter.advanceDynamicVoices(renderedFrames = 256, gainRetention = 0.5)
        assertEquals(384.0, arbiter.dynamicPhase(voice), 0.0)
        assertEquals(0.4, arbiter.dynamicGain(voice), 0.0000001)

        arbiter.deactivateFixed(0)
        arbiter.rebalance()
        assertTrue(arbiter.realSlotForDynamic(voice) >= 0)
        assertEquals("promotion must not rewind", 384.0, arbiter.dynamicPhase(voice), 0.0)
        assertEquals("promotion must retain the ramp", 0.4, arbiter.dynamicGain(voice), 0.0000001)
    }

    @Test
    fun scheduledDynamicVoiceAdvancesOnlyAfterItsExactInBurstStart() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(2),
        )
        val voice = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 3,
            priority = 64,
            initialAudibility = 0.8,
            frameCount = 4_800,
            startDelayFrames = 73,
        )
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.5)

        arbiter.advanceDynamicVoices(renderedFrames = 256, gainRetention = 1.0)

        assertEquals(0, arbiter.dynamicStartDelayFrames(voice))
        assertEquals((256 - 73) * 1.5, arbiter.dynamicPhase(voice), 0.0)
    }

    @Test
    fun fxxZeroTransitionFadesFor64FramesThenHoldsAt512AndResumesWithOverlap() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(4),
        )
        val retained = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 3,
            priority = 64,
            initialAudibility = 0.8,
            frameCount = 1_000,
            zeroGainVirtualization = holdVirtualization(
                retainFrames = 0,
                fadeFrames = 64,
                holdLatencyFrames = 512,
                pitch = ZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH,
            ),
        )
        arbiter.updateDynamicMix(retained, targetGain = 0.8, increment = 1.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(256.0, arbiter.dynamicPhase(retained), 0.0)

        arbiter.updateDynamicMix(
            retained, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        assertTrue(arbiter.dynamicExactZeroGated(retained))
        assertEquals(0.4, arbiter.dynamicGain(retained), 0.0000001)
        assertEquals(0, arbiter.dynamicZeroTransitionElapsedFrames(retained))
        assertEquals(0, arbiter.dynamicZeroTransitionRetainFrames(retained))
        assertEquals(64, arbiter.dynamicZeroTransitionFadeFrames(retained))

        // The source-bound transition boundary lies inside a writer burst.
        arbiter.advanceDynamicVoices(32, gainRetention = 0.5)
        assertEquals(288.0, arbiter.dynamicPhase(retained), 0.0)
        assertEquals(0.2, arbiter.dynamicGain(retained), 0.0000001)
        arbiter.advanceDynamicVoices(32, gainRetention = 0.5)
        assertEquals(320.0, arbiter.dynamicPhase(retained), 0.0)
        assertEquals(0.0, arbiter.dynamicGain(retained), 0.0)
        arbiter.advanceDynamicVoices(448, gainRetention = 0.5)
        assertEquals(768.0, arbiter.dynamicPhase(retained), 0.0)
        assertEquals(0, arbiter.dynamicPhaseAdvanceFrames(retained, 256))

        repeat(8) { arbiter.advanceDynamicVoices(256, gainRetention = 0.5) }
        assertTrue("held logical deadline must not expire", arbiter.isDynamicActive(retained))
        assertEquals(768.0, arbiter.dynamicPhase(retained), 0.0)

        arbiter.updateDynamicMix(retained, targetGain = 0.8, increment = 1.0)
        assertFalse(arbiter.dynamicExactZeroGated(retained))
        arbiter.advanceDynamicVoices(128, gainRetention = 0.5)
        assertEquals(896.0, arbiter.dynamicPhase(retained), 0.0)

        // The retained voice remains while region re-entry independently schedules an overlap.
        val overlap = arbiter.triggerDynamic(0, 3, 64, 0.8, frameCount = 1_000)
        assertTrue(overlap >= 0)
        assertEquals(2, arbiter.activeVoicesForProgram(0))
    }

    @Test
    fun ferrari812Retains514Fades55AndAdvances1536FramesBeforeHold() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(2),
        )
        val voice = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 25,
            priority = 64,
            initialAudibility = 0.8,
            frameCount = 4_000,
            zeroGainVirtualization = holdVirtualization(
                retainFrames = 514,
                fadeFrames = 55,
                holdLatencyFrames = 1_536,
                pitch = ZeroGainTransitionPitch.AUTHORED_STATIC_BAKED_PITCH,
            ),
        )
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(0.4, arbiter.dynamicGain(voice), 0.0000001)

        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        arbiter.advanceDynamicVoices(512, gainRetention = 0.5)
        assertEquals(0.4, arbiter.dynamicGain(voice), 0.0000001)
        arbiter.advanceDynamicVoices(2, gainRetention = 0.5)
        assertEquals(0.4, arbiter.dynamicGain(voice), 0.0000001)
        arbiter.advanceDynamicVoices(27, gainRetention = 0.5)
        assertEquals(0.4 * 28.0 / 55.0, arbiter.dynamicGain(voice), 0.0000001)
        arbiter.advanceDynamicVoices(28, gainRetention = 0.5)
        assertEquals(0.0, arbiter.dynamicGain(voice), 0.0)
        assertEquals(569, arbiter.dynamicZeroTransitionElapsedFrames(voice))

        arbiter.advanceDynamicVoices(967, gainRetention = 0.5)
        assertEquals(256.0 + 1_536.0, arbiter.dynamicPhase(voice), 0.0)
        assertEquals(0, arbiter.dynamicPhaseAdvanceFrames(voice, 256))
        repeat(4) { arbiter.advanceDynamicVoices(256, gainRetention = 0.5) }
        assertTrue(arbiter.isDynamicActive(voice))
        assertEquals(256.0 + 1_536.0, arbiter.dynamicPhase(voice), 0.0)
    }

    @Test
    fun positiveGainBeforeHoldCancelsThenNextExactZeroRestartsTheSourceCountdown() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(2),
        )
        val voice = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 3,
            priority = 64,
            initialAudibility = 0.8,
            frameCount = 4_000,
            zeroGainVirtualization = holdVirtualization(
                retainFrames = 0,
                fadeFrames = 64,
                holdLatencyFrames = 512,
                pitch = ZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH,
                restorePhaseOffsetFrames = -0.483,
            ),
        )
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)

        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(256, arbiter.dynamicZeroTransitionElapsedFrames(voice))
        assertEquals(512.0, arbiter.dynamicPhase(voice), 0.0)

        // Returning positive before the 512-frame hold boundary cancels this zero episode.
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        assertFalse(arbiter.dynamicExactZeroGated(voice))
        assertEquals(0, arbiter.dynamicZeroTransitionElapsedFrames(voice))
        assertEquals("pre-hold cancellation must not apply a restore offset",
            0.0, arbiter.consumeDynamicPhysicalRestorePhaseOffset(voice), 0.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(768.0, arbiter.dynamicPhase(voice), 0.0)

        // A later exact-zero crossing starts the FXX 64/512 transition and countdown anew.
        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        assertEquals(0, arbiter.dynamicZeroTransitionElapsedFrames(voice))
        arbiter.advanceDynamicVoices(512, gainRetention = 0.5)
        assertEquals(1_280.0, arbiter.dynamicPhase(voice), 0.0)
        assertEquals(512, arbiter.dynamicZeroTransitionElapsedFrames(voice))
        assertEquals(0, arbiter.dynamicPhaseAdvanceFrames(voice, 256))
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals("second episode must hold at its own source-bound boundary", 1_280.0,
            arbiter.dynamicPhase(voice), 0.0)
    }

    @Test
    fun heldSourceAppliesFractionalRestoreOffsetExactlyOncePerCompletedZeroEpisode() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(1),
        )
        val voice = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 18,
            priority = 64,
            initialAudibility = 0.8,
            frameCount = 4_000,
            zeroGainVirtualization = holdVirtualization(
                retainFrames = 0,
                fadeFrames = 64,
                holdLatencyFrames = 512,
                pitch = ZeroGainTransitionPitch.LIVE_CURRENT_RPM_PITCH,
                restorePhaseOffsetFrames = -0.483,
            ),
        )
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)

        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        arbiter.advanceDynamicVoices(512, gainRetention = 0.5)
        assertEquals(768.0, arbiter.dynamicPhase(voice), 0.0)

        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        assertEquals(767.517, arbiter.dynamicPhase(voice), 1e-12)
        assertEquals(-0.483, arbiter.consumeDynamicPhysicalRestorePhaseOffset(voice), 1e-12)
        assertEquals(0.0, arbiter.consumeDynamicPhysicalRestorePhaseOffset(voice), 0.0)
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        assertEquals("ordinary positive updates must not reapply the correction",
            767.517, arbiter.dynamicPhase(voice), 1e-12)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)

        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        arbiter.advanceDynamicVoices(512, gainRetention = 0.5)
        arbiter.updateDynamicMix(voice, targetGain = 0.8, increment = 1.0)
        assertEquals("a later completed hold owns one independent source-bound correction",
            1_535.034, arbiter.dynamicPhase(voice), 1e-12)
        assertEquals(-0.483, arbiter.consumeDynamicPhysicalRestorePhaseOffset(voice), 1e-12)
    }

    @Test
    fun certifiedAdvanceAtExactZeroGatesOutputStateButKeepsWriterTimeDeadline() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(2),
        )
        val voice = arbiter.triggerDynamic(
            programIndex = 0,
            trackIndex = 1,
            priority = 64,
            initialAudibility = 0.5,
            frameCount = 700,
            zeroGainVirtualization = ZeroGainVirtualizationSpec(
                kind = ZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO,
                phaseHoldLatencyWriterFrames = 0,
                transition = ZeroGainTransitionSpec.IMMEDIATE,
            ),
        )
        arbiter.updateDynamicMix(
            voice, targetGain = 0.0, increment = 1.0, authoredExactZero = true,
        )
        assertTrue(arbiter.dynamicExactZeroGated(voice))
        assertEquals(256, arbiter.dynamicPhaseAdvanceFrames(voice, 256))
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(256.0, arbiter.dynamicPhase(voice), 0.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertEquals(512.0, arbiter.dynamicPhase(voice), 0.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)
        assertFalse("advance policy must retain the ordinary natural end", arbiter.isDynamicActive(voice))
    }

    private fun holdVirtualization(
        retainFrames: Int,
        fadeFrames: Int,
        holdLatencyFrames: Int,
        pitch: ZeroGainTransitionPitch,
        restorePhaseOffsetFrames: Double = 0.0,
    ) = ZeroGainVirtualizationSpec(
        kind = ZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE,
        phaseHoldLatencyWriterFrames = holdLatencyFrames,
        transition = ZeroGainTransitionSpec(
            policy = ZeroGainTransitionPolicy.RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO,
            retainPreZeroGainWriterFrames = retainFrames,
            linearFadeWriterFrames = fadeFrames,
            pitchDuringTransition = pitch,
            phaseTreatment = if (restorePhaseOffsetFrames == 0.0) {
                ZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET
            } else {
                ZeroGainTransitionPhaseTreatment.APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET
            },
            restoreCapturePcmPhaseOffsetFrames = restorePhaseOffsetFrames,
        ),
    )

    @Test
    fun limiterOwnerRetirementCannotReviveOldOneShotGeneration() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(1),
            logicalVoiceLimit = 4,
            realVoiceBudget = 2,
        )
        val old = arbiter.triggerDynamic(0, 4, 64, 0.8, frameCount = 48_000)
        arbiter.updateDynamicMix(old, targetGain = 0.8, increment = 1.0)
        arbiter.advanceDynamicVoices(256, gainRetention = 0.5)

        arbiter.retireDynamicVoicesForProgram(0)
        val replacement = arbiter.triggerDynamic(0, 4, 64, 0.8, frameCount = 48_000)
        arbiter.updateDynamicMix(replacement, targetGain = 0.8, increment = 1.0)
        arbiter.updateDynamicMix(old, targetGain = 0.8, increment = 1.0)

        assertTrue(arbiter.isDynamicRetiring(old))
        assertEquals(0.0, arbiter.dynamicTargetGain(old), 0.0)
        assertEquals(0.8, arbiter.dynamicTargetGain(replacement), 0.0)
        assertEquals(1, arbiter.activeVoicesForProgram(0))
    }

    @Test
    fun logicalCeilingAdmitsNewVoiceAndStopsCurrentWorstWhileLaneOverflowRejects() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(64),
            fixedInitiallyActive = booleanArrayOf(true),
            programLaneLimits = intArrayOf(2, 2),
            logicalVoiceLimit = 3,
            realVoiceBudget = 1,
        )
        arbiter.setFixedAudibility(0, 0.01)
        arbiter.triggerDynamic(0, 1, 64, 0.2, 4_800)
        arbiter.triggerDynamic(0, 2, 64, 0.2, 4_800)

        val admitted = arbiter.triggerDynamic(1, 3, 255, 0.001, 4_800)
        assertTrue(admitted >= 0)
        assertFalse("oracle fixture stops the quieter existing loop at logical admission", arbiter.isFixedActive(0))
        assertEquals(1L, arbiter.stolenLogicalVoices)
        assertEquals(3, arbiter.activeLogicalVoices)

        assertEquals(
            GlobalVoiceArbiter.REJECTED,
            arbiter.triggerDynamic(0, 4, 64, 0.2, 4_800),
        )
        assertEquals(1L, arbiter.rejectedTriggers)
    }
}
