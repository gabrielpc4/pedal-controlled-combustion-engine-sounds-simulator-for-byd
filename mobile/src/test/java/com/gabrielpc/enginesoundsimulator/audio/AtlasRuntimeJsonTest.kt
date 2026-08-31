package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AtlasRuntimeJsonTest {
    @Test
    fun acceptsRawAndEscapedNonBmpCharacters() {
        val rawName = "Cadillac Escalade ESV ∣ 𝐾𝑜𝑟𝑔𝑖 😈"
        val raw = AtlasRuntimeJson.parse("{\"name\":\"$rawName\"}".toByteArray())
            .objectValues("root")
        assertEquals(rawName, raw.getValue("name").stringValue("name"))

        val escaped = AtlasRuntimeJson.parse("{\"name\":\"\\uD83D\\uDE08\"}".toByteArray())
            .objectValues("root")
        assertEquals("😈", escaped.getValue("name").stringValue("name"))
    }

    @Test
    fun rejectsUnpairedEscapedSurrogates() {
        assertThrows(AtlasJsonException::class.java) {
            AtlasRuntimeJson.parse("{\"name\":\"\\uD83D\"}".toByteArray())
        }
        assertThrows(AtlasJsonException::class.java) {
            AtlasRuntimeJson.parse("{\"name\":\"\\uDE08\"}".toByteArray())
        }
        assertThrows(AtlasJsonException::class.java) {
            AtlasRuntimeJson.parse("{\"name\":\"\\uD83D\\u0041\"}".toByteArray())
        }
    }

    @Test
    fun rejectsDuplicateObjectKeys() {
        assertThrows(AtlasJsonException::class.java) {
            AtlasRuntimeJson.parse("{\"name\":\"first\",\"name\":\"second\"}".toByteArray())
        }
    }
}
