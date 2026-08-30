package com.gabrielpc.enginesoundsimulator.audio

/** Separately controllable core powertrain events exposed by an FMOD car profile. */
enum class FmodEventKind {
    ENGINE,
    TURBO,
    LIMITER,
    SHIFTS,
    BACKFIRE,
    TRANSMISSION,
}

data class FmodEventControl(
    val enabled: Boolean = true,
    val gainDb: Double = 0.0,
) {
    fun sanitized(): FmodEventControl {
        val cleanGain = gainDb.coerceIn(
            FmodEventMixSettings.MIN_GAIN_DB,
            FmodEventMixSettings.MAX_GAIN_DB,
        )
        return if (cleanGain == gainDb) this else copy(gainDb = cleanGain)
    }
}

/** User mix applied above FMOD's authored event balance. */
data class FmodEventMixSettings(
    val controls: Map<FmodEventKind, FmodEventControl> = DEFAULT_CONTROLS,
) {
    fun control(kind: FmodEventKind): FmodEventControl = controls[kind] ?: DEFAULT_CONTROL

    fun withControl(kind: FmodEventKind, control: FmodEventControl): FmodEventMixSettings =
        copy(controls = controls + (kind to control.sanitized()))

    fun sanitized(): FmodEventMixSettings = FmodEventMixSettings(
        controls = FmodEventKind.entries.associateWith { control(it).sanitized() },
    )

    companion object {
        const val MIN_GAIN_DB = -60.0
        const val MAX_GAIN_DB = 6.0

        val DEFAULT_CONTROLS: Map<FmodEventKind, FmodEventControl> =
            FmodEventKind.entries.associateWith { FmodEventControl() }

        val DEFAULT = FmodEventMixSettings()
        private val DEFAULT_CONTROL = FmodEventControl()
    }
}
