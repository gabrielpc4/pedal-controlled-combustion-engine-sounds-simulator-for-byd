package com.gabrielpc.enginesoundsimulator.drive

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPackStagingPolicyTest {
    @Test
    fun `accepts and closes only direct lowercase packs under canonical staging root`() {
        val temporary = Files.createTempDirectory("byd-pack-staging-test").toFile()
        try {
            val root = temporary.resolve("adb-import").apply { mkdirs() }
            val batchDirectory = root.resolve("bulk-123").apply { mkdir() }
            batchDirectory.resolve("001.aclib").writeBytes(byteArrayOf(1))
            batchDirectory.resolve("000.aclib").writeBytes(byteArrayOf(2))

            val batch = DebugPackStagingPolicy.requireBatch(
                suppliedDirectory = batchDirectory.path,
                allowedRoots = listOf(root),
                maximumPacks = 153,
            )

            assertEquals(listOf("000.aclib", "001.aclib"), batch.packFiles.map { it.name })
            assertTrue(DebugPackStagingPolicy.close(batch))
            assertFalse(batchDirectory.exists())
            assertTrue(root.exists())
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `rejects sibling directory outside canonical staging boundary`() {
        val temporary = Files.createTempDirectory("byd-pack-boundary-test").toFile()
        try {
            val root = temporary.resolve("adb-import").apply { mkdirs() }
            val sibling = temporary.resolve("adb-import-escape").apply { mkdir() }
            sibling.resolve("000.aclib").writeBytes(byteArrayOf(1))

            assertThrows(IllegalArgumentException::class.java) {
                DebugPackStagingPolicy.requireBatch(sibling.path, listOf(root), 153)
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `rejects nested or non-pack staging content instead of deleting it recursively`() {
        val temporary = Files.createTempDirectory("byd-pack-shape-test").toFile()
        try {
            val root = temporary.resolve("adb-import").apply { mkdirs() }
            val batch = root.resolve("bulk-123").apply { mkdir() }
            batch.resolve("000.aclib").writeBytes(byteArrayOf(1))
            batch.resolve("nested").mkdir()

            assertThrows(IllegalArgumentException::class.java) {
                DebugPackStagingPolicy.requireBatch(batch.path, listOf(root), 153)
            }
            assertTrue(batch.resolve("000.aclib").exists())
            assertTrue(batch.resolve("nested").exists())
        } finally {
            temporary.deleteRecursively()
        }
    }
}
