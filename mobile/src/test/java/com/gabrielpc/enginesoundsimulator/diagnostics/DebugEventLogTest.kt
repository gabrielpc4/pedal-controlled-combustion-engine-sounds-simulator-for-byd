package com.gabrielpc.enginesoundsimulator.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugEventLogTest {
    @Before
    fun setUp() {
        DebugEventLog.clearForTests()
    }

    @Test
    fun keepsOnlyWarningsAndErrorsInMemory() {
        DebugEventLog.warning("test_warning", "detail=a")
        DebugEventLog.recordThrowable("test_error", IllegalStateException("boom"), "detail=b")

        val text = DebugEventLog.readRecentLogText()
        assertTrue(text.contains("event=test_warning"))
        assertTrue(text.contains("event=test_error"))
        assertTrue(text.contains("IllegalStateException"))
        assertEquals(2, text.lines().size)
    }
}
