package com.gabrielpc.enginesoundsimulator.telemetry

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
    fun readOnlyCompatibilityPolicyAllowsOnlySpeedReadPermissions() {
        assertTrue(
            BydReadOnlyPermissionPolicy.treatAsGranted(
                "android.permission.BYDAUTO_SPEED_COMMON",
            ),
        )
        assertTrue(
            BydReadOnlyPermissionPolicy.treatAsGranted(
                "android.permission.BYDAUTO_SPEED_GET",
            ),
        )

        assertEquals(
            false,
            BydReadOnlyPermissionPolicy.treatAsGranted(
                "android.permission.BYDAUTO_SPEED_SET",
            ),
        )
        assertEquals(
            false,
            BydReadOnlyPermissionPolicy.treatAsGranted(
                "android.permission.BYDAUTO_AC_GET",
            ),
        )
        assertEquals(false, BydReadOnlyPermissionPolicy.treatAsGranted(null))
    }
}
