package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.util.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Stores only user-installed WAV packs. The render thread sees an already-published directory. */
internal class AudioPackStore(filesDirectory: File) {
    private val packsDirectory = File(filesDirectory, "audio-packs")

    fun isInstalled(profile: EngineSampleProfile): Boolean = installedDirectory(profile.audioPackId) != null

    fun installedPackIds(): Set<String> = packsDirectory.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .filter { File(it, MANIFEST_NAME).isFile }
        .mapTo(linkedSetOf()) { it.name }

    fun open(profile: EngineSampleProfile, assetPath: String): InputStream {
        val directory = requireNotNull(installedDirectory(profile.audioPackId)) {
            "Install the ${profile.displayName} audio pack before playing it."
        }
        val relative = when {
            assetPath.startsWith("sample_engine/${profile.assetDirectory}/") -> {
                "audio/" + assetPath.removePrefix("sample_engine/${profile.assetDirectory}/")
            }

            assetPath.startsWith("sample_engine/shared/") -> {
                "shared/" + assetPath.removePrefix("sample_engine/shared/")
            }

            else -> throw IllegalArgumentException("Undeclared audio asset path: $assetPath")
        }
        val file = safeDestination(directory, relative)
        require(file.isFile) { "Installed ${profile.displayName} pack is missing $relative." }

        return FileInputStream(file)
    }

    @Synchronized
    fun install(packId: String, source: InputStream) {
        require(SAFE_PACK_ID.matches(packId)) { "Invalid audio pack id" }
        packsDirectory.mkdirs()
        val incoming = File.createTempFile(".$packId-", ".bydpack", packsDirectory)
        try {
            FileOutputStream(incoming).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
            installZip(packId, incoming)
        } finally {
            incoming.delete()
        }
    }

    @Synchronized
    fun deleteAll() {
        packsDirectory.listFiles()?.forEach(::deleteRecursively)
    }

    private fun installZip(expectedPackId: String, archive: File) {
        val stage = File(packsDirectory, ".staging-$expectedPackId-${System.nanoTime()}")
        check(stage.mkdirs()) { "Could not create audio pack staging directory" }
        try {
            ZipFile(archive).use { zip ->
                val manifestEntry = requireNotNull(zip.getEntry(MANIFEST_NAME)) { "Audio pack has no manifest" }
                val manifest = zip.getInputStream(manifestEntry).use(::readManifest)
                require(manifest.id == expectedPackId) { "Audio pack id does not match destination" }
                require(manifest.files.isNotEmpty()) { "Audio pack has no audio files" }
                manifest.files.forEach { entry ->
                    val input = requireNotNull(zip.getEntry(entry.path)) { "Audio pack is missing ${entry.path}" }
                    val destination = safeDestination(stage, entry.path)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(input).use { source ->
                        FileOutputStream(destination).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                    require(destination.length() == entry.bytes) { "Audio pack file length differs: ${entry.path}" }
                    require(sha256(destination) == entry.sha256) { "Audio pack checksum differs: ${entry.path}" }
                }
                zip.getInputStream(manifestEntry).use { source ->
                    FileOutputStream(File(stage, MANIFEST_NAME)).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }

            val target = File(packsDirectory, expectedPackId)
            val backup = File(packsDirectory, ".previous-$expectedPackId-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Could not replace the existing $expectedPackId audio pack")
            }
            if (!stage.renameTo(target)) {
                backup.renameTo(target)
                throw IllegalStateException("Could not publish $expectedPackId audio pack")
            }
            deleteRecursively(backup)
        } finally {
            deleteRecursively(stage)
        }
    }

    private fun installedDirectory(packId: String): File? {
        if (!SAFE_PACK_ID.matches(packId)) return null
        val directory = File(packsDirectory, packId)
        return directory.takeIf { File(it, MANIFEST_NAME).isFile }
    }

    private fun readManifest(input: InputStream): AudioPackManifest = JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        reader.beginObject()
        var schema: String? = null
        var id: String? = null
        var version: Int? = null
        var files: List<AudioPackFile>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema" -> schema = reader.nextString()
                "id" -> id = reader.nextString()
                "version" -> version = reader.nextInt()
                "files" -> files = readFiles(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(schema == SCHEMA) { "Unsupported audio pack format" }
        require(SAFE_PACK_ID.matches(requireNotNull(id))) { "Invalid audio pack id" }
        require(requireNotNull(version) > 0) { "Invalid audio pack version" }
        val parsedFiles = requireNotNull(files)
        require(parsedFiles.map(AudioPackFile::path).distinct().size == parsedFiles.size) { "Audio pack has duplicate files" }
        parsedFiles.forEach { file ->
            require(file.path.startsWith("audio/") || file.path.startsWith("shared/")) { "Audio pack path is outside its payload" }
            require(isSafeRelativePath(file.path)) { "Audio pack has unsafe path" }
            require(file.bytes > 0L && SHA256.matches(file.sha256)) { "Audio pack has invalid file metadata" }
        }
        AudioPackManifest(requireNotNull(id), requireNotNull(version), parsedFiles)
    }

    private fun readFiles(reader: JsonReader): List<AudioPackFile> {
        val files = mutableListOf<AudioPackFile>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var path: String? = null
            var bytes: Long? = null
            var sha256: String? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "path" -> path = reader.nextString()
                    "bytes" -> bytes = reader.nextLong()
                    "sha256" -> sha256 = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            files += AudioPackFile(requireNotNull(path), requireNotNull(bytes), requireNotNull(sha256))
        }
        reader.endArray()
        return files
    }

    private fun safeDestination(root: File, relative: String): File {
        require(isSafeRelativePath(relative)) { "Audio pack has unsafe path" }
        val destination = File(root, relative).canonicalFile
        require(destination.path.startsWith(root.canonicalPath + File.separator)) { "Audio pack escapes destination" }
        return destination
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains("\\") &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private data class AudioPackManifest(val id: String, val version: Int, val files: List<AudioPackFile>)
    private data class AudioPackFile(val path: String, val bytes: Long, val sha256: String)

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val SCHEMA = "byd-wav-audio-pack-v1"
        const val COPY_BUFFER_BYTES = 256 * 1024
        val SAFE_PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal fun interface AudioAssetSource {
    fun open(assetPath: String): InputStream
}

internal class EngineAudioAssetResolver(context: Context) {
    private val store = AudioPackStore(context.applicationContext.filesDir)

    fun sourceFor(profile: EngineSampleProfile): AudioAssetSource {
        require(store.isInstalled(profile)) { "Install the ${profile.displayName} audio pack before playing it." }
        return AudioAssetSource { assetPath -> store.open(profile, assetPath) }
    }

    fun isInstalled(profile: EngineSampleProfile): Boolean = store.isInstalled(profile)
}
