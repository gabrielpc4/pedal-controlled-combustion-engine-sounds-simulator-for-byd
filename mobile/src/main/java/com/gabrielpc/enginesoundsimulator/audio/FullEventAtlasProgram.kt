package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.abs

internal data class AtlasShard(
    val name: String,
    val sha256: String,
    val bytes: Long,
)

internal data class AtlasEnginePcmGeometry(
    val shardName: String,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val loopStartFrame: Long?,
    val loopEndFrameExclusive: Long?,
) {
    val frameCount: Long get() = endFrameExclusive - startFrame
}

internal data class AtlasEngineModePrograms(
    val loadOnly: AtlasEnginePcmGeometry,
    val coastOnly: AtlasEnginePcmGeometry,
)

internal enum class AtlasEngineProgram {
    FULL,
    LOAD_ONLY,
    COAST_ONLY,
}

internal data class AtlasEngineNode(
    val rpm: Double,
    val throttle: Double,
    val shardName: String,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val loopStartFrame: Long?,
    val loopEndFrameExclusive: Long?,
    val modePrograms: AtlasEngineModePrograms,
    val phaseOffsetFrames: Double = 0.0,
) {
    val frameCount: Long get() = endFrameExclusive - startFrame
}

internal data class AtlasPerspectiveProgram(
    val rpmAxis: DoubleArray,
    val throttleAxis: DoubleArray,
    val nodes: List<AtlasEngineNode>,
) {
    private val nodeIndicesByCoordinate: IntArray

    init {
        require(rpmAxis.size >= 2 && rpmAxis.isStrictlyIncreasing()) { "Atlas RPM axis is invalid" }
        require(throttleAxis.size >= 2 && throttleAxis.isStrictlyIncreasing()) { "Atlas throttle axis is invalid" }
        require(throttleAxis.first() == 0.0 && throttleAxis.last() == 1.0) {
            "Atlas throttle axis must cover 0 and 1"
        }
        require(nodes.size == rpmAxis.size * throttleAxis.size) { "Atlas node grid is incomplete" }
        nodeIndicesByCoordinate = IntArray(nodes.size) { -1 }
        nodes.forEachIndexed { nodeIndex, node ->
            val rpmIndex = rpmAxis.binarySearch(node.rpm)
            val throttleIndex = throttleAxis.binarySearch(node.throttle)
            require(rpmIndex >= 0 && throttleIndex >= 0) { "Atlas node is outside its axes" }
            val coordinateIndex = rpmIndex * throttleAxis.size + throttleIndex
            require(nodeIndicesByCoordinate[coordinateIndex] == -1) { "Atlas node grid has duplicates" }
            nodeIndicesByCoordinate[coordinateIndex] = nodeIndex
        }
        require(nodeIndicesByCoordinate.none { it < 0 }) { "Atlas node grid is incomplete" }
        nodes.forEach(::validateNode)
        for (rpmIndex in 0 until rpmAxis.lastIndex) {
            for (throttleIndex in 0 until throttleAxis.lastIndex) {
                val cellShardNames = buildSet {
                    for (row in rpmIndex..rpmIndex + 1) {
                        for (column in throttleIndex..throttleIndex + 1) {
                            add(nodes[nodeIndex(row, column)].shardName)
                        }
                    }
                }
                require(cellShardNames.size <= 2) {
                    "One atlas interpolation cell may use at most two mmap shards"
                }
            }
        }
    }

    fun nodeIndex(rpmIndex: Int, throttleIndex: Int): Int =
        nodeIndicesByCoordinate[rpmIndex * throttleAxis.size + throttleIndex]

    private fun validateNode(node: AtlasEngineNode) {
        require(rpmAxis.binarySearch(node.rpm) >= 0 && throttleAxis.binarySearch(node.throttle) >= 0) {
            "Atlas node is outside its axes"
        }
        require(isSafeAtlasShardName(node.shardName)) { "Unsafe atlas shard name" }
        require(node.startFrame >= 0 && node.endFrameExclusive > node.startFrame) { "Invalid atlas node range" }
        require((node.loopStartFrame == null) == (node.loopEndFrameExclusive == null)) {
            "Atlas loop bounds must both be null or present"
        }
        if (node.loopStartFrame != null && node.loopEndFrameExclusive != null) {
            require(node.loopStartFrame >= node.startFrame)
            require(node.loopEndFrameExclusive <= node.endFrameExclusive)
            require(node.loopEndFrameExclusive > node.loopStartFrame)
            require(node.phaseOffsetFrames >= 0.0 &&
                node.phaseOffsetFrames < node.loopEndFrameExclusive - node.loopStartFrame) {
                "Atlas phase offset must be loop-relative"
            }
        } else {
            require(node.phaseOffsetFrames >= 0.0 && node.phaseOffsetFrames < node.frameCount) {
                "Atlas phase offset must be node-relative"
            }
        }
        require(node.phaseOffsetFrames.isFinite()) { "Atlas phase offset is not finite" }
        validateModeProgram(node, "LOAD_ONLY", node.modePrograms.loadOnly)
        validateModeProgram(node, "COAST_ONLY", node.modePrograms.coastOnly)
    }

    private fun validateModeProgram(
        node: AtlasEngineNode,
        program: String,
        geometry: AtlasEnginePcmGeometry,
    ) {
        require(isSafeAtlasShardName(geometry.shardName)) { "Unsafe atlas $program shard name" }
        require(geometry.shardName == node.shardName) {
            "Atlas FULL/LOAD/COAST regions must share one mmap shard"
        }
        require(geometry.startFrame >= 0 && geometry.endFrameExclusive > geometry.startFrame) {
            "Invalid atlas $program range"
        }
        require(geometry.frameCount == node.frameCount) {
            "Atlas $program capture does not share FULL capture geometry"
        }
        require((geometry.loopStartFrame == null) == (geometry.loopEndFrameExclusive == null)) {
            "Atlas $program loop bounds must both be null or present"
        }
        require((geometry.loopStartFrame == null) == (node.loopStartFrame == null)) {
            "Atlas $program loop presence differs from FULL"
        }
        if (geometry.loopStartFrame != null && geometry.loopEndFrameExclusive != null &&
            node.loopStartFrame != null && node.loopEndFrameExclusive != null
        ) {
            require(geometry.loopStartFrame >= geometry.startFrame)
            require(geometry.loopEndFrameExclusive <= geometry.endFrameExclusive)
            require(geometry.loopEndFrameExclusive > geometry.loopStartFrame)
            require(geometry.loopStartFrame - geometry.startFrame == node.loopStartFrame - node.startFrame &&
                geometry.loopEndFrameExclusive - geometry.startFrame == node.loopEndFrameExclusive - node.startFrame) {
                "Atlas $program loop geometry differs from FULL"
            }
        }
    }
}

internal data class AtlasEffectNode(
    val parameters: Map<String, Double>,
    val lifetime: AtlasEffectLifetime,
    val hostGainClass: AtlasHostGainClass,
    val requiredAuthoredBindingKey: String,
    val requiredSourceGuid: String,
    val shardName: String,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val loopStartFrame: Long?,
    val loopEndFrameExclusive: Long?,
)

internal enum class AtlasSchedulingComposition {
    SIMULTANEOUS_LAYER,
    PLAYLIST_ALTERNATIVE,
}

internal data class AtlasSchedulingMember(
    val sourceGuid: String,
    val authoredOrder: Int,
    val weight: Double,
    val triggerChancePercent: Double,
)

internal data class AtlasTimelinePlacement(
    val instrumentGuid: String,
    val startTime: Double,
    val length: Double,
    val timeLocked: Boolean,
)

internal data class AtlasSchedulingGroup(
    val id: String,
    val composition: AtlasSchedulingComposition,
    val selectionKind: String,
    val playMode: String?,
    val playModeValue: Int?,
    val selectionMode: String?,
    val selectionModeValue: Int?,
    val groupTriggerChancePercent: Double,
    val members: List<AtlasSchedulingMember>,
    val timelinePlacements: List<AtlasTimelinePlacement>,
    val maximumSourceCornerContributorsPerLogicalRing: Int,
    val maximumFmodSourceChannelsPerLogicalRing: Int,
    val maximumCaptureFramesPerLogicalRing: Int,
    val streamingRingBufferFrames: Int,
    val complete: Boolean,
)

internal enum class AtlasEffectLifetime(val wireName: String) {
    CONTINUOUS("continuous"),
    ONE_SHOT("oneShot"),
    FINITE_REPEAT("finiteRepeat"),
    ;

    companion object {
        fun parse(value: String): AtlasEffectLifetime =
            requireNotNull(entries.firstOrNull { it.wireName == value }) {
                "Unsupported atlas effect lifetime $value"
            }
    }
}

internal enum class AtlasRuntimeTrigger {
    PARAMETER_PLACEMENT_ENTRY,
    ENGINE_EVENT_START,
    THROTTLE_LIFT,
    SHIFT_UP,
    SHIFT_DOWN,
    SHIFT_REJECTED,
    TRANSMISSION_LOOP,
    TRANSMISSION_PULSE,
    TURBO_LOOP,
    TURBO_DUMP,
    LIMITER_LOOP,
    LIMITER_PULSE,
    TRACTION_LIMIT,
    TRACTION_PULSE,
    ENGINE_START,
}

internal enum class AtlasHostGainClass(val wireName: String) {
    ENGINE_EVENT("engineEvent"),
    EFFECT_EVENT("effectEvent");

    companion object {
        fun parse(value: String): AtlasHostGainClass = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported atlas host gain class $value")
    }
}

internal sealed interface AtlasHostParameterValue {
    data class Source(val name: String) : AtlasHostParameterValue
    data class Constant(val value: Double) : AtlasHostParameterValue
}

internal data class AtlasHostParameterBinding(
    val parameter: String,
    val value: AtlasHostParameterValue,
)

internal data class AtlasParameterPlacementSpan(
    val start: Double,
    val end: Double,
    val includeEnd: Boolean,
    val parameterGuid: String,
    val layoutGuid: String,
    val instrumentGuid: String,
) {
    fun contains(value: Double): Boolean =
        value >= start && (value < end || (includeEnd && value == end))
}

internal data class AtlasParameterPlacementAxis(
    val parameter: String,
    val parameterGuid: String,
    val layoutGuid: String,
    val value: AtlasHostParameterValue,
    val spans: Array<AtlasParameterPlacementSpan>,
)

internal data class AtlasParameterPlacementEntry(
    val axes: Array<AtlasParameterPlacementAxis>,
) {
    /** FMOD tests prove an AND across every parameter and every instrument-chain placement. */
    fun contains(valueFor: (String) -> Double): Boolean {
        var axisIndex = 0
        while (axisIndex < axes.size) {
            val axis = axes[axisIndex]
            val value = valueFor(axis.parameter)
            var spanIndex = 0
            while (spanIndex < axis.spans.size) {
                if (!axis.spans[spanIndex].contains(value)) return false
                spanIndex += 1
            }
            axisIndex += 1
        }

        return true
    }
}

internal data class AtlasEffectRuntimeVariant(
    val authoredBindingKey: String,
    val sourceGuid: String,
    val lifetime: AtlasEffectLifetime,
    val parameters: Map<String, Double>,
    val parameterAxes: Map<String, DoubleArray>,
    val runtimeTriggers: Set<AtlasRuntimeTrigger>,
    val perspectives: Set<EngineSoundPerspective>,
    val hostParameterBindings: List<AtlasHostParameterBinding>,
    val parameterPlacementEntry: AtlasParameterPlacementEntry?,
    val schedulingGroup: AtlasSchedulingGroup,
    val hostGainClass: AtlasHostGainClass,
    val engineProgramRole: AtlasEngineProgramRole?,
    val eventInstanceOwnership: AtlasEventInstanceOwnership,
    val finiteLifecycleTopology: AtlasFiniteLifecycleTopology?,
)

internal enum class AtlasEngineProgramRole {
    LOAD,
    COAST,
    UNAFFECTED;

    companion object {
        fun parse(value: String): AtlasEngineProgramRole = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unsupported engine-program role $value")
    }
}

internal enum class AtlasEventInstanceOwner(val wireValue: String) {
    SELECTED_PERSPECTIVE_ENGINE("selectedPerspectiveEngineEventInstance"),
    PROFILE_AUDIO_SESSION_PERSISTENT("profileAudioSessionPersistentEventInstance");

    companion object {
        fun parse(value: String): AtlasEventInstanceOwner = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported FMOD event-instance owner $value")
    }
}

internal data class AtlasEventInstanceOwnership(
    val key: String,
    val owner: AtlasEventInstanceOwner,
    val created: String,
    val survives: String,
    val resets: String,
    val activationGeneration: String,
)

internal data class AtlasFiniteLifecycleTopology(
    val topology: String,
    val status: String,
) {
    val executable: Boolean get() = status in EXECUTABLE_FINITE_LIFECYCLE_STATUSES

    private companion object {
        val EXECUTABLE_FINITE_LIFECYCLE_STATUSES = setOf(
            "hostSemanticTrigger",
            "PASS_SOURCE_SOLO_PARAMETER_PLACEMENT_LIFECYCLE",
        )
    }
}

internal data class AtlasEffectEvent(
    val eventPath: String,
    val eventSuffix: String,
    val perspectives: Set<EngineSoundPerspective>,
    val runtimeTriggers: Set<AtlasRuntimeTrigger>,
    val runtimeMappingBlocked: Boolean,
    val runtimeContractComplete: Boolean,
    val variants: List<AtlasEffectRuntimeVariant>,
    val nodes: List<AtlasEffectNode>,
    val hostGainClasses: Set<AtlasHostGainClass>,
)

internal data class AtlasHostMixContract(
    val engineEventHostGainLinear: Double,
    val effectEventHostGainLinear: Double,
    val limiterCeilingLinear: Double,
    val limiterLookaheadFrames: Int,
    val limiterAttackFrames: Int,
    val limiterReleaseFrames: Int,
)

internal data class AtlasChannelArbitrationContract(
    val assettoStudioLogicalChannelCap: Int,
    val assettoSoftwareRealChannelBudget: Int,
    val requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget: Boolean,
    val status: String,
)

/** Proven after packing: this is a file-mapping bound, distinct from PCM node/voice bounds. */
internal data class AtlasPerspectiveResourceBounds(
    val engineMaximumMappedShardInstancesDuringCellTransition: Int,
    val effectMaximumMappedShardInstancesSafeUpperBound: Int,
    val effectMaximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails: Int,
    val effectMaximumContinuousMmapPlaybackCornerVoices: Int,
    val effectMaximumContinuousMappedSourceCorners: Int,
    val effectMaximumFiniteLogicalRingVoicesPerOneDspUpdate: Int,
    val effectMaximumMappedNodesPerUpdate: Int,
    val effectMaximumSourceCornerRegionsDuringMaterialization: Int,
    val effectMaximumFiniteSourceCornerContributorsPerUpdate: Int,
    val effectMaximumFiniteMappedSourceCornerRegionsDuringMaterialization: Int,
    val engineMaximumFmodLogicalSourceChannelsAtAtlasNode: Int,
    val effectMaximumFmodContinuousSourceChannels: Int,
    val effectMaximumFmodFiniteSourceChannelsPerOneDspUpdate: Int,
    val totalMaximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails: Int,
    val effectFiniteAttackCacheBytes: Long,
    val effectFiniteRingPoolBytes: Long?,
    val effectFiniteRingPoolStatus: String,
    val effectPeakProofStatus: String,
    val totalPeakProofStatus: String,
)

internal data class AtlasSelectedEngineSessionResourceBounds(
    val engineMaximumMappedShardInstancesDuringCellTransition: Int,
    val retainedCabinEffectsMaximumMappedShardInstances: Int,
    val retainedExteriorEffectsMaximumMappedShardInstances: Int,
    val maximumMappedShardInstancesDuringTransitionSafeUpperBound: Int,
)

/** Mapping-instance proof for the engine bed plus both session-retained effect participants. */
internal data class AtlasSessionResourceBounds(
    val perSelectedEnginePerspective: Map<EngineSoundPerspective, AtlasSelectedEngineSessionResourceBounds>,
    val maximumMappedShardInstancesDuringTransitionSafeUpperBound: Int,
    val proofStatus: String,
)

private data class ParsedAtlasResourceBounds(
    val perPerspective: Map<EngineSoundPerspective, AtlasPerspectiveResourceBounds>,
    val session: AtlasSessionResourceBounds,
)

