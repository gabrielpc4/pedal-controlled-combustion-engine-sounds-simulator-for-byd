package com.gabrielpc.enginesoundsimulator

import android.content.Context

/** Preference namespaces for the small amount of driver state retained by the app. */
internal object AppPreferenceStores {
    const val SELECTED_CAR = "selected_car"
    const val SHIFT_MODE = "shift_mode"
    const val ENGINE_SOUND_PERSPECTIVE = "engine_sound_perspective"
    const val AUDIO_MIX_GAINS = "audio_mix_gains_v2"
    const val AUDIO_MIX_GAINS_LEGACY = "audio_mix_gains"
    const val BACKFIRE_SETTINGS = "backfire_settings_v1"
    const val FMOD_UPDATE_RATE = "fmod_update_rate_v1"
    const val EXTERIOR_AUDIO_MODE = "exterior_audio_mode_v1"
    const val SHIFT_SOUND_SETTINGS = "shift_sound_settings_v1"
}
