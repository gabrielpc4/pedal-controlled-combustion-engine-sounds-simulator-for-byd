package com.gabrielpc.enginesoundsimulator.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticFileLogTest {
    @Test
    fun rotatesTheActiveFileBeforeItsConfiguredLimitIsExceeded() {
        val directory = Files.createTempDirectory("byd-diagnostic-log").toFile()
        try {
            val log = BoundedDiagnosticFileLog(
                directory = directory,
                activeFileName = "active.log",
                previousFileName = "previous.log",
                maxFileBytes = 64L,
            )

            log.append("first-entry-1234567890")
            log.append("second-entry-1234567890")
            log.append("third-entry-1234567890")

            val active = File(directory, "active.log")
            val previous = File(directory, "previous.log")
            assertTrue(active.isFile)
            assertTrue(previous.isFile)
            assertTrue(active.length() <= 64L)
            assertTrue(previous.length() <= 64L)
            assertTrue(previous.readText().contains("second-entry"))
            assertTrue(active.readText().contains("third-entry"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun createsTheDirectoryAndPersistsAnEntry() {
        val root = Files.createTempDirectory("byd-diagnostic-log").toFile()
        val directory = File(root, "nested")
        try {
            BoundedDiagnosticFileLog(
                directory = directory,
                activeFileName = "active.log",
                previousFileName = "previous.log",
                maxFileBytes = 1_024L,
            ).append("session_started")

            assertTrue(File(directory, "active.log").isFile)
            assertEquals("session_started\n", File(directory, "active.log").readText())
            assertFalse(File(directory, "previous.log").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun capsAnOversizedSingleEntryAtTheConfiguredLimit() {
        val directory = Files.createTempDirectory("byd-diagnostic-log").toFile()
        try {
            BoundedDiagnosticFileLog(
                directory = directory,
                activeFileName = "active.log",
                previousFileName = "previous.log",
                maxFileBytes = 64L,
            ).append("x".repeat(1_024))

            assertTrue(File(directory, "active.log").length() <= 64L)
        } finally {
            directory.deleteRecursively()
        }
    }
}
