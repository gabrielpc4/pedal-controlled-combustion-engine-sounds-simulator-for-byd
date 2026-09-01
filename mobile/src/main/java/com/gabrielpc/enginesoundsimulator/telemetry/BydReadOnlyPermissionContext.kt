package com.gabrielpc.enginesoundsimulator.telemetry

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * Compatibility context for BYD's client-side speed API permission checks.
 *
 * Some DiLink framework builds enforce their signature-only read permission by calling the
 * Context supplied to BYDAutoSpeedDevice.getInstance(), rather than enforcing the Binder caller
 * inside the vehicle service. This wrapper reports only the two speed-read permissions used by
 * this application as granted. It does not alter Android's package grants, grant any SET
 * permission, or affect calls made through the base application context.
 */
internal class BydReadOnlyPermissionContext(base: Context) : ContextWrapper(base) {
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (BydReadOnlyPermissionPolicy.treatAsGranted(permission)) return
        super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (BydReadOnlyPermissionPolicy.treatAsGranted(permission)) {
            PackageManager.PERMISSION_GRANTED
        } else {
            super.checkCallingOrSelfPermission(permission)
        }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (BydReadOnlyPermissionPolicy.treatAsGranted(permission)) return
        super.enforcePermission(permission, pid, uid, message)
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (BydReadOnlyPermissionPolicy.treatAsGranted(permission)) {
            PackageManager.PERMISSION_GRANTED
        } else {
            super.checkPermission(permission, pid, uid)
        }
}

internal object BydReadOnlyPermissionPolicy {
    private val allowedPermissions = setOf(
        BYD_SPEED_COMMON,
        BYD_SPEED_GET,
        BYD_GEARBOX_GET,
    )

    fun treatAsGranted(permission: String?): Boolean = permission in allowedPermissions
}