internal data class FullEventAtlasProgram(
    val id: String,
    val draftBlocked: Boolean,
    val perspectives: Map<EngineSoundPerspective, AtlasPerspectiveProgram>,
    val maximumEffectPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails: Int,
    val maximumEffectMappedNodesPerUpdate: Int,
    val maximumEffectSourceCornerRegionsDuringMaterialization: Int,
    val effects: List<AtlasEffectEvent>,
    val shards: List<AtlasShard>,
    val interpolationContractValid: Boolean,
    val hostMixContract: AtlasHostMixContract,
    val channelArbitrationContract: AtlasChannelArbitrationContract,
    val resourceBounds: Map<EngineSoundPerspective, AtlasPerspectiveResourceBounds>,
    val sessionResourceBounds: AtlasSessionResourceBounds,
) {
    val requiredShardNames: Set<String> = shards.mapTo(linkedSetOf()) { it.name }

    init {
        require(id.isNotBlank()) { "Atlas id is empty" }
        require(perspectives.keys.containsAll(EngineSoundPerspective.entries)) {
            "Atlas must contain cabin and exterior perspectives"
        }
        require(maximumEffectPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails >= 0) {
            "Atlas effect voice bound is negative"
        }
        require(maximumEffectMappedNodesPerUpdate >= 0) { "Atlas effect node bound is negative" }
        require(maximumEffectSourceCornerRegionsDuringMaterialization >= 0) {
            "Atlas effect source-region bound is negative"
        }
        require(maximumEffectPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails ==
            resourceBounds.values.maxOf(AtlasPerspectiveResourceBounds::effectMaximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails)) {
            "Atlas global effect voice bound does not equal its selected-perspective bound"
        }
        require(maximumEffectMappedNodesPerUpdate ==
            resourceBounds.values.maxOf(AtlasPerspectiveResourceBounds::effectMaximumMappedNodesPerUpdate)) {
            "Atlas global mapped-effect-node bound does not equal its selected-perspective bound"
        }
        require(maximumEffectSourceCornerRegionsDuringMaterialization ==
            resourceBounds.values.maxOf(AtlasPerspectiveResourceBounds::effectMaximumSourceCornerRegionsDuringMaterialization)) {
            "Atlas global source-region bound does not equal its selected-perspective bound"
        }
        require(resourceBounds.keys.containsAll(EngineSoundPerspective.entries)) {
            "Atlas resource bounds must contain cabin and exterior"
        }
        resourceBounds.forEach { (perspective, bounds) ->
            val continuousCorners = effects.sumOf { event ->
                event.variants.filter { variant ->
                    perspective in variant.perspectives && variant.lifetime == AtlasEffectLifetime.CONTINUOUS
                }.groupBy { variant -> variant.schedulingGroup.id }.values.sumOf { variants ->
                    if (variants.first().schedulingGroup.composition == AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE) {
                        variants.maxOf(::maximumAtlasEffectCorners)
                    } else {
                        variants.sumOf(::maximumAtlasEffectCorners)
                    }
                }
            }
            require(continuousCorners == bounds.effectMaximumContinuousMmapPlaybackCornerVoices.toLong())
            require(continuousCorners == bounds.effectMaximumContinuousMappedSourceCorners.toLong())
            val finiteAttackCacheBytes = effects.sumOf { event ->
                val visibleBindings = event.variants.asSequence()
                    .filter { perspective in it.perspectives }
                    .mapTo(hashSetOf(), AtlasEffectRuntimeVariant::authoredBindingKey)
                event.nodes.asSequence()
                    .filter {
                        it.requiredAuthoredBindingKey in visibleBindings &&
                            it.lifetime != AtlasEffectLifetime.CONTINUOUS
                    }
                    .sumOf { node -> minOf(node.endFrameExclusive - node.startFrame, 4_096L) * 4L }
            }
            require(finiteAttackCacheBytes == bounds.effectFiniteAttackCacheBytes) {
                "Atlas $perspective finite attack cache byte proof disagrees with its nodes"
            }
            require(bounds.totalMaximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails ==
                bounds.engineMaximumFmodLogicalSourceChannelsAtAtlasNode +
                bounds.effectMaximumFmodContinuousSourceChannels +
                bounds.effectMaximumFmodFiniteSourceChannelsPerOneDspUpdate) {
                "Atlas $perspective raw FMOD channel accounting does not reconcile"
            }
        }
        require(sessionResourceBounds.perSelectedEnginePerspective.keys ==
            EngineSoundPerspective.entries.toSet()) {
            "Atlas session mmap proof must contain both selected engine perspectives"
        }
        val cabinEffectsBound = resourceBounds(EngineSoundPerspective.CABIN)
            .effectMaximumMappedShardInstancesSafeUpperBound
        val exteriorEffectsBound = resourceBounds(EngineSoundPerspective.EXTERIOR)
            .effectMaximumMappedShardInstancesSafeUpperBound
        sessionResourceBounds.perSelectedEnginePerspective.forEach { (perspective, bounds) ->
            require(bounds.engineMaximumMappedShardInstancesDuringCellTransition ==
                resourceBounds(perspective).engineMaximumMappedShardInstancesDuringCellTransition &&
                bounds.retainedCabinEffectsMaximumMappedShardInstances == cabinEffectsBound &&
                bounds.retainedExteriorEffectsMaximumMappedShardInstances == exteriorEffectsBound &&
                bounds.maximumMappedShardInstancesDuringTransitionSafeUpperBound ==
                bounds.engineMaximumMappedShardInstancesDuringCellTransition + cabinEffectsBound +
                exteriorEffectsBound) {
                "Atlas $perspective session mmap proof does not reconcile its retained mapping instances"
            }
        }
        require(sessionResourceBounds.maximumMappedShardInstancesDuringTransitionSafeUpperBound ==
            sessionResourceBounds.perSelectedEnginePerspective.values.maxOf {
                it.maximumMappedShardInstancesDuringTransitionSafeUpperBound
            }) {
            "Atlas global session mmap bound is not the maximum selected-engine transition"
        }
        require(shards.isNotEmpty() && shards.map { it.name }.distinct().size == shards.size) {
            "Atlas shard list is empty or duplicated"
        }
        shards.forEach { shard ->
            require(isSafeAtlasShardName(shard.name)) { "Unsafe atlas shard name" }
            require(BydAudioPackManifest.isSha256(shard.sha256)) { "Invalid atlas shard hash" }
            require(shard.bytes in 45..MAXIMUM_SHARD_BYTES) { "Atlas shard size is invalid" }
        }
        val referenced = perspectives.values.flatMap { program -> program.nodes.map { it.shardName } } +
            effects.flatMap { event -> event.nodes.map { it.shardName } }
        require(referenced.all(requiredShardNames::contains)) { "Atlas node references an undeclared shard" }
    }

    fun perspective(perspective: EngineSoundPerspective): AtlasPerspectiveProgram =
        requireNotNull(perspectives[perspective])

    fun resourceBounds(perspective: EngineSoundPerspective): AtlasPerspectiveResourceBounds =
        requireNotNull(resourceBounds[perspective])

    fun finiteRingPoolVoiceCapacity(perspective: EngineSoundPerspective): Int {
        val bounds = resourceBounds(perspective)
        val finiteGroups = effects.asSequence()
            .flatMap { it.variants.asSequence() }
            .filter { perspective in it.perspectives && it.lifetime != AtlasEffectLifetime.CONTINUOUS }
            .map(AtlasEffectRuntimeVariant::schedulingGroup)
            .distinctBy(AtlasSchedulingGroup::id)
            .toList()
        if (finiteGroups.isEmpty()) {
            require(bounds.effectFiniteRingPoolBytes == 0L)
            return 0
        }
        val streamingFrameCounts = finiteGroups.mapTo(linkedSetOf()) {
            it.streamingRingBufferFrames
        }
        require(streamingFrameCounts == setOf(FINITE_STREAMING_RING_BUFFER_FRAMES)) {
            "Atlas $id has an unsupported heterogeneous finite streaming-ring contract"
        }
        val bytes = requireNotNull(bounds.effectFiniteRingPoolBytes) {
            "Atlas $id has no proven finite ring-pool capacity"
        }
        require(bytes % FINITE_RING_BYTES_PER_VOICE == 0L) {
            "Atlas $id finite ring-pool bytes do not contain whole Float32 stereo rings"
        }
        val voiceCapacity = bytes / FINITE_RING_BYTES_PER_VOICE
        require(voiceCapacity in bounds.effectMaximumFiniteLogicalRingVoicesPerOneDspUpdate.toLong()..
            channelArbitrationContract.assettoStudioLogicalChannelCap.toLong()) {
            "Atlas $id finite ring-pool capacity is outside its proven logical-voice bounds"
        }

        return voiceCapacity.toInt()
    }

    /** No blocked core event is allowed to disappear behind otherwise playable engine audio. */
    fun requirePlaybackReady() {
        require(!draftBlocked) { "Atlas $id is blocked by its NRT/oracle release gate" }
        require(interpolationContractValid) { "Atlas $id has no proven interpolation/oracle contract" }
        require(channelArbitrationContract.status == "PASS" &&
            channelArbitrationContract.requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget) {
            "Atlas $id has no proven Assetto channel-admission parity"
        }
        require(sessionResourceBounds.proofStatus == "PASS") {
            "Atlas $id has no proven profile-session mmap mapping-instance bound"
        }
        resourceBounds.forEach { (perspective, bounds) ->
            require(bounds.effectFiniteRingPoolBytes != null &&
                bounds.effectFiniteRingPoolStatus == "PASS" &&
                bounds.effectPeakProofStatus == "PASS" &&
                bounds.totalPeakProofStatus == "PASS") {
                "Atlas $id has no proven causal finite-tail ring/channel bound"
            }
            finiteRingPoolVoiceCapacity(perspective)
        }
        effects.forEach { event ->
            require(!event.runtimeMappingBlocked) {
                "Core event ${event.eventSuffix} has no proven Android runtime mapping"
            }
            require(event.runtimeContractComplete && event.variants.isNotEmpty()) {
                "Core event ${event.eventSuffix} has no proven lifecycle/variant mapping"
            }
            require(event.variants.all { variant -> variant.finiteLifecycleTopology?.executable != false }) {
                "Core event ${event.eventSuffix} still has a blocked per-binding FMOD lifecycle"
            }
            require(event.variants.mapTo(linkedSetOf()) { it.eventInstanceOwnership }.size == 1) {
                "Core event ${event.eventSuffix} variants disagree on EventInstance ownership"
            }
        }
    }

    companion object {
        const val SCHEMA = "byd-full-event-atlas-runtime-v3"
        const val FINITE_STREAMING_RING_BUFFER_FRAMES = 12_288
        const val FINITE_RING_BYTES_PER_VOICE = FINITE_STREAMING_RING_BUFFER_FRAMES * 2L * Float.SIZE_BYTES
        /** Generator limits PCM payload to 256 MiB; RIFF headers add a few bytes. */
        const val MAXIMUM_SHARD_BYTES = 256L * 1024L * 1024L + 64L * 1024L
    }
}

internal object FullEventAtlasParser {
    private data class ParsedEffectRuntimeMapping(
        val lifetime: AtlasEffectLifetime,
        val parameters: Map<String, Double>,
        val parameterAxes: Map<String, DoubleArray>,
        val runtimeTriggers: Set<AtlasRuntimeTrigger>,
        val perspectives: Set<EngineSoundPerspective>,
        val hostParameterBindings: List<AtlasHostParameterBinding>,
        val parameterPlacementEntry: AtlasParameterPlacementEntry?,
        val hostGainClass: AtlasHostGainClass,
        val engineProgramRole: AtlasEngineProgramRole?,
        val eventInstanceOwnership: AtlasEventInstanceOwnership,
        val finiteLifecycleTopology: AtlasFiniteLifecycleTopology?,
    )

    private data class AuthoredParameterDefault(
        val parameter: String,
        val parameterGuid: String,
        val defaultValue: Double,
    )

    private data class PlacementParameterValue(
        val parameterGuid: String,
        val layoutGuid: String,
        val value: AtlasHostParameterValue,
    )

    private data class ParsedEffectRuntimeBinding(
        val runtimeMappingRef: String,
        val schedulingGroupRef: String,
        val variant: AtlasEffectRuntimeVariant,
    )

    private const val EFFECT_NODE_BINDING_INDEX = 0
    private const val EFFECT_NODE_PARAMETERS_INDEX = 1
    private const val EFFECT_NODE_SHARD_INDEX = 2
    private const val EFFECT_NODE_START_INDEX = 3
    private const val EFFECT_NODE_END_INDEX = 4
    private const val EFFECT_NODE_LOOP_START_INDEX = 5
    private const val EFFECT_NODE_LOOP_END_INDEX = 6
    private const val EFFECT_NODE_FIELD_COUNT = 7

    private val SUPPORTED_EFFECT_EVENT_SUFFIXES = setOf(
        "backfire_int",
        "backfire_ext",
        "engine_int",
        "engine_ext",
        "gear_int",
        "gear_ext",
        "gear_grind",
        "limiter",
        "start",
        "tractioncontrol_int",
        "tractioncontrol_ext",
        "transmission",
        "transmission_ext",
        "turbo",
    )
    private val SUPPORTED_PLAYLIST_MODES = setOf(
        "PlaylistPlayMode_SmartRandom",
        "PlaylistPlayMode_PlaySequential",
    )
    private val SUPPORTED_HOST_PARAMETER_SOURCES = setOf(
        "EngineSimulation.rpm",
        "EngineSimulation.throttle",
        "EngineSimulation.drivetrainSpeed",
        "EngineSimulation.gearState",
        "EngineSimulation.turboBoost",
        "EngineSimulation.turboBov",
        "EngineSimulation.turboBovDecay",
        "EngineSimulation.limiterDecay",
        "EngineSimulation.tractionDecay",
    )
    private val SEMANTIC_LIFECYCLE_CONTRACTS = mapOf(
        AtlasRuntimeTrigger.TRANSMISSION_LOOP to continuousLifecycle(
            signal = "EngineSimulation.transmissionActive",
        ),
        AtlasRuntimeTrigger.TRANSMISSION_PULSE to pulseLifecycle(
            signal = "EngineSimulation.transmissionPulseSequence",
            parameterSample = "atSequenceEdgeUseCurrentAuthoredParameterBindings",
        ),
        AtlasRuntimeTrigger.ENGINE_EVENT_START to mapOf(
            "signal" to "EngineSimulation.engineEventInstanceGeneration",
            "start" to "onceForEveryNewSelectedPerspectiveEngineEventInstance",
            "parameterSample" to "atInstanceCreationUseCurrentAuthoredParameterBindings",
            "stop" to "capturedOneShotOrFiniteRepeatEnd",
            "retrigger" to "onlyAfterEngineEventInstanceGenerationChanges",
        ),
        AtlasRuntimeTrigger.ENGINE_START to mapOf(
            "signal" to "EngineSimulation.ignitionCycleGeneration",
            "start" to "onceForEveryStrictlyIncreasingIgnitionCycleGeneration",
            "parameterSample" to "atIgnitionEdgeUseCurrentAuthoredParameterBindings",
            "stop" to "capturedOneShotOrFiniteRepeatEnd",
            "retrigger" to "onlyAfterEngineStoppedThenNextIgnitionCycleGeneration",
        ),
        AtlasRuntimeTrigger.SHIFT_UP to pulseLifecycle("EngineSimulation.successfulUpShiftSequence"),
        AtlasRuntimeTrigger.SHIFT_DOWN to pulseLifecycle("EngineSimulation.successfulDownShiftSequence"),
        AtlasRuntimeTrigger.SHIFT_REJECTED to pulseLifecycle("EngineSimulation.rejectedShiftSequence"),
        AtlasRuntimeTrigger.THROTTLE_LIFT to pulseLifecycle("EngineSimulation.throttleLiftSequence"),
        AtlasRuntimeTrigger.TURBO_LOOP to continuousLifecycle("EngineSimulation.turboActive"),
        AtlasRuntimeTrigger.TURBO_DUMP to pulseLifecycle("EngineSimulation.turboDumpSequence"),
        AtlasRuntimeTrigger.LIMITER_LOOP to continuousLifecycle("EngineSimulation.limiterActive"),
        AtlasRuntimeTrigger.LIMITER_PULSE to pulseLifecycle("EngineSimulation.limiterPulseSequence"),
        AtlasRuntimeTrigger.TRACTION_LIMIT to continuousLifecycle("EngineSimulation.tractionLimitActive"),
        AtlasRuntimeTrigger.TRACTION_PULSE to pulseLifecycle("EngineSimulation.tractionLimitPulseSequence"),
    )

    private fun continuousLifecycle(signal: String): Map<String, String> = mapOf(
        "signal" to signal,
        "start" to "falseToTrue",
        "update" to "whileTrueUseCurrentAuthoredParameterBindings",
        "stop" to "trueToFalse",
        "retrigger" to "noneWhileActive",
    )

    private fun pulseLifecycle(signal: String, parameterSample: String? = null): Map<String, String> = buildMap {
        put("signal", signal)
        put("start", "onceForEveryStrictlyIncreasingSequenceValue")
        if (parameterSample != null) put("parameterSample", parameterSample)
        put("stop", "capturedOneShotOrFiniteRepeatEnd")
        put("retrigger", "everyNewSequenceValueSubjectToSchedulingGroupPolyphony")
    }

    private fun parseAndValidateEffectNodeEncoding(value: AtlasJsonValue) {
        val label = "atlas.effects.runtimeContract.nodeEncoding"
        val encoding = value.objectValues(label)
        require(encoding.getValue("schema").stringValue("$label.schema") ==
            "byd-full-event-effect-node-array-v1")
        require(encoding.getValue("fields").arrayValues("$label.fields")
            .mapIndexed { index, item -> item.stringValue("$label.fields[$index]") } == listOf(
            "variantBindingRef",
            "parameters",
            "shardName",
            "startFrame",
            "endFrameExclusive",
            "loopStartFrame",
            "loopEndFrameExclusive",
        ))
        require(encoding.getValue("sourceIdentity").stringValue("$label.sourceIdentity") ==
            "nodes[][0] resolves to variantBindings[].authoredBindingKeyAndSourceGuid")
        require(encoding.getValue("finiteDurationFrames").stringValue("$label.finiteDurationFrames") ==
            "nodes[][4]-nodes[][3]")
    }

