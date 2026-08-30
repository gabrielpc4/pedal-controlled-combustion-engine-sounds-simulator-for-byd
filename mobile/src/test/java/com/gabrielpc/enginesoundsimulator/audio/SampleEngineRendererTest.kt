package com.gabrielpc.enginesoundsimulator.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleEngineRendererTest {
    private val profile = EngineSampleProfiles.default

    @Test
    fun everySelectableCarHasACompleteDistinctSampleProfile() {
        assertEquals(3, EngineSampleProfiles.all.size)
        assertEquals(3, EngineSampleProfiles.all.map { it.id }.distinct().size)
        assertEquals(3, EngineSampleProfiles.all.map { it.previewAssetName }.distinct().size)

        EngineSampleProfiles.all.forEach { candidate ->
            assertTrue("${candidate.id} has no layers", candidate.layers.isNotEmpty())
            assertTrue(candidate.requiredAssets.containsAll(candidate.layers.map { it.assetName }))
            assertTrue(
                candidate.requiredAssets.containsAll(
                    candidate.effects.flatMap { effect -> effect.allAssetNames },
                ),
            )
            assertTrue(candidate.outputSampleRate == 44_100 || candidate.outputSampleRate == 48_000)
            for (rpm in candidate.idleRpm.toInt()..candidate.limiterRpm.toInt() step 25) {
                val onLoad = candidate.layers.maxOf { it.gainAt(rpm.toDouble(), 1.0, loadOnlyProgram = false) }
                val lifted = candidate.layers.maxOf { it.gainAt(rpm.toDouble(), 0.0, loadOnlyProgram = false) }
                assertTrue("${candidate.id} has no full-load voice at $rpm", onLoad > 0.0001)
                assertTrue("${candidate.id} has no lift-off voice at $rpm", lifted > 0.0001)
            }
        }
        val huracan = EngineSampleProfiles.find("lamborghini_huracan_trofeo_evo2_cabin")
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        assertEquals(44_100, huracan.outputSampleRate)
        assertEquals(48_000, huracan.playbackSampleRate)
        assertEquals(48_000, aventador.outputSampleRate)
        assertEquals(48_000, aventador.playbackSampleRate)
        EngineSampleProfiles.all.filterNot { it.id == huracan.id }.forEach { candidate ->
            assertEquals("${candidate.id} playback rate changed", candidate.outputSampleRate, candidate.playbackSampleRate)
        }
        val skyline = EngineSampleProfiles.find("nissan_skyline_r34_cabin")
        assertEquals(44_100, skyline.outputSampleRate)
        assertFalse(skyline.appliesLoadOnlyProgram(loadOnlyProgram = true))
        assertEquals(skyline.layers.size, skyline.loopLayersForLoad(loadOnlyProgram = true).size)
        assertEquals(skyline.requiredAssets, skyline.requiredAssetsForLoad(loadOnlyProgram = true))
        assertEquals(17, skyline.layers.size)
        assertTrue(
            skyline.layers
                .filter { layer -> layer.role == SampleLayerRole.LOAD }
                .all { layer -> layer.assetName.contains("_in_") },
        )
        assertTrue(skyline.layers.none { it.role == SampleLayerRole.COAST })
        assertTrue(skyline.effects.any { it.trigger == SampleEffectTrigger.TURBO_LOOP })
        assertTrue(skyline.effects.any { it.trigger == SampleEffectTrigger.TURBO_FLUTTER })
        assertTrue(skyline.effects.any { it.trigger == SampleEffectTrigger.TURBO_DUMP })
        assertTrue(skyline.layers.any { it.role == SampleLayerRole.LIMITER })
        assertTrue(skyline.layers.any { it.role == SampleLayerRole.TEXTURE })
        assertEquals(3, skyline.layers.count { it.assetName == "sin5.wav" })
        val loadedMid = skyline.layers.first { it.id == "skyline_load_mid" }
        assertEquals(4_780.0, loadedMid.autopitchRootRpm ?: 0.0, 0.0)
        assertTrue(loadedMid.gainAt(4_400.0, 1.0, loadOnlyProgram = false) > 0.1)
        assertEquals(6, skyline.gearRatios.size)
    }

    @Test
    fun fmodCurveShapesRetainTheAuthoredTransitionInsteadOfLinearizingIt() {
        val shaped = AutomationCurve(
            listOf(
                CurvePoint(0.0, 0.0, shape = 0.75),
                CurvePoint(1.0, 1.0),
            ),
        )
        val linear = AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0)))

        assertTrue(shaped.valueAt(0.5) < linear.valueAt(0.5))
        assertEquals(0.0, shaped.valueAt(0.0), 0.0)
        assertEquals(1.0, shaped.valueAt(1.0), 0.0)
    }

    @Test
    fun skylineLiftRetainsTheInteriorLoadCharacter() {
        val skyline = EngineSampleProfiles.find("nissan_skyline_r34_cabin")
        val rpm = 7_200.0
        val acceleratingLoad = skyline.layers
            .filter { it.role == SampleLayerRole.LOAD }
            .sumOf { it.gainAt(rpm, throttle = 1.0, loadOnlyProgram = false) }
        val liftingLoad = skyline.layers
            .filter { it.role == SampleLayerRole.LOAD }
            .sumOf { it.gainAt(rpm, throttle = 0.0, loadOnlyProgram = false) }

        assertTrue(liftingLoad >= acceleratingLoad * 0.60)
    }

    @Test
    fun profileContainsRecoveredContinuousEngineEvent() {
        assertEquals(24, profile.layers.size)
        assertEquals(27, profile.requiredAssets.size)
        assertEquals(3, profile.effects.size)
        assertEquals(7, profile.gearRatios.size)
        assertEquals(10_000.0, profile.maximumRpm, 0.0)
        assertEquals(8_350.0, profile.limiterRpm, 0.0)
        assertEquals(1.0, profile.outputGainAt(0.0), 0.0001)
        assertEquals(0.75, profile.throttleOutputGainDb?.valueAt(1.0) ?: 0.0, 0.0)
        assertTrue(profile.outputGainAt(1.0) > profile.outputGainAt(0.0) * 1.09)
        assertTrue(profile.layers.any { it.role == SampleLayerRole.IDLE })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.LOAD })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.COAST })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.LIMITER })

        for (rpm in profile.idleRpm.toInt()..profile.limiterRpm.toInt() step 10) {
            assertTrue("no audible full-load layer at $rpm", strongestGain(rpm.toDouble(), 1.0) > 0.0001)
            assertTrue("no audible lift-off layer at $rpm", strongestGain(rpm.toDouble(), 0.0) > 0.0001)
        }
    }

    @Test
    fun coastCanReplaceLoadAsThePrimaryContinuousSourceWhenTheProfileProvidesIt() {
        val coastSource = profile.loopLayersForPrimarySource(PrimaryEngineLayerSource.COAST)
        val fmodMix = profile.loopLayersForPrimarySource(PrimaryEngineLayerSource.FMOD_MIX)

        assertTrue(profile.supportsPrimaryLayerSource(PrimaryEngineLayerSource.COAST))
        assertTrue(coastSource.any { it.role == SampleLayerRole.COAST })
        assertTrue(coastSource.none { it.role == SampleLayerRole.LOAD })
        assertTrue(profile.supportsPrimaryLayerSource(PrimaryEngineLayerSource.FMOD_MIX))
        assertTrue(fmodMix.any { it.role == SampleLayerRole.LOAD })
        assertTrue(fmodMix.any { it.role == SampleLayerRole.COAST })
        assertTrue(
            coastSource
                .filter { it.role == SampleLayerRole.COAST }
                .any { layer ->
                    layer.gainAt(
                        rpm = layer.autopitchRootRpm ?: profile.idleRpm,
                        throttle = 1.0,
                        loadOnlyProgram = true,
                        primaryLayerSource = PrimaryEngineLayerSource.COAST,
                    ) > 0.0001
                },
        )
        val skyline = EngineSampleProfiles.find("nissan_skyline_r34_cabin")
        assertFalse(skyline.supportsPrimaryLayerSource(PrimaryEngineLayerSource.COAST))
        assertFalse(skyline.supportsPrimaryLayerSource(PrimaryEngineLayerSource.FMOD_MIX))
        assertEquals(
            PrimaryEngineLayerSource.LOAD,
            skyline.resolvedPrimaryLayerSource(PrimaryEngineLayerSource.COAST),
        )
        assertEquals(
            PrimaryEngineLayerSource.LOAD,
            skyline.resolvedPrimaryLayerSource(PrimaryEngineLayerSource.FMOD_MIX),
        )
    }

    @Test
    fun throttleCurvesKeepTonalBodyWhileStillFavoringTheCorrectLayerSet() {
        val load = profile.layers.first { it.id == "l1" }
        val coast = profile.layers.first { it.id == "c2" }
        val broadbandNoise = profile.layers.first { it.id == "engine_noise_7" }

        assertTrue(load.gainAt(7_500.0, 1.0, loadOnlyProgram = false) > load.gainAt(7_500.0, 0.0, loadOnlyProgram = false) * 8.0)
        assertTrue(coast.gainAt(7_000.0, 0.0, loadOnlyProgram = false) > coast.gainAt(7_000.0, 1.0, loadOnlyProgram = false) * 2.5)
        assertTrue(coast.gainAt(7_000.0, 1.0, loadOnlyProgram = false) > 0.10)
        assertEquals(-0.5, broadbandNoise.baseGainDb, 0.0)
    }

    @Test
    fun wavDecoderPreservesStereoPcm16AndReadsLoopMetadataAfterData() {
        val wav = pcm16Wav(
            sampleRate = 44_100,
            channels = 2,
            interleaved = shortArrayOf(12_000, -4_000, -16_000, 8_000).repeatFrames(20),
            loopStart = 7,
            loopEndInclusive = 31,
        )

        val decoded = WavPcmDecoder.decode(ByteArrayInputStream(wav))

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(2, decoded.sourceChannels)
        assertEquals(40, decoded.frameCount)
        assertEquals(7, decoded.loopStartFrame)
        assertEquals(32, decoded.loopEndFrameExclusive)
        assertEquals(12_000.0 / 32_768.0, decoded.sampleAt(0, 0).toDouble(), 0.00001)
        assertEquals(-4_000.0 / 32_768.0, decoded.sampleAt(1, 0).toDouble(), 0.00001)
        assertEquals(-16_000.0 / 32_768.0, decoded.sampleAt(0, 1).toDouble(), 0.00001)
        assertEquals(8_000.0 / 32_768.0, decoded.sampleAt(1, 1).toDouble(), 0.00001)
        assertEquals(40L * 2 * Short.SIZE_BYTES, decoded.decodedBytes)
    }

    @Test
    fun wavDecoderToleratesTrailingPartialStereoFrameFromFsbExports() {
        val wav = pcm16Wav(
            sampleRate = 44_100,
            channels = 2,
            interleaved = shortArrayOf(12_000, -4_000, -16_000, 8_000).repeatFrames(20),
            loopStart = 0,
            loopEndInclusive = 39,
            dataBytes = 40 * 2 * Short.SIZE_BYTES + 2,
        )

        val decoded = WavPcmDecoder.decode(ByteArrayInputStream(wav))

        assertEquals(40, decoded.frameCount)
    }

    @Test
    fun codeDrivenSweepKeepsNativeRangeAudibleAndReportsRuntimeTelemetry() {
        val decoded = testBank()
        val renderer = SampleEngineRenderer.fromDecoded(44_100, decoded, profile)
        var totalNonZero = 0

        for (step in 0..100) {
            val rpm = profile.idleRpm + (profile.limiterRpm - profile.idleRpm) * step / 100.0
            val throttle = when {
                step < 20 -> 0.0
                step < 60 -> (step - 20) / 40.0
                else -> 1.0
            }
            val output = ShortArray(1_920)
            renderer.render(EngineAudioFrame(rpm = rpm, throttle = throttle), output, gain = 1.0)
            val nonZero = output.count { it != 0.toShort() }
            assertTrue("silent render at rpm=$rpm throttle=$throttle", nonZero > output.size * 0.75)
            totalNonZero += nonZero
        }
        repeat(12) {
            renderer.render(EngineAudioFrame(rpm = profile.limiterRpm, throttle = 1.0), ShortArray(1_920), gain = 1.0)
        }

        val diagnostics = renderer.diagnostics()
        assertTrue(totalNonZero > 80_000)
        assertEquals(profile.layers.size, diagnostics.loadedLoops)
        assertEquals(profile.limiterRpm, diagnostics.targetRpm.toDouble(), 2.0)
        assertEquals(profile.limiterRpm, diagnostics.renderRpm.toDouble(), 10.0)
        assertTrue(diagnostics.framesRendered > 90_000)
        assertTrue(diagnostics.loopWraps > 0)
        assertTrue(diagnostics.activeLayers != "none")
        assertTrue(diagnostics.playingSamples.isNotEmpty())
        assertTrue(diagnostics.playingSamples.all { it.assetName.endsWith(".wav") })
        assertTrue(diagnostics.playingSamples.all { it.role.isNotBlank() })
        assertTrue(diagnostics.playingSamples.any { it.displayText().contains('(') })
        assertTrue(diagnostics.peak in 0.01..1.0)
        assertEquals(0L, diagnostics.overRangeSamples)
    }

    @Test
    fun rendererUsesProfileRpmWithoutAxisRemapping() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(9_600)

        repeat(8) {
            renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.5), output, gain = 0.5)
        }

        assertEquals(4_000.0, renderer.diagnostics().targetRpm.toDouble(), 1.0)
        assertEquals(4_000.0, renderer.diagnostics().renderRpm.toDouble(), 8.0)
    }

    @Test
    fun rendererDoesNotCollapseTheStereoProgramToMono() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(12) {
            renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 1.0), output, gain = 0.66)
        }

        assertTrue((output.indices step 2).any { output[it] != output[it + 1] })
    }

    @Test
    fun limiterLayerAudibleInTopGearAtVmax() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(12) {
            renderer.render(
                EngineAudioFrame(
                    rpm = profile.limiterRpm,
                    throttle = 1.0,
                ),
                output,
                gain = 1.0,
            )
        }

        val limiterLevel = renderer.diagnostics().layerOutputMeters
            .firstOrNull { it.id == "limiter" }
            ?.outputLevel ?: 0.0

        assertTrue(limiterLevel > 0.01)
    }

    @Test
    fun shiftEventsTriggerThroughTheSameLayerMixPath() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(10) {
            renderer.render(
                EngineAudioFrame(rpm = 4_500.0, throttle = 0.6),
                output,
                gain = 0.6,
            )
        }
        renderer.render(
            EngineAudioFrame(
                rpm = 7_800.0,
                throttle = 1.0,
                shiftSerial = 1,
                shiftDirection = 1,
            ),
            output,
            gain = 0.6,
        )
        repeat(9) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_000.0,
                    throttle = 0.7,
                    shiftSerial = 1,
                    shiftDirection = 1,
                ),
                output,
                gain = 0.6,
            )
        }

        val diagnostics = renderer.diagnostics()
        assertTrue(diagnostics.loadedEffects >= profile.effects.size)
        assertEquals(1L, diagnostics.effectTriggers)
        assertTrue(diagnostics.activeEffects.contains("transmission_loop"))
    }

    @Test
    fun mutedShiftEffectsDoNotTriggerOnShift() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)
        val mutedShifts = mapOf(
            "shift_up" to LayerMixControl(muted = true),
            "shift_down" to LayerMixControl(muted = true),
        )
        renderer.render(EngineAudioFrame(shiftSerial = 0, layerMix = mutedShifts), output, gain = 0.5)
        renderer.render(
            EngineAudioFrame(shiftSerial = 1, shiftDirection = 1, layerMix = mutedShifts),
            output,
            gain = 0.5,
        )
        repeat(8) {
            renderer.render(EngineAudioFrame(shiftSerial = 1, layerMix = mutedShifts), output, gain = 0.5)
        }

        assertEquals(0L, renderer.diagnostics().effectTriggers)
    }

    @Test
    fun sharedPopsAndBangsUsesRecordedAlfaBackfireVariantsWithoutAttenuation() {
        assertEquals(0.0, SharedPopsAndBangs.effectSpec.baseGainDb, 0.0)
        assertEquals(4, SharedPopsAndBangs.assetNames.size)
        assertEquals(SampleEffectTrigger.THROTTLE_LIFT, SharedPopsAndBangs.effectSpec.trigger)
        assertTrue(SharedPopsAndBangs.assetNames.all { name -> name.startsWith("backfire_") })
    }

    @Test
    fun sharedPopsAndBangsOverridesNativeOverrunWhenEnabled() {
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        val decoded = aventador.requiredAssets.associateWith { asset ->
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        } + SharedPopsAndBangs.assetNames.associateWith {
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, aventador)
        val output = ShortArray(1_920)
        val frame = EngineAudioFrame(
            rpm = 4_000.0,
            throttle = 0.0,
            popsAndBangsEnabled = true,
        )

        renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.5, popsAndBangsEnabled = true), output, gain = 0.7)
        renderer.render(frame, output, gain = 0.7)
        repeat(10) {
            renderer.render(frame, output, gain = 0.7)
        }
        assertEquals(0L, renderer.diagnostics().effectTriggers)

        repeat(20) {
            renderer.render(frame.copy(throttle = 1.0), output, gain = 0.7)
        }
        renderer.render(frame, output, gain = 0.7)
        assertEquals(0L, renderer.diagnostics().effectTriggers)
        repeat(9) {
            renderer.render(frame, output, gain = 0.7)
        }
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertTrue(renderer.diagnostics().activeEffects.contains(SharedPopsAndBangs.EFFECT_ID))
    }

    @Test
    fun freeRevDoesNotTriggerSharedPopsOrNativeExhaustOverrun() {
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        val decoded = aventador.requiredAssets.associateWith { asset ->
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        } + SharedPopsAndBangs.assetNames.associateWith {
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, aventador)
        val output = ShortArray(1_920)
        val freeRevFrame = EngineAudioFrame(
            rpm = 4_000.0,
            popsAndBangsEnabled = true,
            throttleLiftEffectsEnabled = false,
        )

        renderer.render(freeRevFrame.copy(throttle = 0.5), output, gain = 0.7)
        renderer.render(freeRevFrame.copy(throttle = 0.0), output, gain = 0.7)

        assertEquals(0L, renderer.diagnostics().effectTriggers)
        assertFalse(renderer.diagnostics().activeEffects.contains(SharedPopsAndBangs.EFFECT_ID))
    }

    @Test
    fun sharedShiftSoundsOverridesNativeGearChangesWhenEnabled() {
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        val decoded = aventador.requiredAssets.associateWith { asset ->
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        } + SharedHuracanShiftSounds.assetNames.associateWith {
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, aventador)
        val output = ShortArray(1_920)

        renderer.render(
            EngineAudioFrame(
                rpm = 4_500.0,
                shiftSerial = 0,
                sharedShiftSoundsEnabled = true,
            ),
            output,
            gain = 0.7,
        )
        renderer.render(
            EngineAudioFrame(
                rpm = 4_500.0,
                shiftSerial = 1,
                shiftDirection = 1,
                sharedShiftSoundsEnabled = true,
                sharedShiftSoundsGain = 3.0,
            ),
            output,
            gain = 0.7,
        )
        repeat(8) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 4_500.0,
                    shiftSerial = 1,
                    shiftDirection = 1,
                    sharedShiftSoundsEnabled = true,
                ),
                output,
                gain = 0.7,
            )
        }

        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertTrue(renderer.diagnostics().activeEffects.contains(SharedHuracanShiftSounds.SHIFT_UP_ID))
    }

    @Test
    fun lateAttachHonorsActiveIgnitionShiftCue() {
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        val decoded = aventador.requiredAssets.associateWith { asset ->
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        } + SharedHuracanShiftSounds.assetNames.associateWith {
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, aventador)
        val output = ShortArray(1_920)

        renderer.render(
            EngineAudioFrame(
                rpm = 4_500.0,
                shiftSerial = 1,
                shiftDirection = 1,
                sharedShiftSoundsEnabled = true,
            ),
            output,
            gain = 0.7,
        )

        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertTrue(renderer.diagnostics().activeEffects.contains(SharedHuracanShiftSounds.SHIFT_UP_ID))
    }

    @Test
    fun throttleLiftOverrunWaitsForTheTurboDumpThenDoesNotRetriggerWhilePlaying() {
        val aventador = EngineSampleProfiles.find("lamborghini_aventador_sv_cabin")
        val decoded = aventador.requiredAssets.associateWith { asset ->
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        } + SharedPopsAndBangs.assetNames.associateWith {
            shortLoopSample(frameCount = 48_000, sampleRate = 48_000)
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, aventador)
        val output = ShortArray(1_920)
        val enabled = EngineAudioFrame(rpm = 4_000.0, popsAndBangsEnabled = true)

        repeat(20) {
            renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 1.0, popsAndBangsEnabled = true), output, gain = 0.7)
        }
        renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.0, popsAndBangsEnabled = true), output, gain = 0.7)
        assertEquals(0L, renderer.diagnostics().effectTriggers)
        repeat(9) {
            renderer.render(enabled.copy(throttle = 0.0), output, gain = 0.7)
        }
        assertEquals(1L, renderer.diagnostics().effectTriggers)

        renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.5, popsAndBangsEnabled = true), output, gain = 0.7)
        renderer.render(EngineAudioFrame(rpm = 4_000.0, throttle = 0.0, popsAndBangsEnabled = true), output, gain = 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)

        repeat(4) {
            renderer.render(enabled.copy(throttle = 0.0), output, gain = 0.7)
        }
    }

    @Test
    fun layerMixSoloMutesNonSoloLoadLoopsAndBlocksShiftEffects() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)
        val soloLoad = mapOf(
            "l1" to LayerMixControl(volume = 1.0, solo = true),
        )
        repeat(40) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_500.0,
                    throttle = 0.0,
                    layerMix = soloLoad,
                ),
                output,
                gain = 0.7,
            )
        }
        val loadSoloPeak = output.maxOf { abs(it.toInt()) }
        assertTrue("solo load layer should remain audible", loadSoloPeak > 20)

        renderer.render(
            EngineAudioFrame(
                rpm = 6_500.0,
                throttle = 0.0,
                shiftSerial = 1,
                shiftDirection = -1,
                layerMix = soloLoad,
            ),
            output,
            gain = 0.7,
        )
        repeat(20) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_500.0,
                    throttle = 0.0,
                    shiftSerial = 1,
                    shiftDirection = -1,
                layerMix = soloLoad,
                ),
                output,
                gain = 0.7,
            )
        }
        assertEquals("shift effects must not trigger while another layer is soloed", 0L, renderer.diagnostics().effectTriggers)
        assertEquals("none", renderer.diagnostics().activeEffects)
        assertTrue("non-solo output should stay near the load solo level", output.maxOf { abs(it.toInt()) } <= loadSoloPeak + 5)
    }

    @Test
    fun loadOnlySkipsCoastLayerAssets() {
        val coastAssets = profile.layers
            .filter { layer -> layer.role == SampleLayerRole.COAST }
            .map { layer -> layer.assetName }
            .toSet()
        assertTrue(coastAssets.isNotEmpty())
        val loadOnlyAssets = profile.requiredAssetsForLoad(loadOnlyProgram = true)
        assertTrue(coastAssets.none { asset -> asset in loadOnlyAssets })
        assertTrue(loadOnlyAssets.size < profile.requiredAssets.size)
        assertEquals(
            profile.layers.count { layer -> layer.role != SampleLayerRole.COAST },
            profile.loopLayersForLoad(loadOnlyProgram = true).size,
        )
    }

    @Test
    fun loadOnlyRendererNeedsNoCoastWavsAndRendersLoadLayers() {
        val loadOnlyAssets = profile.requiredAssetsForLoad(loadOnlyProgram = true)
        val renderer = SampleEngineRenderer.fromDecoded(
            outputSampleRate = 44_100,
            decoded = testBank().filterKeys(loadOnlyAssets::contains),
            profile = profile,
            loadOnlyProgram = true,
        )
        val output = ShortArray(1_920)

        repeat(24) {
            renderer.render(
                EngineAudioFrame(rpm = 5_500.0, throttle = 0.0, loadOnlyProgram = true),
                output,
                gain = 0.7,
            )
        }

        val coastTrackIds = profile.layers
            .filter { it.role == SampleLayerRole.COAST }
            .map { it.id }
            .toSet()
        val outputLevels = renderer.diagnostics().layerOutputMeters
        assertTrue(outputLevels.none { it.id in coastTrackIds })
        assertTrue(
            outputLevels.any { meter ->
                profile.layers.any { it.id == meter.id && it.role == SampleLayerRole.LOAD } &&
                    meter.outputLevel > 0.0
            },
        )
    }

    @Test
    fun loadOnlyMutesCoastAndHoldsLoadThrottleCurveAtFull() {
        val load = profile.layers.first { it.id == "l1" }
        val coast = profile.layers.first { it.id == "c2" }
        assertTrue(load.gainAt(7_500.0, 1.0, loadOnlyProgram = false) > load.gainAt(7_500.0, 0.0, loadOnlyProgram = false))
        val loadAtFullPedal = load.gainAt(7_500.0, 1.0, loadOnlyProgram = true)
        val loadAtLiftOff = load.gainAt(7_500.0, 0.0, loadOnlyProgram = true)
        assertEquals(loadAtFullPedal, loadAtLiftOff, 0.0001)
        assertTrue(loadAtFullPedal > 0.0)
        val coastAtFullPedal = coast.gainAt(7_000.0, 1.0, loadOnlyProgram = true)
        assertEquals(0.0, coastAtFullPedal, 0.0)
        assertTrue(loadAtLiftOff > load.gainAt(7_500.0, 0.0, loadOnlyProgram = false))
    }

    @Test
    fun loadOnlyGainMultiplierScalesDynamicEffects() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)
        val frame = EngineAudioFrame(
            rpm = 5_500.0,
            throttle = 0.8,
            loadOnlyProgram = true,
        )
        repeat(24) {
            renderer.render(frame, output, gain = 0.7)
        }

        val baseline = renderer.diagnostics().layerOutputMeters.first { it.id == "transmission_loop" }.outputLevel
        assertTrue("transmission should stay in a normal dynamic range at 1x", baseline in 0.05..0.35)

        repeat(24) {
            renderer.render(
                frame.copy(layerMix = mapOf("transmission_loop" to LayerMixControl(volume = 2.0))),
                output,
                gain = 0.7,
            )
        }
        val doubled = renderer.diagnostics().layerOutputMeters.first { it.id == "transmission_loop" }.outputLevel
        assertEquals(baseline * 2.0, doubled, 0.03)

        repeat(24) {
            renderer.render(
                frame.copy(layerMix = mapOf("transmission_loop" to LayerMixControl(volume = 0.5))),
                output,
                gain = 0.7,
            )
        }
        val halved = renderer.diagnostics().layerOutputMeters.first { it.id == "transmission_loop" }.outputLevel
        assertEquals(baseline * 0.5, halved, 0.03)

        repeat(48) {
            renderer.render(
                frame.copy(layerMix = mapOf("transmission_loop" to LayerMixControl(volume = LayerMixControl.MAX_GAIN_MULTIPLIER))),
                output,
                gain = 0.7,
            )
        }
        val maxed = renderer.diagnostics().layerOutputMeters.first { it.id == "transmission_loop" }.outputLevel
        val expectedMax = (baseline * LayerMixControl.MAX_GAIN_MULTIPLIER).coerceAtMost(1.0)
        assertEquals(expectedMax, maxed, 0.06)
    }

    @Test
    fun incompleteBankIsRejectedInsteadOfUsingAnotherSoundSource() {
        val decoded = mapOf(
            profile.requiredAssets.first() to PcmLoopData(ShortArray(32) { 3_277 }, 1, 48_000, 1),
        )

        val failure = runCatching { SampleEngineRenderer.fromDecoded(48_000, decoded, profile) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.startsWith("Missing ") == true)
    }

    private fun strongestGain(rpm: Double, throttle: Double): Double =
        profile.layers.maxOf { it.gainAt(rpm, throttle, loadOnlyProgram = false) }

    private fun shortLoopSample(frameCount: Int, sampleRate: Int): PcmLoopData {
        return PcmLoopData(
            interleavedSamples = ShortArray(frameCount * 2) { 1_000 },
            sourceChannels = 2,
            sampleRate = sampleRate,
            loopStartFrame = 0,
            loopEndFrameExclusive = frameCount,
        )
    }

    private fun testBank(): Map<String, PcmLoopData> = profile.requiredAssets.associateWith { asset ->
        val frequency = 70.0 + abs(asset.hashCode() % 220)
        val samples = ShortArray(2_048 * 2) { sampleIndex ->
            val frame = sampleIndex / 2
            val left = sin(2.0 * PI * frequency * frame / 44_100.0) * 0.35
            val value = if (sampleIndex % 2 == 0) left else -left * 0.75
            (value * Short.MAX_VALUE).toInt().toShort()
        }
        PcmLoopData(
            interleavedSamples = samples,
            sourceChannels = 2,
            sampleRate = 44_100,
            loopStartFrame = 250,
            loopEndFrameExclusive = 1_900,
        )
    }

    private fun ShortArray.repeatFrames(times: Int): ShortArray =
        ShortArray(size * times) { this[it % size] }

    private fun pcm16Wav(
        sampleRate: Int,
        channels: Int,
        interleaved: ShortArray,
        loopStart: Int,
        loopEndInclusive: Int,
        dataBytes: Int = interleaved.size * 2,
    ): ByteArray {
        require(dataBytes >= interleaved.size * 2) { "dataBytes must cover interleaved PCM" }
        val smplBytes = 60
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLe32(36 + dataBytes + 8 + smplBytes)
            write("WAVEfmt ".toByteArray())
            writeLe32(16)
            writeLe16(1)
            writeLe16(channels)
            writeLe32(sampleRate)
            writeLe32(sampleRate * channels * 2)
            writeLe16(channels * 2)
            writeLe16(16)
            write("data".toByteArray())
            writeLe32(dataBytes)
            interleaved.forEach { sample -> writeLe16(sample.toInt()) }
            if (dataBytes > interleaved.size * 2) {
                write(ByteArray(dataBytes - interleaved.size * 2))
            }
            write("smpl".toByteArray())
            writeLe32(smplBytes)
            repeat(7) { writeLe32(0) }
            writeLe32(1)
            writeLe32(0)
            writeLe32(0)
            writeLe32(0)
            writeLe32(loopStart)
            writeLe32(loopEndInclusive)
            writeLe32(0)
            writeLe32(0)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
