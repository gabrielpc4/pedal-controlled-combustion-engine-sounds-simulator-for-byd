package com.gabrielpc.enginesoundsimulator.diagnostics

/**
 * Debug-only scenario input expressed with primitive values so the main drive loop can consume it
 * without depending on a debug source set. A null override means ordinary driver/UI input.
 */
internal data class DebugScenarioOverride(
    val scenarioId: Long,
    val profileId: String,
    val perspectiveOrdinal: Int,
    val inputModeOrdinal: Int,
    val transmissionPositionOrdinal: Int,
    val throttle: Double,
    val brake: Double,
    val manualModeEnabled: Boolean,
    val manualShiftSerial: Long = 0L,
    val manualShiftDirection: Int = 0,
    val forceAuthoredBankEffects: Boolean = false,
)
