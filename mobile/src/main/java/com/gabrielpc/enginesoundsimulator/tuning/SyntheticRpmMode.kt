package com.gabrielpc.enginesoundsimulator.tuning

/** Whether synthetic tachometer RPM follows road speed or free-revs like neutral. */
enum class SyntheticRpmMode(val displayName: String) {
    ROAD_COUPLED("ROAD"),
    FREE_REV("NEUTRAL"),
}

internal fun decodeSyntheticRpmMode(raw: String?, fallback: SyntheticRpmMode): SyntheticRpmMode {
    if (raw.isNullOrBlank()) {
        return fallback
    }
    return SyntheticRpmMode.entries.firstOrNull { it.name == raw } ?: fallback
}
