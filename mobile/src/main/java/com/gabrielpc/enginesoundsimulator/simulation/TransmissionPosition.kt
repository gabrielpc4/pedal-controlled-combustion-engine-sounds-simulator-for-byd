package com.gabrielpc.enginesoundsimulator.simulation

/** PRND-style selector beside the pedals. Only D couples RPM to road speed and auto-shifts. */
enum class TransmissionPosition(val displayName: String) {
    PARK("P"),
    NEUTRAL("N"),
    DRIVE("D"),
}
