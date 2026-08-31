package com.gabrielpc.enginesoundsimulator.audio

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Deterministic authored playlist state. Construct off the audio thread, then select allocation-free. */
internal class AtlasEffectScheduler(
    atlasFamilyId: String,
    eventPath: String,
    profileAudioSessionGeneration: Long,
    private val group: AtlasSchedulingGroup,
    private val state: AtlasEffectSchedulerState = AtlasEffectSchedulerState(
        seed(atlasFamilyId, eventPath, profileAudioSessionGeneration, group.id),
    ),
) {
    /** Test/diagnostic storage is fixed-size so semantic triggers never allocate. */
    val lastDraws = LongArray(maxOf(MINIMUM_DRAWS_PER_TRIGGER, group.members.size + 1))
    var lastDrawCount = 0
        private set

    /**
     * PlaySequential is proven to reset after Stop/rewind/Start. SmartRandom state deliberately
     * remains session-scoped until its cross-instance FMOD scope has been proven.
     */
    fun enterActivation(generation: Long) {
        state.enterActivation(generation, resetSequential = group.playMode == "PlaylistPlayMode_PlaySequential")
    }

    /** Returns authored member order, or -1 when an authored chance rejects the trigger. */
    fun selectMember(): Int {
        lastDrawCount = 0
        if (group.composition == AtlasSchedulingComposition.SIMULTANEOUS_LAYER) {
            return if (beginSimultaneousTrigger()) 0 else NO_SELECTION
        }
        if (drawUniform() * 100.0 >= group.groupTriggerChancePercent) return NO_SELECTION

        val selectedOrder = when (group.playMode) {
            "PlaylistPlayMode_PlaySequential" -> {
                val result = state.sequentialCursor
                state.sequentialCursor = (state.sequentialCursor + 1) % group.members.size
                result
            }
            "PlaylistPlayMode_SmartRandom" -> selectSmartRandom()
            else -> error("Unsupported validated atlas playlist mode ${group.playMode}")
        }
        state.lastSelectedOrder = selectedOrder
        val accepted = drawUniform() * 100.0 < group.members[selectedOrder].triggerChancePercent

        return if (accepted) selectedOrder else NO_SELECTION
    }

    /** One authored group-chance draw, shared by every simultaneous member in this trigger. */
    fun beginSimultaneousTrigger(): Boolean {
        require(group.composition == AtlasSchedulingComposition.SIMULTANEOUS_LAYER)
        lastDrawCount = 0

        return drawUniform() * 100.0 < group.groupTriggerChancePercent
    }

    private fun selectSmartRandom(): Int {
        val excludePrevious = state.lastSelectedOrder != NO_SELECTION &&
            group.members.size >= 3 &&
            group.members.all { it.triggerChancePercent == 100.0 }
        var totalWeight = 0.0
        group.members.forEachIndexed { order, member ->
            if (!excludePrevious || order != state.lastSelectedOrder) totalWeight += member.weight
        }
        val point = drawUniform() * totalWeight
        var cumulative = 0.0
        var fallback = NO_SELECTION
        group.members.forEachIndexed { order, member ->
            if (!excludePrevious || order != state.lastSelectedOrder) {
                fallback = order
                cumulative += member.weight
                if (point < cumulative) return order
            }
        }

        return fallback
    }

    private fun drawUniform(): Double {
        var value = if (state.randomState == 0L) ZERO_SEED_REPLACEMENT else state.randomState
        value = value xor (value ushr 12)
        value = value xor (value shl 25)
        value = value xor (value ushr 27)
        state.randomState = value
        val output = value * XORSHIFT64_STAR_MULTIPLIER
        lastDraws[lastDrawCount++] = output

        return (output ushr 11).toDouble() / UNIFORM_DIVISOR
    }

    companion object {
        const val NO_SELECTION = -1
        private const val MINIMUM_DRAWS_PER_TRIGGER = 3
        private const val XORSHIFT64_STAR_MULTIPLIER = 2_685_821_657_736_338_717L
        private const val UNIFORM_DIVISOR = 9_007_199_254_740_992.0
        internal val ZERO_SEED_REPLACEMENT = 0x9e3779b97f4a7c15UL.toLong()

        internal fun seed(
            atlasFamilyId: String,
            eventPath: String,
            profileAudioSessionGeneration: Long,
            groupId: String,
        ): Long {
            require(profileAudioSessionGeneration >= 0L) {
                "Atlas profile audio session generation must be non-negative"
            }
            val input = "byd-fmod-playlist-v3|$atlasFamilyId|$eventPath|$profileAudioSessionGeneration|$groupId"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            var result = 0L
            repeat(Long.SIZE_BYTES) { index -> result = (result shl 8) or (digest[index].toLong() and 0xffL) }

            return if (result == 0L) ZERO_SEED_REPLACEMENT else result
        }
    }
}

/** Mutable scheduler memory owned by [AtlasAudioSessionState], never by a perspective renderer. */
internal class AtlasEffectSchedulerState(seed: Long) {
    var randomState = if (seed == 0L) AtlasEffectScheduler.ZERO_SEED_REPLACEMENT else seed
    var sequentialCursor = 0
    var lastSelectedOrder = AtlasEffectScheduler.NO_SELECTION
    private var activationGeneration = Long.MIN_VALUE

    fun enterActivation(generation: Long, resetSequential: Boolean) {
        require(generation >= 0L) { "Atlas activation generation must be non-negative" }
        if (generation == activationGeneration) return
        activationGeneration = generation
        if (resetSequential) {
            sequentialCursor = 0
            lastSelectedOrder = AtlasEffectScheduler.NO_SELECTION
        }
    }
}
