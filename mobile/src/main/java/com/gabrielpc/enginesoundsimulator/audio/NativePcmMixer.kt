package com.gabrielpc.enginesoundsimulator.audio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Persistent native installed-pack mixer. All arrays are allocated once during profile preparation. */
internal class NativePcmMixer private constructor(
    nativeHandle: Long,
    loopCount: Int,
    effectCount: Int,
    dynamicEffectCount: Int,
) : Closeable {
    private val handle = AtomicLong(nativeHandle)
    val loopTargets = DoubleArray(loopCount)
    val loopIncrements = DoubleArray(loopCount)
    /** One global arbiter gates mixing; virtual sources still advance natively. */
    val loopReal = IntArray(loopCount) { 1 }
    val effectTargets = DoubleArray(effectCount)
    val effectIncrements = DoubleArray(effectCount)
    val effectTriggers = IntArray(effectCount)
    val effectStartOffsets = IntArray(effectCount)
    val effectReal = IntArray(effectCount) { 1 }
    /**
     * Shared real/software voices used by overlapping authored engine transients. Commands are
     * zero for no change, a positive fixed-effect template index plus one for start, or -1 to stop.
     */
    val dynamicEffectTargets = DoubleArray(dynamicEffectCount)
    val dynamicEffectIncrements = DoubleArray(dynamicEffectCount) { 1.0 }
    val dynamicEffectCommands = IntArray(dynamicEffectCount)
    /** Exact frame inside the next render burst at which a positive start command becomes active. */
    val dynamicEffectStartOffsets = IntArray(dynamicEffectCount)
    /** Retained logical state used when a virtual source is promoted into a software slot. */
    val dynamicEffectStartPhases = DoubleArray(dynamicEffectCount)
    val dynamicEffectStartGains = DoubleArray(dynamicEffectCount)
    /** Exact source-bound zero transition and phase-advance allowance for this writer burst. */
    val dynamicEffectZeroTransitionActive = IntArray(dynamicEffectCount)
    val dynamicEffectZeroTransitionElapsedFrames = IntArray(dynamicEffectCount)
    val dynamicEffectZeroTransitionRetainFrames = IntArray(dynamicEffectCount)
    val dynamicEffectZeroTransitionFadeFrames = IntArray(dynamicEffectCount)
    val dynamicEffectZeroTransitionStartGains = DoubleArray(dynamicEffectCount)
    val dynamicEffectPhaseAdvanceFrames = IntArray(dynamicEffectCount) { Int.MAX_VALUE }
    /** Signed decoded capture-PCM frame correction, consumed once at the next render boundary. */
    val dynamicEffectRestorePhaseOffsets = DoubleArray(dynamicEffectCount)
    /** Native writes current gains and active flags back into these preallocated arrays. */
    val loopGains = DoubleArray(loopCount)
    val effectGains = DoubleArray(effectCount)
    val effectActive = IntArray(effectCount)
    val dynamicEffectGains = DoubleArray(dynamicEffectCount)
    val dynamicEffectActive = IntArray(dynamicEffectCount)
    private val statusLongs = LongArray(3)
    private val statusDoubles = DoubleArray(1)

    fun render(
        output: ShortArray,
        frameCount: Int,
        targetMaster: Double,
        targetProfileGain: Double,
        targetEnabled: Double,
        targetContinuous: Double,
        masterAlpha: Double,
        profileAlpha: Double,
        enabledAlpha: Double,
        layerAlpha: Double,
    ) {
        nativeRender(
            handle.get(), output, frameCount,
            loopTargets, loopIncrements, loopReal, loopGains,
            effectTargets, effectIncrements, effectTriggers, effectStartOffsets,
            effectReal, effectGains, effectActive,
            dynamicEffectTargets, dynamicEffectIncrements, dynamicEffectCommands,
            dynamicEffectStartOffsets, dynamicEffectStartPhases, dynamicEffectStartGains,
            dynamicEffectZeroTransitionActive, dynamicEffectZeroTransitionElapsedFrames,
            dynamicEffectZeroTransitionRetainFrames, dynamicEffectZeroTransitionFadeFrames,
            dynamicEffectZeroTransitionStartGains, dynamicEffectPhaseAdvanceFrames,
            dynamicEffectRestorePhaseOffsets,
            dynamicEffectGains, dynamicEffectActive,
            targetMaster, targetProfileGain, targetEnabled, targetContinuous,
            masterAlpha, profileAlpha, enabledAlpha, layerAlpha,
            statusLongs, statusDoubles,
        )
        effectTriggers.fill(0)
        effectStartOffsets.fill(0)
        dynamicEffectCommands.fill(0)
        dynamicEffectStartOffsets.fill(0)
        dynamicEffectRestorePhaseOffsets.fill(0.0)
    }

    val framesRendered: Long get() = statusLongs[0]
    val loopWraps: Long get() = statusLongs[1]
    val overRangeSamples: Long get() = statusLongs[2]
    val peak: Double get() = statusDoubles[0]

    override fun close() {
        val active = handle.getAndSet(0L)
        if (active != 0L) nativeRelease(active)
    }

    companion object {
        init {
            // Silent/uninstalled profiles construct an empty mixer before any FLAC decode occurs.
            NativeAudioLibrary.ensureLoaded()
        }

        fun create(
            loops: List<Pair<NativePlanarPcmData, Int>>,
            effects: List<Pair<NativePlanarPcmData, Boolean>>,
            dynamicEffectCount: Int = 0,
        ): NativePcmMixer {
            require(dynamicEffectCount in 0..GlobalVoiceArbiter.AC_SOFTWARE_REAL_VOICE_BUDGET)
            val loopHandles = LongArray(loops.size) { loops[it].first.clip.activeHandle() }
            val loopStarts = IntArray(loops.size) { loops[it].first.loopStartFrame }
            val loopEnds = IntArray(loops.size) { loops[it].first.loopEndFrameExclusive }
            val loopCrossfades = IntArray(loops.size) { loops[it].second }
            val effectHandles = LongArray(effects.size) { effects[it].first.clip.activeHandle() }
            val effectStarts = IntArray(effects.size) { effects[it].first.loopStartFrame }
            val effectEnds = IntArray(effects.size) { effects[it].first.loopEndFrameExclusive }
            val effectLoops = BooleanArray(effects.size) { effects[it].second }
            val handle = nativeCreate(
                loopHandles, loopStarts, loopEnds, loopCrossfades,
                effectHandles, effectStarts, effectEnds, effectLoops, dynamicEffectCount,
            )
            check(handle != 0L) { "Native mixer creation failed" }
            return NativePcmMixer(handle, loops.size, effects.size, dynamicEffectCount)
        }

        private external fun nativeCreate(
            loopHandles: LongArray, loopStarts: IntArray, loopEnds: IntArray, loopCrossfades: IntArray,
            effectHandles: LongArray, effectStarts: IntArray, effectEnds: IntArray, effectLoops: BooleanArray,
            dynamicEffectCount: Int,
        ): Long
        private external fun nativeRender(
            handle: Long, output: ShortArray, frames: Int,
            loopTargets: DoubleArray, loopIncrements: DoubleArray, loopReal: IntArray,
            loopGains: DoubleArray,
            effectTargets: DoubleArray, effectIncrements: DoubleArray, effectTriggers: IntArray,
            effectStartOffsets: IntArray, effectReal: IntArray,
            effectGains: DoubleArray, effectActive: IntArray,
            dynamicEffectTargets: DoubleArray, dynamicEffectIncrements: DoubleArray,
            dynamicEffectCommands: IntArray, dynamicEffectStartOffsets: IntArray,
            dynamicEffectStartPhases: DoubleArray,
            dynamicEffectStartGains: DoubleArray,
            dynamicEffectZeroTransitionActive: IntArray,
            dynamicEffectZeroTransitionElapsedFrames: IntArray,
            dynamicEffectZeroTransitionRetainFrames: IntArray,
            dynamicEffectZeroTransitionFadeFrames: IntArray,
            dynamicEffectZeroTransitionStartGains: DoubleArray,
            dynamicEffectPhaseAdvanceFrames: IntArray,
            dynamicEffectRestorePhaseOffsets: DoubleArray,
            dynamicEffectGains: DoubleArray,
            dynamicEffectActive: IntArray,
            targetMaster: Double, targetProfileGain: Double, targetEnabled: Double, targetContinuous: Double,
            masterAlpha: Double, profileAlpha: Double, enabledAlpha: Double, layerAlpha: Double,
            statusLongs: LongArray, statusDoubles: DoubleArray,
        )
        private external fun nativeRelease(handle: Long)
    }
}
