package com.gabrielpc.bydmotorsound.diagnostics

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * Small, durable event trail for bench and on-car debugging.
 *
 * This is deliberately for lifecycle, input-source, gear-transition, audio-state, and error
 * events only. It must not be called for every 200 Hz simulation step or audio buffer write.
 * Each accepted entry is closed and synced before returning so a process crash does not discard
 * the events that preceded it.
 */
object PersistentDiagnosticLog {
    private const val tag = "BydMotorSound"
    private const val directoryName = "diagnostics"
    private const val activeFileName = "drive-events.log"
    private const val previousFileName = "drive-events.previous.log"
    private const val maxFileBytes = 256L * 1024L
    private const val maxEntryCharacters = 8_192

    private val outputLock = Any()
    private var store: BoundedDiagnosticFileLog? = null
    private var sessionId: String = "uninitialized"
    private var uncaughtHandlerInstalled = false
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** Installs persistent logging once per process. Safe to call repeatedly. */
    fun install(context: Context) {
        synchronized(outputLock) {
            if (store == null) {
                val diagnosticsDirectory = File(context.applicationContext.filesDir, directoryName)
                store = BoundedDiagnosticFileLog(
                    directory = diagnosticsDirectory,
                    activeFileName = activeFileName,
                    previousFileName = previousFileName,
                    maxFileBytes = maxFileBytes,
                )
                sessionId = "${Process.myPid()}-${System.currentTimeMillis()}"
            }

            if (!uncaughtHandlerInstalled) {
                previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    // A malformed/custom Throwable must never prevent Android's normal crash
                    // handler from receiving the original failure.
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
        event("session_started", "process=${Process.myPid()}")
    }

    /** Records a low-rate, state-changing event. Values should not contain vehicle identifiers. */
    fun event(name: String, details: String = "") {
        record(level = "INFO", name = name, details = details, priority = Log.INFO)
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

    /**
     * The active file is app-private and survives Activity/process closure. It is intentionally
     * exposed for debug tooling, not for display in the driving UI.
     */
    fun activeLogPath(context: Context): String =
        File(File(context.applicationContext.filesDir, directoryName), activeFileName).absolutePath

    private fun record(level: String, name: String, details: String, priority: Int) {
        val nowWallMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
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

        Log.println(priority, tag, entry)
        synchronized(outputLock) {
            runCatching { store?.append(entry) }
                .onFailure { writeFailure ->
                    Log.e(tag, "Unable to persist diagnostic event $normalizedName", writeFailure)
                }
        }
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxEntryCharacters)
}

/**
 * Two-file bounded log store: the active file rolls into one previous file before it exceeds its
 * limit. Keeping the implementation platform-free makes its rotation behavior unit-testable.
 */
internal class BoundedDiagnosticFileLog(
    private val directory: File,
    private val activeFileName: String,
    private val previousFileName: String,
    private val maxFileBytes: Long,
) {
    init {
        require(maxFileBytes > 0L) { "maxFileBytes must be positive" }
    }

    @Synchronized
    fun append(entry: String) {
        if (!directory.exists() && !directory.mkdirs()) return

        val active = File(directory, activeFileName)
        val previous = File(directory, previousFileName)
        val fullEntry = (entry + "\n").toByteArray(StandardCharsets.UTF_8)
        val bytes = if (fullEntry.size.toLong() <= maxFileBytes) {
            fullEntry
        } else {
            fullEntry.copyOf(maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        if (active.exists() && active.length() + bytes.size > maxFileBytes) {
            rotate(active, previous)
        }

        FileOutputStream(active, true).use { output ->
            output.write(bytes)
            output.flush()
            // Entries are rare and important; a force here is worth more than avoiding a small
            // amount of I/O. Simulation and audio loops must use transition/heartbeat events.
            output.fd.sync()
        }
    }

    private fun rotate(active: File, previous: File) {
        if (previous.exists() && !previous.delete()) {
            truncate(active)
            return
        }
        if (active.renameTo(previous)) return

        // Rename can fail on unusual Android filesystems. Preserve the old trail if possible,
        // then truncate the active file so the log remains bounded and writable.
        runCatching { active.copyTo(previous, overwrite = true) }
        truncate(active)
    }

    private fun truncate(active: File) {
        FileOutputStream(active, false).use { output ->
            output.flush()
            output.fd.sync()
        }
    }
}
