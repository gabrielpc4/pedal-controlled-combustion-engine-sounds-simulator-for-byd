package com.gabrielpc.enginesoundsimulator.audio

/**
 * Replays the non-bed event graph captured by the atlas.
 *
 * Finite nodes retain only their PCM16 attack caches while idle. Continuous current-corner regions
 * and triggered finite tails are mapped by background workers, atomically published when ready,
 * and released off the audio callback after deactivation. The callback only reads prepared loop
 * views and Float32 logical rings; it never opens, maps, warms, unmaps, allocates, or locks.
 */
internal class FullEventAtlasEffectsRenderer(
    private val atlasFamilyId: String,
    private val perspective: EngineSoundPerspective,
    private val program: FullEventAtlasProgram,
    private val fileResolver: AtlasShardFileResolver,
    private val controls: AtlasEffectControlModel,
    sharedSource: AudioAssetSource,
    private val finiteVoiceCapacity: Int = program.finiteRingPoolVoiceCapacity(perspective),
    private val sessionState: AtlasAudioSessionState? = null,
    private val perspectiveActivation: AtlasPerspectiveActivation? = null,
) : AutoCloseable {
    private val mappedShardBound = program.resourceBounds(perspective).effectMaximumMappedShardInstancesSafeUpperBound
    private val selectedEffectNodes = program.effects.asSequence()
        .filter { perspective in it.perspectives }
        .flatMap { event ->
            val authoredBindingKeys = event.variants.asSequence()
                .filter { perspective in it.perspectives }
                .mapTo(linkedSetOf(), AtlasEffectRuntimeVariant::authoredBindingKey)
            event.nodes.asSequence().filter {
                it.requiredAuthoredBindingKey in authoredBindingKeys
            }
        }
        .toList()
    private val regionFactory = MappedAtlasEffectPcmRegionFactory(
        nodes = selectedEffectNodes,
        fileResolver = fileResolver,
        maximumMappedShardsDuringTransition = mappedShardBound,
    )
    private val continuousGroupCount = program.effects.sumOf { event ->
        event.variants.asSequence()
            .filter { perspective in it.perspectives && it.lifetime == AtlasEffectLifetime.CONTINUOUS }
            .map { it.schedulingGroup.id }
            .distinct()
            .count()
    }
    private val continuousDispatcher = AtlasContinuousEffectDispatcher(continuousGroupCount)
    val meterTrackIds: List<String> = buildList {
        program.effects.filter { perspective in it.perspectives }.forEach { event ->
            event.variants.asSequence()
                .filter { perspective in it.perspectives }
                .flatMap { it.runtimeTriggers.asSequence() }
                .distinct()
                .forEach { trigger -> add(atlasEffectTrackId(event.eventSuffix, trigger)) }
        }
        if (sessionState == null) {
            add(SharedPopsAndBangs.EFFECT_ID)
            add(SharedHuracanShiftSounds.SHIFT_UP_ID)
            add(SharedHuracanShiftSounds.SHIFT_DOWN_ID)
        }
    }
    private val eventRuntimes: Array<EffectEventRuntime>
    private val continuousVoices: Array<ContinuousEffectVoice>
    private val finiteVoices: Array<FiniteEffectVoice>
    private var allocatedFiniteAttackCacheBytes = 0L
    private var oneShotStreams: AtlasOneShotStreamPool? = null
    private var voiceCapRejected = false
    private var loadProgramGain = 0.0
    private var coastProgramGain = 0.0
    private var loadProgramGainIgnoringSolo = 0.0
    private var coastProgramGainIgnoringSolo = 0.0
    private var currentAnySolo = false
    private val sharedOverrides = if (sessionState == null) AtlasSharedOverrides(sharedSource) else null

    init {
        require((sessionState == null) == (perspectiveActivation == null)) {
            "Atlas session state and perspective activation must be supplied together"
        }
        require(sessionState?.effectControls == null || sessionState.effectControls === controls) {
            "Perspective renderer cannot replace session-owned Audio Lab controls"
        }
        require(finiteVoiceCapacity in 0..ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP)
        try {
            eventRuntimes = program.effects
                .filter { perspective in it.perspectives }
                .map { event ->
                    val scopedAuthoredBindingKeys = event.variants.asSequence()
                        .filter { perspective in it.perspectives }
                        .mapTo(linkedSetOf(), AtlasEffectRuntimeVariant::authoredBindingKey)
                    val eventNodes = event.nodes.asSequence()
                        .filter { it.requiredAuthoredBindingKey in scopedAuthoredBindingKeys }
                        .map(::prepareNode)
                        .toList()
                        .toTypedArray()
                    EffectEventRuntime(event, eventNodes)
                }
                .toTypedArray()
            val resourceBound = program.resourceBounds(perspective)
            require(allocatedFiniteAttackCacheBytes == resourceBound.effectFiniteAttackCacheBytes) {
                "Atlas finite attack-cache allocation disagrees with the selected-perspective proof"
            }
            continuousVoices = Array(resourceBound.effectMaximumContinuousMmapPlaybackCornerVoices) {
                ContinuousEffectVoice()
            }
            val finiteGroups = program.effects.asSequence()
                .flatMap { event -> event.variants.asSequence() }
                .filter { variant ->
                    perspective in variant.perspectives && variant.lifetime != AtlasEffectLifetime.CONTINUOUS
                }
                .map(AtlasEffectRuntimeVariant::schedulingGroup)
                .distinct()
                .toList()
            val maximumContributorsPerLogicalRing = finiteGroups
                .maxOfOrNull(AtlasSchedulingGroup::maximumSourceCornerContributorsPerLogicalRing)
                ?: 0
            if (maximumContributorsPerLogicalRing > 0) {
                require(finiteVoiceCapacity > 0)
                val streams = AtlasOneShotStreamPool(
                    voiceCount = finiteVoiceCapacity,
                    ringFramesPerVoice = finiteGroups.maxOf(AtlasSchedulingGroup::streamingRingBufferFrames),
                    maximumContributorsPerVoice = maximumContributorsPerLogicalRing,
                )
                require(streams.allocatedRingBytes == resourceBound.effectFiniteRingPoolBytes) {
                    "Atlas finite Float32 ring allocation disagrees with the causal-tail proof"
                }
                oneShotStreams = streams
                finiteVoices = Array(finiteVoiceCapacity) { index ->
                    FiniteEffectVoice(index.toLong(), streams.voice(index))
                }
            } else {
                require(finiteVoiceCapacity == 0)
                finiteVoices = emptyArray()
            }
        } catch (error: Throwable) {
            oneShotStreams?.close()
            oneShotStreams = null
            continuousDispatcher.close()
            regionFactory.close()
            throw error
        }
    }

    /** Starts/stops semantic instances once per render block, then mixes every active voice. */
    fun render(
        target: EngineAudioFrame,
        selectedPerspectiveActive: Boolean,
        frameCount: Int,
        engineEventLeft: DoubleArray,
        engineEventRight: DoubleArray,
        effectEventLeft: DoubleArray,
        effectEventRight: DoubleArray,
        anySolo: Boolean,
        loadProgramGain: Double,
        coastProgramGain: Double,
        loadProgramGainIgnoringSolo: Double,
        coastProgramGainIgnoringSolo: Double,
    ) {
        require(frameCount <= engineEventLeft.size && frameCount <= engineEventRight.size &&
            frameCount <= effectEventLeft.size && frameCount <= effectEventRight.size)
        voiceCapRejected = false
        this.loadProgramGain = loadProgramGain
        this.coastProgramGain = coastProgramGain
        this.loadProgramGainIgnoringSolo = loadProgramGainIgnoringSolo
        this.coastProgramGainIgnoringSolo = coastProgramGainIgnoringSolo
        currentAnySolo = anySolo
        var eventIndex = 0
        while (eventIndex < eventRuntimes.size) {
            eventRuntimes[eventIndex].update(target, selectedPerspectiveActive)
            eventIndex += 1
        }
        sharedOverrides?.update(target, controls)
        continuousVoices.forEach {
            it.prepareMixerGain(target, anySolo)
        }
        finiteVoices.forEach {
            it.prepareMixerGain(target, anySolo)
        }
        var frame = 0
        while (frame < frameCount) {
            var voiceIndex = 0
            while (voiceIndex < continuousVoices.size) {
                val voice = continuousVoices[voiceIndex]
                if (voice.active) {
                    val gain = voice.renderGain
                    if (gain != 0.0) {
                        val left = voice.sample(0) * gain
                        val right = voice.sample(1) * gain
                        if (voice.hostGainClass == AtlasHostGainClass.ENGINE_EVENT) {
                            engineEventLeft[frame] += left
                            engineEventRight[frame] += right
                        } else {
                            effectEventLeft[frame] += left
                            effectEventRight[frame] += right
                        }
                    }
                    voice.advance()
                }
                voiceIndex += 1
            }
            voiceIndex = 0
            while (voiceIndex < finiteVoices.size) {
                val voice = finiteVoices[voiceIndex]
                if (voice.active) {
                    val gain = voice.renderGain
                    if (gain != 0.0) {
                        val left = voice.sample(0) * gain
                        val right = voice.sample(1) * gain
                        if (voice.hostGainClass == AtlasHostGainClass.ENGINE_EVENT) {
                            engineEventLeft[frame] += left
                            engineEventRight[frame] += right
                        } else {
                            effectEventLeft[frame] += left
                            effectEventRight[frame] += right
                        }
                    }
                    voice.advance()
                }
                voiceIndex += 1
            }
            sharedOverrides?.let { shared ->
                sharedFrame[0] = 0.0
                sharedFrame[1] = 0.0
                shared.mixFrame(target, sharedFrame, anySolo)
                effectEventLeft[frame] += sharedFrame[0]
                effectEventRight[frame] += sharedFrame[1]
            }
            frame += 1
        }
        finiteVoices.forEach(FiniteEffectVoice::commitSessionProgress)
        if (selectedPerspectiveActive) {
            eventRuntimes.forEach { it.advanceContinuousClocks(frameCount) }
        }
    }

    private val sharedFrame = DoubleArray(2)
    private val meterLevels = DoubleArray(meterTrackIds.size)
    private val meterIndexByTrackId = meterTrackIds.withIndex().associate { it.value to it.index }

    fun consumeVoiceCapRejected(): Boolean {
        val result = voiceCapRejected
        voiceCapRejected = false
        return result
    }

    /** Explicit diagnostic: finite PCM was not ready, so the realtime callback emitted silence. */
    fun consumeOneShotUnderrunFrameCount(): Int = oneShotStreams?.consumeUnderrunFrameCount() ?: 0

    /** Explicit diagnostic: a finite voice was still retiring when a new start was requested. */
    fun consumeOneShotStartRejectedCount(): Int = oneShotStreams?.consumeStartRejectedCount() ?: 0

    /** Off-callback mmap/read/prewarm failures; the previous continuous set remains audible. */
    fun consumePcmMaterializationFailureCount(): Int {
        var failures = oneShotStreams?.consumeMaterializationFailureCount() ?: 0
        eventRuntimes.forEach { failures += it.consumeMaterializationFailureCount() }

        return failures
    }

    fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) {
        require(destination.size - offset >= meterLevels.size)
        meterLevels.fill(0.0)
        continuousVoices.forEach { voice ->
            if (voice.active && voice.meterIndex >= 0) {
                meterLevels[voice.meterIndex] = maxOf(meterLevels[voice.meterIndex], voice.meterGain)
            }
        }
        finiteVoices.forEach { voice ->
            if (voice.active && voice.meterIndex >= 0) {
                meterLevels[voice.meterIndex] = maxOf(meterLevels[voice.meterIndex], voice.meterGain)
            }
        }
        sharedOverrides?.writeMeters(target, meterLevels, meterLevels.size - SHARED_METER_COUNT)
        meterLevels.copyInto(destination, destinationOffset = offset)
    }

    override fun close() {
        oneShotStreams?.close()
        oneShotStreams = null
        continuousDispatcher.close()
        regionFactory.close()
    }

    private fun prepareNode(node: AtlasEffectNode): PreparedEffectNode {
        val finiteSource = if (node.lifetime == AtlasEffectLifetime.CONTINUOUS) {
            null
        } else {
            regionFactory.prepareFiniteSource(node).also { source ->
                allocatedFiniteAttackCacheBytes += source.attackFrameCount * 2L * Short.SIZE_BYTES
            }
        }

        return PreparedEffectNode(node, finiteSource)
    }

    private inner class EffectEventRuntime(
        private val event: AtlasEffectEvent,
        eventNodes: Array<PreparedEffectNode>,
    ) {
        private val ownership = event.variants.mapTo(linkedSetOf()) { it.eventInstanceOwnership }.single()
        private val hasContinuousRuntime = event.variants.any { variant ->
            perspective in variant.perspectives && variant.lifetime == AtlasEffectLifetime.CONTINUOUS
        }
        private var blockActivation: AtlasEventActivation? = null
        private var activationStartedThisBlock = false
        private var selectedEngineActivationWasActive = false
        private var continuousActivationWasActive = false
        private val groups = event.variants
            .filter { perspective in it.perspectives }
            .groupBy { it.schedulingGroup.id }
            .map { (_, variants) ->
                EffectGroupRuntime(event, variants.toTypedArray(), eventNodes, this)
            }
            .toTypedArray()

        fun update(target: EngineAudioFrame, selectedPerspectiveActive: Boolean) {
            val existing = sessionState?.activationFor(event.eventPath)
            blockActivation = existing?.takeIf {
                selectedPerspectiveActive &&
                    sessionState?.acceptsSemanticUpdates(
                        event.eventPath,
                        requireNotNull(perspectiveActivation).rendererId,
                    ) == true &&
                    (hasContinuousRuntime || ownership.owner == AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE)
            }
            activationStartedThisBlock = false
            if (sessionState != null &&
                ownership.owner == AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE &&
                blockActivation == null &&
                selectedEngineActivationWasActive
            ) {
                groups.forEach(EffectGroupRuntime::stopEventInstance)
            }
            if (sessionState != null && hasContinuousRuntime && blockActivation == null &&
                continuousActivationWasActive
            ) {
                groups.forEach(EffectGroupRuntime::stopContinuousPlayback)
            }
            if (ownership.owner == AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE) {
                selectedEngineActivationWasActive = blockActivation != null
            }
            if (hasContinuousRuntime) continuousActivationWasActive = blockActivation != null
            if (!selectedPerspectiveActive) return
            blockActivation?.let { activation ->
                groups.forEach { group -> group.enterActivation(activation.generation) }
            }
            var groupIndex = 0
            while (groupIndex < groups.size) {
                groups[groupIndex].prepareParameterPlacementEntries()
                groupIndex += 1
            }
            if (sessionState != null && blockActivation == null) {
                var pendingTrigger: AtlasRuntimeTrigger? = null
                groupIndex = 0
                while (groupIndex < groups.size) {
                    val candidate = groups[groupIndex].firstPendingSemanticTrigger(target)
                    if (candidate != null && (pendingTrigger == null || candidate.ordinal < pendingTrigger.ordinal)) {
                        pendingTrigger = candidate
                    }
                    groupIndex += 1
                }
                if (pendingTrigger != null) allowSemanticStart(pendingTrigger)
            }
            groupIndex = 0
            while (groupIndex < groups.size) {
                groups[groupIndex].update(target)
                groupIndex += 1
            }
            val activation = blockActivation
            if (activationStartedThisBlock && activation != null && groups.none { it.hasActivePlayback() }) {
                sessionState?.abandonEmptyActivation(activation)
            }
            val persistentActivation = sessionState?.activationFor(event.eventPath)
            if (hasContinuousRuntime && persistentActivation != null &&
                sessionState.acceptsSemanticUpdates(
                    event.eventPath,
                    requireNotNull(perspectiveActivation).rendererId,
                )
            ) {
                sessionState.markContinuousOwnerActive(
                    persistentActivation,
                    groups.any { it.hasContinuousPlayback() },
                )
            }
        }

        fun allowSemanticStart(trigger: AtlasRuntimeTrigger): Boolean {
            val session = sessionState ?: return true
            val renderer = requireNotNull(perspectiveActivation)
            blockActivation?.let { activation ->
                groups.forEach { it.enterActivation(activation.generation) }

                return true
            }
            val existing = session.activationFor(event.eventPath)
            val activation = when (ownership.owner) {
                AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE -> {
                    if (existing == null ||
                        !session.acceptsSemanticUpdates(event.eventPath, renderer.rendererId)
                    ) return false
                    existing
                }
                AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT -> {
                    if (existing != null) {
                        if (!session.acceptsSemanticUpdates(event.eventPath, renderer.rendererId) ||
                            !hasContinuousRuntime
                        ) return false
                        existing
                    } else if (hasContinuousRuntime) {
                        activationStartedThisBlock = true
                        session.ensurePersistentActivation(event.eventPath, renderer.rendererId)
                    } else {
                        val exclusionGroup = if (trigger == AtlasRuntimeTrigger.THROTTLE_LIFT) {
                            HOST_BACKFIRE_INSTANCE_SET
                        } else {
                            null
                        }
                        when (session.tryStartFiniteActivationStatus(
                            event.eventPath,
                            renderer.rendererId,
                            exclusionGroup,
                        )) {
                            AtlasFiniteEventStartStatus.STARTED -> {
                                activationStartedThisBlock = true
                                requireNotNull(session.activationFor(event.eventPath))
                            }
                            AtlasFiniteEventStartStatus.IN_FLIGHT,
                            AtlasFiniteEventStartStatus.INACTIVE_PERSPECTIVE,
                            -> return false
                        }
                    }
                }
            }
            blockActivation = activation
            groups.forEach { it.enterActivation(activation.generation) }
            if (hasContinuousRuntime && trigger in CONTINUOUS_OWNER_TRIGGERS) {
                session.markContinuousOwnerActive(activation, true)
            }

            return true
        }

        fun currentActivation(): AtlasEventActivation? = blockActivation

        fun advanceContinuousClocks(frameCount: Int) {
            groups.forEach { it.advanceContinuousClocks(frameCount) }
        }

        fun consumeMaterializationFailureCount(): Int =
            groups.sumOf(EffectGroupRuntime::consumeMaterializationFailureCount)
    }

    private inner class EffectGroupRuntime(
        private val event: AtlasEffectEvent,
        private val variants: Array<AtlasEffectRuntimeVariant>,
        private val eventNodes: Array<PreparedEffectNode>,
        private val eventRuntime: EffectEventRuntime,
    ) {
        private val group = variants.first().schedulingGroup
        private val runtimeGroupId = "${event.eventPath}|${group.id}"
        private val scheduler = sessionState?.let { session ->
            AtlasEffectScheduler(
                atlasFamilyId,
                event.eventPath,
                session.profileAudioSessionGeneration,
                group,
                session.schedulerState(event.eventPath, group.id),
            )
        } ?: AtlasEffectScheduler(atlasFamilyId, event.eventPath, 1L, group)
        private val parameterNamesByVariant = Array(variants.size) { index ->
            variants[index].parameterAxes.keys.toTypedArray()
        }
        private val parameterAxesByVariant = Array(variants.size) { index ->
            Array(parameterNamesByVariant[index].size) { parameterIndex ->
                variants[index].parameterAxes.getValue(parameterNamesByVariant[index][parameterIndex])
            }
        }
        private val lowerCoordinatesByVariant = Array(variants.size) { index ->
            DoubleArray(parameterNamesByVariant[index].size)
        }
        private val upperCoordinatesByVariant = Array(variants.size) { index ->
            DoubleArray(parameterNamesByVariant[index].size)
        }
        private val fractionsByVariant = Array(variants.size) { index ->
            DoubleArray(parameterNamesByVariant[index].size)
        }
        private val groupNodes = buildList {
            eventNodes.forEach { node ->
                val variantIndex = variants.indexOfFirst {
                    it.authoredBindingKey == node.node.requiredAuthoredBindingKey
                }
                if (variantIndex >= 0) {
                    val parameterNames = parameterNamesByVariant[variantIndex]
                    add(
                        PreparedGroupNode(
                            prepared = node,
                            variantIndex = variantIndex,
                            coordinates = DoubleArray(parameterNames.size) { parameterIndex ->
                                node.node.parameters.getValue(parameterNames[parameterIndex])
                            },
                        ),
                    )
                }
            }
        }.toTypedArray()
        private val continuousVariants = variants.filter { it.lifetime == AtlasEffectLifetime.CONTINUOUS }
        private val maximumContinuousCorners = when {
            continuousVariants.isEmpty() -> 0
            group.composition == AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE ->
                continuousVariants.maxOf(::maximumCorners)
            else -> continuousVariants.sumOf(::maximumCorners)
        }
        private val continuousHotSet = if (maximumContinuousCorners > 0) {
            AtlasContinuousEffectHotSet(
                nodes = Array(groupNodes.size) { groupNodes[it].prepared.node },
                regionFactory = regionFactory,
                maximumCurrentCorners = maximumContinuousCorners,
                dispatcher = continuousDispatcher,
            )
        } else {
            null
        }
        private val requestedContinuousNodeIndices = IntArray(maximumContinuousCorners.coerceAtLeast(1))
        private var requestedContinuousGeneration = 0
        private var activeContinuousGeneration = 0
        private val meterIndices = IntArray(AtlasRuntimeTrigger.entries.size) { triggerIndex ->
            meterIndexByTrackId[atlasEffectTrackId(event.eventSuffix, AtlasRuntimeTrigger.entries[triggerIndex])] ?: -1
        }
        private var continuousStarted = false
        private val continuousSourceActive = BooleanArray(variants.size)
        private val continuousSourceClockFrames = LongArray(variants.size)
        private val placementStates = Array(variants.size) { index ->
            if (variants[index].parameterPlacementEntry != null) AtlasParameterPlacementState() else null
        }
        private val preparedPlacementMembership = BooleanArray(variants.size)
        private val eligibleVariants = BooleanArray(variants.size)
        private val contributorSources = arrayOfNulls<AtlasOneShotPcmSource>(
            group.maximumSourceCornerContributorsPerLogicalRing,
        )
        private val contributorGains = DoubleArray(contributorSources.size)
        private var observedActivationGeneration = Long.MIN_VALUE

        init {
            require(maximumContinuousCorners <= groupNodes.size)
        }

        fun update(target: EngineAudioFrame) {
            updateParameterPlacementEntries(target)
            var triggerIndex = 0
            while (triggerIndex < AtlasRuntimeTrigger.entries.size) {
                val trigger = AtlasRuntimeTrigger.entries[triggerIndex]
                if (trigger == AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY) {
                    triggerIndex += 1
                    continue
                }
                if (variants.any { trigger in it.runtimeTriggers }) {
                    if (isContinuous(trigger)) {
                        updateContinuous(trigger, target)
                    } else if (controls.isTriggered(trigger)) {
                        startTriggered(trigger, target)
                    }
                }
                triggerIndex += 1
            }
        }

        fun prepareParameterPlacementEntries() {
            var index = 0
            while (index < variants.size) {
                val placement = variants[index].parameterPlacementEntry
                preparedPlacementMembership[index] = placement != null && placementContains(placement)
                index += 1
            }
        }

        fun firstPendingSemanticTrigger(target: EngineAudioFrame): AtlasRuntimeTrigger? {
            var index = 0
            while (index < variants.size) {
                val state = placementStates[index]
                if (state != null && state.wouldEnter(preparedPlacementMembership[index]) &&
                    effectEnabled(target, AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY)
                ) {
                    return AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY
                }
                index += 1
            }
            var triggerIndex = 0
            while (triggerIndex < AtlasRuntimeTrigger.entries.size) {
                val trigger = AtlasRuntimeTrigger.entries[triggerIndex]
                if (trigger != AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY &&
                    variants.any { trigger in it.runtimeTriggers } && effectEnabled(target, trigger)
                ) {
                    if (isContinuous(trigger)) {
                        if (!continuousStarted && controls.isContinuousActive(trigger)) return trigger
                    } else if (controls.isTriggered(trigger)) {
                        return trigger
                    }
                }
                triggerIndex += 1
            }

            return null
        }

        private fun isContinuous(trigger: AtlasRuntimeTrigger): Boolean =
            variants.any { trigger in it.runtimeTriggers && it.lifetime == AtlasEffectLifetime.CONTINUOUS }

        private fun updateContinuous(trigger: AtlasRuntimeTrigger, target: EngineAudioFrame) {
            val active = controls.isContinuousActive(trigger) && effectEnabled(target, trigger)
            if (!active) {
                if (continuousStarted || requestedContinuousGeneration != 0 || activeContinuousGeneration != 0) {
                    stopContinuous(runtimeGroupId)
                }
                continuousStarted = false
                return
            }
            if (!continuousStarted) {
                startTriggered(trigger, target)
                continuousStarted = continuousSourceActive.any { it }
                if (!continuousStarted) return
            }
            refreshContinuousCorners(trigger)
        }

        private fun startTriggered(trigger: AtlasRuntimeTrigger, target: EngineAudioFrame) {
            if (!effectEnabled(target, trigger)) return
            eligibleVariants.fill(false)
            var variantIndex = 0
            while (variantIndex < variants.size) {
                eligibleVariants[variantIndex] = trigger in variants[variantIndex].runtimeTriggers
                variantIndex += 1
            }
            startEligible(trigger, target)
        }

        private fun startEligible(trigger: AtlasRuntimeTrigger, target: EngineAudioFrame) {
            if (!effectEnabled(target, trigger)) return
            if (!eventRuntime.allowSemanticStart(trigger)) return
            if (group.composition == AtlasSchedulingComposition.SIMULTANEOUS_LAYER) {
                var firstEligibleIndex = -1
                var index = 0
                while (index < eligibleVariants.size && firstEligibleIndex < 0) {
                    if (eligibleVariants[index]) firstEligibleIndex = index
                    index += 1
                }
                if (firstEligibleIndex < 0 || !scheduler.beginSimultaneousTrigger()) return
                if (variants[firstEligibleIndex].lifetime == AtlasEffectLifetime.CONTINUOUS) {
                    index = 0
                    while (index < variants.size) {
                        if (eligibleVariants[index] && variants[index].lifetime == AtlasEffectLifetime.CONTINUOUS) {
                            startContinuousVariant(variants[index])
                        }
                        index += 1
                    }
                } else {
                    startFiniteVariants(trigger, selectedPlaylistIndex = -1, target = target)
                }
            } else {
                val selected = scheduler.selectMember()
                if (selected == AtlasEffectScheduler.NO_SELECTION) return
                val sourceGuid = group.members[selected].sourceGuid
                val variantIndex = variants.indexOfFirst { it.sourceGuid == sourceGuid }
                if (variantIndex < 0 || !eligibleVariants[variantIndex]) return
                val variant = variants[variantIndex]
                if (variant.lifetime == AtlasEffectLifetime.CONTINUOUS) {
                    startContinuousVariant(variant)
                } else {
                    startFiniteVariants(trigger, selectedPlaylistIndex = variantIndex, target = target)
                }
            }
        }

        private fun startFiniteVariants(
            trigger: AtlasRuntimeTrigger,
            selectedPlaylistIndex: Int,
            target: EngineAudioFrame,
        ) {
            var contributorCount = 0
            var maximumCornerGain = 0.0
            var variantIndex = 0
            while (variantIndex < variants.size) {
                val selected = if (selectedPlaylistIndex >= 0) {
                    variantIndex == selectedPlaylistIndex
                } else {
                    eligibleVariants[variantIndex]
                }
                val variant = variants[variantIndex]
                if (selected && variant.lifetime != AtlasEffectLifetime.CONTINUOUS) {
                    prepareInterpolation(variantIndex, trigger)
                    var candidateIndex = 0
                    while (candidateIndex < groupNodes.size) {
                        val groupNode = groupNodes[candidateIndex]
                        val candidate = groupNode.prepared
                        if (groupNode.variantIndex == variantIndex) {
                            val interpolationGain = preparedCornerGain(variantIndex, groupNode.coordinates)
                            if (interpolationGain > 0.0) {
                                check(contributorCount < contributorSources.size) {
                                    "Atlas finite contributor proof drift in $runtimeGroupId"
                                }
                                val programGain = if (variant.hostGainClass == AtlasHostGainClass.ENGINE_EVENT) {
                                    engineContributorProgramGain(
                                        variant,
                                        target,
                                        meterIndex(trigger),
                                    )
                                } else {
                                    1.0
                                }
                                val gain = interpolationGain * programGain
                                contributorSources[contributorCount] = requireNotNull(candidate.finiteSource)
                                contributorGains[contributorCount] = gain
                                maximumCornerGain = maxOf(maximumCornerGain, kotlin.math.abs(gain))
                                contributorCount += 1
                            }
                        }
                        candidateIndex += 1
                    }
                }
                variantIndex += 1
            }
            if (contributorCount == 0) return
            val slot = findFiniteSlot() ?: run {
                voiceCapRejected = true
                return
            }
            check(slot.start(
                sources = contributorSources,
                gains = contributorGains,
                contributorCount = contributorCount,
                trigger = trigger,
                groupId = runtimeGroupId,
                hostGainClass = variants.first().hostGainClass,
                maximumCornerGain = maximumCornerGain,
                meterIndex = meterIndex(trigger),
                activation = eventRuntime.currentActivation(),
            )) { "Atlas finite logical-ring slot became unavailable" }
            var clearIndex = 0
            while (clearIndex < contributorCount) {
                contributorSources[clearIndex] = null
                contributorGains[clearIndex] = 0.0
                clearIndex += 1
            }
        }

        private fun startContinuousVariant(variant: AtlasEffectRuntimeVariant) {
            val variantIndex = variants.indexOfFirst { it === variant }
            if (variantIndex >= 0) continuousSourceActive[variantIndex] = true
        }

        private fun parameterValue(
            variant: AtlasEffectRuntimeVariant,
            parameter: String,
            trigger: AtlasRuntimeTrigger,
        ): Double {
            var binding: AtlasHostParameterBinding? = null
            var bindingIndex = 0
            while (bindingIndex < variant.hostParameterBindings.size) {
                val candidate = variant.hostParameterBindings[bindingIndex]
                if (candidate.parameter == parameter) {
                    binding = candidate
                    break
                }
                bindingIndex += 1
            }
            return when (val value = binding?.value) {
                is AtlasHostParameterValue.Source -> controls.parameter(value.name.toRuntimeParameterName(), trigger)
                is AtlasHostParameterValue.Constant -> value.value
                null -> variant.parameters.getValue(parameter)
            }
        }

        private fun refreshContinuousCorners(trigger: AtlasRuntimeTrigger) {
            val hotSet = requireNotNull(continuousHotSet)
            var variantIndex = 0
            while (variantIndex < variants.size) {
                if (continuousSourceActive[variantIndex]) prepareInterpolation(variantIndex, trigger)
                variantIndex += 1
            }
            var requestedCount = 0
            var nodeIndex = 0
            while (nodeIndex < groupNodes.size) {
                val groupNode = groupNodes[nodeIndex]
                val gain = if (continuousSourceActive[groupNode.variantIndex]) {
                    preparedCornerGain(groupNode.variantIndex, groupNode.coordinates)
                } else {
                    0.0
                }
                if (gain > 0.0) {
                    check(requestedCount < requestedContinuousNodeIndices.size) {
                        "Atlas continuous corner proof drift in $runtimeGroupId"
                    }
                    requestedContinuousNodeIndices[requestedCount] = nodeIndex
                    requestedCount += 1
                }
                nodeIndex += 1
            }

            if (requestedCount == 0) {
                if (requestedContinuousGeneration != 0 || activeContinuousGeneration != 0) {
                    stopContinuousVoices(runtimeGroupId)
                    hotSet.deactivate()
                    requestedContinuousGeneration = 0
                    activeContinuousGeneration = 0
                }
                return
            }

            val generation = hotSet.request(requestedContinuousNodeIndices, requestedCount)
            requestedContinuousGeneration = generation
            val ready = hotSet.readyFor(generation) ?: return // Retain the previous ready set.
            if (activeContinuousGeneration != generation) {
                if (!hasContinuousCapacityForSwap(ready.count)) {
                    voiceCapRejected = true
                    return
                }
                stopContinuousVoices(runtimeGroupId)
                startReadyContinuousSet(ready, trigger)
                activeContinuousGeneration = generation
                hotSet.acknowledge(generation)
            } else {
                refreshActiveContinuousGains()
            }
        }

        private fun startReadyContinuousSet(
            ready: AtlasContinuousEffectHotSet.ReadySet,
            trigger: AtlasRuntimeTrigger,
        ) {
            var readyIndex = 0
            while (readyIndex < ready.count) {
                val groupNode = groupNodes[ready.nodeIndices[readyIndex]]
                val variant = variants[groupNode.variantIndex]
                val slot = requireNotNull(findContinuousSlot())
                slot.start(
                    region = requireNotNull(ready.regions[readyIndex]),
                    coordinates = groupNode.coordinates,
                    variant = variant,
                    variantIndex = groupNode.variantIndex,
                    trigger = trigger,
                    groupId = runtimeGroupId,
                    baseGain = preparedCornerGain(groupNode.variantIndex, groupNode.coordinates),
                    meterIndex = meterIndex(trigger),
                    sourceClockFrames = sourceClockFrames(variant),
                )
                readyIndex += 1
            }
        }

        private fun refreshActiveContinuousGains() {
            var voiceIndex = 0
            while (voiceIndex < continuousVoices.size) {
                val voice = continuousVoices[voiceIndex]
                if (voice.active && voice.groupId == runtimeGroupId) {
                    voice.baseGain = preparedCornerGain(voice.variantIndex, voice.coordinates)
                }
                voiceIndex += 1
            }
        }

        private fun hasContinuousCapacityForSwap(replacementCount: Int): Boolean {
            var reusable = 0
            var voiceIndex = 0
            while (voiceIndex < continuousVoices.size) {
                val voice = continuousVoices[voiceIndex]
                if (!voice.active || voice.groupId == runtimeGroupId) reusable += 1
                voiceIndex += 1
            }

            return reusable >= replacementCount
        }

        private fun prepareInterpolation(variantIndex: Int, trigger: AtlasRuntimeTrigger) {
            val variant = variants[variantIndex]
            val parameterNames = parameterNamesByVariant[variantIndex]
            val parameterAxes = parameterAxesByVariant[variantIndex]
            val lowerCoordinates = lowerCoordinatesByVariant[variantIndex]
            val upperCoordinates = upperCoordinatesByVariant[variantIndex]
            val fractions = fractionsByVariant[variantIndex]
            var index = 0
            while (index < parameterNames.size) {
                val axis = parameterAxes[index]
                val value = parameterValue(variant, parameterNames[index], trigger)
                    .coerceIn(axis.first(), axis.last())
                val lowerIndex = atlasLowerAxisIndex(axis, value)
                val upperIndex = minOf(lowerIndex + 1, axis.lastIndex)
                val lower = axis[lowerIndex]
                val upper = axis[upperIndex]
                lowerCoordinates[index] = lower
                upperCoordinates[index] = upper
                fractions[index] = if (upper == lower) 0.0 else (value - lower) / (upper - lower)
                index += 1
            }
        }

        private fun preparedCornerGain(variantIndex: Int, coordinates: DoubleArray): Double {
            val lowerCoordinates = lowerCoordinatesByVariant[variantIndex]
            val upperCoordinates = upperCoordinatesByVariant[variantIndex]
            val fractions = fractionsByVariant[variantIndex]
            var gain = 1.0
            var index = 0
            while (index < coordinates.size) {
                gain *= when (coordinates[index]) {
                    lowerCoordinates[index] -> 1.0 - fractions[index]
                    upperCoordinates[index] -> fractions[index]
                    else -> return 0.0
                }
                index += 1
            }

            return gain
        }


        private fun stopContinuous(groupId: String) {
            stopContinuousVoices(groupId)
            continuousHotSet?.deactivate()
            requestedContinuousGeneration = 0
            activeContinuousGeneration = 0
            continuousSourceActive.fill(false)
            continuousSourceClockFrames.fill(0L)
        }

        private fun stopContinuousVoices(groupId: String) {
            var voiceIndex = 0
            while (voiceIndex < continuousVoices.size) {
                val voice = continuousVoices[voiceIndex]
                if (voice.active && voice.groupId == groupId) voice.stop()
                voiceIndex += 1
            }
        }

        private fun sourceClockFrames(variant: AtlasEffectRuntimeVariant): Long {
            val activation = eventRuntime.currentActivation()
            if (activation != null && sessionState != null) {
                return sessionState.continuousClockFrames(activation, variant.authoredBindingKey)
            }

            return continuousSourceClockFrames[variants.indexOfFirst { it === variant }.coerceAtLeast(0)]
        }

        fun advanceContinuousClocks(frameCount: Int) {
            continuousSourceClockFrames.indices.forEach { index ->
                if (continuousSourceActive[index]) {
                    val activation = eventRuntime.currentActivation()
                    if (activation != null && sessionState != null) {
                        sessionState.advanceContinuousClock(
                            activation,
                            variants[index].authoredBindingKey,
                            frameCount,
                        )
                    } else {
                        continuousSourceClockFrames[index] += frameCount
                    }
                }
            }
        }

        fun consumeMaterializationFailureCount(): Int = continuousHotSet?.consumeFailureCount() ?: 0

        fun enterActivation(generation: Long) {
            if (generation == observedActivationGeneration) return
            observedActivationGeneration = generation
            stopEventInstance()
            scheduler.enterActivation(generation)
            placementStates.forEach { state -> state?.reset() }
        }

        fun stopEventInstance() {
            stopContinuous(runtimeGroupId)
            var voiceIndex = 0
            while (voiceIndex < finiteVoices.size) {
                val voice = finiteVoices[voiceIndex]
                if (voice.active && voice.groupId == runtimeGroupId) voice.cancel()
                voiceIndex += 1
            }
        }

        fun stopContinuousPlayback() {
            if (maximumContinuousCorners > 0) stopContinuous(runtimeGroupId)
        }

        fun hasActivePlayback(): Boolean =
            hasContinuousPlayback() ||
                finiteVoices.any { voice -> voice.active && voice.groupId == runtimeGroupId }

        fun hasContinuousPlayback(): Boolean =
            continuousStarted ||
                requestedContinuousGeneration != 0 ||
                activeContinuousGeneration != 0 ||
                continuousVoices.any { voice -> voice.active && voice.groupId == runtimeGroupId }

        private fun maximumCorners(variant: AtlasEffectRuntimeVariant): Int {
            var corners = 1
            variant.parameterAxes.values.forEach { axis ->
                corners = Math.multiplyExact(corners, minOf(axis.size, 2))
            }

            return corners
        }

        private fun findContinuousSlot(): ContinuousEffectVoice? = continuousVoices.firstOrNull { !it.active }

        private fun findFiniteSlot(): FiniteEffectVoice? = finiteVoices.firstOrNull { !it.active && it.readyForStart }

        private fun updateParameterPlacementEntries(target: EngineAudioFrame) {
            eligibleVariants.fill(false)
            var hasEntry = false
            var index = 0
            while (index < variants.size) {
                val variant = variants[index]
                val placement = variant.parameterPlacementEntry
                if (placement != null) {
                    val entered = requireNotNull(placementStates[index]).update(
                        preparedPlacementMembership[index],
                    )
                    eligibleVariants[index] = entered
                    hasEntry = hasEntry || entered
                }
                index += 1
            }
            if (hasEntry && effectEnabled(target, AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY)) {
                startEligible(AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY, target)
            }
        }

        private fun placementContains(placement: AtlasParameterPlacementEntry): Boolean {
            var axisIndex = 0
            while (axisIndex < placement.axes.size) {
                val axis = placement.axes[axisIndex]
                val value = when (val placementValue = axis.value) {
                    is AtlasHostParameterValue.Source -> controls.parameter(
                        placementValue.name.toRuntimeParameterName(),
                        AtlasRuntimeTrigger.PARAMETER_PLACEMENT_ENTRY,
                    )
                    is AtlasHostParameterValue.Constant -> placementValue.value
                }
                var spanIndex = 0
                while (spanIndex < axis.spans.size) {
                    if (!axis.spans[spanIndex].contains(value)) return false
                    spanIndex += 1
                }
                axisIndex += 1
            }
            return true
        }

        private fun meterIndex(trigger: AtlasRuntimeTrigger): Int = meterIndices[trigger.ordinal]

        private fun engineContributorProgramGain(
            variant: AtlasEffectRuntimeVariant,
            target: EngineAudioFrame,
            meterIndex: Int,
        ): Double {
            val trackId = meterTrackIds.getOrNull(meterIndex) ?: ""
            val effectControl = target.layerMix[trackId]
            val ignoreSolo = effectControl?.solo == true && !effectControl.muted
            return atlasEngineContributorProgramGain(
                role = requireNotNull(variant.engineProgramRole),
                loadGain = if (ignoreSolo) loadProgramGainIgnoringSolo else loadProgramGain,
                coastGain = if (ignoreSolo) coastProgramGainIgnoringSolo else coastProgramGain,
                unaffectedGain = if (ignoreSolo || !currentAnySolo) 1.0 else 0.0,
            )
        }
    }

    private inner class ContinuousEffectVoice {
        var active = false
        var sourceGuid = ""
        var groupId = ""
        lateinit var region: AtlasEffectPcmRegion
        lateinit var variant: AtlasEffectRuntimeVariant
        var variantIndex = -1
        lateinit var coordinates: DoubleArray
        var meterIndex = -1
        lateinit var hostGainClass: AtlasHostGainClass
        var renderGain = 0.0
            private set
        var meterGain = 0.0
            private set
        private var trigger = AtlasRuntimeTrigger.ENGINE_EVENT_START
        var phase = 0.0
            private set

        var baseGain = 1.0

        fun start(
            region: AtlasEffectPcmRegion,
            coordinates: DoubleArray,
            variant: AtlasEffectRuntimeVariant,
            variantIndex: Int,
            trigger: AtlasRuntimeTrigger,
            groupId: String,
            baseGain: Double,
            meterIndex: Int,
            sourceClockFrames: Long = 0L,
        ): Boolean {
            this.region = region
            this.variant = variant
            this.variantIndex = variantIndex
            this.coordinates = coordinates
            this.trigger = trigger
            this.groupId = groupId
            sourceGuid = variant.sourceGuid
            this.baseGain = baseGain
            this.meterIndex = meterIndex
            hostGainClass = variant.hostGainClass
            val loopLength = region.loopEndExclusive - region.loopStart
            phase = region.loopStart + (sourceClockFrames % loopLength).toDouble()
            active = true
            return true
        }

        fun stop() {
            active = false
        }

        fun prepareMixerGain(
            target: EngineAudioFrame,
            anySolo: Boolean,
        ) {
            val trackId = effectTrackIdForVoice()
            val programGain = if (hostGainClass == AtlasHostGainClass.ENGINE_EVENT) {
                val effectControl = target.layerMix[trackId]
                val ignoreSolo = effectControl?.solo == true && !effectControl.muted
                atlasEngineContributorProgramGain(
                    role = requireNotNull(variant.engineProgramRole),
                    loadGain = if (ignoreSolo) loadProgramGainIgnoringSolo else loadProgramGain,
                    coastGain = if (ignoreSolo) coastProgramGainIgnoringSolo else coastProgramGain,
                    unaffectedGain = if (ignoreSolo || !anySolo) 1.0 else 0.0,
                )
            } else {
                1.0
            }
            val common = voiceCommonGain(
                target,
                anySolo,
                hostGainClass,
                trigger,
                trackId,
                engineProgramAudible = programGain != 0.0,
            )
            renderGain = baseGain * programGain * common
            meterGain = renderGain.coerceIn(0.0, 1.0)
        }

        private fun effectTrackIdForVoice(): String = meterTrackIds.getOrNull(meterIndex) ?: ""

        fun sample(channel: Int): Double = region.sample(phase.toInt(), channel)

        fun advance() {
            phase += 1.0
            if (phase >= region.loopEndExclusive) {
                val loopLength = region.loopEndExclusive - region.loopStart
                phase = region.loopStart + (phase - region.loopEndExclusive) % loopLength
            }
        }
    }

    private inner class FiniteEffectVoice(
        private val tailId: Long,
        private val stream: AtlasOneShotStreamPool.Voice,
    ) {
        var active = false
            private set
        var groupId = ""
            private set
        var meterIndex = -1
            private set
        lateinit var hostGainClass: AtlasHostGainClass
            private set
        private var trigger = AtlasRuntimeTrigger.ENGINE_EVENT_START
        private var maximumCornerGain = 1.0
        private var sessionActivation: AtlasEventActivation? = null
        private var sessionTailSlot = NO_SESSION_TAIL_SLOT
        private var pendingSessionAdvanceFrames = 0
        var renderGain = 0.0
            private set
        var meterGain = 0.0
            private set

        val readyForStart: Boolean get() = stream.readyForStart

        fun start(
            sources: Array<AtlasOneShotPcmSource?>,
            gains: DoubleArray,
            contributorCount: Int,
            trigger: AtlasRuntimeTrigger,
            groupId: String,
            hostGainClass: AtlasHostGainClass,
            maximumCornerGain: Double,
            meterIndex: Int,
            activation: AtlasEventActivation?,
        ): Boolean {
            if (!stream.begin(sources, gains, contributorCount)) return false
            this.trigger = trigger
            this.groupId = groupId
            this.hostGainClass = hostGainClass
            this.maximumCornerGain = maximumCornerGain
            this.meterIndex = meterIndex
            sessionActivation = activation
            sessionTailSlot = activation?.let { started ->
                sessionState?.retainFiniteTailSlot(started, tailId)
            } ?: NO_SESSION_TAIL_SLOT
            pendingSessionAdvanceFrames = 0
            active = true
            return true
        }

        fun prepareMixerGain(
            target: EngineAudioFrame,
            anySolo: Boolean,
        ) {
            val trackId = meterTrackIds.getOrNull(meterIndex) ?: ""
            renderGain = voiceCommonGain(
                target,
                anySolo,
                hostGainClass,
                trigger,
                trackId,
                engineProgramAudible = maximumCornerGain != 0.0,
            )
            meterGain = (renderGain * maximumCornerGain).coerceIn(0.0, 1.0)
        }

        fun sample(channel: Int): Double = stream.sample(channel)

        fun advance() {
            if (sessionTailSlot != NO_SESSION_TAIL_SLOT) pendingSessionAdvanceFrames += 1
            stream.advance()
            active = stream.active
        }

        fun commitSessionProgress() {
            val activation = sessionActivation ?: return
            if (pendingSessionAdvanceFrames > 0) {
                sessionState?.advanceFiniteTailSlot(
                    activation,
                    tailId,
                    sessionTailSlot,
                    pendingSessionAdvanceFrames,
                )
                pendingSessionAdvanceFrames = 0
            }
            if (!active) {
                sessionState?.completeFiniteTailSlot(activation, tailId, sessionTailSlot)
                sessionActivation = null
                sessionTailSlot = NO_SESSION_TAIL_SLOT
            }
        }

        fun cancel() {
            stream.cancel()
            active = false
            sessionActivation?.let { activation ->
                sessionState?.completeFiniteTailSlot(activation, tailId, sessionTailSlot)
            }
            sessionActivation = null
            sessionTailSlot = NO_SESSION_TAIL_SLOT
            pendingSessionAdvanceFrames = 0
        }
    }

    private fun voiceCommonGain(
        target: EngineAudioFrame,
        anySolo: Boolean,
        hostGainClass: AtlasHostGainClass,
        trigger: AtlasRuntimeTrigger,
        trackId: String,
        engineProgramAudible: Boolean,
    ): Double {
        if (!target.enabled) return 0.0
        val control = target.layerMix[trackId] ?: LayerMixControl.DEFAULT
        if (control.muted) return 0.0
        if (hostGainClass == AtlasHostGainClass.EFFECT_EVENT && anySolo && !control.solo) return 0.0
        if (hostGainClass == AtlasHostGainClass.ENGINE_EVENT && anySolo && !control.solo && !engineProgramAudible) {
            return 0.0
        }
        val featureGain = when (trigger) {
            AtlasRuntimeTrigger.TURBO_LOOP,
            AtlasRuntimeTrigger.TURBO_DUMP,
            -> if (target.turboSoundsEnabled) {
                target.turboSoundsGain.coerceIn(
                    EngineAudioFrame.MIN_TURBO_SOUNDS_GAIN,
                    EngineAudioFrame.MAX_EFFECT_GAIN,
                )
            } else 0.0
            AtlasRuntimeTrigger.TRANSMISSION_LOOP,
            AtlasRuntimeTrigger.TRANSMISSION_PULSE,
            -> if (target.transmissionEnabled) {
                target.transmissionGain.coerceIn(EngineAudioFrame.MIN_EFFECT_GAIN, EngineAudioFrame.MAX_EFFECT_GAIN)
            } else 0.0
            AtlasRuntimeTrigger.THROTTLE_LIFT ->
                if (target.throttleLiftEffectsEnabled && !target.popsAndBangsEnabled) 1.0 else 0.0
            AtlasRuntimeTrigger.SHIFT_UP,
            AtlasRuntimeTrigger.SHIFT_DOWN,
            -> if (target.sharedShiftSoundsEnabled) 0.0 else 1.0
            else -> 1.0
        }
        return control.volume.coerceIn(0.0, LayerMixControl.MAX_GAIN_MULTIPLIER) * featureGain
    }

    private data class PreparedEffectNode(
        val node: AtlasEffectNode,
        val finiteSource: AtlasOneShotPcmSource?,
    )
    private data class PreparedGroupNode(
        val prepared: PreparedEffectNode,
        val variantIndex: Int,
        val coordinates: DoubleArray,
    )

    private fun effectEnabled(
        target: EngineAudioFrame,
        trigger: AtlasRuntimeTrigger? = null,
    ): Boolean = when {
        trigger == AtlasRuntimeTrigger.TURBO_LOOP || trigger == AtlasRuntimeTrigger.TURBO_DUMP -> controls.hasTurbo
        trigger == AtlasRuntimeTrigger.THROTTLE_LIFT ->
            target.throttleLiftEffectsEnabled && !target.popsAndBangsEnabled
        !atlasNativeShiftTriggerEnabled(trigger, target.sharedShiftSoundsEnabled) -> false
        else -> true
    }

    private fun String.toRuntimeParameterName(): String = when (this) {
        "EngineSimulation.rpm" -> "rpms"
        "EngineSimulation.drivetrainSpeed" -> "drivetrain_speed"
        "EngineSimulation.gearState" -> "state"
        "EngineSimulation.turboBoost" -> "boost"
        "EngineSimulation.turboBov" -> "bov"
        "EngineSimulation.turboBovDecay" -> "bov_decay"
        "EngineSimulation.limiterDecay", "EngineSimulation.tractionDecay" -> "decay"
        "EngineSimulation.throttle" -> "throttle"
        else -> error("Unsupported host parameter source $this")
    }

    private fun lowerAxisIndex(axis: DoubleArray, value: Double): Int {
        var low = 0
        var high = axis.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (axis[middle] <= value) low = middle else high = middle - 1
        }
        return low
    }

    private companion object {
        const val SHARED_METER_COUNT = 3
        const val NO_SESSION_TAIL_SLOT = -1
        const val ASSETTO_STUDIO_LOGICAL_CHANNEL_CAP = 2_048
        const val HOST_BACKFIRE_INSTANCE_SET = "host:backfire_int_or_ext_playing"
        val CONTINUOUS_OWNER_TRIGGERS = setOf(
            AtlasRuntimeTrigger.TRANSMISSION_LOOP,
            AtlasRuntimeTrigger.TURBO_LOOP,
            AtlasRuntimeTrigger.LIMITER_LOOP,
            AtlasRuntimeTrigger.TRACTION_LIMIT,
        )
    }
}

