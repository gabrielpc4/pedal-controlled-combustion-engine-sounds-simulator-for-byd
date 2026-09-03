package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.util.JsonReader
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysics
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysicsLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Atomically publishes installer-provided FMOD Studio banks. Runtime playback
 * only receives an already verified on-disk bank path; it never reads archives
 * or decodes audio on its control thread.
 */
internal class FmodBankStore(filesDirectory: File) {
    private val packsDirectory = File(filesDirectory, "fmod-banks")

    fun isInstalled(profile: FmodBankProfile): Boolean =
        runCatching {
            bankFile(profile)
            physicsFile(profile)
            sharedBankFile(FmodBankProfiles.commonStringsPackId)
            sharedBankFile(FmodBankProfiles.commonPackId)
        }.isSuccess

    fun installedPackIds(): Set<String> = packsDirectory.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .flatMap { group -> group.listFiles().orEmpty().filter(File::isDirectory) }
        .filter { File(it, MANIFEST_NAME).isFile }
        .filter { directory ->
            runCatching {
                val manifest = File(directory, MANIFEST_NAME).inputStream().use(::readManifest)
            manifest.id == directory.name && manifest.group == directory.parentFile?.name
            }.getOrDefault(false)
        }
        .mapTo(linkedSetOf()) { "${it.parentFile?.name}/${it.name}" }

    fun bankFile(profile: FmodBankProfile): File = bankFile(profile.packGroup, profile.bankPackId, profile.displayName)

    fun sharedBankFile(packId: String): File = bankFile(FmodBankProfiles.originalCarsPackId, packId, "required shared FMOD")

    fun physicsFile(profile: FmodBankProfile): File {
        val directory = requireNotNull(installedDirectory(profile.packGroup, profile.bankPackId)) {
            "Install the ${profile.displayName} bank before playing it."
        }
        val manifest = File(directory, MANIFEST_NAME).inputStream().use(::readManifest)
        val expectedPath = "profiles/${profile.id}/physics.json"
        require(manifest.files.any { it.path == expectedPath }) {
            "Installed ${profile.displayName} package has no matching Assetto physics."
        }
        return safeDestination(directory, expectedPath).also {
            require(it.isFile) { "Installed ${profile.displayName} physics is missing." }
        }
    }

    fun previewFile(profile: FmodBankProfile): File? = runCatching {
        val directory = installedDirectory(profile.packGroup, profile.bankPackId) ?: return null
        val manifest = File(directory, MANIFEST_NAME).inputStream().use(::readManifest)
        val path = manifest.files.firstOrNull { it.path.startsWith("preview/") }?.path ?: return null
        safeDestination(directory, path).takeIf(File::isFile)
    }.getOrNull()

    private fun bankFile(group: String, packId: String, displayName: String): File {
        val directory = requireNotNull(installedDirectory(group, packId)) {
            "Install the $displayName bank before playing it."
        }
        val manifest = File(directory, MANIFEST_NAME).inputStream().use(::readManifest)
        val bank = manifest.files.singleOrNull { it.path.startsWith("bank/") && it.path.endsWith(".bank") }
            ?: error("Installed $displayName package has no playable bank.")
        return safeDestination(directory, bank.path).also {
            require(it.isFile) { "Installed $displayName bank is missing ${bank.path}." }
        }
    }

    @Synchronized
    fun install(group: String, packId: String, source: InputStream) {
        require(SAFE_PACK_ID.matches(group) && SAFE_PACK_ID.matches(packId)) { "Invalid FMOD bank id" }
        packsDirectory.mkdirs()
        val groupDirectory = File(packsDirectory, group).apply { mkdirs() }
        val incoming = File.createTempFile(".$packId-", ".bydbank", groupDirectory)
        try {
            FileOutputStream(incoming).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
            installArchive(group, packId, incoming)
        } finally {
            incoming.delete()
        }
    }

    @Synchronized
    fun deleteAll() {
        packsDirectory.listFiles()?.forEach(::deleteRecursively)
    }