    fun parse(value: AtlasJsonValue): FullEventAtlasProgram {
        val root = value.objectValues("atlas")
        require(root.getValue("schema").stringValue("atlas.schema") == FullEventAtlasProgram.SCHEMA)
        parseAndValidateModeRows(root.getValue("modeRows"))
        parseAndValidateHotCellPolicy(root.getValue("hotCellPolicy"))
        val perspectives = root.getValue("perspectives").objectValues("atlas.perspectives")
        val effects = root.getValue("effects").objectValues("atlas.effects")
        val effectRuntime = effects.getValue("runtimeContract").objectValues("atlas.effects.runtimeContract")
        require(effectRuntime.getValue("schema").stringValue("atlas.effects.runtimeContract.schema") ==
            "byd-full-event-effect-runtime-v5")
        require(effectRuntime.getValue("variantBindingIdentity")
            .stringValue("atlas.effects.runtimeContract.variantBindingIdentity") ==
            "familyLocalVnRefPlusExactAuthoredBindingKeyAndSourceGuid")
        require(effectRuntime.getValue("schedulingGroupIdentity")
            .stringValue("atlas.effects.runtimeContract.schedulingGroupIdentity") ==
            "familyLocalGnRefPlusExactAuthoredGroupId")
        require(effectRuntime.getValue("runtimeMappingProfileIdentity")
            .stringValue("atlas.effects.runtimeContract.runtimeMappingProfileIdentity") ==
            "familyLocalMnRefPlusCanonicalExecutableMapping")
        require(effectRuntime.getValue("nodeBinding")
            .stringValue("atlas.effects.runtimeContract.nodeBinding") ==
            "nodes[][0] is variantBindingRef resolving to authoredBindingKey")
        parseAndValidateEffectNodeEncoding(effectRuntime.getValue("nodeEncoding"))
        require(effectRuntime.getValue("selectionRuntimeContractTable")
            .stringValue("atlas.effects.runtimeContract.selectionRuntimeContractTable") ==
            "selectionRuntimeContracts[].id")
        parseAndValidateEffectExecutionContract(effectRuntime.getValue("execution"))
        val selectionRuntimeContracts = parseSelectionRuntimeContracts(
            effects.getValue("selectionRuntimeContracts"),
        )
        val schedulingGroups = parseEffectSchedulingGroups(
            effects.getValue("schedulingGroups"),
            selectionRuntimeContracts,
        )
        val runtimeMappingProfiles = parseEffectRuntimeMappingProfiles(
            effects.getValue("runtimeMappingProfiles"),
        )
        val variantBindings = parseEffectVariantBindings(
            effects.getValue("variantBindings"),
            runtimeMappingProfiles,
            schedulingGroups,
        )
        val eventValues = effects.getValue("events").arrayValues("atlas.effects.events")
        val referencedBindingIds = eventValues.flatMapIndexed { index, event ->
            event.objectValues("atlas.effects.events[$index]").getValue("variantBindingRefs")
                .arrayValues("atlas.effects.events[$index].variantBindingRefs")
                .mapIndexed { referenceIndex, reference ->
                    reference.stringValue("atlas.effects.events[$index].variantBindingRefs[$referenceIndex]")
                }
        }
        require(referencedBindingIds.distinct().size == referencedBindingIds.size &&
            referencedBindingIds.toSet() == variantBindings.keys) {
            "atlas.effects events must partition every variant binding exactly once"
        }
        val referencedGroupIds = eventValues.flatMapIndexed { index, event ->
            event.objectValues("atlas.effects.events[$index]").getValue("schedulingGroupRefs")
                .arrayValues("atlas.effects.events[$index].schedulingGroupRefs")
                .mapIndexed { referenceIndex, reference ->
                    reference.stringValue("atlas.effects.events[$index].schedulingGroupRefs[$referenceIndex]")
                }
        }
        require(referencedGroupIds.distinct().size == referencedGroupIds.size &&
            referencedGroupIds.toSet() == schedulingGroups.keys) {
            "atlas.effects events must partition every scheduling group exactly once"
        }
        val parsedEvents = eventValues.mapIndexed { index, event ->
            parseEffect(index, event, variantBindings, schedulingGroups)
        }
        require(variantBindings.values.mapTo(linkedSetOf()) { it.variant } ==
            parsedEvents.flatMapTo(linkedSetOf(), AtlasEffectEvent::variants)) {
            "atlas.effects.variantBindings contains an orphan or missing binding"
        }
        require(schedulingGroups.values.toSet() == parsedEvents.flatMapTo(linkedSetOf()) { event ->
            event.variants.map(AtlasEffectRuntimeVariant::schedulingGroup)
        }) { "atlas.effects.schedulingGroups contains an orphan or missing group" }
        require(runtimeMappingProfiles.keys == variantBindings.values.mapTo(linkedSetOf()) { it.runtimeMappingRef }) {
            "atlas.effects.runtimeMappingProfiles contains an orphan or missing profile"
        }
        require(effects.getValue("resourceModel").stringValue("atlas.effects.resourceModel") ==
            "profileSessionRetainedEffectsResourceBounds-v3")
        val parsedResourceBounds = parseResourceBounds(root.getValue("resourceBounds"))

        return FullEventAtlasProgram(
            id = root.getValue("id").stringValue("atlas.id"),
            draftBlocked = root.getValue("draftBlocked").booleanValue("atlas.draftBlocked"),
            perspectives = mapOf(
                EngineSoundPerspective.CABIN to parsePerspective(
                    perspectives.getValue("cabin"),
                    "atlas.perspectives.cabin",
                ),
                EngineSoundPerspective.EXTERIOR to parsePerspective(
                    perspectives.getValue("exterior"),
                    "atlas.perspectives.exterior",
                ),
            ),
            maximumEffectPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails = effects
                .getValue("maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails")
                .intValue("atlas.effects.maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails"),
            maximumEffectMappedNodesPerUpdate = effects.getValue("maximumMappedNodesPerUpdate")
                .intValue("atlas.effects.maximumMappedNodesPerUpdate"),
            maximumEffectSourceCornerRegionsDuringMaterialization = effects
                .getValue("maximumSourceCornerRegionsDuringMaterialization")
                .intValue("atlas.effects.maximumSourceCornerRegionsDuringMaterialization"),
            effects = parsedEvents,
            shards = root.getValue("shards").arrayValues("atlas.shards").mapIndexed(::parseShard),
            interpolationContractValid = parseInterpolationContract(root["interpolationContract"]),
            hostMixContract = parseHostMixContract(root.getValue("hostMixContract")),
            channelArbitrationContract = parseChannelArbitrationContract(
                effects.getValue("channelArbitration"),
            ),
            resourceBounds = parsedResourceBounds.perPerspective,
            sessionResourceBounds = parsedResourceBounds.session,
        )
    }

    private fun parseResourceBounds(value: AtlasJsonValue): ParsedAtlasResourceBounds {
        val root = value.objectValues("atlas.resourceBounds")
        require(root.keys == setOf("schema", "scope", "perPerspective", "session"))
        require(root.getValue("schema").stringValue("atlas.resourceBounds.schema") ==
            "byd-full-event-atlas-runtime-resource-bounds-v3")
        require(root.getValue("scope").stringValue("atlas.resourceBounds.scope") ==
            "selectedEnginePerspectivePlusSessionRetainedCabinAndExteriorEffects")
        val perspectives = root.getValue("perPerspective").objectValues("atlas.resourceBounds.perPerspective")
        require(perspectives.keys == setOf("cabin", "exterior"))
        fun parsePerspective(name: String): AtlasPerspectiveResourceBounds {
            val values = perspectives.getValue(name).objectValues("atlas.resourceBounds.perPerspective.$name")
            val engine = values.getValue("engine").objectValues("atlas.resourceBounds.perPerspective.$name.engine")
            val effects = values.getValue("effects").objectValues("atlas.resourceBounds.perPerspective.$name.effects")
            val total = values.getValue("total").objectValues("atlas.resourceBounds.perPerspective.$name.total")
            require("maximumUniqueMappedShardsDuringCellTransition" !in engine &&
                "maximumUniqueMappedShardsSafeUpperBound" !in effects &&
                "maximumUniqueMappedShardsDuringTransitionSafeUpperBound" !in total) {
                "atlas.resourceBounds.perPerspective.$name retains obsolete filename-unique mmap accounting"
            }
            val engineBound = engine.getValue("maximumMappedShardInstancesDuringCellTransition")
                .intValue("atlas.resourceBounds.perPerspective.$name.engine.maximumMappedShardInstancesDuringCellTransition")
            val engineFmodChannels = engine.getValue("maximumFmodLogicalSourceChannelsAtAtlasNode")
                .intValue("atlas.resourceBounds.perPerspective.$name.engine.maximumFmodLogicalSourceChannelsAtAtlasNode")
            require(engine.getValue("androidPremixedBedIsNotFmodChannelAccounting")
                .booleanValue("atlas.resourceBounds.perPerspective.$name.engine.androidPremixedBedIsNotFmodChannelAccounting"))
            val effectBound = effects.getValue("maximumMappedShardInstancesSafeUpperBound")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumMappedShardInstancesSafeUpperBound")
            val playbackVoices = effects.getValue("maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumPlaybackVoicesPerOneDspUpdateExcludingPriorFiniteTails")
            val continuousVoices = effects.getValue("maximumContinuousMmapPlaybackCornerVoices")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumContinuousMmapPlaybackCornerVoices")
            val continuousMappedCorners = effects.getValue("maximumContinuousMappedSourceCorners")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumContinuousMappedSourceCorners")
            val finiteVoices = effects.getValue("maximumFiniteLogicalRingVoicesPerOneDspUpdate")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumFiniteLogicalRingVoicesPerOneDspUpdate")
            val mappedNodes = effects.getValue("maximumMappedNodesPerUpdate")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumMappedNodesPerUpdate")
            val sourceRegions = effects.getValue("maximumSourceCornerRegionsDuringMaterialization")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumSourceCornerRegionsDuringMaterialization")
            val finiteContributors = effects.getValue("maximumFiniteSourceCornerContributorsPerUpdate")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumFiniteSourceCornerContributorsPerUpdate")
            val finiteMappedRegions = effects.getValue("maximumFiniteMappedSourceCornerRegionsDuringMaterialization")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumFiniteMappedSourceCornerRegionsDuringMaterialization")
            val effectFmodContinuousChannels = effects.getValue("maximumFmodContinuousSourceChannels")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumFmodContinuousSourceChannels")
            val effectFmodFiniteChannels = effects.getValue("maximumFmodFiniteSourceChannelsPerOneDspUpdate")
                .intValue("atlas.resourceBounds.perPerspective.$name.effects.maximumFmodFiniteSourceChannelsPerOneDspUpdate")
            val finiteAttackCacheBytes = effects.getValue("finiteAttackCacheBytes")
                .longValue("atlas.resourceBounds.perPerspective.$name.effects.finiteAttackCacheBytes")
            require(effects.getValue("finiteAttackCacheMeaning")
                .stringValue("atlas.resourceBounds.perPerspective.$name.effects.finiteAttackCacheMeaning") ==
                "sum(min(nodeFrames,4096)*stereoPcm16BytesPerFrame)ForEveryFiniteNodePrearmedInSelectedPerspective")
            val finiteRingPoolBytes = effects.getValue("finiteRingPoolBytes")
                .nullableLongValue("atlas.resourceBounds.perPerspective.$name.effects.finiteRingPoolBytes")
            val finiteRingPoolStatus = effects.getValue("finiteRingPoolStatus")
                .stringValue("atlas.resourceBounds.perPerspective.$name.effects.finiteRingPoolStatus")
            require(effects.getValue("finiteRingPoolFormula")
                .stringValue("atlas.resourceBounds.perPerspective.$name.effects.finiteRingPoolFormula") ==
                "sum(physicalLiveLogicalRingInstancesBySchedulingGroup[groupId]*streamingRingBufferFrames[groupId]*8)")
            val effectPeakProofStatus = effects.getValue("peakProofStatus")
                .stringValue("atlas.resourceBounds.perPerspective.$name.effects.peakProofStatus")
            val totalFmodChannels = total.getValue(
                "maximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails",
            ).intValue(
                "atlas.resourceBounds.perPerspective.$name.total.maximumFmodLogicalSourceChannelsPerOneDspUpdateExcludingPriorFiniteTails",
            )
            require(total.getValue("fmodRawSourceAccounting")
                .stringValue("atlas.resourceBounds.perPerspective.$name.total.fmodRawSourceAccounting") ==
                "engineContinuousSourcesPlusEffectContinuousSourcesPlusNewFiniteSources; priorFiniteTailsRequireGlobalArbitrationOracle")
            val totalPeakProofStatus = total.getValue("peakProofStatus")
                .stringValue("atlas.resourceBounds.perPerspective.$name.total.peakProofStatus")
            require(engineBound > 0 && engineFmodChannels > 0 && effectBound >= 0 && playbackVoices >= 0 &&
                continuousVoices >= 0 && continuousMappedCorners >= 0 && finiteVoices >= 0 &&
                mappedNodes >= 0 && sourceRegions >= 0 &&
                finiteContributors >= 0 && finiteMappedRegions == finiteContributors &&
                effectFmodContinuousChannels >= 0 && effectFmodFiniteChannels >= 0 &&
                finiteAttackCacheBytes >= 0L && (finiteRingPoolBytes == null || finiteRingPoolBytes >= 0L) &&
                totalFmodChannels > 0 &&
                playbackVoices == continuousVoices + finiteVoices &&
                mappedNodes == continuousMappedCorners + finiteContributors &&
                sourceRegions == mappedNodes) {
                "atlas.resourceBounds.perPerspective.$name has an invalid exact mmap bound"
            }
            return AtlasPerspectiveResourceBounds(
                engineBound,
                effectBound,
                playbackVoices,
                continuousVoices,
                continuousMappedCorners,
                finiteVoices,
                mappedNodes,
                sourceRegions,
                finiteContributors,
                finiteMappedRegions,
                engineFmodChannels,
                effectFmodContinuousChannels,
                effectFmodFiniteChannels,
                totalFmodChannels,
                finiteAttackCacheBytes,
                finiteRingPoolBytes,
                finiteRingPoolStatus,
                effectPeakProofStatus,
                totalPeakProofStatus,
            )
        }
        val parsedPerspectives = mapOf(
            EngineSoundPerspective.CABIN to parsePerspective("cabin"),
            EngineSoundPerspective.EXTERIOR to parsePerspective("exterior"),
        )
        val sessionLabel = "atlas.resourceBounds.session"
        val session = root.getValue("session").objectValues(sessionLabel)
        require(session.keys == setOf(
            "mappingInstanceIdentity",
            "retainedEffectPerspectives",
            "perSelectedEnginePerspective",
            "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
            "proofStatus",
        ))
        require(session.getValue("mappingInstanceIdentity").stringValue("$sessionLabel.mappingInstanceIdentity") ==
            "activationPerspectivePlusShardName")
        require(session.getValue("retainedEffectPerspectives").arrayValues("$sessionLabel.retainedEffectPerspectives")
            .mapIndexed { index, item ->
                item.stringValue("$sessionLabel.retainedEffectPerspectives[$index]")
            } == listOf("cabin", "exterior"))
        val selected = session.getValue("perSelectedEnginePerspective")
            .objectValues("$sessionLabel.perSelectedEnginePerspective")
        require(selected.keys == setOf("cabin", "exterior"))
        fun parseSelectedPerspective(name: String): AtlasSelectedEngineSessionResourceBounds {
            val label = "$sessionLabel.perSelectedEnginePerspective.$name"
            val fields = selected.getValue(name).objectValues(label)
            require(fields.keys == setOf(
                "engineMaximumMappedShardInstancesDuringCellTransition",
                "retainedCabinEffectsMaximumMappedShardInstances",
                "retainedExteriorEffectsMaximumMappedShardInstances",
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
            ))

            return AtlasSelectedEngineSessionResourceBounds(
                engineMaximumMappedShardInstancesDuringCellTransition = fields.getValue(
                    "engineMaximumMappedShardInstancesDuringCellTransition",
                ).intValue("$label.engineMaximumMappedShardInstancesDuringCellTransition"),
                retainedCabinEffectsMaximumMappedShardInstances = fields.getValue(
                    "retainedCabinEffectsMaximumMappedShardInstances",
                ).intValue("$label.retainedCabinEffectsMaximumMappedShardInstances"),
                retainedExteriorEffectsMaximumMappedShardInstances = fields.getValue(
                    "retainedExteriorEffectsMaximumMappedShardInstances",
                ).intValue("$label.retainedExteriorEffectsMaximumMappedShardInstances"),
                maximumMappedShardInstancesDuringTransitionSafeUpperBound = fields.getValue(
                    "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
                ).intValue("$label.maximumMappedShardInstancesDuringTransitionSafeUpperBound"),
            )
        }
        val parsedSession = AtlasSessionResourceBounds(
            perSelectedEnginePerspective = mapOf(
                EngineSoundPerspective.CABIN to parseSelectedPerspective("cabin"),
                EngineSoundPerspective.EXTERIOR to parseSelectedPerspective("exterior"),
            ),
            maximumMappedShardInstancesDuringTransitionSafeUpperBound = session.getValue(
                "maximumMappedShardInstancesDuringTransitionSafeUpperBound",
            ).intValue("$sessionLabel.maximumMappedShardInstancesDuringTransitionSafeUpperBound"),
            proofStatus = session.getValue("proofStatus").stringValue("$sessionLabel.proofStatus"),
        )

        return ParsedAtlasResourceBounds(parsedPerspectives, parsedSession)
    }

