package com.gabrielpc.enginesoundsimulator.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullEventAtlasRuntimeTest {
    @Test
    fun initialCellTimeoutJoinsBlockedMapperBeforeClosingItsFactory() {
        val program = perspectiveProgram(doubleArrayOf(800.0, 1_000.0))
        val factory = InterruptibleBlockingRegionFactory()

        assertThrows(IllegalStateException::class.java) {
            AtlasHotCellLoader(
                program = program,
                factory = factory,
                initialRpm = 800.0,
                initialThrottle = 0.0,
                initialLoadTimeoutMillis = 250L,
            )
        }

        assertTrue(factory.mapEntered.await(0L, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(factory.mapExited.get())
        assertTrue(factory.workerInterrupted.get())
        assertTrue(factory.closedAfterWorkerExit.get())
    }

    @Test
    fun selectorUsesAtMostFourNodesAndFixedModesCollapseToTwo() {
        val program = perspectiveProgram(doubleArrayOf(1_000.0, 2_000.0, 3_000.0))
        val selection = AtlasCellSelection()
        AtlasCellSelector.select(program.rpmAxis, program.throttleAxis, 1_500.0, 0.25, selection)
        assertEquals(0, selection.lowerRpmIndex)
        assertEquals(1, selection.upperRpmIndex)
        assertEquals(0, selection.lowerThrottleIndex)
        assertEquals(1, selection.upperThrottleIndex)

        val regions = (0..3).map { FakeRegion(it, if (it % 2 == 0) 1_000.0 else 2_000.0) }
        val cell = HotAtlasCell(
            key = selection.key(),
            lowerRpmIndex = 0,
            upperRpmIndex = 1,
            lowerThrottleIndex = 0,
            upperThrottleIndex = 1,
            lowerLower = regions[0],
            upperLower = regions[1],
            lowerUpper = regions[2],
            upperUpper = regions[3],
        )
        val weights = DoubleArray(4)
        cell.fillWeights(program, 1_500.0, 0.25, weights)
        assertEquals(listOf(0.375, 0.375, 0.125, 0.125), weights.toList())

        cell.fillWeights(program, 1_500.0, 1.0, weights)
        assertEquals(2, weights.count { it > 0.0 })
        assertEquals(1.0, weights.sum(), 1.0e-12)
        cell.fillWeights(program, 1_500.0, 0.0, weights)
        assertEquals(2, weights.count { it > 0.0 })
        assertEquals(1.0, weights.sum(), 1.0e-12)
    }

    @Test
    fun perspectiveAllowsManyRpmShardsButKeepsEachLiveCellAtTwo() {
        val rpmAxis = doubleArrayOf(1_000.0, 2_000.0, 3_000.0, 4_000.0)
        val nodes = buildList {
            rpmAxis.forEachIndexed { rpmIndex, rpm ->
                listOf(0.0, 1.0).forEach { throttle ->
                    val start = size * 128L
                    add(
                        AtlasEngineNode(
                            rpm = rpm,
                            throttle = throttle,
                            shardName = "rpm_$rpmIndex.wav",
                            startFrame = start,
                            endFrameExclusive = start + 128L,
                            loopStartFrame = start,
                            loopEndFrameExclusive = start + 128L,
                            modePrograms = testModePrograms(
                                start,
                                start + 128L,
                                "rpm_$rpmIndex.wav",
                            ),
                        ),
                    )
                }
            }
        }

        val program = AtlasPerspectiveProgram(rpmAxis, doubleArrayOf(0.0, 1.0), nodes)

        assertEquals(4, program.nodes.map { it.shardName }.distinct().size)

        val invalidCell = buildList {
            doubleArrayOf(1_000.0, 2_000.0).forEach { rpm ->
                listOf(0.0, 1.0).forEach { throttle ->
                    val start = size * 128L
                    val shardName = "corner_$size.wav"
                    add(
                        AtlasEngineNode(
                            rpm = rpm,
                            throttle = throttle,
                            shardName = shardName,
                            startFrame = start,
                            endFrameExclusive = start + 128L,
                            loopStartFrame = start,
                            loopEndFrameExclusive = start + 128L,
                            modePrograms = testModePrograms(start, start + 128L, shardName),
                        ),
                    )
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtlasPerspectiveProgram(
                doubleArrayOf(1_000.0, 2_000.0),
                doubleArrayOf(0.0, 1.0),
                invalidCell,
            )
        }
    }

    @Test
    fun eachModeSelectsOneIndependentFmodProgramAndAppliesOnlyPostMasterGain() {
        assertEquals(
            AtlasEngineProgram.FULL,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.FMOD_MIX,
                0.25,
                2.0,
                0.5,
                false,
                false,
                false,
            ).program,
        )
        assertEquals(
            0.875,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.FMOD_MIX,
                0.25,
                2.0,
                0.5,
                false,
                false,
                false,
            ).postMasterGain,
            1.0e-12,
        )
        assertEquals(
            AtlasEngineProgram.LOAD_ONLY,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.LOAD,
                0.0,
                2.0,
                0.5,
                false,
                false,
                false,
            ).program,
        )
        assertEquals(
            AtlasEngineProgram.COAST_ONLY,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.COAST,
                1.0,
                2.0,
                0.5,
                false,
                false,
                false,
            ).program,
        )
        assertEquals(
            AtlasEngineProgram.LOAD_ONLY,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.FMOD_MIX,
                0.5,
                2.0,
                0.5,
                true,
                true,
                false,
            ).program,
        )
        assertEquals(
            0.0,
            selectAtlasEngineProgram(
                PrimaryEngineLayerSource.FMOD_MIX,
                0.5,
                2.0,
                0.5,
                true,
                false,
                false,
            ).postMasterGain,
            0.0,
        )
    }

    @Test
    fun hotCellStressClosesEveryRegionAndKeepsOneSyntheticShard() {
        val program = perspectiveProgram(DoubleArray(40) { index -> 800.0 + index * 250.0 })
        val factory = CountingRegionFactory(program)
        val loader = AtlasHotCellLoader(program, factory, initialRpm = 800.0, initialThrottle = 0.0)
        val selection = AtlasCellSelection()
        try {
            repeat(4) {
                for (rpm in program.rpmAxis) {
                    AtlasCellSelector.select(program.rpmAxis, program.throttleAxis, rpm, 0.5, selection)
                    val key = selection.key()
                    loader.request(key, rpm, 0.5, AtlasEngineProgram.FULL)
                    waitUntil {
                        val cell = loader.acquireCurrentOrThrow()
                        try {
                            cell.key == key
                        } finally {
                            loader.release(cell)
                        }
                    }
                    val cell = loader.acquireCurrentOrThrow()
                    try {
                        assertTrue(cell.uniqueRegions().size <= 4)
                    } finally {
                        loader.release(cell)
                    }
                    assertEquals(1, factory.activeShards.get())
                }
            }
        } finally {
            loader.close()
        }
        assertEquals(0, factory.activeRegions.get())
        assertEquals(0, factory.activeShards.get())
        assertTrue(factory.maximumActiveRegions.get() <= 8)
    }

    @Test
    fun retiredCellStaysAliveUntilConcurrentAudioReaderReleasesIt() {
        val program = perspectiveProgram(doubleArrayOf(800.0, 1_000.0, 1_200.0))
        val factory = CountingRegionFactory(program)
        val loader = AtlasHotCellLoader(program, factory, initialRpm = 900.0, initialThrottle = 0.5)
        try {
            val heldByAudio = loader.acquireCurrentOrThrow()
            val selection = AtlasCellSelection()
            AtlasCellSelector.select(program.rpmAxis, program.throttleAxis, 1_200.0, 1.0, selection)
            val nextKey = selection.key()
            loader.request(nextKey, 1_200.0, 1.0, AtlasEngineProgram.LOAD_ONLY)
            waitUntil {
                val current = loader.acquireCurrentOrThrow()
                try {
                    current.key == nextKey
                } finally {
                    loader.release(current)
                }
            }
            assertTrue("Retired regions were closed while audio still held the cell", factory.activeRegions.get() > 2)
            loader.release(heldByAudio)
            waitUntil { factory.activeRegions.get() <= 2 }
        } finally {
            loader.close()
        }
        assertEquals(0, factory.activeRegions.get())
    }

    @Test
    fun parserEnforcesLoadCoastBothRowsAndFailClosedEffects() {
        val atlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().toByteArray()))
        assertEquals(2, atlas.perspective(EngineSoundPerspective.CABIN).rpmAxis.size)
        assertEquals(1, atlas.resourceBounds(EngineSoundPerspective.CABIN).engineMaximumMappedShardInstancesDuringCellTransition)
        assertEquals(2, atlas.sessionResourceBounds.maximumMappedShardInstancesDuringTransitionSafeUpperBound)
        atlas.requirePlaybackReady()

        val blocked = runtimeJson().replace("\"draftBlocked\":false", "\"draftBlocked\":true")
        val blockedAtlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(blocked.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { blockedAtlas.requirePlaybackReady() }

        val unprovenLifecycle = runtimeJson().replace(
            "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE",
            "PENDING_UNRECOGNIZED_LIFECYCLE",
        )
        val unprovenAtlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(unprovenLifecycle.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { unprovenAtlas.requirePlaybackReady() }

        val wrongLoad = runtimeJson().replace("\"throttle\":1.0", "\"throttle\":0.5")
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(wrongLoad.toByteArray()))
        }

        val missingModePrograms = runtimeJson().replaceFirst("\"modePrograms\"", "\"missingModePrograms\"")
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(missingModePrograms.toByteArray()))
        }

        val crossShardModeProgram = runtimeJson().replaceFirst(
            "\"modePrograms\":{\"loadOnly\":{\"shardName\":\"engine.wav\"",
            "\"modePrograms\":{\"loadOnly\":{\"shardName\":\"other.wav\"",
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(crossShardModeProgram.toByteArray()))
        }

        val legacyRuntime = runtimeJson().replace(
            "byd-full-event-atlas-runtime-v3",
            "byd-full-event-atlas-runtime-v2",
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(legacyRuntime.toByteArray()))
        }

        val unsupportedShardField = runtimeJson().replaceFirst(
            "\"bytes\":1068",
            "\"bytes\":1068,\"shardName\":\"engine.wav\"",
        )
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(unsupportedShardField.toByteArray()))
        }
    }

    @Test
    fun atlasTurboControlRequiresBothPhysicalTurboAndPerspectiveEvent() {
        val atlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().toByteArray()))
        val turboEffect = SampleEffectSpec(
            id = "turbo",
            control = SampleEffectControls.turbo,
            assetName = "turbo.wav",
            trigger = SampleEffectTrigger.TURBO_LOOP,
        )
        val physicalTurbo = AtlasCarAudioPhysics(
            turbos = listOf(AtlasTurboStage(0, 1.0, 1.0, 1.0, 1.0, 3_000.0, 1.0, 0.2)),
            turboBoostDivisor = 1.0,
            backfire = AtlasBackfirePhysics(0.3, 3_000.0, 8_000.0, 0.6, 0.4, 1.0),
            limiterFrequencyHz = 40.0,
        )
        val base = EngineSampleProfiles.default.copy(
            atlasProgram = atlas,
            cabinProgram = EngineSampleProgram(emptyList(), effects = listOf(turboEffect)),
            exteriorProgram = EngineSampleProgram(emptyList()),
        )
        assertTrue(base.copy(atlasAudioPhysics = physicalTurbo).hasTurboSounds(EngineSoundPerspective.CABIN))
        assertFalse(base.copy(atlasAudioPhysics = physicalTurbo).hasTurboSounds(EngineSoundPerspective.EXTERIOR))
        assertFalse(base.copy(atlasAudioPhysics = physicalTurbo.copy(turbos = emptyList())).hasTurboSounds(EngineSoundPerspective.CABIN))

        val eagerOnly = base.copy(
            atlasProgram = null,
            atlasRuntimeDescriptor = AtlasFamilyRuntimeDescriptor(
                id = "eager",
                assetDirectory = "eager",
                requirement = EngineAudioPackRequirement("eager", 1, "a".repeat(64)),
                runtimeAssetName = "families/eager.json",
                runtimeBytes = 1,
                runtimeSha256 = "b".repeat(64),
                eagerCapabilities = AtlasEagerCapabilities(emptySet(), emptyMap()),
            ),
            cabinProgram = EngineSampleProgram(emptyList()),
        )
        assertFalse(eagerOnly.hasTurboSounds(EngineSoundPerspective.CABIN))
    }

    @Test
    fun continuousCornerContractKeepsOnlyTwo1dOrFour2dNeighborsAcrossBoundaries() {
        val oneAxis = mapOf("rpms" to doubleArrayOf(0.0, 1.0, 2.0, 3.0))
        fun oneDimensionalCorners(value: Double): List<Double> = oneAxis.getValue("rpms").filter { coordinate ->
            atlasIsCurrentContinuousCorner(oneAxis, mapOf("rpms" to coordinate)) { value }
        }
        assertEquals(listOf(0.0, 1.0), oneDimensionalCorners(0.25))
        assertEquals(listOf(1.0, 2.0), oneDimensionalCorners(1.25))
        assertEquals(listOf(2.0, 3.0), oneDimensionalCorners(2.25))
        assertEquals(0.0, atlasContinuousCornerGain(oneAxis, mapOf("rpms" to 0.0)) { 1.25 }, 0.0)
        assertTrue(atlasContinuousCornerGain(oneAxis, mapOf("rpms" to 1.0)) { 1.25 } > 0.0)

        val twoAxes = mapOf("rpms" to doubleArrayOf(0.0, 1.0), "boost" to doubleArrayOf(0.0, 1.0))
        val corners = twoAxes.getValue("rpms").flatMap { rpm -> twoAxes.getValue("boost").map { boost -> mapOf("rpms" to rpm, "boost" to boost) } }
            .filter { coordinates -> atlasIsCurrentContinuousCorner(twoAxes, coordinates) { if (it == "rpms") 0.25 else 0.75 } }
        assertEquals(4, corners.size)
    }

    @Test
    fun resourceBoundsRejectMissingOrInconsistentProofTotalsAndConfiguresExactLimits() {
        assertThrows(NoSuchElementException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace("\"resourceBounds\":{", "\"removedResourceBounds\":{").toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
                "\"maximumMappedShardInstancesDuringTransitionSafeUpperBound\":2",
                "\"maximumMappedShardInstancesDuringTransitionSafeUpperBound\":3",
            ).toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
                "byd-full-event-atlas-runtime-resource-bounds-v3",
                "byd-full-event-atlas-runtime-resource-bounds-v2",
            ).toByteArray()))
        }
        val blockedSessionProof = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
            "\"proofStatus\":\"PASS\"}",
            "\"proofStatus\":\"BLOCKED_PENDING_SESSION_MAPPING_INSTANCE_PROOF\"}",
        ).toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { blockedSessionProof.requirePlaybackReady() }
        try {
            listOf(1, 2, 3).forEach { bound ->
                NativeAtlasMapRegistry.configureExactLimit(bound)
                assertEquals(bound, NativeAtlasMapRegistry.configuredLimit)
            }
        } finally {
            NativeAtlasMapRegistry.configureExactLimit(2)
        }
    }

    @Test
    fun sessionMmapLimitCountsBothRetainedEffectParticipantsAndCannotChangeWhileActive() {
        val atlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().toByteArray()))
        val bound = atlas.sessionResourceBounds.maximumMappedShardInstancesDuringTransitionSafeUpperBound
        NativeAtlasMapRegistry.configureExactLimit(bound)
        try {
            repeat(bound) { NativeAtlasMapRegistry.reserveMapping() }
            assertEquals(bound, NativeAtlasMapRegistry.activeMappings)
            assertThrows(IllegalStateException::class.java) { NativeAtlasMapRegistry.reserveMapping() }
            assertThrows(IllegalStateException::class.java) {
                NativeAtlasMapRegistry.configureExactLimit(bound + 1)
            }
        } finally {
            repeat(bound) { NativeAtlasMapRegistry.mappingClosed() }
            NativeAtlasMapRegistry.configureExactLimit(2)
        }
    }

    @Test
    fun hostGainClassesAreRequiredAndCannotChangeTheirProvenGains() {
        assertThrows(NoSuchElementException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace("\"hostGainClasses\"", "\"removedHostGainClasses\"").toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace("\"gainLinear\":0.5", "\"gainLinear\":0.75").toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace(
                    "continuousEngineBedAndFiniteSourcesInsideSameEngineEventInstance",
                    "separatelyStartedNonEngineEffectEventInstances",
                ).toByteArray(),
            ))
        }
    }

    @Test
    fun compactEffectTablesAreRequiredAndRejectTheLegacyRuntimeContract() {
        assertThrows(NoSuchElementException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace("\"variantBindings\"", "\"removedVariantBindings\"").toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace(
                    "byd-full-event-effect-runtime-v5",
                    "byd-full-event-effect-runtime-v4",
                ).toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace("\"engineProgramRole\":\"LOAD\"", "\"engineProgramRole\":\"UNCLASSIFIED\"")
                    .toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace(
                    "\"selectedPerspectiveEngineEventInstance\"",
                    "\"profileAudioSessionPersistentEventInstance\"",
                ).toByteArray(),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(
                runtimeJson().replace(
                    "selectionKindSpecificSeeSelectionStateOwnership",
                    "perEventInstancePerGroupIdPerPerspective",
                ).toByteArray(),
            ))
        }
    }

    @Test
    fun finiteResourceProofsAndCompactGroupScalarsFailClosed() {
        val missingPool = runtimeJson().replace("\"finiteRingPoolBytes\":98304", "\"finiteRingPoolBytes\":null")
        val missingPoolAtlas = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(missingPool.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { missingPoolAtlas.requirePlaybackReady() }

        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
                "\"finiteAttackCacheBytes\":256",
                "\"finiteAttackCacheBytes\":252",
            ).toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
                "\"maximumCaptureFramesPerLogicalRing\":64",
                "\"maximumCaptureFramesPerLogicalRing\":63",
            ).toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullEventAtlasParser.parse(AtlasRuntimeJson.parse(runtimeJson().replace(
                "\"streamingRingBufferFrames\":12288",
                "\"streamingRingBufferFrames\":6144",
            ).toByteArray()))
        }
    }

    @Test
    fun sharedShiftOverrideDoesNotSilenceGearGrind() {
        assertFalse(atlasNativeShiftTriggerEnabled(AtlasRuntimeTrigger.SHIFT_UP, sharedShiftSoundsEnabled = true))
        assertFalse(atlasNativeShiftTriggerEnabled(AtlasRuntimeTrigger.SHIFT_DOWN, sharedShiftSoundsEnabled = true))
        assertTrue(atlasNativeShiftTriggerEnabled(AtlasRuntimeTrigger.SHIFT_REJECTED, sharedShiftSoundsEnabled = true))
        assertTrue(atlasNativeShiftTriggerEnabled(null, sharedShiftSoundsEnabled = true))
    }

    @Test
    fun canonicalWavInspectionReadsOnlyHeaderMetadata() {
        val file = File.createTempFile("atlas-runtime", ".wav")
        try {
            val pcmBytes = 128 * 4
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcmBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(2)
                putInt(48_000)
                putInt(192_000)
                putShort(4)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes)
            }.array()
            file.outputStream().use { output ->
                output.write(header)
                output.write(ByteArray(pcmBytes))
            }
            val layout = inspectCanonicalAtlasWav(file)
            assertEquals(44L, layout.dataOffsetBytes)
            assertEquals(128L, layout.frameCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun cubicInterpolationMatchesSharedOracleGoldenVectors() {
        val fixtureBytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "atlas-cubic-interpolation-v1.json",
        )).use { it.readBytes() }
        val fixture = AtlasRuntimeJson.parse(fixtureBytes).objectValues("fixture")
        assertEquals(
            "byd-atlas-cubic-fixture-v1",
            fixture.getValue("schema").stringValue("fixture.schema"),
        )
        val frames = fixture.getValue("pcm16StereoFrames").arrayValues("fixture.pcm16StereoFrames")
            .mapIndexed { index, item ->
                item.arrayValues("fixture.pcm16StereoFrames[$index]").mapIndexed { channel, sample ->
                    sample.numberValue("fixture.pcm16StereoFrames[$index][$channel]") / Short.MAX_VALUE
                }
            }
        val loopStart = fixture.getValue("loopStartFrame").intValue("fixture.loopStartFrame")
        val loopEnd = fixture.getValue("loopEndFrameExclusive").intValue("fixture.loopEndFrameExclusive")
        fun resolve(frame: Int): Int {
            val length = loopEnd - loopStart
            return when {
                frame >= loopEnd -> loopStart + (frame - loopEnd) % length
                frame < loopStart -> loopEnd - 1 - ((loopStart - 1 - frame) % length)
                else -> frame
            }
        }
        fixture.getValue("vectors").arrayValues("fixture.vectors").forEachIndexed { vectorIndex, item ->
            val vector = item.objectValues("fixture.vectors[$vectorIndex]")
            var phase = vector.getValue("phaseOffsetFrames").numberValue("fixture.vectors[$vectorIndex].phase")
            val length = loopEnd - loopStart
            phase = loopStart + ((phase - loopStart) % length + length) % length
            val base = kotlin.math.floor(phase).toInt()
            val fraction = phase - base
            val expected = vector.getValue("expectedStereo").arrayValues("fixture.vectors[$vectorIndex].expected")
            repeat(2) { channel ->
                val actual = atlasCubicSample(
                    frames[resolve(base - 1)][channel],
                    frames[resolve(base)][channel],
                    frames[resolve(base + 1)][channel],
                    frames[resolve(base + 2)][channel],
                    fraction,
                )
                assertEquals(expected[channel].numberValue("fixture.expected[$channel]"), actual, 1.0e-15)
            }
        }
    }

    @Test
    fun zeroWeightNeighborsStayPhaseReadyAtAnExactBoundary() {
        val program = perspectiveProgram(doubleArrayOf(1_000.0, 2_000.0, 3_000.0))
        val factory = PhaseRecordingRegionFactory(program)
        val loader = AtlasHotCellLoader(program, factory, initialRpm = 2_000.0, initialThrottle = 0.5)
        try {
            assertEquals(4, factory.regions.size)
            assertEquals(3, factory.regions.sumOf { it.fullHistoryAlignments })
            val cell = loader.acquireCurrentOrThrow()
            try {
                val weights = DoubleArray(4)
                cell.fillWeights(program, 2_000.0, 0.5, weights)
                assertEquals(2, weights.count { it == 0.0 })
                cell.advance(2_000.0)
                assertTrue(factory.regions.all { it.advances == 1 })
            } finally {
                loader.release(cell)
            }
        } finally {
            loader.close()
        }
    }

    @Test
    fun hostMixPreservesRelativeGainAndLimitsTheCombinedFrameWithoutDelay() {
        val limiter = StereoCausalPeakLimiter(ceilingLinear = 0.98, releaseFrames = 4_800)
        val output = DoubleArray(2)
        limiter.process(1.0 * 0.5, -1.0 * 0.5, output)
        assertEquals(0.5, output[0], 0.0)
        assertEquals(-0.5, output[1], 0.0)

        limiter.process(1.0 * 0.5 + 0.8, -1.0 * 0.5 - 0.8, output)
        assertEquals(0.98, output[0], 1.0e-15)
        assertEquals(-0.98, output[1], 1.0e-15)

        limiter.process(0.5, -0.5, output)
        assertTrue(output[0] < 0.5)
        assertEquals(-output[0], output[1], 0.0)
    }

    @Test
    fun hostGainClassesMixEngineTransientsWithTheBedBeforeEffectEvents() {
        val contract = AtlasHostMixContract(
            engineEventHostGainLinear = 0.5,
            effectEventHostGainLinear = 1.0,
            limiterCeilingLinear = 0.98,
            limiterLookaheadFrames = 0,
            limiterAttackFrames = 1,
            limiterReleaseFrames = 4_800,
        )
        assertEquals(
            2.0,
            atlasHostMixInput(engineBed = 2.0, engineTransient = 1.0, effectEvent = 0.5, contract),
            0.0,
        )
        assertEquals(
            0.5,
            atlasHostMixInput(engineBed = 0.0, engineTransient = 1.0, effectEvent = 0.0, contract),
            0.0,
        )
        assertEquals(
            1.0,
            atlasHostMixInput(engineBed = 0.0, engineTransient = 0.0, effectEvent = 1.0, contract),
            0.0,
        )
    }

    @Test
    fun playlistSelectionMatchesTheSharedPythonGoldenFixture() {
        val fixtureBytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "atlas-playlist-selection-v3.json",
        )).use { it.readBytes() }
        val fixture = AtlasRuntimeJson.parse(fixtureBytes).objectValues("fixture")
        val atlasFamilyId = fixture.getValue("atlasFamilyId").stringValue("fixture.atlasFamilyId")
        val eventPath = fixture.getValue("eventPath").stringValue("fixture.eventPath")
        val profileAudioSessionGeneration = fixture.getValue("profileAudioSessionGeneration")
            .longValue("fixture.profileAudioSessionGeneration")
        val groupId = fixture.getValue("groupId").stringValue("fixture.groupId")
        assertEquals(
            fixture.getValue("expectedSeedUnsigned").stringValue("fixture.expectedSeedUnsigned"),
            AtlasEffectScheduler.seed(
                atlasFamilyId,
                eventPath,
                profileAudioSessionGeneration,
                groupId,
            ).toULong().toString(),
        )
        val members = fixture.getValue("members").arrayValues("fixture.members").mapIndexed { index, item ->
            val member = item.objectValues("fixture.members[$index]")
            AtlasSchedulingMember(
                sourceGuid = "source-$index",
                authoredOrder = member.getValue("authoredOrder").intValue("fixture.members[$index].authoredOrder"),
                weight = member.getValue("weight").numberValue("fixture.members[$index].weight"),
                triggerChancePercent = member.getValue("triggerChancePercent")
                    .numberValue("fixture.members[$index].triggerChancePercent"),
            )
        }
        listOf(
            "PlaylistPlayMode_SmartRandom" to "smartRandom",
            "PlaylistPlayMode_PlaySequential" to "playSequential",
        ).forEach { (playMode, vectorName) ->
            val scheduler = AtlasEffectScheduler(
                atlasFamilyId = atlasFamilyId,
                eventPath = eventPath,
                profileAudioSessionGeneration = profileAudioSessionGeneration,
                group = playlistGroup(groupId, playMode, members),
            )
            fixture.getValue(vectorName).arrayValues("fixture.$vectorName").forEachIndexed { index, item ->
                val expected = item.objectValues("fixture.$vectorName[$index]")
                assertEquals(
                    expected.getValue("selectedOrder").intValue("fixture.$vectorName[$index].selectedOrder"),
                    scheduler.selectMember(),
                )
                val draws = expected.getValue("drawsUnsignedDecimal")
                    .arrayValues("fixture.$vectorName[$index].drawsUnsignedDecimal")
                assertEquals(draws.size, scheduler.lastDrawCount)
                draws.forEachIndexed { drawIndex, draw ->
                    assertEquals(
                        draw.stringValue("fixture.$vectorName[$index].drawsUnsignedDecimal[$drawIndex]"),
                        scheduler.lastDraws[drawIndex].toULong().toString(),
                    )
                }
            }
        }
    }

    @Test
    fun simultaneousLayerConsumesOneChanceDrawForTheWholeLogicalGroup() {
        val member = AtlasSchedulingMember(
            sourceGuid = "source",
            authoredOrder = 0,
            weight = 1.0,
            triggerChancePercent = 100.0,
        )
        fun scheduler() = AtlasEffectScheduler(
            atlasFamilyId = "family",
            eventPath = "event:/cars/test/engine_int",
            profileAudioSessionGeneration = 1L,
            group = AtlasSchedulingGroup(
                id = "layer:source",
                composition = AtlasSchedulingComposition.SIMULTANEOUS_LAYER,
                selectionKind = "always",
                playMode = null,
                playModeValue = null,
                selectionMode = null,
                selectionModeValue = null,
                groupTriggerChancePercent = 100.0,
                members = listOf(member),
                timelinePlacements = emptyList(),
                maximumSourceCornerContributorsPerLogicalRing = 1,
                maximumFmodSourceChannelsPerLogicalRing = 1,
                maximumCaptureFramesPerLogicalRing = 256,
                streamingRingBufferFrames = 256,
                complete = true,
            ),
        )

        val direct = scheduler()
        assertTrue(direct.beginSimultaneousTrigger())
        assertEquals(1, direct.lastDrawCount)

        val throughMemberApi = scheduler()
        assertEquals(0, throughMemberApi.selectMember())
        assertEquals(1, throughMemberApi.lastDrawCount)
    }

    private fun perspectiveProgram(rpmAxis: DoubleArray): AtlasPerspectiveProgram {
        val nodes = buildList {
            rpmAxis.forEach { rpm ->
                listOf(0.0, 1.0).forEach { throttle ->
                    add(
                        AtlasEngineNode(
                            rpm = rpm,
                            throttle = throttle,
                            shardName = "engine.wav",
                            startFrame = size * 128L,
                            endFrameExclusive = size * 128L + 128L,
                            loopStartFrame = size * 128L,
                            loopEndFrameExclusive = size * 128L + 128L,
                            modePrograms = testModePrograms(size * 128L, size * 128L + 128L),
                        ),
                    )
                }
            }
        }
        return AtlasPerspectiveProgram(rpmAxis, doubleArrayOf(0.0, 1.0), nodes)
    }

    private fun playlistGroup(
        groupId: String,
        playMode: String,
        members: List<AtlasSchedulingMember>,
    ): AtlasSchedulingGroup = AtlasSchedulingGroup(
        id = groupId,
        composition = AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE,
        selectionKind = "fmodMultiInstrumentPlaylist",
        playMode = playMode,
        playModeValue = if (playMode == "PlaylistPlayMode_SmartRandom") 2 else 0,
        selectionMode = "PlaylistSelectionMode_SelectNormal",
        selectionModeValue = 1,
        groupTriggerChancePercent = 100.0,
        members = members,
        timelinePlacements = emptyList(),
        maximumSourceCornerContributorsPerLogicalRing = 1,
        maximumFmodSourceChannelsPerLogicalRing = 1,
        maximumCaptureFramesPerLogicalRing = 256,
        streamingRingBufferFrames = 256,
        complete = true,
    )

    private fun runtimeJson(): String = """
        {
          "schema":"byd-full-event-atlas-runtime-v3",
          "id":"atlas_test",
          "draftBlocked":false,
          "modeRows":{
            "LOAD":{"throttle":1.0,"livePedalIgnored":true},
            "COAST":{"throttle":0.0,"livePedalIgnored":true},
            "BOTH":{"throttle":"livePedal"}
          },
          "perspectives":{
            "cabin":${perspectiveJson()},
            "exterior":${perspectiveJson()}
          },
          "hotCellPolicy":{
            "neighborSelection":"binarySearchLowerUpperOnSortedAxes",
            "maximumMappedLoopNodesPerPerspective":4,
            "LOADOrCOASTMappedNodesPerPerspective":2,
            "BOTHMappedNodesPerPerspective":4,
            "cellReplacement":"hold the previous ready cell until its leaving node is clamped to zero at/after the boundary; then prepare the entering node while its raw bilinear weight is zero",
            "wholeAtlasHeapDecodeForbidden":true,
            "packedWavAccess":"read-only mmap of PCM data chunk"
          },
          "effects":${effectRuntimeJson()},
          "interpolationContract":{
            "algorithm":"phaseCoherentRootRpmBilinear-v1",
            "nodeRootRpmPlaybackRatio":{"formula":"targetRpm/nodeRpm","zeroRootRatio":1.0,"minimum":0.1,"maximum":4.0},
            "phaseAlignment":"correlationAlignedLoopPhase",
            "phaseReference":"targetRpmNormalizedProgress",
            "crossfade":"none; raw bilinear gains after zero-gain preparation",
            "activation":{"prepareOnlyAtZeroWeight":true,"gainFormula":"rawBilinearWeight","audibleRamp":"none","unreadyPolicy":"holdPreviousReadyCell","neverMapMoreThanNodes":4,"mappedCellCorners":"allUniqueLowerUpperRpmByLowerUpperThrottleIncludingZeroWeightNeighbors","zeroWeightNeighborPhasePolicy":"correlationAlignAtCellCreationAndAdvanceEveryOutputFrame"},
            "correlation":{"channelScore":"sumStereoDotProducts","windowFrames":960,"searchOffsetFrames":960,"candidateAnchor":"loopStartPlusPhaseOffsetFrames","minimumRmsLinear":0.001,"tieBreak":"smallestAbsoluteOffsetThenNegative","coarseOffsetStrideFrames":8,"coarseReferenceFrameStride":4,"fineSearchHalfWidthFrames":8,"fineReferenceFrameStride":1,"offsetIteration":"ascendingInclusive","coldStart":"highestWeightCornerReferenceThenAlignEveryMappedCornerIncludingZeroWeight"},
            "oracleStatus":"PASS",
            "oracleReportSha256":"${"1".repeat(64)}"
          },
          "hostMixContract":{
            "schema":"byd-full-event-atlas-host-mix-v1",
            "hostGainClasses":{
              "engineEvent":{"gainLinear":0.5,"appliesTo":"continuousEngineBedAndFiniteSourcesInsideSameEngineEventInstance"},
              "effectEvent":{"gainLinear":1.0,"appliesTo":"separatelyStartedNonEngineEffectEventInstances"}
            },
            "postSumMaster":{"algorithm":"stereoLinkedCausalPeakLimiter-v1","ceilingLinear":0.98,"lookaheadFrames":0,"outputDelayFrames":0,"preRoll":"none","blockState":"continuousAcrossRenderBlocks","stopTail":"none","attackFrames":1,"releaseFrames":4800,"releaseStepPerFrame":"(1.0-currentGain)/releaseFrames","detector":"maxAbsoluteStereoSampleCurrentFrame","targetGain":"min(1.0,ceilingLinear/detectorPeak)","gainSmoothing":"attackImmediateReleaseLinearTowardOne"},
            "requiresCombinedEngineEffectMixOracle":true
          },
          "resourceBounds":{
            "schema":"byd-full-event-atlas-runtime-resource-bounds-v3",
            "scope":"selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects",
            "perPerspective":{
              "cabin":{"engine":{"maximumMappedShardInstancesDuringCellTransition":1,"maximumFmodLogicalSourceChannelsAtAtlasNode":1,"androidPremixedBedIsNotFmodChannelAccounting":true},"effects":{"maximumMappedShardInstancesSafeUpperBound":1,"maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails":1,"maximumContinuousMmapPlaybackCornerVoices":0,"maximumContinuousMappedSourceCorners":0,"maximumFiniteLogicalRingVoicesPerOneDspUpdate":1,"maximumMappedNodesPerUpdate":1,"maximumSourceCornerRegionsDuringMaterialization":1,"maximumFiniteSourceCornerContributorsPerUpdate":1,"maximumFiniteMappedSourceCornerRegionsDuringMaterialization":1,"maximumFmodContinuousSourceChannels":0,"maximumFmodFiniteSourceChannelsPerOneDspUpdate":1,"finiteAttackCacheBytes":256,"finiteAttackCacheMeaning":"sum(min(nodeFrames,4096)*stereoPcm16BytesPerFrame)ForEveryFiniteNodePrearmedInSelectedPerspective","finiteRingPoolBytes":98304,"finiteRingPoolStatus":"PASS","finiteRingPoolFormula":"sum(physicalLiveLogicalRingInstancesBySchedulingGroup[groupId]*streamingRingBufferFrames[groupId]*8)","peakProofStatus":"PASS"},"total":{"maximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails":2,"fmodRawSourceAccounting":"engineContinuousSourcesPlusEffectContinuousSourcesPlusNewFiniteSources; priorFiniteTailsRequireGlobalArbitrationOracle","peakProofStatus":"PASS"}},
              "exterior":{"engine":{"maximumMappedShardInstancesDuringCellTransition":1,"maximumFmodLogicalSourceChannelsAtAtlasNode":1,"androidPremixedBedIsNotFmodChannelAccounting":true},"effects":{"maximumMappedShardInstancesSafeUpperBound":0,"maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails":0,"maximumContinuousMmapPlaybackCornerVoices":0,"maximumContinuousMappedSourceCorners":0,"maximumFiniteLogicalRingVoicesPerOneDspUpdate":0,"maximumMappedNodesPerUpdate":0,"maximumSourceCornerRegionsDuringMaterialization":0,"maximumFiniteSourceCornerContributorsPerUpdate":0,"maximumFiniteMappedSourceCornerRegionsDuringMaterialization":0,"maximumFmodContinuousSourceChannels":0,"maximumFmodFiniteSourceChannelsPerOneDspUpdate":0,"finiteAttackCacheBytes":0,"finiteAttackCacheMeaning":"sum(min(nodeFrames,4096)*stereoPcm16BytesPerFrame)ForEveryFiniteNodePrearmedInSelectedPerspective","finiteRingPoolBytes":0,"finiteRingPoolStatus":"PASS","finiteRingPoolFormula":"sum(physicalLiveLogicalRingInstancesBySchedulingGroup[groupId]*streamingRingBufferFrames[groupId]*8)","peakProofStatus":"PASS"},"total":{"maximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails":1,"fmodRawSourceAccounting":"engineContinuousSourcesPlusEffectContinuousSourcesPlusNewFiniteSources; priorFiniteTailsRequireGlobalArbitrationOracle","peakProofStatus":"PASS"}}
            },
            "session":{"mappingInstanceIdentity":"activationPerspectivePlusShardName","retainedEffectPerspectives":["cabin","exterior"],"perSelectedEnginePerspective":{"cabin":{"engineMaximumMappedShardInstancesDuringCellTransition":1,"retainedCabinEffectsMaximumMappedShardInstances":1,"retainedExteriorEffectsMaximumMappedShardInstances":0,"maximumMappedShardInstancesDuringTransitionSafeUpperBound":2},"exterior":{"engineMaximumMappedShardInstancesDuringCellTransition":1,"retainedCabinEffectsMaximumMappedShardInstances":1,"retainedExteriorEffectsMaximumMappedShardInstances":0,"maximumMappedShardInstancesDuringTransitionSafeUpperBound":2}},"maximumMappedShardInstancesDuringTransitionSafeUpperBound":2,"proofStatus":"PASS"}
          },
          "shards":[{"name":"engine.wav","sha256":"${"0".repeat(64)}","bytes":1068}]
        }
    """.trimIndent()

    private fun effectRuntimeJson(): String = """
        {
          "resourceModel":"profileSessionRetainedEffectsResourceBounds-v3",
          "channelArbitration":{"schema":"byd-full-event-fmod-channel-arbitration-oracle-v2","assettoStudioLogicalChannelCap":2048,"assettoSoftwareRealChannelBudget":256,"premixAdmissionParity":{"requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget":true,"realBudget":256,"scenarioDemand":"continuousRawSourcesPlusEveryCausallyLiveFiniteTailSource","onExceeded":"BLOCK_RELEASE_REQUIRE_SOURCE_STEMS_FOR_PER_SOURCE_PRIORITY_AUDIBILITY_AND_VIRTUALIZATION","scalarOnlyProofIsSufficient":false},"status":"PASS"},
          "maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails":1,
          "maximumMappedNodesPerUpdate":1,
          "maximumSourceCornerRegionsDuringMaterialization":1,
          "runtimeContract":{
            "schema":"byd-full-event-effect-runtime-v5",
            "variantBindingIdentity":"familyLocalVnRefPlusExactAuthoredBindingKeyAndSourceGuid",
            "schedulingGroupIdentity":"familyLocalGnRefPlusExactAuthoredGroupId",
            "runtimeMappingProfileIdentity":"familyLocalMnRefPlusCanonicalExecutableMapping",
            "nodeBinding":"nodes[][0] is variantBindingRef resolving to authoredBindingKey",
            "nodeEncoding":{"schema":"byd-full-event-effect-node-array-v1","fields":["variantBindingRef","parameters","shardName","startFrame","endFrameExclusive","loopStartFrame","loopEndFrameExclusive"],"sourceIdentity":"nodes[][0] resolves to variantBindings[].authoredBindingKeyAndSourceGuid","finiteDurationFrames":"nodes[][4]-nodes[][3]"},
            "execution":{
              "schema":"byd-full-event-effect-execution-contract-v1",
              "continuous":{"algorithm":"perSourceAxisAlignedMultilinear-v1","axisSource":"sourceBinding.parameterAxes","axisBounds":"clampToAuthoredEndpointThenBinarySearchLowerUpper","cornerGainFormula":"rawNDimensionalMultilinearWeight","duplicateCornerPolicy":"sumDuplicateAxesThenMapOneNodeOnce","nodeIdentity":"requiredAuthoredBindingKeyPlusCanonicalParameters","nodePlaybackRatio":1.0,"mmapPolicy":"mapOnlyCurrentSourceCorners; unmapAfterSourceDeactivation","lifecycle":"startOnSemanticTriggerUpdateParametersWhileActiveStopOnSemanticDeactivation"},
              "oneShot":{"algorithm":"perSourceAxisAlignedMultilinearFiniteRing-v2","axisSource":"sourceBinding.parameterAxes","axisBounds":"clampToAuthoredEndpointThenBinarySearchLowerUpper","cornerGainFormula":"rawNDimensionalMultilinearWeight","duplicateCornerPolicy":"sumDuplicateAxesThenMixOneFiniteRingContributorOnce","nodeIdentity":"requiredAuthoredBindingKeyPlusCanonicalParameters","selection":"chooseSchedulingGroupMembersThenMixEveryNonZeroCornerForEachSelectedMemberIntoOneLogicalGroupRing","logicalVoice":{"model":"onePreallocatedFiniteRingVoicePerSchedulingGroupInstance","materialization":"evaluateGroupAndMemberSelectionOnceThenAtomicallyMixWeightedFloat32OrFloat64StereoContributorsFromExactMappedNodes","pcm16Premix":"forbidden","tail":"retainMixedRingUntilEverySelectedCapturedContributorEnds","sourceCornerRegions":"audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; noAudioCallbackMmapAllocationLockOrPcm16PremixStorage"},"finiteRepeat":"renderAndPlayExactlyCapturedFiniteDuration"},
              "schedulingGroupComposition":"sumIndependentSimultaneousGroups; alternativesOnlyWithinSameGroupId"
            },
            "selectionRuntimeContractTable":"selectionRuntimeContracts[].id"
          },
          "variantBindings":[{"id":"v0","sourceGuid":"source-shift","authoredBindingKey":"binding:${"1".repeat(64)}","runtimeMappingRef":"m0","schedulingGroupRef":"g0"}],
          "runtimeMappingProfiles":[{"id":"m0","runtimeMapping":{"hostGainClass":"engineEvent","engineProgramRole":"LOAD","eventInstanceOwnership":{"schema":"byd-fmod-event-instance-ownership-v1","key":"exactEventPath","owner":"selectedPerspectiveEngineEventInstance","created":"selectedEngineEventPathStartForActiveProfileAudioSession","survives":"loadCoastBothModeChangeOnly","resets":"thatExactEngineEventPathStopRewindStartOrNewInstance","activationGeneration":"incrementsForEveryStopRewindStartOfThatExactEnginePath"},"finiteLifecycleTopology":{"schema":"byd-fmod-finite-lifecycle-topology-v1","status":"PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE","topology":"parameterPlacementOnly"},"hostParameterBindings":[{"parameter":"rpms","source":"EngineSimulation.rpm"}],"parameters":{"rpms":0.0},"parameterAxes":{"rpms":[0.0]},"parameterDomains":{"rpms":[0.0,10000.0]},"parameterPlacements":{},"parameterPlacementEntry":${parameterPlacementEntryJson()},"authoredParameterDefaults":[{"parameter":"rpms","parameterGuid":"parameter-rpms","defaultValue":0.0,"type":"FMOD_STUDIO_PARAMETER_GAME_CONTROLLED"},{"parameter":"distance","parameterGuid":"parameter-distance","defaultValue":0.0,"type":"FMOD_STUDIO_PARAMETER_AUTOMATIC_DISTANCE"}],"semanticLifecycle":[{"trigger":"PARAMETER_PLACEMENT_ENTRY","lifetime":"oneShot","signal":"EngineSimulation.engineEventHostParameters","start":"atEngineEventInstanceCreationIfInitialPlacementMembershipInsideOrEveryOutsideToInsidePlacementEntry","parameterSample":"currentHostParameterBindingsAtExactDspBlock","stop":"capturedOneShotOrFiniteRepeatEnd","retrigger":"everyOutsideToInsidePlacementEntrySubjectToSchedulingGroupPolyphony","parameterPlacementEntry":${parameterPlacementEntryJson()}}],"triggers":["PARAMETER_PLACEMENT_ENTRY"],"perspectives":["cabin"],"kind":"engineEventTransient","lifetime":"oneShot"}}],
          "schedulingGroups":[{"id":"g0","groupId":"layer:source-shift","selectionRuntimeContractRef":"s0","complete":true,"composition":"simultaneousLayer","incompleteReason":null,"selection":{"kind":"always","triggerChance":{"source":"waveformInstrument.baseProperties.triggerChancePercent","percent":100.0,"defaultPercentWhenNull":100.0,"activation":"independentPerSemanticTrigger","acceptance":"uniformTimes100 < triggerChancePercent"}},"members":[{"sourceGuid":"source-shift","authoredOrder":0,"weight":1.0,"triggerChancePercent":100.0}],"timelinePlacements":[],"maximumSourceCornerContributorsPerLogicalRing":1,"maximumFmodSourceChannelsPerLogicalRing":1,"maximumCaptureFramesPerLogicalRing":64,"streamingRingBufferFrames":12288}],
          "selectionRuntimeContracts":[{"id":"s0","contract":${simultaneousSelectionRuntimeContractJson()}}],
          "events":[{"eventPath":"event:/cars/test/engine_int","eventSuffix":"engine_int","perspectives":["cabin"],"runtimeTriggers":["PARAMETER_PLACEMENT_ENTRY"],"runtimeMappingBlocked":false,"variantBindingRefs":["v0"],"schedulingGroupRefs":["g0"],"nodes":[["v0",{"rpms":0.0},"engine.wav",0,64,null,null]]}]
        }
    """.trimIndent()

    private fun simultaneousSelectionRuntimeContractJson(): String = """
        {
          "schema":"byd-full-event-playlist-selection-v1",
          "schedulerKind":"simultaneousLayer",
          "stateScope":"selectionKindSpecificSeeSelectionStateOwnership",
          "seedDerivation":{
            "encoding":"utf8",
            "formula":"sha256('byd-fmod-playlist-v3|'+atlasFamilyId+'|'+eventPath+'|'+profileAudioSessionGeneration+'|'+groupId)",
            "atlasFamilyId":"runtimeIndex.id",
            "appliesTo":"androidDeterministicSmartRandomSubstituteOnly; notFMODSequenceParity",
            "take":"first8BytesBigEndianUnsigned",
            "zeroSeedReplacementUnsigned":"0x9e3779b97f4a7c15"
          },
          "rng":{
            "algorithm":"xorshift64star-v1",
            "unsignedArithmetic":"uint64Modulo2To64",
            "stateTransition":[
              "x = x xor (x unsignedShiftRight 12)",
              "x = x xor ((x shiftLeft 25) modulo2To64)",
              "x = x xor (x unsignedShiftRight 27)"
            ],
            "output":"postTransitionStateTimes2685821657736338717Modulo2To64",
            "uniform":"(outputUnsigned unsignedShiftRight 11) / 9007199254740992.0"
          },
          "selection":{
            "kind":"always",
            "drawConsumption":"oneRngOutputForThisLayerChanceOnEverySemanticTrigger",
            "history":"none"
          },
          "triggerChance":{
            "source":"waveformInstrument.baseProperties.triggerChancePercent",
            "defaultPercentWhenNull":100.0,
            "acceptance":"uniformTimes100 < triggerChancePercent",
            "onRejected":"silent"
          },
          "invalidAuthoredValue":"blockReleaseNonFiniteChanceOrChanceOutside0To100OrNonFiniteNonPositiveWeight"
        }
    """.trimIndent()

    private fun parameterPlacementEntryJson(): String = """
        {
          "schema":"byd-fmod-parameter-placement-entry-v1",
          "stateScope":"perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration",
          "initialState":{"inside":"startOnceAtCurrentHostParameterValue","outside":"remainSilentUntilOutsideToInsideEntry","when":"exactEventInstanceCreated"},
          "membership":{
            "parameterCombination":"allParameterGroupsMustContainCurrentValue",
            "placementsWithinParameter":"allInstrumentChainPlacementsMustContainCurrentValue",
            "startBoundary":"inclusive",
            "endBoundary":"includeEndFromAuthoredParameterPlacement",
            "placements":{
              "distance":[{"start":0.0,"end":1.0,"includeEnd":false,"parameterGuid":"parameter-distance","layoutGuid":"layout-distance","instrumentGuid":"source-shift"}],
              "rpms":[{"start":1000.0,"end":2000.0,"includeEnd":true,"parameterGuid":"parameter-rpms","layoutGuid":"layout-rpms","instrumentGuid":"source-shift"}]
            },
            "parameterValues":[
              {"parameter":"distance","parameterGuid":"parameter-distance","layoutGuid":"layout-distance","value":{"kind":"authoredDefault","value":0.0}},
              {"parameter":"rpms","parameterGuid":"parameter-rpms","layoutGuid":"layout-rpms","value":{"kind":"hostBinding","binding":{"parameter":"rpms","source":"EngineSimulation.rpm"}}}
            ]
          },
          "transition":{"sampleBoundary":"eachDspBlockAfterHostParameterUpdateForHostBoundParameters","trigger":"combinedMembershipOutsideToInside","exit":"combinedMembershipInsideToOutsideArmsNextEntry","directions":["increasing","decreasing","discontinuousJump"]}
        }
    """.trimIndent()

    private fun perspectiveJson(): String = """
        {
          "rpmAxis":[1000.0,2000.0],
          "throttleAxis":[0.0,1.0],
          "nodes":[
            {"rpm":1000.0,"throttle":0.0,"shardName":"engine.wav","startFrame":0,"endFrameExclusive":128,"loopStartFrame":0,"loopEndFrameExclusive":128,"phaseOffsetFrames":0.0,"modePrograms":{"loadOnly":{"shardName":"engine.wav","startFrame":0,"endFrameExclusive":128,"loopStartFrame":0,"loopEndFrameExclusive":128},"coastOnly":{"shardName":"engine.wav","startFrame":0,"endFrameExclusive":128,"loopStartFrame":0,"loopEndFrameExclusive":128}}},
            {"rpm":1000.0,"throttle":1.0,"shardName":"engine.wav","startFrame":128,"endFrameExclusive":256,"loopStartFrame":128,"loopEndFrameExclusive":256,"phaseOffsetFrames":0.0,"modePrograms":{"loadOnly":{"shardName":"engine.wav","startFrame":128,"endFrameExclusive":256,"loopStartFrame":128,"loopEndFrameExclusive":256},"coastOnly":{"shardName":"engine.wav","startFrame":128,"endFrameExclusive":256,"loopStartFrame":128,"loopEndFrameExclusive":256}}},
            {"rpm":2000.0,"throttle":0.0,"shardName":"engine.wav","startFrame":256,"endFrameExclusive":384,"loopStartFrame":256,"loopEndFrameExclusive":384,"phaseOffsetFrames":0.0,"modePrograms":{"loadOnly":{"shardName":"engine.wav","startFrame":256,"endFrameExclusive":384,"loopStartFrame":256,"loopEndFrameExclusive":384},"coastOnly":{"shardName":"engine.wav","startFrame":256,"endFrameExclusive":384,"loopStartFrame":256,"loopEndFrameExclusive":384}}},
            {"rpm":2000.0,"throttle":1.0,"shardName":"engine.wav","startFrame":384,"endFrameExclusive":512,"loopStartFrame":384,"loopEndFrameExclusive":512,"phaseOffsetFrames":0.0,"modePrograms":{"loadOnly":{"shardName":"engine.wav","startFrame":384,"endFrameExclusive":512,"loopStartFrame":384,"loopEndFrameExclusive":512},"coastOnly":{"shardName":"engine.wav","startFrame":384,"endFrameExclusive":512,"loopStartFrame":384,"loopEndFrameExclusive":512}}}
          ]
        }
    """.trimIndent()

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for atlas prefetch" }
            Thread.yield()
        }
    }

    private class FakeRegion(
        override val nodeIndex: Int,
        override val nodeRpm: Double,
        private val onClose: (() -> Unit)? = null,
    ) : AtlasNodeRegion {
        override val frameCount: Int = 128
        private var closed = false

        override fun sampleAt(program: AtlasEngineProgram, channel: Int): Double = 0.0

        override fun advance(playbackRatio: Double) = Unit

        override fun close() {
            if (closed) return
            closed = true
            onClose?.invoke()
        }
    }

    private class CountingRegionFactory(private val program: AtlasPerspectiveProgram) : AtlasNodeRegionFactory {
        val activeRegions = AtomicInteger(0)
        val maximumActiveRegions = AtomicInteger(0)
        val activeShards = AtomicInteger(0)

        override fun map(nodeIndex: Int, node: AtlasEngineNode): AtlasNodeRegion {
            if (activeRegions.getAndIncrement() == 0) activeShards.incrementAndGet()
            maximumActiveRegions.updateAndGet { maximum -> maxOf(maximum, activeRegions.get()) }
            return FakeRegion(nodeIndex, node.rpm) {
                if (activeRegions.decrementAndGet() == 0) activeShards.decrementAndGet()
            }
        }

        override fun close() {
            assertEquals(0, activeRegions.get())
        }
    }

    private class PhaseRecordingRegionFactory(
        private val program: AtlasPerspectiveProgram,
    ) : AtlasNodeRegionFactory {
        val regions = mutableListOf<PhaseRecordingRegion>()

        override fun map(nodeIndex: Int, node: AtlasEngineNode): AtlasNodeRegion =
            PhaseRecordingRegion(nodeIndex, node.rpm).also(regions::add)
    }

    private class PhaseRecordingRegion(
        override val nodeIndex: Int,
        override val nodeRpm: Double,
    ) : AtlasNodeRegion {
        override val frameCount = 128
        var fullHistoryAlignments = 0
        var advances = 0

        override fun sampleAt(program: AtlasEngineProgram, channel: Int): Double {
            val amplitude = when (program) {
                AtlasEngineProgram.FULL -> 0.2
                AtlasEngineProgram.LOAD_ONLY -> 0.1
                AtlasEngineProgram.COAST_ONLY -> 0.05
            }

            return if (channel == 0) amplitude else -amplitude
        }

        override fun advance(playbackRatio: Double) {
            advances += 1
        }

        override fun alignToHistory(
            historyLeft: DoubleArray,
            historyRight: DoubleArray,
            historyFrames: Int,
            targetRpm: Double,
            program: AtlasEngineProgram,
            continueAfterHistory: Boolean,
        ) {
            if (historyFrames == AtlasOutputHistory.CAPACITY_FRAMES) fullHistoryAlignments += 1
        }

        override fun mixNextFramesInto(
            destinationLeft: DoubleArray,
            destinationRight: DoubleArray,
            frameCount: Int,
            targetRpm: Double,
            program: AtlasEngineProgram,
            gain: Double,
            clearDestination: Boolean,
        ) {
            repeat(frameCount) { frame ->
                val amplitude = when (program) {
                    AtlasEngineProgram.FULL -> 0.2
                    AtlasEngineProgram.LOAD_ONLY -> 0.1
                    AtlasEngineProgram.COAST_ONLY -> 0.05
                }
                if (clearDestination) {
                    destinationLeft[frame] = amplitude * gain
                    destinationRight[frame] = -amplitude * gain
                } else {
                    destinationLeft[frame] += amplitude * gain
                    destinationRight[frame] -= amplitude * gain
                }
            }
        }

        override fun close() = Unit
    }

    private fun testModePrograms(
        startFrame: Long,
        endFrameExclusive: Long,
        shardName: String = "engine.wav",
    ): AtlasEngineModePrograms {
        fun geometry() = AtlasEnginePcmGeometry(
            shardName = shardName,
            startFrame = startFrame,
            endFrameExclusive = endFrameExclusive,
            loopStartFrame = startFrame,
            loopEndFrameExclusive = endFrameExclusive,
        )

        return AtlasEngineModePrograms(loadOnly = geometry(), coastOnly = geometry())
    }

    private class InterruptibleBlockingRegionFactory : AtlasNodeRegionFactory {
        val mapEntered = CountDownLatch(1)
        val mapExited = AtomicBoolean(false)
        val workerInterrupted = AtomicBoolean(false)
        val closedAfterWorkerExit = AtomicBoolean(false)

        override fun map(nodeIndex: Int, node: AtlasEngineNode): AtlasNodeRegion {
            mapEntered.countDown()
            try {
                CountDownLatch(1).await()
                error("Blocking atlas mapper unexpectedly resumed")
            } catch (error: InterruptedException) {
                workerInterrupted.set(true)
                throw error
            } finally {
                mapExited.set(true)
            }
        }

        override fun close() {
            closedAfterWorkerExit.set(mapExited.get())
        }
    }
}
