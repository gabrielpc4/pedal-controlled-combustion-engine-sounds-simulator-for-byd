package com.gabrielpc.enginesoundsimulator.drive

import java.io.File

internal data class DebugPackStagingBatch(
    val directory: File,
    val packFiles: List<File>,
)

/** Canonical, non-recursive boundary for the debug-only ADB bulk-import staging adapter. */
internal object DebugPackStagingPolicy {
    fun requireBatch(
        suppliedDirectory: String,
        allowedRoots: List<File>,
        maximumPacks: Int,
    ): DebugPackStagingBatch {
        require(maximumPacks > 0) { "Maximum pack count must be positive" }
        val candidate = File(suppliedDirectory).canonicalFile
        val canonicalRoots = allowedRoots.map(File::getCanonicalFile)
        val insideRoot = canonicalRoots.any { root ->
            val prefix = root.path.trimEnd(File.separatorChar) + File.separator
            candidate.path.startsWith(prefix)
        }
        require(insideRoot && candidate.isDirectory) {
            "Debug bulk import must be a directory under the app's adb-import directory"
        }

        val children = requireNotNull(candidate.listFiles()) {
            "Debug bulk import directory is unreadable"
        }
        val packs = children.sortedBy(File::getName)
        require(packs.isNotEmpty() && packs.size <= maximumPacks) {
            "Debug bulk import requires 1..$maximumPacks packs"
        }
        require(packs.all { file ->
            file.isFile && file.extension == "aclib" && file.canonicalFile.parentFile == candidate
        }) {
            "Debug bulk import directory may contain only direct lowercase .aclib files"
        }
        return DebugPackStagingBatch(candidate, packs.map(File::getCanonicalFile))
    }

    /** Deletes only the exact validated direct children and their now-empty staging directory. */
    fun close(batch: DebugPackStagingBatch): Boolean {
        var complete = true
        batch.packFiles.forEach { file ->
            if (file.exists() && !file.delete()) complete = false
        }
        if (batch.directory.exists() && !batch.directory.delete()) complete = false
        return complete
    }
}