    private fun parseChannelArbitrationContract(value: AtlasJsonValue): AtlasChannelArbitrationContract {
        val label = "atlas.effects.channelArbitration"
        val contract = value.objectValues(label)
        require(contract.getValue("schema").stringValue("$label.schema") ==
            "byd-full-event-fmod-channel-arbitration-oracle-v2")
        val logicalCap = contract.getValue("assettoStudioLogicalChannelCap")
            .intValue("$label.assettoStudioLogicalChannelCap")
        val realBudget = contract.getValue("assettoSoftwareRealChannelBudget")
            .intValue("$label.assettoSoftwareRealChannelBudget")
        require(logicalCap == 2_048 && realBudget == 256)
        val parity = contract.getValue("premixAdmissionParity").objectValues("$label.premixAdmissionParity")
        val requireUnderBudget = parity.getValue("requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget")
            .booleanValue("$label.premixAdmissionParity.requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget")
        require(parity.getValue("realBudget").intValue("$label.premixAdmissionParity.realBudget") == realBudget)
        require(parity.getValue("scenarioDemand").stringValue("$label.premixAdmissionParity.scenarioDemand") ==
            "continuousRawSourcesPlusEveryCausallyLiveFiniteTailSource")
        require(parity.getValue("onExceeded").stringValue("$label.premixAdmissionParity.onExceeded") ==
            "BLOCK_RELEASE_REQUIRE_SOURCE_STEMS_FOR_PER_SOURCE_PRIORITY_AUDIBILITY_AND_VIRTUALIZATION")
        require(!parity.getValue("scalarOnlyProofIsSufficient")
            .booleanValue("$label.premixAdmissionParity.scalarOnlyProofIsSufficient"))

        return AtlasChannelArbitrationContract(
            assettoStudioLogicalChannelCap = logicalCap,
            assettoSoftwareRealChannelBudget = realBudget,
            requireEverySupportedFamilyPerspectiveScenarioAtOrBelowRealBudget = requireUnderBudget,
            status = contract.getValue("status").stringValue("$label.status"),
        )
    }

    private fun parsePerspective(value: AtlasJsonValue, label: String): AtlasPerspectiveProgram {
        val values = value.objectValues(label)
        require(values.keys == setOf("rpmAxis", "throttleAxis", "nodes")) {
            "$label has unsupported runtime fields"
        }
        val rpmAxis = values.getValue("rpmAxis").arrayValues("$label.rpmAxis")
            .mapIndexed { index, item -> item.numberValue("$label.rpmAxis[$index]") }
            .toDoubleArray()
        val throttleAxis = values.getValue("throttleAxis").arrayValues("$label.throttleAxis")
            .mapIndexed { index, item -> item.numberValue("$label.throttleAxis[$index]") }
            .toDoubleArray()
        val nodes = values.getValue("nodes").arrayValues("$label.nodes").mapIndexed { index, item ->
            val nodeLabel = "$label.nodes[$index]"
            val node = item.objectValues(nodeLabel)
            require(node.keys == setOf(
                "rpm",
                "throttle",
                "shardName",
                "startFrame",
                "endFrameExclusive",
                "loopStartFrame",
                "loopEndFrameExclusive",
                "phaseOffsetFrames",
                "modePrograms",
            )) { "$nodeLabel has unsupported runtime fields" }
            val modePrograms = node.getValue("modePrograms").objectValues("$nodeLabel.modePrograms")
            require(modePrograms.keys == setOf("loadOnly", "coastOnly")) {
                "$nodeLabel must contain exact LOAD_ONLY and COAST_ONLY programs"
            }
            AtlasEngineNode(
                rpm = node.getValue("rpm").numberValue("$nodeLabel.rpm"),
                throttle = node.getValue("throttle").numberValue("$nodeLabel.throttle"),
                shardName = node.getValue("shardName").stringValue("$nodeLabel.shardName"),
                startFrame = node.getValue("startFrame").longValue("$nodeLabel.startFrame"),
                endFrameExclusive = node.getValue("endFrameExclusive")
                    .longValue("$nodeLabel.endFrameExclusive"),
                loopStartFrame = node.getValue("loopStartFrame").nullableLongValue("$nodeLabel.loopStartFrame"),
                loopEndFrameExclusive = node.getValue("loopEndFrameExclusive")
                    .nullableLongValue("$nodeLabel.loopEndFrameExclusive"),
                modePrograms = AtlasEngineModePrograms(
                    loadOnly = parseEnginePcmGeometry(
                        modePrograms.getValue("loadOnly"),
                        "$nodeLabel.modePrograms.loadOnly",
                    ),
                    coastOnly = parseEnginePcmGeometry(
                        modePrograms.getValue("coastOnly"),
                        "$nodeLabel.modePrograms.coastOnly",
                    ),
                ),
                phaseOffsetFrames = node.getValue("phaseOffsetFrames")
                    .numberValue("$nodeLabel.phaseOffsetFrames"),
            )
        }

        return AtlasPerspectiveProgram(rpmAxis, throttleAxis, nodes)
    }

    private fun parseEnginePcmGeometry(
        value: AtlasJsonValue,
        label: String,
    ): AtlasEnginePcmGeometry {
        val fields = value.objectValues(label)
        require(fields.keys == setOf(
            "shardName",
            "startFrame",
            "endFrameExclusive",
            "loopStartFrame",
            "loopEndFrameExclusive",
        )) { "$label has unsupported mode-program geometry fields" }

        return AtlasEnginePcmGeometry(
            shardName = fields.getValue("shardName").stringValue("$label.shardName"),
            startFrame = fields.getValue("startFrame").longValue("$label.startFrame"),
            endFrameExclusive = fields.getValue("endFrameExclusive").longValue("$label.endFrameExclusive"),
            loopStartFrame = fields.getValue("loopStartFrame").nullableLongValue("$label.loopStartFrame"),
            loopEndFrameExclusive = fields.getValue("loopEndFrameExclusive")
                .nullableLongValue("$label.loopEndFrameExclusive"),
        )
    }

    private fun parseEffect(
        index: Int,
        value: AtlasJsonValue,
        variantBindings: Map<String, ParsedEffectRuntimeBinding>,
        schedulingGroups: Map<String, AtlasSchedulingGroup>,
    ): AtlasEffectEvent {
        val label = "atlas.effects.events[$index]"
        val values = value.objectValues(label)
        val eventPath = values.getValue("eventPath").stringValue("$label.eventPath")
        val perspectives = parsePerspectives(values.getValue("perspectives"), "$label.perspectives")
        val triggers = parseTriggers(values.getValue("runtimeTriggers"), "$label.runtimeTriggers")
        return AtlasEffectEvent(
            eventPath = eventPath,
            eventSuffix = values.getValue("eventSuffix").stringValue("$label.eventSuffix"),
            perspectives = perspectives,
            runtimeTriggers = triggers,
            runtimeMappingBlocked = values.getValue("runtimeMappingBlocked")
                .booleanValue("$label.runtimeMappingBlocked"),
            runtimeContractComplete = true,
            variants = values.getValue("variantBindingRefs")
                .arrayValues("$label.variantBindingRefs")
                .mapIndexed { variantIndex, item ->
                    val reference = item.stringValue("$label.variantBindingRefs[$variantIndex]")
                    requireNotNull(variantBindings[reference]) {
                        "$label references an unknown variant binding"
                    }.variant
                },
            nodes = values.getValue("nodes").arrayValues("$label.nodes").mapIndexed { nodeIndex, item ->
                parseEffectNode(item, "$label.nodes[$nodeIndex]", variantBindings)
            },
            hostGainClasses = values.getValue("variantBindingRefs")
                .arrayValues("$label.variantBindingRefs")
                .mapTo(linkedSetOf()) { reference ->
                    requireNotNull(variantBindings[reference.stringValue("$label.variantBindingRefs[]")])
                        .variant.hostGainClass
                },
        ).also { event ->
            val groupRefs = values.getValue("schedulingGroupRefs")
                .arrayValues("$label.schedulingGroupRefs")
                .mapIndexed { groupIndex, item ->
                    val reference = item.stringValue("$label.schedulingGroupRefs[$groupIndex]")
                    requireNotNull(schedulingGroups[reference]) { "$label references an unknown scheduling group" }
                    reference
                }
            require(groupRefs.distinct().size == groupRefs.size)
            require(groupRefs.toSet() == values.getValue("variantBindingRefs")
                .arrayValues("$label.variantBindingRefs")
                .mapTo(linkedSetOf()) { bindingRef ->
                    requireNotNull(variantBindings[bindingRef.stringValue("$label.variantBindingRefs[]")])
                        .schedulingGroupRef
                }) {
                "$label scheduling group refs do not equal its variants"
            }
            validateEffectEvent(event, label)
        }
    }

    private fun parseSelectionRuntimeContracts(value: AtlasJsonValue): Map<String, AtlasJsonValue> {
        val contracts = value.arrayValues("atlas.effects.selectionRuntimeContracts").mapIndexed { index, item ->
            val label = "atlas.effects.selectionRuntimeContracts[$index]"
            val fields = item.objectValues(label)
            require(fields.keys == setOf("id", "contract")) { "$label has unsupported fields" }
            val id = fields.getValue("id").stringValue("$label.id")
            require(id == "s$index") { "$label has a non-canonical family-local id" }
            id to fields.getValue("contract")
        }
        require(contracts.isNotEmpty() && contracts.map { it.first }.distinct().size == contracts.size) {
            "atlas.effects.selectionRuntimeContracts is empty or duplicated"
        }

        return contracts.toMap()
    }

    private fun parseEffectSchedulingGroups(
        value: AtlasJsonValue,
        selectionRuntimeContracts: Map<String, AtlasJsonValue>,
    ): Map<String, AtlasSchedulingGroup> {
        val referencedSelectionContracts = linkedSetOf<String>()
        val bindings = value.arrayValues("atlas.effects.schedulingGroups").mapIndexed { index, item ->
            val label = "atlas.effects.schedulingGroups[$index]"
            val fields = item.objectValues(label)
            val id = fields.getValue("id").stringValue("$label.id")
            require(id == "g$index") { "$label has a non-canonical family-local id" }
            val selectionRuntimeContractRef = fields.getValue("selectionRuntimeContractRef")
                .stringValue("$label.selectionRuntimeContractRef")
            referencedSelectionContracts += selectionRuntimeContractRef
            val selectionRuntimeContract = requireNotNull(
                selectionRuntimeContracts[selectionRuntimeContractRef],
            ) { "$label references an unknown selection runtime contract" }
            val group = parseSchedulingGroup(item, label, selectionRuntimeContract)
            id to group
        }
        require(bindings.map { it.first }.distinct().size == bindings.size) {
            "atlas.effects.schedulingGroups has duplicate ids"
        }
        require(referencedSelectionContracts == selectionRuntimeContracts.keys) {
            "atlas.effects.selectionRuntimeContracts contains an orphan contract"
        }
        return bindings.toMap()
    }

    private fun parseEffectVariantBindings(
        value: AtlasJsonValue,
        runtimeMappingProfiles: Map<String, ParsedEffectRuntimeMapping>,
        schedulingGroups: Map<String, AtlasSchedulingGroup>,
    ): Map<String, ParsedEffectRuntimeBinding> {
        val bindings = value.arrayValues("atlas.effects.variantBindings").mapIndexed { index, item ->
            val label = "atlas.effects.variantBindings[$index]"
            val fields = item.objectValues(label)
            require(fields.keys == setOf(
                "id", "sourceGuid", "authoredBindingKey", "runtimeMappingRef", "schedulingGroupRef",
            )) { "$label has unsupported compact binding fields" }
            val sourceGuid = fields.getValue("sourceGuid").stringValue("$label.sourceGuid")
            val authoredBindingKey = fields.getValue("authoredBindingKey")
                .stringValue("$label.authoredBindingKey")
            require(authoredBindingKey.matches(Regex("^binding:[0-9a-f]{64}$"))) {
                "$label has an invalid authored binding key"
            }
            val id = fields.getValue("id").stringValue("$label.id")
            require(id == "v$index") { "$label has a non-canonical family-local id" }
            require(sourceGuid.isNotBlank()) { "$label has no source GUID" }
            val runtimeMappingRef = fields.getValue("runtimeMappingRef")
                .stringValue("$label.runtimeMappingRef")
            val schedulingGroupRef = fields.getValue("schedulingGroupRef")
                .stringValue("$label.schedulingGroupRef")
            val runtime = requireNotNull(runtimeMappingProfiles[runtimeMappingRef]) {
                "$label references an unknown runtime mapping profile"
            }
            val schedulingGroup = requireNotNull(schedulingGroups[schedulingGroupRef]) {
                "$label references an unknown scheduling group"
            }
            id to ParsedEffectRuntimeBinding(
                runtimeMappingRef = runtimeMappingRef,
                schedulingGroupRef = schedulingGroupRef,
                variant = AtlasEffectRuntimeVariant(
                    authoredBindingKey = authoredBindingKey,
                    sourceGuid = sourceGuid,
                    lifetime = runtime.lifetime,
                    parameters = runtime.parameters,
                    parameterAxes = runtime.parameterAxes,
                    runtimeTriggers = runtime.runtimeTriggers,
                    perspectives = runtime.perspectives,
                    hostParameterBindings = runtime.hostParameterBindings,
                    parameterPlacementEntry = runtime.parameterPlacementEntry,
                    schedulingGroup = schedulingGroup,
                    hostGainClass = runtime.hostGainClass,
                    engineProgramRole = runtime.engineProgramRole,
                    eventInstanceOwnership = runtime.eventInstanceOwnership,
                    finiteLifecycleTopology = runtime.finiteLifecycleTopology,
                ),
            )
        }
        require(bindings.map { it.first }.distinct().size == bindings.size) {
            "atlas.effects.variantBindings has duplicate ids"
        }
        return bindings.toMap()
    }

    private fun parseEffectRuntimeMappingProfiles(
        value: AtlasJsonValue,
    ): Map<String, ParsedEffectRuntimeMapping> {
        val profiles = value.arrayValues("atlas.effects.runtimeMappingProfiles").mapIndexed { index, item ->
            val label = "atlas.effects.runtimeMappingProfiles[$index]"
            val fields = item.objectValues(label)
            require(fields.keys == setOf("id", "runtimeMapping")) {
                "$label has unsupported compact mapping-profile fields"
            }
            val id = fields.getValue("id").stringValue("$label.id")
            require(id == "m$index") { "$label has a non-canonical family-local id" }
            id to parseEffectRuntimeMapping(fields.getValue("runtimeMapping"), "$label.runtimeMapping")
        }
        require(profiles.map { it.first }.distinct().size == profiles.size) {
            "atlas.effects.runtimeMappingProfiles has duplicate ids"
        }

        return profiles.toMap()
    }

