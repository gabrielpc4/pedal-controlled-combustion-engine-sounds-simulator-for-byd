package com.gabrielpc.enginesoundsimulator.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug APK-only ADB entry point. It intentionally has no UI so normal vehicle operation cannot
 * accidentally begin a high-rate capture. The receiver is not included in release manifests.
 */
class DebugTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DebugTelemetry.ACTION) return
        val pendingResult = goAsync()
        Thread({
            val result = DebugTelemetry.handleCommand(
                context.applicationContext,
                intent.getStringExtra(DebugTelemetry.EXTRA_COMMAND),
                intent.extras,
            )
            Log.i(TAG, result)
            pendingResult.finish()
        }, "debug-telemetry-command").start()
    }

    private companion object {
        const val TAG = "DebugTelemetry"
    }
}
