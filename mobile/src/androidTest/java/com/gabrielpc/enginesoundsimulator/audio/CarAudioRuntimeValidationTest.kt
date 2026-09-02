package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
    /**
     * The default car is the fastest way to catch a regression where a disabled
     * override is merely hidden in the UI while its native event is still started.
     *
     * This deliberately submits shift serials and an overrun trigger. Both events
     * must remain at the native meter floor because the user disabled their
     * overrides for this car.
     */
    @Test
    fun huracanDisabledOverridesNeverContributeToTheNativeMix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = FmodBankProfiles.default
        val resolver = FmodBankResolver(context)
        val installedPackIds = FmodBankStore(context.filesDir).installedPackIds()
        assumeTrue(
            "Install the Huracan FMOD bank before running the override validation",
            installedPackIds.containsAll(
                setOf(
                    FmodBankProfiles.commonStringsPackId,
                    FmodBankProfiles.commonPackId,
                    profile.bankPackId,
                ),
            ),
        )

        val bridge = NativeFmodBankBridge()
        val focus = requestMediaFocus(context)
        org.fmod.FMOD.init(context)
        try {
            assertNull(
                "Huracan cabin bank failed to open",
                resolver.bankFiles(profile).let { banks ->
                    bridge.open(
                        commonStringsBankPath = banks.commonStrings.absolutePath,
                        commonBankPath = banks.common.absolutePath,
                        carBankPath = banks.car.absolutePath,
                        perspective = EngineSoundPerspective.CABIN.ordinal,
                        source = PrimaryEngineLayerSource.LOAD.nativeValue,
                    )
                },
            )

            runDisabledOverrideFrames(bridge, masterGain = 0.72f, shiftSerialOffset = 0)

            val meters = bridge.outputMeters()
            assertEquals(FmodNativeMeterTrackIds.size, meters.size)
            assertTrue(
                "The audible engine event must report post-FMOD output, meters=${meters.contentToString()}",
                meters[METER_ENGINE_LOAD] > METER_FLOOR_DB + METER_TOLERANCE_DB,
            )
            assertEquals(
                "Disabled gear override must not start or contribute audio, meters=${meters.contentToString()}",
                METER_FLOOR_DB,
                meters[METER_GEAR],
                METER_TOLERANCE_DB,
            )
            assertEquals(
                "Disabled pops-and-bangs override must not start or contribute audio, meters=${meters.contentToString()}",
                METER_FLOOR_DB,
                meters[METER_OVERRUN],
                METER_TOLERANCE_DB,
            )

            runDisabledOverrideFrames(bridge, masterGain = 0.36f, shiftSerialOffset = METER_FRAME_COUNT)
            val quieterEngineDb = bridge.outputMeters()[METER_ENGINE_LOAD]
            assertTrue(
                "Reducing master gain must lower the measured engine output, before=${meters[METER_ENGINE_LOAD]} after=$quieterEngineDb",
                quieterEngineDb < meters[METER_ENGINE_LOAD] - MINIMUM_MASTER_GAIN_DROP_DB,
            )
        } finally {
            bridge.close()
            org.fmod.FMOD.close()
            abandonMediaFocus(focus)
        }
    }

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
        val focus = requestMediaFocus(context)
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
                        limiterDecay = 10.0f,
                        // This is an engine-and-powertrain sweep, not an override
                        // audition. Keep both optional one-shots off for every car.
                        shiftGain = 0.0f,
                        overrunGain = 0.0f,
                        boost = (frameIndex % 10) / 9.0f,
                        bovDecay = if (throttle < 0.1f) 0.85f else 0.0f,
                        shiftSerial = 0L,
                        shiftDirection = 0,
                        triggerOverrun = false,
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
            abandonMediaFocus(focus)
        }
    }

    private fun runDisabledOverrideFrames(
        bridge: NativeFmodBankBridge,
        masterGain: Float,
        shiftSerialOffset: Int,
    ) {
        repeat(METER_FRAME_COUNT) { frameIndex ->
            assertNull(
                "Huracan disabled-override frame $frameIndex failed",
                bridge.update(
                    rpm = 6_200f,
                    throttle = 0.92f,
                    masterGain = masterGain,
                    loadGain = 1.0f,
                    coastGain = 0.0f,
                    transmissionGain = 1.0f,
                    turboGain = 1.0f,
                    limiterGain = 1.0f,
                    limiterDecay = 10.0f,
                    shiftGain = 0.0f,
                    overrunGain = 0.0f,
                    boost = 0.75f,
                    bovDecay = 0.0f,
                    shiftSerial = (shiftSerialOffset + frameIndex + 1).toLong(),
                    shiftDirection = if (frameIndex % 2 == 0) 1 else -1,
                    triggerOverrun = true,
                ),
            )
            Thread.sleep(METER_SETTLE_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestMediaFocus(context: Context): Pair<AudioManager, AudioManager.OnAudioFocusChangeListener> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { }
        assertEquals(
            "FMOD validation could not obtain media audio focus",
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN),
        )
        return manager to listener
    }

    @Suppress("DEPRECATION")
    private fun abandonMediaFocus(focus: Pair<AudioManager, AudioManager.OnAudioFocusChangeListener>) {
        focus.first.abandonAudioFocus(focus.second)
    }

    private companion object {
        const val TAG = "CarAudioRuntimeValidation"
        const val FRAME_COUNT = 40
        const val METER_ENGINE_LOAD = 0
        const val METER_GEAR = 5
        const val METER_OVERRUN = 6
        const val METER_FLOOR_DB = -80.0f
        const val METER_TOLERANCE_DB = 0.1f
        const val METER_SETTLE_MS = 16L
        const val METER_FRAME_COUNT = 12
        const val MINIMUM_MASTER_GAIN_DROP_DB = 3.0f
        val THROTTLE_PATTERN = floatArrayOf(0.0f, 0.35f, 0.95f, 0.45f, 1.0f, 0.12f, 0.78f, 0.0f)
    }
}
