package com.gabrielpc.enginesoundsimulator.catalog

import com.gabrielpc.enginesoundsimulator.audio.NativeSoundFamilyLoader
import com.gabrielpc.enginesoundsimulator.audio.engineSampleProfileFor
import com.gabrielpc.enginesoundsimulator.tuning.EngineTuning
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogValidationTest {
    @Test
    fun turboTrackAdvertisesItsAuthoredSpoolCharacterWithoutDuplicatePcm() {
        val effects = deriveCoreEffectAvailability(setOf(PackTrackRole.IDLE, PackTrackRole.TURBO))

        assertTrue(effects.turbo)
        assertTrue(effects.spool)
        assertFalse(effects.bov)
    }

    @Test
    fun authoredOverrunAdvertisesBothOverrunAndPopsBangAuditionCapabilities() {
        val effects = deriveCoreEffectAvailability(setOf(PackTrackRole.IDLE, PackTrackRole.OVERRUN))

        assertTrue(effects.overrun)
        assertTrue(effects.popsBangsCracks)
    }

    @Test
    fun officialIndexContainsExactly178UniqueUsableCars() {
        assertEquals(178, OfficialCarIndex.cars.size)
        assertEquals(178, OfficialCarIndex.cars.map { it.id }.toSet().size)
        assertTrue(OfficialCarIndex.cars.none { it.id in setOf("ks_ferrari_488_challenge_evo", "ks_ferrari_488_gt3_2020") })
    }

    @Test
    fun strictJsonRejectsDuplicateKeys() {
        assertThrows(JsonValidationException::class.java) {
            StrictJson.parse("{\"schemaVersion\":1,\"schemaVersion\":1}".toByteArray())
        }
    }

    @Test
    fun forbiddenLoadScannerMatchesWholeAsciiTokensWithoutRegexAllocation() {
        assertTrue(StrictJson.containsForbiddenLoadToken("{\"role\":\"LOAD\"}".toByteArray()))
        assertTrue(StrictJson.containsForbiddenLoadToken("load_value".toByteArray()))
        assertFalse(StrictJson.containsForbiddenLoadToken("download".toByteArray()))
        assertFalse(StrictJson.containsForbiddenLoadToken("loader2".toByteArray()))
    }

    @Test
    fun canonicalWriterPreservesHashesForSortedAndUnsortedObjects() {
        val sorted = StrictJson.parse("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}".toByteArray())
        val unsorted = StrictJson.parse("{\"b\":{\"d\":3,\"c\":2},\"a\":1}".toByteArray())
        assertArrayEquals(StrictJson.canonicalBytes(sorted), StrictJson.canonicalBytes(unsorted))

        val root = StrictJson.parse(
            "{\"a\":1,\"catalogSha256\":\"ignored\",\"z\":2}".toByteArray(),
        ).asObject("root")
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(StrictJson.canonicalBytes(JsonValue.ObjectValue(root - "catalogSha256")))
        assertArrayEquals(
            expected,
            StrictJson.canonicalSha256ExcludingObjectKey(root, "catalogSha256"),
        )

        val unicodeRoot = StrictJson.parse(
            """{"catalogSha256":"ignored","emoji":"\ud83d\ude00","unpaired":"\ud800"}"""
                .toByteArray(),
        ).asObject("unicodeRoot")
        val expectedUnicodeHash = MessageDigest.getInstance("SHA-256").digest(
            StrictJson.canonicalBytes(JsonValue.ObjectValue(unicodeRoot - "catalogSha256")),
        )
        assertArrayEquals(
            expectedUnicodeHash,
            StrictJson.canonicalSha256ExcludingObjectKey(unicodeRoot, "catalogSha256"),
        )
    }

    @Test
    fun exactKeyFastPathStillRejectsMissingAndUnknownFields() {
        mapOf<String, JsonValue>("a" to JsonValue.NullValue)
            .requireExactKeys("fixture", setOf("a"))
        assertThrows(JsonValidationException::class.java) {
            mapOf<String, JsonValue>("a" to JsonValue.NullValue)
                .requireExactKeys("fixture", setOf("a", "b"))
        }
        assertThrows(JsonValidationException::class.java) {
            mapOf<String, JsonValue>("a" to JsonValue.NullValue, "b" to JsonValue.NullValue)
                .requireExactKeys("fixture", setOf("a"))
        }
    }

    @Test
    fun strictJsonFastStringPathPreservesEscapeAndControlValidation() {
        assertEquals(
            "plain",
            StrictJson.parse("\"plain\"".toByteArray()).asString("fixture"),
        )
        assertEquals(
            "line\nA",
            StrictJson.parse("\"line\\n\\u0041\"".toByteArray()).asString("fixture"),
        )
        assertThrows(JsonValidationException::class.java) {
            StrictJson.parse(byteArrayOf('"'.code.toByte(), 0x01, '"'.code.toByte()))
        }
        assertThrows(JsonValidationException::class.java) {
            StrictJson.parse("\"\\u00xz\"".toByteArray())
        }
    }

    @Test
    fun manifestKeepsExclusiveLoopAndCalculatedGearLandingRpm() {
        val manifest = SoundFamilyManifestV1.parse(validManifest().toByteArray())
        assertEquals(100L, manifest.tracks.single().loopEndFrameExclusive)
        assertEquals(4_000.0, manifest.cars.single().gearbox.downshiftLandingRpmByGear.getValue(2), 0.0)
        assertEquals(PackTrackRole.IDLE, manifest.tracks.single().role)
        assertEquals(setOf(OfficialCarQuirks.AUTHORED_BOV_LANE_SILENT), manifest.quirks)
    }

    @Test
    fun sharedTrackPathCountsPhysicalPcmOnceAndKeepsPerRoleMetadata() {
        val manifest = SoundFamilyManifestV1.parse(sharedPathManifest().toByteArray())

        assertEquals(2, manifest.tracks.size)
        assertEquals(400L, manifest.totalDecodedBytes)
        assertEquals(setOf("idle", "idle_alias"), manifest.tracks.map { it.id }.toSet())
    }

    @Test
    fun sharedTrackPathRejectsConflictingPhysicalPcmMetadata() {
        val failure = assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                sharedPathManifest(secondPcmSha256 = "9".repeat(64)).toByteArray(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("identical physical PCM metadata"))
    }

    @Test
    fun manifestV2RequiresExplicitTopologyAndPerCarProgramPolicies() {
        val manifest = SoundFamilyManifestV1.parse(validV2Manifest().toByteArray())
        assertEquals(2, manifest.schemaVersion)
        assertTrue(manifest.oneShotPrograms.isEmpty())
        assertTrue(manifest.cars.single().oneShotTriggerPolicies.isEmpty())

        val missingPolicies = validV2Manifest().replace(",\"oneShotTriggerPolicies\":{}", "")
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(missingPolicies.toByteArray())
        }
        val unknownPolicy = validV2Manifest().replace(
            "\"oneShotTriggerPolicies\":{}",
            "\"oneShotTriggerPolicies\":{\"missing_program\":{\"kind\":\"SHIFT_UP\",\"minimumRpm\":0,\"maximumRpm\":null,\"armPedal\":null,\"firePedal\":null,\"armBoost\":null,\"initialPeakPedal\":null,\"initialArmPedal\":null,\"initialFirePedal\":null,\"minimumArmMs\":0,\"cooldownMs\":0,\"periodHz\":null}}",
        )
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(unknownPolicy.toByteArray())
        }
    }

    @Test
    fun manifestV2RequiresOracleBoundSoftwarePrioritiesEverywhere() {
        val manifest = SoundFamilyManifestV1.parse(validV2OneShotManifest().toByteArray())
        assertEquals("5".repeat(64), manifest.softwareChannelPriorityOracleSha256)
        assertEquals(64, manifest.tracks.first { it.id == "idle" }.softwareChannelPriority)
        assertEquals(128, manifest.tracks.first { it.id == "shift_up" }.softwareChannelPriority)
        assertEquals(128, manifest.oneShotPrograms.single().softwareChannelPriority)

        val profile = manifest.engineSampleProfileFor("tatuusfa1")
        assertEquals(64, profile.layers.single().softwareVoicePriority)
        assertEquals(128, profile.effects.single().softwareVoicePriority)
        assertEquals(128, profile.oneShotPrograms.single().softwareVoicePriority)

        listOf(
            validV2Manifest().replace(",\"softwareChannelPriority\":64", ""),
            validV2Manifest().replace("\"softwareChannelPriority\":64", "\"softwareChannelPriority\":257"),
            validV2Manifest().replace(
                ",\"softwareChannelPriorityOracleSha256\":\"${"5".repeat(64)}\"",
                "",
            ),
            validV2OneShotManifest().replaceFirst(
                "\"softwareChannelPriority\":128",
                "\"softwareChannelPriority\":64",
            ),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2RequiresExplicitTrackPitchModeAndRunsPropertyOneAsReplacementRate() {
        val auto = SoundFamilyManifestV1.parse(validV2Manifest().toByteArray())
        assertEquals(PackTrackPitchMode.AUTO_PITCH_RPM_RATIO, auto.tracks.single().pitchMode)
        assertTrue(auto.tracks.single().pitchCurve.isEmpty())

        val propertyOneJson = validV2Manifest().replace(
            "\"pitchMode\":\"AUTO_PITCH_RPM_RATIO\",\"pitchCurve\":[]," +
                "\"pitchCurveInterpolation\":\"NONE\"",
            "\"pitchMode\":\"AUTHORED_PROPERTY_ONE_RELATIVE_RATE\"," +
                "\"pitchCurve\":[[0,0.5],[1000,1]]," +
                "\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\"",
        )
        val propertyOne = SoundFamilyManifestV1.parse(propertyOneJson.toByteArray())
        val layer = propertyOne.engineSampleProfileFor("tatuusfa1").layers.single()
        assertEquals(0.5, layer.playbackRatio(0.0), 0.0)
        assertEquals(0.75, layer.playbackRatio(500.0), 1e-12)
        assertEquals(1.0, layer.playbackRatio(2_000.0), 0.0)

        val inclusiveBounds = SoundFamilyManifestV1.parse(
            propertyOneJson.replace(
                "\"pitchCurve\":[[0,0.5],[1000,1]]",
                "\"pitchCurve\":[[0,16],[1000,1.0002]]",
            ).toByteArray(),
        ).engineSampleProfileFor("tatuusfa1").layers.single()
        assertEquals(16.0, inclusiveBounds.playbackRatio(0.0), 0.0)
        assertEquals(1.0002, inclusiveBounds.playbackRatio(1_000.0), 0.0)

        val autoFields = "\"pitchMode\":\"AUTO_PITCH_RPM_RATIO\",\"pitchCurve\":[]," +
            "\"pitchCurveInterpolation\":\"NONE\""
        listOf(
            validV2Manifest().replace(",\"pitchMode\":\"AUTO_PITCH_RPM_RATIO\"", ""),
            validV2Manifest().replace("\"pitchCurve\":[]", "\"pitchCurve\":[[0,1],[1000,1]]"),
            validV2Manifest().replace("\"pitchCurveInterpolation\":\"NONE\"", "\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\""),
            validV2Manifest().replace(
                autoFields,
                "\"pitchMode\":\"AUTHORED_PROPERTY_ONE_RELATIVE_RATE\"," +
                    "\"pitchCurve\":[[0,0.5]],\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\"",
            ),
            validV2Manifest().replace(
                autoFields,
                "\"pitchMode\":\"AUTHORED_PROPERTY_ONE_RELATIVE_RATE\"," +
                    "\"pitchCurve\":[[0,0.5],[1000,16.0001]]," +
                    "\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\"",
            ),
            validV2Manifest().replace(
                autoFields,
                "\"pitchMode\":\"AUTHORED_PROPERTY_ONE_RELATIVE_RATE\"," +
                    "\"pitchCurve\":[[1,0.5],[1000,1]]," +
                    "\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\"",
            ),
            validV2Manifest().replace(
                autoFields,
                "\"pitchMode\":\"AUTHORED_PROPERTY_ONE_RELATIVE_RATE\"," +
                    "\"pitchCurve\":[[0,0.5],[1000,1.00021]]," +
                    "\"pitchCurveInterpolation\":\"CLAMPED_LINEAR\"",
            ),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun runtimeProfilePreservesEveryManifestOneShotLeafEffectId() {
        listOf(validV2OneShotManifest(), validV2EngineEventManifest()).forEach { json ->
            val manifest = SoundFamilyManifestV1.parse(json.toByteArray())
            val profile = manifest.engineSampleProfileFor("tatuusfa1")
            val effectIds = profile.effects.mapTo(hashSetOf()) { it.id }
            val leafIds = profile.oneShotPrograms.flatMap { program ->
                program.nodes.filterIsInstance<com.gabrielpc.enginesoundsimulator.audio.OneShotTrackNodeSpec>()
                    .map { it.effectId }
            }
            assertTrue(effectIds.containsAll(leafIds))
        }
    }

    @Test
    fun manifestV2StrictlyRecordsAuthoredDspBakedIntoTargetCapture() {
        val dspJson = """{"name":"FMOD Gain","version":65536,"parameters":{"gainDb":-0.5,"invert":false},"treatment":"BAKED_INTO_TARGET_ONLY_CAPTURE","evidence":"FMOD108_SET_PARAMETER_CALLBACK"}"""
        val valid = validV2Manifest().replace("\"authoredDsp\":[]", "\"authoredDsp\":[$dspJson]")
        val dsp = SoundFamilyManifestV1.parse(valid.toByteArray()).authoredDsp.single()
        assertEquals("FMOD Gain", dsp.name)
        assertEquals(65_536, dsp.version)
        assertEquals(-0.5, dsp.gainDb, 0.0)
        assertFalse(dsp.invert)

        listOf(
            valid.replace("\"FMOD Gain\"", "\"Unknown DSP\""),
            valid.replace("\"gainDb\":-0.5", "\"gainDb\":0.5"),
            valid.replace("FMOD108_SET_PARAMETER_CALLBACK", "ASSUMED"),
            valid.replace("\"invert\":false", "\"invert\":false,\"extra\":0"),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2AcceptsOnlyTheProvenCabinEngineEventRegionContract() {
        val valid = validV2EngineEventManifest()
        val program = SoundFamilyManifestV1.parse(valid.toByteArray()).oneShotPrograms.single()
        assertEquals(PackOneShotTrigger.ENGINE_EVENT, program.trigger)
        assertEquals(
            PackEngineEventArmingMode.EVENT_START_INSIDE_REQUIRED,
            program.engineEventPolicy?.armingMode,
        )
        val leaf = program.nodes.filterIsInstance<PackOneShotTrackNodeV2>().single()
        assertEquals(
            PackZeroGainVirtualizationKind.EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE,
            leaf.zeroGainVirtualization.kind,
        )
        assertEquals(512, leaf.zeroGainVirtualization.phaseHoldLatencyWriterFrames)
        assertEquals(64, leaf.zeroGainVirtualization.transition?.exactZeroFromWriterFrame)
        assertEquals(
            PackZeroGainTransitionPhaseTreatment.RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET,
            leaf.zeroGainVirtualization.transition?.phaseTreatment,
        )
        assertEquals(0.0,
            leaf.zeroGainVirtualization.transition?.restoreCapturePcmPhaseOffsetFrames ?: Double.NaN,
            0.0)
        assertEquals(
            PackEngineTransientReentryPolicy
                .CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE,
            leaf.engineTransientReentryPolicy,
        )

        val ferrariTransition = """{
          "policy":"RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO",
          "frameDomain":"STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
          "gainInterpolation":"LINEAR_PER_WRITER_FRAME","gainAtTransitionStart":1,
          "gainAtExactZero":0,"retainPreZeroGainWriterFrames":514,
          "linearFadeWriterFrames":55,"exactZeroFromWriterFrame":569,
          "pitchDuringTransition":"AUTHORED_STATIC_BAKED_PITCH",
          "phaseTreatment":"RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
          "residualMaximumAbsolutePcmLsb":1,"acceptanceBoundMaximumAbsolutePcmLsb":1,
          "positiveGainReturnBeforePhaseHoldPolicy":"CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD",
          "subsequentExactZeroCrossingPolicy":"RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE",
          "restoreCapturePcmPhaseOffsetFrames":0,
          "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames":512
        }""".trimIndent()
        val ferrariSemantic = """{
          "kind":"EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
          "mixerZeroGateAction":"APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING",
          "ordinaryNonzeroGainSmoothingUnaffected":true,
          "decodePhaseBeforeHold":"CURRENT_ACTIVE_VOICE_PITCH",
          "phaseHoldLatencyWriterFrames":1536,
          "phaseAndDeadlineAdvanceWriterFramesBeforeHold":1536,
          "phaseHoldLatencyFrameDomain":"STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
          "holdDecodePhaseAfterLatency":true,"pauseNaturalEndDeadlineWhileHeld":true,
          "reaudibilizationBeforeDeadline":"CONTINUE_FROM_HELD_LOGICAL_PHASE",
          "writerDspBlockFrames":256,"zeroTransition":$ferrariTransition,
          "channelGetPositionWhileVirtualIsRuntimeAuthoritative":false
        }""".trimIndent()
        val ferrari812 = SoundFamilyManifestV1.parse(
            validV2EngineEventManifest(runtimeSemanticOverride = ferrariSemantic)
                .replace("\"rootRpm\":3000", "\"rootRpm\":null")
                .replace("\"liveVarispeed\":true", "\"liveVarispeed\":false")
                .replace("\"runtimeVarispeed\":true", "\"runtimeVarispeed\":false")
                .replace(
                    "\"updatesContinuouslyWhileVoiceIsActive\":true",
                    "\"updatesContinuouslyWhileVoiceIsActive\":false",
                )
                .replace(
                    "currentPresentationEngineRpm/rootRpm",
                    "1.0;authoredStaticPitchBakedInPcm",
                )
                .toByteArray(),
        ).oneShotPrograms.single().nodes.filterIsInstance<PackOneShotTrackNodeV2>().single()
        assertEquals(1_536, ferrari812.zeroGainVirtualization.phaseHoldLatencyWriterFrames)
        assertEquals(514, ferrari812.zeroGainVirtualization.transition?.retainPreZeroGainWriterFrames)
        assertEquals(55, ferrari812.zeroGainVirtualization.transition?.linearFadeWriterFrames)

        val sourceBoundOffsetSemantic = ferrariSemantic
            .replace(
                "\"phaseTreatment\":\"RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET\"",
                "\"phaseTreatment\":\"APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET\"",
            )
            .replace("\"restoreCapturePcmPhaseOffsetFrames\":0",
                "\"restoreCapturePcmPhaseOffsetFrames\":-0.483")
        val sourceBoundOffset = SoundFamilyManifestV1.parse(
            validV2EngineEventManifest(runtimeSemanticOverride = sourceBoundOffsetSemantic)
                .replace("\"rootRpm\":3000", "\"rootRpm\":null")
                .replace("\"liveVarispeed\":true", "\"liveVarispeed\":false")
                .replace("\"runtimeVarispeed\":true", "\"runtimeVarispeed\":false")
                .replace("\"updatesContinuouslyWhileVoiceIsActive\":true",
                    "\"updatesContinuouslyWhileVoiceIsActive\":false")
                .replace("currentPresentationEngineRpm/rootRpm",
                    "1.0;authoredStaticPitchBakedInPcm")
                .toByteArray(),
        ).engineSampleProfileFor("tatuusfa1").oneShotPrograms.single().nodes
            .filterIsInstance<com.gabrielpc.enginesoundsimulator.audio.OneShotTrackNodeSpec>()
            .single().zeroGainVirtualization.transition
        assertEquals(
            com.gabrielpc.enginesoundsimulator.audio.ZeroGainTransitionPhaseTreatment
                .APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET,
            sourceBoundOffset?.phaseTreatment,
        )
        assertEquals(-0.483, sourceBoundOffset?.restoreCapturePcmPhaseOffsetFrames ?: Double.NaN, 0.0)

        val noReentry = SoundFamilyManifestV1.parse(
            validV2EngineEventManifest(
                reentryPolicy =
                    "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER",
            ).toByteArray(),
        ).engineSampleProfileFor("tatuusfa1").oneShotPrograms.single().nodes
            .filterIsInstance<com.gabrielpc.enginesoundsimulator.audio.OneShotTrackNodeSpec>()
            .single()
        assertEquals(
            com.gabrielpc.enginesoundsimulator.audio.EngineTransientReentryPolicy
                .NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER,
            noReentry.engineTransientReentryPolicy,
        )

        val fixedGeometry = valid.replace(
            "EVENT_START_INSIDE_REQUIRED",
            "FIXED_COMPILER_GEOMETRY_AT_EVENT_START",
        )
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(fixedGeometry.toByteArray())
        }
        val mismatchedLeafGate = valid.replaceFirst(
            "\"minimum\":2000",
            "\"minimum\":2100",
        )
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(mismatchedLeafGate.toByteArray())
        }

        listOf(
            valid.replace(
                "\"pitchTreatment\":",
                "\"pitchTreatmentUnexpected\":",
            ),
            Regex("\\\"verificationPayloadSha256\\\":\\\"[0-9a-f]{64}\\\"").replaceFirst(
                valid,
                "\"verificationPayloadSha256\":\"${"0".repeat(64)}\"",
            ),
            valid.replace(
                "CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD",
                "KEEP_PENDING_HOLD",
            ),
            valid.replace("\"exactZeroFromWriterFrame\":64", "\"exactZeroFromWriterFrame\":65"),
            valid.replace("\"phaseHoldLatencyWriterFrames\":512", "\"phaseHoldLatencyWriterFrames\":513"),
            valid.replace("LIVE_CURRENT_RPM_PITCH", "AUTHORED_STATIC_BAKED_PITCH"),
            valid.replace("\"restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames\":512",
                "\"restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames\":511"),
            valid.replace("\"restoreCapturePcmPhaseOffsetFrames\":0",
                "\"restoreCapturePcmPhaseOffsetFrames\":513"),
            valid.replace("RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
                "APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET"),
            valid.replace(
                "CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE",
                "UNKNOWN_REENTRY_POLICY",
            ),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2StrictlyParsesAdvanceAndNoReachableZeroEngineVoicePolicies() {
        val transition = """{
          "policy":"IMMEDIATE_EXACT_ZERO",
          "frameDomain":"STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
          "gainInterpolation":"LINEAR_PER_WRITER_FRAME","gainAtTransitionStart":1,
          "gainAtExactZero":0,"retainPreZeroGainWriterFrames":0,
          "linearFadeWriterFrames":0,"exactZeroFromWriterFrame":0,
          "pitchDuringTransition":"LIVE_CURRENT_RPM_PITCH",
          "phaseTreatment":"RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
          "residualMaximumAbsolutePcmLsb":0,
          "acceptanceBoundMaximumAbsolutePcmLsb":1,
          "positiveGainReturnBeforePhaseHoldPolicy":"CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD",
          "subsequentExactZeroCrossingPolicy":"RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE",
          "restoreCapturePcmPhaseOffsetFrames":0,
          "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames":512
        }""".trimIndent()
        val advance = """{
          "kind":"ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO",
          "mixerZeroGateAction":"APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING",
          "ordinaryNonzeroGainSmoothingUnaffected":true,
          "decodePhaseWhileExactZero":"CURRENT_ACTIVE_VOICE_PITCH",
          "naturalEndDeadlineAdvancesWhileExactZero":true,
          "reaudibilizationBeforeDeadline":"CONTINUE_FROM_ADVANCED_LOGICAL_PHASE",
          "writerDspBlockFrames":256,"zeroTransition":$transition,
          "channelGetPositionWhileVirtualIsRuntimeAuthoritative":false
        }""".trimIndent()
        val advanceLeaf = SoundFamilyManifestV1.parse(
            validV2EngineEventManifest(runtimeSemanticOverride = advance).toByteArray(),
        ).oneShotPrograms.single().nodes.filterIsInstance<PackOneShotTrackNodeV2>().single()
        assertEquals(
            PackZeroGainVirtualizationKind.ADVANCE_DECODE_AND_LOGICAL_PHASE_WHILE_EXACT_ZERO,
            advanceLeaf.zeroGainVirtualization.kind,
        )
        assertEquals(0, advanceLeaf.zeroGainVirtualization.phaseHoldLatencyWriterFrames)
        assertEquals(PackZeroGainTransitionPolicy.IMMEDIATE_EXACT_ZERO,
            advanceLeaf.zeroGainVirtualization.transition?.policy)

        val notApplicable = """{
          "kind":"NOT_APPLICABLE",
          "logicalVoiceDeadlineAdvancesAtWriterTime":true,
          "decodeCursorTreatment":"NORMAL_ACTIVE_VOICE",
          "zeroTransition":{"policy":"NOT_APPLICABLE","reason":"EXACT_ZERO_COMBINED_AUTHORED_GAIN_NOT_REACHABLE_WHILE_ACTIVE"}
        }""".trimIndent()
        val noZeroLeaf = SoundFamilyManifestV1.parse(
            validV2EngineEventManifest(runtimeSemanticOverride = notApplicable).toByteArray(),
        ).oneShotPrograms.single().nodes.filterIsInstance<PackOneShotTrackNodeV2>().single()
        assertEquals(PackZeroGainVirtualizationKind.NOT_APPLICABLE,
            noZeroLeaf.zeroGainVirtualization.kind)
        assertEquals(null, noZeroLeaf.zeroGainVirtualization.transition)

        listOf(
            advance.replace("\"naturalEndDeadlineAdvancesWhileExactZero\":true",
                "\"naturalEndDeadlineAdvancesWhileExactZero\":false"),
            notApplicable.replace("NORMAL_ACTIVE_VOICE", "HOLD_CURSOR"),
            transition.replace("\"acceptanceBoundMaximumAbsolutePcmLsb\":1",
                "\"acceptanceBoundMaximumAbsolutePcmLsb\":1.0001").let { badTransition ->
                advance.replace(transition, badTransition)
            },
            transition.replace("RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
                "APPLY_SOURCE_BOUND_CAPTURE_PCM_RESTORE_PHASE_OFFSET")
                .replace("\"restoreCapturePcmPhaseOffsetFrames\":0",
                    "\"restoreCapturePcmPhaseOffsetFrames\":-0.483")
                .let { badTransition -> advance.replace(transition, badTransition) },
        ).forEach { invalidSemantic ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(
                    validV2EngineEventManifest(runtimeSemanticOverride = invalidSemantic)
                        .toByteArray(),
                )
            }
        }
    }

    @Test
    fun manifestV2PreservesTheExecutableBackedPersistentLimiterContract() {
        val cases = listOf(
            PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT to
                com.gabrielpc.enginesoundsimulator.audio.PersistentLimiterProgramMode.TIMELINE_PERIOD_LOOP,
            PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT to
                com.gabrielpc.enginesoundsimulator.audio.PersistentLimiterProgramMode.DECAY_REGION_ONE_SHOT,
            PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP to
                com.gabrielpc.enginesoundsimulator.audio.PersistentLimiterProgramMode.DECAY_REGION_LOOP,
        )
        cases.forEach { (packMode, runtimeMode) ->
            val manifest = SoundFamilyManifestV1.parse(
                validV2LimiterManifest(packMode).toByteArray(),
            )
            val program = manifest.oneShotPrograms.single()
            val policy = requireNotNull(program.limiterEventPolicy)
            assertEquals(PackOneShotTrigger.LIMITER_EVENT, program.trigger)
            assertEquals(packMode, policy.programMode)
            assertEquals("3".repeat(64), policy.sourceVerificationPayloadSha256)
            assertEquals(null, manifest.cars.single()
                .oneShotTriggerPolicies.getValue("limiter_program").periodHz)

            val profile = manifest.engineSampleProfileFor("tatuusfa1")
            val runtimeProgram = profile.oneShotPrograms.single()
            assertEquals(
                com.gabrielpc.enginesoundsimulator.audio.SampleEffectTrigger.LIMITER_EVENT,
                runtimeProgram.trigger,
            )
            assertEquals(runtimeMode, runtimeProgram.policy.limiterEvent?.mode)
            assertEquals(
                packMode != PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT,
                profile.effects.single { it.id == "limiter_source" }.looping,
            )
            assertFalse(profile.effects.single { it.id == "limiter_source" }.startsActive)
        }

        val timeline = validV2LimiterManifest(
            PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT,
        )
        listOf(
            timeline.replace(
                "acs.exe:0x140067134-0x14006718c",
                "acs.exe:0x140067134-0x14006718d",
            ),
            timeline.replace("\"periodFramesAt48k\":100", "\"periodFramesAt48k\":99"),
            timeline.replace(
                ",\"sourceVerificationPayloadSha256\":\"${"3".repeat(64)}\"",
                "",
            ),
            timeline.replace("\"periodHz\":null", "\"periodHz\":20", ignoreCase = false),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2KeepsCertifiedSilentLimiterEvidenceOutOfTheAudioGraph() {
        val silentSource =
            """{"sourceGuid":"11111111-2222-3333-4444-555555555555","role":"LIMITER","disposition":"AUTHORED_TARGET_SILENT","verificationPayloadSha256":"${"4".repeat(64)}"}"""
        val valid = validV2Manifest().replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$silentSource]",
        )
        val manifest = SoundFamilyManifestV1.parse(valid.toByteArray())
        assertEquals(1, manifest.certifiedSilentSources.size)
        assertEquals("11111111-2222-3333-4444-555555555555",
            manifest.certifiedSilentSources.single().sourceGuid)
        assertFalse(manifest.effects.limiter)
        assertTrue(manifest.tracks.none { it.role == PackTrackRole.LIMITER })

        val illegallyAudible = validV2LimiterManifest(
            PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT,
        ).replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$silentSource]",
        )
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(illegallyAudible.toByteArray())
        }
    }

    @Test
    fun manifestV2KeepsCertifiedSilentShiftsOutOfProgramsAndCapabilities() {
        val upGuid = "11111111-2222-3333-4444-555555555555"
        val downGuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val up = certifiedSilentSource(upGuid, "SHIFT_UP", "4".repeat(64))
        val down = certifiedSilentSource(downGuid, "SHIFT_DOWN", "6".repeat(64))
        val valid = validV2Manifest().replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$up,$down]",
        )

        val manifest = SoundFamilyManifestV1.parse(valid.toByteArray())
        assertEquals(setOf(PackTrackRole.SHIFT_UP, PackTrackRole.SHIFT_DOWN),
            manifest.certifiedSilentSources.mapTo(linkedSetOf()) { it.role })
        assertFalse("silent-only shifts must not expose the Shift control", manifest.effects.shift)
        assertTrue(manifest.tracks.none {
            it.role == PackTrackRole.SHIFT_UP || it.role == PackTrackRole.SHIFT_DOWN
        })
        assertTrue(manifest.oneShotPrograms.none {
            it.trigger == PackOneShotTrigger.SHIFT_UP || it.trigger == PackOneShotTrigger.SHIFT_DOWN
        })
    }

    @Test
    fun manifestV2RejectsInvalidOrContradictorySilentShiftEvidence() {
        val guid = "11111111-2222-3333-4444-555555555555"
        val compactGuid = guid.replace("-", "")
        val validCertificate = certifiedSilentSource(guid, "SHIFT_UP", "4".repeat(64))
        val silentOnly = validV2Manifest().replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$validCertificate]",
        )
        val deterministicTrackId = "shift_up_${compactGuid.take(16)}"
        val deterministicNodeId = "track_$compactGuid"
        val retainedShift = validV2OneShotManifest().replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$validCertificate]",
        )
        val duplicateGuidAcrossRoles = validV2Manifest().replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[$validCertificate," +
                certifiedSilentSource(guid, "SHIFT_DOWN", "6".repeat(64)) + "]",
        )
        val overCap = (1..513).joinToString(",") { index ->
            certifiedSilentSource(
                "%08x-0000-0000-0000-%012x".format(index, index),
                if (index % 2 == 0) "SHIFT_UP" else "SHIFT_DOWN",
                "%064x".format(index),
            )
        }

        listOf(
            silentOnly.replace("\"role\":\"SHIFT_UP\"", "\"role\":\"POP\""),
            silentOnly.replace("\"verificationPayloadSha256\":\"${"4".repeat(64)}\"",
                "\"verificationPayloadSha256\":\"${"4".repeat(63)}\""),
            silentOnly.replace("\"shift\":false", "\"shift\":true"),
            duplicateGuidAcrossRoles,
            validV2Manifest().replace(
                "\"certifiedSilentSources\":[]",
                "\"certifiedSilentSources\":[$overCap]",
            ),
            retainedShift.replace("\"shift_up\"", "\"$deterministicTrackId\""),
            retainedShift.replace("\"shift_leaf\"", "\"$deterministicNodeId\""),
            validV2TurboManifest().replace(
                "\"certifiedSilentSources\":[",
                "\"certifiedSilentSources\":[${certifiedSilentSource(guid, "SHIFT_UP", "3".repeat(64))},",
            ),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2RequiresExactTurboPhysicsAndControllerMapping() {
        val valid = validV2TurboManifest()
        val engine = SoundFamilyManifestV1.parse(valid.toByteArray()).cars.single().engine
        assertEquals(1, engine.turboPhysics.turbos.size)
        assertEquals(1.25, engine.turboPhysics.turbos.single().maximumBoost, 0.0)
        assertEquals("ctrl_turbo0.ini", engine.turboPhysics.turbos.single().controllerFile)

        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                valid.replace("\"turbos\":[{", "\"turbos\":[] , \"unused\":[{").toByteArray(),
            )
        }
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                valid.replace("\"controllerFile\":\"ctrl_turbo0.ini\"", "\"controllerFile\":null")
                    .toByteArray(),
            )
        }
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                valid.replace("\"lagDown\":2", "\"lagDown\":-0.1").toByteArray(),
            )
        }
    }

    @Test
    fun manifestV2PreservesStrictTurboEventProgramsAutomationAndSilentTopology() {
        val timeline = SoundFamilyManifestV1.parse(
            validV2TurboEventManifest(PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT)
                .toByteArray(),
        )
        val program = timeline.oneShotPrograms.single()
        assertEquals(PackOneShotTrigger.TURBO_EVENT, program.trigger)
        assertEquals(128, program.softwareChannelPriority)
        assertEquals(
            PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT,
            program.turboEventPolicy?.programMode,
        )
        assertEquals(120L, program.turboEventPolicy?.timelineStartFrames)
        assertEquals(480L, program.turboEventPolicy?.timelinePeriodFrames)
        val track = program.nodes.filterIsInstance<PackOneShotTrackNodeV2>().single()
        assertEquals(PackOneShotGateControl.BOOST, track.captureControlValues.single().control)
        assertEquals(0.5, track.captureControlValues.single().value, 0.0)
        assertEquals(1, track.controlGainCurves.size)
        assertEquals(1, track.pitchAutomations.size)
        assertEquals("3".repeat(64), track.sourceVerificationPayloadSha256)
        val silent = program.nodes.filterIsInstance<PackOneShotSilentNodeV2>().single()
        assertEquals(PackTrackRole.TURBO_TRANSIENT, silent.resolvedRole)
        assertEquals(PackTrackRole.TURBO_TRANSIENT, timeline.certifiedSilentSources.single().role)
        assertEquals(
            silent.sourceVerificationPayloadSha256,
            timeline.certifiedSilentSources.single().verificationPayloadSha256,
        )

        val profile = timeline.engineSampleProfileFor("tatuusfa1")
        val runtimeProgram = profile.oneShotPrograms.single()
        assertEquals(
            com.gabrielpc.enginesoundsimulator.audio.SampleEffectTrigger.TURBO_EVENT,
            runtimeProgram.trigger,
        )
        assertFalse(profile.effects.single().auditionable)
        assertEquals(0L, profile.auditionEffectMask)
        assertEquals(1, runtimeProgram.nodes
            .filterIsInstance<com.gabrielpc.enginesoundsimulator.audio.OneShotSilentNodeSpec>().size)

        val release = SoundFamilyManifestV1.parse(validV2SilentTurboReleaseManifest().toByteArray())
        assertEquals(
            PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT,
            release.oneShotPrograms.single().turboEventPolicy?.programMode,
        )
        assertTrue(release.oneShotPrograms.single().nodes.single() is PackOneShotSilentNodeV2)
        assertFalse(release.effects.bov)
        assertFalse(release.effects.turbo)
        assertFalse(release.effects.spool)
    }

    @Test
    fun turboEventControlGainAcceptsLockedCeilingAndRejectsAnythingAboveIt() {
        val fixture = validV2TurboEventManifest(PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT)
        val boundary = fixture.replace("[1.5,1.08536057]", "[1.5,38]")
        val parsed = SoundFamilyManifestV1.parse(boundary.toByteArray())
        val track = parsed.oneShotPrograms.single().nodes
            .filterIsInstance<PackOneShotTrackNodeV2>()
            .single()
        assertEquals(38.0, track.controlGainCurves.single().curve.last().output, 0.0)

        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                fixture.replace("[1.5,1.08536057]", "[1.5,38.000001]").toByteArray(),
            )
        }
    }

    @Test
    fun manifestV2RejectsAnyTurboEventContractDrift() {
        val valid = validV2TurboEventManifest(
            PackTurboEventProgramMode.PARAMETER_SHEET_EVENT_START_ONE_SHOT,
        )
        listOf(
            valid.replace("\"auditionable\":false", "\"auditionable\":true"),
            valid.replaceFirst("\"softwareChannelPriority\":128", "\"softwareChannelPriority\":64"),
            valid.replace(
                "multiplyActiveVoiceRateContinuously",
                "capturePitchOnly",
            ),
            valid.replace("[0,0.5],[0.5,1],[1.5,2]", "[0,0],[0.5,1],[1.5,2]"),
            valid.replaceFirst("\"control\":\"BOOST\"", "\"control\":\"ACCELERATOR\""),
            valid.replace("ONE_VOICE_PER_EVENT_START", "ALLOW_OVERLAP"),
            valid.replace(
                ",\"sourceVerificationPayloadSha256\":\"${"4".repeat(64)}\"",
                "",
            ),
            valid.replace("\"resolvedRole\":\"TURBO_TRANSIENT\"", "\"resolvedRole\":\"BOV\""),
            valid.replace(
                "\"certifiedSilentSources\":[{\"sourceGuid\":\"99999999-8888-7777-6666-555555555555\",\"role\":\"TURBO_TRANSIENT\",\"disposition\":\"AUTHORED_TARGET_SILENT\",\"verificationPayloadSha256\":\"${"4".repeat(64)}\"}]",
                "\"certifiedSilentSources\":[]",
            ),
            valid.replace(
                "\"role\":\"TURBO_TRANSIENT\",\"disposition\":\"AUTHORED_TARGET_SILENT\"",
                "\"role\":\"BOV\",\"disposition\":\"AUTHORED_TARGET_SILENT\"",
            ),
            valid.replace(
                "\"verificationPayloadSha256\":\"${"4".repeat(64)}\"}]",
                "\"verificationPayloadSha256\":\"${"3".repeat(64)}\"}]",
            ),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }

        val silentOnly = validV2SilentTurboReleaseManifest()
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                silentOnly.replace("\"bov\":false", "\"bov\":true").toByteArray(),
            )
        }
    }

    @Test
    fun manifestV2RequiresNormalizedClampedLinearPhysicalThrottleMap() {
        val valid = validV2Manifest().replace(
            "\"points\":[[0,0],[1,1]]",
            "\"points\":[[0,0],[0.5,0.8],[1,1]]",
        )
        val points = SoundFamilyManifestV1.parse(valid.toByteArray())
            .cars.single().engine.throttleMap.points
        assertEquals(0.8, points[1].output, 0.0)

        listOf(
            valid.replace("\"NORMALIZED_PEDAL\"", "\"PERCENT_PEDAL\""),
            valid.replace("\"CLAMPED_LINEAR\"", "\"CUBIC\""),
            valid.replace("[[0,0],[0.5,0.8],[1,1]]", "[[0.1,0],[1,1]]"),
            valid.replace("[[0,0],[0.5,0.8],[1,1]]", "[[0,0],[1,1.1]]"),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2PreservesAuthoredOrderForNonMonotonicAutoBlip() {
        val valid = validV2Manifest().replace(
            "\"autoBlipProfile\":[],\"autoBlipEndTimeMs\":0",
            "\"autoBlipProfile\":[[0,0],[20,0.9],[130,0.9],[60,0]],\"autoBlipEndTimeMs\":60",
        )
        val assist = SoundFamilyManifestV1.parse(valid.toByteArray())
            .cars.single().gearbox.engineGasAssist
        assertEquals(listOf(0.0, 20.0, 130.0, 60.0), assist.autoBlipProfile.map { it.input })
        assertEquals(60.0, assist.autoBlipEndTimeMs, 0.0)

        listOf(
            valid.replace("\"autoBlipEndTimeMs\":60", "\"autoBlipEndTimeMs\":130"),
            valid.replace(
                "AUTHORED_ORDER_FIRST_UPPER_BOUND_LINEAR",
                "SORTED_LINEAR",
            ),
            valid.replace("\"autoBlipClutchGateExclusive\":0.3183098861837907", "\"autoBlipClutchGateExclusive\":0.85"),
        ).forEach { invalid ->
            assertThrows(JsonValidationException::class.java) {
                SoundFamilyManifestV1.parse(invalid.toByteArray())
            }
        }
    }

    @Test
    fun manifestV2ValidatesWeightedOneShotTreeGatesAndTrackCoverage() {
        val manifest = SoundFamilyManifestV1.parse(validV2OneShotManifest().toByteArray())
        val program = manifest.oneShotPrograms.single()
        assertEquals(PackOneShotTrigger.SHIFT_UP, program.trigger)
        assertEquals(2, program.nodes.size)
        assertEquals(PackOneShotPlayMode.SMART_RANDOM, (program.nodes.first() as PackOneShotGroupNodeV2).playMode)
        assertEquals(
            PackOneShotGateControl.SHIFT_STATE,
            (program.nodes.last() as PackOneShotTrackNodeV2).parameterGates.single().control,
        )
        assertEquals(PackOneShotPolicyKind.SHIFT_UP, manifest.cars.single()
            .oneShotTriggerPolicies.getValue("shift_program").kind)

        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                validV2OneShotManifest().replace("\"weight\":1", "\"weight\":0").toByteArray(),
            )
        }
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                validV2OneShotManifest().replace("\"trackId\":\"shift_up\"", "\"trackId\":\"idle\"")
                    .toByteArray(),
            )
        }
    }

    @Test
    fun manifestRejectsUnsupportedOrCarMismatchedQuirks() {
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                validManifest().replace(
                    "authoredBovLaneSilent",
                    "inventedRuntimeBehavior",
                ).toByteArray(),
            )
        }
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(
                validManifest().replace("tatuusfa1", "abarth500").toByteArray(),
            )
        }
    }

    @Test
    fun manifestRejectsForbiddenRoleTokenAnywhere() {
        val forbidden = validManifest().replace("\"IDLE\"", "\"LOAD\"")
        val error = assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(forbidden.toByteArray())
        }
        assertTrue(error.message.orEmpty().contains("forbidden", ignoreCase = true))
    }

    @Test
    fun manifestRejectsTraversalAndUncalculatedDownshiftThreshold() {
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(validManifest().replace("audio/idle.flac", "audio/../idle.flac").toByteArray())
        }
        assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(validManifest().replace("\"2\":4000", "\"2\":3900").toByteArray())
        }
    }

    @Test
    fun manifestRequiresAudibleAuthoredIdleCurvesAtEveryMemberIdleRpm() {
        val silentRpmCurve = validManifest().replace(
            "\"rpmCurve\":[[0,0],[1000,1]]",
            "\"rpmCurve\":[[0,0],[1000,0]]",
        )
        val rpmError = assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(silentRpmCurve.toByteArray())
        }
        assertTrue(rpmError.message.orEmpty().contains("silent", ignoreCase = true))

        val silentReleasedPedalGain = validManifest().replace(
            "\"gainCurve\":[[0,1],[1,1]]",
            "\"gainCurve\":[[0,0],[1,1]]",
        )
        val gainError = assertThrows(JsonValidationException::class.java) {
            SoundFamilyManifestV1.parse(silentReleasedPedalGain.toByteArray())
        }
        assertTrue(gainError.message.orEmpty().contains("silent", ignoreCase = true))
    }

    @Test
    fun importerRejectsWrongCatalogAndInstalledStoreExcludesStaleCatalogPacks() {
        val manifestBytes = validManifest().toByteArray()
        val manifest = SoundFamilyManifestV1.parse(manifestBytes)
        val expectedHash = manifest.catalogSha256!!
        val wrongHash = "9".repeat(64)
        val privateRoot = kotlin.io.path.createTempDirectory("catalog-binding-test").toFile()
        try {
            val importer = AclibPackImporter(
                privateFilesDirectory = privateRoot,
                verifier = FlacPcmIntegrityVerifier { _, _ -> error("Catalog mismatch must fail before decoding") },
                decodedHardBudgetBytes = manifest.totalDecodedBytes,
                officialFamilyMembership = mapOf(manifest.familyId to manifest.memberCarIds.toSet()),
                expectedCatalogSha256 = wrongHash,
            )
            val archive = storedAclib(manifestBytes, ByteArray(0))
            val importError = assertThrows(PackValidationException::class.java) {
                ByteArrayInputStream(archive).use(importer::importFrom)
            }
            assertTrue(importError.message.orEmpty().contains("different official catalog"))
            assertTrue(importer.installedFamilies().isEmpty())

            val store = InstalledSoundFamilyStore(privateRoot)
            val staging = store.newStagingDirectory()
            assertTrue(staging.mkdirs())
            File(staging, "manifest.json").writeBytes(manifestBytes)
            File(staging, ".ready-v1").writeText(manifest.familyId, Charsets.US_ASCII)
            File(staging, "audio").mkdirs()
            File(staging, "audio/idle.flac").writeBytes(byteArrayOf(1))
            store.commit(InstalledSoundFamily(staging, manifest))

            val membership = mapOf(manifest.familyId to manifest.memberCarIds.toSet())
            assertEquals(1, store.loadInstalled(expectedHash, membership).size)
            assertTrue(store.loadInstalled(wrongHash, membership).isEmpty())
            assertTrue(
                store.loadInstalled(
                    expectedHash,
                    mapOf(manifest.familyId to setOf("abarth500")),
                ).isEmpty(),
            )
        } finally {
            privateRoot.deleteRecursively()
        }
    }

    @Test
    fun importerExtractsAndVerifiesSharedPhysicalFlacOnlyOnce() {
        val audio = minimalFlacStreamInfo(48_000, 2, 16, 100)
        val audioSha = MessageDigest.getInstance("SHA-256").digest(audio)
            .joinToString("") { byte -> "%02x".format(byte) }
        val manifestBytes = sharedPathManifest()
            .replace("b".repeat(64), audioSha)
            .toByteArray()
        val manifest = SoundFamilyManifestV1.parse(manifestBytes)
        var decodeCalls = 0
        val privateRoot = kotlin.io.path.createTempDirectory("shared-pcm-import-test").toFile()
        try {
            val importer = AclibPackImporter(
                privateFilesDirectory = privateRoot,
                verifier = FlacPcmIntegrityVerifier { _, maximumBytes ->
                    decodeCalls += 1
                    assertEquals(400L, maximumBytes)
                    DecodedPcmIntegrity(48_000, 2, 16, 100, "c".repeat(64))
                },
                decodedHardBudgetBytes = 400L,
                officialFamilyMembership = mapOf(manifest.familyId to manifest.memberCarIds.toSet()),
            )

            val imported = ByteArrayInputStream(storedAclib(manifestBytes, audio)).use(importer::importFrom)

            assertEquals(1, decodeCalls)
            assertEquals(400L, imported.family.manifest.totalDecodedBytes)
            assertTrue(imported.family.manifest.tracks.all { imported.family.trackFile(it).isFile })
        } finally {
            privateRoot.deleteRecursively()
        }
    }

    @Test
    fun zipPolicyRejectsTraversalAndCompression() {
        val limits = AclibPackLimits()
        val traversal = storedEntry("audio/../idle.flac", 20)
        assertThrows(PackValidationException::class.java) { validateAclibZipEntry(traversal, limits) }

        val compressed = ZipEntry("audio/idle.flac").apply {
            method = ZipEntry.DEFLATED
            size = 20
            compressedSize = 10
        }
        assertThrows(PackValidationException::class.java) { validateAclibZipEntry(compressed, limits) }
    }

    @Test
    fun streamInfoReaderRequiresExactStereoPcm16At48kAtImportBoundary() {
        val file = kotlin.io.path.createTempFile(suffix = ".flac").toFile()
        try {
            file.writeBytes(minimalFlacStreamInfo(sampleRate = 48_000, channels = 2, bits = 16, frames = 100))
            assertEquals(FlacStreamInfo(48_000, 2, 16, 100), FlacStreamInfoReader.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun currentCompilerCatalogAndReferencePackMatchAndroidSchemaWhenPresent() {
        val compilerRoot = File("C:/Users/Gabriel/Documents/ChatGPT/assettocorsa/.aclib-local")
        val catalog = File(compilerRoot, "catalog-v1.json")
        if (!catalog.isFile) return
        val parsedCatalog = GeneratedOfficialCatalogV1.parse(catalog.readBytes())
        assertEquals(178, parsedCatalog.cars.size)
        assertEquals(153, parsedCatalog.soundFamilies.size)
        parsedCatalog.cars.values.forEach { car ->
            assertEquals(
                "Sanitizer changed authored default ratios for ${car.id}",
                car.gearbox.forwardRatios,
                EngineTuning(gearRatios = car.gearbox.forwardRatios).sanitized().gearRatios,
            )
        }

        val manifest = File(
            compilerRoot,
            "families/668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab/manifest.json",
        )
        if (manifest.isFile) {
            val parsedManifest = SoundFamilyManifestV1.parse(manifest.readBytes())
            assertEquals("tatuusfa1", parsedManifest.memberCarIds.single())
            assertTrue(parsedManifest.tracks.size >= 19)
            assertTrue(parsedManifest.tracks.any { it.role == PackTrackRole.IDLE })
            assertTrue(!parsedManifest.effects.bov)
            assertTrue(parsedManifest.tracks.none { it.role == PackTrackRole.BOV })
            NativeSoundFamilyLoader.validateContinuousCurves(parsedManifest.tracks)
        }
    }

    @Test
    fun currentCompilerCatalogParseBenchmarkReportsSteadyStateCostWhenPresent() {
        val catalog = File("C:/Users/Gabriel/Documents/ChatGPT/assettocorsa/.aclib-local/catalog-v1.json")
        if (!catalog.isFile) return
        val bytes = catalog.readBytes()
        repeat(2) { GeneratedOfficialCatalogV1.parse(bytes) }

        val iterations = 8
        val collectionsBefore = ManagementFactory.getGarbageCollectorMXBeans()
            .sumOf { collector -> collector.collectionCount.coerceAtLeast(0L) }
        var validatedEntries = 0
        val startedNanos = System.nanoTime()
        repeat(iterations) {
            val parsed = GeneratedOfficialCatalogV1.parse(bytes)
            validatedEntries += parsed.cars.size + parsed.soundFamilies.size
        }
        val elapsedNanos = System.nanoTime() - startedNanos
        val collectionsAfter = ManagementFactory.getGarbageCollectorMXBeans()
            .sumOf { collector -> collector.collectionCount.coerceAtLeast(0L) }
        assertEquals(iterations * (178 + 153), validatedEntries)
        println(
            "CATALOG_PARSE_BENCHMARK bytes=${bytes.size} iterations=$iterations " +
                "totalMs=${elapsedNanos / 1_000_000.0} " +
                "averageMs=${elapsedNanos / iterations / 1_000_000.0} " +
                "gcCollections=${collectionsAfter - collectionsBefore}",
        )
    }

    @Test
    fun currentCompilerCatalogOptimizationPhaseBenchmarkWhenPresent() {
        val catalog = File("C:/Users/Gabriel/Documents/ChatGPT/assettocorsa/.aclib-local/catalog-v1.json")
        if (!catalog.isFile) return
        val bytes = catalog.readBytes()
        val root = StrictJson.parse(bytes).asObject("catalog")
        val legacyTokenPattern = Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)
        val legacyRoot = JsonValue.ObjectValue(root - "catalogSha256")
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(StrictJson.canonicalBytes(legacyRoot))
        assertArrayEquals(
            expectedHash,
            StrictJson.canonicalSha256ExcludingObjectKey(root, "catalogSha256"),
        )

        val scanIterations = 32
        val legacyScanStarted = System.nanoTime()
        repeat(scanIterations) {
            assertFalse(
                legacyTokenPattern.findAll(bytes.toString(Charsets.UTF_8))
                    .any { match -> match.value.equals("load", ignoreCase = true) },
            )
        }
        val legacyScanNanos = System.nanoTime() - legacyScanStarted
        val directScanStarted = System.nanoTime()
        repeat(scanIterations) { assertFalse(StrictJson.containsForbiddenLoadToken(bytes)) }
        val directScanNanos = System.nanoTime() - directScanStarted

        val hashIterations = 16
        val materializedHashStarted = System.nanoTime()
        repeat(hashIterations) {
            MessageDigest.getInstance("SHA-256").digest(StrictJson.canonicalBytes(legacyRoot))
        }
        val materializedHashNanos = System.nanoTime() - materializedHashStarted
        val streamingHashStarted = System.nanoTime()
        repeat(hashIterations) {
            StrictJson.canonicalSha256ExcludingObjectKey(root, "catalogSha256")
        }
        val streamingHashNanos = System.nanoTime() - streamingHashStarted
        println(
            "CATALOG_PHASE_BENCHMARK bytes=${bytes.size} " +
                "legacyScanAverageMs=${legacyScanNanos / scanIterations / 1_000_000.0} " +
                "directScanAverageMs=${directScanNanos / scanIterations / 1_000_000.0} " +
                "materializedHashAverageMs=${materializedHashNanos / hashIterations / 1_000_000.0} " +
                "streamingHashAverageMs=${streamingHashNanos / hashIterations / 1_000_000.0}",
        )
    }

    @Test
    fun referencePackImportsAtomicallyAndCorruptionCannotReplaceItWhenPresent() {
        val compilerRoot = File("C:/Users/Gabriel/Documents/ChatGPT/assettocorsa/.aclib-local")
        val familyId = "668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab"
        val pack = File(compilerRoot, "packs/$familyId.aclib")
        val sourceManifest = File(compilerRoot, "families/$familyId/manifest.json")
        if (!pack.isFile || !sourceManifest.isFile) return

        val manifest = SoundFamilyManifestV1.parse(sourceManifest.readBytes())
        val tracksByFileName = manifest.tracks.associateBy { File(it.path).name }
        val fakeDecoder = FlacPcmIntegrityVerifier { file, _ ->
            val track = tracksByFileName.getValue(file.name)
            DecodedPcmIntegrity(48_000, 2, 16, track.frameCount, track.pcmSha256)
        }
        val privateRoot = kotlin.io.path.createTempDirectory("aclib-import-test").toFile()
        try {
            val importer = AclibPackImporter(
                privateFilesDirectory = privateRoot,
                verifier = fakeDecoder,
                decodedHardBudgetBytes = manifest.totalDecodedBytes,
                officialFamilyMembership = mapOf(familyId to setOf("tatuusfa1")),
            )
            val first = FileInputStream(pack).use(importer::importFrom)
            assertEquals(familyId, first.family.manifest.familyId)
            assertTrue(first.family.trackFile(first.family.manifest.tracks.first()).isFile)

            val corruptPack = File(privateRoot, "corrupt.aclib")
            rewriteStoredZip(pack, corruptPack) { name, data ->
                if (name == "audio/idle.flac") data.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() } else data
            }
            assertThrows(PackValidationException::class.java) {
                FileInputStream(corruptPack).use(importer::importFrom)
            }
            val stillInstalled = importer.installedFamilies().getValue(familyId)
            assertEquals(manifest.tracks.first().flacSha256, sha256(stillInstalled.trackFile(manifest.tracks.first())))
        } finally {
            privateRoot.deleteRecursively()
        }
    }

    private fun storedEntry(name: String, bytes: Long): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes
        compressedSize = bytes
    }

    private fun rewriteStoredZip(
        source: File,
        destination: File,
        transform: (String, ByteArray) -> ByteArray,
    ) {
        ZipFile(source).use { input ->
            ZipOutputStream(FileOutputStream(destination)).use { output ->
                input.entries().asSequence().forEach { original ->
                    val bytes = transform(original.name, input.getInputStream(original).use(InputStream::readBytes))
                    val crc = CRC32().apply { update(bytes) }
                    val entry = ZipEntry(original.name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        this.crc = crc.value
                    }
                    output.putNextEntry(entry)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
    }

    private fun storedAclib(manifest: ByteArray, audio: ByteArray): ByteArray {
        val destination = ByteArrayOutputStream()
        ZipOutputStream(destination).use { output ->
            listOf("manifest.json" to manifest, "audio/idle.flac" to audio).forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }
                output.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    this.crc = crc.value
                })
                output.write(bytes)
                output.closeEntry()
            }
        }
        return destination.toByteArray()
    }

    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun minimalFlacStreamInfo(sampleRate: Int, channels: Int, bits: Int, frames: Long): ByteArray {
        val info = ByteArray(34)
        val packed = (sampleRate.toLong() shl 44) or
            ((channels - 1).toLong() shl 41) or
            ((bits - 1).toLong() shl 36) or frames
        for (index in 0 until 8) info[10 + index] = (packed ushr ((7 - index) * 8)).toByte()
        return byteArrayOf(
            'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
            0x80.toByte(), 0, 0, 34,
        ) + info
    }

    private fun validV2Manifest(): String = validManifest()
        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        .replace(
            "\"bitsPerSample\":16,\"rootRpm\"",
            "\"bitsPerSample\":16,\"softwareChannelPriority\":64," +
                "\"pitchMode\":\"AUTO_PITCH_RPM_RATIO\",\"pitchCurve\":[]," +
                "\"pitchCurveInterpolation\":\"NONE\",\"rootRpm\"",
        )
        .replace(
            "\"turboControllers\":[]}",
            "\"turboControllers\":[],\"turboPhysics\":{\"bovPressureThreshold\":0,\"turbos\":[]},\"throttleMap\":{\"input\":\"NORMALIZED_PEDAL\",\"output\":\"NORMALIZED_ENGINE_GAS\",\"interpolation\":\"CLAMPED_LINEAR\",\"points\":[[0,0],[1,1]]}}",
        )
        .replace(
            "\"alternateGearSets\":[]}",
            "\"alternateGearSets\":[],\"engineGasAssist\":{\"autoShifterGasCutoffMs\":0,\"engineCutoffMs\":0,\"autoBlipElectronic\":false,\"autoBlipEnableMode\":\"ELECTRONIC_OR_AUTOCLUTCH\",\"autoBlipClutchGateExclusive\":0.3183098861837907,\"autoBlipProfile\":[],\"autoBlipEndTimeMs\":0,\"autoBlipEvaluator\":\"AUTHORED_ORDER_FIRST_UPPER_BOUND_LINEAR\",\"autoBlipCombiner\":\"MAX_WITH_POST_ASSIST_PEDAL\",\"processingOrder\":\"AUTOBLIP_THEN_AUTO_SHIFTER_CUT_THEN_ENGINE_CUTOFF_THEN_THROTTLE_MAP_THEN_LIMITER_CUT\"}},\"oneShotTriggerPolicies\":{}",
        )
        .replace("\"assets\":[]", "\"oneShotPrograms\":[],\"assets\":[]")
        .replace("\"effectVariants\":\"singleEventTake\"", "\"effectVariants\":\"authoredOneShotTopology\"")
        .replace(
            "\"encoder\":{",
            "\"authoredDsp\":[],\"certifiedSilentSources\":[],\"softwareChannelPriorityOracleSha256\":\"${"5".repeat(64)}\",\"encoder\":{",
        )

    private fun validV2TurboManifest(): String = validV2Manifest()
        .replace("\"turboCount\":0", "\"turboCount\":1")
        .replace(
            "\"turboControllers\":[]",
            """"turboControllers":[{"file":"ctrl_turbo0.ini","sha256":"${"1".repeat(64)}","controllers":[{"section":"CONTROLLER_0","input":"RPMS","combinator":"ADD","lut":[[0,0.5],[7000,1.0]],"filter":0,"upLimit":2,"downLimit":0}]}]""",
        )
        .replace(
            "\"turboPhysics\":{\"bovPressureThreshold\":0,\"turbos\":[]}",
            "\"turboPhysics\":{\"bovPressureThreshold\":0.2,\"turbos\":[{\"maximumBoost\":1.25,\"wastegate\":0.9,\"referenceRpm\":4000,\"gamma\":1.5,\"lagUp\":4,\"lagDown\":2,\"controllerFile\":\"ctrl_turbo0.ini\"}]}",
        )
        .replace(
            "\"quirks\":[\"authoredBovLaneSilent\"]",
            "\"quirks\":[\"authoredBovLaneSilent\",\"gearDependentTurboController\"]",
        )

    private fun validV2TurboEventManifest(mode: PackTurboEventProgramMode): String {
        require(mode != PackTurboEventProgramMode.BOOST_RELEASE_REGION_ONE_SHOT)
        val placement = if (mode == PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT) {
            """{"kind":"timeline","instrumentGuid":"11111111-2222-3333-4444-555555555555","startTick":120,"lengthTicks":480,"timeLocked":true}"""
        } else {
            """{"kind":"parameter","instrumentGuid":"11111111-2222-3333-4444-555555555555","parameter":"boost","parameterGuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","minimum":0,"maximum":1.5,"authoredMaximum":1.5,"includeMaximum":true}"""
        }
        val triggerTemplate = if (mode == PackTurboEventProgramMode.TIMELINE_PERIODIC_ONE_SHOT) {
            """{"trigger":"EVENT_TIMELINE_PERIODIC","startTick":120,"periodTicks":480,"ticksPerSecond":48000,"overlapMode":"ALLOW_OVERLAP","exitBehavior":"NOT_APPLICABLE"}"""
        } else {
            """{"trigger":"EVENT_START","parameter":"boost","parameterRegionCoversEntireDomain":true,"rearmMode":"NONE_WITHOUT_EVENT_RESTART","overlapMode":"ONE_VOICE_PER_EVENT_START","exitBehavior":"LET_ACTIVE_VOICE_FINISH"}"""
        }
        val withEffects = validV2Manifest()
            .replace("\"turbo\":false,\"spool\":false", "\"turbo\":true,\"spool\":true")
        val withTrack = Regex(""""triggers":\[\]\s*}\]""").replaceFirst(
            withEffects,
            """"triggers":[]
              },{
                "id":"turbo_transient","role":"TURBO_TRANSIENT","path":"audio/turbo_transient.flac","flacSha256":"${"1".repeat(64)}","pcmSha256":"${"2".repeat(64)}",
                "frameCount":960,"sampleRate":48000,"channels":2,"bitsPerSample":16,"softwareChannelPriority":128,"pitchMode":"AUTO_PITCH_RPM_RATIO","pitchCurve":[],"pitchCurveInterpolation":"NONE","rootRpm":null,
                "loopStartFrame":null,"loopEndFrame":null,"gainDb":-6,"peakDbfs":-6,
                "rpmCurve":[],"gainCurve":[],"triggers":["turboEvent"]
              }]""",
        )
        return withTrack.replace(
            "\"oneShotPrograms\":[]",
            """"oneShotPrograms":[{
              "id":"turbo_program","trigger":"TURBO_EVENT","softwareChannelPriority":128,"capturedFromEventStart":true,
              "rootNodeIds":["turbo_group"],"nodes":[
                {"id":"turbo_group","kind":"GROUP","triggerChance":1,"playMode":"NORMAL","selectionMode":"NORMAL","members":[{"nodeId":"turbo_leaf","weight":3,"order":0},{"nodeId":"silent_leaf","weight":1,"order":1}]},
                {"id":"turbo_leaf","kind":"TRACK","trackId":"turbo_transient","triggerChance":1,"parameterGates":[],"rpmCurve":[],"gainCurve":[],"liveVarispeed":false,"rootRpm":null,
                 "captureControlValues":[{"control":"BOOST","value":0.5}],
                 "controlGainCurves":[{"control":"BOOST","curve":[[0,0],[0.5,1],[1.5,1.08536057]]}],
                 "pitchAutomations":[{"control":"BOOST","propertyIndex":1,"rawValueToSemitonesScale":24,"captureSemitones":12,"playbackRateCurve":[[0,0.5],[0.5,1],[1.5,2]],"runtimeTreatment":"multiplyActiveVoiceRateContinuously","updatesWhileVoiceActive":true,"continuesOutsideSchedulingRegion":true,"captureRate":1}],
                 "sourceVerificationPayloadSha256":"${"3".repeat(64)}"},
                {"id":"silent_leaf","kind":"SILENT_SOURCE","triggerChance":1,"sourceGuid":"99999999-8888-7777-6666-555555555555","resolvedRole":"TURBO_TRANSIENT","sourceVerificationPayloadSha256":"${"4".repeat(64)}"}
              ],
              "policy":{"kind":"TURBO_EVENT_PROGRAM","programMode":"${mode.name}","programPlacementRootInstrumentGuid":"11111111-2222-3333-4444-555555555555",
                "placementSignature":$placement,"programTriggerTemplate":$triggerTemplate,
                "voicePolicy":{"softwareChannelPriority":128,"priorityRequiredFromSourceBoundOracle":false,"acGlobalLogicalVoiceCap":2048,"acDefaultSoftwareRealVoiceBudget":256,"overlapSharesGlobalBudget":true},
                "runtimeControlSemantics":{"boost":"AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN","bov":"AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED","bov_decay":"AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED","propertyZero":"DB_VOLUME","propertyOne":"RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE","propertyFour":"LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH","autoPitchFromParameterPlacement":false},
                "coreProgram":true,"auditionable":false}
            }]""",
        ).replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[{\"sourceGuid\":\"99999999-8888-7777-6666-555555555555\",\"role\":\"TURBO_TRANSIENT\",\"disposition\":\"AUTHORED_TARGET_SILENT\",\"verificationPayloadSha256\":\"${"4".repeat(64)}\"}]",
        )
    }

    private fun validV2SilentTurboReleaseManifest(): String = validV2Manifest()
        .replace(
            "\"oneShotPrograms\":[]",
            """"oneShotPrograms":[{
          "id":"bov_program","trigger":"TURBO_EVENT","softwareChannelPriority":128,"capturedFromEventStart":true,
          "rootNodeIds":["silent_bov"],"nodes":[
            {"id":"silent_bov","kind":"SILENT_SOURCE","triggerChance":1,"sourceGuid":"99999999-8888-7777-6666-555555555555","resolvedRole":"BOV","sourceVerificationPayloadSha256":"${"4".repeat(64)}"}
          ],
          "policy":{"kind":"TURBO_EVENT_PROGRAM","programMode":"BOOST_RELEASE_REGION_ONE_SHOT","programPlacementRootInstrumentGuid":"11111111-2222-3333-4444-555555555555",
            "placementSignature":{"kind":"parameter","instrumentGuid":"11111111-2222-3333-4444-555555555555","parameter":"boost","parameterGuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","minimum":0,"maximum":0.8,"authoredMaximum":1.5,"includeMaximum":true},
            "programTriggerTemplate":{"trigger":"EVENT_START_ARMED_PARAMETER_REGION_REENTRY","parameter":"boost","minimum":0,"maximum":0.8,"includeMinimum":true,"includeMaximum":true,"entryEdges":[{"boundary":"MAXIMUM","direction":"DECREASING","value":0.8,"includeBoundary":true}],"armingMode":"ARMED_WHEN_EVENT_STARTS_INSIDE_OR_OUTSIDE","initiallyOutsideBehavior":"SCHEDULE_ON_FIRST_OUTSIDE_TO_INSIDE_ENTRY","rearmMode":"AFTER_ANY_GATE_EXIT","overlapMode":"ALLOW_OVERLAP","exitBehavior":"LET_ACTIVE_VOICES_FINISH"},
            "voicePolicy":{"softwareChannelPriority":128,"priorityRequiredFromSourceBoundOracle":false,"acGlobalLogicalVoiceCap":2048,"acDefaultSoftwareRealVoiceBudget":256,"overlapSharesGlobalBudget":true},
            "runtimeControlSemantics":{"boost":"AC_CTRL_TURBO_OUTPUT_NORMALIZED_TO_EVENT_PARAMETER_DOMAIN","bov":"AC_TURBO_EVENT_BOV_PARAMETER_WHEN_AUTHORED","bov_decay":"AC_TURBO_EVENT_BOV_DECAY_PARAMETER_WHEN_AUTHORED","propertyZero":"DB_VOLUME","propertyOne":"RAW_VALUE_TIMES_24_SEMITONES_LIVE_ACTIVE_VOICE_RATE","propertyFour":"LINEAR_PARAMETER_SHEET_GAIN_NOT_PITCH","autoPitchFromParameterPlacement":false},
            "coreProgram":false,"auditionable":false}
        }]""",
        ).replace(
            "\"certifiedSilentSources\":[]",
            "\"certifiedSilentSources\":[{\"sourceGuid\":\"99999999-8888-7777-6666-555555555555\",\"role\":\"BOV\",\"disposition\":\"AUTHORED_TARGET_SILENT\",\"verificationPayloadSha256\":\"${"4".repeat(64)}\"}]",
        )

    private fun validV2LimiterManifest(mode: PackLimiterProgramMode): String {
        val timeline = mode == PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT
        val decayOneShot = mode == PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT
        val sourceLifetime = if (mode == PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP) {
            "continuous"
        } else {
            "oneShot"
        }
        val loopStart = if (decayOneShot) "null" else "0"
        val loopEnd = if (decayOneShot) "null" else "100"
        val frameCount = if (decayOneShot) 960 else 100
        val decayPlacement = if (timeline) {
            "null"
        } else {
            """{"control":"LIMITER_DECAY_SECONDS","minimum":0,"maximum":0.02,"includeMinimum":true,"includeMaximum":false}"""
        }
        val timelinePlacement = if (timeline) {
            """{"startTicks":0,"lengthTicks":100,"timeLocked":true,"tickRateHz":48000,"startFrameAt48k":0,"periodFramesAt48k":100}"""
        } else {
            "null"
        }
        val scheduling = when (mode) {
            PackLimiterProgramMode.PERSISTENT_TIMELINE_PERIODIC_ONE_SHOT ->
                """{"timelinePeriodicOneShot":"EVENT_TIMELINE_OWNS_PERIOD_AND_RETRIGGER","parameterRegionEntry":null,"sameInsideValueBehavior":"DO_NOT_RETRIGGER","placementExitBehavior":"TIMELINE_OWNS_SOURCE_LIFETIME","overlapMode":"ONE_RENDERED_TIMELINE_LOOP_TRACK"}"""
            PackLimiterProgramMode.PERSISTENT_DECAY_REGION_ONE_SHOT ->
                """{"timelinePeriodicOneShot":null,"parameterRegionEntry":"SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY","sameInsideValueBehavior":"DO_NOT_RETRIGGER","placementExitBehavior":"LET_ACTIVE_ONE_SHOTS_FINISH","overlapMode":"ALLOW_OVERLAPPING_ONE_SHOT_VOICES"}"""
            PackLimiterProgramMode.PERSISTENT_DECAY_REGION_LOOP ->
                """{"timelinePeriodicOneShot":null,"parameterRegionEntry":"SCHEDULE_ON_EVENT_START_INSIDE_OR_OUTSIDE_TO_INSIDE_REENTRY","sameInsideValueBehavior":"DO_NOT_RETRIGGER","placementExitBehavior":"STOP_LOOP_SOURCE_AND_RESTART_FROM_PHASE_ZERO_ON_NEXT_ENTRY","overlapMode":"ONE_ACTIVE_LOOP_VOICE"}"""
        }
        val maximumSimultaneous = if (decayOneShot) "null" else "1"
        val laneBound = if (decayOneShot) {
            "\"min(2048,ceil(decodedOneShotFrames/480))\""
        } else {
            "null"
        }
        val withPolicy = validV2Manifest()
            .replace(
                "\"oneShotTriggerPolicies\":{}",
                "\"oneShotTriggerPolicies\":{\"limiter_program\":{\"kind\":\"LIMITER_EVENT\",\"minimumRpm\":7000,\"maximumRpm\":null,\"armPedal\":null,\"firePedal\":null,\"armBoost\":null,\"initialPeakPedal\":null,\"initialArmPedal\":null,\"initialFirePedal\":null,\"minimumArmMs\":0,\"cooldownMs\":0,\"periodHz\":null}}",
            )
            .replace("\"limiter\":false", "\"limiter\":true")
        val withTrack = Regex(""""triggers":\[\]\s*\}\]""").replaceFirst(
            withPolicy,
            """"triggers":[]
              },{
                "id":"limiter_source","role":"LIMITER","path":"audio/limiter_source.flac","flacSha256":"${"1".repeat(64)}","pcmSha256":"${"2".repeat(64)}",
                "frameCount":$frameCount,"sampleRate":48000,"channels":2,"bitsPerSample":16,"softwareChannelPriority":64,"pitchMode":"AUTO_PITCH_RPM_RATIO","pitchCurve":[],"pitchCurveInterpolation":"NONE","rootRpm":null,
                "loopStartFrame":$loopStart,"loopEndFrame":$loopEnd,"gainDb":-6,"peakDbfs":-6,
                "rpmCurve":[],"gainCurve":[[0,1],[1,1]],"triggers":["limiterEvent"]
              }]""",
        )
        return withTrack.replace(
            "\"oneShotPrograms\":[]",
            """"oneShotPrograms":[{
                "id":"limiter_program","trigger":"LIMITER_EVENT","capturedFromEventStart":true,"softwareChannelPriority":64,
                "rootNodeIds":["limiter_leaf"],"nodes":[{
                  "id":"limiter_leaf","kind":"TRACK","trackId":"limiter_source","triggerChance":1,
                  "parameterGates":[],"rpmCurve":[],"gainCurve":[[0,1],[1,1]],"liveVarispeed":false,"rootRpm":null
                }],
                "policy":{
                  "kind":"PERSISTENT_LIMITER_EVENT","programMode":"${mode.name}","sourceLifetime":"$sourceLifetime",
                  "decayParameter":{"control":"LIMITER_DECAY_SECONDS","minimum":0,"maximum":1,"defaultValue":0,"runtimeInput":"min(hostFloat32DecayTimerSeconds,1)"},
                  "decayGainCurve":[[0,1],[1,0]],"decayPlacement":$decayPlacement,"timelinePlacement":$timelinePlacement,
                  "runtimeLifecycle":{
                    "owner":"ONE_PERSISTENT_LIMITER_EVENT_INSTANCE","initialHostDecayTimerSeconds":10,
                    "updateOrder":["FLOAT32_TIMER_PLUS_DT","RESET_TIMER_TO_ZERO_IF_LIMITER_PULSE","WRITE_RAW_TIMER_TO_FMOD_DECAY_PARAMETER","UPDATE_EVENT_OWNER_STATE"],
                    "eventDesiredActiveWhen":"driveAudioActive && limiterEnabled && hostDecayTimerSeconds<=10",
                    "inactiveThreshold":{"comparison":"STRICTLY_GREATER_THAN","seconds":10},
                    "activeEventAction":"UNPAUSE_IF_PAUSED_ELSE_REWIND_TIMELINE_ZERO_AND_START_IF_STOPPED",
                    "inactiveEventAction":"STOP_ALLOWFADEOUT",
                    "limiterPulseWhileEventActive":"RESET_DECAY_ONLY_PRESERVE_EVENT_TIMELINE_AND_ACTIVE_SOURCE_PHASE",
                    "reactivationAfterInactive":"SET_DECAY_ZERO_THEN_REWIND_TIMELINE_ZERO_THEN_START",
                    "executableEvidence":{
                      "timerInitialization":"acs.exe:0x140063038 immediate float32 10.0",
                      "timerAndParameterUpdate":"acs.exe:0x140067134-0x14006718c",
                      "tenSecondOwnerGate":"acs.exe:0x140067e28-0x140067ea4",
                      "rewindThenStart":"acs.exe:0x1401fbf40-0x1401fbfb7",
                      "allowFadeStop":"acs.exe:0x1401fc040-0x1401fc07f"
                    }
                  },
                  "sourceScheduling":$scheduling,
                  "voicePolicy":{"maximumSimultaneousProgramTracks":$maximumSimultaneous,"oneShotLaneBoundAfterDecode":$laneBound,"acGlobalLogicalVoiceCap":2048,"acDefaultSoftwareRealVoiceBudget":256},
                  "targetCaptureBakedModulators":[],"sourceVerificationPayloadSha256":"${"3".repeat(64)}"
                }
              }]""",
        )
    }

    private fun validV2OneShotManifest(): String {
        val withPolicy = validV2Manifest().replace(
            "\"oneShotTriggerPolicies\":{}",
            "\"oneShotTriggerPolicies\":{\"shift_program\":{\"kind\":\"SHIFT_UP\",\"minimumRpm\":0,\"maximumRpm\":null,\"armPedal\":null,\"firePedal\":null,\"armBoost\":null,\"initialPeakPedal\":null,\"initialArmPedal\":null,\"initialFirePedal\":null,\"minimumArmMs\":0,\"cooldownMs\":0,\"periodHz\":null}}",
        )
            .replace("\"shift\":false", "\"shift\":true")
        val withTrack = Regex(""""triggers":\[\]\s*}\]""").replaceFirst(
            withPolicy,
            """"triggers":[]
              },{
                "id":"shift_up","role":"SHIFT_UP","path":"audio/shift_up.flac","flacSha256":"${"1".repeat(64)}","pcmSha256":"${"2".repeat(64)}",
                "frameCount":100,"sampleRate":48000,"channels":2,"bitsPerSample":16,"softwareChannelPriority":128,"pitchMode":"AUTO_PITCH_RPM_RATIO","pitchCurve":[],"pitchCurveInterpolation":"NONE","rootRpm":null,
                "loopStartFrame":null,"loopEndFrame":null,"gainDb":-6,"peakDbfs":-6,
                "rpmCurve":[],"gainCurve":[],"triggers":["shiftUp"]
              }]""",
        )
        return withTrack.replace(
            "\"oneShotPrograms\":[]",
            """"oneShotPrograms":[{
                "id":"shift_program","trigger":"SHIFT_UP","capturedFromEventStart":true,"softwareChannelPriority":128,
                "rootNodeIds":["shift_group"],"nodes":[
                  {"id":"shift_group","kind":"GROUP","triggerChance":1,"playMode":"SMART_RANDOM","selectionMode":"NORMAL","members":[{"nodeId":"shift_leaf","weight":1,"order":0}]},
                  {"id":"shift_leaf","kind":"TRACK","trackId":"shift_up","triggerChance":1,"parameterGates":[{"control":"SHIFT_STATE","minimum":0.5,"maximum":1.5,"includeMinimum":true,"includeMaximum":true}],"rpmCurve":[],"gainCurve":[],"liveVarispeed":false,"rootRpm":null}
                ]
              }]""",
        )
    }

    private fun certifiedSilentSource(sourceGuid: String, role: String, hash: String): String =
        """{"sourceGuid":"$sourceGuid","role":"$role","disposition":"AUTHORED_TARGET_SILENT","verificationPayloadSha256":"$hash"}"""

    private fun validV2EngineEventManifest(
        runtimeSemanticOverride: String? = null,
        reentryPolicy: String = "CONTINUE_PRIOR_VOICE_AND_SCHEDULE_NEW_OVERLAPPING_VOICE",
    ): String {
        val pitchVerification = """{"accepted":true}"""
        val zeroTransition = """{
          "policy":"RETAIN_PRE_ZERO_GAIN_THEN_LINEAR_FADE_TO_EXACT_ZERO",
          "frameDomain":"STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
          "gainInterpolation":"LINEAR_PER_WRITER_FRAME","gainAtTransitionStart":1,
          "gainAtExactZero":0,"retainPreZeroGainWriterFrames":0,
          "linearFadeWriterFrames":64,"exactZeroFromWriterFrame":64,
          "pitchDuringTransition":"LIVE_CURRENT_RPM_PITCH",
          "phaseTreatment":"RETAIN_CURRENT_LOGICAL_PHASE_NO_OFFSET",
          "residualMaximumAbsolutePcmLsb":1,
          "acceptanceBoundMaximumAbsolutePcmLsb":1,
          "positiveGainReturnBeforePhaseHoldPolicy":"CANCEL_ZERO_EPISODE_AND_RESUME_ORDINARY_NONZERO_GAIN_SMOOTHING_WITHOUT_PHASE_OR_DEADLINE_HOLD",
          "subsequentExactZeroCrossingPolicy":"RESTART_SOURCE_BOUND_ZERO_TRANSITION_AND_PHASE_DEADLINE_COUNTDOWN_FROM_CURRENT_ACTIVE_PHASE",
          "restoreCapturePcmPhaseOffsetFrames":0,
          "restoreCapturePcmPhaseOffsetMaximumAbsoluteBoundFrames":512
        }""".trimIndent()
        val runtimeSemantic = runtimeSemanticOverride ?: """{
          "kind":"EXACT_ZERO_GATE_THEN_HOLD_DECODE_AND_LOGICAL_PHASE",
          "mixerZeroGateAction":"APPLY_SOURCE_BOUND_ZERO_TRANSITION_THEN_SET_OUTPUT_EXACT_ZERO;DO_NOT_USE_ASYMPTOTIC_GAIN_SMOOTHING",
          "ordinaryNonzeroGainSmoothingUnaffected":true,
          "decodePhaseBeforeHold":"CURRENT_ACTIVE_VOICE_PITCH",
          "phaseHoldLatencyWriterFrames":512,
          "phaseAndDeadlineAdvanceWriterFramesBeforeHold":512,
          "phaseHoldLatencyFrameDomain":"STEREO_WRITER_OUTPUT_FRAMES_AT_48000_HZ",
          "holdDecodePhaseAfterLatency":true,
          "pauseNaturalEndDeadlineWhileHeld":true,
          "reaudibilizationBeforeDeadline":"CONTINUE_FROM_HELD_LOGICAL_PHASE",
          "writerDspBlockFrames":256,"zeroTransition":$zeroTransition,
          "channelGetPositionWhileVirtualIsRuntimeAuthoritative":false
        }""".trimIndent()
        val sourceReentryPolicy = if (
            reentryPolicy ==
            "NO_NEW_VOICE_ON_PARAMETER_REGION_REENTRY_AFTER_INITIAL_SOURCE_TRIGGER"
        ) {
            reentryPolicy
        } else {
            "PRESERVE_PRIOR_UNTIL_SOURCE_BOUND_NATURAL_END_AND_SCHEDULE_NEW_ON_REENTRY;" +
                "OVERLAP_IF_PRIOR_REMAINS_ALIVE"
        }
        val unhashedSourceVerification = """{
          "pitchVerification":$pitchVerification,
          "zeroGainVirtualization":{
            "runtimeSemantic":$runtimeSemantic,
            "reentryPolicy":"$sourceReentryPolicy"
          }
        }""".trimIndent()
        val sourceVerificationHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(StrictJson.canonicalBytes(StrictJson.parse(unhashedSourceVerification.toByteArray())))
            .joinToString("") { byte -> "%02x".format(byte) }
        val sourceVerification = """{
          "pitchVerification":$pitchVerification,
          "verificationPayloadSha256":"$sourceVerificationHash",
          "zeroGainVirtualization":{
            "runtimeSemantic":$runtimeSemantic,
            "reentryPolicy":"$sourceReentryPolicy"
          }
        }""".trimIndent()
        val pitchTreatment = """{
          "runtimeVarispeed":true,"rootRpm":3000,
          "scale":"currentPresentationEngineRpm/rootRpm",
          "updatesContinuouslyWhileVoiceIsActive":true,
          "continuesAfterParameterGateExit":true,
          "fmodLivePitchLatchSemantics":"notLatched",
          "entryEdgeSpecificCaptureVariants":false,
          "captureOperatingPointEdgesAreValidationOnly":true,
          "oracleBound":{"runtime":"FMOD Studio API 1.08.12","dspBufferFrames":256,
            "fixed3000TotalUpdates":1244,
            "move3000To4500After101UpdatesTotalUpdates":864,
            "move3000To5400After101UpdatesTotalUpdates":737,
            "maximumDurationPredictionErrorUpdates":1},
          "zeroGainVirtualization":{
            "runtimeSemantic":$runtimeSemantic,
            "reentryPolicy":"$reentryPolicy",
            "sourceVerificationPayloadSha256":"$sourceVerificationHash"
          },
          "timelineAutomation":"targetCompareVarispeededCaptureAgainstLiveFmodBeforeRelease",
          "sourceBoundPitchVerification":$pitchVerification
        }""".trimIndent()
        val withTrack = Regex(""""triggers":\[\]\s*}\]""").replaceFirst(
            validV2Manifest(),
            """"triggers":[]
              },{
                "id":"engine_event","role":"ENGINE_TRANSIENT","path":"audio/engine_event.flac","flacSha256":"${"1".repeat(64)}","pcmSha256":"${"2".repeat(64)}",
                "frameCount":2048,"sampleRate":48000,"channels":2,"bitsPerSample":16,"softwareChannelPriority":64,"pitchMode":"AUTO_PITCH_RPM_RATIO","pitchCurve":[],"pitchCurveInterpolation":"NONE","rootRpm":3000,
                "loopStartFrame":null,"loopEndFrame":null,"gainDb":-6,"peakDbfs":-6,
                "rpmCurve":[[0,1],[10000,1]],"gainCurve":[[0,1],[1,1]],"triggers":["engineEvent"]
              }]""",
        )
        return withTrack.replace(
            "\"oneShotPrograms\":[]",
            """"oneShotPrograms":[{
                "id":"engine_event_program","trigger":"ENGINE_EVENT","capturedFromEventStart":true,"softwareChannelPriority":64,
                "rootNodeIds":["engine_leaf"],
                "nodes":[{
                  "id":"engine_leaf","kind":"TRACK","trackId":"engine_event","triggerChance":1,
                  "parameterGates":[{"control":"ENGINE_RPM","minimum":2000,"maximum":6000,"includeMinimum":true,"includeMaximum":true}],
                  "rpmCurve":[[0,1],[10000,1]],"gainCurve":[[0,1],[1,1]],"liveVarispeed":true,"rootRpm":3000,
                  "pitchTreatment":$pitchTreatment,"sourceVerification":$sourceVerification
                }],
                "policy":{
                  "kind":"ENGINE_EVENT_REGION",
                  "parameterRegions":[{
                    "parameterGates":[{"control":"ENGINE_RPM","minimum":2000,"maximum":6000,"includeMinimum":true,"includeMaximum":true}],
                    "entryEdges":[{"control":"ENGINE_RPM","boundary":"MINIMUM","direction":"INCREASING","value":2000,"includeBoundary":true}],
                    "triggerOnEventStartIfInside":true
                  }],
                  "armingMode":"EVENT_START_INSIDE_REQUIRED",
                  "initiallyOutsideBehavior":"DISABLED_UNTIL_EVENT_RESTART",
                  "rearmMode":"AFTER_ANY_GATE_EXIT","overlapMode":"ALLOW_OVERLAP",
                  "exitBehavior":"LET_ACTIVE_VOICES_FINISH","coreProgram":true,"auditionable":false,
                  "maxDecodedOneShotFrameCount":2048,"laneCount":5,
                  "logicalVoiceLimit":2048,"softwareRealVoiceBudget":256
                }
              }]""",
        )
    }

    private fun validManifest(): String {
        val family = "a".repeat(64)
        val flac = "b".repeat(64)
        val pcm = "c".repeat(64)
        return """
            {
              "schemaVersion":1,
              "familyId":"$family",
              "displayName":"Test Car",
              "memberCarIds":["tatuusfa1"],
              "audioFormat":{"codec":"FLAC","sampleRate":48000,"channels":2,"bitsPerSample":16},
              "cars":[{
                "id":"tatuusfa1","name":"Test Car","brand":"Test","previewPath":null,
                "engine":{"idleRpm":1000,"redlineRpm":7000,"limiterRpm":7000,"limiterHz":20,"tachometerMaximumRpm":7500,"turboCount":0,"hybrid":false,"hybridConfig":null,"turboControllers":[]},
                "gearbox":{"traction":"RWD","forwardRatios":[3.0,2.0],"reverseRatio":-3.0,"finalDrive":4.0,"upshiftRpm":6000,"downshiftLandingRpmByGear":{"2":4000},"upshiftTimeMs":100,"downshiftTimeMs":150,"alternateGearSets":[]}
              }],
              "effects":{"idle":true,"coast":false,"texture":false,"intake":false,"exhaust":false,"turbo":false,"spool":false,"bov":false,"transmission":false,"limiter":false,"shift":false,"overrun":false,"popsBangsCracks":false},
              "quirks":["authoredBovLaneSilent"],
              "tracks":[{
                "id":"idle","role":"IDLE","path":"audio/idle.flac","flacSha256":"$flac","pcmSha256":"$pcm",
                "frameCount":100,"sampleRate":48000,"channels":2,"bitsPerSample":16,"rootRpm":1000,
                "loopStartFrame":0,"loopEndFrame":100,"gainDb":0,"peakDbfs":-3.1,
                "rpmCurve":[[0,0],[1000,1]],"gainCurve":[[0,1],[1,1]],"triggers":[]
              }],
              "assets":[],
              "fidelity":{"sourceAudio":"nativeFmodFinalMix","layerIsolation":"eventLevel","rpmGainCurve":"compilerWindowApproximation","effectVariants":"singleEventTake","notes":["test fixture"]},
              "provenance":{"source":"installedKunosAssettoCorsa1164","sourceBankSha256":"$family","catalogSha256":"${"d".repeat(64)}","capturePlanSha256":"${"e".repeat(64)}","referenceRenderer":"test","familyAttenuationDb":0,"defaultMixPeakDbfs":-3,"encoder":{"name":"libFLAC","version":"1.5.0","executableSha256":"${"f".repeat(64)}"}}
            }
        """.trimIndent()
    }

    private fun sharedPathManifest(secondPcmSha256: String = "c".repeat(64)): String {
        val duplicate = """
            ,{
              "id":"idle_alias","role":"IDLE","path":"audio/idle.flac","flacSha256":"${"b".repeat(64)}","pcmSha256":"$secondPcmSha256",
              "frameCount":100,"sampleRate":48000,"channels":2,"bitsPerSample":16,"rootRpm":1200,
              "loopStartFrame":5,"loopEndFrame":95,"gainDb":-2,"peakDbfs":-3.1,
              "rpmCurve":[[1000,0],[1200,1]],"gainCurve":[[0,0.5],[1,1]],"triggers":[]
            }
        """.trimIndent()
        val marker = "  }],\n  \"assets\":[]"
        val base = validManifest()
        check(marker in base)
        return base.replaceFirst(marker, "  }$duplicate],\n  \"assets\":[]")
    }
}
