package com.gabrielpc.enginesoundsimulator.audio

/**
 * Owns every non-bed PCM renderer for one profile audio session.
 *
 * A perspective view is intentionally not an owner. Closing a cabin/exterior engine-bed renderer
 * therefore cannot destroy a transmission/turbo EventInstance or an already-playing finite tail.
 * The owner is closed only when the profile audio session itself stops.
 */
internal class AtlasAudioSessionEffects private constructor(
    internal val state: AtlasAudioSessionState,
    private val controls: AtlasEffectControlModel,
    override val meterTrackIds: List<String>,
    private val sharedEffects: AtlasSessionSharedEffects,
    private val participantFactory: AtlasSessionEffectsParticipantFactory,
) : AutoCloseable, AtlasSessionEffectsMeterSource {
    private val participants = arrayOfNulls<ParticipantSlot>(EngineSoundPerspective.entries.size)
    private val meterLevels = DoubleArray(meterTrackIds.size)
    private val sharedFrame = DoubleArray(2)
    private val sharedMeterOffset = meterTrackIds.size - SHARED_METER_COUNT
    private var selectedRendererId = NO_RENDERER
    private var selectedViewGeneration = 0L
    private var closed = false

    init {
        require(state.effectControls == null || state.effectControls === controls) {
            "Atlas session effects must use the session-owned Audio Lab controls"
        }
        require(meterTrackIds.size >= SHARED_METER_COUNT)
        require(meterTrackIds.takeLast(SHARED_METER_COUNT) == SHARED_METER_TRACK_IDS)
        require(meterTrackIds.distinct().size == meterTrackIds.size) {
            "Atlas session meter ids must be unique"
        }
    }

    /**
     * Starts the selected engine EventInstance while reusing the prepared perspective participant.
     * Mode changes never call this API.
     */
    fun selectPerspective(perspective: EngineSoundPerspective): View {
        check(!closed) { "Atlas audio session effects are closed" }
        var slot = participants[perspective.ordinal]
        val activation = if (slot == null) {
            state.selectPerspective(perspective)
        } else {
            state.reselectPerspective(perspective, slot.rendererId)
        }
        if (slot == null) {
            val participant = participantFactory.create(perspective, activation)
            try {
                require(participant.perspective == perspective)
                require(participant.rendererId == activation.rendererId)
                slot = ParticipantSlot(
                    rendererId = activation.rendererId,
                    participant = participant,
                    meterScratch = DoubleArray(participant.meterTrackIds.size),
                    globalMeterIndices = IntArray(participant.meterTrackIds.size) { localIndex ->
                        val trackId = participant.meterTrackIds[localIndex]
                        val globalIndex = meterTrackIds.indexOf(trackId)
                        require(globalIndex in 0 until sharedMeterOffset) {
                            "Unknown or shared participant meter id $trackId"
                        }
                        globalIndex
                    },
                )
            } catch (error: Throwable) {
                participant.close()
                throw error
            }
            participants[perspective.ordinal] = slot
        }
        selectedRendererId = activation.rendererId
        selectedViewGeneration = Math.addExact(selectedViewGeneration, 1L)

        return View(this, activation.rendererId, selectedViewGeneration)
    }

    private fun updateAndRender(
        viewRendererId: Long,
        viewGeneration: Long,
        target: EngineAudioFrame,
        blockSeconds: Double,
        effectiveProgramThrottle: Double,
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
        check(!closed) { "Atlas audio session effects are closed" }
        requireSelectedView(viewRendererId, viewGeneration)
        require(frameCount <= effectEventLeft.size && frameCount <= effectEventRight.size)
        state.selectProgramMode(target.primaryLayerSource)
        controls.update(
            target,
            blockSeconds,
            effectiveProgramThrottle,
            selectedEngineEventActivationStarted = state.consumeSelectedEngineEventStart(viewRendererId),
        )

        var participantIndex = 0
        while (participantIndex < participants.size) {
            participants[participantIndex]?.participant?.render(
                target = target,
                selectedPerspectiveActive = participants[participantIndex]?.rendererId == selectedRendererId,
                frameCount = frameCount,
                engineEventLeft = engineEventLeft,
                engineEventRight = engineEventRight,
                effectEventLeft = effectEventLeft,
                effectEventRight = effectEventRight,
                anySolo = anySolo,
                loadProgramGain = loadProgramGain,
                coastProgramGain = coastProgramGain,
                loadProgramGainIgnoringSolo = loadProgramGainIgnoringSolo,
                coastProgramGainIgnoringSolo = coastProgramGainIgnoringSolo,
            )
            participantIndex += 1
        }

        sharedEffects.update(target, controls)
        var frame = 0
        while (frame < frameCount) {
            sharedFrame[0] = 0.0
            sharedFrame[1] = 0.0
            sharedEffects.mixFrame(target, sharedFrame, anySolo)
            effectEventLeft[frame] += sharedFrame[0]
            effectEventRight[frame] += sharedFrame[1]
            frame += 1
        }
    }

    private fun writeMeters(
        viewRendererId: Long,
        viewGeneration: Long,
        target: EngineAudioFrame,
        destination: DoubleArray,
        offset: Int,
    ) {
        requireSelectedView(viewRendererId, viewGeneration)
        require(destination.size - offset >= meterLevels.size)
        meterLevels.fill(0.0)
        var participantIndex = 0
        while (participantIndex < participants.size) {
            participants[participantIndex]?.let { slot ->
                slot.participant.writeMeters(target, slot.meterScratch, 0)
                var localIndex = 0
                while (localIndex < slot.meterScratch.size) {
                    val globalIndex = slot.globalMeterIndices[localIndex]
                    meterLevels[globalIndex] = maxOf(meterLevels[globalIndex], slot.meterScratch[localIndex])
                    localIndex += 1
                }
            }
            participantIndex += 1
        }
        sharedEffects.writeMeters(target, meterLevels, sharedMeterOffset)
        meterLevels.copyInto(destination, destinationOffset = offset)
    }

    private fun requireSelectedView(rendererId: Long, generation: Long) {
        check(rendererId == selectedRendererId && generation == selectedViewGeneration) {
            "Inactive atlas perspective view cannot access the session effects"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        participants.forEach { slot -> slot?.participant?.close() }
        participants.fill(null)
        selectedRendererId = NO_RENDERER
        selectedViewGeneration = Math.addExact(selectedViewGeneration, 1L)
    }

    internal class View internal constructor(
        private val owner: AtlasAudioSessionEffects,
        private val rendererId: Long,
        private val generation: Long,
    ) : AtlasSessionEffectsMeterSource, AutoCloseable {
        private var closed = false
        override val meterTrackIds: List<String> get() = owner.meterTrackIds

        fun updateAndRender(
            target: EngineAudioFrame,
            blockSeconds: Double,
            effectiveProgramThrottle: Double,
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
            check(!closed) { "Closed atlas perspective view cannot render" }
            owner.updateAndRender(
                viewRendererId = rendererId,
                viewGeneration = generation,
                target = target,
                blockSeconds = blockSeconds,
                effectiveProgramThrottle = effectiveProgramThrottle,
                frameCount = frameCount,
                engineEventLeft = engineEventLeft,
                engineEventRight = engineEventRight,
                effectEventLeft = effectEventLeft,
                effectEventRight = effectEventRight,
                anySolo = anySolo,
                loadProgramGain = loadProgramGain,
                coastProgramGain = coastProgramGain,
                loadProgramGainIgnoringSolo = loadProgramGainIgnoringSolo,
                coastProgramGainIgnoringSolo = coastProgramGainIgnoringSolo,
            )
        }

        fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) {
            check(!closed) { "Closed atlas perspective view cannot publish meters" }
            owner.writeMeters(rendererId, generation, target, destination, offset)
        }

        /** A view invalidates only itself; [AtlasAudioSessionEffects.close] is the sole PCM teardown. */
        override fun close() {
            closed = true
        }
    }

    private data class ParticipantSlot(
        val rendererId: Long,
        val participant: AtlasSessionEffectsParticipant,
        val meterScratch: DoubleArray,
        val globalMeterIndices: IntArray,
    )

    companion object {
        fun create(
            profile: EngineSampleProfile,
            fileResolver: AtlasShardFileResolver,
            sharedEffectsSource: AudioAssetSource,
            profileAudioSessionGeneration: Long = 1L,
        ): AtlasAudioSessionEffects {
            val program = requireNotNull(profile.atlasProgram) { "Profile has no full-event atlas" }
            program.requirePlaybackReady()
            NativeAtlasMapRegistry.configureExactLimit(
                program.sessionResourceBounds.maximumMappedShardInstancesDuringTransitionSafeUpperBound,
            )
            val state = AtlasAudioSessionState.from(profile, profileAudioSessionGeneration)
            val controls = requireNotNull(state.effectControls)

            return AtlasAudioSessionEffects(
                state = state,
                controls = controls,
                meterTrackIds = sessionMeterTrackIds(program),
                sharedEffects = RealAtlasSessionSharedEffects(sharedEffectsSource),
                participantFactory = AtlasSessionEffectsParticipantFactory { perspective, activation ->
                    RealAtlasSessionEffectsParticipant(
                        perspective = perspective,
                        activation = activation,
                        renderer = FullEventAtlasEffectsRenderer(
                            atlasFamilyId = program.id,
                            perspective = perspective,
                            program = program,
                            fileResolver = fileResolver,
                            controls = controls,
                            sharedSource = sharedEffectsSource,
                            sessionState = state,
                            perspectiveActivation = activation,
                        ),
                    )
                },
            )
        }

        internal fun createForTest(
            state: AtlasAudioSessionState,
            controls: AtlasEffectControlModel,
            effectMeterTrackIds: List<String>,
            sharedEffects: AtlasSessionSharedEffects,
            participantFactory: AtlasSessionEffectsParticipantFactory,
        ): AtlasAudioSessionEffects = AtlasAudioSessionEffects(
            state = state,
            controls = controls,
            meterTrackIds = effectMeterTrackIds + SHARED_METER_TRACK_IDS,
            sharedEffects = sharedEffects,
            participantFactory = participantFactory,
        )

        private fun sessionMeterTrackIds(program: FullEventAtlasProgram): List<String> = buildList {
            val effectIds = linkedSetOf<String>()
            program.effects.forEach { event ->
                event.variants.asSequence()
                    .flatMap { variant -> variant.runtimeTriggers.asSequence() }
                    .forEach { trigger -> effectIds += atlasEffectTrackId(event.eventSuffix, trigger) }
            }
            addAll(effectIds.filterNot { it in SHARED_METER_TRACK_IDS })
            addAll(SHARED_METER_TRACK_IDS)
        }

        private const val NO_RENDERER = 0L
        private const val SHARED_METER_COUNT = 3
        private val SHARED_METER_TRACK_IDS = listOf(
            SharedPopsAndBangs.EFFECT_ID,
            SharedHuracanShiftSounds.SHIFT_UP_ID,
            SharedHuracanShiftSounds.SHIFT_DOWN_ID,
        )
    }
}

