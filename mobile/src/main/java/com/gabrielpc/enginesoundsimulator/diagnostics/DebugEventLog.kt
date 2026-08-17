package com.gabrielpc.enginesoundsimulator.diagnostics

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * In-memory warnings and errors for the diagnostics screen.
 *
 * Routine lifecycle and telemetry events are intentionally omitted. Each entry is also mirrored to
 * Logcat. Nothing is written to disk.
 */
object DebugEventLog {
    private const val tag = "EngineSoundsSimulator"
    private const val maxEntries = 200
    private const val maxEntryCharacters = 8_192

    private val outputLock = Any()
    private val entries = ArrayDeque<String>(maxEntries)
    private var sessionId: String = "uninitialized"
    private var uncaughtHandlerInstalled = false
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** Installs the in-memory logger and uncaught-exception hook once per process. */
    fun install(@Suppress("UNUSED_PARAMETER") context: Context) {
        synchronized(outputLock) {
            if (sessionId == "uninitialized") {
                sessionId = "${Process.myPid()}-${System.currentTimeMillis()}"
            }

            if (!uncaughtHandlerInstalled) {
                previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    runCatching {
                        recordThrowable("uncaught_exception", throwable, "thread=${thread.name}")
                    }
                    previousUncaughtHandler?.uncaughtException(thread, throwable)
                        ?: run {
                            Process.killProcess(Process.myPid())
                            kotlin.system.exitProcess(10)
                        }
                }
                uncaughtHandlerInstalled = true
            }
        }
    }

    /** Records an unexpected but recoverable condition. */
    fun warning(name: String, details: String = "") {
        record(level = "WARN", name = name, details = details, priority = Log.WARN)
    }

    /** Records a caught failure and its stack trace before recovery continues. */
    fun recordThrowable(name: String, throwable: Throwable, details: String = "") {
        val stackTrace = StringWriter().use { writer ->
            PrintWriter(writer).use { printer ->
                throwable.printStackTrace(printer)
            }
            writer.toString()
        }
        record(
            level = "ERROR",
            name = name,
            details = listOfNotNull(details.takeIf { it.isNotBlank() }, stackTrace).joinToString(" | "),
            priority = Log.ERROR,
        )
    }

    /** Returns buffered warnings and errors for the diagnostics screen. */
    fun readRecentLogText(): String = synchronized(outputLock) {
        if (entries.isEmpty()) {
            "(no errors or warnings yet)"
        } else {
            entries.joinToString("\n")
        }
    }

    internal fun clearForTests() {
        synchronized(outputLock) {
            entries.clear()
            sessionId = "test"
        }
    }

    private fun record(level: String, name: String, details: String, priority: Int) {
        val nowWallMs = System.currentTimeMillis()
        val elapsedMs = elapsedRealtimeMs()
        val normalizedName = sanitize(name).ifBlank { "unnamed_event" }
        val normalizedDetails = sanitize(details)
        val entry = buildString {
            append("wall_ms=").append(nowWallMs)
            append(" elapsed_ms=").append(elapsedMs)
            append(" session=").append(sessionId)
            append(" level=").append(level)
            append(" event=").append(normalizedName)
            if (normalizedDetails.isNotBlank()) append(" details=").append(normalizedDetails)
        }

        synchronized(outputLock) {
            runCatching { Log.println(priority, tag, entry) }
            if (entries.size >= maxEntries) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxEntryCharacters)

    private fun elapsedRealtimeMs(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
}
