package com.gabrielpc.enginesoundsimulator.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FmodNativeBridgeTest {
    @Test(timeout = 180_000L)
    fun loadsAndSwitchesEveryProfileWithExactlyItsAllowlistedGraph() {
        assertEquals(
            "The native instrumentation expectations must cover every selectable profile.",
            PROFILE_EXPECTATIONS.map(ProfileExpectation::id),
            FmodCarProfiles.all.map(FmodCarProfile::id),
        )

        withOpenBridge { bridge ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val duplicateOpen = FmodNativeBridge.open(context)
            assertFalse("A second process-wide FMOD runtime must be rejected.", duplicateOpen.succeeded)
            assertTrue(
                duplicateOpen.error.orEmpty(),
                duplicateOpen.error.orEmpty().contains("Only one FMOD runtime"),
            )

            PROFILE_EXPECTATIONS.forEachIndexed { index, expected ->
                assertSuccess(bridge.loadBanks(expected.id))

                val diagnostics = bridge.diagnostics()
                assertCommonLoadedDiagnostics(diagnostics, expected)
                if (expected.id == FmodCarProfiles.AVENTADOR_SV_ID) {
                    val pluginEvidence = requireNotNull(COMPATIBILITY_VALUE_PATTERN.find(diagnostics)) {
                        "Aventador diagnostics omitted compatibility DSP values:\n$diagnostics"
                    }.value
                    Log.i(TEST_LOG_TAG, "Aventador compatibility DSP evidence: $pluginEvidence")
                }
                assertEquals(
                    "Native FMOD instantiated an unexpected event for ${expected.id}.\n$diagnostics",
                    expected.events.values.toList(),
                    eventPathsFrom(diagnostics),
                )

                val profile = requireNotNull(FmodCarProfiles.findOrNull(expected.id))
                assertEquals(
                    "Kotlin and native capability allowlists disagree for ${expected.id}.",
                    expected.events.keys,
                    profile.events.keys,
                )
                assertEquals(
                    "Kotlin and native event paths disagree for ${expected.id}.",
                    expected.events,
                    profile.events.mapValues { it.value.path },
                )

                assertSuccess(
                    bridge.update(
                        controlBuffer(
                            profile = profile,
                            audioEnabled = true,
                            serial = index.toLong() + 1L,
                        ),
                    ),
                )
                assertTrue(
                    bridge.diagnostics(),
                    bridge.diagnostics().contains("excludedInstantiations=0"),
                )
            }
        }
    }

    @Test(timeout = 600_000L)
    fun rendersFiniteAudiblePcmAndCallbacksForEveryAllowlistedEvent() {
        val failures = mutableListOf<String>()
        withOpenBridge { bridge ->
            PROFILE_EXPECTATIONS.forEach { expected ->
                assertSuccess(bridge.loadBanks(expected.id))

                val validation = bridge.validateRenderedAudio()
                val failureContext = buildString {
                    append("profile=").append(expected.id)
                    append(", error=").append(validation.error)
                    append(", passed=").append(validation.passed)
                    append(", eventKinds=").append(validation.eventResults.map { it.kind })
                }
                fun requireValidation(condition: Boolean, detail: String) {
                    if (!condition) failures += "${expected.id}: $detail; $failureContext"
                }
                requireValidation(validation.error == null, "native error=${validation.error}")
                requireValidation(validation.profileId == expected.id, "wrong profile id")
                requireValidation(
                    validation.outputMode ==
                        "NOSOUND_NRT+STREAM_FROM_UPDATE/512x4/synchronous",
                    "wrong output mode ${validation.outputMode}",
                )
                requireValidation(
                    validation.excludedInstantiationCount == 0,
                    "instantiated ${validation.excludedInstantiationCount} excluded events",
                )
                requireValidation(validation.passed, "top-level validation did not pass")

                val resultKinds = validation.eventResults.map { it.kind }
                val resultsByKind = validation.eventResults.associateBy { it.kind }
                requireValidation(
                    resultKinds == expected.events.keys.toList(),
                    "checked event kinds $resultKinds, expected ${expected.events.keys}",
                )
                for ((kind, expectedPath) in expected.events) {
                    val result = resultsByKind[kind]
                    if (result == null) {
                        failures += "${expected.id}: missing $kind rendered-audio check"
                        continue
                    }
                    val eventContext = buildString {
                        append(failureContext)
                        append(", event=").append(kind)
                        append(", path=").append(result.eventPath)
                        append(", passed=").append(result.passed)
                        append(", starts=").append(result.instanceStarts)
                        append(", sounds=").append(result.soundPlayedCallbacks)
                        append(", frames=").append(result.renderedFrames)
                        append(", peakDbfs=").append(result.peakDbfs)
                        append(", rmsDbfs=").append(result.rmsDbfs)
                        append(", nonFinite=").append(result.nonFiniteSamples)
                        append(", detail=").append(result.detail)
                        append(", soundNames=").append(result.soundNames.take(8))
                    }
                    fun requireEvent(condition: Boolean, detail: String) {
                        if (!condition) failures += "${expected.id}/$kind: $detail; $eventContext"
                    }
                    requireEvent(result.eventPath == expectedPath, "wrong event path")
                    requireEvent(result.passed, "native event validation did not pass")
                    requireEvent(result.instanceStarts > 0, "no instance-start callback")
                    requireEvent(result.soundPlayedCallbacks > 0, "no SOUND_PLAYED callback")
                    requireEvent(result.renderedFrames > 0L, "no PCM frames rendered")
                    requireEvent(result.nonFiniteSamples == 0L, "rendered non-finite PCM")
                    requireEvent(result.peakDbfs.isFinite(), "non-finite peak dBFS")
                    requireEvent(result.rmsDbfs.isFinite(), "non-finite RMS dBFS")
                    requireEvent(result.peakDbfs > SILENCE_DBFS, "silent peak")
                    requireEvent(result.rmsDbfs > SILENCE_DBFS, "silent RMS")
                    requireEvent(result.soundNames.isNotEmpty(), "no played sound names")
                    requireEvent(result.soundNames.all(String::isNotBlank), "blank played sound name")
                    expected.callbackTokenGroups[kind].orEmpty().forEach { tokenGroup ->
                        val matchingNames = tokenGroup.matchingNames(result.soundNames)
                        Log.i(
                            TEST_LOG_TAG,
                            "${expected.id}/$kind '${tokenGroup.label}' callback evidence: " +
                                matchingNames.distinct().take(MAX_LOGGED_MATCHING_NAMES),
                        )
                        requireEvent(
                            matchingNames.isNotEmpty(),
                            "missing callback group '${tokenGroup.label}'; expected " +
                                "${tokenGroup.alternatives}, observed=" +
                                result.soundNames.distinct().take(MAX_REPORTED_SOUND_NAMES),
                        )
                    }
                    if (kind == FmodEventKind.ENGINE) {
                        expected.embeddedEngineEvidence.forEach { evidence ->
                            requireEvent(
                                result.detail.contains(evidence),
                                "missing embedded-transition evidence '$evidence'",
                            )
                        }
                    }
                }
            }
        }
        assertTrue(
            "Rendered FMOD validation failures:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test(timeout = 180_000L)
    fun delayedLimiterSerialEdgesAreQueuedAndDeliveredExactlyOnce() {
        withOpenBridge { bridge ->
            val profile = FmodCarProfiles.skylineR34
            assertSuccess(bridge.loadBanks(profile.id))

            assertSuccess(
                bridge.update(
                    controlBuffer(
                        profile = profile,
                        audioEnabled = true,
                        serial = 1L,
                        limiterSerial = 1L,
                        limiterDecaySeconds = 0f,
                    ),
                ),
            )
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 1L, delivered = 1L, pending = 0L,
                phase = "zero-hold", zeroHold = 2, cooldown = 7,
            )

            // Three source pulses arrived while Android did not service the JNI worker.
            // The current pulse remains at zero for three total worker ticks before rearming.
            assertSuccess(
                bridge.update(
                    controlBuffer(
                        profile = profile,
                        audioEnabled = true,
                        serial = 1L,
                        limiterSerial = 4L,
                        limiterDecaySeconds = 0.01f,
                    ),
                ),
            )
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 1L, pending = 3L,
                phase = "zero-hold", zeroHold = 1, cooldown = 6,
            )

            fun tickLimiter() {
                assertSuccess(
                    bridge.update(
                        controlBuffer(
                            profile = profile,
                            audioEnabled = true,
                            serial = 1L,
                            limiterSerial = 4L,
                            limiterDecaySeconds = 0.01f,
                        ),
                    ),
                )
            }

            // Third zero tick: the next update must rearm, but no queued pulse may start early.
            tickLimiter()
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 1L, pending = 3L,
                phase = "rearm", zeroHold = 0, cooldown = 5,
            )
            tickLimiter()
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 1L, pending = 3L,
                phase = "ready", zeroHold = 0, cooldown = 4,
            )

            // Finish the eight-tick period, then prove exactly one queued edge starts at tick 8.
            repeat(4) { tickLimiter() }
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 1L, pending = 3L,
                phase = "ready", zeroHold = 0, cooldown = 0,
            )
            tickLimiter()
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 2L, pending = 2L,
                phase = "zero-hold", zeroHold = 2, cooldown = 7,
            )

            // Each remaining queued edge must start exactly eight worker ticks later.
            repeat(7) { tickLimiter() }
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 2L, pending = 2L,
                phase = "ready", zeroHold = 0, cooldown = 0,
            )
            tickLimiter()
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 3L, pending = 1L,
                phase = "zero-hold", zeroHold = 2, cooldown = 7,
            )
            repeat(7) { tickLimiter() }
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 3L, pending = 1L,
                phase = "ready", zeroHold = 0, cooldown = 0,
            )
            tickLimiter()
            assertLimiterDiagnostics(
                bridge.diagnostics(), accepted = 4L, delivered = 4L, pending = 0L,
                phase = "zero-hold", zeroHold = 2, cooldown = 7,
            )
        }
    }

    @Test(timeout = 180_000L)
    fun realOutputLifecycleAndRepeatedOpenSwitchUpdateReleaseCyclesRemainHealthy() {
        repeat(2) { cycle ->
            withOpenBridge { bridge ->
                val first = PROFILE_EXPECTATIONS[cycle]
                val second = PROFILE_EXPECTATIONS[cycle + 1]

                listOf(first, second).forEachIndexed { switchIndex, expected ->
                    assertSuccess(bridge.loadBanks(expected.id))
                    val profile = requireNotNull(FmodCarProfiles.findOrNull(expected.id))
                    val serial = (cycle * 10 + switchIndex + 1).toLong()
                    assertSuccess(
                        bridge.update(
                            controlBuffer(
                                profile = profile,
                                audioEnabled = true,
                                serial = serial,
                            ),
                        ),
                    )
                    assertTrue(bridge.diagnostics(), bridge.diagnostics().contains("output=device"))
                }

                assertSuccess(bridge.suspendMixer())
                assertTrue(bridge.diagnostics(), bridge.diagnostics().contains("suspended=true"))
                assertSuccess(bridge.resumeMixer())
                assertTrue(bridge.diagnostics(), bridge.diagnostics().contains("suspended=false"))

                val finalProfile = requireNotNull(FmodCarProfiles.findOrNull(second.id))
                assertSuccess(
                    bridge.update(
                        controlBuffer(
                            profile = finalProfile,
                            audioEnabled = false,
                            serial = 20L + cycle,
                        ),
                    ),
                )
            }
        }
    }

    private fun assertCommonLoadedDiagnostics(
        diagnostics: String,
        expected: ProfileExpectation,
    ) {
        listOf(
            "FMOD 1.10.11",
            "output=device/48000Hz stereo",
            "dsp=64x4",
            "studioUpdate=synchronous-400Hz/1.333ms-mixer",
            "initialized=true",
            "loaded=true",
            "plugins=FMOD Distance Filter,FMOD Gain,FMOD Distortion",
            "compatibility=distance-pass-nearfield+gain-db-ramp+distortion-hard-clip",
            "validation=bank-guid+exact-profile+allowlisted-guids+paths+parameters+NRT-PCM",
            "samples=preloaded",
            "excludedInstantiations=0",
            "profile=${expected.id}",
            "banks=common.strings.bank>common.bank>${expected.bankFileName}",
        ).forEach { required ->
            assertTrue(
                "Missing '$required' from diagnostics for ${expected.id}:\n$diagnostics",
                diagnostics.contains(required),
            )
        }
    }

    private fun assertLimiterDiagnostics(
        diagnostics: String,
        accepted: Long,
        delivered: Long,
        pending: Long,
        phase: String,
        zeroHold: Int,
        cooldown: Int,
    ) {
        assertTrue(
            diagnostics,
            diagnostics.contains(
                "limiterEdges=accepted:$accepted/delivered:$delivered/" +
                    "pending:$pending/phase:$phase/zeroHold:$zeroHold/cooldown:$cooldown",
            ),
        )
    }

    private fun eventPathsFrom(diagnostics: String): List<String> {
        val value = diagnostics.substringAfter("; events=", missingDelimiterValue = "")
            .substringBefore(';')
        assertTrue("Diagnostics did not include an events field:\n$diagnostics", value.isNotBlank())
        return value.split(',').filter(String::isNotBlank)
    }

    private fun controlBuffer(
        profile: FmodCarProfile,
        audioEnabled: Boolean,
        serial: Long,
        limiterSerial: Long = serial,
        limiterDecaySeconds: Float = 0.02f,
    ): ByteBuffer {
        val buffer = FmodNativeBridge.allocateControlBuffer()
        val layout = FmodNativeBridge.ControlBufferLayout
        buffer.putInt(
            layout.ENABLED_MASK_OFFSET,
            if (audioEnabled) layout.ALL_EVENTS_ENABLED else 0,
        )
        buffer.putFloat(layout.RPM_OFFSET, profile.idleRpm.toFloat().coerceAtLeast(1f))
        buffer.putFloat(layout.ENGINE_THROTTLE_OFFSET, if (audioEnabled) 0.65f else 0f)
        buffer.putFloat(layout.BOOST_OFFSET, if (audioEnabled) 0.25f else 0f)
        buffer.putFloat(layout.BOV_OFFSET, if (audioEnabled) 1f else 0f)
        buffer.putFloat(layout.BOV_DECAY_OFFSET, 10f)
        buffer.putFloat(layout.LIMITER_DECAY_OFFSET, limiterDecaySeconds)
        buffer.putFloat(layout.MASTER_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.ENGINE_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.TURBO_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.LIMITER_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.SHIFT_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.BACKFIRE_GAIN_OFFSET, 1f)
        buffer.putInt(layout.SHIFT_DIRECTION_OFFSET, if (audioEnabled) 1 else 0)
        buffer.putLong(layout.SHIFT_SERIAL_OFFSET, serial)
        buffer.putLong(layout.LIMITER_SERIAL_OFFSET, limiterSerial)
        buffer.putLong(layout.BOV_SERIAL_OFFSET, serial)
        buffer.putLong(layout.BACKFIRE_SERIAL_OFFSET, serial)
        buffer.putFloat(
            layout.DRIVETRAIN_SPEED_OFFSET,
            if (audioEnabled) {
                profile.transmissionSpeedMaximumRadPerSecond?.times(0.5)?.toFloat() ?: 0f
            } else {
                0f
            },
        )
        buffer.putFloat(layout.TRANSMISSION_THROTTLE_OFFSET, if (audioEnabled) 0.65f else 0f)
        buffer.putFloat(layout.TRANSMISSION_GAIN_OFFSET, 1f)
        return buffer
    }

    private fun withOpenBridge(block: (FmodNativeBridge) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opened = FmodNativeBridge.open(context)
        assertTrue(opened.error, opened.succeeded)
        val bridge = requireNotNull(opened.bridge)
        try {
            block(bridge)
        } finally {
            bridge.close()
        }
    }

    private fun assertSuccess(result: FmodNativeCallResult) {
        val failure = result as? FmodNativeCallResult.Failure
        assertTrue(failure?.detail, result === FmodNativeCallResult.Success)
    }

    private data class ProfileExpectation(
        val id: String,
        val bankFileName: String,
        val events: Map<FmodEventKind, String>,
        val callbackTokenGroups: Map<FmodEventKind, List<CallbackTokenGroup>> = emptyMap(),
        val embeddedEngineEvidence: List<String> = emptyList(),
    )

    private data class CallbackTokenGroup(
        val label: String,
        val alternatives: List<String>,
        val exactName: Boolean = false,
    ) {
        fun matchingNames(soundNames: List<String>): List<String> = soundNames.filter { soundName ->
            alternatives.any { alternative ->
                if (exactName) {
                    soundName.equals(alternative, ignoreCase = true) ||
                        soundName.substringBeforeLast('.', soundName)
                            .equals(alternative, ignoreCase = true)
                } else {
                    soundName.contains(alternative, ignoreCase = true)
                }
            }
        }
    }

    private companion object {
        const val SILENCE_DBFS = -119.0
        const val MAX_LOGGED_MATCHING_NAMES = 8
        const val MAX_REPORTED_SOUND_NAMES = 48
        const val TEST_LOG_TAG = "FmodNativeBridgeTest"
        val COMPATIBILITY_VALUE_PATTERN = Regex(
            "gainSets=\\d+/lastDb=[^/;]+/invertSets=\\d+; " +
                "distortionSets=\\d+/lastLevel=[^;]+",
        )

        val PROFILE_EXPECTATIONS = listOf(
            ProfileExpectation(
                id = "nissan_skyline_r34_cabin",
                bankFileName = "ks_nissan_skyline_r34.bank",
                events = linkedMapOf(
                    FmodEventKind.ENGINE to
                        "event:/cars/ks_nissan_skyline_r34/engine_int",
                    FmodEventKind.TURBO to
                        "event:/cars/ks_nissan_skyline_r34/turbo",
                    FmodEventKind.LIMITER to
                        "event:/cars/ks_nissan_skyline_r34/limiter",
                    FmodEventKind.SHIFTS to
                        "event:/cars/ks_nissan_skyline_r34/gear_int",
                    FmodEventKind.BACKFIRE to
                        "event:/cars/ks_nissan_skyline_r34/backfire_int",
                ),
                callbackTokenGroups = mapOf(
                    FmodEventKind.TURBO to listOf(
                        callbackGroup("turbo/blow-off", "s1_turbo", "rb26_bf"),
                    ),
                    FmodEventKind.LIMITER to listOf(
                        callbackGroup("limiter pop", "s1_pop"),
                    ),
                    FmodEventKind.SHIFTS to listOf(
                        callbackGroup("gear change", "gearup"),
                    ),
                    FmodEventKind.BACKFIRE to listOf(
                        callbackGroup("RB26 backfire", "rb26det_pop"),
                    ),
                ),
            ),
            ProfileExpectation(
                id = "lamborghini_huracan_trofeo_evo2_cabin",
                bankFileName = "fx_lamborghini_huracan_trofeo_evo2.bank",
                events = linkedMapOf(
                    FmodEventKind.ENGINE to
                        "event:/cars/fx_lamborghini_huracan_trofeo_evo2/engine_int",
                    FmodEventKind.SHIFTS to
                        "event:/cars/fx_lamborghini_huracan_trofeo_evo2/gear_int",
                    FmodEventKind.BACKFIRE to
                        "event:/cars/fx_lamborghini_huracan_trofeo_evo2/backfire_ext",
                    FmodEventKind.TRANSMISSION to
                        "event:/cars/fx_lamborghini_huracan_trofeo_evo2/transmission",
                ),
                callbackTokenGroups = mapOf(
                    FmodEventKind.ENGINE to listOf(
                        callbackGroup("embedded limiter", "hur_lim"),
                    ),
                    FmodEventKind.SHIFTS to listOf(
                        callbackGroup(
                            "gear change",
                            "hur_1st",
                            "hur_comp",
                            "vettegt3_gearchange",
                        ),
                    ),
                    FmodEventKind.BACKFIRE to listOf(
                        callbackGroup("exterior backfire", "488_ex_backfire"),
                    ),
                    FmodEventKind.TRANSMISSION to listOf(
                        callbackGroup("transmission", "hur_gear", "gearbox"),
                    ),
                ),
                embeddedEngineEvidence = listOf(
                    "callback scope=event-start identity (not firing proof)",
                    "exactTrace=below-limit>ramp-to-limit>latched;throttle=1",
                    "targetPcmEvidence=true",
                ),
            ),
            ProfileExpectation(
                id = "lamborghini_aventador_sv_cabin",
                bankFileName = "tr_lamborghini_aventador_sv.bank",
                events = linkedMapOf(
                    FmodEventKind.ENGINE to
                        "event:/cars/tr_lamborghini_aventador_sv/engine_int",
                    FmodEventKind.TRANSMISSION to
                        "event:/cars/tr_lamborghini_aventador_sv/transmission",
                ),
                callbackTokenGroups = mapOf(
                    FmodEventKind.ENGINE to listOf(
                        callbackGroup("embedded gear change", "gear_changing_cabin"),
                        callbackGroup(
                            "embedded backfire",
                            "throttlefart",
                            "gintanisvjboom",
                        ),
                        callbackGroup(
                            "high-RPM engine region",
                            "aventadorintacc8294",
                            "powercraftaventadorextaccveryhigh",
                            "gintanisvjextaccsavagehigh",
                        ),
                    ),
                    FmodEventKind.TRANSMISSION to listOf(
                        callbackGroup("transmission", "transmission"),
                    ),
                ),
                embeddedEngineEvidence = listOf(
                    "callback scope=event-start identity (not firing proof)",
                    "exactTrace=below-limit>ramp-to-limit>latched;throttle=1",
                    "exactTrace=400Hz/80ms-upshift;gear-swap=38%;simulation-tau=24ms;" +
                        "presentation-tau=7.5ms;throttle=1",
                    "exactTrace=rpm-held;throttle=1>0-edge",
                    "callback scope=isolated live throttle edge",
                    "targetPcmEvidence=true",
                ),
            ),
            ProfileExpectation(
                id = "ks_alfa_romeo_4c",
                bankFileName = "ks_alfa_romeo_4c.bank",
                events = linkedMapOf(
                    FmodEventKind.ENGINE to
                        "event:/cars/ks_alfa_romeo_4c/engine_int",
                    FmodEventKind.TURBO to
                        "event:/cars/ks_alfa_romeo_4c/turbo",
                    FmodEventKind.LIMITER to
                        "event:/cars/ks_alfa_romeo_4c/limiter",
                    FmodEventKind.SHIFTS to
                        "event:/cars/ks_alfa_romeo_4c/gear_int",
                    FmodEventKind.BACKFIRE to
                        "event:/cars/ks_alfa_romeo_4c/backfire_int",
                ),
                callbackTokenGroups = mapOf(
                    FmodEventKind.TURBO to listOf(
                        callbackGroup("turbo", "turbo"),
                    ),
                    FmodEventKind.LIMITER to listOf(
                        callbackGroup("limiter", "500_limiter"),
                    ),
                    FmodEventKind.SHIFTS to listOf(
                        exactCallbackName("authored shift sample", "2"),
                    ),
                    FmodEventKind.BACKFIRE to listOf(
                        callbackGroup("backfire", "backfire_"),
                    ),
                ),
            ),
            ProfileExpectation(
                id = "zesty_toyota_supra_mk4_shuto_street",
                bankFileName = "zesty_toyota_supra_mk4_shuto_street.bank",
                events = linkedMapOf(
                    FmodEventKind.ENGINE to
                        "event:/cars/zesty_toyota_supra_mk4_shuto_street/engine_int",
                    FmodEventKind.TURBO to
                        "event:/cars/zesty_toyota_supra_mk4_shuto_street/turbo",
                    FmodEventKind.SHIFTS to
                        "event:/cars/zesty_toyota_supra_mk4_shuto_street/gear_int",
                    FmodEventKind.BACKFIRE to
                        "event:/cars/zesty_toyota_supra_mk4_shuto_street/backfire_int",
                ),
                callbackTokenGroups = mapOf(
                    FmodEventKind.ENGINE to listOf(
                        callbackGroup("ignition", "ignition"),
                        callbackGroup("embedded limiter", "limiter"),
                        callbackGroup("shutdown", "shutdown"),
                    ),
                    FmodEventKind.TURBO to listOf(
                        callbackGroup("blow-off/flutter", "bov", "flutter"),
                        callbackGroup("spool/turbo", "spool", "turbo"),
                    ),
                    FmodEventKind.SHIFTS to listOf(
                        callbackGroup("race gear shift", "veh_gear_shift_race"),
                    ),
                    FmodEventKind.BACKFIRE to listOf(
                        callbackGroup("backfire", "bf"),
                    ),
                ),
                embeddedEngineEvidence = listOf(
                    "callback scope=event-start identity (not firing proof)",
                    "exactTrace=below-limit>ramp-to-limit>latched;throttle=1",
                    "targetPcmEvidence=true",
                ),
            ),
        )

        fun callbackGroup(label: String, vararg alternatives: String) = CallbackTokenGroup(
            label = label,
            alternatives = alternatives.toList(),
        )

        fun exactCallbackName(label: String, name: String) = CallbackTokenGroup(
            label = label,
            alternatives = listOf(name),
            exactName = true,
        )
    }
}
