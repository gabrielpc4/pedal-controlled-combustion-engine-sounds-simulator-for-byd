package com.gabrielpc.enginesoundsimulator

/**
 * Central switches for behavior that is useful while diagnosing the simulator.
 *
 * Diagnostic telemetry follows the build type: it remains available in debug APKs for
 * investigation, but is disabled in release APKs so the production audio loop does not spend
 * CPU formatting and emitting high-rate trace messages. The initial screen is kept
 * as an explicit decision here so a future release can restore the tachometer without touching
 * navigation logic.
 */
internal object RuntimeFeatureFlags {
    /** Keep the mixer/dashboard visible first while the FMOD voice behavior is being reviewed. */
    const val START_ON_MIXER = true

    /** Detailed 3 ms shift telemetry is a diagnostic aid, not a production feature. */
    val ENABLE_DETAILED_DRIVETRAIN_TELEMETRY: Boolean = BuildConfig.DEBUG
}
