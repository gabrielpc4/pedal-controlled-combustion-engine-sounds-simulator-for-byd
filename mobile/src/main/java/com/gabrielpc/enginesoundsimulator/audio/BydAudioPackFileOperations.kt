package com.gabrielpc.enginesoundsimulator.audio

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Small injectable filesystem boundary for crash-consistency and fault-injection tests. */
internal interface BydAudioPackFileOperations {
    fun ensureDirectory(directory: File): Boolean

    fun move(source: File, destination: File): Boolean

    fun deleteFile(file: File): Boolean

    fun deleteTree(directory: File): Boolean

    fun syncDirectory(directory: File)
}

internal object PlatformBydAudioPackFileOperations : BydAudioPackFileOperations {
    override fun ensureDirectory(directory: File): Boolean = directory.isDirectory || directory.mkdirs()

    override fun move(source: File, destination: File): Boolean = source.renameTo(destination)

    override fun deleteFile(file: File): Boolean = !file.exists() || file.delete()

    override fun deleteTree(directory: File): Boolean = !directory.exists() || directory.deleteRecursively()

    override fun syncDirectory(directory: File) {
        require(directory.isDirectory) { "Cannot sync missing directory ${directory.path}" }
        // Host unit tests do not provide a real android.system.Os. Production Android does, and
        // fsyncing the containing directories is what makes rename/delete outcomes durable.
        if (!isAndroidRuntime()) return
        val descriptor = try {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        } catch (error: Exception) {
            throw IOException("Could not open ${directory.path} for directory sync", error)
        }
        try {
            Os.fsync(descriptor)
        } catch (error: Exception) {
            throw IOException("Could not sync directory ${directory.path}", error)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.runtime.name") == "Android Runtime" ||
            System.getProperty("java.vm.name") == "Dalvik"
}