    private fun parseEffectRuntimeMapping(
        value: AtlasJsonValue,
        label: String,
    ): ParsedEffectRuntimeMapping {
        val values = value.objectValues(label)
        val kind = values.getValue("kind").stringValue("$label.kind")
        require(kind in setOf("effect", "engineEventTransient"))
        val hostGainClass = AtlasHostGainClass.parse(
            values.getValue("hostGainClass").stringValue("$label.hostGainClass"),
        )
        require((kind == "engineEventTransient") == (hostGainClass == AtlasHostGainClass.ENGINE_EVENT)) {
            "$label kind and host gain class disagree"
        }
        val engineProgramRole = values.getValue("engineProgramRole").let { role ->
            if (role == AtlasJsonValue.NullValue) null else AtlasEngineProgramRole.parse(
                role.stringValue("$label.engineProgramRole"),
            )
        }
        require((hostGainClass == AtlasHostGainClass.ENGINE_EVENT) == (engineProgramRole != null)) {
            "$label must explicitly classify only engine-event contributors by program role"
        }
        val lifetime = AtlasEffectLifetime.parse(values.getValue("lifetime").stringValue("$label.lifetime"))
        val parameters = parseParameters(values.getValue("parameters"), "$label.parameters")
        val authoredDefaults = parseAuthoredParameterDefaults(
            values.getValue("authoredParameterDefaults"),
            "$label.authoredParameterDefaults",
        )
        require(parameters.all { (parameter, value) ->
            authoredDefaults.any { authored ->
                authored.parameter == parameter && authored.defaultValue == value
            }
        }) {
            "$label authored defaults do not cover its runtime parameters"
        }
        val axes = parseParameterAxes(values.getValue("parameterAxes"), "$label.parameterAxes")
        require(axes.keys == parameters.keys) {
            "$label parameter axes do not equal its executable runtime parameter set"
        }
        val triggers = parseTriggers(values.getValue("triggers"), "$label.triggers")
        val hostBindings = parseHostParameterBindings(
            values.getValue("hostParameterBindings"),
            "$label.hostParameterBindings",
            parameters,
        )
        val lifecyclePlacementEntry = parseAndValidateSemanticLifecycle(
            values.getValue("semanticLifecycle"),
            "$label.semanticLifecycle",
            triggers,
            lifetime,
            authoredDefaults,
            hostBindings,
        )
        val directPlacementEntry = values.getValue("parameterPlacementEntry").let { placement ->
            if (placement == AtlasJsonValue.NullValue) null else parseParameterPlacementEntry(
                placement,
                "$label.parameterPlacementEntry",
                authoredDefaults,
                hostBindings,
            )
        }
        require(parameterPlacementEntriesEqual(lifecyclePlacementEntry, directPlacementEntry)) {
            "$label parameter-placement entry differs from its executable lifecycle"
        }
        val eventInstanceOwnership = parseEventInstanceOwnership(
            values.getValue("eventInstanceOwnership"),
            "$label.eventInstanceOwnership",
        )
        val finiteLifecycleTopology = values.getValue("finiteLifecycleTopology").let { topology ->
            if (topology == AtlasJsonValue.NullValue) null else parseFiniteLifecycleTopology(
                topology,
                "$label.finiteLifecycleTopology",
            )
        }

        return ParsedEffectRuntimeMapping(
            lifetime = lifetime,
            parameters = parameters,
            parameterAxes = axes,
            runtimeTriggers = triggers,
            perspectives = parsePerspectives(values.getValue("perspectives"), "$label.perspectives"),
            hostParameterBindings = hostBindings,
            parameterPlacementEntry = directPlacementEntry,
            hostGainClass = hostGainClass,
            engineProgramRole = engineProgramRole,
            eventInstanceOwnership = eventInstanceOwnership,
            finiteLifecycleTopology = finiteLifecycleTopology,
        )
    }

    private fun parseEventInstanceOwnership(
        value: AtlasJsonValue,
        label: String,
    ): AtlasEventInstanceOwnership {
        val fields = value.objectValues(label)
        require(fields.keys == setOf(
            "schema",
            "key",
            "owner",
            "created",
            "survives",
            "resets",
            "activationGeneration",
        )) { "$label has unsupported ownership fields" }
        require(fields.getValue("schema").stringValue("$label.schema") ==
            "byd-fmod-event-instance-ownership-v1")
        val key = fields.getValue("key").stringValue("$label.key")
        require(key == "exactEventPath") { "$label does not use exact event-path identity" }

        return AtlasEventInstanceOwnership(
            key = key,
            owner = AtlasEventInstanceOwner.parse(fields.getValue("owner").stringValue("$label.owner")),
            created = fields.getValue("created").stringValue("$label.created"),
            survives = fields.getValue("survives").stringValue("$label.survives"),
            resets = fields.getValue("resets").stringValue("$label.resets"),
            activationGeneration = fields.getValue("activationGeneration")
                .stringValue("$label.activationGeneration"),
        ).also { ownership ->
            when (ownership.owner) {
                AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE -> {
                    require(ownership.created == "selectedEngineEventPathStartForActiveProfileAudioSession")
                    require(ownership.survives == "loadCoastBothModeChangeOnly")
                    require(ownership.resets == "thatExactEngineEventPathStopRewindStartOrNewInstance")
                    require(ownership.activationGeneration ==
                        "incrementsForEveryStopRewindStartOfThatExactEnginePath")
                }
                AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT -> {
                    require(ownership.created == "exactEventPathStartForActiveProfileAudioSession")
                    require(ownership.survives == "listenerCameraAndLoadCoastBothModeChanges")
                    require(ownership.resets == "profileAudioSessionStopThenNewInstance")
                    require(ownership.activationGeneration ==
                        "incrementsOnlyWhenThatPersistentExactEventPathIsStoppedThenStarted")
                }
            }
        }
    }

    private fun parseFiniteLifecycleTopology(
        value: AtlasJsonValue,
        label: String,
    ): AtlasFiniteLifecycleTopology {
        val fields = value.objectValues(label)
        require(fields.getValue("schema").stringValue("$label.schema") ==
            "byd-fmod-finite-lifecycle-topology-v1")
        val topology = fields.getValue("topology").stringValue("$label.topology")
        require(topology in setOf(
            "externalSemanticTrigger",
            "parameterPlacementOnly",
            "timelineAndParameterPlacement",
        )) { "$label has unsupported lifecycle topology" }

        return AtlasFiniteLifecycleTopology(
            topology = topology,
            status = fields.getValue("status").stringValue("$label.status"),
        )
    }

    private fun parseEffectNode(
        value: AtlasJsonValue,
        label: String,
        variantBindings: Map<String, ParsedEffectRuntimeBinding>,
    ): AtlasEffectNode {
        val values = value.arrayValues(label)
        require(values.size == EFFECT_NODE_FIELD_COUNT) { "$label does not match the compact node encoding" }
        val bindingRef = values[EFFECT_NODE_BINDING_INDEX].stringValue("$label[0]")
        val binding = requireNotNull(variantBindings[bindingRef]) {
            "$label references an unknown variant binding"
        }.variant
        val parameters = parseParameters(values[EFFECT_NODE_PARAMETERS_INDEX], "$label[1]")
        require(parameters.keys == binding.parameterAxes.keys)
        parameters.forEach { (name, coordinate) ->
            require(binding.parameterAxes.getValue(name).binarySearch(coordinate) >= 0) {
                "$label node coordinate is outside its source axis"
            }
        }
        return AtlasEffectNode(
            parameters = parameters,
            lifetime = binding.lifetime,
            hostGainClass = binding.hostGainClass,
            requiredAuthoredBindingKey = binding.authoredBindingKey,
            requiredSourceGuid = binding.sourceGuid,
            shardName = values[EFFECT_NODE_SHARD_INDEX].stringValue("$label[2]"),
            startFrame = values[EFFECT_NODE_START_INDEX].longValue("$label[3]"),
            endFrameExclusive = values[EFFECT_NODE_END_INDEX].longValue("$label[4]"),
            loopStartFrame = values[EFFECT_NODE_LOOP_START_INDEX].nullableLongValue("$label[5]"),
            loopEndFrameExclusive = values[EFFECT_NODE_LOOP_END_INDEX].nullableLongValue("$label[6]"),
        )
    }

    private fun validateEffectEvent(event: AtlasEffectEvent, label: String) {
        require(event.eventPath.startsWith("event:/") && event.eventPath.endsWith("/${event.eventSuffix}")) {
            "$label has an invalid event path"
        }
        require(event.eventSuffix.matches(Regex("^[A-Za-z0-9._-]{1,96}$"))) { "$label has unsafe suffix" }
        require(event.eventSuffix in SUPPORTED_EFFECT_EVENT_SUFFIXES) { "$label has an unsupported core event suffix" }
        require(event.perspectives.isNotEmpty()) { "$label has no perspective" }
        require(event.runtimeTriggers.isNotEmpty()) { "$label has no runtime trigger" }
        require(event.nodes.isNotEmpty()) { "$label has no PCM nodes" }
        require(event.variants.all { variant ->
            variant.runtimeTriggers.isNotEmpty() &&
                event.runtimeTriggers.containsAll(variant.runtimeTriggers) &&
                event.perspectives.containsAll(variant.perspectives)
        }) { "$label variant mapping disagrees with its event scope" }
        require(event.variants.map(AtlasEffectRuntimeVariant::authoredBindingKey).distinct().size ==
            event.variants.size) { "$label contains duplicate authored binding identities" }
        val variantsBySource = event.variants.associateBy(AtlasEffectRuntimeVariant::sourceGuid)
        require(variantsBySource.size == event.variants.size) { "$label contains duplicate source variants" }
        val variantsByBinding = event.variants.associateBy(AtlasEffectRuntimeVariant::authoredBindingKey)
        require(event.nodes.mapTo(linkedSetOf()) { it.requiredAuthoredBindingKey } == variantsByBinding.keys) {
            "$label has an orphan variant binding or a node without a binding"
        }
        require(event.runtimeTriggers == event.variants.flatMapTo(linkedSetOf()) { it.runtimeTriggers }) {
            "$label event triggers do not equal the variant trigger union"
        }
        require(event.perspectives == event.variants.flatMapTo(linkedSetOf()) { it.perspectives }) {
            "$label event perspectives do not equal the variant perspective union"
        }
        require(event.hostGainClasses.isNotEmpty()) { "$label has no host gain class" }
        require(event.hostGainClasses == event.variants.mapTo(linkedSetOf()) { it.hostGainClass }) {
            "$label event host gain classes do not equal its variants"
        }
        require(event.hostGainClasses == event.nodes.mapTo(linkedSetOf()) { it.hostGainClass }) {
            "$label event host gain classes do not equal its nodes"
        }
        require((event.eventSuffix == "engine_int" || event.eventSuffix == "engine_ext") ==
            (event.hostGainClasses == setOf(AtlasHostGainClass.ENGINE_EVENT))) {
            "$label event path and host gain class disagree"
        }
        require(event.variants.all { variant ->
            (variant.hostGainClass == AtlasHostGainClass.ENGINE_EVENT) ==
                (variant.eventInstanceOwnership.owner ==
                    AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE)
        }) { "$label host gain class and EventInstance owner disagree" }
        require(event.variants.all { variant ->
            (variant.lifetime == AtlasEffectLifetime.CONTINUOUS) ||
                variant.finiteLifecycleTopology != null
        }) { "$label finite variant has no explicit lifecycle topology" }
        validateEffectBounds(event, label)
        event.nodes.forEach { node ->
            val variant = variantsByBinding[node.requiredAuthoredBindingKey]
                ?: throw IllegalArgumentException("$label node has no matching source variant")
            require(node.requiredSourceGuid == variant.sourceGuid) {
                "$label node binding and source GUID disagree"
            }
            require(node.lifetime == variant.lifetime && node.hostGainClass == variant.hostGainClass)
            require(node.parameters.keys == variant.parameterAxes.keys) {
                "$label node does not define every authored parameter"
            }
            node.parameters.forEach { (name, coordinate) ->
                require(variant.parameterAxes.getValue(name).binarySearch(coordinate) >= 0) {
                    "$label node parameter is outside its axis"
                }
            }
            require(node.endFrameExclusive > node.startFrame && node.startFrame >= 0)
            require((node.loopStartFrame == null) == (node.loopEndFrameExclusive == null))
            if (node.loopStartFrame != null && node.loopEndFrameExclusive != null) {
                require(node.loopStartFrame >= node.startFrame && node.loopEndFrameExclusive <= node.endFrameExclusive)
                require(node.loopEndFrameExclusive > node.loopStartFrame)
            }
            require(isSafeAtlasShardName(node.shardName))
        }
        require(event.nodes.map { node ->
            node.requiredAuthoredBindingKey to canonicalParameterKey(node.parameters)
        }
            .distinct().size == event.nodes.size) { "$label contains duplicate source/parameter nodes" }
        event.variants.forEach { variant ->
            require(event.nodes.count {
                it.requiredAuthoredBindingKey == variant.authoredBindingKey
            }.toLong() ==
                totalAtlasEffectGridNodes(variant)) {
                "$label binding ${variant.authoredBindingKey} does not contain its complete Cartesian parameter grid"
            }
        }
    }

    private fun parseSchedulingGroup(
        value: AtlasJsonValue,
        label: String,
        selectionRuntimeContract: AtlasJsonValue,
    ): AtlasSchedulingGroup {
        val values = value.objectValues(label)
        val composition = when (values.getValue("composition").stringValue("$label.composition")) {
            "simultaneousLayer" -> AtlasSchedulingComposition.SIMULTANEOUS_LAYER
            "playlistAlternative" -> AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE
            else -> throw IllegalArgumentException("$label has unsupported scheduling topology")
        }
        val groupId = values.getValue("groupId").stringValue("$label.groupId")
        require(groupId.matches(Regex("^(layer|multi):[A-Za-z0-9._-]{1,96}$"))) { "$label has unsafe group id" }
        val selection = values.getValue("selection").objectValues("$label.selection")
        val selectionKind = selection.getValue("kind").stringValue("$label.selection.kind")
        val groupTriggerChancePercent: Double
        val playMode: String?
        val playModeValue: Int?
        val selectionMode: String?
        val selectionModeValue: Int?
        when (composition) {
            AtlasSchedulingComposition.SIMULTANEOUS_LAYER -> {
                require(selectionKind == "always")
                val triggerChance = selection.getValue("triggerChance")
                    .objectValues("$label.selection.triggerChance")
                require(triggerChance.getValue("source").stringValue("$label.selection.triggerChance.source") ==
                    "waveformInstrument.baseProperties.triggerChancePercent")
                groupTriggerChancePercent = parseChance(
                    triggerChance.getValue("percent"),
                    "$label.selection.triggerChance.percent",
                )
                require(triggerChance.getValue("defaultPercentWhenNull")
                    .numberValue("$label.selection.triggerChance.defaultPercentWhenNull") == 100.0)
                require(triggerChance.getValue("activation").stringValue("$label.selection.triggerChance.activation") ==
                    "independentPerSemanticTrigger")
                require(triggerChance.getValue("acceptance").stringValue("$label.selection.triggerChance.acceptance") ==
                    "uniformTimes100 < triggerChancePercent")
                playMode = null
                playModeValue = null
                selectionMode = null
                selectionModeValue = null
            }
            AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE -> {
                require(selectionKind == "fmodMultiInstrumentPlaylist")
                playMode = selection.getValue("playMode").stringValue("$label.selection.playMode")
                require(playMode in SUPPORTED_PLAYLIST_MODES) { "$label has an unsupported playlist mode" }
                playModeValue = selection.getValue("playModeValue").intValue("$label.selection.playModeValue")
                selectionMode = selection.getValue("selectionMode").stringValue("$label.selection.selectionMode")
                require(selectionMode == "PlaylistSelectionMode_SelectNormal")
                selectionModeValue = selection.getValue("selectionModeValue")
                    .intValue("$label.selection.selectionModeValue")
                require(selectionModeValue == 1)
                groupTriggerChancePercent = parseChance(
                    values.getValue("groupTriggerChancePercent"),
                    "$label.groupTriggerChancePercent",
                )
            }
        }
        parseAndValidateSelectionRuntimeContract(
            selectionRuntimeContract,
            "$label.selectionRuntimeContractRef",
            composition,
        )
        val members = values.getValue("members").arrayValues("$label.members").mapIndexed { index, item ->
            val memberLabel = "$label.members[$index]"
            val member = item.objectValues(memberLabel)
            AtlasSchedulingMember(
                sourceGuid = member.getValue("sourceGuid").stringValue("$memberLabel.sourceGuid"),
                authoredOrder = member.getValue("authoredOrder").intValue("$memberLabel.authoredOrder"),
                weight = parsePositiveDefaultOne(member.getValue("weight"), "$memberLabel.weight"),
                triggerChancePercent = parseChance(
                    member.getValue("triggerChancePercent"),
                    "$memberLabel.triggerChancePercent",
                ),
            )
        }
        require(members.isNotEmpty()) { "$label has no scheduling members" }
        require(members.map(AtlasSchedulingMember::sourceGuid).distinct().size == members.size)
        require(members.map(AtlasSchedulingMember::authoredOrder).sorted() == members.indices.toList()) {
            "$label authored order is not contiguous"
        }
        if (composition == AtlasSchedulingComposition.SIMULTANEOUS_LAYER) {
            require(members.size == 1 && members.single().authoredOrder == 0)
            require(members.single().triggerChancePercent == groupTriggerChancePercent)
        }
        val placements = values.getValue("timelinePlacements")
            .arrayValues("$label.timelinePlacements")
            .mapIndexed { index, item -> parseTimelinePlacement(item, "$label.timelinePlacements[$index]") }
        val complete = values.getValue("complete").booleanValue("$label.complete")
        require(values.getValue("incompleteReason") == AtlasJsonValue.NullValue || !complete) {
            "$label is complete but retains an incomplete reason"
        }

        return AtlasSchedulingGroup(
            id = groupId,
            composition = composition,
            selectionKind = selectionKind,
            playMode = playMode,
            playModeValue = playModeValue,
            selectionMode = selectionMode,
            selectionModeValue = selectionModeValue,
            groupTriggerChancePercent = groupTriggerChancePercent,
            members = members.sortedBy(AtlasSchedulingMember::authoredOrder),
            timelinePlacements = placements,
            maximumSourceCornerContributorsPerLogicalRing = values
                .getValue("maximumSourceCornerContributorsPerLogicalRing")
                .intValue("$label.maximumSourceCornerContributorsPerLogicalRing")
                .also { require(it > 0) },
            maximumFmodSourceChannelsPerLogicalRing = values
                .getValue("maximumFmodSourceChannelsPerLogicalRing")
                .intValue("$label.maximumFmodSourceChannelsPerLogicalRing")
                .also { require(it > 0) },
            maximumCaptureFramesPerLogicalRing = values.getValue("maximumCaptureFramesPerLogicalRing")
                .intValue("$label.maximumCaptureFramesPerLogicalRing")
                .also { require(it >= 0) },
            streamingRingBufferFrames = values.getValue("streamingRingBufferFrames")
                .intValue("$label.streamingRingBufferFrames")
                .also { require(it >= 0) },
            complete = complete,
        )
    }

