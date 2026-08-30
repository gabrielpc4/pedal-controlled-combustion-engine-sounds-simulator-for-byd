package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context

/** Persists the selected FMOD profile and upgrades IDs used by earlier private builds. */
internal class FmodCarSelectionRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): FmodCarProfile {
        val persistedId = preferences.getString(KEY_PROFILE_ID, null)
        val resolvedId = resolvePersistedFmodProfileId(persistedId)
        if (persistedId != resolvedId) {
            preferences.edit().putString(KEY_PROFILE_ID, resolvedId).apply()
        }
        return FmodCarProfiles.find(resolvedId)
    }

    fun save(profile: FmodCarProfile): FmodCarProfile {
        preferences.edit().putString(KEY_PROFILE_ID, profile.id).apply()
        return profile
    }

    companion object {
        internal const val PREFERENCES_NAME = "selected_car"
        internal const val KEY_PROFILE_ID = "profile_id"
    }
}

/** Pure selection migration so stale and legacy preference behavior is JVM-testable. */
internal fun resolvePersistedFmodProfileId(persistedId: String?): String {
    val migratedId = when (persistedId) {
        TRANSIENT_HURACAN_BANK_SLUG_ID -> FmodCarProfiles.HURACAN_TROFEO_EVO2_ID
        TRANSIENT_AVENTADOR_BANK_SLUG_ID -> FmodCarProfiles.AVENTADOR_SV_ID
        else -> persistedId
    }
    return FmodCarProfiles.findOrNull(migratedId)?.id ?: FmodCarProfiles.default.id
}

private const val TRANSIENT_HURACAN_BANK_SLUG_ID = "fx_lamborghini_huracan_trofeo_evo2"
private const val TRANSIENT_AVENTADOR_BANK_SLUG_ID = "tr_lamborghini_aventador_sv"
