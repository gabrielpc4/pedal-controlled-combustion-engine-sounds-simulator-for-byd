package com.gabrielpc.enginesoundsimulator.drive

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveRuntimeManifestTest {
    @Test
    fun appRequestsNoWakeLockPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertFalse(Manifest.permission.WAKE_LOCK in packageInfo.requestedPermissions.orEmpty())
    }

    @Test
    fun notificationPermissionIsDeclaredForAndroid13RuntimePrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in packageInfo.requestedPermissions.orEmpty())
    }

    @Test
    fun foregroundRuntimeIsPrivateAndReceivesTaskRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, DriveRuntimeService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(serviceInfo.exported)
        assertEquals(0, serviceInfo.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                serviceInfo.foregroundServiceType,
            )
        }
    }

    @Test
    fun debugApkExportsTheAdbControlReceiver() {
        assertTrue(BuildConfig.DEBUG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(
                context.packageName,
                "com.gabrielpc.enginesoundsimulator.debug.DriveDebugReceiver",
            ),
            PackageManager.GET_META_DATA,
        )

        assertTrue(receiver.enabled)
        assertTrue(receiver.exported)
        assertEquals("android.permission.DUMP", receiver.permission)
    }
}
