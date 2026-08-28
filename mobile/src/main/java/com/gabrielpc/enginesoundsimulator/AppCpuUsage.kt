package com.gabrielpc.enginesoundsimulator

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.math.roundToInt

/** Tracks the hottest app thread so header CPU stays within one core (0–100%). */
object AppCpuUsage {
    private var lastWallTimeMs = 0L
    private var lastThreadCpuJiffies: Map<Int, Long> = emptyMap()

    fun primeSample() {
        synchronized(this) {
            lastWallTimeMs = SystemClock.elapsedRealtime()
            lastThreadCpuJiffies = readThreadCpuJiffies()
        }
    }

    fun sampleLabel(): String {
        synchronized(this) {
            val nowWall = SystemClock.elapsedRealtime()
            val nowThreads = readThreadCpuJiffies()

            if (lastWallTimeMs == 0L) {
                lastWallTimeMs = nowWall
                lastThreadCpuJiffies = nowThreads
                return PLACEHOLDER_LABEL
            }

            val deltaWall = nowWall - lastWallTimeMs
            val percent = hottestThreadCpuPercent(
                deltaWallMs = deltaWall,
                previousJiffies = lastThreadCpuJiffies,
                currentJiffies = nowThreads,
                clockTicksPerSecond = clockTicksPerSecond(),
            )

            lastWallTimeMs = nowWall
            lastThreadCpuJiffies = nowThreads

            if (percent == null) {
                return PLACEHOLDER_LABEL
            }

            return formatCpuLabel(percent)
        }
    }

    internal fun hottestThreadCpuPercent(
        deltaWallMs: Long,
        previousJiffies: Map<Int, Long>,
        currentJiffies: Map<Int, Long>,
        clockTicksPerSecond: Double,
    ): Double? {
        if (deltaWallMs <= 0L || clockTicksPerSecond <= 0.0) {
            return null
        }

        var maxPercent = 0.0
        for ((threadId, nowJiffies) in currentJiffies) {
            val lastJiffies = previousJiffies[threadId] ?: nowJiffies
            val deltaJiffies = (nowJiffies - lastJiffies).coerceAtLeast(0)
            val cpuMs = (deltaJiffies / clockTicksPerSecond) * 1_000.0
            val percent = (cpuMs / deltaWallMs.toDouble()) * 100.0
            if (percent > maxPercent) {
                maxPercent = percent
            }
        }

        return maxPercent.coerceIn(0.0, 100.0)
    }

    internal fun formatCpuLabel(percent: Double): String {
        val rounded = percent.roundToInt().coerceIn(0, 100)
        return "$rounded% CPU"
    }

    private fun clockTicksPerSecond(): Double {
        return Os.sysconf(OsConstants._SC_CLK_TCK).toDouble().coerceAtLeast(1.0)
    }

    private fun readThreadCpuJiffies(): Map<Int, Long> {
        val threads = mutableMapOf<Int, Long>()
        val taskDir = File("/proc/self/task")
        val threadDirs = taskDir.listFiles() ?: return threads

        for (threadDir in threadDirs) {
            val threadId = threadDir.name.toIntOrNull() ?: continue
            val statLine = runCatching {
                threadDir.resolve("stat").readText()
            }.getOrNull() ?: continue

            threads[threadId] = parseCpuJiffies(statLine)
        }

        return threads
    }

    private fun parseCpuJiffies(statLine: String): Long {
        val closeParen = statLine.lastIndexOf(')')
        if (closeParen < 0) {
            return 0L
        }

        val fields = statLine.substring(closeParen + 2).split(' ')
        if (fields.size < 13) {
            return 0L
        }

        val userJiffies = fields[11].toLongOrNull() ?: 0L
        val systemJiffies = fields[12].toLongOrNull() ?: 0L
        return userJiffies + systemJiffies
    }

    private const val PLACEHOLDER_LABEL = "—% CPU"
}
