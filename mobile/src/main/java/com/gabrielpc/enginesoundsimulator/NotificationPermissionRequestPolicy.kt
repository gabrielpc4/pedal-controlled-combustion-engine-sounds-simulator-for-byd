package com.gabrielpc.enginesoundsimulator

/** Android-free policy so the notification permission cannot be requested from a service. */
internal object NotificationPermissionRequestPolicy {
    private const val ANDROID_13_API = 33

    fun shouldRequest(
        sdkInt: Int,
        activityVisible: Boolean,
        permissionGranted: Boolean,
        promptRecorded: Boolean,
        requestInFlight: Boolean,
    ): Boolean = sdkInt >= ANDROID_13_API &&
        activityVisible &&
        !permissionGranted &&
        !promptRecorded &&
        !requestInFlight
}