    private fun installArchive(expectedGroup: String, expectedPackId: String, archive: File) {
        val groupDirectory = File(packsDirectory, expectedGroup).apply { mkdirs() }
        val stage = File(groupDirectory, ".staging-$expectedPackId-${System.nanoTime()}")
        check(stage.mkdirs()) { "Could not create FMOD bank staging directory" }
        try {
            ZipFile(archive).use { zip ->
                val manifestEntry = requireNotNull(zip.getEntry(MANIFEST_NAME)) { "FMOD bank package has no manifest" }
                val manifest = zip.getInputStream(manifestEntry).use(::readManifest)
                require(manifest.id == expectedPackId) { "FMOD bank id does not match destination" }
                require(manifest.group == expectedGroup) { "FMOD bank group does not match destination" }
                manifest.files.forEach { entry ->
                    val sourceEntry = requireNotNull(zip.getEntry(entry.path)) { "FMOD bank package is missing ${entry.path}" }
                    val destination = safeDestination(stage, entry.path)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(sourceEntry).use { source ->
                        FileOutputStream(destination).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                    require(destination.length() == entry.bytes) { "FMOD bank file length differs: ${entry.path}" }
                    require(sha256(destination) == entry.sha256) { "FMOD bank checksum differs: ${entry.path}" }
                }
                require(manifest.files.count { it.path.startsWith("bank/") && it.path.endsWith(".bank") } == 1) {
                    "FMOD bank package must contain exactly one bank"
                }
                zip.getInputStream(manifestEntry).use { source ->
                    FileOutputStream(File(stage, MANIFEST_NAME)).use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }

            val target = File(groupDirectory, expectedPackId)
            val backup = File(groupDirectory, ".previous-$expectedPackId-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Could not replace the existing FMOD bank package")
            }
            if (!stage.renameTo(target)) {
                backup.renameTo(target)
                throw IllegalStateException("Could not publish FMOD bank package")
            }
            deleteRecursively(backup)
        } finally {
            deleteRecursively(stage)
        }
    }

    private fun installedDirectory(group: String, packId: String): File? {
        if (!SAFE_PACK_ID.matches(group) || !SAFE_PACK_ID.matches(packId)) return null
        val directory = File(File(packsDirectory, group), packId)
        return directory.takeIf { File(it, MANIFEST_NAME).isFile }
    }

    private fun readManifest(input: InputStream): FmodBankManifest = JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        reader.beginObject()
        var schema: String? = null
        var id: String? = null
        var group: String? = null
        var version: Int? = null
        var files: List<FmodBankFile>? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema" -> schema = reader.nextString()
                "id" -> id = reader.nextString()
                "group" -> group = reader.nextString()
                "version" -> version = reader.nextInt()
                "files" -> files = readFiles(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(schema == SCHEMA) {
            "This is an old or unsupported audio pack. Use DELETE ALL in the audio installer, then reinstall the current packs."
        }
        require(SAFE_PACK_ID.matches(requireNotNull(id))) { "Invalid FMOD bank id" }
        require(SAFE_PACK_ID.matches(requireNotNull(group))) { "Invalid FMOD bank group" }
        require(requireNotNull(version) > 0) { "Invalid FMOD bank package version" }
        val parsedFiles = requireNotNull(files)
        require(parsedFiles.map(FmodBankFile::path).distinct().size == parsedFiles.size) {
            "FMOD bank package has duplicate files"
        }
        parsedFiles.forEach { file ->
            require(
                file.path.startsWith("bank/") ||
                    file.path.startsWith("profiles/") ||
                    file.path.startsWith("preview/"),
            ) {
                "FMOD bank package path is outside its payload"
            }
            require(isSafeRelativePath(file.path)) { "FMOD bank package has unsafe path" }
            require(file.bytes > 0L && SHA256.matches(file.sha256)) { "FMOD bank package has invalid file metadata" }
        }
        FmodBankManifest(requireNotNull(id), requireNotNull(group), requireNotNull(version), parsedFiles)
    }

    private fun readFiles(reader: JsonReader): List<FmodBankFile> {
        val files = mutableListOf<FmodBankFile>()
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
            files += FmodBankFile(requireNotNull(path), requireNotNull(bytes), requireNotNull(sha256))
        }
        reader.endArray()
        return files
    }

    private fun safeDestination(root: File, relative: String): File {
        require(isSafeRelativePath(relative)) { "FMOD bank package has unsafe path" }
        val destination = File(root, relative).canonicalFile
        require(destination.path.startsWith(root.canonicalPath + File.separator)) { "FMOD bank package escapes destination" }
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

    private data class FmodBankManifest(val id: String, val group: String, val version: Int, val files: List<FmodBankFile>)
    private data class FmodBankFile(val path: String, val bytes: Long, val sha256: String)

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val SCHEMA = "byd-fmod-bank-pack-v3"
        const val COPY_BUFFER_BYTES = 256 * 1024
        val SAFE_PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal class FmodBankResolver(context: Context) {
    private val appContext = context.applicationContext
    private val store = FmodBankStore(appContext.filesDir)

    fun bankFiles(profile: FmodBankProfile): FmodBankFiles = FmodBankFiles(
        commonStrings = store.sharedBankFile(FmodBankProfiles.commonStringsPackId),
        common = store.sharedBankFile(FmodBankProfiles.commonPackId),
        car = store.bankFile(profile),
        physics = store.physicsFile(profile),
    )

    fun isInstalled(profile: FmodBankProfile): Boolean = store.isInstalled(profile)

    fun physics(profile: FmodBankProfile): AssettoPhysics = AssettoPhysicsLoader.load(store.physicsFile(profile))

    fun previewFile(profile: FmodBankProfile): File? = store.previewFile(profile)

    /** Installed bank previews win. Only official cars may fall back to APK-bundled artwork. */
    fun openCarPreviewInput(profile: FmodBankProfile): InputStream? {
        previewFile(profile)?.let { return FileInputStream(it) }
        if (profile.packGroup != FmodBankProfiles.originalCarsPackId) {
            return null
        }
        return runCatching { appContext.assets.open(profile.previewAssetName) }.getOrNull()
    }
}

internal data class FmodBankFiles(
    val commonStrings: File,
    val common: File,
    val car: File,
    val physics: File,
)
