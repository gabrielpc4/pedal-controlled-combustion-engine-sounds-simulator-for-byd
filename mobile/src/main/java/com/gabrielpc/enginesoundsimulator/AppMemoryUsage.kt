package com.gabrielpc.enginesoundsimulator

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.util.Locale
import kotlin.math.roundToInt

data class MemoryHeaderLabels(
    val usageLabel: String,
    val availableLabel: String,
)

/** Reads app PSS and device-available RAM for the dashboard header. */
object AppMemoryUsage {
    fun readHeaderLabels(context: Context): MemoryHeaderLabels {
        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemory)

        return MemoryHeaderLabels(
            usageLabel = formatPssLabel(processMemory.totalPss.coerceAtLeast(0)),
            availableLabel = formatAvailableLabel(systemMemory.availMem.coerceAtLeast(0L)),
        )
    }

    internal fun formatPssLabel(pssKb: Int): String {
        val megabytes = pssKb / 1024.0

        if (megabytes >= 1024.0) {
            return String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
        }

        return "${megabytes.roundToInt()} MB"
    }

    internal fun formatAvailableLabel(availableBytes: Long): String {
        val megabytes = availableBytes / (1024.0 * 1024.0)

        if (megabytes >= 1024.0) {
            return String.format(Locale.US, "%.1f GB left", megabytes / 1024.0)
        }

        return "${megabytes.roundToInt()} MB left"
    }
}
