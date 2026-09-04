package com.gabrielpc.enginesoundsimulator

/**
 * Central switches for behavior that is useful while diagnosing the simulator.
 *
 * The debug-only telemetry entry point is intentionally kept outside this feature object: it is
 * armed only by an explicit ADB command and absent from release APKs. The initial screen is kept
 * as an explicit decision here so a future release can restore the tachometer without touching
 * navigation logic.
 */
internal object RuntimeFeatureFlags {
    /** The classic dashboard is the normal entry point; Mixer remains available from its button. */
    const val START_ON_MIXER = false
}
