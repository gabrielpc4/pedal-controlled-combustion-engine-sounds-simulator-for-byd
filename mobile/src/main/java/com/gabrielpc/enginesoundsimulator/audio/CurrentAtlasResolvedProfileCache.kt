package com.gabrielpc.enginesoundsimulator.audio

/** Keeps only the family whose parsed runtime can still be selected without another load. */
internal class CurrentAtlasResolvedProfileCache {
    private data class Entry(
        val id: String,
        val profile: EngineSampleProfile,
    )

    @Volatile
    private var current: Entry? = null

    fun find(id: String): EngineSampleProfile? = current?.takeIf { it.id == id }?.profile

    fun replace(profile: EngineSampleProfile) {
        current = Entry(profile.id, profile)
    }

    fun clear() {
        current = null
    }

    internal val retainedProfileCount: Int get() = if (current == null) 0 else 1
}
