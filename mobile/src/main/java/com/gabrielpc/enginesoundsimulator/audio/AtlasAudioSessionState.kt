package com.gabrielpc.enginesoundsimulator.audio

/**
 * Exact FMOD event-instance ownership for one selected car/profile audio session.
 *
 * This state is deliberately independent from a perspective PCM renderer. Camera switches may
 * replace the selected engine renderer, but they do not recreate transmission, turbo, limiter,
 * gear/backfire EventInstances, their scheduler history, or already-started finite tails. A
 * persistent EventInstance may survive a camera switch while only the renderer for the selected
 * perspective is allowed to drive its semantic state and continuous PCM.
 * Mutation is confined to the audio/control handoff after the previous renderer has joined; the
 * realtime callback therefore never locks.
 */
internal class AtlasAudioSessionState(
    internal val atlasFamilyId: String,
    eventContracts: List<AtlasAudioSessionEventContract>,
    internal val effectControls: AtlasEffectControlModel? = null,
    internal val profileAudioSessionGeneration: Long = 1L,
) {
    private val events = eventContracts.associate { contract ->
        contract.eventPath to EventState(contract)
    }
    private val eventStates = events.values.toTypedArray()
    private val schedulerStates = linkedMapOf<AtlasSessionSchedulerKey, AtlasEffectSchedulerState>()
    private var nextRendererId = 1L
    private var selectedPerspective: EngineSoundPerspective? = null
    private var selectedRendererId = NO_RENDERER
    private var pendingEngineStartRendererId = NO_RENDERER

    init {
        require(atlasFamilyId.isNotBlank()) { "Atlas audio session has no family id" }
        require(profileAudioSessionGeneration >= 0L) {
            "Atlas profile audio session generation must be non-negative"
        }
        require(events.size == eventContracts.size) { "Atlas audio session has duplicate exact event paths" }
        eventContracts.forEach { contract ->
            require(contract.eventPath.startsWith("event:/")) { "Atlas session event path is not exact" }
            require(contract.perspectives.isNotEmpty()) { "Atlas session event has no activation perspective" }
            require(!contract.profileSessionContinuousOwner ||
                contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT) {
                "Only a profile-session persistent EventInstance may own a session-continuous event"
            }
            require(contract.runtimeExecutable) {
                "Atlas session cannot execute blocked lifecycle for ${contract.eventPath}"
            }
        }
    }

    /**
     * Selects one newly prepared perspective renderer. Only the matching engine EventInstance is
     * restarted. Persistent event owners and finite tails are intentionally untouched.
     */
    fun selectPerspective(perspective: EngineSoundPerspective): AtlasPerspectiveActivation {
        val rendererId = nextRendererId++

        return selectPerspective(perspective, rendererId)
    }

    /** Reuses one prepared perspective-effects participant without changing persistent ownership. */
    fun reselectPerspective(
        perspective: EngineSoundPerspective,
        rendererId: Long,
    ): AtlasPerspectiveActivation {
        require(rendererId in 1 until nextRendererId) { "Unknown atlas perspective renderer id" }

        return selectPerspective(perspective, rendererId)
    }

    private fun selectPerspective(
        perspective: EngineSoundPerspective,
        rendererId: Long,
    ): AtlasPerspectiveActivation {
        events.values.forEach { state ->
            if (state.contract.owner == AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE) {
                state.stopSelectedPerspectiveActivation()
                if (perspective in state.contract.perspectives) {
                    state.startActivation(rendererId)
                }
            } else if (state.contract.profileSessionContinuousOwner) {
                if (perspective in state.contract.perspectives) {
                    if (state.activation == null) {
                        state.startActivation(rendererId)
                    } else {
                        state.selectSemanticRenderer(rendererId)
                    }
                } else {
                    state.suspendSemanticUpdates()
                }
            }
        }
        selectedPerspective = perspective
        selectedRendererId = rendererId
        pendingEngineStartRendererId = rendererId

        return AtlasPerspectiveActivation(rendererId, perspective)
    }

    /** LOAD/COAST/BOTH changes are mix parameters, not FMOD instance lifecycle transitions. */
    fun selectProgramMode(@Suppress("UNUSED_PARAMETER") source: PrimaryEngineLayerSource) = Unit

    /** Consumed by the first audible block of the newly selected engine renderer. */
    fun consumeSelectedEngineEventStart(rendererId: Long): Boolean {
        if (pendingEngineStartRendererId != rendererId) return false
        pendingEngineStartRendererId = NO_RENDERER

        return true
    }

    /** Starts or reuses one session-persistent transmission/turbo/limiter owner. */
    fun ensurePersistentActivation(
        eventPath: String,
        rendererId: Long,
    ): AtlasEventActivation {
        val state = eventState(eventPath)
        require(state.contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT) {
            "$eventPath is not a profile-session persistent EventInstance"
        }
        require(rendererMayStart(state, rendererId)) {
            "Inactive perspective renderer cannot start $eventPath"
        }
        state.activation?.let { activation ->
            require(state.acceptsSemanticUpdates(rendererId)) {
                "Inactive perspective renderer cannot reuse $eventPath"
            }

            return activation
        }

        return state.startActivation(rendererId)
    }

    /**
     * Replays Audio Lab's finite host guard. An in-flight Start is rejected because FMOD behavior
     * for Start-on-PLAYING has not yet been proven. Backfire's host-wide guard is represented by
     * [mutualExclusionGroup] without deriving policy from an event suffix.
     */
    fun tryStartFiniteActivation(
        eventPath: String,
        rendererId: Long,
        mutualExclusionGroup: String? = null,
    ): AtlasFiniteEventStartResult {
        return when (tryStartFiniteActivationStatus(eventPath, rendererId, mutualExclusionGroup)) {
            AtlasFiniteEventStartStatus.STARTED -> AtlasFiniteEventStartResult.Started(
                requireNotNull(activationFor(eventPath)),
            )
            AtlasFiniteEventStartStatus.IN_FLIGHT -> AtlasFiniteEventStartResult.InFlight
            AtlasFiniteEventStartStatus.INACTIVE_PERSPECTIVE -> AtlasFiniteEventStartResult.InactivePerspective
        }
    }

    /** Allocation-free host-Start gate used from the realtime effects callback. */
    fun tryStartFiniteActivationStatus(
        eventPath: String,
        rendererId: Long,
        mutualExclusionGroup: String? = null,
    ): AtlasFiniteEventStartStatus {
        val state = eventState(eventPath)
        require(state.contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT) {
            "$eventPath is not host-started by a persistent EventInstance"
        }
        if (!rendererMayStart(state, rendererId)) return AtlasFiniteEventStartStatus.INACTIVE_PERSPECTIVE
        if (state.activation != null) return AtlasFiniteEventStartStatus.IN_FLIGHT
        if (mutualExclusionGroup != null) {
            var index = 0
            while (index < eventStates.size) {
                if (eventStates[index].activeMutualExclusionGroup == mutualExclusionGroup) {
                    return AtlasFiniteEventStartStatus.IN_FLIGHT
                }
                index += 1
            }
        }
        state.startActivation(rendererId, mutualExclusionGroup)

        return AtlasFiniteEventStartStatus.STARTED
    }

    /** Associates a captured one-shot/repeat tail with the activation that produced it. */
    fun retainFiniteTail(
        activation: AtlasEventActivation,
        tailId: Long,
    ): AtlasFiniteTail {
        require(tailId >= 0L) { "Finite tail id must be non-negative" }
        val state = eventState(activation.eventPath)
        require(state.activation == activation) { "Finite tail belongs to a stale event activation" }
        state.retainFiniteTail(tailId)

        return AtlasFiniteTail(activation, tailId)
    }

    /** Returns a preallocated tail slot; no handle object is created on the audio callback. */
    fun retainFiniteTailSlot(
        activation: AtlasEventActivation,
        tailId: Long,
    ): Int {
        require(tailId >= 0L) { "Finite tail id must be non-negative" }
        val state = eventState(activation.eventPath)
        require(state.activation === activation) { "Finite tail belongs to a stale event activation" }

        return state.retainFiniteTail(tailId)
    }

    fun advanceFiniteTailSlot(
        activation: AtlasEventActivation,
        tailId: Long,
        slot: Int,
        frameCount: Int,
    ) {
        require(frameCount >= 0) { "Finite tail cannot move backwards" }
        val state = eventState(activation.eventPath)
        require(state.activation === activation) { "Finite tail belongs to a stale activation" }
        state.advanceFiniteTail(slot, tailId, frameCount)
    }

    fun completeFiniteTailSlot(
        activation: AtlasEventActivation,
        tailId: Long,
        slot: Int,
    ) {
        val state = eventState(activation.eventPath)
        if (state.activation !== activation || !state.completeFiniteTail(slot, tailId)) return
        releaseCompletedFiniteActivation(state)
    }

    /** Natural finite completion releases only host-started persistent events. */
    fun completeFiniteTail(tail: AtlasFiniteTail) {
        val state = eventState(tail.activation.eventPath)
        if (state.activation !== tail.activation) return
        if (!state.completeFiniteTail(tail.tailId)) return
        releaseCompletedFiniteActivation(state)
    }

    /** Releases a chance-rejected/inaudible host Start that produced no continuous or finite voice. */
    fun abandonEmptyActivation(activation: AtlasEventActivation) {
        val state = eventState(activation.eventPath)
        if (state.activation !== activation || state.hasActiveTails || state.continuousOwnerActive) return
        if (state.contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT &&
            !state.contract.profileSessionContinuousOwner
        ) {
            state.activation = null
            state.activeMutualExclusionGroup = null
        }
    }

    fun finiteTailPlaybackFrame(tail: AtlasFiniteTail): Long {
        val state = eventState(tail.activation.eventPath)
        require(state.activation === tail.activation) { "Finite tail belongs to a stale activation" }

        return state.finiteTailPlaybackFrame(tail.tailId)
    }

    fun advanceFiniteTail(tail: AtlasFiniteTail, frameCount: Int) {
        require(frameCount >= 0) { "Finite tail cannot move backwards" }
        val state = eventState(tail.activation.eventPath)
        require(state.activation === tail.activation) { "Finite tail belongs to a stale activation" }
        state.advanceFiniteTail(tail.tailId, frameCount)
    }

    fun markContinuousOwnerActive(activation: AtlasEventActivation, active: Boolean) {
        val state = eventState(activation.eventPath)
        require(state.activation === activation) { "Continuous owner belongs to a stale activation" }
        state.continuousOwnerActive = active
        if (!active && !state.hasActiveTails &&
            state.contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT &&
            !state.contract.profileSessionContinuousOwner
        ) {
            state.activation = null
            state.activeMutualExclusionGroup = null
        }
    }

    fun continuousClockFrames(
        activation: AtlasEventActivation,
        authoredBindingKey: String,
    ): Long {
        val state = eventState(activation.eventPath)
        require(state.activation === activation) { "Continuous clock belongs to a stale activation" }

        return state.continuousClockFrames(authoredBindingKey)
    }

    fun advanceContinuousClock(
        activation: AtlasEventActivation,
        authoredBindingKey: String,
        frameCount: Int,
    ) {
        require(frameCount >= 0) { "Continuous clock cannot move backwards" }
        val state = eventState(activation.eventPath)
        require(state.activation === activation) { "Continuous clock belongs to a stale activation" }
        state.advanceContinuousClock(authoredBindingKey, frameCount)
    }

    fun activationFor(eventPath: String): AtlasEventActivation? = eventState(eventPath).activation

    fun acceptsSemanticUpdates(eventPath: String, rendererId: Long): Boolean {
        val state = eventState(eventPath)
        if (state.activation == null) return false

        return state.acceptsSemanticUpdates(rendererId)
    }

    fun activeFiniteTails(): List<AtlasFiniteTail> = buildList {
        events.values.forEach { state ->
            val activation = state.activation ?: return@forEach
            state.forEachActiveTail { tailId -> add(AtlasFiniteTail(activation, tailId)) }
        }
    }

    /** Scheduler history follows its proven event-instance/group/perspective scope. */
    fun schedulerState(
        eventPath: String,
        groupId: String,
    ): AtlasEffectSchedulerState {
        eventState(eventPath)
        val key = AtlasSessionSchedulerKey(eventPath, groupId)

        return schedulerStates.getOrPut(key) {
            AtlasEffectSchedulerState(
                AtlasEffectScheduler.seed(
                    atlasFamilyId,
                    eventPath,
                    profileAudioSessionGeneration,
                    groupId,
                ),
            )
        }
    }

    private fun rendererMayStart(state: EventState, rendererId: Long): Boolean =
        rendererId == selectedRendererId && selectedPerspective in state.contract.perspectives

    private fun releaseCompletedFiniteActivation(state: EventState) {
        if (!state.hasActiveTails &&
            state.contract.owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT &&
            !state.continuousOwnerActive &&
            !state.contract.profileSessionContinuousOwner
        ) {
            state.activation = null
            state.activeMutualExclusionGroup = null
        }
    }

    private fun eventState(eventPath: String): EventState = requireNotNull(events[eventPath]) {
        "Unknown exact atlas event path $eventPath"
    }

    private class EventState(val contract: AtlasAudioSessionEventContract) {
        var activation: AtlasEventActivation? = null
        private val activationHandle = AtlasEventActivation(contract.eventPath)
        private var semanticRendererId = NO_RENDERER
        private var semanticUpdatesEnabled = false
        var continuousOwnerActive = false
        var activeMutualExclusionGroup: String? = null
        private val activeTailIds = LongArray(contract.finiteTailCapacity) { NO_TAIL }
        private val activeTailPlaybackFrames = LongArray(contract.finiteTailCapacity)
        private val continuousClockFrames = LongArray(contract.authoredBindingKeys.size)
        private val continuousClockIndex = contract.authoredBindingKeys.withIndex()
            .associate { indexed -> indexed.value to indexed.index }
        private var generation = 0L

        fun startActivation(
            rendererId: Long,
            mutualExclusionGroup: String? = null,
        ): AtlasEventActivation {
            generation = Math.addExact(generation, 1L)
            activeTailIds.fill(NO_TAIL)
            activeTailPlaybackFrames.fill(0L)
            continuousClockFrames.fill(0L)
            continuousOwnerActive = false
            activeMutualExclusionGroup = mutualExclusionGroup
            selectSemanticRenderer(rendererId)

            activationHandle.generation = generation
            activationHandle.rendererId = rendererId
            activation = activationHandle

            return activationHandle
        }

        fun stopSelectedPerspectiveActivation() {
            activation = null
            suspendSemanticUpdates()
            continuousOwnerActive = false
            activeMutualExclusionGroup = null
            activeTailIds.fill(NO_TAIL)
            activeTailPlaybackFrames.fill(0L)
            continuousClockFrames.fill(0L)
        }

        fun selectSemanticRenderer(rendererId: Long) {
            semanticRendererId = rendererId
            semanticUpdatesEnabled = true
        }

        fun suspendSemanticUpdates() {
            semanticRendererId = NO_RENDERER
            semanticUpdatesEnabled = false
            continuousOwnerActive = false
        }

        fun acceptsSemanticUpdates(rendererId: Long): Boolean =
            semanticUpdatesEnabled && semanticRendererId == rendererId

        fun continuousClockFrames(authoredBindingKey: String): Long =
            continuousClockFrames[continuousClockIndex(authoredBindingKey)]

        fun advanceContinuousClock(authoredBindingKey: String, frameCount: Int) {
            val index = continuousClockIndex(authoredBindingKey)
            continuousClockFrames[index] = Math.addExact(continuousClockFrames[index], frameCount.toLong())
        }

        private fun continuousClockIndex(authoredBindingKey: String): Int =
            requireNotNull(continuousClockIndex[authoredBindingKey]) {
                "Unknown authored binding $authoredBindingKey for ${contract.eventPath}"
            }

        val hasActiveTails: Boolean get() = activeTailIds.any { it != NO_TAIL }

        fun retainFiniteTail(tailId: Long): Int {
            require(activeTailIds.none { it == tailId }) { "Finite tail id is already active" }
            val slot = activeTailIds.indexOfFirst { it == NO_TAIL }
            require(slot >= 0) { "Finite-tail ownership exceeds its proven capacity" }
            activeTailIds[slot] = tailId
            activeTailPlaybackFrames[slot] = 0L

            return slot
        }

        fun completeFiniteTail(tailId: Long): Boolean {
            val slot = activeTailIds.indexOfFirst { it == tailId }
            if (slot < 0) return false
            return completeFiniteTail(slot, tailId)
        }

        fun completeFiniteTail(slot: Int, tailId: Long): Boolean {
            require(slot in activeTailIds.indices) { "Finite tail slot is outside its proven capacity" }
            if (activeTailIds[slot] != tailId) return false
            activeTailIds[slot] = NO_TAIL
            activeTailPlaybackFrames[slot] = 0L

            return true
        }

        inline fun forEachActiveTail(block: (Long) -> Unit) {
            activeTailIds.forEach { tailId -> if (tailId != NO_TAIL) block(tailId) }
        }

        fun finiteTailPlaybackFrame(tailId: Long): Long =
            activeTailPlaybackFrames[finiteTailSlot(tailId)]

        fun advanceFiniteTail(tailId: Long, frameCount: Int) {
            val slot = finiteTailSlot(tailId)
            advanceFiniteTail(slot, tailId, frameCount)
        }

        fun advanceFiniteTail(slot: Int, tailId: Long, frameCount: Int) {
            require(slot in activeTailIds.indices && activeTailIds[slot] == tailId) {
                "Unknown active finite tail $tailId for ${contract.eventPath}"
            }
            activeTailPlaybackFrames[slot] = Math.addExact(
                activeTailPlaybackFrames[slot],
                frameCount.toLong(),
            )
        }

        private fun finiteTailSlot(tailId: Long): Int {
            val slot = activeTailIds.indexOfFirst { it == tailId }
            require(slot >= 0) { "Unknown active finite tail $tailId for ${contract.eventPath}" }

            return slot
        }
    }

    companion object {
        private const val NO_RENDERER = 0L

        fun from(
            profile: EngineSampleProfile,
            profileAudioSessionGeneration: Long = 1L,
        ): AtlasAudioSessionState {
            val atlas = requireNotNull(profile.atlasProgram) { "Profile has no full-event atlas" }

            return AtlasAudioSessionState(
                atlasFamilyId = atlas.id,
                eventContracts = atlas.effects.map { event ->
                    val ownerships = event.variants.mapTo(linkedSetOf()) { it.eventInstanceOwnership }
                    require(ownerships.size == 1) {
                        "${event.eventPath} variants disagree on FMOD EventInstance ownership"
                    }
                    val owner = ownerships.single().owner
                    val executable = !event.runtimeMappingBlocked && event.variants.all { variant ->
                        variant.finiteLifecycleTopology?.executable != false
                    }
                    AtlasAudioSessionEventContract(
                        eventPath = event.eventPath,
                        owner = owner,
                        perspectives = event.perspectives,
                        runtimeExecutable = executable,
                        profileSessionContinuousOwner =
                            owner == AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT &&
                                event.variants.any { it.lifetime == AtlasEffectLifetime.CONTINUOUS },
                        authoredBindingKeys = event.variants.mapTo(linkedSetOf()) {
                            it.authoredBindingKey
                        },
                        finiteTailCapacity = EngineSoundPerspective.entries.maxOf { perspective ->
                            atlas.finiteRingPoolVoiceCapacity(perspective)
                        }.coerceAtLeast(1),
                    )
                },
                effectControls = AtlasEffectControlModel(
                    requireNotNull(profile.atlasAudioPhysics),
                    profile.soundDrivenWheelRadiusMeters,
                ),
                profileAudioSessionGeneration = profileAudioSessionGeneration,
            )
        }
    }
}

