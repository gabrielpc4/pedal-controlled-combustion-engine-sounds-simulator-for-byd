package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysics
import com.gabrielpc.enginesoundsimulator.simulation.nativeFmodSpatialCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the exact native-bank path used by the dashboard. */
@RunWith(AndroidJUnit4::class)
class CarAudioRuntimeValidationTest {
    @Test
    fun disabledShiftAndBackfireNeverCreateOwnedSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = FmodBankProfiles.default
        val resolver = FmodBankResolver(context)
        assumeAllPacksInstalled(context, setOf(
            FmodBankProfiles.commonStringsPackId,
            FmodBankProfiles.commonPackId,
            profile.bankPackId,
        ))

        val physics = resolver.physics(profile)
        withOpenBridge(context, profile, physics, resolver) { bridge ->
            repeat(30) { index ->
                assertNull(
                    bridge.update(
                        dt = FIXED_STEP_SECONDS,
                        rpm = 6_200f,
                        drivetrainSpeed = 120f,
                        masterGain = 0.72f,
                        perspective = EngineSoundPerspective.CABIN.ordinal,
                        boost = 0.75f,
                        bov = 0.8f,
                        bovDecay = 0f,
                        limiterPulse = false,
                        shiftStarted = true,
                        shiftDirection = if (index % 2 == 0) 1 else -1,
                        shiftRejected = true,
                        backfireTriggered = true,
                        tractionActive = false,
                        tractionPulse = false,
                        shiftSoundsEnabled = false,
                        shiftGain = 1f,
                        popsAndBangsEnabled = false,
                        popsAndBangsGain = 1f,
                        transmissionEnabled = true,
                        transmissionGain = 1f,
                        turboEnabled = true,
                        turboGain = 1f,
                    ),
                )
                Thread.sleep(3L)
            }

            val sources = parseNativeVoiceSnapshots(bridge.voiceSnapshots())
            assertTrue("Engine must expose at least one real source", sources.any { it.eventName == "engine_int" })
            assertFalse(sources.any { it.eventName in SHIFT_EVENTS })
            assertFalse(sources.any { it.eventName in BACKFIRE_EVENTS })
            assertTrue("FMOD source meters must not mirror a fixed master percent", sources.map { it.audibilityPercent }.distinct().size > 1)
        }
    }

    @Test
    fun everyInstalledBankSurvivesContinuousPhysicsAndPerspectiveChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = FmodBankResolver(context)
        assumeAllPacksInstalled(context, FmodBankProfiles.requiredPackIds)

        FmodBankProfiles.all.forEach { profile ->
            val physics = resolver.physics(profile)
            withOpenBridge(context, profile, physics, resolver) { bridge ->
                EngineSoundPerspective.entries.forEach { perspective ->
                    repeat(FRAMES_PER_PERSPECTIVE) { frameIndex ->
                        val progress = frameIndex.toDouble() / (FRAMES_PER_PERSPECTIVE - 1).coerceAtLeast(1)
                        val rpm = physics.engine.idleRpm +
                            (physics.engine.limiterRpm - physics.engine.idleRpm) * progress
                        assertNull(
                            "${profile.id} $perspective failed at frame $frameIndex",
                            bridge.update(
                                dt = FIXED_STEP_SECONDS,
                                rpm = rpm.toFloat(),
                                drivetrainSpeed = (progress * 210.0).toFloat(),
                                masterGain = 0.72f,
                                perspective = perspective.ordinal,
                                boost = progress.toFloat(),
                                bov = if (frameIndex == FRAMES_PER_PERSPECTIVE / 2) 1f else 0f,
                                bovDecay = (frameIndex * FIXED_STEP_SECONDS).coerceAtMost(10f),
                                limiterPulse = frameIndex == FRAMES_PER_PERSPECTIVE - 1,
                                shiftStarted = false,
                                shiftDirection = 0,
                                shiftRejected = false,
                                backfireTriggered = false,
                                tractionActive = false,
                                tractionPulse = false,
                                shiftSoundsEnabled = false,
                                shiftGain = 1f,
                                popsAndBangsEnabled = false,
                                popsAndBangsGain = 1f,
                                transmissionEnabled = true,
                                transmissionGain = 1f,
                                turboEnabled = true,
                                turboGain = 1f,
                            ),
                        )
                    }
                }

                val sources = parseNativeVoiceSnapshots(bridge.voiceSnapshots())
                sources.forEach { source ->
                    assertTrue(source.id.contains('\u001e'))
                    assertEquals(source.eventName, source.eventPath.substringAfterLast('/').lowercase())
                    assertTrue(source.audibility in 0.0..1.0)
                    assertTrue(source.routeGain >= 0.0)
                }
                assertFalse(sources.any { it.eventName in SHIFT_EVENTS || it.eventName in BACKFIRE_EVENTS })
                Log.i(TAG, "car=${profile.id} sources=${sources.size} events=${sources.map { it.eventName }.distinct()}")
            }
        }
    }

    private fun withOpenBridge(
        context: Context,
        profile: FmodBankProfile,
        physics: AssettoPhysics,
        resolver: FmodBankResolver,
        action: (NativeFmodBankBridge) -> Unit,
    ) {
        val bridge = NativeFmodBankBridge()
        val focus = requestMediaFocus(context)
        org.fmod.FMOD.init(context)
        try {
            assertNull(
                "${profile.id} bank failed to open",
                resolver.bankFiles(profile).let { banks ->
                    bridge.open(
                        commonStringsBankPath = banks.commonStrings.absolutePath,
                        commonBankPath = banks.common.absolutePath,
                        carBankPath = banks.car.absolutePath,
                        perspective = EngineSoundPerspective.CABIN.ordinal,
                        hasTurbo = physics.engine.turbos.isNotEmpty(),
                        spatial = physics.nativeFmodSpatialCoordinates(),
                    )
                },
            )
            action(bridge)
        } finally {
            bridge.close()
            org.fmod.FMOD.close()
            abandonMediaFocus(focus)
        }
    }

    private fun assumeAllPacksInstalled(context: Context, required: Set<String>) {
        val installed = FmodBankStore(context.filesDir).installedPackIds()
        assumeTrue("Install all required v2 FMOD packs before this test", installed.containsAll(required))
    }

    @Suppress("DEPRECATION")
    private fun requestMediaFocus(context: Context): Pair<AudioManager, AudioManager.OnAudioFocusChangeListener> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { }
        assertEquals(
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
        const val FIXED_STEP_SECONDS = 0.003f
        const val FRAMES_PER_PERSPECTIVE = 32
        val SHIFT_EVENTS = setOf("gear_int", "gear_ext", "gear_grind")
        val BACKFIRE_EVENTS = setOf("backfire_int", "backfire_ext")
    }
}
