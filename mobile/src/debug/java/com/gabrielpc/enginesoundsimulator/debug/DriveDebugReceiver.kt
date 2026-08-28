package com.gabrielpc.enginesoundsimulator.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gabrielpc.enginesoundsimulator.BuildConfig
import com.gabrielpc.enginesoundsimulator.drive.DriveRuntimeService

/** Debug-build-only bridge from explicit adb broadcasts to the private driving service. */
class DriveDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != DriveRuntimeService.DEBUG_CONTROL_ACTION) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "rejected"
            return
        }

        val command = intent.getStringExtra(DriveRuntimeService.EXTRA_DEBUG_COMMAND)
            ?.trim()
            .orEmpty()
        if (command.isEmpty()) {
            Log.e(LOG_TAG, "Rejected adb command without --es command <name>")
            resultCode = Activity.RESULT_CANCELED
            resultData = "missing command"
            return
        }

        DriveRuntimeService.forwardDebugCommand(context.applicationContext, intent)
        resultCode = Activity.RESULT_OK
        resultData = "forwarded $command"
    }

    private companion object {
        const val LOG_TAG = "BYDDriveDebug"
    }
}