internal interface AtlasSessionEffectsMeterSource {
    val meterTrackIds: List<String>
}

internal fun interface AtlasSessionEffectsParticipantFactory {
    fun create(
        perspective: EngineSoundPerspective,
        activation: AtlasPerspectiveActivation,
    ): AtlasSessionEffectsParticipant
}

internal interface AtlasSessionEffectsParticipant : AutoCloseable {
    val perspective: EngineSoundPerspective
    val rendererId: Long
    val meterTrackIds: List<String>

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
    )

    fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int)
}

internal interface AtlasSessionSharedEffects {
    fun update(target: EngineAudioFrame, controls: AtlasEffectControlModel)

    fun mixFrame(target: EngineAudioFrame, destination: DoubleArray, anySolo: Boolean)

    fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int)
}

private class RealAtlasSessionEffectsParticipant(
    override val perspective: EngineSoundPerspective,
    activation: AtlasPerspectiveActivation,
    private val renderer: FullEventAtlasEffectsRenderer,
) : AtlasSessionEffectsParticipant {
    override val rendererId = activation.rendererId
    override val meterTrackIds: List<String> get() = renderer.meterTrackIds

    override fun render(
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
    ) = renderer.render(
        target,
        selectedPerspectiveActive,
        frameCount,
        engineEventLeft,
        engineEventRight,
        effectEventLeft,
        effectEventRight,
        anySolo,
        loadProgramGain,
        coastProgramGain,
        loadProgramGainIgnoringSolo,
        coastProgramGainIgnoringSolo,
    )

    override fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) =
        renderer.writeMeters(target, destination, offset)

    override fun close() = renderer.close()
}

private class RealAtlasSessionSharedEffects(source: AudioAssetSource) : AtlasSessionSharedEffects {
    private val delegate = AtlasSharedOverrides(source)

    override fun update(target: EngineAudioFrame, controls: AtlasEffectControlModel) =
        delegate.update(target, controls)

    override fun mixFrame(target: EngineAudioFrame, destination: DoubleArray, anySolo: Boolean) =
        delegate.mixFrame(target, destination, anySolo)

    override fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) =
        delegate.writeMeters(target, destination, offset)
}
