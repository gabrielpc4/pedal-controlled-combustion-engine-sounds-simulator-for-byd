package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.exp

internal class FullEventAtlasRenderer private constructor(
    private val profile: EngineSampleProfile,
    private val perspective: EngineSoundPerspective,
    private val program: AtlasPerspectiveProgram,
    private val cellLoader: AtlasHotCellLoader,
    private val outputHistory: AtlasOutputHistory,
    private val effectsView: AtlasAudioSessionEffects.View?,
    initialEngineProgram: AtlasEngineProgram,
) : EngineProgramRenderer {
    override val meterTrackIds: List<String> = buildList {
        add(LOAD_TRACK_ID)
        add(COAST_TRACK_ID)
        addAll(effectsView?.meterTrackIds.orEmpty())
    }

    private val selection = AtlasCellSelection()
    private val weights = DoubleArray(4)
    private val meterLevels = DoubleArray(meterTrackIds.size)
    private val limitedFrame = DoubleArray(2)
    private val hostMix = requireNotNull(profile.atlasProgram).hostMixContract
    private val limiter = StereoCausalPeakLimiter(
        ceilingLinear = hostMix.limiterCeilingLinear,
        releaseFrames = hostMix.limiterReleaseFrames,
    )
    private var smoothedRpm = profile.idleRpm
    private var smoothedThrottle = 0.0
    private var outputGain = 0.0
    private var activeEngineProgram = initialEngineProgram
    private var previousEngineProgram = initialEngineProgram
    private var activeEngineProgramGain = 1.0
    private var previousEngineProgramGain = 1.0
    private var engineProgramTransition = 1.0
    private val engineEventTransientLeft = DoubleArray(MAX_RENDER_FRAMES)
    private val engineEventTransientRight = DoubleArray(MAX_RENDER_FRAMES)
    private val effectEventLeft = DoubleArray(MAX_RENDER_FRAMES)
    private val effectEventRight = DoubleArray(MAX_RENDER_FRAMES)

    override fun render(target: EngineAudioFrame, output: ShortArray, gain: Double) {
        require(output.size % CHANNELS == 0) { "Stereo render buffer must contain whole frames" }
        val frameCount = output.size / CHANNELS
        require(frameCount <= MAX_RENDER_FRAMES) { "Atlas render block exceeds prepared effect buffer" }
        val blockSeconds = frameCount.toDouble() / SAMPLE_RATE
        val rpmAlpha = 1.0 - exp(-blockSeconds / (target.tuning.rpmSmoothingMs / 1_000.0))
        val throttleAlpha = 1.0 - exp(-blockSeconds / (target.tuning.throttleSmoothingMs / 1_000.0))
        smoothedRpm += (target.rpm.coerceIn(profile.minimumRpm, profile.maximumRpm) - smoothedRpm) * rpmAlpha
        smoothedThrottle += (target.throttle.coerceIn(0.0, 1.0) - smoothedThrottle) * throttleAlpha
        val source = profile.resolvedPrimaryLayerSource(target.primaryLayerSource, perspective)
        var anySolo = false
        var trackIndex = 0
        while (trackIndex < meterTrackIds.size) {
            val control = target.layerMix[meterTrackIds[trackIndex]]
            if (control?.solo == true && !control.muted) {
                anySolo = true
                break
            }
            trackIndex += 1
        }
        engineEventTransientLeft.fill(0.0, 0, frameCount)
        engineEventTransientRight.fill(0.0, 0, frameCount)
        effectEventLeft.fill(0.0, 0, frameCount)
        effectEventRight.fill(0.0, 0, frameCount)
        val loadGain = trackGain(
            LOAD_TRACK_ID,
            target.programLayerGains.load,
            target.layerMix,
            anySolo,
        )
        val coastGain = trackGain(
            COAST_TRACK_ID,
            target.programLayerGains.coast,
            target.layerMix,
            anySolo,
        )
        val loadControl = target.layerMix[LOAD_TRACK_ID] ?: LayerMixControl.DEFAULT
        val coastControl = target.layerMix[COAST_TRACK_ID] ?: LayerMixControl.DEFAULT
        val loadSolo = loadControl.solo && !loadControl.muted
        val coastSolo = coastControl.solo && !coastControl.muted
        val engineProgramSelection = selectAtlasEngineProgram(
            source = source,
            liveThrottle = smoothedThrottle,
            loadGain = loadGain,
            coastGain = coastGain,
            anySolo = anySolo,
            loadSolo = loadSolo,
            coastSolo = coastSolo,
        )
        beginEngineProgramTransition(engineProgramSelection)
        val atlasThrottle = effectiveAtlasProgramThrottle(
            engineProgramSelection.program,
            smoothedThrottle,
        )
        AtlasCellSelector.select(program.rpmAxis, program.throttleAxis, smoothedRpm, atlasThrottle, selection)
        cellLoader.request(
            selection.key(),
            smoothedRpm,
            atlasThrottle,
            engineProgramSelection.program,
        )
        val cell = cellLoader.acquireCurrentOrThrow()
        cell.fillWeights(program, smoothedRpm, atlasThrottle, weights)
        val loadGainIgnoringSolo = trackGain(
            LOAD_TRACK_ID,
            target.programLayerGains.load,
            target.layerMix,
            anySolo = false,
        )
        val coastGainIgnoringSolo = trackGain(
            COAST_TRACK_ID,
            target.programLayerGains.coast,
            target.layerMix,
            anySolo = false,
        )
        effectsView?.updateAndRender(
            target = target,
            blockSeconds = blockSeconds,
            effectiveProgramThrottle = atlasThrottle,
            frameCount = frameCount,
            engineEventLeft = engineEventTransientLeft,
            engineEventRight = engineEventTransientRight,
            effectEventLeft = effectEventLeft,
            effectEventRight = effectEventRight,
            anySolo = anySolo,
            loadProgramGain = loadGain,
            coastProgramGain = coastGain,
            loadProgramGainIgnoringSolo = loadGainIgnoringSolo,
            coastProgramGainIgnoringSolo = coastGainIgnoringSolo,
        )
        val requestedGain = if (target.enabled) {
            gain * (target.tuning.masterGain.coerceIn(0.0, 1.2) / 0.72)
        } else {
            0.0
        }
        val fadeSeconds = if (target.enabled) {
            target.tuning.programFadeMs / 1_000.0
        } else {
            target.tuning.enabledFadeMs / 1_000.0
        }.coerceAtLeast(0.001)
        val outputAlpha = 1.0 - exp(-1.0 / (SAMPLE_RATE * fadeSeconds))
        val programTransitionStep = 1.0 / (SAMPLE_RATE * fadeSeconds)

        var outputIndex = 0
        outputHistory.beginBlock()
        try {
            repeat(frameCount) {
                outputGain += (requestedGain - outputGain) * outputAlpha
                val engineBedLeft = mixedTransitioningProgramSample(cell, channel = 0)
                val engineBedRight = mixedTransitioningProgramSample(cell, channel = 1)
                outputHistory.append(engineBedLeft, engineBedRight)
                val frame = outputIndex / CHANNELS
                limiter.process(
                    atlasHostMixInput(
                        engineBed = engineBedLeft,
                        engineTransient = engineEventTransientLeft[frame],
                        effectEvent = effectEventLeft[frame],
                        contract = hostMix,
                    ),
                    atlasHostMixInput(
                        engineBed = engineBedRight,
                        engineTransient = engineEventTransientRight[frame],
                        effectEvent = effectEventRight[frame],
                        contract = hostMix,
                    ),
                    limitedFrame,
                )
                output[outputIndex] = toPcm16(limitedFrame[0] * outputGain)
                output[outputIndex + 1] = toPcm16(limitedFrame[1] * outputGain)
                outputIndex += CHANNELS
                cell.advance(smoothedRpm)
                engineProgramTransition = (engineProgramTransition + programTransitionStep).coerceAtMost(1.0)
            }
        } finally {
            outputHistory.endBlock()
            cellLoader.release(cell)
        }
        val audibleGain = outputGain.coerceIn(0.0, 1.0)
        meterLevels[0] = (audibleGain * engineProgramSelection.loadMeterGain).coerceIn(0.0, 1.0)
        meterLevels[1] = (audibleGain * engineProgramSelection.coastMeterGain).coerceIn(0.0, 1.0)
        effectsView?.writeMeters(target, meterLevels, offset = 2)
    }

    override fun writeLayerOutputLevels(target: EngineAudioFrame, destination: DoubleArray) {
        require(destination.size == meterLevels.size)
        meterLevels.copyInto(destination)
    }

    override fun close() {
        cellLoader.close()
        effectsView?.close()
    }

    private fun beginEngineProgramTransition(selection: AtlasEngineProgramSelection) {
        if (selection.program != activeEngineProgram) {
            previousEngineProgram = activeEngineProgram
            previousEngineProgramGain = activeEngineProgramGain
            activeEngineProgram = selection.program
            engineProgramTransition = 0.0
        }
        activeEngineProgramGain = selection.postMasterGain
    }

    private fun mixedTransitioningProgramSample(cell: HotAtlasCell, channel: Int): Double {
        val current = mixedProgramSample(cell, activeEngineProgram, channel) * activeEngineProgramGain
        if (engineProgramTransition >= 1.0 || previousEngineProgram == activeEngineProgram) return current
        val previous = mixedProgramSample(cell, previousEngineProgram, channel) * previousEngineProgramGain

        return previous + (current - previous) * engineProgramTransition
    }

    private fun mixedProgramSample(
        cell: HotAtlasCell,
        engineProgram: AtlasEngineProgram,
        channel: Int,
    ): Double =
        (if (weights[0] > 0.0) cell.lowerLower.sampleAt(engineProgram, channel) * weights[0] else 0.0) +
            (if (weights[1] > 0.0) cell.upperLower.sampleAt(engineProgram, channel) * weights[1] else 0.0) +
            (if (weights[2] > 0.0) cell.lowerUpper.sampleAt(engineProgram, channel) * weights[2] else 0.0) +
            (if (weights[3] > 0.0) cell.upperUpper.sampleAt(engineProgram, channel) * weights[3] else 0.0)

    private fun trackGain(
        id: String,
        groupGain: Double,
        controls: Map<String, LayerMixControl>,
        anySolo: Boolean,
    ): Double {
        val control = controls[id] ?: LayerMixControl.DEFAULT
        if (control.muted || (anySolo && !control.solo)) return 0.0
        return groupGain.coerceIn(0.0, LayerMixControl.MAX_GAIN_MULTIPLIER) *
            control.volume.coerceIn(0.0, LayerMixControl.MAX_GAIN_MULTIPLIER)
    }

    companion object {
        const val LOAD_TRACK_ID = "atlas_engine_load"
        const val COAST_TRACK_ID = "atlas_engine_coast"

        fun load(
            profile: EngineSampleProfile,
            perspective: EngineSoundPerspective,
            fileResolver: AtlasShardFileResolver,
            effectsView: AtlasAudioSessionEffects.View,
            initialSource: PrimaryEngineLayerSource,
        ): FullEventAtlasRenderer {
            val atlas = requireNotNull(profile.atlasProgram) { "Profile is not an atlas profile" }
            atlas.requirePlaybackReady()
            val program = atlas.perspective(perspective)
            val initialThrottle = when (initialSource) {
                PrimaryEngineLayerSource.LOAD -> 1.0
                PrimaryEngineLayerSource.COAST -> 0.0
                PrimaryEngineLayerSource.FMOD_MIX -> 0.0
            }
            val initialEngineProgram = atlasEngineProgramForSource(initialSource)
            val factory = MappedAtlasNodeRegionFactory(
                program,
                fileResolver,
                atlas.resourceBounds(perspective).engineMaximumMappedShardInstancesDuringCellTransition,
            )
            val history = AtlasOutputHistory()
            var loader: AtlasHotCellLoader? = null
            try {
                loader = AtlasHotCellLoader(
                    program = program,
                    factory = factory,
                    initialRpm = profile.idleRpm,
                    initialThrottle = initialThrottle,
                    initialEngineProgram = initialEngineProgram,
                    outputHistory = history,
                )
                return FullEventAtlasRenderer(
                    profile,
                    perspective,
                    program,
                    loader,
                    history,
                    effectsView,
                    initialEngineProgram,
                )
            } catch (error: Throwable) {
                loader?.close()
                throw error
            }
        }

        internal fun createForTest(
            profile: EngineSampleProfile,
            perspective: EngineSoundPerspective,
            factory: AtlasNodeRegionFactory,
            initialSource: PrimaryEngineLayerSource,
        ): FullEventAtlasRenderer {
            val atlas = requireNotNull(profile.atlasProgram)
            val program = atlas.perspective(perspective)
            val initialThrottle = if (initialSource == PrimaryEngineLayerSource.LOAD) 1.0 else 0.0
            val initialEngineProgram = atlasEngineProgramForSource(initialSource)
            val history = AtlasOutputHistory()
            return FullEventAtlasRenderer(
                profile,
                perspective,
                program,
                AtlasHotCellLoader(
                    program,
                    factory,
                    profile.idleRpm,
                    initialThrottle,
                    initialEngineProgram,
                    history,
                ),
                history,
                null,
                initialEngineProgram,
            )
        }

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val MAX_RENDER_FRAMES = 8_192
    }
}

