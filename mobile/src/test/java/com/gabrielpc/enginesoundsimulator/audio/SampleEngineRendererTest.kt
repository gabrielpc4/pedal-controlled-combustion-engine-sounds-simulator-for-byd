package com.gabrielpc.enginesoundsimulator.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleEngineRendererTest {
    @Test
    fun silentDecodePlaceholderDropsProgramsThatReferenceRemovedEffects() {
        val gate = OneShotParameterGateSpec(
            OneShotGateControl.ENGINE_RPM,
            1_000.0,
            2_000.0,
            includeMinimum = true,
            includeMaximum = true,
        )
        val transient = SampleEffectSpec(
            id = "transient",
            control = SampleEffectControls.coreEngine,
            assetName = "transient.flac",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            coreEngineTransient = true,
            autopitchRootRpm = 1_500.0,
        )
        val program = OneShotProgramSpec(
            id = "engine_program",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            rootNodeIds = listOf("engine_leaf"),
            nodes = listOf(
                oneShotLeaf("engine_leaf", transient.id, gates = listOf(gate)).copy(
                    liveVarispeed = true,
                    rootRpm = 1_500.0,
                ),
            ),
            policy = engineEventPolicy(gate),
        )
        val placeholder = rendererTestProfile().copy(
            layers = emptyList(),
            effects = listOf(transient),
            oneShotPrograms = listOf(program),
        ).silentPlaceholder()

        assertTrue(placeholder.layers.isEmpty())
        assertTrue(placeholder.effects.isEmpty())
        assertTrue(placeholder.oneShotPrograms.isEmpty())
        SampleEngineRenderer.fromDecoded(48_000, emptyMap(), placeholder)
    }

    private val profile = rendererTestProfile()

    @Test
    fun baseRuntimeBootstrapContainsNoPackagedCarProfile() {
        assertEquals("catalog_unselected", SILENT_CATALOG_PROFILE.id)
        assertTrue(SILENT_CATALOG_PROFILE.layers.isEmpty())
        assertTrue(SILENT_CATALOG_PROFILE.effects.isEmpty())
        assertTrue(SILENT_CATALOG_PROFILE.requiredAssets.isEmpty())
    }

    @Test
    fun profileContainsRecoveredContinuousEngineEvent() {
        assertTrue(profile.layers.size >= 5)
        assertEquals(profile.layers.size + profile.effects.size, profile.requiredAssets.size)
        assertEquals(3, profile.effects.size)
        assertEquals(7, profile.gearRatios.size)
        assertEquals(10_000.0, profile.maximumRpm, 0.0)
        assertEquals(8_350.0, profile.limiterRpm, 0.0)
        assertEquals(1.0, profile.outputGainAt(0.0), 0.0001)
        assertEquals(0.75, profile.throttleOutputGainDb?.valueAt(1.0) ?: 0.0, 0.0)
        assertTrue(profile.outputGainAt(1.0) > profile.outputGainAt(0.0) * 1.09)
        assertTrue(profile.layers.any { it.role == SampleLayerRole.IDLE })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.COAST })
        assertTrue(profile.layers.any { it.role == SampleLayerRole.LIMITER })

        for (rpm in profile.idleRpm.toInt()..profile.limiterRpm.toInt() step 10) {
            assertTrue("no audible on-pedal layer at $rpm", strongestGain(rpm.toDouble(), 1.0) > 0.0001)
            assertTrue("no audible lift-off layer at $rpm", strongestGain(rpm.toDouble(), 0.0) > 0.0001)
        }
    }

    @Test
    fun throttleCurvesKeepTonalBodyWhileStillFavoringTheCorrectLayerSet() {
        val coast = profile.layers.first { it.id == "c2" }
        val broadbandNoise = profile.layers.first { it.id == "engine_noise_7" }

        assertTrue(coast.gainAt(7_000.0, 0.0) > 0.10)
        assertTrue(coast.gainAt(7_000.0, 1.0) > 0.10)
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
        assertEquals(12_000.0 / 32_768.0, decoded.channelSamples[0][0].toDouble(), 0.00001)
        assertEquals(-4_000.0 / 32_768.0, decoded.channelSamples[1][0].toDouble(), 0.00001)
        assertEquals(-16_000.0 / 32_768.0, decoded.channelSamples[0][1].toDouble(), 0.00001)
        assertEquals(8_000.0 / 32_768.0, decoded.channelSamples[1][1].toDouble(), 0.00001)
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
    fun effectMaskControlsTransmissionAndShiftEventsWithoutASecondAudioPath() {
        val enabled = SampleEffectControls.transmission.bit or SampleEffectControls.gearChanges.bit
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)

        repeat(10) {
            renderer.render(
                EngineAudioFrame(rpm = 4_500.0, throttle = 0.6, enabledEffectMask = enabled),
                output,
                gain = 0.6,
            )
        }
        renderer.render(
            EngineAudioFrame(
                rpm = 7_800.0,
                throttle = 1.0,
                enabledEffectMask = enabled,
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
                    enabledEffectMask = enabled,
                    shiftSerial = 1,
                    shiftDirection = 1,
                ),
                output,
                gain = 0.6,
            )
        }

        val diagnostics = renderer.diagnostics()
        assertEquals(profile.effects.size, diagnostics.loadedEffects)
        assertEquals(1L, diagnostics.effectTriggers)
        assertTrue(diagnostics.activeEffects.contains("transmission_loop"))
    }

    @Test
    fun disabledEffectDoesNotTriggerOnShift() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)
        renderer.render(EngineAudioFrame(shiftSerial = 0), output, gain = 0.5)
        renderer.render(EngineAudioFrame(shiftSerial = 1, shiftDirection = 1), output, gain = 0.5)
        repeat(8) { renderer.render(EngineAudioFrame(shiftSerial = 1), output, gain = 0.5) }

        assertEquals(0L, renderer.diagnostics().effectTriggers)
        assertEquals("none", renderer.diagnostics().activeEffects)
    }

    @Test
    fun limiterRepeatsAtTheAuthoredFrequencyWhileRpmRemainsLimited() {
        val limiterAsset = "limiter_pulse.wav"
        val limiterProfile = profile.copy(
            limiterHz = 10.0,
            effects = profile.effects + SampleEffectSpec(
                id = "limiter_pulse",
                control = SampleEffectControls.limiter,
                assetName = limiterAsset,
                trigger = SampleEffectTrigger.LIMITER,
                baseGainDb = -12.0,
            ),
        )
        val decoded = testBank().toMutableMap().apply {
            val pulse = FloatArray(2_048) { frame -> if (frame < 128) 0.25f else 0.0f }
            put(limiterAsset, PcmLoopData(arrayOf(pulse, pulse.copyOf()), 48_000, 0, pulse.size))
        }
        val renderer = SampleEngineRenderer.fromDecoded(48_000, decoded, limiterProfile)
        val output = ShortArray(480 * 2)
        repeat(120) {
            renderer.render(
                EngineAudioFrame(
                    rpm = limiterProfile.limiterRpm,
                    throttle = 1.0,
                    enabledEffectMask = SampleEffectControls.limiter.bit,
                ),
                output,
                gain = 0.6,
            )
        }

        val triggers = renderer.diagnostics().effectTriggers
        assertTrue("expected 10 Hz limiter pulses, got $triggers", triggers in 11L..12L)
    }

    @Test
    fun persistentTimelineLimiterPreservesPhaseAcrossCutsAndUsesStrictTenSecondOwnerGate() {
        val fixture = persistentLimiterFixture(
            mode = PersistentLimiterProgramMode.TIMELINE_PERIOD_LOOP,
            pcmFrames = 480,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val enabled = EngineAudioFrame(
            rpm = fixture.profile.limiterRpm,
            throttle = 1.0,
            enabledEffectMask = SampleEffectControls.limiter.bit,
        )

        renderer.render(enabled, ShortArray(2), 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        repeat(20) { renderer.render(enabled, ShortArray(480 * 2), 0.7) }
        assertEquals(
            "repeated limiter cuts must reset decay without restarting the event timeline",
            1L,
            renderer.diagnostics().effectTriggers,
        )

        val belowLimiter = enabled.copy(rpm = fixture.profile.idleRpm, throttle = 0.0)
        renderer.render(belowLimiter, ShortArray(480_000 * 2), 0.7)
        renderer.render(enabled, ShortArray(2), 0.7)
        assertEquals(
            "the persistent owner must remain active at exactly 10.0 seconds",
            1L,
            renderer.diagnostics().effectTriggers,
        )

        renderer.render(belowLimiter, ShortArray(480_001 * 2), 0.7)
        renderer.render(enabled, ShortArray(2), 0.7)
        assertEquals(
            "strictly greater than 10 seconds must stop and rearm the owner",
            2L,
            renderer.diagnostics().effectTriggers,
        )
    }

    @Test
    fun persistentDecayRegionLoopStopsOnExitAndRestartsFromASecondEntry() {
        val fixture = persistentLimiterFixture(
            mode = PersistentLimiterProgramMode.DECAY_REGION_LOOP,
            pcmFrames = 480,
            placement = LimiterDecayPlacementSpec(0.0, 0.02, true, false),
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val output = ShortArray(480 * 2)
        val atLimiter = EngineAudioFrame(
            rpm = fixture.profile.limiterRpm,
            throttle = 1.0,
            enabledEffectMask = SampleEffectControls.limiter.bit,
        )

        renderer.render(atLimiter, output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        repeat(3) { renderer.render(atLimiter.copy(rpm = fixture.profile.idleRpm), output, 0.7) }
        assertEquals(1L, renderer.diagnostics().effectTriggers)

        renderer.render(atLimiter, output, 0.7)
        assertEquals(
            "a new 0.0-second placement entry must restart the stopped loop",
            2L,
            renderer.diagnostics().effectTriggers,
        )
        assertTrue(renderer.diagnostics().activeEffects.contains("limiter_source"))
    }

    @Test
    fun persistentDecayRegionOneShotsOverlapAtAuthoredLimiterPulseEntries() {
        val fixture = persistentLimiterFixture(
            mode = PersistentLimiterProgramMode.DECAY_REGION_ONE_SHOT,
            pcmFrames = 9_600,
            placement = LimiterDecayPlacementSpec(0.0, 0.02, true, false),
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val output = ShortArray(480 * 2)
        val atLimiter = EngineAudioFrame(
            rpm = fixture.profile.limiterRpm,
            throttle = 1.0,
            enabledEffectMask = SampleEffectControls.limiter.bit,
        )

        repeat(6) { renderer.render(atLimiter, output, 0.7) }

        assertEquals(2L, renderer.diagnostics().effectTriggers)
        assertTrue(
            "the first limiter source must still be alive when the next decay entry fires",
            renderer.diagnostics().globalLogicalVoices >= 2,
        )
        assertTrue(renderer.diagnostics().globalRealVoices >= 2)
    }

    @Test
    fun soloEffectsMutesEngineAndTransmissionButKeepsCheckedOneShotAudible() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)
        repeat(30) {
            renderer.render(
                EngineAudioFrame(rpm = 4_500.0, throttle = 0.6, soloEffects = true),
                output,
                gain = 0.7,
            )
        }
        assertTrue("unchecked solo must mute the continuous engine", output.maxOf { abs(it.toInt()) } <= 1)

        repeat(30) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 4_500.0,
                    throttle = 0.6,
                    enabledEffectMask = SampleEffectControls.transmission.bit,
                    soloEffects = true,
                ),
                output,
                gain = 0.7,
            )
        }
        assertTrue("transmission is part of the muted drivetrain", output.maxOf { abs(it.toInt()) } <= 1)

        renderer.render(
            EngineAudioFrame(
                rpm = 4_500.0,
                throttle = 0.6,
                enabledEffectMask = SampleEffectControls.gearChanges.bit,
                soloEffects = true,
                shiftSerial = 0,
            ),
            output,
            gain = 0.7,
        )
        renderer.render(
            EngineAudioFrame(
                rpm = 4_500.0,
                throttle = 0.6,
                enabledEffectMask = SampleEffectControls.gearChanges.bit,
                soloEffects = true,
                shiftSerial = 1,
                shiftDirection = 1,
            ),
            output,
            gain = 0.7,
        )
        assertTrue("checked non-drivetrain effect must remain audible in solo", output.maxOf { abs(it.toInt()) } > 20)
        assertTrue(renderer.diagnostics().activeEffects.contains("shift_up"))
        assertTrue(renderer.diagnostics().activeLayers.contains("effects solo"))
    }

    @Test
    fun naturalLiftAndAuditionChooseOneRandomInstrumentVariantPerControl() {
        val variants = (1..3).map { variant ->
            SampleEffectSpec(
                id = "overrun_$variant",
                control = SampleEffectControls.popsBangsCracks,
                assetName = "overrun_$variant",
                trigger = SampleEffectTrigger.THROTTLE_LIFT,
                baseGainDb = -12.0,
                minimumRpm = 6_500.0,
                auditionable = true,
                rpmAmplitudeCurve = AutomationCurve(
                    listOf(CurvePoint(6_500.0, 0.2), CurvePoint(7_200.0, 1.0), CurvePoint(8_200.0, 0.1)),
                ),
                throttleAmplitudeCurve = AutomationCurve(
                    listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 0.05)),
                ),
            )
        }
        val variantProfile = profile.copy(
            id = "random_overrun_variants",
            layers = emptyList(),
            effects = variants,
            throttleOutputGainDb = null,
        )
        val decoded = variants.associate { effect ->
            val tone = FloatArray(2_048) { frame -> (sin(frame * 0.07) * 0.25).toFloat() }
            effect.assetName to PcmLoopData(arrayOf(tone, tone.copyOf()), 48_000)
        }
        val frame = EngineAudioFrame(
            rpm = 7_200.0,
            enabledEffectMask = SampleEffectControls.popsBangsCracks.bit,
        )

        val natural = SampleEngineRenderer.fromDecoded(48_000, decoded, variantProfile)
        natural.render(frame.copy(throttle = 1.0), ShortArray(1_920), gain = 0.7)
        natural.render(frame.copy(throttle = 0.0), ShortArray(1_920), gain = 0.7)
        assertEquals("a natural lift must not stack random variants", 1L, natural.diagnostics().effectTriggers)

        val audition = SampleEngineRenderer.fromDecoded(48_000, decoded, variantProfile)
        val auditionOutput = ShortArray(1_920)
        val deliberatelyInvalidLiveState = frame.copy(rpm = 1_000.0, enabledEffectMask = 0L, throttle = 1.0)
        audition.render(deliberatelyInvalidLiveState, auditionOutput, gain = 0.7, popsAndBangsAuditionSerial = 0L)
        audition.render(deliberatelyInvalidLiveState, auditionOutput, gain = 0.7, popsAndBangsAuditionSerial = 1L)
        assertEquals("audition must use the natural variant-selection path", 1L, audition.diagnostics().effectTriggers)
        assertTrue("audition must choose an audible authored trigger state", auditionOutput.maxOf { abs(it.toInt()) } > 20)
    }

    @Test
    fun overrunOnlyProgramUsesTheSameAuthoredTopologyForNaturalLiftAndAudition() {
        val overrun = SampleEffectSpec(
            id = "authored_overrun",
            control = SampleEffectControls.exhaustOverrun,
            assetName = "authored_overrun",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            baseGainDb = -12.0,
            auditionable = true,
        )
        val program = OneShotProgramSpec(
            id = "authored_overrun_program",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            rootNodeIds = listOf("authored_overrun_leaf"),
            nodes = listOf(oneShotLeaf("authored_overrun_leaf", overrun.id)),
            policy = throttleLiftPolicy(),
        )
        val authoredProfile = profile.copy(
            id = "overrun_only_audition",
            layers = emptyList(),
            effects = listOf(overrun),
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val frame = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.exhaustOverrun.bit,
        )
        val natural = SampleEngineRenderer.fromDecoded(
            48_000, oneShotBank(listOf(overrun)), authoredProfile,
        )
        natural.render(frame.copy(throttle = 1.0), ShortArray(1_920), 0.7)
        natural.render(frame.copy(throttle = 0.01), ShortArray(1_920), 0.7)
        assertEquals(1L, natural.diagnostics().effectTriggers)
        assertTrue(natural.diagnostics().activeEffects.contains(overrun.id))

        val audition = SampleEngineRenderer.fromDecoded(
            48_000, oneShotBank(listOf(overrun)), authoredProfile,
        )
        val output = ShortArray(1_920)
        val maskedLiveState = frame.copy(enabledEffectMask = 0L, throttle = 1.0)
        audition.render(maskedLiveState, output, 0.7, popsAndBangsAuditionSerial = 0L)
        audition.render(maskedLiveState, output, 0.7, popsAndBangsAuditionSerial = 1L)
        assertEquals(1L, audition.diagnostics().effectTriggers)
        assertTrue(audition.diagnostics().activeEffects.contains(overrun.id))
        assertTrue(output.any { it != 0.toShort() })
    }

    @Test
    fun authoredOneShotTopologyTriggersIndependentRootsAndSelectAllChildrenSimultaneously() {
        val effects = listOf("pop_a", "pop_b", "mechanical_clack").map { id ->
            SampleEffectSpec(
                id = id,
                control = SampleEffectControls.popsBangsCracks,
                assetName = id,
                trigger = SampleEffectTrigger.THROTTLE_LIFT,
                baseGainDb = -18.0,
                auditionable = true,
            )
        }
        val programs = listOf(
            OneShotProgramSpec(
                id = "lift_event",
                trigger = SampleEffectTrigger.THROTTLE_LIFT,
                rootNodeIds = listOf("all_exhaust", "clack_leaf"),
                nodes = listOf(
                    OneShotGroupNodeSpec(
                        id = "all_exhaust",
                        triggerChance = 1.0,
                        playMode = OneShotPlayMode.NORMAL,
                        selectionMode = OneShotSelectionMode.SELECT_ALL,
                        members = listOf(
                            OneShotGroupMemberSpec("pop_a_leaf", 1.0, 0),
                            OneShotGroupMemberSpec("pop_b_leaf", 1.0, 1),
                        ),
                    ),
                    oneShotLeaf("pop_a_leaf", "pop_a"),
                    oneShotLeaf("pop_b_leaf", "pop_b"),
                    oneShotLeaf("clack_leaf", "mechanical_clack"),
                ),
                policy = throttleLiftPolicy(),
            ),
        )
        val topologyProfile = profile.copy(
            id = "authored_select_all",
            layers = emptyList(),
            effects = effects,
            oneShotPrograms = programs,
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, oneShotBank(effects), topologyProfile)
        val output = ShortArray(1_920)
        val frame = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.popsBangsCracks.bit,
        )
        renderer.render(frame.copy(throttle = 1.0), output, 0.7)
        renderer.render(frame.copy(throttle = 0.01), output, 0.7)

        assertEquals(3L, renderer.diagnostics().effectTriggers)
        assertTrue(renderer.diagnostics().activeEffects.contains("pop_a"))
        assertTrue(renderer.diagnostics().activeEffects.contains("pop_b"))
        assertTrue(renderer.diagnostics().activeEffects.contains("mechanical_clack"))
    }

    @Test
    fun authoredSmartRandomUsesWeightsWithoutImmediateVariantRepeats() {
        val effects = listOf("bang_a", "bang_b", "bang_c").map { id ->
            SampleEffectSpec(
                id = id,
                control = SampleEffectControls.popsBangsCracks,
                assetName = id,
                trigger = SampleEffectTrigger.THROTTLE_LIFT,
                baseGainDb = -18.0,
                auditionable = true,
            )
        }
        val program = OneShotProgramSpec(
            id = "smart_backfire",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            rootNodeIds = listOf("smart_group"),
            nodes = listOf(
                OneShotGroupNodeSpec(
                    id = "smart_group",
                    triggerChance = 1.0,
                    playMode = OneShotPlayMode.SMART_RANDOM,
                    selectionMode = OneShotSelectionMode.NORMAL,
                    members = effects.mapIndexed { index, effect ->
                        OneShotGroupMemberSpec("${effect.id}_leaf", (index + 1).toDouble(), index)
                    },
                ),
            ) + effects.map { oneShotLeaf("${it.id}_leaf", it.id) },
            policy = throttleLiftPolicy(),
        )
        val topologyProfile = profile.copy(
            id = "authored_smart_random",
            layers = emptyList(),
            effects = effects,
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, oneShotBank(effects), topologyProfile)
        val output = ShortArray(1_920)
        val base = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.popsBangsCracks.bit,
        )
        var previous = ""
        repeat(12) {
            renderer.render(base.copy(throttle = 1.0), output, 0.7)
            renderer.render(base.copy(throttle = 0.01), output, 0.7)
            val active = renderer.diagnostics().activeEffects
            assertTrue(active in effects.map { it.id })
            if (previous.isNotEmpty()) assertTrue("SmartRandom repeated $active", active != previous)
            previous = active
            repeat(4) { renderer.render(base.copy(throttle = 0.0), output, 0.7) }
        }
        assertEquals(12L, renderer.diagnostics().effectTriggers)
    }

    @Test
    fun authoredOneShotLeafGatesAndZeroChanceAreHonored() {
        val allowed = SampleEffectSpec(
            id = "upshift_allowed",
            control = SampleEffectControls.gearChanges,
            assetName = "upshift_allowed",
            trigger = SampleEffectTrigger.SHIFT_UP,
            baseGainDb = -18.0,
        )
        val never = allowed.copy(id = "zero_chance", assetName = "zero_chance")
        val policy = OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.SHIFT_UP,
            minimumRpm = 2_000.0,
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
        )
        val program = OneShotProgramSpec(
            id = "upshift_event",
            trigger = SampleEffectTrigger.SHIFT_UP,
            rootNodeIds = listOf("allowed_leaf", "never_leaf"),
            nodes = listOf(
                oneShotLeaf(
                    "allowed_leaf", allowed.id,
                    gates = listOf(OneShotParameterGateSpec(OneShotGateControl.SHIFT_STATE, 0.5, 1.5, true)),
                ),
                oneShotLeaf("never_leaf", never.id, chance = 0.0),
            ),
            policy = policy,
        )
        val gateProfile = profile.copy(
            id = "authored_gates",
            layers = emptyList(),
            effects = listOf(allowed, never),
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(
            48_000, oneShotBank(gateProfile.effects), gateProfile,
        )
        val output = ShortArray(1_920)
        val enabled = SampleEffectControls.gearChanges.bit
        renderer.render(EngineAudioFrame(rpm = 5_000.0, enabledEffectMask = enabled), output, 0.7)
        renderer.render(
            EngineAudioFrame(
                rpm = 5_000.0,
                enabledEffectMask = enabled,
                shiftSerial = 1,
                shiftDirection = 1,
            ),
            output,
            0.7,
        )
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertEquals("upshift_allowed", renderer.diagnostics().activeEffects)
    }

    @Test
    fun authoredBovFiresOnlyOnPhysicalPressureSignalRisingEdge() {
        val bov = SampleEffectSpec(
            id = "bov_release",
            control = SampleEffectControls.turbo,
            assetName = "bov_release",
            trigger = SampleEffectTrigger.BOV_LIFT,
            baseGainDb = -18.0,
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        val policy = OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.BOV_LIFT,
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
        )
        val program = OneShotProgramSpec(
            id = "bov_event",
            trigger = SampleEffectTrigger.BOV_LIFT,
            rootNodeIds = listOf("bov_leaf"),
            nodes = listOf(
                oneShotLeaf(
                    "bov_leaf",
                    bov.id,
                    gates = listOf(OneShotParameterGateSpec(OneShotGateControl.BOV, 0.5, 1.5, true)),
                ),
            ),
            policy = policy,
        )
        val turboPhysics = TurboPhysicsSpec(
            bovPressureThreshold = 0.30,
            units = arrayOf(
                TurboPhysicsUnitSpec(
                    maximumBoost = 1.0,
                    wastegate = 1.0,
                    referenceRpm = 5_000.0,
                    gamma = 1.0,
                    lagUp = 1_000.0,
                    lagDown = 1.0,
                    controllerProgramIndex = -1,
                ),
            ),
            controllerBank = null,
        )
        val bovProfile = profile.copy(
            id = "authored_bov",
            layers = emptyList(),
            effects = listOf(bov),
            oneShotPrograms = listOf(program),
            turboPhysics = turboPhysics,
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, oneShotBank(listOf(bov)), bovProfile)
        val output = ShortArray(1_920)
        val base = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.turbo.bit,
        )

        repeat(8) { renderer.render(base.copy(throttle = 0.2, physicalPedal = 0.2), output, 0.7) }
        repeat(8) { renderer.render(base.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7) }
        assertEquals("low physical pressure must not open the BOV", 0L, renderer.diagnostics().effectTriggers)

        repeat(8) { renderer.render(base.copy(throttle = 1.0, physicalPedal = 1.0), output, 0.7) }
        var releaseBlocks = 0
        while (renderer.diagnostics().effectTriggers == 0L && releaseBlocks < 8) {
            renderer.render(base.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
            releaseBlocks += 1
        }
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertTrue(renderer.diagnostics().activeEffects.contains("bov_release"))
    }

    @Test
    fun turboEventFullDomainRequiresTurboMaskButSurvivesDrivetrainIsolation() {
        val fixture = turboEventFixture(
            mode = TurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT,
            pcmFrames = 4_800,
            controlGainCurves = listOf(
                OneShotControlCurveSpec(
                    OneShotGateControl.BOOST,
                    AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))),
                ),
            ),
            pitchAutomations = listOf(
                OneShotPitchAutomationSpec(
                    OneShotGateControl.BOOST,
                    captureSemitones = 0.0,
                    playbackRateCurve = AutomationCurve(
                        listOf(CurvePoint(0.0, 0.5), CurvePoint(1.0, 2.0)),
                    ),
                ),
            ),
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val output = ShortArray(512)
        val masked = EngineAudioFrame(rpm = 5_000.0)
        val base = masked.copy(enabledEffectMask = SampleEffectControls.turbo.bit)

        renderer.render(masked.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
        assertEquals("core turbo program must obey the user-visible Turbo mask", 0L,
            renderer.diagnostics().effectTriggers)
        assertEquals(0, renderer.diagnostics().globalLogicalVoices)
        renderer.render(base.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertEquals(1, renderer.diagnostics().globalLogicalVoices)
        assertEquals("none", renderer.diagnostics().activeEffects)

        renderer.render(
            base.copy(throttle = 1.0, physicalPedal = 1.0, soloEffects = true),
            output,
            0.7,
        )
        assertTrue(
            "engine/transmission isolation must not implicitly mute selected turbo audio",
            output.any { it != 0.toShort() },
        )
        assertTrue(renderer.diagnostics().activeEffects.contains("turbo_event_source"))
        val triggersBeforeAudition = renderer.diagnostics().effectTriggers
        renderer.render(
            base.copy(throttle = 1.0, physicalPedal = 1.0),
            output,
            0.7,
            popsAndBangsAuditionSerial = 1L,
        )
        assertEquals(triggersBeforeAudition, renderer.diagnostics().effectTriggers)

        repeat(8) {
            renderer.render(base.copy(throttle = 1.0, physicalPedal = 1.0), output, 0.7)
        }
        assertEquals(
            "live property-1 rate automation did not accelerate the active voice",
            0,
            renderer.diagnostics().globalLogicalVoices,
        )
    }

    @Test
    fun turboBoostReleaseAllowsOverlapAndOwnerRestartCannotReviveOldVoices() {
        val fixture = turboEventFixture(
            mode = TurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT,
            pcmFrames = 48_000,
            placementMinimumBoost = 0.0,
            placementMaximumBoost = 0.95,
            controlGainCurves = listOf(
                OneShotControlCurveSpec(
                    OneShotGateControl.BOOST,
                    AutomationCurve(listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 1.0))),
                ),
                OneShotControlCurveSpec(
                    OneShotGateControl.BOV,
                    AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))),
                ),
                OneShotControlCurveSpec(
                    OneShotGateControl.BOV_DECAY,
                    AutomationCurve(listOf(CurvePoint(0.0, 1.0), CurvePoint(10.0, 0.0))),
                ),
            ),
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val output = ShortArray(512)
        val enabled = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.turbo.bit,
        )

        renderer.render(
            enabled.copy(throttle = 1.0, physicalPedal = 1.0, soloEffects = true),
            output,
            0.7,
        )
        assertEquals(0L, renderer.diagnostics().effectTriggers)
        renderer.render(
            enabled.copy(throttle = 0.0, physicalPedal = 0.0, soloEffects = true),
            output,
            0.7,
        )
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertTrue(
            "Turbo isolation must expose authored BOV/release audio",
            output.any { it != 0.toShort() },
        )
        assertTrue(renderer.diagnostics().activeEffects.contains("turbo_event_source"))
        renderer.render(enabled.copy(throttle = 1.0, physicalPedal = 1.0), output, 0.7)
        renderer.render(enabled.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
        assertEquals(2L, renderer.diagnostics().effectTriggers)
        assertEquals(2, renderer.diagnostics().globalLogicalVoices)

        renderer.render(
            enabled.copy(throttle = 0.0, physicalPedal = 0.0, enabledEffectMask = 0L),
            output,
            0.7,
        )
        renderer.render(enabled.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
        assertEquals("event-start-inside restart must schedule one new generation", 3L,
            renderer.diagnostics().effectTriggers)
        repeat(24) {
            renderer.render(enabled.copy(throttle = 0.0, physicalPedal = 0.0), output, 0.7)
        }
        assertEquals("retired turbo owner voices were resurrected", 1,
            renderer.diagnostics().globalLogicalVoices)
    }

    @Test
    fun turboTimelineSequentialSelectionRetainsExplicitSilentProbability() {
        val fixture = turboEventFixture(
            mode = TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT,
            pcmFrames = 1_000,
            timelineStartFrames = 0L,
            timelinePeriodFrames = 100L,
            includeSequentialSilentLeaf = true,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val base = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.turbo.bit,
        )

        renderer.render(base, ShortArray(100), 0.7)
        assertEquals("silent first selection was incorrectly reweighted", 0L,
            renderer.diagnostics().effectTriggers)
        assertEquals(0, renderer.diagnostics().globalLogicalVoices)
        renderer.render(base, ShortArray(120), 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertEquals(1, renderer.diagnostics().globalLogicalVoices)
        renderer.render(base, ShortArray(180), 0.7)
        renderer.render(base, ShortArray(2), 0.7)
        assertEquals("second silent selection must not create PCM or choose again", 1L,
            renderer.diagnostics().effectTriggers)
    }

    @Test
    fun turboTimelineStartsAtItsExactFrameInsideTheRenderBurst() {
        val startFrame = 73
        val fixture = turboEventFixture(
            mode = TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT,
            pcmFrames = 1_000,
            timelineStartFrames = startFrame.toLong(),
            timelinePeriodFrames = 2_000L,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, fixture.bank, fixture.profile)
        val output = ShortArray(256 * 2)
        renderer.render(
            EngineAudioFrame(
                rpm = 5_000.0,
                enabledEffectMask = SampleEffectControls.turbo.bit,
            ),
            output,
            0.7,
        )

        assertTrue(
            "authored source leaked before timeline frame $startFrame",
            output.copyOfRange(0, startFrame * 2).all { it == 0.toShort() },
        )
        assertTrue(
            "authored source did not start inside the same burst",
            output.copyOfRange(startFrame * 2, output.size).any { it != 0.toShort() },
        )
        assertEquals(1L, renderer.diagnostics().effectTriggers)
    }

    @Test
    fun authoredBackfireConsumesTriggerWhileItsProgramVoiceIsBusy() {
        val backfire = SampleEffectSpec(
            id = "backfire_busy",
            control = SampleEffectControls.popsBangsCracks,
            assetName = "backfire_busy",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            baseGainDb = -18.0,
            auditionable = true,
        )
        val program = OneShotProgramSpec(
            id = "backfire_busy_program",
            trigger = SampleEffectTrigger.THROTTLE_LIFT,
            rootNodeIds = listOf("backfire_leaf"),
            nodes = listOf(oneShotLeaf("backfire_leaf", backfire.id)),
            policy = throttleLiftPolicy(),
        )
        val busyProfile = profile.copy(
            id = "backfire_busy_profile",
            layers = emptyList(),
            effects = listOf(backfire),
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(
            48_000,
            oneShotBank(listOf(backfire)),
            busyProfile,
        )
        val output = ShortArray(1_920)
        val base = EngineAudioFrame(
            rpm = 5_000.0,
            enabledEffectMask = SampleEffectControls.popsBangsCracks.bit,
        )

        renderer.render(base.copy(throttle = 1.0), output, 0.7)
        renderer.render(base.copy(throttle = 0.01), output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        renderer.render(base.copy(throttle = 1.0), output, 0.7)
        renderer.render(base.copy(throttle = 0.01), output, 0.7)
        assertEquals("busy backfire must consume, not rewind or queue, the trigger", 1L,
            renderer.diagnostics().effectTriggers)
        repeat(4) { renderer.render(base.copy(throttle = 0.01), output, 0.7) }
        assertEquals("a refused trigger must not retry after the voice ends", 1L,
            renderer.diagnostics().effectTriggers)
    }

    @Test
    fun engineEventStartsInsideReentersWithOverlapAndUsesLiveVarispeed() {
        val transient = SampleEffectSpec(
            id = "engine_transient",
            control = SampleEffectControls.coreEngine,
            assetName = "engine_transient",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            baseGainDb = -18.0,
            autopitchRootRpm = 3_000.0,
            coreEngineTransient = true,
        )
        val gate = OneShotParameterGateSpec(
            OneShotGateControl.ENGINE_RPM, 2_000.0, 6_000.0,
            includeMinimum = true, includeMaximum = true,
        )
        val program = OneShotProgramSpec(
            id = "engine_event_region",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            rootNodeIds = listOf("engine_leaf"),
            nodes = listOf(
                oneShotLeaf("engine_leaf", transient.id, gates = listOf(gate)).copy(
                    liveVarispeed = true,
                    rootRpm = 3_000.0,
                ),
            ),
            policy = engineEventPolicy(gate),
        )
        val eventProfile = profile.copy(
            id = "engine_event_region_profile",
            layers = emptyList(),
            effects = listOf(transient),
            oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val bank = oneShotBank(listOf(transient))
        val renderer = SampleEngineRenderer.fromDecoded(48_000, bank, eventProfile)
        val output = ShortArray(256 * 2)

        renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        assertEquals(1, renderer.diagnostics().globalLogicalVoices)
        assertEquals(1, renderer.diagnostics().globalRealVoices)
        renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        renderer.render(EngineAudioFrame(rpm = 1_000.0), output, 0.7)
        renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        assertEquals(2L, renderer.diagnostics().effectTriggers)
        assertEquals("both reentry voices must overlap", 2,
            renderer.diagnostics().globalLogicalVoices)
        assertEquals(2, renderer.diagnostics().globalRealVoices)
        assertTrue(renderer.diagnostics().activeEffects.contains(transient.id))

        fun blocksUntilFinished(rpm: Double): Int {
            val candidate = SampleEngineRenderer.fromDecoded(48_000, bank, eventProfile)
            var blocks = 0
            do {
                candidate.render(EngineAudioFrame(rpm = rpm), output, 0.7)
                blocks += 1
            } while (candidate.diagnostics().activeEffects != "none" && blocks < 100)
            return blocks
        }
        val rootSpeedBlocks = blocksUntilFinished(3_000.0)
        val doubleSpeedBlocks = blocksUntilFinished(6_000.0)
        assertTrue("live AutoPitch did not shorten the voice", doubleSpeedBlocks < rootSpeedBlocks)

        fun blocksAfterLeavingAuthoredRegion(outsideRpm: Double): Int {
            val candidate = SampleEngineRenderer.fromDecoded(48_000, bank, eventProfile)
            candidate.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
            var blocks = 0
            do {
                candidate.render(EngineAudioFrame(rpm = outsideRpm), output, 0.7)
                blocks += 1
            } while (candidate.diagnostics().globalLogicalVoices != 0 && blocks < 100)
            return blocks
        }
        val slowOutsideBlocks = blocksAfterLeavingAuthoredRegion(1_000.0)
        val fastOutsideBlocks = blocksAfterLeavingAuthoredRegion(7_000.0)
        assertTrue(
            "live AutoPitch must continue following RPM after the authored gate exits",
            fastOutsideBlocks < slowOutsideBlocks,
        )
    }

    @Test
    fun engineLeafCanForbidEveryNewVoiceAfterItsInitialSourceTrigger() {
        val transient = SampleEffectSpec(
            id = "engine_transient_no_reentry",
            control = SampleEffectControls.coreEngine,
            assetName = "engine_transient_no_reentry",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            baseGainDb = -18.0,
            autopitchRootRpm = 3_000.0,
            coreEngineTransient = true,
        )
        val gate = OneShotParameterGateSpec(
            OneShotGateControl.ENGINE_RPM, 2_000.0, 6_000.0,
            includeMinimum = true, includeMaximum = true,
        )
        val leaf = oneShotLeaf("engine_leaf", transient.id, gates = listOf(gate)).copy(
            liveVarispeed = true,
            rootRpm = 3_000.0,
            engineTransientReentryPolicy = EngineTransientReentryPolicy
                .NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER,
        )
        val eventProfile = profile.copy(
            id = "engine_event_no_reentry_profile",
            layers = emptyList(),
            effects = listOf(transient),
            oneShotPrograms = listOf(
                OneShotProgramSpec(
                    id = "engine_event_no_reentry",
                    trigger = SampleEffectTrigger.ENGINE_EVENT,
                    rootNodeIds = listOf(leaf.id),
                    nodes = listOf(leaf),
                    policy = engineEventPolicy(gate),
                ),
            ),
            throttleOutputGainDb = null,
        )
        val renderer = SampleEngineRenderer.fromDecoded(
            48_000,
            oneShotBank(listOf(transient)),
            eventProfile,
        )
        val output = ShortArray(256 * 2)

        renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        assertEquals(1L, renderer.diagnostics().effectTriggers)
        repeat(100) {
            if (renderer.diagnostics().globalLogicalVoices == 0) return@repeat
            renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        }
        assertEquals(0, renderer.diagnostics().globalLogicalVoices)
        renderer.render(EngineAudioFrame(rpm = 1_000.0), output, 0.7)
        renderer.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)

        assertEquals("source-bound no-reentry policy must not schedule a replacement", 1L,
            renderer.diagnostics().effectTriggers)
        assertEquals(0, renderer.diagnostics().globalLogicalVoices)
    }

    @Test
    fun engineEventStartedOutsideStaysDisabledAndCoreIsolationOnlyMutesItsPcm() {
        val transient = SampleEffectSpec(
            id = "isolated_engine_transient",
            control = SampleEffectControls.coreEngine,
            assetName = "isolated_engine_transient",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            baseGainDb = -12.0,
            autopitchRootRpm = 3_000.0,
            coreEngineTransient = true,
        )
        val gate = OneShotParameterGateSpec(OneShotGateControl.ENGINE_RPM, 2_000.0, 6_000.0)
        val program = OneShotProgramSpec(
            id = "isolated_engine_event",
            trigger = SampleEffectTrigger.ENGINE_EVENT,
            rootNodeIds = listOf("engine_leaf"),
            nodes = listOf(oneShotLeaf("engine_leaf", transient.id, gates = listOf(gate)).copy(
                liveVarispeed = true, rootRpm = 3_000.0,
            )),
            policy = engineEventPolicy(gate),
        )
        val eventProfile = profile.copy(
            id = "isolated_engine_event_profile",
            layers = emptyList(), effects = listOf(transient), oneShotPrograms = listOf(program),
            throttleOutputGainDb = null,
        )
        val bank = oneShotBank(listOf(transient))
        val output = ShortArray(256 * 2)

        val startsOutside = SampleEngineRenderer.fromDecoded(48_000, bank, eventProfile)
        startsOutside.render(EngineAudioFrame(rpm = 1_000.0), output, 0.7)
        startsOutside.render(EngineAudioFrame(rpm = 3_000.0), output, 0.7)
        assertEquals(0L, startsOutside.diagnostics().effectTriggers)

        val isolated = SampleEngineRenderer.fromDecoded(48_000, bank, eventProfile)
        isolated.render(EngineAudioFrame(rpm = 3_000.0, soloEffects = true), output, 0.7)
        assertEquals(1L, isolated.diagnostics().effectTriggers)
        assertTrue(output.all { it == 0.toShort() })
        isolated.render(EngineAudioFrame(rpm = 3_000.0, soloEffects = false), output, 0.7)
        assertEquals("unmuting must not retrigger the event", 1L, isolated.diagnostics().effectTriggers)
        assertTrue(output.any { it != 0.toShort() })
    }

    @Test
    fun layerMixSoloMutesNonSoloLoopsAndBlocksShiftEffects() {
        val renderer = SampleEngineRenderer.fromDecoded(48_000, testBank(), profile)
        val output = ShortArray(1_920)
        val allEffects = profile.effectControls.fold(0L) { mask, control -> mask or control.bit }
        val soloCoast = mapOf(
            "c1" to LayerMixControl(volume = 1.0, solo = true),
        )
        repeat(40) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_500.0,
                    throttle = 0.0,
                    enabledEffectMask = allEffects,
                    layerMix = soloCoast,
                ),
                output,
                gain = 0.7,
            )
        }
        val coastSoloPeak = output.maxOf { abs(it.toInt()) }
        assertTrue("solo coast layer should remain audible", coastSoloPeak > 20)

        renderer.render(
            EngineAudioFrame(
                rpm = 6_500.0,
                throttle = 0.0,
                enabledEffectMask = allEffects,
                shiftSerial = 1,
                shiftDirection = -1,
                layerMix = soloCoast,
            ),
            output,
            gain = 0.7,
        )
        repeat(20) {
            renderer.render(
                EngineAudioFrame(
                    rpm = 6_500.0,
                    throttle = 0.0,
                    enabledEffectMask = allEffects,
                    shiftSerial = 1,
                    shiftDirection = -1,
                    layerMix = soloCoast,
                ),
                output,
                gain = 0.7,
            )
        }
        assertEquals("shift effects must not trigger while another layer is soloed", 0L, renderer.diagnostics().effectTriggers)
        assertEquals("none", renderer.diagnostics().activeEffects)
        assertTrue("non-solo output should stay near the coast solo level", output.maxOf { abs(it.toInt()) } <= coastSoloPeak + 5)
    }

    @Test
    fun runtimeProfileContainsOnlyTheSupportedLayerRoles() {
        assertEquals(
            setOf("IDLE", "COAST", "TEXTURE", "INTAKE", "EXHAUST", "TURBO", "SPOOL", "LIMITER"),
            SampleLayerRole.entries.map { it.name }.toSet(),
        )
        assertTrue(profile.layers.all { it.role in SampleLayerRole.entries })
        assertEquals(profile.requiredAssets, (profile.layers.map { it.assetName } + profile.effects.map { it.assetName }).toSet())
    }

    @Test
    fun authoredIdleAndCoastCurvesRemainPedalResponsive() {
        val coast = profile.layers.first { it.id == "c2" }
        val idle = profile.layers.first { it.id == "idle_low" }
        val coastAtFullPedal = coast.gainAt(7_000.0, 1.0)
        val coastAtLiftOff = coast.gainAt(7_000.0, 0.0)
        assertTrue(coastAtLiftOff > 0.0)
        assertTrue(coastAtFullPedal > 0.0)
        assertEquals(10.0.pow(-14.5 / 20.0), idle.gainAt(1_000.0, 0.0), 0.000001)
        assertEquals(10.0.pow(-10.5 / 20.0), idle.gainAt(1_000.0, 1.0), 0.000001)
        assertTrue(idle.gainAt(1_000.0, 1.0) > idle.gainAt(1_000.0, 0.0))
    }

    @Test
    fun coastPedalCurvesStayAudibleAtFullThrottleWithoutLoadTracks() {
        val inverseCoast = AutomationCurve(listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 0.0)))
        assertEquals(0.0, inverseCoast.valueAt(1.0), 0.0)
        assertEquals(1.0, pedalAmplitudeForLayerRole(SampleLayerRole.COAST, inverseCoast, 1.0), 0.0)
        assertEquals(1.0, pedalAmplitudeForLayerRole(SampleLayerRole.COAST, inverseCoast, 0.0), 0.0)
        assertEquals(0.0, pedalAmplitudeForLayerRole(SampleLayerRole.IDLE, inverseCoast, 1.0), 0.0)
    }

    @Test
    fun layerGainMultiplierScalesDynamicEffects() {
        val renderer = SampleEngineRenderer.fromDecoded(44_100, testBank(), profile)
        val output = ShortArray(1_920)
        val frame = EngineAudioFrame(
            rpm = 5_500.0,
            throttle = 0.8,
            enabledEffectMask = profile.defaultEffectMask,
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
            profile.requiredAssets.first() to PcmLoopData(arrayOf(FloatArray(32) { 0.1f }), 48_000, 1),
        )

        val failure = runCatching { SampleEngineRenderer.fromDecoded(48_000, decoded, profile) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.startsWith("Missing ") == true)
    }

    @Test
    fun safetyLimiterIsTransparentBelowKneeAndNeverExceedsMinusOneDbfs() {
        assertEquals(0.5, safetyLimit(0.5), 0.0)
        assertEquals(-0.5, safetyLimit(-0.5), 0.0)
        assertTrue(safetyLimit(0.9) < 0.9)
        assertTrue(safetyLimit(100.0) <= SAFETY_LIMITER_CEILING)
        assertTrue(safetyLimit(-100.0) >= -SAFETY_LIMITER_CEILING)
    }

    @Test
    fun huracanDiscontinuousLoopsUseExplicitRuntimeCrossfades() {
        listOf("c1", "c3", "limiter").forEach { id ->
            assertEquals(512, profile.layers.first { it.id == id }.loopCrossfadeFrames)
        }

        val seamLayer = SampleLayerSpec(
            id = "seam_regression",
            assetName = "seam.wav",
            role = SampleLayerRole.TEXTURE,
            startRpm = 0.0,
            endRpm = 2_000.0,
            autopitchRootRpm = 1_000.0,
            loopCrossfadeFrames = 64,
        )
        val seamProfile = profile.copy(
            id = "seam_regression",
            outputSampleRate = 48_000,
            minimumRpm = 0.0,
            maximumRpm = 2_000.0,
            idleRpm = 1_000.0,
            redlineRpm = 1_800.0,
            limiterRpm = 1_900.0,
            upshiftRpm = 1_800.0,
            layers = listOf(seamLayer),
            effects = emptyList(),
            throttleOutputGainDb = null,
        )
        val ramp = FloatArray(256) { frame -> -0.8f + 1.6f * frame / 255.0f }
        val renderer = SampleEngineRenderer.fromDecoded(
            outputSampleRate = 48_000,
            decoded = mapOf("seam.wav" to PcmLoopData(arrayOf(ramp, ramp.copyOf()), 48_000)),
            profile = seamProfile,
        )
        val output = ShortArray(8_192)
        renderer.render(EngineAudioFrame(rpm = 1_000.0, throttle = 0.5), output, gain = 0.5)

        var maximumAdjacentDelta = 0
        var frame = 1_024
        while (frame < output.size / 2) {
            maximumAdjacentDelta = maxOf(
                maximumAdjacentDelta,
                abs(output[frame * 2].toInt() - output[(frame - 1) * 2].toInt()),
            )
            frame += 1
        }
        assertTrue("crossfaded loop still has a click-sized jump: $maximumAdjacentDelta", maximumAdjacentDelta < 1_500)
    }

    private fun oneShotLeaf(
        nodeId: String,
        effectId: String,
        chance: Double = 1.0,
        gates: List<OneShotParameterGateSpec> = emptyList(),
    ): OneShotTrackNodeSpec = OneShotTrackNodeSpec(
        id = nodeId,
        triggerChance = chance,
        effectId = effectId,
        parameterGates = gates,
        rpmAmplitudeCurve = null,
        throttleAmplitudeCurve = null,
    )

    private fun throttleLiftPolicy(): OneShotTriggerPolicySpec = OneShotTriggerPolicySpec(
        kind = OneShotPolicyKind.AC_BACKFIRE,
        minimumRpm = 0.0,
        maximumRpm = 20_000.0,
        armPedal = 0.35,
        firePedal = 0.08,
        armBoost = null,
        initialPeakPedal = 0.6,
        initialArmPedal = 0.35,
        initialFirePedal = 0.08,
        minimumArmSeconds = 0.0,
        cooldownSeconds = 0.0,
        periodHz = null,
    )

    private fun engineEventPolicy(gate: OneShotParameterGateSpec): OneShotTriggerPolicySpec =
        OneShotTriggerPolicySpec(
            kind = OneShotPolicyKind.ENGINE_EVENT_REGION,
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
            engineEvent = EngineEventProgramPolicySpec(
                requiresEventStartInside = true,
                parameterGates = listOf(gate),
                laneCount = 5,
                maximumDecodedOneShotFrames = 2_048,
                logicalVoiceLimit = 2_048,
                softwareRealVoiceBudget = 256,
            ),
        )

    private fun oneShotBank(effects: List<SampleEffectSpec>): Map<String, PcmLoopData> =
        effects.associate { effect ->
            val frequency = 90.0 + abs(effect.id.hashCode() % 300)
            val tone = FloatArray(2_048) { frame ->
                (sin(2.0 * PI * frequency * frame / 48_000.0) * 0.25).toFloat()
            }
            effect.assetName to PcmLoopData(arrayOf(tone, tone.copyOf()), 48_000)
        }

    private data class TurboEventFixture(
        val profile: EngineSampleProfile,
        val bank: Map<String, PcmLoopData>,
    )

    private fun turboEventFixture(
        mode: TurboEventProgramMode,
        pcmFrames: Int,
        placementMinimumBoost: Double = 0.0,
        placementMaximumBoost: Double = 1.5,
        timelineStartFrames: Long? = null,
        timelinePeriodFrames: Long? = null,
        controlGainCurves: List<OneShotControlCurveSpec> = emptyList(),
        pitchAutomations: List<OneShotPitchAutomationSpec> = emptyList(),
        includeSequentialSilentLeaf: Boolean = false,
    ): TurboEventFixture {
        val effect = SampleEffectSpec(
            id = "turbo_event_source",
            control = SampleEffectControls.turbo,
            assetName = "turbo_event_source",
            trigger = SampleEffectTrigger.TURBO_EVENT,
            baseGainDb = -12.0,
            auditionable = false,
            polyphonicTemplate = true,
            softwareVoicePriority = GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY,
            looping = false,
            startsActive = false,
        )
        val effectiveGainCurves = controlGainCurves.ifEmpty {
            listOf(
                OneShotControlCurveSpec(
                    OneShotGateControl.BOOST,
                    AutomationCurve(listOf(CurvePoint(0.0, 1.0), CurvePoint(1.5, 1.0))),
                ),
            )
        }
        val trackLeaf = oneShotLeaf("turbo_leaf", effect.id).copy(
            captureControlValues = listOf(
                OneShotControlValueSpec(OneShotGateControl.BOOST, 0.5),
                OneShotControlValueSpec(OneShotGateControl.BOV, 0.0),
                OneShotControlValueSpec(OneShotGateControl.BOV_DECAY, 10.0),
            ),
            controlGainCurves = effectiveGainCurves,
            pitchAutomations = pitchAutomations,
            sourceVerificationPayloadSha256 = "3".repeat(64),
        )
        val silentLeaf = OneShotSilentNodeSpec(
            id = "silent_leaf",
            triggerChance = 1.0,
            sourceGuid = "99999999-8888-7777-6666-555555555555",
            resolvedRole = if (mode == TurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT) {
                "BOV"
            } else {
                "TURBO_TRANSIENT"
            },
            sourceVerificationPayloadSha256 = "4".repeat(64),
        )
        val roots: List<String>
        val nodes: List<OneShotNodeSpec>
        if (includeSequentialSilentLeaf) {
            roots = listOf("turbo_group")
            nodes = listOf(
                OneShotGroupNodeSpec(
                    id = "turbo_group",
                    triggerChance = 1.0,
                    playMode = OneShotPlayMode.SEQUENTIAL,
                    selectionMode = OneShotSelectionMode.NORMAL,
                    members = listOf(
                        OneShotGroupMemberSpec(silentLeaf.id, 1.0, 0),
                        OneShotGroupMemberSpec(trackLeaf.id, 1.0, 1),
                    ),
                ),
                silentLeaf,
                trackLeaf,
            )
        } else {
            roots = listOf(trackLeaf.id)
            nodes = listOf(trackLeaf)
        }
        val policy = OneShotTriggerPolicySpec(
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
                mode = mode,
                placementMinimumBoost = if (mode == TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT) {
                    null
                } else {
                    placementMinimumBoost
                },
                placementMaximumBoost = if (mode == TurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT) {
                    null
                } else {
                    placementMaximumBoost
                },
                includeMinimum = true,
                includeMaximum = true,
                timelineStartFrames = timelineStartFrames,
                timelinePeriodFrames = timelinePeriodFrames,
                coreProgram = mode != TurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT,
            ),
        )
        val program = OneShotProgramSpec(
            id = "turbo_event_program",
            trigger = SampleEffectTrigger.TURBO_EVENT,
            rootNodeIds = roots,
            nodes = nodes,
            policy = policy,
            softwareVoicePriority = GlobalVoiceArbiter.FMOD_DEFAULT_EVENT_PRIORITY,
        )
        val turboPhysics = TurboPhysicsSpec(
            bovPressureThreshold = 0.1,
            units = arrayOf(
                TurboPhysicsUnitSpec(
                    maximumBoost = 1.0,
                    wastegate = 1.0,
                    referenceRpm = 5_000.0,
                    gamma = 1.0,
                    lagUp = 1_000.0,
                    lagDown = 10.0,
                    controllerProgramIndex = -1,
                ),
            ),
            controllerBank = null,
        )
        val turboProfile = profile.copy(
            id = "turbo_event_${mode.name.lowercase()}",
            layers = emptyList(),
            effects = listOf(effect),
            oneShotPrograms = listOf(program),
            turboPhysics = turboPhysics,
            throttleOutputGainDb = null,
        )
        val tone = FloatArray(pcmFrames) { frame ->
            (sin(2.0 * PI * 260.0 * frame / 48_000.0) * 0.25).toFloat()
        }
        return TurboEventFixture(
            turboProfile,
            mapOf(effect.assetName to PcmLoopData(arrayOf(tone, tone.copyOf()), 48_000)),
        )
    }

    private data class PersistentLimiterFixture(
        val profile: EngineSampleProfile,
        val bank: Map<String, PcmLoopData>,
    )

    private fun persistentLimiterFixture(
        mode: PersistentLimiterProgramMode,
        pcmFrames: Int,
        placement: LimiterDecayPlacementSpec? = null,
    ): PersistentLimiterFixture {
        val polyphonic = mode == PersistentLimiterProgramMode.DECAY_REGION_ONE_SHOT
        val effect = SampleEffectSpec(
            id = "limiter_source",
            control = SampleEffectControls.limiter,
            assetName = "limiter_source",
            trigger = SampleEffectTrigger.LIMITER_EVENT,
            baseGainDb = -12.0,
            polyphonicTemplate = polyphonic,
            looping = !polyphonic,
            startsActive = false,
        )
        val program = OneShotProgramSpec(
            id = "persistent_limiter_program",
            trigger = SampleEffectTrigger.LIMITER_EVENT,
            rootNodeIds = listOf("limiter_leaf"),
            nodes = listOf(oneShotLeaf("limiter_leaf", effect.id)),
            policy = OneShotTriggerPolicySpec(
                kind = OneShotPolicyKind.PERSISTENT_LIMITER_EVENT,
                minimumRpm = profile.limiterRpm,
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
                limiterEvent = PersistentLimiterProgramPolicySpec(
                    mode = mode,
                    decayGainCurve = AutomationCurve(
                        listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 0.0)),
                    ),
                    decayPlacement = placement,
                    timelinePeriodFrames = if (
                        mode == PersistentLimiterProgramMode.TIMELINE_PERIOD_LOOP
                    ) pcmFrames else null,
                    oneShotLaneCount = if (polyphonic) {
                        minOf(2_048, (pcmFrames + 479) / 480)
                    } else {
                        0
                    },
                ),
            ),
        )
        val limiterProfile = profile.copy(
            id = "persistent_limiter_${mode.name.lowercase()}",
            layers = emptyList(),
            effects = listOf(effect),
            oneShotPrograms = listOf(program),
            limiterHz = 20.0,
            throttleOutputGainDb = null,
        )
        val tone = FloatArray(pcmFrames) { frame ->
            (sin(2.0 * PI * 220.0 * frame / 48_000.0) * 0.25).toFloat()
        }
        return PersistentLimiterFixture(
            limiterProfile,
            mapOf(effect.assetName to PcmLoopData(arrayOf(tone, tone.copyOf()), 48_000)),
        )
    }

    private fun strongestGain(rpm: Double, throttle: Double): Double =
        profile.layers.maxOf { it.gainAt(rpm, throttle) }

    private fun testBank(): Map<String, PcmLoopData> = profile.requiredAssets.associateWith { asset ->
        val frequency = 70.0 + abs(asset.hashCode() % 220)
        val left = FloatArray(2_048) { frame ->
            (sin(2.0 * PI * frequency * frame / 44_100.0) * 0.35).toFloat()
        }
        PcmLoopData(
            channelSamples = arrayOf(left, FloatArray(left.size) { -left[it] * 0.75f }),
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
    ): ByteArray {
        val dataBytes = interleaved.size * 2
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

private fun rendererTestProfile(): EngineSampleProfile {
    fun curve(vararg points: Pair<Double, Double>) = AutomationCurve(
        points.map { (input, output) -> CurvePoint(input, output) },
    )
    val layers = listOf(
        SampleLayerSpec(
            id = "idle_low", assetName = "idle.wav", role = SampleLayerRole.IDLE,
            startRpm = 0.0, endRpm = 2_000.0, autopitchRootRpm = 1_040.0,
            baseGainDb = -14.5, throttleGainDb = curve(0.0 to 0.0, 1.0 to 4.0),
        ),
        SampleLayerSpec(
            id = "c2", assetName = "c2.wav", role = SampleLayerRole.COAST,
            startRpm = 1_000.0, endRpm = 10_000.0, autopitchRootRpm = 4_500.0,
            baseGainDb = -6.0, throttleAmplitudeCurve = curve(0.0 to 1.0, 1.0 to 0.30),
        ),
        SampleLayerSpec(
            id = "c1", assetName = "c1.wav", role = SampleLayerRole.COAST,
            startRpm = 5_000.0, endRpm = 10_000.0, autopitchRootRpm = 7_400.0,
            baseGainDb = -12.0, loopCrossfadeFrames = 512,
        ),
        SampleLayerSpec(
            id = "c3", assetName = "c3.wav", role = SampleLayerRole.COAST,
            startRpm = 3_500.0, endRpm = 9_000.0, autopitchRootRpm = 6_200.0,
            baseGainDb = -14.0, loopCrossfadeFrames = 512,
        ),
        SampleLayerSpec(
            id = "limiter", assetName = "limiter.wav", role = SampleLayerRole.LIMITER,
            startRpm = 8_000.0, endRpm = 10_000.0, autopitchRootRpm = 8_350.0,
            baseGainDb = -18.0, loopCrossfadeFrames = 512,
        ),
        SampleLayerSpec(
            id = "engine_noise_7", assetName = "engine_noise_7.wav", role = SampleLayerRole.TEXTURE,
            startRpm = 1_000.0, endRpm = 10_000.0, autopitchRootRpm = 5_000.0,
            baseGainDb = -0.5, throttleAmplitudeCurve = curve(0.0 to 0.15, 1.0 to 0.55),
        ),
    )
    val effects = listOf(
        SampleEffectSpec(
            id = "transmission_loop", control = SampleEffectControls.transmission,
            assetName = "transmission.wav", trigger = SampleEffectTrigger.TRANSMISSION_LOOP,
            baseGainDb = -14.0,
        ),
        SampleEffectSpec(
            id = "shift_up", control = SampleEffectControls.gearChanges,
            assetName = "shift_up.wav", trigger = SampleEffectTrigger.SHIFT_UP,
            baseGainDb = -8.0,
        ),
        SampleEffectSpec(
            id = "shift_down", control = SampleEffectControls.gearChanges,
            assetName = "shift_down.wav", trigger = SampleEffectTrigger.SHIFT_DOWN,
            baseGainDb = -8.0,
        ),
    )
    return EngineSampleProfile(
        id = "renderer_test_profile",
        displayName = "Renderer test profile",
        assetDirectory = "test",
        previewAssetName = "test.jpg",
        outputSampleRate = 48_000,
        minimumRpm = 0.0,
        maximumRpm = 10_000.0,
        idleRpm = 1_040.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        upshiftRpm = 8_200.0,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftDurationSeconds = 0.060,
        downshiftDurationSeconds = 0.150,
        layers = layers,
        effects = effects,
        throttleOutputGainDb = curve(0.0 to 0.0, 1.0 to 0.75),
    )
}