    private fun parseTimelinePlacement(value: AtlasJsonValue, label: String): AtlasTimelinePlacement {
        val placement = value.objectValues(label)
        val startTime = placement.getValue("startTime").numberValue("$label.startTime")
        val length = placement.getValue("length").numberValue("$label.length")
        require(startTime >= 0.0 && length >= 0.0)

        return AtlasTimelinePlacement(
            instrumentGuid = placement.getValue("instrumentGuid").stringValue("$label.instrumentGuid"),
            startTime = startTime,
            length = length,
            timeLocked = placement.getValue("timeLocked").booleanValue("$label.timeLocked"),
        )
    }

    private fun parseAndValidateSelectionRuntimeContract(
        value: AtlasJsonValue,
        label: String,
        composition: AtlasSchedulingComposition,
    ) {
        val contract = value.objectValues(label)
        require(contract.getValue("schema").stringValue("$label.schema") ==
            "byd-full-event-playlist-selection-v1")
        require(contract.getValue("stateScope").stringValue("$label.stateScope") ==
            "selectionKindSpecificSeeSelectionStateOwnership")
        val seed = contract.getValue("seedDerivation").objectValues("$label.seedDerivation")
        require(seed.keys == setOf(
            "encoding",
            "formula",
            "atlasFamilyId",
            "appliesTo",
            "take",
            "zeroSeedReplacementUnsigned",
        ))
        require(seed.getValue("encoding").stringValue("$label.seedDerivation.encoding") == "utf8")
        require(seed.getValue("formula").stringValue("$label.seedDerivation.formula") ==
            "sha256('byd-fmod-playlist-v3|'+atlasFamilyId+'|'+eventPath+'|'+profileAudioSessionGeneration+'|'+groupId)")
        require(seed.getValue("atlasFamilyId").stringValue("$label.seedDerivation.atlasFamilyId") ==
            "runtimeIndex.id")
        require(seed.getValue("appliesTo").stringValue("$label.seedDerivation.appliesTo") ==
            "androidDeterministicSmartRandomSubstituteOnly; notFMODSequenceParity")
        require(seed.getValue("take").stringValue("$label.seedDerivation.take") ==
            "first8BytesBigEndianUnsigned")
        require(seed.getValue("zeroSeedReplacementUnsigned")
            .stringValue("$label.seedDerivation.zeroSeedReplacementUnsigned") == "0x9e3779b97f4a7c15")
        val rng = contract.getValue("rng").objectValues("$label.rng")
        require(rng.keys == setOf(
            "algorithm",
            "unsignedArithmetic",
            "stateTransition",
            "output",
            "uniform",
        ))
        require(rng.getValue("algorithm").stringValue("$label.rng.algorithm") == "xorshift64star-v1")
        require(rng.getValue("unsignedArithmetic").stringValue("$label.rng.unsignedArithmetic") ==
            "uint64Modulo2To64")
        require(rng.getValue("stateTransition").arrayValues("$label.rng.stateTransition")
            .mapIndexed { index, item -> item.stringValue("$label.rng.stateTransition[$index]") } == listOf(
            "x = x xor (x unsignedShiftRight 12)",
            "x = x xor ((x shiftLeft 25) modulo2To64)",
            "x = x xor (x unsignedShiftRight 27)",
        ))
        require(rng.getValue("output").stringValue("$label.rng.output") ==
            "postTransitionStateTimes2685821657736338717Modulo2To64")
        require(rng.getValue("uniform").stringValue("$label.rng.uniform") ==
            "(outputUnsigned unsignedShiftRight 11) / 9007199254740992.0")
        if (composition == AtlasSchedulingComposition.SIMULTANEOUS_LAYER) {
            require(contract.keys == setOf(
                "schema",
                "schedulerKind",
                "stateScope",
                "seedDerivation",
                "rng",
                "selection",
                "triggerChance",
                "invalidAuthoredValue",
            ))
            require(contract.getValue("schedulerKind").stringValue("$label.schedulerKind") == "simultaneousLayer")
            val chance = contract.getValue("triggerChance").objectValues("$label.triggerChance")
            require(chance.keys == setOf(
                "source",
                "defaultPercentWhenNull",
                "acceptance",
                "onRejected",
            ))
            require(chance.getValue("source").stringValue("$label.triggerChance.source") ==
                "waveformInstrument.baseProperties.triggerChancePercent")
            require(chance.getValue("defaultPercentWhenNull")
                .numberValue("$label.triggerChance.defaultPercentWhenNull") == 100.0)
            require(chance.getValue("acceptance").stringValue("$label.triggerChance.acceptance") ==
                "uniformTimes100 < triggerChancePercent")
            require(chance.getValue("onRejected").stringValue("$label.triggerChance.onRejected") == "silent")
            val selection = contract.getValue("selection").objectValues("$label.selection")
            require(selection.keys == setOf("kind", "drawConsumption", "history"))
            require(selection.getValue("kind").stringValue("$label.selection.kind") == "always")
            require(selection.getValue("drawConsumption").stringValue("$label.selection.drawConsumption") ==
                "oneRngOutputForThisLayerChanceOnEverySemanticTrigger")
            require(selection.getValue("history").stringValue("$label.selection.history") == "none")
        } else {
            require(contract.keys == setOf(
                "schema",
                "stateScope",
                "selectionStateOwnership",
                "seedDerivation",
                "rng",
                "groupTriggerChance",
                "selection",
                "memberTriggerChance",
                "invalidAuthoredValue",
            ))
            validatePlaylistChanceAndSelectionContract(contract, label)
        }
        require(contract.getValue("invalidAuthoredValue").stringValue("$label.invalidAuthoredValue") ==
            "blockReleaseNonFiniteChanceOrChanceOutside0To100OrNonFiniteNonPositiveWeight")
    }

    private fun validatePlaylistChanceAndSelectionContract(
        contract: Map<String, AtlasJsonValue>,
        label: String,
    ) {
        val ownership = contract.getValue("selectionStateOwnership")
            .objectValues("$label.selectionStateOwnership")
        require(ownership.keys == setOf("playSequential", "smartRandom"))
        val sequentialOwnership = ownership.getValue("playSequential")
            .objectValues("$label.selectionStateOwnership.playSequential")
        require(sequentialOwnership.keys == setOf("scope", "originalBankEvidence"))
        require(sequentialOwnership.getValue("scope")
            .stringValue("$label.selectionStateOwnership.playSequential.scope") ==
            "perExactEventPathEventInstanceActivationGenerationAndGroupId")
        require(sequentialOwnership.getValue("originalBankEvidence")
            .stringValue("$label.selectionStateOwnership.playSequential.originalBankEvidence") ==
            "stopRewindStartSameEventInstanceResetsAuthoredCursor")
        val randomOwnership = ownership.getValue("smartRandom")
            .objectValues("$label.selectionStateOwnership.smartRandom")
        require(randomOwnership.keys == setOf(
            "fmodObservedScope",
            "androidDeterministicSubstituteScope",
            "originalBankEvidence",
            "sequenceParity",
            "androidPolicy",
        ))
        require(randomOwnership.getValue("fmodObservedScope")
            .stringValue("$label.selectionStateOwnership.smartRandom.fmodObservedScope") ==
            "advancesAcrossStopRewindStart; exactScopeNotSeparatedBetweenStudioSessionEventDescriptionAndGroup")
        require(randomOwnership.getValue("androidDeterministicSubstituteScope")
            .stringValue("$label.selectionStateOwnership.smartRandom.androidDeterministicSubstituteScope") ==
            "perProfileAudioSessionGenerationExactEventPathAndGroupId")
        require(randomOwnership.getValue("originalBankEvidence")
            .stringValue("$label.selectionStateOwnership.smartRandom.originalBankEvidence") ==
            "freshEventInstancesAndStopRewindStartAdvanceObservedStream")
        require(randomOwnership.getValue("sequenceParity")
            .stringValue("$label.selectionStateOwnership.smartRandom.sequenceParity") == "notClaimed")
        require(randomOwnership.getValue("androidPolicy")
            .stringValue("$label.selectionStateOwnership.smartRandom.androidPolicy") ==
            "deterministicSubstituteRequiresIndependentMemberCoverageWeightChanceDistributionAndLifecycleOracle")
        val groupChance = contract.getValue("groupTriggerChance").objectValues("$label.groupTriggerChance")
        require(groupChance.keys == setOf(
            "source",
            "defaultPercentWhenNull",
            "drawConsumption",
            "acceptance",
            "onRejected",
        ))
        require(groupChance.getValue("source").stringValue("$label.groupTriggerChance.source") ==
            "multiInstrument.baseProperties.triggerChancePercent")
        require(groupChance.getValue("defaultPercentWhenNull")
            .numberValue("$label.groupTriggerChance.defaultPercentWhenNull") == 100.0)
        require(groupChance.getValue("drawConsumption").stringValue("$label.groupTriggerChance.drawConsumption") ==
            "oneRngOutputBeforePlaylistSelectionForEverySemanticTrigger")
        require(groupChance.getValue("acceptance").stringValue("$label.groupTriggerChance.acceptance") ==
            "uniformTimes100 < triggerChancePercent")
        require(groupChance.getValue("onRejected").stringValue("$label.groupTriggerChance.onRejected") ==
            "silentNoCursorOrHistoryUpdate")
        val selection = contract.getValue("selection").objectValues("$label.selection")
        require(selection.keys == setOf("playSequential", "smartRandom"))
        val sequential = selection.getValue("playSequential").objectValues("$label.selection.playSequential")
        require(sequential.keys == setOf(
            "cursorScope",
            "initialCursor",
            "order",
            "weights",
            "cursorAdvance",
        ))
        require(sequential.getValue("cursorScope").stringValue("$label.selection.playSequential.cursorScope") ==
            "perExactEventPathEventInstanceActivationGenerationAndGroupId")
        require(sequential.getValue("initialCursor").intValue("$label.selection.playSequential.initialCursor") == 0)
        require(sequential.getValue("order").stringValue("$label.selection.playSequential.order") ==
            "ascendingAuthoredOrderWrap")
        require(sequential.getValue("weights").stringValue("$label.selection.playSequential.weights") == "ignored")
        require(sequential.getValue("cursorAdvance").stringValue("$label.selection.playSequential.cursorAdvance") ==
            "afterGroupAcceptanceBeforeMemberChanceEvenWhenMemberChanceRejects")
        val random = selection.getValue("smartRandom").objectValues("$label.selection.smartRandom")
        require(random.keys == setOf(
            "stateScope",
            "drawConsumption",
            "weight",
            "weightedBoundary",
            "noImmediateRepeat",
            "historyUpdate",
        ))
        require(random.getValue("stateScope").stringValue("$label.selection.smartRandom.stateScope") ==
            "AndroidDeterministicSubstitutePerProfileAudioSessionGenerationExactEventPathAndGroupId; FMODSequenceParityNotClaimed")
        require(random.getValue("drawConsumption").stringValue("$label.selection.smartRandom.drawConsumption") ==
            "oneRngOutputAfterGroupAcceptance")
        require(random.getValue("weight").stringValue("$label.selection.smartRandom.weight") ==
            "positiveAuthoredWeightDefaultOne")
        require(random.getValue("weightedBoundary").stringValue("$label.selection.smartRandom.weightedBoundary") ==
            "uniformTimesTotalWeight < cumulativeWeight; finalMemberFallbackOnlyForFloatingPointRoundup")
        require(random.getValue("noImmediateRepeat").stringValue("$label.selection.smartRandom.noImmediateRepeat") ==
            "excludeLastSelectedOnlyWhenMemberCountAtLeast3AndEveryMemberTriggerChancePercentIs100")
        require(random.getValue("historyUpdate").stringValue("$label.selection.smartRandom.historyUpdate") ==
            "afterMemberSelectionBeforeMemberChance")
        val memberChance = contract.getValue("memberTriggerChance").objectValues("$label.memberTriggerChance")
        require(memberChance.keys == setOf(
            "source",
            "defaultPercentWhenNull",
            "drawConsumption",
            "acceptance",
            "onRejected",
        ))
        require(memberChance.getValue("source").stringValue("$label.memberTriggerChance.source") ==
            "waveformInstrument.baseProperties.triggerChancePercent")
        require(memberChance.getValue("defaultPercentWhenNull")
            .numberValue("$label.memberTriggerChance.defaultPercentWhenNull") == 100.0)
        require(memberChance.getValue("drawConsumption").stringValue("$label.memberTriggerChance.drawConsumption") ==
            "oneRngOutputAfterMemberSelectionIncludingZeroAnd100Percent")
        require(memberChance.getValue("acceptance").stringValue("$label.memberTriggerChance.acceptance") ==
            "uniformTimes100 < triggerChancePercent")
        require(memberChance.getValue("onRejected").stringValue("$label.memberTriggerChance.onRejected") ==
            "silentButSelectionCursorAndHistoryRemainAdvanced")
    }

    private fun parseAndValidateSemanticLifecycle(
        value: AtlasJsonValue,
        label: String,
        triggers: Set<AtlasRuntimeTrigger>,
        lifetime: AtlasEffectLifetime,
        authoredDefaults: List<AuthoredParameterDefault>,
        hostBindings: List<AtlasHostParameterBinding>,
    ): AtlasParameterPlacementEntry? {
        val contracts = value.arrayValues(label)
        require(contracts.size == triggers.size) { "$label does not cover every semantic trigger" }
        var parameterPlacementEntry: AtlasParameterPlacementEntry? = null
        val parsed = contracts.mapIndexed { index, item ->
            val itemLabel = "$label[$index]"
            val lifecycle = item.objectValues(itemLabel)
            val trigger = parseTrigger(lifecycle.getValue("trigger"), "$itemLabel.trigger")
            require(lifecycle.getValue("lifetime").stringValue("$itemLabel.lifetime") == lifetime.wireName)
            if (trigger == AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY) {
                require(lifetime != AtlasEffectLifetime.CONTINUOUS) {
                    "$itemLabel parameter-placement entry cannot own a continuous capture"
                }
                val expected = mapOf(
                    "signal" to "EngineSimulation.engineEventHostParameters",
                    "start" to "atEngineEventInstanceCreationIfInitialPlacementMembershipInsideOrEveryOutsideToInsidePlacementEntry",
                    "parameterSample" to "currentHostParameterBindingsAtExactDspBlock",
                    "stop" to "capturedOneShotOrFiniteRepeatEnd",
                    "retrigger" to "everyOutsideToInsidePlacementEntrySubjectToSchedulingGroupPolyphony",
                )
                expected.forEach { (name, expectedValue) ->
                    require(lifecycle.getValue(name).stringValue("$itemLabel.$name") == expectedValue) {
                        "$itemLabel.$name disagrees with the Android lifecycle"
                    }
                }
                require(lifecycle.keys == setOf("trigger", "lifetime", "parameterPlacementEntry") + expected.keys) {
                    "$itemLabel has unknown or missing parameter-placement lifecycle fields"
                }
                require(parameterPlacementEntry == null) { "$label contains duplicate placement entry contracts" }
                parameterPlacementEntry = parseParameterPlacementEntry(
                    lifecycle.getValue("parameterPlacementEntry"),
                    "$itemLabel.parameterPlacementEntry",
                    authoredDefaults,
                    hostBindings,
                )
            } else {
                val expected = requireNotNull(SEMANTIC_LIFECYCLE_CONTRACTS[trigger]) {
                    "No Android lifecycle contract for $trigger"
                }
                expected.forEach { (name, expectedValue) ->
                    require(lifecycle.getValue(name).stringValue("$itemLabel.$name") == expectedValue) {
                        "$itemLabel.$name disagrees with the Android lifecycle"
                    }
                }
                require(lifecycle.keys == setOf("trigger", "lifetime") + expected.keys) {
                    "$itemLabel has unknown or missing lifecycle fields"
                }
            }
            trigger
        }
        require(parsed.toSet() == triggers && parsed.distinct().size == parsed.size)

        return parameterPlacementEntry
    }