private fun toPcm16(value: Double): Short =
    (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

internal fun effectiveAtlasProgramThrottle(program: AtlasEngineProgram, liveThrottle: Double): Double =
    when (program) {
        AtlasEngineProgram.LOAD_ONLY -> 1.0
        AtlasEngineProgram.COAST_ONLY -> 0.0
        AtlasEngineProgram.FULL -> liveThrottle.coerceIn(0.0, 1.0)
    }

internal data class AtlasEngineProgramSelection(
    val program: AtlasEngineProgram,
    val postMasterGain: Double,
    val loadMeterGain: Double,
    val coastMeterGain: Double,
)

internal fun selectAtlasEngineProgram(
    source: PrimaryEngineLayerSource,
    liveThrottle: Double,
    loadGain: Double,
    coastGain: Double,
    anySolo: Boolean,
    loadSolo: Boolean,
    coastSolo: Boolean,
): AtlasEngineProgramSelection {
    val throttle = liveThrottle.coerceIn(0.0, 1.0)
    val selectedProgram = when {
        anySolo && loadSolo && !coastSolo -> AtlasEngineProgram.LOAD_ONLY
        anySolo && coastSolo && !loadSolo -> AtlasEngineProgram.COAST_ONLY
        anySolo && !loadSolo && !coastSolo -> atlasEngineProgramForSource(source)
        !anySolo && loadGain <= 0.0 && coastGain > 0.0 -> AtlasEngineProgram.COAST_ONLY
        !anySolo && coastGain <= 0.0 && loadGain > 0.0 -> AtlasEngineProgram.LOAD_ONLY
        else -> atlasEngineProgramForSource(source)
    }
    val audible = !anySolo || loadSolo || coastSolo
    val postMasterGain = if (!audible) {
        0.0
    } else {
        when (selectedProgram) {
            AtlasEngineProgram.LOAD_ONLY -> loadGain
            AtlasEngineProgram.COAST_ONLY -> coastGain
            AtlasEngineProgram.FULL -> coastGain + (loadGain - coastGain) * throttle
        }
    }
    val loadMeterGain = when (selectedProgram) {
        AtlasEngineProgram.LOAD_ONLY -> postMasterGain
        AtlasEngineProgram.COAST_ONLY -> 0.0
        AtlasEngineProgram.FULL -> if (audible) loadGain * throttle else 0.0
    }
    val coastMeterGain = when (selectedProgram) {
        AtlasEngineProgram.LOAD_ONLY -> 0.0
        AtlasEngineProgram.COAST_ONLY -> postMasterGain
        AtlasEngineProgram.FULL -> if (audible) coastGain * (1.0 - throttle) else 0.0
    }

    return AtlasEngineProgramSelection(
        program = selectedProgram,
        postMasterGain = postMasterGain,
        loadMeterGain = loadMeterGain,
        coastMeterGain = coastMeterGain,
    )
}

internal fun atlasEngineProgramForSource(source: PrimaryEngineLayerSource): AtlasEngineProgram = when (source) {
    PrimaryEngineLayerSource.LOAD -> AtlasEngineProgram.LOAD_ONLY
    PrimaryEngineLayerSource.COAST -> AtlasEngineProgram.COAST_ONLY
    PrimaryEngineLayerSource.FMOD_MIX -> AtlasEngineProgram.FULL
}

/** The two host gain classes must be summed exactly in this order before the post-sum limiter. */
internal fun atlasHostMixInput(
    engineBed: Double,
    engineTransient: Double,
    effectEvent: Double,
    contract: AtlasHostMixContract,
): Double =
    (engineBed + engineTransient) * contract.engineEventHostGainLinear +
        effectEvent * contract.effectEventHostGainLinear
