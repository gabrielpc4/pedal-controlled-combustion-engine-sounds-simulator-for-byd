package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CurrentAtlasResolvedProfileCacheTest {
    @Test
    fun replacingFamiliesLeavesOnlyTheCurrentParsedRuntimeReachable() {
        val cache = CurrentAtlasResolvedProfileCache()
        val first = EngineSampleProfiles.default.copy(id = "first", atlasProgram = null)
        val second = EngineSampleProfiles.default.copy(id = "second", atlasProgram = null)

        cache.replace(first)
        assertSame(first, cache.find("first"))
        assertEquals(1, cache.retainedProfileCount)

        cache.replace(second)
        assertNull(cache.find("first"))
        assertSame(second, cache.find("second"))
        assertEquals(1, cache.retainedProfileCount)
    }
}
