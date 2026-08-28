package com.gabrielpc.enginesoundsimulator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionRequestPolicyTest {
    @Test
    fun `requests once from a visible Android 13 activity`() {
        assertTrue(
            NotificationPermissionRequestPolicy.shouldRequest(
                sdkInt = 33,
                activityVisible = true,
                permissionGranted = false,
                promptRecorded = false,
                requestInFlight = false,
            ),
        )
    }

    @Test
    fun `never requests from background pre 13 granted or repeated state`() {
        fun shouldRequest(
            sdk: Int = 33,
            visible: Boolean = true,
            granted: Boolean = false,
            recorded: Boolean = false,
            inFlight: Boolean = false,
        ) = NotificationPermissionRequestPolicy.shouldRequest(sdk, visible, granted, recorded, inFlight)

        assertFalse(shouldRequest(visible = false))
        assertFalse(shouldRequest(sdk = 32))
        assertFalse(shouldRequest(granted = true))
        assertFalse(shouldRequest(recorded = true))
        assertFalse(shouldRequest(inFlight = true))
    }
}
