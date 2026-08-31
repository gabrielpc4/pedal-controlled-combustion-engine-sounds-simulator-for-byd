package com.gabrielpc.enginesoundsimulator.audio

/** Per-source FMOD parameter-placement entry latch for one selected perspective event instance. */
internal class AtlasParameterPlacementState {
    private var initialized = false
    private var inside = false

    /**
     * Returns true at initial event creation when already inside, then only on outside-to-inside
     * transitions. Leaving a placement arms the next entry in either parameter direction.
     */
    fun update(nextInside: Boolean): Boolean {
        val entered = wouldEnter(nextInside)
        initialized = true
        inside = nextInside

        return entered
    }

    /** Side-effect-free first phase used before a new EventInstance activation resets every latch. */
    fun wouldEnter(nextInside: Boolean): Boolean =
        if (initialized) !inside && nextInside else nextInside

    /** A new FMOD EventInstance evaluates its initial placement membership from scratch. */
    fun reset() {
        initialized = false
        inside = false
    }
}
