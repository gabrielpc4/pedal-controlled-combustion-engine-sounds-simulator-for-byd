package com.gabrielpc.enginesoundsimulator.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every installed source bank with the same native FMOD bridge used by
 * the dashboard. The short parameter sweep proves event discovery, direct
 * FMOD rendering, allowed auxiliary events, and rapid continuous RPM updates
 * without synthesizing or decoding any WAV data.
 */
@RunWith(AndroidJUnit4::class)
class CarAudioRuntimeValidationTest {
    @Test
    fun everyInstalledBankSurvivesRapidContinuousRpmAndThrottleChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = FmodBankResolver(context)
        val requiredPackIds = FmodBankProfiles.requiredPackIds
        val installedPackIds = FmodBankStore(context.filesDir).installedPackIds()
        assumeTrue(
            "Install all FMOD banks before running the per-car runtime sweep",
            installedPackIds.containsAll(requiredPackIds),
        )

        FmodBankProfiles.all.forEach { profile ->
            EngineSoundPerspective.entries.forEach { perspective ->
                validateProfilePerspective(context, profile, perspective, resolver)
            }
        }
    }

    private fun validateProfilePerspective(
        context: android.content.Context,
        profile: FmodBankProfile,
        perspective: EngineSoundPerspective,
        resolver: FmodBankResolver,
    ) {
        val bridge = NativeFmodBankBridge()
        org.fmod.FMOD.init(context)
        try {
            assertNull(
                "${profile.id} $perspective failed to open",
                resolver.bankFiles(profile).let { banks -> bridge.open(
                    commonStringsBankPath = banks.commonStrings.absolutePath,
                    commonBankPath = banks.common.absolutePath,
                    carBankPath = banks.car.absolutePath,
                    perspective = perspective.ordinal,
                    source = PrimaryEngineLayerSource.BOTH.nativeValue,
                ) },
            )
            val activeEvents = bridge.activeEventNames().toSet()
            assertTrue(
                "${profile.id} $perspective has no permitted engine event",
                "engine_int" in activeEvents || "engine_ext" in activeEvents,
            )

            repeat(FRAME_COUNT) { frameIndex ->
                val progress = frameIndex.toDouble() / (FRAME_COUNT - 1).coerceAtLeast(1)
                val rpm = profile.idleRpm + (profile.maximumRpm - profile.idleRpm) * progress
                val throttle = THROTTLE_PATTERN[frameIndex % THROTTLE_PATTERN.size]
                assertNull(
                    "${profile.id} $perspective failed at continuous frame $frameIndex",
                    bridge.update(
                        rpm = rpm.toFloat(),
                        throttle = throttle,
                        masterGain = 0.72f,
                        loadGain = 1.0f,
                        coastGain = 1.0f,
                        transmissionGain = 1.0f,
                        turboGain = 1.0f,
                        limiterGain = 1.0f,
                        shiftGain = 1.0f,
                        overrunGain = 1.0f,
                        boost = (frameIndex % 10) / 9.0f,
                        bovDecay = if (throttle < 0.1f) 0.85f else 0.0f,
                        shiftSerial = (frameIndex / 7).toLong(),
                        shiftDirection = if ((frameIndex / 7) % 2 == 0) 1 else -1,
                        triggerOverrun = throttle < 0.1f && frameIndex > 6,
                    ),
                )
            }
            Log.i(
                TAG,
                "car=${profile.id} perspective=$perspective bank=${profile.bankPackId} " +
                    "frames=$FRAME_COUNT activeEvents=${activeEvents.sorted()}",
            )
        } finally {
            bridge.close()
            org.fmod.FMOD.close()
        }
    }

    private companion object {
        const val TAG = "CarAudioRuntimeValidation"
        const val FRAME_COUNT = 40
        val THROTTLE_PATTERN = floatArrayOf(0.0f, 0.35f, 0.95f, 0.45f, 1.0f, 0.12f, 0.78f, 0.0f)
    }
}
