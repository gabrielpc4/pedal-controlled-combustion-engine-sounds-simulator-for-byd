package com.gabrielpc.bydmotorsound.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryValidationTest {
    @Test
    fun pedalAcceptsDocumentedRangeIncludingZero() {
        assertEquals(0.0, TelemetryValidation.pedal(0.0).value!!, 0.0)
        assertEquals(42.0, TelemetryValidation.pedal(42.0).value!!, 0.0)
        assertEquals(100.0, TelemetryValidation.pedal(100.0).value!!, 0.0)
    }

    @Test
    fun pedalDoesNotTurnAnErrorIntoZero() {
        val result = TelemetryValidation.pedal(null, "SecurityException: denied")

        assertNull(result.value)
        assertEquals("SecurityException: denied", result.issue)
    }

    @Test
    fun knownSdkSentinelsRemainVisible() {
        val sdkUnavailable = TelemetryValidation.pedal(-2_147_482_624.0)
        val permissionDenied = TelemetryValidation.pedal(-10_005.0)
        val noData = TelemetryValidation.pedal(65_535.0)

        assertNull(sdkUnavailable.value)
        assertTrue(sdkUnavailable.issue!!.contains("SDK not available"))
        assertTrue(permissionDenied.issue!!.contains("permission denied"))
        assertTrue(noData.issue!!.contains("no data"))
    }

    @Test
    fun outOfRangePedalAndSpeedAreInvalid() {
        assertNull(TelemetryValidation.pedal(101.0).value)
        assertNull(TelemetryValidation.pedal(-1.0).value)
        assertNull(TelemetryValidation.speed(283.0).value)
        assertNull(TelemetryValidation.speed(-1.0).value)
    }

    @Test
    fun cadenceReportsMeasuredIntervalsWithoutInventingFirstRate() {
        val tracker = CadenceTracker(capacity = 8)

        val first = tracker.record(1_000_000_000L)
        tracker.record(1_020_000_000L)
        tracker.record(1_040_000_000L)
        val fourth = tracker.record(1_080_000_000L)

        assertEquals(1L, first.sampleCount)
        assertNull(first.rateHz)
        assertEquals(4L, fourth.sampleCount)
        assertEquals(40.0, fourth.lastIntervalMs!!, 0.0)
        assertEquals(26.666, fourth.meanIntervalMs!!, 0.01)
        assertEquals(37.5, fourth.rateHz!!, 0.01)
        assertEquals(40.0, fourth.p95IntervalMs!!, 0.0)
        assertEquals(40.0, fourth.maxIntervalMs!!, 0.0)
    }
}