internal data class AtlasAudioSessionEventContract(
    val eventPath: String,
    val owner: AtlasEventInstanceOwner,
    val perspectives: Set<EngineSoundPerspective>,
    val runtimeExecutable: Boolean = true,
    val profileSessionContinuousOwner: Boolean = false,
    val authoredBindingKeys: Set<String> = emptySet(),
    val finiteTailCapacity: Int = DEFAULT_FINITE_TAIL_CAPACITY,
)

private const val DEFAULT_FINITE_TAIL_CAPACITY = 16
private const val NO_TAIL = -1L

internal data class AtlasPerspectiveActivation(
    val rendererId: Long,
    val perspective: EngineSoundPerspective,
)

/** Preallocated realtime activation token; a stopped event cannot reuse it until every tail ends. */
internal class AtlasEventActivation internal constructor(
    val eventPath: String,
    var generation: Long = 0L,
    var rendererId: Long = 0L,
)

internal data class AtlasFiniteTail(
    val activation: AtlasEventActivation,
    val tailId: Long,
)

internal sealed interface AtlasFiniteEventStartResult {
    data class Started(val activation: AtlasEventActivation) : AtlasFiniteEventStartResult
    data object InFlight : AtlasFiniteEventStartResult
    data object InactivePerspective : AtlasFiniteEventStartResult
}

internal enum class AtlasFiniteEventStartStatus {
    STARTED,
    IN_FLIGHT,
    INACTIVE_PERSPECTIVE,
}

private data class AtlasSessionSchedulerKey(
    val eventPath: String,
    val groupId: String,
)