    private fun parseParameterPlacementEntry(
        value: AtlasJsonValue,
        label: String,
        authoredDefaults: List<AuthoredParameterDefault>,
        hostBindings: List<AtlasHostParameterBinding>,
    ): AtlasParameterPlacementEntry {
        val entry = value.objectValues(label)
        require(entry.keys == setOf("schema", "stateScope", "initialState", "membership", "transition"))
        require(entry.getValue("schema").stringValue("$label.schema") ==
            "byd-fmod-parameter-placement-entry-v1")
        require(entry.getValue("stateScope").stringValue("$label.stateScope") ==
            "perVariantSourceGuidPerExactEventPathAndEventInstanceActivationGeneration")
        val initial = entry.getValue("initialState").objectValues("$label.initialState")
        require(initial.keys == setOf("inside", "outside", "when"))
        require(initial.getValue("inside").stringValue("$label.initialState.inside") ==
            "startOnceAtCurrentHostParameterValue")
        require(initial.getValue("outside").stringValue("$label.initialState.outside") ==
            "remainSilentUntilOutsideToInsideEntry")
        require(initial.getValue("when").stringValue("$label.initialState.when") ==
            "exactEventInstanceCreated")

        val membership = entry.getValue("membership").objectValues("$label.membership")
        require(membership.keys == setOf(
            "parameterCombination",
            "placementsWithinParameter",
            "startBoundary",
            "endBoundary",
            "placements",
            "parameterValues",
        ))
        require(membership.getValue("parameterCombination").stringValue("$label.membership.parameterCombination") ==
            "allParameterGroupsMustContainCurrentValue")
        require(membership.getValue("placementsWithinParameter")
            .stringValue("$label.membership.placementsWithinParameter") ==
            "allInstrumentChainPlacementsMustContainCurrentValue")
        require(membership.getValue("startBoundary").stringValue("$label.membership.startBoundary") == "inclusive")
        require(membership.getValue("endBoundary").stringValue("$label.membership.endBoundary") ==
            "includeEndFromAuthoredParameterPlacement")

        val parameterValues = membership.getValue("parameterValues")
            .arrayValues("$label.membership.parameterValues")
            .associate { item ->
                val fields = item.objectValues("$label.membership.parameterValues[]")
                require(fields.keys == setOf("parameter", "parameterGuid", "layoutGuid", "value"))
                val parameter = fields.getValue("parameter").stringValue("$label.membership.parameterValues[].parameter")
                val parameterGuid = fields.getValue("parameterGuid")
                    .stringValue("$label.membership.parameterValues[].parameterGuid")
                val layoutGuid = fields.getValue("layoutGuid")
                    .stringValue("$label.membership.parameterValues[].layoutGuid")
                require(parameterGuid.isNotBlank() && layoutGuid.isNotBlank())
                val authoredIdentity = requireNotNull(authoredDefaults.singleOrNull { candidate ->
                    candidate.parameterGuid == parameterGuid && candidate.parameter == parameter
                }) { "$label placement parameter has no exact authored GUID default" }
                val parameterValue = fields.getValue("value").objectValues("$label.membership.parameterValues[].value")
                val resolvedValue = when (parameterValue.getValue("kind")
                    .stringValue("$label.membership.parameterValues[].value.kind")) {
                    "hostBinding" -> {
                        require(parameterValue.keys == setOf("kind", "binding"))
                        val binding = parameterValue.getValue("binding")
                            .objectValues("$label.membership.parameterValues[].value.binding")
                        require(binding.keys == setOf("parameter", "source"))
                        require(binding.getValue("parameter").stringValue("$label.membership.parameterValues[].value.binding.parameter") == parameter)
                        val source = binding.getValue("source")
                            .stringValue("$label.membership.parameterValues[].value.binding.source")
                        require(hostBindings.any { host ->
                            host.parameter == parameter &&
                                (host.value as? AtlasHostParameterValue.Source)?.name == source
                        }) { "$label placement host binding disagrees with runtime bindings" }
                        AtlasHostParameterValue.Source(source)
                    }
                    "authoredDefault" -> {
                        require(parameterValue.keys == setOf("kind", "value"))
                        val defaultValue = parameterValue.getValue("value")
                            .numberValue("$label.membership.parameterValues[].value.value")
                        require(defaultValue == authoredIdentity.defaultValue)
                        AtlasHostParameterValue.Constant(defaultValue)
                    }
                    else -> throw IllegalArgumentException("$label placement has an unsupported parameter value")
                }
                parameter to PlacementParameterValue(parameterGuid, layoutGuid, resolvedValue)
            }
        require(parameterValues.size == membership.getValue("parameterValues")
            .arrayValues("$label.membership.parameterValues").size) {
            "$label placement contains duplicate parameter values"
        }

        val placements = membership.getValue("placements").objectValues("$label.membership.placements")
        require(placements.isNotEmpty() && placements.keys == parameterValues.keys)
        val axes = placements.entries.sortedBy { it.key }.map { (parameter, spansValue) ->
            val identity = parameterValues.getValue(parameter)
            val spans = spansValue.arrayValues("$label.membership.placements.$parameter").mapIndexed { index, item ->
                val spanLabel = "$label.membership.placements.$parameter[$index]"
                val span = item.objectValues(spanLabel)
                require(span.keys == setOf(
                    "start", "end", "includeEnd", "parameterGuid", "layoutGuid", "instrumentGuid",
                ))
                val start = span.getValue("start").numberValue("$spanLabel.start")
                val end = span.getValue("end").numberValue("$spanLabel.end")
                val includeEnd = span.getValue("includeEnd").booleanValue("$spanLabel.includeEnd")
                require(start.isFinite() && end.isFinite() && end >= start)
                val parameterGuid = span.getValue("parameterGuid").stringValue("$spanLabel.parameterGuid")
                val layoutGuid = span.getValue("layoutGuid").stringValue("$spanLabel.layoutGuid")
                require(parameterGuid == identity.parameterGuid && layoutGuid == identity.layoutGuid)
                AtlasParameterPlacementSpan(
                    start = start,
                    end = end,
                    includeEnd = includeEnd,
                    parameterGuid = parameterGuid,
                    layoutGuid = layoutGuid,
                    instrumentGuid = span.getValue("instrumentGuid").stringValue("$spanLabel.instrumentGuid")
                        .also { require(it.isNotBlank()) },
                )
            }.toTypedArray()
            require(spans.isNotEmpty())
            AtlasParameterPlacementAxis(
                parameter,
                identity.parameterGuid,
                identity.layoutGuid,
                identity.value,
                spans,
            )
        }.toTypedArray()

        val transition = entry.getValue("transition").objectValues("$label.transition")
        require(transition.keys == setOf("sampleBoundary", "trigger", "exit", "directions"))
        require(transition.getValue("sampleBoundary").stringValue("$label.transition.sampleBoundary") ==
            "eachDspBlockAfterHostParameterUpdateForHostBoundParameters")
        require(transition.getValue("trigger").stringValue("$label.transition.trigger") ==
            "combinedMembershipOutsideToInside")
        require(transition.getValue("exit").stringValue("$label.transition.exit") ==
            "combinedMembershipInsideToOutsideArmsNextEntry")
        require(transition.getValue("directions").arrayValues("$label.transition.directions")
            .map { it.stringValue("$label.transition.directions[]") } ==
            listOf("increasing", "decreasing", "discontinuousJump"))

        return AtlasParameterPlacementEntry(axes)
    }

    private fun parseHostParameterBindings(
        value: AtlasJsonValue,
        label: String,
        authoredDefaults: Map<String, Double>,
    ): List<AtlasHostParameterBinding> {
        val bindings = value.arrayValues(label).mapIndexed { index, item ->
            val itemLabel = "$label[$index]"
            val binding = item.objectValues(itemLabel)
            val parameter = binding.getValue("parameter").stringValue("$itemLabel.parameter")
            require(parameter in authoredDefaults) { "$itemLabel does not have an explicit authored default" }
            val valueFields = binding.keys - "parameter"
            require(valueFields.size == 1) { "$itemLabel must have exactly one source or constant" }
            val hostValue = when (val name = valueFields.single()) {
                "source" -> AtlasHostParameterValue.Source(
                    binding.getValue(name).stringValue("$itemLabel.source").also { source ->
                        require(source in SUPPORTED_HOST_PARAMETER_SOURCES) {
                            "$itemLabel uses an unsupported host source"
                        }
                    },
                )
                "constant" -> AtlasHostParameterValue.Constant(
                    binding.getValue(name).numberValue("$itemLabel.constant"),
                )
                else -> throw IllegalArgumentException("$itemLabel has an unsupported host binding")
            }
            AtlasHostParameterBinding(parameter, hostValue)
        }
        require(bindings.map(AtlasHostParameterBinding::parameter).distinct().size == bindings.size) {
            "$label has duplicate parameters"
        }

        return bindings
    }

    private fun parseAuthoredParameterDefaults(
        value: AtlasJsonValue,
        label: String,
    ): List<AuthoredParameterDefault> {
        val defaults = value.arrayValues(label).mapIndexed { index, item ->
            val itemLabel = "$label[$index]"
            val fields = item.objectValues(itemLabel)
            require(fields.keys == setOf("parameter", "parameterGuid", "defaultValue", "type")) {
                "$itemLabel has unsupported authored-default fields"
            }
            AuthoredParameterDefault(
                parameter = fields.getValue("parameter").stringValue("$itemLabel.parameter")
                    .also { require(isSafeParameterName(it)) },
                parameterGuid = fields.getValue("parameterGuid").stringValue("$itemLabel.parameterGuid")
                    .also { require(it.isNotBlank()) },
                defaultValue = fields.getValue("defaultValue").numberValue("$itemLabel.defaultValue"),
            ).also { fields.getValue("type").stringValue("$itemLabel.type") }
        }
        require(defaults.map(AuthoredParameterDefault::parameterGuid).distinct().size == defaults.size) {
            "$label contains duplicate parameter GUIDs"
        }

        return defaults
    }

    private fun parseAndValidateEffectExecutionContract(
        value: AtlasJsonValue,
        label: String = "atlas.effects.runtimeContract.execution",
    ) {
        val contract = value.objectValues(label)
        require(contract.getValue("schema").stringValue("$label.schema") ==
            "byd-full-event-effect-execution-contract-v1")
        require(contract.getValue("schedulingGroupComposition")
            .stringValue("$label.schedulingGroupComposition") ==
            "sumIndependentSimultaneousGroups; alternativesOnlyWithinSameGroupId")
        val continuous = contract.getValue("continuous").objectValues("$label.continuous")
        require(continuous.getValue("algorithm").stringValue("$label.continuous.algorithm") ==
            "perSourceAxisAlignedMultilinear-v1")
        require(continuous.getValue("axisSource").stringValue("$label.continuous.axisSource") ==
            "sourceBinding.parameterAxes")
        require(continuous.getValue("axisBounds").stringValue("$label.continuous.axisBounds") ==
            "clampToAuthoredEndpointThenBinarySearchLowerUpper")
        require(continuous.getValue("cornerGainFormula").stringValue("$label.continuous.cornerGainFormula") ==
            "rawNDimensionalMultilinearWeight")
        require(continuous.getValue("duplicateCornerPolicy")
            .stringValue("$label.continuous.duplicateCornerPolicy") ==
            "sumDuplicateAxesThenMapOneNodeOnce")
        require(continuous.getValue("nodeIdentity").stringValue("$label.continuous.nodeIdentity") ==
            "requiredAuthoredBindingKeyPlusCanonicalParameters")
        require(continuous.getValue("nodePlaybackRatio").numberValue("$label.continuous.nodePlaybackRatio") == 1.0)
        require(continuous.getValue("mmapPolicy").stringValue("$label.continuous.mmapPolicy") ==
            "mapOnlyCurrentSourceCorners; unmapAfterSourceDeactivation")
        require(continuous.getValue("lifecycle").stringValue("$label.continuous.lifecycle") ==
            "startOnSemanticTriggerUpdateParametersWhileActiveStopOnSemanticDeactivation")
        val oneShot = contract.getValue("oneShot").objectValues("$label.oneShot")
        require(oneShot.getValue("algorithm").stringValue("$label.oneShot.algorithm") ==
            "perSourceAxisAlignedMultilinearFiniteRing-v2")
        require(oneShot.getValue("axisSource").stringValue("$label.oneShot.axisSource") ==
            "sourceBinding.parameterAxes")
        require(oneShot.getValue("axisBounds").stringValue("$label.oneShot.axisBounds") ==
            "clampToAuthoredEndpointThenBinarySearchLowerUpper")
        require(oneShot.getValue("cornerGainFormula").stringValue("$label.oneShot.cornerGainFormula") ==
            "rawNDimensionalMultilinearWeight")
        require(oneShot.getValue("duplicateCornerPolicy")
            .stringValue("$label.oneShot.duplicateCornerPolicy") ==
            "sumDuplicateAxesThenMixOneFiniteRingContributorOnce")
        require(oneShot.getValue("nodeIdentity").stringValue("$label.oneShot.nodeIdentity") ==
            "requiredAuthoredBindingKeyPlusCanonicalParameters")
        require(oneShot.getValue("selection").stringValue("$label.oneShot.selection") ==
            "chooseSchedulingGroupMembersThenMixEveryNonZeroCornerForEachSelectedMemberIntoOneLogicalGroupRing")
        val logicalVoice = oneShot.getValue("logicalVoice").objectValues("$label.oneShot.logicalVoice")
        require(logicalVoice.getValue("model").stringValue("$label.oneShot.logicalVoice.model") ==
            "onePreallocatedFiniteRingVoicePerSchedulingGroupInstance")
        require(logicalVoice.getValue("materialization").stringValue("$label.oneShot.logicalVoice.materialization") ==
            "evaluateGroupAndMemberSelectionOnceThenAtomicallyMixWeightedFloat32OrFloat64StereoContributorsFromExactMappedNodes")
        require(logicalVoice.getValue("pcm16Premix").stringValue("$label.oneShot.logicalVoice.pcm16Premix") ==
            "forbidden")
        require(logicalVoice.getValue("tail").stringValue("$label.oneShot.logicalVoice.tail") ==
            "retainMixedRingUntilEverySelectedCapturedContributorEnds")
        require(logicalVoice.getValue("sourceCornerRegions")
            .stringValue("$label.oneShot.logicalVoice.sourceCornerRegions") ==
            "audioCallbackMixesOnlyPrearmedPcm16AttackCacheForFramesZeroThroughAttackBoundaryExclusiveWhereAttackBoundaryFramesEqualsMinNodeFrames4096ThenConsumesPreparedFloat32OrFloat64Ring; " +
            "nonRealtimeWorkerUsesMappedOrPreopenedReadOnlyShardForTailMaterialization; " +
            "noAudioCallbackMmapAllocationLockOrPcm16PremixStorage")
        require(oneShot.getValue("finiteRepeat").stringValue("$label.oneShot.finiteRepeat") ==
            "renderAndPlayExactlyCapturedFiniteDuration")
    }

    private fun validateEffectBounds(event: AtlasEffectEvent, label: String) {
        val variantsByGroup = event.variants.groupBy { it.schedulingGroup.id }
        variantsByGroup.forEach { (groupId, variants) ->
            val group = variants.first().schedulingGroup
            require(variants.all { it.schedulingGroup == group }) {
                "$label group $groupId has inconsistent authored topology"
            }
            require(group.complete == event.runtimeContractComplete || !event.runtimeContractComplete)
            require(group.members.map(AtlasSchedulingMember::sourceGuid).toSet() ==
                variants.map(AtlasEffectRuntimeVariant::sourceGuid).toSet()) {
                "$label group $groupId does not preserve every source member"
            }
            require(variants.map(AtlasEffectRuntimeVariant::lifetime).distinct().size == 1) {
                "$label group $groupId mixes incompatible lifetimes"
            }
            require(variants.map(AtlasEffectRuntimeVariant::hostGainClass).distinct().size == 1) {
                "$label group $groupId mixes host gain classes"
            }
            require(variants.map(AtlasEffectRuntimeVariant::runtimeTriggers).distinct().size == 1 &&
                variants.map(AtlasEffectRuntimeVariant::perspectives).distinct().size == 1 &&
                variants.drop(1).all { variant ->
                    parameterPlacementEntriesEqual(
                        variants.first().parameterPlacementEntry,
                        variant.parameterPlacementEntry,
                    )
                }) {
                "$label group $groupId mixes incompatible trigger, perspective, or placement lifecycles"
            }
            val expectedCorners = event.perspectives.maxOf { perspective ->
                val scoped = variants.filter { perspective in it.perspectives }
                if (scoped.isEmpty()) return@maxOf 0L
                if (group.composition == AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE) {
                    scoped.maxOf(::maximumAtlasEffectCorners)
                } else {
                    scoped.sumOf(::maximumAtlasEffectCorners)
                }
            }
            require(expectedCorners == group.maximumSourceCornerContributorsPerLogicalRing.toLong()) {
                "$label group $groupId contributor scalar disagrees with its N-D source axes"
            }
            val expectedFmodChannels = event.perspectives.maxOf { perspective ->
                val scopedCount = variants.count { perspective in it.perspectives }
                if (scopedCount == 0) {
                    0
                } else if (group.composition == AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE) {
                    1
                } else {
                    scopedCount
                }
            }
            require(expectedFmodChannels == group.maximumFmodSourceChannelsPerLogicalRing) {
                "$label group $groupId raw FMOD-channel scalar disagrees with its sources"
            }
            val finite = variants.first().lifetime != AtlasEffectLifetime.CONTINUOUS
            val authoredBindingKeys = variants.mapTo(
                hashSetOf(),
                AtlasEffectRuntimeVariant::authoredBindingKey,
            )
            val maximumCaptureFrames = event.nodes.asSequence()
                .filter { it.requiredAuthoredBindingKey in authoredBindingKeys }
                .maxOf { it.endFrameExclusive - it.startFrame }
            require((finite &&
                group.maximumCaptureFramesPerLogicalRing.toLong() == maximumCaptureFrames &&
                group.streamingRingBufferFrames == FullEventAtlasProgram.FINITE_STREAMING_RING_BUFFER_FRAMES) ||
                (!finite && group.maximumCaptureFramesPerLogicalRing == 0 &&
                    group.streamingRingBufferFrames == 0)) {
                "$label group $groupId capture/ring-buffer scalars disagree with its nodes or lifetime"
            }
        }
    }

