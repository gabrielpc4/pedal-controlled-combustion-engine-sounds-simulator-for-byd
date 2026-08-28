package com.gabrielpc.enginesoundsimulator.audio

/** Exact event-start/region-reentry state of an authored AC engine one-shot program. */
internal class EngineTransientEventState(
    private val requiresEventStartInside: Boolean,
) {
    private var eventStarted = false
    private var permanentlyDisabled = false
    private var wasInside = false
    private var exitedSinceLastTrigger = false
    var lastTriggerWasParameterRegionReentry = false
        private set

    /**
     * Samples the combined authored parameter region once. A true result schedules one program
     * voice; leaving the region deliberately has no effect on voices already playing.
     */
    fun update(insideAllRuntimeGates: Boolean): Boolean {
        if (!eventStarted) {
            eventStarted = true
            lastTriggerWasParameterRegionReentry = false
            if (requiresEventStartInside && !insideAllRuntimeGates) {
                permanentlyDisabled = true
                return false
            }
            wasInside = insideAllRuntimeGates
            return true
        }
        if (permanentlyDisabled || !requiresEventStartInside) return false
        if (!insideAllRuntimeGates) {
            wasInside = false
            exitedSinceLastTrigger = true
            return false
        }
        val trigger = !wasInside && exitedSinceLastTrigger
        wasInside = true
        if (trigger) {
            exitedSinceLastTrigger = false
            lastTriggerWasParameterRegionReentry = true
        }
        return trigger
    }

    /** A profile/event restart is the only way AC re-enables a program started outside. */
    fun restartEvent() {
        eventStarted = false
        permanentlyDisabled = false
        wasInside = false
        exitedSinceLastTrigger = false
        lastTriggerWasParameterRegionReentry = false
    }
}
