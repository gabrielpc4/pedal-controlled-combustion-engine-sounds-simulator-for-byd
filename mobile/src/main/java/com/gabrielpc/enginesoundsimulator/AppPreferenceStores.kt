package com.gabrielpc.enginesoundsimulator

import android.content.Context

/** Registry for every app-owned preference store so the user-facing reset cannot leave stale settings behind. */
internal object AppPreferenceStores {
    const val TUNING = "engine_tuning"
    const val SELECTED_CAR = "selected_car"
    const val FMOD_SOURCE_MIX = "fmod_source_mix"
    const val APP_MASTER_VOLUME = "app_master_volume"
    const val CAR_MASTER_VOLUME = "car_master_volume"
    const val CAR_EFFECT_GAINS = "car_effect_gains"
    const val CAR_EFFECT_MODES = "car_effect_modes"
    const val SHIFT_MODE = "shift_mode"
    const val DRIVE_BEHAVIOR = "drive_behavior"
    /** Isolated controls used only while exercising the virtual pedals. */
    const val SIMULATED_PEDAL_TEST = "simulated_pedal_test"
    const val ENGINE_SOUND_PERSPECTIVE = "engine_sound_perspective"

    val all: Set<String> = setOf(
        TUNING,
        SELECTED_CAR,
        FMOD_SOURCE_MIX,
        APP_MASTER_VOLUME,
        CAR_MASTER_VOLUME,
        CAR_EFFECT_GAINS,
        CAR_EFFECT_MODES,
        SHIFT_MODE,
        DRIVE_BEHAVIOR,
        SIMULATED_PEDAL_TEST,
        ENGINE_SOUND_PERSPECTIVE,
    )

    fun clearAll(context: Context): Boolean {
        val appContext = context.applicationContext
        return all.fold(true) { allCleared, name ->
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit() && allCleared
        }
    }
}
