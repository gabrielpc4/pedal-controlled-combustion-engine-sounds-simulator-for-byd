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
    const val EXTERIOR_PURE_AUDIO_SETTINGS = "exterior_pure_audio_settings_v1"
    const val SHIFT_SOUND_SETTINGS = "shift_sound_settings_v1"
    const val TRANSMISSION_SOUND_SETTINGS = "transmission_sound_settings_v1"
    const val CAR_EFFECT_MODES = "car_effect_modes_v2"
    const val VIRTUAL_GEAR_COUNT = "virtual_gear_count_v1"
    /** Last catalog tab shown by the shared car picker (modded or original). */
    const val CAR_PICKER_GROUP = "car_picker_group_v1"
    const val CAR_FAVORITES = "car_favorites_v1"
    const val MINIMUM_AUDIO_THROTTLE = "minimum_audio_throttle_v1"
    const val CRUISING_SHIFT_OFFSET_RPM = "cruising_shift_offset_rpm_v1"
    const val AUTOMATIC_TRANSMISSION_SETTINGS = "automatic_transmission_settings_v1"
}
