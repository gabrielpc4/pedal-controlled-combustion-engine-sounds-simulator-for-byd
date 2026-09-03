package com.gabrielpc.enginesoundsimulator.audio

/**
 * One diagnostic source aggregate owned by one authored Studio event.
 *
 * The ID is the event path plus raw FMOD sound name. It is deliberately not a single exclusive
 * channel: several Core voices from the same source can coexist, and a source can remain active
 * with zero audibility while Studio automation or virtualization makes it silent.
 */
data class FmodSourceState(
    val id: String,
    val eventPath: String,
    val eventName: String,
    val soundName: String,
    val audibility: Double,
    val routeGain: Double,
    val voiceCount: Int,
    val isVirtual: Boolean,
    val isActive: Boolean,
) {
    val audibilityPercent: Int
        get() = (audibility.coerceIn(0.0, 1.0) * 100.0).toInt()

    val section: FmodEventSection
        get() = FmodEventSection.forEvent(eventName)
}

enum class FmodEventSection(val displayName: String, val order: Int) {
    ENGINE("ENGINE", 0),
    DRIVETRAIN("DRIVETRAIN", 1),
    FORCED_INDUCTION("TURBO", 2),
    DRIVER_EVENTS("DRIVER EVENTS", 3),
    ENGINE_EVENTS("ENGINE EVENTS", 4),
    OTHER("OTHER AUTHORED EVENTS", 5),
    ;

    companion object {
        fun forEvent(eventName: String): FmodEventSection = when (eventName) {
            "engine_int", "engine_ext" -> ENGINE
            "transmission", "transmission_ext" -> DRIVETRAIN
            "turbo" -> FORCED_INDUCTION
            "gear_int", "gear_ext", "gear_grind", "tractioncontrol_int", "tractioncontrol_ext" ->
                DRIVER_EVENTS
            "limiter", "backfire_int", "backfire_ext", "start" -> ENGINE_EVENTS
            else -> OTHER
        }
    }
}

internal fun parseNativeVoiceSnapshots(rows: Array<String>): List<FmodSourceState> = rows.mapNotNull { row ->
    val fields = row.split(NATIVE_FIELD_SEPARATOR)
    if (fields.size != NATIVE_SNAPSHOT_FIELD_COUNT) return@mapNotNull null
    FmodSourceState(
        id = fields[0],
        eventPath = fields[1],
        eventName = fields[2],
        soundName = fields[3],
        audibility = fields[4].toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0,
        routeGain = fields[5].toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        voiceCount = fields[6].toIntOrNull()?.coerceAtLeast(0) ?: 0,
        isVirtual = fields[7] == "1",
        isActive = fields[8] == "1",
    )
}

private const val NATIVE_FIELD_SEPARATOR = '\u001f'
private const val NATIVE_SNAPSHOT_FIELD_COUNT = 9
