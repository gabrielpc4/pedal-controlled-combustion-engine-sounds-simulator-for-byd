package com.gabrielpc.enginesoundsimulator.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogImportBatchPolicyTest {
    @Test
    fun `duplicates import once and successful batch closes once`() {
        val imported = mutableListOf<String>()
        var closures = 0

        val result = CatalogImportBatchPolicy.importDistinctAndClose(
            sources = listOf("a", "b", "a", "b"),
            importOne = imported::add,
            closeBatch = {
                closures += 1
                "refreshed"
            },
        )

        assertEquals(listOf("a", "b"), imported)
        assertEquals(1, closures)
        assertEquals("refreshed", result)
    }

    @Test
    fun `late invalid pack preserves original failure and still closes discovery once`() {
        val expected = IllegalArgumentException("bad second pack")
        val imported = mutableListOf<String>()
        var closures = 0

        val actual = assertThrows(IllegalArgumentException::class.java) {
            CatalogImportBatchPolicy.importDistinctAndClose(
                sources = listOf("committed", "invalid", "never-reached"),
                importOne = { source ->
                    if (source == "invalid") throw expected
                    imported += source
                },
                closeBatch = {
                    closures += 1
                    Unit
                },
            )
        }

        assertSame(expected, actual)
        assertEquals(listOf("committed"), imported)
        assertEquals(1, closures)
    }
}