internal fun atlasEffectTrackId(eventSuffix: String, trigger: AtlasRuntimeTrigger): String =
    "atlas_effect_${eventSuffix}_${trigger.name.lowercase()}"

internal fun atlasNativeShiftTriggerEnabled(
    trigger: AtlasRuntimeTrigger?,
    sharedShiftSoundsEnabled: Boolean,
): Boolean = !sharedShiftSoundsEnabled ||
    (trigger != AtlasRuntimeTrigger.SHIFT_UP && trigger != AtlasRuntimeTrigger.SHIFT_DOWN)

/** Pure corner contract shared by the realtime scheduler and deterministic boundary tests. */
internal fun atlasIsCurrentContinuousCorner(
    axes: Map<String, DoubleArray>,
    coordinates: Map<String, Double>,
    valueFor: (String) -> Double,
): Boolean = coordinates.all { (parameter, coordinate) ->
    val axis = axes.getValue(parameter)
    val value = valueFor(parameter).coerceIn(axis.first(), axis.last())
    val lowerIndex = atlasLowerAxisIndex(axis, value)
    coordinate == axis[lowerIndex] || coordinate == axis[minOf(lowerIndex + 1, axis.lastIndex)]
}

internal fun atlasContinuousCornerGain(
    axes: Map<String, DoubleArray>,
    coordinates: Map<String, Double>,
    valueFor: (String) -> Double,
): Double {
    var gain = 1.0
    coordinates.forEach { (parameter, coordinate) ->
        val axis = axes.getValue(parameter)
        val value = valueFor(parameter).coerceIn(axis.first(), axis.last())
        val lowerIndex = atlasLowerAxisIndex(axis, value)
        val upperIndex = minOf(lowerIndex + 1, axis.lastIndex)
        val lower = axis[lowerIndex]
        val upper = axis[upperIndex]
        val fraction = if (upper == lower) 0.0 else (value - lower) / (upper - lower)
        gain *= when (coordinate) {
            lower -> 1.0 - fraction
            upper -> fraction
            else -> return 0.0
        }
    }
    return gain
}

/** Routes an engine-event contributor through its LOAD/COAST mixer stem before host summing. */
internal fun atlasEngineContributorProgramGain(
    role: AtlasEngineProgramRole,
    loadGain: Double,
    coastGain: Double,
    unaffectedGain: Double = 1.0,
): Double = when (role) {
    AtlasEngineProgramRole.LOAD -> loadGain
    AtlasEngineProgramRole.COAST -> coastGain
    AtlasEngineProgramRole.UNAFFECTED -> unaffectedGain
}

private fun atlasLowerAxisIndex(axis: DoubleArray, value: Double): Int {
    var low = 0
    var high = axis.lastIndex
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (axis[middle] <= value) low = middle else high = middle - 1
    }
    return low
}
