package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Selects which authored engine event the listener hears. */
enum class EngineSoundPerspective(val displayName: String) {
    CABIN("CABIN"),
    EXTERIOR("EXTERIOR"),
}

internal class EngineSoundPerspectiveRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.ENGINE_SOUND_PERSPECTIVE,
        Context.MODE_PRIVATE,
    )

    fun load(profile: EngineSampleProfile): EngineSoundPerspective {
        val saved = preferences.getString(perspectiveKey(profile.id), null)
        return profile.resolvedPerspective(
            EngineSoundPerspective.entries.firstOrNull { perspective -> perspective.name == saved }
                ?: EngineSoundPerspective.CABIN,
        )
    }

    fun save(profile: EngineSampleProfile, perspective: EngineSoundPerspective): EngineSoundPerspective {
        val resolved = profile.resolvedPerspective(perspective)
        preferences.edit().putString(perspectiveKey(profile.id), resolved.name).commit()
        return resolved
    }

    private fun perspectiveKey(profileId: String): String = "$profileId.engine_sound_perspective"

}
