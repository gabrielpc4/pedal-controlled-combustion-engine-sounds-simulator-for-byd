package com.gabrielpc.enginesoundsimulator.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.tuning.AudioTuning
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a short, rapidly changing RPM/throttle/gear sweep through every
 * installed pack and leaves one measurement line per selectable car in logcat.
 *
 * The companion installer is intentionally separate from the dashboard APK,
 * so this test skips cleanly when packs have not been installed yet. The
 * field-validation run installs all packs first and therefore exercises every
 * profile, including verified shared-pack aliases.
 */
@RunWith(AndroidJUnit4::class)
class CarAudioRuntimeValidationTest {
    @Test
    fun everyInstalledCarSurvivesRapidRpmAndThrottleChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = EngineAudioAssetResolver(context)
        val requiredPackIds = EngineSampleProfiles.all.map { it.audioPackId }.toSet()
        val installedPackIds = AudioPackStore(context.filesDir).installedPackIds()
        assumeTrue(
            "Install all audio packs before running the per-car runtime sweep",
            installedPackIds.containsAll(requiredPackIds),
        )

        EngineSampleProfiles.all.forEach { profile ->
            EngineSoundPerspective.entries.forEach { perspective ->
                validateProfilePerspective(profile, perspective, resolver)
            }
        }
    }

    private fun validateProfilePerspective(
        profile: EngineSampleProfile,
        perspective: EngineSoundPerspective,
        resolver: EngineAudioAssetResolver,
    ) {
        val renderer = SampleEngineRenderer.load(
            assetSource = resolver.sourceFor(profile),
            outputSampleRate = profile.outputSampleRate,
            profile = profile,
            perspective = perspective,
            loadOnlyProgram = true,
            primaryLayerSource = PrimaryEngineLayerSource.LOAD,
        )
        val output = ShortArray(BLOCK_FRAMES * 2)
        var nonSilentBlocks = 0
        var peak = 0
        var sumSquares = 0.0
        var sampleCount = 0
        var maxFrameStep = 0
        var previousLeft: Int? = null
        val program = profile.program(perspective)
        val hasNativeOneShot = program.effects.any { effect -> !effect.trigger.isContinuousLoop() }

        repeat(BLOCK_COUNT) { blockIndex ->
            val progress = blockIndex.toDouble() / (BLOCK_COUNT - 1).coerceAtLeast(1)
            val rpm = profile.idleRpm + (profile.maximumRpm - profile.idleRpm) * progress
            val throttle = when {
                blockIndex < 20 -> 0.88
                blockIndex in 20..24 -> 0.0
                else -> THROTTLE_PATTERN[blockIndex % THROTTLE_PATTERN.size]
            }
            renderer.render(
                EngineAudioFrame(
                    rpm = rpm,
                    throttle = throttle,
                    tuning = AudioTuning(masterGain = 0.72),
                    loadOnlyProgram = true,
                    primaryLayerSource = PrimaryEngineLayerSource.LOAD,
                    throttleLiftEffectsEnabled = true,
                    popsAndBangsEnabled = false,
                    sharedShiftSoundsEnabled = false,
                    shiftSerial = (blockIndex / 6).toLong(),
                    shiftDirection = if ((blockIndex / 6) % 2 == 0) 1 else -1,
                    transmissionEnabled = true,
                    turboSoundsEnabled = true,
                ),
                output,
                gain = 1.0,
            )

            var blockPeak = 0
            var frameIndex = 0
            while (frameIndex < BLOCK_FRAMES) {
                val left = output[frameIndex * 2].toInt()
                val right = output[frameIndex * 2 + 1].toInt()
                val framePeak = maxOf(abs(left), abs(right))
                blockPeak = maxOf(blockPeak, framePeak)
                peak = maxOf(peak, framePeak)
                sumSquares += left.toDouble() * left + right.toDouble() * right
                sampleCount += 2
                previousLeft?.let { maxFrameStep = maxOf(maxFrameStep, abs(left - it)) }
                previousLeft = left
                frameIndex += 1
            }
            if (blockPeak > 0) nonSilentBlocks += 1
        }

        val rms = sqrt(sumSquares / sampleCount) / Short.MAX_VALUE
        val diagnostics = renderer.diagnostics()
        Log.i(
            TAG,
            "car=${profile.id} perspective=$perspective pack=${profile.audioPackId} blocks=$BLOCK_COUNT " +
                "nonSilentBlocks=$nonSilentBlocks peak=${peak / Short.MAX_VALUE.toDouble()} " +
                "rms=$rms maxFrameStep=$maxFrameStep declaredEffects=${program.effects.map { it.id }} " +
                "effectTriggers=${diagnostics.effectTriggers} activeEffects=${diagnostics.activeEffects}",
        )
        assertTrue("${profile.id} $perspective rendered no audible samples", nonSilentBlocks > BLOCK_COUNT / 2)
        assertTrue("${profile.id} $perspective rendered invalid peak", peak <= Short.MAX_VALUE)
        if (hasNativeOneShot) {
            assertTrue("${profile.id} $perspective never triggered an authored one-shot", diagnostics.effectTriggers > 0)
        }
    }

    private companion object {
        const val TAG = "CarAudioRuntimeValidation"
        const val BLOCK_FRAMES = 2_560
        const val BLOCK_COUNT = 36
        val THROTTLE_PATTERN = doubleArrayOf(0.0, 0.35, 0.95, 0.45, 1.0, 0.12, 0.78, 0.0)
    }
}