    private fun parseChance(value: AtlasJsonValue, label: String): Double {
        val result = if (value == AtlasJsonValue.NullValue) 100.0 else value.numberValue(label)
        require(result in 0.0..100.0) { "$label must be in [0,100]" }

        return result
    }

    private fun parsePositiveDefaultOne(value: AtlasJsonValue, label: String): Double {
        val result = if (value == AtlasJsonValue.NullValue) 1.0 else value.numberValue(label)
        require(result > 0.0) { "$label must be positive" }

        return result
    }

    private fun parseTrigger(value: AtlasJsonValue, label: String): AtlasRuntimeTrigger {
        val name = value.stringValue(label)

        return requireNotNull(AtlasRuntimeTrigger.entries.firstOrNull { it.name == name }) {
            "Unsupported core atlas trigger $name"
        }
    }

    private fun parameterPlacementEntriesEqual(
        left: AtlasParameterPlacementEntry?,
        right: AtlasParameterPlacementEntry?,
    ): Boolean {
        if (left == null || right == null) return left == null && right == null
        if (left.axes.size != right.axes.size) return false
        var axisIndex = 0
        while (axisIndex < left.axes.size) {
            val leftAxis = left.axes[axisIndex]
            val rightAxis = right.axes[axisIndex]
            if (leftAxis.parameter != rightAxis.parameter ||
                leftAxis.parameterGuid != rightAxis.parameterGuid ||
                leftAxis.layoutGuid != rightAxis.layoutGuid ||
                leftAxis.value != rightAxis.value ||
                !leftAxis.spans.contentEquals(rightAxis.spans)
            ) return false
            axisIndex += 1
        }

        return true
    }

    private fun canonicalParameterKey(parameters: Map<String, Double>): String =
        parameters.toSortedMap().entries.joinToString("|") { (name, value) -> "$name=${value.toRawBits()}" }

    private fun parseParameters(value: AtlasJsonValue, label: String): Map<String, Double> =
        value.objectValues(label).also { parameters ->
            require(parameters.keys.all(::isSafeParameterName)) { "$label contains an unsafe parameter name" }
        }.mapValues { (name, item) -> item.numberValue("$label.$name") }

    private fun parseParameterAxes(value: AtlasJsonValue, label: String): Map<String, DoubleArray> =
        value.objectValues(label).mapValues { (name, item) ->
            item.arrayValues("$label.$name")
                .mapIndexed { index, coordinate -> coordinate.numberValue("$label.$name[$index]") }
                .toDoubleArray()
                .also { axis ->
                    require(axis.isNotEmpty() && (axis.size == 1 || axis.isStrictlyIncreasing())) {
                        "$label.$name is not strictly increasing"
                    }
                }
        }

    private fun parseTriggers(value: AtlasJsonValue, label: String): Set<AtlasRuntimeTrigger> {
        val result = value.arrayValues(label).mapIndexed { index, item ->
            val name = item.stringValue("$label[$index]")
            requireNotNull(AtlasRuntimeTrigger.entries.firstOrNull { it.name == name }) {
                "Unsupported core atlas trigger $name"
            }
        }
        require(result.distinct().size == result.size) { "$label contains duplicates" }
        return result.toSet()
    }

    private fun parsePerspectives(value: AtlasJsonValue, label: String): Set<EngineSoundPerspective> {
        val result = value.arrayValues(label).mapIndexed { index, item ->
            when (item.stringValue("$label[$index]")) {
                "cabin" -> EngineSoundPerspective.CABIN
                "exterior" -> EngineSoundPerspective.EXTERIOR
                else -> throw IllegalArgumentException("Unsupported atlas effect perspective")
            }
        }
        require(result.distinct().size == result.size) { "$label contains duplicates" }
        return result.toSet()
    }

    private fun parseShard(index: Int, value: AtlasJsonValue): AtlasShard {
        val label = "atlas.shards[$index]"
        val values = value.objectValues(label)
        require(values.keys == setOf("name", "sha256", "bytes")) {
            "$label has unsupported fields"
        }
        return AtlasShard(
            name = values.getValue("name").stringValue("$label.name"),
            sha256 = values.getValue("sha256").stringValue("$label.sha256"),
            bytes = values.getValue("bytes").longValue("$label.bytes"),
        )
    }

    private fun parseAndValidateModeRows(value: AtlasJsonValue) {
        val rows = value.objectValues("atlas.modeRows")
        require(rows.keys == setOf("LOAD", "COAST", "BOTH")) { "Atlas mode rows are incomplete" }
        val load = rows.getValue("LOAD").objectValues("atlas.modeRows.LOAD")
        val coast = rows.getValue("COAST").objectValues("atlas.modeRows.COAST")
        val both = rows.getValue("BOTH").objectValues("atlas.modeRows.BOTH")
        require(load.keys == setOf("throttle", "livePedalIgnored"))
        require(coast.keys == setOf("throttle", "livePedalIgnored"))
        require(both.keys == setOf("throttle"))
        require(load.getValue("throttle").numberValue("atlas.modeRows.LOAD.throttle") == 1.0)
        require(load.getValue("livePedalIgnored").booleanValue("atlas.modeRows.LOAD.livePedalIgnored"))
        require(coast.getValue("throttle").numberValue("atlas.modeRows.COAST.throttle") == 0.0)
        require(coast.getValue("livePedalIgnored").booleanValue("atlas.modeRows.COAST.livePedalIgnored"))
        require(both.getValue("throttle").stringValue("atlas.modeRows.BOTH.throttle") == "livePedal")
    }

    private fun parseAndValidateHotCellPolicy(value: AtlasJsonValue) {
        val policy = value.objectValues("atlas.hotCellPolicy")
        require(policy.getValue("maximumMappedLoopNodesPerPerspective")
            .intValue("atlas.hotCellPolicy.maximumMappedLoopNodesPerPerspective") == 4)
        require(policy.getValue("LOADOrCOASTMappedNodesPerPerspective")
            .intValue("atlas.hotCellPolicy.LOADOrCOASTMappedNodesPerPerspective") == 2)
        require(policy.getValue("BOTHMappedNodesPerPerspective")
            .intValue("atlas.hotCellPolicy.BOTHMappedNodesPerPerspective") == 4)
        require(policy.getValue("neighborSelection").stringValue("atlas.hotCellPolicy.neighborSelection") ==
            "binarySearchLowerUpperOnSortedAxes")
        require(policy.getValue("cellReplacement").stringValue("atlas.hotCellPolicy.cellReplacement") ==
            "hold the previous ready cell until its leaving node is clamped to zero at/after the boundary; " +
            "then prepare the entering node while its raw bilinear weight is zero")
        require(policy.getValue("wholeAtlasHeapDecodeForbidden")
            .booleanValue("atlas.hotCellPolicy.wholeAtlasHeapDecodeForbidden"))
        require(policy.getValue("packedWavAccess").stringValue("atlas.hotCellPolicy.packedWavAccess") ==
            "read-only mmap of PCM data chunk")
    }

    private fun parseInterpolationContract(value: AtlasJsonValue?): Boolean {
        if (value == null) return false
        val contract = value.objectValues("atlas.interpolationContract")
        require(contract.getValue("algorithm").stringValue("atlas.interpolationContract.algorithm") ==
            "phaseCoherentRootRpmBilinear-v1")
        val ratio = contract.getValue("nodeRootRpmPlaybackRatio")
            .objectValues("atlas.interpolationContract.nodeRootRpmPlaybackRatio")
        require(ratio.getValue("formula").stringValue("atlas.interpolationContract.ratio.formula") ==
            "targetRpm/nodeRpm")
        require(ratio.getValue("zeroRootRatio").numberValue("atlas.interpolationContract.ratio.zeroRootRatio") == 1.0)
        require(ratio.getValue("minimum").numberValue("atlas.interpolationContract.ratio.minimum") == 0.1)
        require(ratio.getValue("maximum").numberValue("atlas.interpolationContract.ratio.maximum") == 4.0)
        require(contract.getValue("phaseAlignment").stringValue("atlas.interpolationContract.phaseAlignment") ==
            "correlationAlignedLoopPhase")
        require(contract.getValue("phaseReference").stringValue("atlas.interpolationContract.phaseReference") ==
            "targetRpmNormalizedProgress")
        require(contract.getValue("crossfade").stringValue("atlas.interpolationContract.crossfade") ==
            "none; raw bilinear gains after zero-gain preparation")
        val activation = contract.getValue("activation").objectValues("atlas.interpolationContract.activation")
        require(activation.getValue("prepareOnlyAtZeroWeight")
            .booleanValue("atlas.interpolationContract.activation.prepareOnlyAtZeroWeight"))
        require(activation.getValue("gainFormula").stringValue("atlas.interpolationContract.activation.gainFormula") ==
            "rawBilinearWeight")
        require(activation.getValue("audibleRamp").stringValue("atlas.interpolationContract.activation.audibleRamp") == "none")
        require(activation.getValue("unreadyPolicy").stringValue("atlas.interpolationContract.activation.unreadyPolicy") ==
            "holdPreviousReadyCell")
        require(activation.getValue("neverMapMoreThanNodes")
            .intValue("atlas.interpolationContract.activation.neverMapMoreThanNodes") == 4)
        require(activation.getValue("mappedCellCorners")
            .stringValue("atlas.interpolationContract.activation.mappedCellCorners") ==
            "allUniqueLowerUpperRpmByLowerUpperThrottleIncludingZeroWeightNeighbors")
        require(activation.getValue("zeroWeightNeighborPhasePolicy")
            .stringValue("atlas.interpolationContract.activation.zeroWeightNeighborPhasePolicy") ==
            "correlationAlignAtCellCreationAndAdvanceEveryOutputFrame")
        val correlation = contract.getValue("correlation").objectValues("atlas.interpolationContract.correlation")
        require(correlation.getValue("channelScore").stringValue("atlas.interpolationContract.correlation.channelScore") ==
            "sumStereoDotProducts")
        require(correlation.getValue("windowFrames").intValue("atlas.interpolationContract.correlation.windowFrames") == 960)
        require(correlation.getValue("searchOffsetFrames")
            .intValue("atlas.interpolationContract.correlation.searchOffsetFrames") == 960)
        require(correlation.getValue("candidateAnchor").stringValue("atlas.interpolationContract.correlation.candidateAnchor") ==
            "loopStartPlusPhaseOffsetFrames")
        require(correlation.getValue("minimumRmsLinear")
            .numberValue("atlas.interpolationContract.correlation.minimumRmsLinear") == 0.001)
        require(correlation.getValue("tieBreak").stringValue("atlas.interpolationContract.correlation.tieBreak") ==
            "smallestAbsoluteOffsetThenNegative")
        require(correlation.getValue("coarseOffsetStrideFrames")
            .intValue("atlas.interpolationContract.correlation.coarseOffsetStrideFrames") == 8)
        require(correlation.getValue("coarseReferenceFrameStride")
            .intValue("atlas.interpolationContract.correlation.coarseReferenceFrameStride") == 4)
        require(correlation.getValue("fineSearchHalfWidthFrames")
            .intValue("atlas.interpolationContract.correlation.fineSearchHalfWidthFrames") == 8)
        require(correlation.getValue("fineReferenceFrameStride")
            .intValue("atlas.interpolationContract.correlation.fineReferenceFrameStride") == 1)
        require(correlation.getValue("offsetIteration")
            .stringValue("atlas.interpolationContract.correlation.offsetIteration") == "ascendingInclusive")
        require(correlation.getValue("coldStart").stringValue("atlas.interpolationContract.correlation.coldStart") ==
            "highestWeightCornerReferenceThenAlignEveryMappedCornerIncludingZeroWeight")
        require(contract.getValue("oracleStatus").stringValue("atlas.interpolationContract.oracleStatus") == "PASS")
        require(BydAudioPackManifest.isSha256(
            contract.getValue("oracleReportSha256").stringValue("atlas.interpolationContract.oracleReportSha256"),
        ))

        return true
    }

    private fun parseHostMixContract(value: AtlasJsonValue): AtlasHostMixContract {
        val contract = value.objectValues("atlas.hostMixContract")
        require(contract.getValue("schema").stringValue("atlas.hostMixContract.schema") ==
            "byd-full-event-atlas-host-mix-v1")
        val classes = contract.getValue("hostGainClasses").objectValues("atlas.hostMixContract.hostGainClasses")
        require(classes.keys == setOf("engineEvent", "effectEvent")) {
            "atlas.hostMixContract.hostGainClasses must declare exactly engineEvent and effectEvent"
        }
        fun parseClass(name: String, gain: Double, appliesTo: String) {
            val values = classes.getValue(name).objectValues("atlas.hostMixContract.hostGainClasses.$name")
            require(values.keys == setOf("gainLinear", "appliesTo")) {
                "atlas.hostMixContract.hostGainClasses.$name has an unsupported field"
            }
            require(values.getValue("gainLinear")
                .numberValue("atlas.hostMixContract.hostGainClasses.$name.gainLinear") == gain)
            require(values.getValue("appliesTo")
                .stringValue("atlas.hostMixContract.hostGainClasses.$name.appliesTo") == appliesTo)
        }
        parseClass(
            "engineEvent",
            gain = 0.5,
            appliesTo = "continuousEngineBedAndFiniteSourcesInsideSameEngineEventInstance",
        )
        parseClass(
            "effectEvent",
            gain = 1.0,
            appliesTo = "separatelyStartedNonEngineEffectEventInstances",
        )
        require(contract.getValue("requiresCombinedEngineEffectMixOracle")
            .booleanValue("atlas.hostMixContract.requiresCombinedEngineEffectMixOracle"))
        val master = contract.getValue("postSumMaster").objectValues("atlas.hostMixContract.postSumMaster")
        require(master.getValue("algorithm").stringValue("atlas.hostMixContract.postSumMaster.algorithm") ==
            "stereoLinkedCausalPeakLimiter-v1")
        require(master.getValue("ceilingLinear").numberValue("atlas.hostMixContract.postSumMaster.ceilingLinear") == 0.98)
        require(master.getValue("lookaheadFrames").intValue("atlas.hostMixContract.postSumMaster.lookaheadFrames") == 0)
        require(master.getValue("outputDelayFrames").intValue("atlas.hostMixContract.postSumMaster.outputDelayFrames") == 0)
        require(master.getValue("attackFrames").intValue("atlas.hostMixContract.postSumMaster.attackFrames") == 1)
        require(master.getValue("releaseFrames").intValue("atlas.hostMixContract.postSumMaster.releaseFrames") == 4_800)
        require(master.getValue("releaseStepPerFrame")
            .stringValue("atlas.hostMixContract.postSumMaster.releaseStepPerFrame") ==
            "(1.0-currentGain)/releaseFrames")
        require(master.getValue("preRoll").stringValue("atlas.hostMixContract.postSumMaster.preRoll") ==
            "none")
        require(master.getValue("blockState").stringValue("atlas.hostMixContract.postSumMaster.blockState") ==
            "continuousAcrossRenderBlocks")
        require(master.getValue("stopTail").stringValue("atlas.hostMixContract.postSumMaster.stopTail") ==
            "none")
        require(master.getValue("detector").stringValue("atlas.hostMixContract.postSumMaster.detector") ==
            "maxAbsoluteStereoSampleCurrentFrame")
        require(master.getValue("targetGain").stringValue("atlas.hostMixContract.postSumMaster.targetGain") ==
            "min(1.0,ceilingLinear/detectorPeak)")
        require(master.getValue("gainSmoothing").stringValue("atlas.hostMixContract.postSumMaster.gainSmoothing") ==
            "attackImmediateReleaseLinearTowardOne")

        return AtlasHostMixContract(
            engineEventHostGainLinear = 0.5,
            effectEventHostGainLinear = 1.0,
            limiterCeilingLinear = 0.98,
            limiterLookaheadFrames = 0,
            limiterAttackFrames = 1,
            limiterReleaseFrames = 4_800,
        )
    }
}

private fun DoubleArray.isStrictlyIncreasing(): Boolean =
    indices.drop(1).all { index -> this[index] > this[index - 1] && abs(this[index] - this[index - 1]) > 1.0e-9 }

private fun maximumAtlasEffectCorners(variant: AtlasEffectRuntimeVariant): Long {
    val changingAxes = variant.parameterAxes.values.count { it.size > 1 }
    require(changingAxes < Long.SIZE_BITS - 1) { "Atlas effect parameter grid is too large" }

    return 1L shl changingAxes
}

private fun totalAtlasEffectGridNodes(variant: AtlasEffectRuntimeVariant): Long =
    variant.parameterAxes.values.fold(1L) { product, axis ->
        require(product <= Long.MAX_VALUE / axis.size) { "Atlas effect parameter grid is too large" }
        product * axis.size
    }

internal fun isSafeAtlasShardName(value: String): Boolean =
    value.isNotEmpty() && value.length <= 120 && '/' !in value && '\\' !in value &&
        value != "." && value != ".." && value.endsWith(".wav")

private fun isSafeParameterName(value: String): Boolean =
    value.matches(Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$"))
