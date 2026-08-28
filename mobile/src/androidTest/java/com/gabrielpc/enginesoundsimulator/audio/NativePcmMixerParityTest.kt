package com.gabrielpc.enginesoundsimulator.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Debug
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePcmMixerParityTest {
    @Test
    fun oracleScaleGlobalArbitrationIsAllocationFreeAndWithinBurstDeadline() {
        val arbiter = GlobalVoiceArbiter(
            fixedVoicePriorities = intArrayOf(),
            fixedInitiallyActive = booleanArrayOf(),
            programLaneLimits = intArrayOf(279, 22),
            logicalVoiceLimit = 2_048,
            realVoiceBudget = 256,
        )
        val handles = IntArray(301) { index ->
            val mid = index >= 279
            arbiter.triggerDynamic(
                programIndex = if (mid) 1 else 0,
                trackIndex = index,
                priority = 64,
                initialAudibility = if (mid) 0.14132794737815857 else 0.11743039637804031,
                frameCount = Int.MAX_VALUE,
            )
        }
        fun burst() {
            var index = 0
            while (index < handles.size) {
                arbiter.updateDynamicMix(
                    handles[index],
                    targetGain = if (index >= 279) 0.14132794737815857 else 0.11743039637804031,
                    increment = 0.01,
                )
                index += 1
            }
            arbiter.rebalance()
            arbiter.advanceDynamicVoices(256, 0.99)
        }
        repeat(100, { burst() })
        val timings = LongArray(1_000)
        val allocationsBefore = Debug.getThreadAllocCount()
        repeat(timings.size) { index ->
            val start = System.nanoTime()
            burst()
            timings[index] = System.nanoTime() - start
        }
        val allocations = Debug.getThreadAllocCount() - allocationsBefore
        timings.sort()
        val p99 = timings[(timings.size * 99 / 100).coerceAtMost(timings.lastIndex)]
        Log.i(BENCHMARK_TAG, "301-logical global arbiter p99=${p99 / 1_000}us allocations=$allocations")
        assertTrue("global arbiter p99=${p99 / 1_000}us", p99 < 1_500_000L)
        assertEquals("steady-state global arbiter allocations", 0, allocations)
    }

    @Test
    fun virtualNativeLoopAdvancesAndPromotionKeepsItsTimeline() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            (sin(frame * 2.0 * PI / 137.0) * 9_000.0).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(listOf(source to 0), emptyList()).use { promoted ->
                NativePcmMixer.create(listOf(source to 0), emptyList()).use { reference ->
                    val silent = ShortArray(256 * 2)
                    val discarded = ShortArray(256 * 2)
                    promoted.loopTargets[0] = 0.4
                    promoted.loopIncrements[0] = 1.0
                    promoted.loopReal[0] = 0
                    reference.loopTargets[0] = 0.4
                    reference.loopIncrements[0] = 1.0
                    reference.loopReal[0] = 1
                    promoted.render(silent, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    reference.render(discarded, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    assertTrue(silent.all { it == 0.toShort() })

                    promoted.loopReal[0] = 1
                    val actual = ShortArray(256 * 2)
                    val expected = ShortArray(256 * 2)
                    promoted.render(actual, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    reference.render(expected, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    assertTrue(actual.contentEquals(expected))
                }
            }
        }
    }

    @Test
    fun multipleAuthoredRolesCanShareOneNativePcmClip() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            (sin((index / 2) * 2.0 * PI / 137.0) * 9_000.0).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val shared = NativePlanarPcmData(clip, 32, frames - 32)
            NativePcmMixer.create(
                loops = listOf(shared to 0, shared to 0),
                effects = listOf(shared to false),
            ).use { mixer ->
                mixer.loopTargets.fill(0.2)
                mixer.loopIncrements.fill(1.0)
                mixer.effectTargets[0] = 0.2
                mixer.effectIncrements[0] = 1.0
                mixer.effectTriggers[0] = 1
                val output = ShortArray(256 * 2)
                mixer.render(
                    output, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertTrue(output.any { it != 0.toShort() })
                assertEquals(1, mixer.effectActive[0])
            }
        }
    }

    @Test
    fun promotedDynamicNativeVoiceStartsAtRetainedLogicalPhaseAndGain() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            (sin(frame * 2.0 * PI / 149.0) * 9_000.0).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { promoted ->
                NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { reference ->
                    reference.dynamicEffectTargets[0] = 0.4
                    reference.dynamicEffectIncrements[0] = 1.0
                    reference.dynamicEffectCommands[0] = 1
                    reference.dynamicEffectStartPhases[0] = 0.0
                    reference.dynamicEffectStartGains[0] = 0.4
                    reference.render(
                        ShortArray(128 * 2), 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0,
                    )

                    promoted.dynamicEffectTargets[0] = 0.4
                    promoted.dynamicEffectIncrements[0] = 1.0
                    promoted.dynamicEffectCommands[0] = 1
                    promoted.dynamicEffectStartPhases[0] = 128.0
                    promoted.dynamicEffectStartGains[0] = 0.4
                    val actual = ShortArray(256 * 2)
                    val expected = ShortArray(256 * 2)
                    promoted.render(actual, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    reference.render(expected, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    assertTrue(actual.contentEquals(expected))
                }
            }
        }
    }

    @Test
    fun dynamicOneShotSlotsSharePcmAndFinishIndependently() {
        val frames = 1_024
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            (sin(frame * 2.0 * PI / 97.0) * 9_000.0).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(
                loops = emptyList(),
                effects = listOf(source to false),
                dynamicEffectCount = 2,
            ).use { mixer ->
                val output = ShortArray(256 * 2)
                mixer.dynamicEffectTargets[0] = 0.4
                mixer.dynamicEffectIncrements[0] = 1.0
                mixer.dynamicEffectCommands[0] = 1 // effect-template index zero plus one
                mixer.render(
                    output, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertEquals(1, mixer.dynamicEffectActive[0])
                assertEquals(0, mixer.dynamicEffectActive[1])
                assertTrue(output.any { it != 0.toShort() })

                mixer.dynamicEffectTargets[1] = 0.4
                mixer.dynamicEffectIncrements[1] = 2.0
                mixer.dynamicEffectCommands[1] = 1
                mixer.render(
                    output, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertEquals(1, mixer.dynamicEffectActive[0])
                assertEquals(1, mixer.dynamicEffectActive[1])

                repeat(4) {
                    mixer.render(
                        output, 256, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0,
                    )
                }
                assertEquals(0, mixer.dynamicEffectActive[0])
                assertEquals(0, mixer.dynamicEffectActive[1])
            }
        }
    }

    @Test
    fun dynamicStartOffsetIsSampleExactAllocationFreeAndWithinBurstDeadline() {
        val frames = 1_024
        val startFrame = 73
        val interleaved = ShortArray(frames * 2) { 10_000 }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { mixer ->
                val output = ShortArray(256 * 2)
                fun scheduledBurst() {
                    mixer.dynamicEffectTargets[0] = 1.0
                    mixer.dynamicEffectIncrements[0] = 1.0
                    mixer.dynamicEffectCommands[0] = 1
                    mixer.dynamicEffectStartOffsets[0] = startFrame
                    mixer.render(
                        output, 256, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0,
                    )
                }
                scheduledBurst()
                assertTrue(
                    "native voice leaked before exact frame offset",
                    (0 until startFrame * 2).all { output[it] == 0.toShort() },
                )
                assertTrue(
                    "native voice did not begin on its scheduled frame",
                    output[startFrame * 2] != 0.toShort(),
                )

                repeat(100) { scheduledBurst() }
                val timings = LongArray(1_000)
                val allocationsBefore = Debug.getThreadAllocCount()
                repeat(timings.size) { index ->
                    val start = System.nanoTime()
                    scheduledBurst()
                    timings[index] = System.nanoTime() - start
                }
                val allocations = Debug.getThreadAllocCount() - allocationsBefore
                timings.sort()
                val p99 = timings[(timings.size * 99 / 100).coerceAtMost(timings.lastIndex)]
                Log.i(
                    BENCHMARK_TAG,
                    "offset dynamic 256-frame p99=${p99 / 1_000}us allocations=$allocations",
                )
                assertEquals("scheduled native render allocations", 0, allocations)
                assertTrue("scheduled native p99=${p99 / 1_000}us", p99 < 1_500_000L)
            }
        }
    }

    @Test
    fun fxxZeroTransitionFades64FramesHoldsAt512AndResumesPhaseBitExactly() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            val channelScale = if (index and 1 == 0) 1.0 else -0.73
            (sin(frame * 2.0 * PI / 151.0 + frame * frame * 0.000001) *
                10_000.0 * channelScale).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { held ->
                held.dynamicEffectTargets[0] = 1.0
                held.dynamicEffectCommands[0] = 1
                held.dynamicEffectPhaseAdvanceFrames[0] = 256
                held.render(
                    ShortArray(256 * 2), 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )

                held.dynamicEffectZeroTransitionActive[0] = 1
                held.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                held.dynamicEffectZeroTransitionRetainFrames[0] = 0
                held.dynamicEffectZeroTransitionFadeFrames[0] = 64
                held.dynamicEffectZeroTransitionStartGains[0] = 1.0
                held.dynamicEffectPhaseAdvanceFrames[0] = 256
                val transition = ShortArray(256 * 2) { 123 }
                held.render(
                    transition, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                var maximumEnvelopeErrorLsb = 0
                repeat(64) { frame ->
                    val gain = (64 - frame).toDouble() / 64.0
                    val expectedLeft = expectedLinearSample(interleaved[(256 + frame) * 2], gain)
                    val expectedRight = expectedLinearSample(interleaved[(256 + frame) * 2 + 1], gain)
                    maximumEnvelopeErrorLsb = maxOf(
                        maximumEnvelopeErrorLsb,
                        abs(expectedLeft.toInt() - transition[frame * 2].toInt()),
                        abs(expectedRight.toInt() - transition[frame * 2 + 1].toInt()),
                    )
                    assertEquals(expectedLeft, transition[frame * 2])
                    assertEquals(expectedRight, transition[frame * 2 + 1])
                }
                assertTrue("FXX compact envelope exceeds the certified 1-LSB bound",
                    maximumEnvelopeErrorLsb <= 1)
                assertTrue(
                    "FXX must become exactly zero at writer frame 64",
                    (64 * 2 until transition.size).all { transition[it] == 0.toShort() },
                )
                assertEquals(1, held.dynamicEffectActive[0])

                held.dynamicEffectZeroTransitionElapsedFrames[0] = 256
                held.dynamicEffectPhaseAdvanceFrames[0] = 256
                val postTransition = ShortArray(256 * 2) { 123 }
                held.render(
                    postTransition, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertTrue(postTransition.all { sample -> sample == 0.toShort() })

                held.dynamicEffectZeroTransitionElapsedFrames[0] = 512
                held.dynamicEffectPhaseAdvanceFrames[0] = 0
                val heldSilence = ShortArray(256 * 2) { 123 }
                held.render(
                    heldSilence, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertTrue(heldSilence.all { sample -> sample == 0.toShort() })
                assertEquals("held voice must pause its natural end", 1, held.dynamicEffectActive[0])

                NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { reference ->
                    held.dynamicEffectZeroTransitionActive[0] = 0
                    held.dynamicEffectTargets[0] = 1.0
                    held.dynamicEffectPhaseAdvanceFrames[0] = 128

                    reference.dynamicEffectTargets[0] = 1.0
                    reference.dynamicEffectCommands[0] = 1
                    reference.dynamicEffectStartPhases[0] = 768.0
                    reference.dynamicEffectPhaseAdvanceFrames[0] = 128
                    val actual = ShortArray(128 * 2)
                    val expected = ShortArray(128 * 2)
                    held.render(
                        actual, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0,
                    )
                    reference.render(
                        expected, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0,
                    )
                    assertTrue(
                        "reaudibilization did not continue at the held decode phase",
                        actual.contentEquals(expected),
                    )
                }
            }
        }
    }

    @Test
    fun ferrari812ZeroTransitionRetains514Fades55AndHoldsAt1536WithinOneBurst() {
        val frames = 4_096
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            val channelScale = if (index and 1 == 0) 1.0 else -0.61
            (sin(frame * 2.0 * PI / 137.0 + frame * frame * 0.0000013) *
                9_000.0 * channelScale).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { held ->
                held.dynamicEffectTargets[0] = 1.0
                held.dynamicEffectCommands[0] = 1
                held.dynamicEffectPhaseAdvanceFrames[0] = 256
                held.render(
                    ShortArray(256 * 2), 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )

                held.dynamicEffectZeroTransitionActive[0] = 1
                held.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                held.dynamicEffectZeroTransitionRetainFrames[0] = 514
                held.dynamicEffectZeroTransitionFadeFrames[0] = 55
                held.dynamicEffectZeroTransitionStartGains[0] = 1.0
                held.dynamicEffectPhaseAdvanceFrames[0] = 600
                val transition = ShortArray(600 * 2) { 123 }
                held.render(
                    transition, 600, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                var maximumEnvelopeErrorLsb = 0
                repeat(600) { frame ->
                    val gain = when {
                        frame < 514 -> 1.0
                        frame < 569 -> (569 - frame).toDouble() / 55.0
                        else -> 0.0
                    }
                    val expectedLeft = expectedLinearSample(interleaved[(256 + frame) * 2], gain)
                    val expectedRight = expectedLinearSample(interleaved[(256 + frame) * 2 + 1], gain)
                    maximumEnvelopeErrorLsb = maxOf(
                        maximumEnvelopeErrorLsb,
                        abs(expectedLeft.toInt() - transition[frame * 2].toInt()),
                        abs(expectedRight.toInt() - transition[frame * 2 + 1].toInt()),
                    )
                    assertEquals(expectedLeft, transition[frame * 2])
                    assertEquals(expectedRight, transition[frame * 2 + 1])
                }
                assertTrue("812 compact envelope exceeds the certified 1-LSB bound",
                    maximumEnvelopeErrorLsb <= 1)
                assertTrue((569 * 2 until transition.size).all { transition[it] == 0.toShort() })

                held.dynamicEffectZeroTransitionElapsedFrames[0] = 600
                held.dynamicEffectPhaseAdvanceFrames[0] = 936
                held.render(
                    ShortArray(936 * 2), 936, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                held.dynamicEffectZeroTransitionElapsedFrames[0] = 1_536
                held.dynamicEffectPhaseAdvanceFrames[0] = 0
                val heldSilence = ShortArray(256 * 2) { 123 }
                held.render(
                    heldSilence, 256, 1.0, 1.0, 1.0, 1.0,
                    1.0, 1.0, 1.0, 1.0,
                )
                assertTrue(heldSilence.all { it == 0.toShort() })
                assertEquals(1, held.dynamicEffectActive[0])

                NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { reference ->
                    held.dynamicEffectZeroTransitionActive[0] = 0
                    held.dynamicEffectTargets[0] = 1.0
                    held.dynamicEffectPhaseAdvanceFrames[0] = 128
                    reference.dynamicEffectTargets[0] = 1.0
                    reference.dynamicEffectCommands[0] = 1
                    reference.dynamicEffectStartPhases[0] = 1_792.0
                    reference.dynamicEffectPhaseAdvanceFrames[0] = 128
                    val actual = ShortArray(128 * 2)
                    val expected = ShortArray(128 * 2)
                    held.render(actual, 128, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    reference.render(expected, 128, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    assertTrue(actual.contentEquals(expected))
                }
            }
        }
    }

    @Test
    fun heldNativeVoiceAppliesFractionalCapturePcmRestoreOffsetExactlyOnce() {
        val frames = 4_096
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            val channelScale = if (index and 1 == 0) 1.0 else -0.67
            (sin(frame * 2.0 * PI / 113.0 + frame * frame * 0.0000017) *
                10_000.0 * channelScale).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { restored ->
                NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { reference ->
                    restored.dynamicEffectTargets[0] = 1.0
                    restored.dynamicEffectCommands[0] = 1
                    restored.dynamicEffectStartPhases[0] = 1_000.0
                    restored.dynamicEffectStartGains[0] = 1.0
                    restored.dynamicEffectPhaseAdvanceFrames[0] = 0
                    restored.render(ShortArray(2), 1, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0)

                    reference.dynamicEffectTargets[0] = 1.0
                    reference.dynamicEffectCommands[0] = 1
                    reference.dynamicEffectStartPhases[0] = 999.517
                    reference.dynamicEffectStartGains[0] = 1.0
                    reference.dynamicEffectPhaseAdvanceFrames[0] = 0

                    restored.dynamicEffectRestorePhaseOffsets[0] = -0.483
                    val actualFirst = ShortArray(128 * 2)
                    val expectedFirst = ShortArray(128 * 2)
                    restored.render(actualFirst, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0)
                    reference.render(expectedFirst, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0)
                    assertTrue("native restore did not apply the signed fractional cursor offset",
                        actualFirst.contentEquals(expectedFirst))

                    // render() clears the one-shot offset command. Neither the Kotlin array nor the
                    // native cursor may apply the source correction again on ordinary positive audio.
                    val actualSecond = ShortArray(128 * 2)
                    val expectedSecond = ShortArray(128 * 2)
                    restored.render(actualSecond, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0)
                    reference.render(expectedSecond, 128, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0)
                    assertTrue("fractional restore offset was applied more than once",
                        actualSecond.contentEquals(expectedSecond))
                    assertEquals(0.0, restored.dynamicEffectRestorePhaseOffsets[0], 0.0)
                }
            }
        }
    }

    @Test
    fun nativeBriefPositiveReturnCancelsAndSecondZeroRestartsTransitionAllocationFree() {
        val frames = 4_096
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            (sin(frame * 2.0 * PI / 131.0) * 9_000.0).toInt().toShort()
        }
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val source = NativePlanarPcmData(clip, 0, frames)
            NativePcmMixer.create(emptyList(), listOf(source to false), 1).use { mixer ->
                val output = ShortArray(256 * 2)
                mixer.dynamicEffectTargets[0] = 1.0
                mixer.dynamicEffectCommands[0] = 1
                mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                mixer.dynamicEffectCommands[0] = 0

                mixer.dynamicEffectZeroTransitionActive[0] = 1
                mixer.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                mixer.dynamicEffectZeroTransitionRetainFrames[0] = 0
                mixer.dynamicEffectZeroTransitionFadeFrames[0] = 64
                mixer.dynamicEffectZeroTransitionStartGains[0] = 1.0
                mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                assertTrue((64 * 2 until output.size).all { output[it] == 0.toShort() })

                // This positive return occurs before the FXX 512-frame hold boundary.
                mixer.dynamicEffectZeroTransitionActive[0] = 0
                mixer.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                mixer.dynamicEffectTargets[0] = 1.0
                mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                assertTrue("ordinary nonzero output did not resume", output.any { it != 0.toShort() })

                // A second exact-zero crossing restarts the 64-frame fade from current phase.
                mixer.dynamicEffectZeroTransitionActive[0] = 1
                mixer.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                mixer.dynamicEffectZeroTransitionStartGains[0] = 1.0
                mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                repeat(64) { frame ->
                    val gain = (64 - frame).toDouble() / 64.0
                    assertEquals(
                        expectedLinearSample(interleaved[(768 + frame) * 2], gain),
                        output[frame * 2],
                    )
                }
                assertTrue((64 * 2 until output.size).all { output[it] == 0.toShort() })

                // Exercise the exact transition inner loop repeatedly under the audio-thread gate.
                repeat(100) {
                    mixer.dynamicEffectCommands[0] = 1
                    mixer.dynamicEffectStartPhases[0] = 0.0
                    mixer.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                    mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                    mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                }
                val timings = LongArray(1_000)
                val allocationsBefore = Debug.getThreadAllocCount()
                repeat(timings.size) { index ->
                    mixer.dynamicEffectCommands[0] = 1
                    mixer.dynamicEffectStartPhases[0] = 0.0
                    mixer.dynamicEffectZeroTransitionElapsedFrames[0] = 0
                    mixer.dynamicEffectPhaseAdvanceFrames[0] = 256
                    val start = System.nanoTime()
                    mixer.render(output, 256, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
                    timings[index] = System.nanoTime() - start
                }
                val allocations = Debug.getThreadAllocCount() - allocationsBefore
                timings.sort()
                val p99 = timings[(timings.size * 99 / 100).coerceAtMost(timings.lastIndex)]
                Log.i(
                    BENCHMARK_TAG,
                    "zero-transition 256-frame p99=${p99 / 1_000}us allocations=$allocations",
                )
                assertEquals("zero-transition native render allocations", 0, allocations)
                assertTrue("zero-transition p99=${p99 / 1_000}us", p99 < 1_500_000L)
            }
        }
    }

    @Test
    fun emptyMixerLoadsNativeLibraryWithoutPriorFlacDecode() {
        val output = ShortArray(256 * 2) { 123 }
        NativePcmMixer.create(emptyList(), emptyList()).use { mixer ->
            mixer.render(
                output = output,
                frameCount = 256,
                targetMaster = 1.0,
                targetProfileGain = 1.0,
                targetEnabled = 1.0,
                targetContinuous = 1.0,
                masterAlpha = 1.0,
                profileAlpha = 1.0,
                enabledAlpha = 1.0,
                layerAlpha = 1.0,
            )
        }
        assertTrue(output.all { it == 0.toShort() })
    }

    @Test
    fun nativeInstalledPackMatchesReferenceAndStaysInRange() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            (sin(frame * 2.0 * PI / 127.0) * 12_000.0).toInt().toShort()
        }
        val floats = Array(2) { channel ->
            FloatArray(frames) { frame -> interleaved[frame * 2 + channel] / 32_768f }
        }
        val profile = SILENT_CATALOG_PROFILE.copy(
            id = "native_parity",
            minimumRpm = 0.0,
            maximumRpm = 8_000.0,
            idleRpm = 1_000.0,
            limiterRpm = 8_000.0,
            layers = listOf(
                SampleLayerSpec(
                    id = "tone", assetName = "tone", role = SampleLayerRole.COAST,
                    startRpm = 0.0, endRpm = 8_000.0, autopitchRootRpm = 4_000.0,
                    baseGainDb = -12.0,
                ),
            ),
            effects = emptyList(),
            throttleOutputGainDb = null,
        )
        val reference = SampleEngineRenderer.fromDecoded(
            48_000, mapOf("tone" to PcmLoopData(floats, 48_000, 32, frames - 32)), profile,
        )
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val native = SampleEngineRenderer.fromDecoded(
                48_000,
                mapOf("tone" to NativePlanarPcmData(clip, 32, frames - 32)),
                profile,
            )
            val expected = ShortArray(256 * 2)
            val actual = ShortArray(256 * 2)
            val frame = EngineAudioFrame(rpm = 4_000.0, throttle = 0.4)
            repeat(20) {
                reference.render(frame, expected, 0.72)
                native.render(frame, actual, 0.72)
                assertTrue(actual.indices.maxOf { abs(actual[it].toInt() - expected[it].toInt()) } <= 3)
                assertTrue(actual.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
            }
            assertEquals(0L, native.diagnostics().overRangeSamples)
            native.closeNativeMixer()
        }
    }

    @Test
    fun nativeTurboEventMatchesReferenceWithLiveControlGainAndPitch() {
        val frames = 4_096
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            val channelScale = if (index and 1 == 0) 1.0 else -0.72
            (sin(frame * 2.0 * PI / 109.0) * 10_000.0 * channelScale).toInt().toShort()
        }
        val floats = Array(2) { channel ->
            FloatArray(frames) { frame -> interleaved[frame * 2 + channel] / 32_768f }
        }
        val effect = SampleEffectSpec(
            id = "native_turbo_event",
            control = SampleEffectControls.turbo,
            assetName = "native_turbo_event",
            trigger = SampleEffectTrigger.TURBO_EVENT,
            baseGainDb = -12.0,
            auditionable = false,
            polyphonicTemplate = true,
            softwareVoicePriority = GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY,
        )
        val leaf = OneShotTrackNodeSpec(
            id = "native_turbo_leaf",
            triggerChance = 1.0,
            effectId = effect.id,
            parameterGates = emptyList(),
            rpmAmplitudeCurve = null,
            throttleAmplitudeCurve = null,
            captureControlValues = listOf(
                OneShotControlValueSpec(OneShotGateControl.BOOST, 1.0),
            ),
            controlGainCurves = listOf(
                OneShotControlCurveSpec(
                    OneShotGateControl.BOOST,
                    AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.5, 0.75))),
                ),
            ),
            pitchAutomations = listOf(
                OneShotPitchAutomationSpec(
                    OneShotGateControl.BOOST,
                    captureSemitones = 0.0,
                    playbackRateCurve = AutomationCurve(
                        listOf(CurvePoint(0.0, 0.5), CurvePoint(1.5, 1.625)),
                    ),
                ),
            ),
            sourceVerificationPayloadSha256 = "a".repeat(64),
        )
        val program = OneShotProgramSpec(
            id = "native_turbo_program",
            trigger = SampleEffectTrigger.TURBO_EVENT,
            rootNodeIds = listOf(leaf.id),
            nodes = listOf(leaf),
            policy = OneShotTriggerPolicySpec(
                kind = OneShotPolicyKind.TURBO_EVENT_PROGRAM,
                minimumRpm = 0.0,
                maximumRpm = null,
                armPedal = null,
                firePedal = null,
                armBoost = null,
                initialPeakPedal = null,
                initialArmPedal = null,
                initialFirePedal = null,
                minimumArmSeconds = 0.0,
                cooldownSeconds = 0.0,
                periodHz = null,
                turboEvent = TurboEventProgramPolicySpec(
                    mode = TurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT,
                    placementMinimumBoost = 0.0,
                    placementMaximumBoost = 1.5,
                    includeMinimum = true,
                    includeMaximum = true,
                    timelineStartFrames = null,
                    timelinePeriodFrames = null,
                    coreProgram = true,
                ),
            ),
            softwareVoicePriority = GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY,
        )
        val profile = SILENT_CATALOG_PROFILE.copy(
            id = "native_turbo_event_parity",
            minimumRpm = 0.0,
            maximumRpm = 8_000.0,
            idleRpm = 1_000.0,
            limiterRpm = 8_000.0,
            layers = emptyList(),
            effects = listOf(effect),
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val reference = SampleEngineRenderer.fromDecoded(
            48_000,
            mapOf(effect.id to PcmLoopData(floats, 48_000)),
            profile,
        )
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val native = SampleEngineRenderer.fromDecoded(
                48_000,
                mapOf(effect.id to NativePlanarPcmData(clip, 0, frames)),
                profile,
            )
            val expected = ShortArray(256 * 2)
            val actual = ShortArray(256 * 2)
            val frame = EngineAudioFrame(
                rpm = 5_000.0,
                throttle = 0.5,
                enabledEffectMask = SampleEffectControls.turbo.bit,
            )
            repeat(10) {
                reference.render(frame, expected, 0.72)
                native.render(frame, actual, 0.72)
                assertTrue(
                    "native TURBO_EVENT diverged from the reference mixer",
                    actual.indices.maxOf { abs(actual[it].toInt() - expected[it].toInt()) } <= 3,
                )
            }
            assertEquals(1L, native.diagnostics().effectTriggers)
            assertEquals(0L, native.diagnostics().overRangeSamples)
            native.closeNativeMixer()
        }
    }

    @Test
    fun nativeStereoCrossfadeMatchesReferenceAcrossLoopWraps() {
        val frames = 2_048
        val interleaved = ShortArray(frames * 2) { index ->
            val frame = index / 2
            val sample = if (index and 1 == 0) {
                sin(frame * 2.0 * PI / 113.0) * 11_000.0
            } else {
                sin(frame * 2.0 * PI / 173.0 + 0.37) * 9_000.0
            }
            sample.toInt().toShort()
        }
        val floats = Array(2) { channel ->
            FloatArray(frames) { frame -> interleaved[frame * 2 + channel] / 32_768f }
        }
        val profile = SILENT_CATALOG_PROFILE.copy(
            id = "native_crossfade_parity",
            minimumRpm = 0.0,
            maximumRpm = 8_000.0,
            idleRpm = 1_000.0,
            limiterRpm = 8_000.0,
            layers = listOf(
                SampleLayerSpec(
                    id = "tone", assetName = "tone", role = SampleLayerRole.COAST,
                    startRpm = 0.0, endRpm = 8_000.0, autopitchRootRpm = 4_000.0,
                    baseGainDb = -12.0, loopCrossfadeFrames = 192,
                ),
            ),
            effects = emptyList(),
            throttleOutputGainDb = null,
        )
        val reference = SampleEngineRenderer.fromDecoded(
            48_000, mapOf("tone" to PcmLoopData(floats, 48_000, 96, frames - 96)), profile,
        )
        NativeFlacDecoder.testClip(interleaved).use { clip ->
            val native = SampleEngineRenderer.fromDecoded(
                48_000,
                mapOf("tone" to NativePlanarPcmData(clip, 96, frames - 96)),
                profile,
            )
            val expected = ShortArray(256 * 2)
            val actual = ShortArray(256 * 2)
            val frame = EngineAudioFrame(rpm = 5_500.0, throttle = 0.4)
            repeat(40) {
                reference.render(frame, expected, 0.72)
                native.render(frame, actual, 0.72)
                assertTrue(
                    "crossfade parity diverged after wrap",
                    actual.indices.maxOf { abs(actual[it].toInt() - expected[it].toInt()) } <= 3,
                )
            }
            assertTrue(native.diagnostics().loopWraps > 0)
            native.closeNativeMixer()
        }
    }

    @Test
    fun nativeHostProxyBenchmarkRecordsP99For256Frames() {
        // Runs on the Android test host/emulator ABI; this is deliberately not BYD hardware proof.
        val samples = ShortArray(4_096) { (sin(it * 0.03) * 8_000).toInt().toShort() }
        NativeFlacDecoder.testClip(samples).use { clip ->
            val data = NativePlanarPcmData(clip, 0, samples.size / 2)
            val profile = SILENT_CATALOG_PROFILE.copy(
                id = "native_benchmark", layers = listOf(
                    SampleLayerSpec(
                        id = "tone", assetName = "tone", role = SampleLayerRole.COAST,
                        startRpm = 0.0, endRpm = 9_000.0, autopitchRootRpm = 4_000.0, baseGainDb = -12.0,
                    ),
                ), effects = emptyList(), throttleOutputGainDb = null,
            )
            val renderer = SampleEngineRenderer.fromDecoded(48_000, mapOf("tone" to data), profile)
            val output = ShortArray(512)
            val timings = LongArray(1_000)
            repeat(100) { renderer.render(EngineAudioFrame(rpm = 4_000.0), output, 0.72) }
            val stableFrame = EngineAudioFrame(rpm = 4_000.0)
            val allocationsBefore = Debug.getThreadAllocCount()
            repeat(timings.size) { index ->
                val start = System.nanoTime()
                renderer.render(stableFrame, output, 0.72)
                timings[index] = System.nanoTime() - start
            }
            val renderAllocations = Debug.getThreadAllocCount() - allocationsBefore
            timings.sort()
            val p99 = timings[(timings.size * 99 / 100).coerceAtMost(timings.lastIndex)]
            Log.i(BENCHMARK_TAG, "1-track 256-frame p99=${p99 / 1_000}us allocations=$renderAllocations")
            assertTrue("native 256-frame p99=${p99 / 1_000}us", p99 < 1_500_000L)
            assertEquals("steady-state native render allocations", 0, renderAllocations)
            renderer.closeNativeMixer()
        }
    }

    @Test
    fun representativeMaximumTrackPackIsAllocationFreeAndMeetsRenderDeadline() {
        val sourceFrames = 4_096
        val interleaved = ShortArray(sourceFrames * 2) { index ->
            val frame = index / 2
            val channelScale = if (index and 1 == 0) 1.0 else -0.8
            (sin(frame * 0.031) * 8_000.0 * channelScale).toInt().toShort()
        }
        val clips = (0 until MAX_PACK_TRACKS).map { NativeFlacDecoder.testClip(interleaved) }
        try {
            val decoded = LinkedHashMap<String, PlanarPcmData>(MAX_PACK_TRACKS)
            val layers = (0 until MAX_PACK_LOOPS).map { index ->
                val id = "loop_$index"
                decoded[id] = NativePlanarPcmData(clips[index], 64, sourceFrames - 64)
                SampleLayerSpec(
                    id = id,
                    assetName = id,
                    role = if (index == 0) SampleLayerRole.IDLE else SampleLayerRole.COAST,
                    startRpm = 0.0,
                    endRpm = 9_000.0,
                    autopitchRootRpm = 3_500.0 + index * 40.0,
                    baseGainDb = -30.0,
                )
            }
            val effects = (0 until MAX_PACK_EFFECTS).map { localIndex ->
                val clipIndex = MAX_PACK_LOOPS + localIndex
                val id = "effect_$localIndex"
                decoded[id] = NativePlanarPcmData(clips[clipIndex], 64, sourceFrames - 64)
                SampleEffectSpec(
                    id = id,
                    control = SampleEffectControls.turbo,
                    assetName = id,
                    trigger = SampleEffectTrigger.CONTINUOUS_LOOP,
                    baseGainDb = -30.0,
                )
            }
            val profile = SILENT_CATALOG_PROFILE.copy(
                id = "maximum_track_benchmark",
                maximumRpm = 9_000.0,
                limiterRpm = 8_500.0,
                layers = layers,
                effects = effects,
                throttleOutputGainDb = null,
            )
            val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, profile)
            val output = ShortArray(256 * 2)
            val stableFrame = EngineAudioFrame(
                rpm = 4_500.0,
                throttle = 0.45,
                enabledEffectMask = SampleEffectControls.turbo.bit,
            )
            repeat(150) { renderer.render(stableFrame, output, 0.72) }

            val timings = LongArray(1_000)
            val allocationsBefore = Debug.getThreadAllocCount()
            repeat(timings.size) { index ->
                val start = System.nanoTime()
                renderer.render(stableFrame, output, 0.72)
                timings[index] = System.nanoTime() - start
            }
            val allocations = Debug.getThreadAllocCount() - allocationsBefore
            timings.sort()
            val p99 = timings[(timings.size * 99 / 100).coerceAtMost(timings.lastIndex)]
            Log.i(BENCHMARK_TAG, "$MAX_PACK_TRACKS-track 256-frame p99=${p99 / 1_000}us allocations=$allocations")
            assertTrue("$MAX_PACK_TRACKS-track native p99=${p99 / 1_000}us", p99 < 1_500_000L)
            assertEquals("steady-state maximum-track render allocations", 0, allocations)
            assertEquals(0L, renderer.diagnostics().overRangeSamples)
            renderer.closeNativeMixer()
        } finally {
            clips.forEach(NativePcm16Clip::close)
        }
    }

    private fun expectedLinearSample(sample: Short, gain: Double): Short =
        ((sample.toDouble() / 32768.0) * gain * 0.65 * 32767.0)
            .coerceIn(-32768.0, 32767.0)
            .toInt()
            .toShort()

    private companion object {
        // The largest profiles ported into the original Android prototype used 24 FMOD-style
        // layers. Eight simultaneous core-effect lanes exercise the native effect path too.
        const val MAX_PACK_LOOPS = 24
        const val MAX_PACK_EFFECTS = 8
        const val MAX_PACK_TRACKS = MAX_PACK_LOOPS + MAX_PACK_EFFECTS
        const val BENCHMARK_TAG = "NativeMixerBenchmark"
    }
}
