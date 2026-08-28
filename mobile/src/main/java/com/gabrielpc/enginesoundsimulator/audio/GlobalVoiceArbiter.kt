package com.gabrielpc.enginesoundsimulator.audio

import kotlin.math.max
import kotlin.math.pow

/**
 * One allocation-free software/logical voice arbiter for the complete authored sound graph.
 *
 * Assetto Corsa initializes FMOD 1.08 with 2,048 logical channels and 256 software channels.
 * Every active continuous source, fixed event source, and overlapping program source competes in
 * this one pool. Lower numeric channel priority wins first; within one priority class, higher live
 * audibility wins. The final sequence/index comparison is deliberately only a deterministic Android
 * tie-break. The FMOD oracle does not establish the exact order of equal-priority, exactly-equal-
 * audibility voices from different sources, nor any order inside one 256-frame DSP update.
 *
 * Dynamic (polyphonic) voices keep phase, gain, and playback increment in their logical record.
 * Consequently a virtual voice advances and can later be promoted at its retained position instead
 * of restarting at frame zero.
 */
internal class GlobalVoiceArbiter(
    fixedVoicePriorities: IntArray,
    fixedInitiallyActive: BooleanArray,
    private val programLaneLimits: IntArray,
    private val logicalVoiceLimit: Int = AC_LOGICAL_VOICE_LIMIT,
    private val realVoiceBudget: Int = AC_SOFTWARE_REAL_VOICE_BUDGET,
) {
    val fixedVoiceCount: Int = fixedVoicePriorities.size
    val firstDynamicHandle: Int = fixedVoiceCount
    /** Fixed metadata plus a full logical-channel-sized pool only when polyphony is authored. */
    val recordCapacity: Int = fixedVoiceCount +
        if (programLaneLimits.isEmpty()) 0 else logicalVoiceLimit

    private val active = BooleanArray(recordCapacity)
    private val selectedReal = BooleanArray(recordCapacity)
    private val priorities = IntArray(recordCapacity)
    private val audibilities = DoubleArray(recordCapacity)
    private val sequences = LongArray(recordCapacity)
    private val realSlots = IntArray(recordCapacity) { UNUSED }
    private val realLogicalVoices = IntArray(realVoiceBudget) { UNUSED }
    private val selectionHeap = IntArray(realVoiceBudget)

    private val dynamicPrograms = IntArray(recordCapacity) { UNUSED }
    private val dynamicTracks = IntArray(recordCapacity) { UNUSED }
    private val dynamicFrameCounts = IntArray(recordCapacity)
    private val dynamicPhases = DoubleArray(recordCapacity)
    private val dynamicIncrements = DoubleArray(recordCapacity) { 1.0 }
    private val dynamicGains = DoubleArray(recordCapacity)
    private val dynamicTargetGains = DoubleArray(recordCapacity)
    /** Frames remaining before a newly scheduled source reaches its exact in-burst start. */
    private val dynamicStartDelays = IntArray(recordCapacity)
    private val dynamicRetiring = BooleanArray(recordCapacity)
    /** Per-source exact-zero output/lifetime contract, copied into each admitted logical voice. */
    private val dynamicExactZeroLifecycleEnabled = BooleanArray(recordCapacity)
    private val dynamicHoldPhaseAfterLatency = BooleanArray(recordCapacity)
    private val dynamicZeroHoldLatencies = IntArray(recordCapacity)
    private val dynamicZeroTransitionRetainFrames = IntArray(recordCapacity)
    private val dynamicZeroTransitionFadeFrames = IntArray(recordCapacity)
    private val dynamicRestorePhaseOffsets = DoubleArray(recordCapacity)
    /** One-shot correction still owed to an already-bound physical/native voice. */
    private val dynamicPendingPhysicalRestoreOffsets = DoubleArray(recordCapacity)
    private val dynamicConsecutiveExactZeroFrames = IntArray(recordCapacity)
    private val dynamicZeroTransitionStartGains = DoubleArray(recordCapacity)
    private val dynamicExactZeroGated = BooleanArray(recordCapacity)
    private val activeByProgram = IntArray(programLaneLimits.size)

    private var nextSequence = 1L
    private var heapSize = 0

    var activeLogicalVoices: Int = 0
        private set
    var activeFixedVoices: Int = 0
        private set
    var activeDynamicVoices: Int = 0
        private set
    var activeRealVoices: Int = 0
        private set
    val activeVirtualVoices: Int get() = activeLogicalVoices - activeRealVoices
    var rejectedTriggers: Long = 0L
        private set
    var stolenLogicalVoices: Long = 0L
        private set

    init {
        require(fixedInitiallyActive.size == fixedVoiceCount)
        require(fixedVoiceCount <= logicalVoiceLimit) {
            "Fixed sound graph exceeds Assetto Corsa's logical-channel limit"
        }
        require(logicalVoiceLimit in 1..AC_LOGICAL_VOICE_LIMIT) {
            "Logical voice budget exceeds Assetto Corsa's FMOD initialization limit"
        }
        require(realVoiceBudget in 1..AC_SOFTWARE_REAL_VOICE_BUDGET) {
            "Real voice budget exceeds Assetto Corsa's stock software-channel count"
        }
        require(realVoiceBudget <= logicalVoiceLimit)
        require(programLaneLimits.all { it in 1..logicalVoiceLimit }) {
            "Program lane limits must fit the logical voice budget"
        }
        var fixed = 0
        while (fixed < fixedVoiceCount) {
            priorities[fixed] = checkedPriority(fixedVoicePriorities[fixed])
            if (fixedInitiallyActive[fixed]) activateFixedInternal(fixed, refreshSequence = true)
            fixed += 1
        }
        rebalance()
    }

    fun isFixedActive(fixedIndex: Int): Boolean {
        require(fixedIndex in 0 until fixedVoiceCount)
        return active[fixedIndex]
    }

    fun isFixedReal(fixedIndex: Int): Boolean {
        require(fixedIndex in 0 until fixedVoiceCount)
        return selectedReal[fixedIndex]
    }

    fun setFixedAudibility(fixedIndex: Int, audibility: Double) {
        require(fixedIndex in 0 until fixedVoiceCount)
        audibilities[fixedIndex] = sanitizeAudibility(audibility)
    }

    /** Activates or retriggers a fixed source. A retrigger receives a fresh deterministic age. */
    fun activateFixed(fixedIndex: Int) {
        require(fixedIndex in 0 until fixedVoiceCount)
        activateFixedInternal(fixedIndex, refreshSequence = true)
    }

    fun deactivateFixed(fixedIndex: Int) {
        require(fixedIndex in 0 until fixedVoiceCount)
        if (active[fixedIndex]) deactivateRecord(fixedIndex, stolen = false)
    }

    /**
     * Admits an overlapping authored source and returns its logical handle. Program-lane overflow
     * is rejected. At the global 2,048-channel ceiling, admission replaces the current worst
     * logical source; the reference fixture proves this behavior for fixed-source admission but
     * does not establish FMOD's universal equal-tie victim rule.
     */
    fun triggerDynamic(
        programIndex: Int,
        trackIndex: Int,
        priority: Int,
        initialAudibility: Double,
        frameCount: Int,
        startDelayFrames: Int = 0,
        zeroGainVirtualization: ZeroGainVirtualizationSpec =
            ZeroGainVirtualizationSpec.NOT_APPLICABLE,
    ): Int {
        require(programIndex in programLaneLimits.indices)
        require(trackIndex >= 0)
        require(frameCount > 1)
        require(startDelayFrames >= 0)
        if (activeByProgram[programIndex] >= programLaneLimits[programIndex]) {
            rejectedTriggers += 1L
            return REJECTED
        }
        ensureLogicalAdmission()
        val logical = findUnusedDynamicRecord()
        check(logical != UNUSED) { "Logical admission did not free a dynamic record" }
        active[logical] = true
        priorities[logical] = checkedPriority(priority)
        audibilities[logical] = sanitizeAudibility(initialAudibility)
        sequences[logical] = nextSequence++
        dynamicPrograms[logical] = programIndex
        dynamicTracks[logical] = trackIndex
        dynamicFrameCounts[logical] = frameCount
        dynamicPhases[logical] = 0.0
        dynamicIncrements[logical] = 1.0
        dynamicGains[logical] = 0.0
        dynamicTargetGains[logical] = 0.0
        dynamicStartDelays[logical] = startDelayFrames
        dynamicRetiring[logical] = false
        dynamicExactZeroLifecycleEnabled[logical] =
            zeroGainVirtualization.exactZeroLifecycleEnabled
        dynamicHoldPhaseAfterLatency[logical] = zeroGainVirtualization.holdsPhaseAfterLatency
        dynamicZeroHoldLatencies[logical] = zeroGainVirtualization.phaseHoldLatencyWriterFrames
        val zeroTransition = zeroGainVirtualization.transition
        dynamicZeroTransitionRetainFrames[logical] =
            zeroTransition?.retainPreZeroGainWriterFrames ?: 0
        dynamicZeroTransitionFadeFrames[logical] = zeroTransition?.linearFadeWriterFrames ?: 0
        dynamicRestorePhaseOffsets[logical] =
            zeroTransition?.restoreCapturePcmPhaseOffsetFrames ?: 0.0
        dynamicPendingPhysicalRestoreOffsets[logical] = 0.0
        dynamicConsecutiveExactZeroFrames[logical] = 0
        dynamicZeroTransitionStartGains[logical] = 0.0
        dynamicExactZeroGated[logical] = false
        activeByProgram[programIndex] += 1
        activeLogicalVoices += 1
        activeDynamicVoices += 1
        return logical
    }

    fun isDynamicActive(logical: Int): Boolean =
        logical in firstDynamicHandle until recordCapacity && active[logical]

    fun isDynamicRetiring(logical: Int): Boolean {
        require(logical in firstDynamicHandle until recordCapacity)
        return active[logical] && dynamicRetiring[logical]
    }

    fun dynamicTrack(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicTracks[logical]
    }

    fun dynamicProgram(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicPrograms[logical].also {
            require(it >= 0) { "Retiring logical voice no longer belongs to an active owner" }
        }
    }

    fun dynamicPhase(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicPhases[logical]
    }

    fun dynamicIncrement(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicIncrements[logical]
    }

    fun dynamicGain(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicGains[logical]
    }

    fun dynamicTargetGain(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicTargetGains[logical]
    }

    fun dynamicStartDelayFrames(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicStartDelays[logical]
    }

    fun dynamicExactZeroGated(logical: Int): Boolean {
        requireActiveDynamic(logical)
        return dynamicExactZeroGated[logical]
    }

    fun dynamicZeroTransitionElapsedFrames(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicConsecutiveExactZeroFrames[logical]
    }

    fun dynamicZeroTransitionRetainFrames(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicZeroTransitionRetainFrames[logical]
    }

    fun dynamicZeroTransitionFadeFrames(logical: Int): Int {
        requireActiveDynamic(logical)
        return dynamicZeroTransitionFadeFrames[logical]
    }

    fun dynamicZeroTransitionStartGain(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicZeroTransitionStartGains[logical]
    }

    /**
     * Consumes the fractional decoded-PCM correction owed to the currently bound software voice.
     * The logical cursor is corrected at the positive-gain edge; this separate primitive keeps an
     * already-bound native/Kotlin cursor bit-for-bit aligned without rebinding or allocating.
     */
    fun consumeDynamicPhysicalRestorePhaseOffset(logical: Int): Double {
        requireActiveDynamic(logical)
        return dynamicPendingPhysicalRestoreOffsets[logical].also {
            dynamicPendingPhysicalRestoreOffsets[logical] = 0.0
        }
    }

    /** Number of active source frames allowed to advance in the next writer burst. */
    fun dynamicPhaseAdvanceFrames(logical: Int, renderedFrames: Int): Int {
        requireActiveDynamic(logical)
        require(renderedFrames >= 0)
        val activeFrames = (renderedFrames - dynamicStartDelays[logical]).coerceAtLeast(0)
        if (!dynamicExactZeroGated[logical] || !dynamicHoldPhaseAfterLatency[logical]) {
            return activeFrames
        }
        val remainingBeforeHold = (
            dynamicZeroHoldLatencies[logical] - dynamicConsecutiveExactZeroFrames[logical]
        ).coerceAtLeast(0)
        return minOf(activeFrames, remainingBeforeHold)
    }

    fun sequence(logical: Int): Long {
        require(logical in 0 until recordCapacity && active[logical])
        return sequences[logical]
    }

    fun updateDynamicMix(
        logical: Int,
        targetGain: Double,
        increment: Double,
        authoredExactZero: Boolean = false,
    ) {
        requireActiveDynamic(logical)
        require(increment >= 0.0 && increment.isFinite())
        val exactZeroGate = !dynamicRetiring[logical] && authoredExactZero &&
            dynamicExactZeroLifecycleEnabled[logical]
        val restoringFromHeldPhase = dynamicExactZeroGated[logical] &&
            dynamicHoldPhaseAfterLatency[logical] &&
            dynamicConsecutiveExactZeroFrames[logical] >= dynamicZeroHoldLatencies[logical] &&
            !exactZeroGate
        if (restoringFromHeldPhase) {
            val previousPhase = dynamicPhases[logical]
            val correctedPhase = (previousPhase + dynamicRestorePhaseOffsets[logical]).coerceIn(
                0.0,
                (dynamicFrameCounts[logical] - 1).toDouble(),
            )
            dynamicPhases[logical] = correctedPhase
            dynamicPendingPhysicalRestoreOffsets[logical] += correctedPhase - previousPhase
        }
        val enteringExactZero = exactZeroGate && !dynamicExactZeroGated[logical]
        if (enteringExactZero) {
            dynamicConsecutiveExactZeroFrames[logical] = 0
            dynamicZeroTransitionStartGains[logical] = dynamicGains[logical].coerceAtLeast(0.0)
        } else if (!exactZeroGate) {
            dynamicConsecutiveExactZeroFrames[logical] = 0
            dynamicZeroTransitionStartGains[logical] = 0.0
        }
        dynamicExactZeroGated[logical] = exactZeroGate
        val target = if (dynamicRetiring[logical] || exactZeroGate) 0.0 else max(0.0, targetGain)
        dynamicTargetGains[logical] = target
        dynamicIncrements[logical] = increment
        if (exactZeroGate) {
            // Each source owns an exact, finite writer-frame transition. Do not substitute the
            // ordinary asymptotic layer smoother or a family-global immediate mute.
            dynamicGains[logical] = zeroTransitionGainAt(
                logical,
                dynamicConsecutiveExactZeroFrames[logical],
            )
            audibilities[logical] = dynamicGains[logical]
        } else {
            audibilities[logical] = max(dynamicGains[logical], target)
        }
    }

    /**
     * STOP_ALLOWFADEOUT ownership boundary for a persistent program. Old source generations keep
     * advancing with a zero target until inaudible, but stop consuming the new owner's lane count
     * and can never regain its later program gain.
     */
    fun retireDynamicVoicesForProgram(programIndex: Int) {
        require(programIndex in programLaneLimits.indices)
        var logical = firstDynamicHandle
        while (logical < recordCapacity) {
            if (active[logical] && dynamicPrograms[logical] == programIndex) {
                activeByProgram[programIndex] -= 1
                dynamicPrograms[logical] = RETIRED_PROGRAM
                dynamicRetiring[logical] = true
                dynamicTargetGains[logical] = 0.0
                audibilities[logical] = dynamicGains[logical]
            }
            logical += 1
        }
    }

    /** Selects the one global set of real/software voices at this render-buffer boundary. */
    fun rebalance() {
        selectedReal.fill(false)
        heapSize = 0
        var logical = 0
        while (logical < recordCapacity) {
            if (active[logical]) offerSelectionCandidate(logical)
            logical += 1
        }
        var selectedIndex = 0
        while (selectedIndex < heapSize) {
            selectedReal[selectionHeap[selectedIndex]] = true
            selectedIndex += 1
        }
        activeRealVoices = heapSize

        // Keep physical dynamic bindings stable for voices that remain real.
        var realSlot = 0
        while (realSlot < realLogicalVoices.size) {
            val bound = realLogicalVoices[realSlot]
            if (bound != UNUSED && (!active[bound] || !selectedReal[bound])) {
                realLogicalVoices[realSlot] = UNUSED
                realSlots[bound] = UNUSED
            }
            realSlot += 1
        }
        logical = firstDynamicHandle
        while (logical < recordCapacity) {
            if (active[logical] && selectedReal[logical] && realSlots[logical] == UNUSED) {
                realSlot = findUnusedRealSlot()
                check(realSlot != UNUSED)
                realLogicalVoices[realSlot] = logical
                realSlots[logical] = realSlot
            }
            logical += 1
        }
    }

    fun logicalForRealSlot(realSlot: Int): Int {
        require(realSlot in realLogicalVoices.indices)
        return realLogicalVoices[realSlot]
    }

    fun realSlotForDynamic(logical: Int): Int {
        requireActiveDynamic(logical)
        return realSlots[logical]
    }

    /**
     * Advances every dynamic source, including virtual sources, through one completed render
     * buffer. [gainRetention] is `(1 - layerAlpha)^frames` for the renderer's sample ramp.
     */
    fun advanceDynamicVoices(renderedFrames: Int, gainRetention: Double) {
        require(renderedFrames >= 0)
        require(gainRetention in 0.0..1.0)
        var logical = firstDynamicHandle
        while (logical < recordCapacity) {
            if (active[logical]) {
                val delay = dynamicStartDelays[logical]
                val activeFrames = (renderedFrames - delay).coerceAtLeast(0)
                val phaseAdvanceFrames = if (
                    dynamicExactZeroGated[logical] && dynamicHoldPhaseAfterLatency[logical]
                ) {
                    minOf(
                        activeFrames,
                        (dynamicZeroHoldLatencies[logical] -
                            dynamicConsecutiveExactZeroFrames[logical]).coerceAtLeast(0),
                    )
                } else {
                    activeFrames
                }
                dynamicStartDelays[logical] = (delay - renderedFrames).coerceAtLeast(0)
                val target = dynamicTargetGains[logical]
                val activeRetention = when {
                    activeFrames == 0 -> 1.0
                    activeFrames == renderedFrames -> gainRetention
                    renderedFrames == 0 -> 1.0
                    else -> gainRetention.pow(activeFrames.toDouble() / renderedFrames.toDouble())
                }
                dynamicGains[logical] = if (dynamicExactZeroGated[logical]) {
                    val previousElapsed = dynamicConsecutiveExactZeroFrames[logical]
                    val maximumRelevantFrames = maxOf(
                        dynamicZeroHoldLatencies[logical],
                        dynamicZeroTransitionRetainFrames[logical] +
                            dynamicZeroTransitionFadeFrames[logical],
                    )
                    dynamicConsecutiveExactZeroFrames[logical] =
                        saturatingAdvance(previousElapsed, activeFrames, maximumRelevantFrames)
                    zeroTransitionGainAt(logical, dynamicConsecutiveExactZeroFrames[logical])
                } else {
                    target + (dynamicGains[logical] - target) * activeRetention
                }
                dynamicPhases[logical] += dynamicIncrements[logical] * phaseAdvanceFrames
                val finished = dynamicPhases[logical] >= dynamicFrameCounts[logical] - 1.0
                val fadedOwnerTail = dynamicRetiring[logical] &&
                    dynamicGains[logical] <= SILENCE_GAIN && target <= SILENCE_GAIN
                if (finished || fadedOwnerTail) {
                    deactivateRecord(logical, stolen = false)
                } else {
                    audibilities[logical] = max(dynamicGains[logical], target)
                }
            }
            logical += 1
        }
    }

    fun activeVoicesForProgram(programIndex: Int): Int {
        require(programIndex in activeByProgram.indices)
        return activeByProgram[programIndex]
    }

    private fun activateFixedInternal(fixedIndex: Int, refreshSequence: Boolean) {
        if (!active[fixedIndex]) {
            ensureLogicalAdmission()
            active[fixedIndex] = true
            activeLogicalVoices += 1
            activeFixedVoices += 1
        }
        if (refreshSequence) sequences[fixedIndex] = nextSequence++
    }

    private fun ensureLogicalAdmission() {
        if (activeLogicalVoices < logicalVoiceLimit) return
        val victim = findWorstActiveLogical()
        check(victim != UNUSED)
        deactivateRecord(victim, stolen = true)
    }

    private fun deactivateRecord(logical: Int, stolen: Boolean) {
        if (!active[logical]) return
        val slot = realSlots[logical]
        if (slot != UNUSED) {
            realLogicalVoices[slot] = UNUSED
            realSlots[logical] = UNUSED
        }
        active[logical] = false
        selectedReal[logical] = false
        activeLogicalVoices -= 1
        if (logical < fixedVoiceCount) {
            activeFixedVoices -= 1
        } else {
            val program = dynamicPrograms[logical]
            if (program >= 0) activeByProgram[program] -= 1
            activeDynamicVoices -= 1
            dynamicPrograms[logical] = UNUSED
            dynamicTracks[logical] = UNUSED
            dynamicFrameCounts[logical] = 0
            dynamicPhases[logical] = 0.0
            dynamicIncrements[logical] = 1.0
            dynamicGains[logical] = 0.0
            dynamicTargetGains[logical] = 0.0
            dynamicStartDelays[logical] = 0
            dynamicRetiring[logical] = false
            dynamicExactZeroLifecycleEnabled[logical] = false
            dynamicHoldPhaseAfterLatency[logical] = false
            dynamicZeroHoldLatencies[logical] = 0
            dynamicZeroTransitionRetainFrames[logical] = 0
            dynamicZeroTransitionFadeFrames[logical] = 0
            dynamicRestorePhaseOffsets[logical] = 0.0
            dynamicPendingPhysicalRestoreOffsets[logical] = 0.0
            dynamicConsecutiveExactZeroFrames[logical] = 0
            dynamicZeroTransitionStartGains[logical] = 0.0
            dynamicExactZeroGated[logical] = false
        }
        audibilities[logical] = 0.0
        if (stolen) stolenLogicalVoices += 1L
    }

    private fun offerSelectionCandidate(logical: Int) {
        if (heapSize < realVoiceBudget) {
            selectionHeap[heapSize] = logical
            siftWorstUp(heapSize)
            heapSize += 1
            return
        }
        val worstSelected = selectionHeap[0]
        if (isBetter(logical, worstSelected)) {
            selectionHeap[0] = logical
            siftWorstDown(0)
        }
    }

    private fun siftWorstUp(start: Int) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) ushr 1
            if (!isWorse(selectionHeap[child], selectionHeap[parent])) return
            val swap = selectionHeap[parent]
            selectionHeap[parent] = selectionHeap[child]
            selectionHeap[child] = swap
            child = parent
        }
    }

    private fun siftWorstDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= heapSize) return
            val right = left + 1
            var worseChild = left
            if (right < heapSize && isWorse(selectionHeap[right], selectionHeap[left])) {
                worseChild = right
            }
            if (!isWorse(selectionHeap[worseChild], selectionHeap[parent])) return
            val swap = selectionHeap[parent]
            selectionHeap[parent] = selectionHeap[worseChild]
            selectionHeap[worseChild] = swap
            parent = worseChild
        }
    }

    private fun findWorstActiveLogical(): Int {
        var selected = UNUSED
        var logical = 0
        while (logical < recordCapacity) {
            if (active[logical] && (selected == UNUSED || isWorse(logical, selected))) {
                selected = logical
            }
            logical += 1
        }
        return selected
    }

    private fun findUnusedDynamicRecord(): Int {
        var logical = firstDynamicHandle
        while (logical < recordCapacity) {
            if (!active[logical]) return logical
            logical += 1
        }
        return UNUSED
    }

    private fun findUnusedRealSlot(): Int {
        var slot = 0
        while (slot < realLogicalVoices.size) {
            if (realLogicalVoices[slot] == UNUSED) return slot
            slot += 1
        }
        return UNUSED
    }

    private fun isBetter(left: Int, right: Int): Boolean {
        val leftPriority = priorities[left]
        val rightPriority = priorities[right]
        if (leftPriority != rightPriority) return leftPriority < rightPriority
        val leftAudibility = audibilities[left]
        val rightAudibility = audibilities[right]
        if (leftAudibility != rightAudibility) return leftAudibility > rightAudibility
        val leftSequence = sequences[left]
        val rightSequence = sequences[right]
        if (leftSequence != rightSequence) return leftSequence < rightSequence
        return left < right
    }

    private fun isWorse(left: Int, right: Int): Boolean = isBetter(right, left)

    private fun requireActiveDynamic(logical: Int) {
        require(logical in firstDynamicHandle until recordCapacity && active[logical]) {
            "Dynamic logical voice is inactive"
        }
    }

    private fun checkedPriority(priority: Int): Int = priority.also {
        require(it in FMOD_HIGHEST_PRIORITY..FMOD_LOWEST_PRIORITY) {
            "FMOD channel priority must be in 0..256"
        }
    }

    private fun sanitizeAudibility(value: Double): Double =
        if (value.isFinite()) max(0.0, value) else 0.0

    private fun zeroTransitionGainAt(logical: Int, elapsedWriterFrames: Int): Double {
        val retainedGain = dynamicZeroTransitionStartGains[logical]
        val retainFrames = dynamicZeroTransitionRetainFrames[logical]
        if (elapsedWriterFrames < retainFrames) return retainedGain
        val fadeFrames = dynamicZeroTransitionFadeFrames[logical]
        if (fadeFrames <= 0) return 0.0
        val fadeElapsed = elapsedWriterFrames - retainFrames
        if (fadeElapsed >= fadeFrames) return 0.0
        return retainedGain * (fadeFrames - fadeElapsed).toDouble() / fadeFrames.toDouble()
    }

    private fun saturatingAdvance(current: Int, delta: Int, maximum: Int): Int {
        if (maximum <= 0 || current >= maximum) return maximum.coerceAtLeast(0)
        val remaining = maximum - current
        return if (delta >= remaining) maximum else current + delta
    }

    companion object {
        const val AC_LOGICAL_VOICE_LIMIT = 2_048
        const val AC_SOFTWARE_REAL_VOICE_BUDGET = 256
        const val FMOD_HIGHEST_PRIORITY = 0
        const val FMOD_DEFAULT_EVENT_PRIORITY = 128
        const val FMOD_AUTHORED_ENGINE_PRIORITY = 64
        const val FMOD_LOWEST_PRIORITY = 256
        const val REJECTED = -1
        const val NO_LOGICAL_VOICE = -1
        private const val UNUSED = -1
        private const val RETIRED_PROGRAM = -2
        private const val SILENCE_GAIN = 0.00001
    }
}
