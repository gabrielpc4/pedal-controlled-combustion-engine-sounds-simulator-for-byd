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

internal data class FmodBankImportResult(
    val importedPackCount: Int,
    val alreadyInstalledPackCount: Int,
    val failures: List<String>,
) {
    val foundPacks: Boolean get() = importedPackCount > 0 || alreadyInstalledPackCount > 0 || failures.isNotEmpty()
}

/**
 * Atomically publishes file-manager-staged FMOD Studio banks. Runtime playback
 * only receives an already verified on-disk bank path; it never reads archives
 * or decodes audio on its control thread.
 */
internal class FmodBankStore(
    filesDirectory: File,
    private val stagedImportDirectory: File? = null,
) {
    private val packsDirectory = File(filesDirectory, "fmod-banks")

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

    fun hasStagedPacks(): Boolean = stagedImportDirectory
        ?.takeIf(File::isDirectory)
        ?.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .filter { it.name in SUPPORTED_IMPORT_GROUPS }
        .any { groupDirectory ->
            groupDirectory.listFiles()
                .orEmpty()
                .any { archive -> archive.isFile && archive.extension.equals(ARCHIVE_EXTENSION, ignoreCase = true) }
        }

    /**
     * Consumes `.bydbank` archives copied to the app-specific external-storage staging folder.
     *
     * This deliberately replaces the companion installer as the normal vehicle workflow. The
     * files remain user-copyable through a file manager, but are still SHA-256-validated and
     * atomically published into private app storage before FMOD can load them. Successfully
     * consumed archives are removed to avoid keeping a second multi-gigabyte copy on the head
     * unit.
     */
    @Synchronized
    fun importStagedPacks(): FmodBankImportResult {
        val root = stagedImportDirectory?.takeIf(File::isDirectory)
            ?: return FmodBankImportResult(0, 0, emptyList())
        var imported = 0
        var alreadyInstalled = 0
        val failures = mutableListOf<String>()
        root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy { it.name }
            .forEach { groupDirectory ->
                if (groupDirectory.name !in SUPPORTED_IMPORT_GROUPS) return@forEach
                groupDirectory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.equals(ARCHIVE_EXTENSION, ignoreCase = true) }
                    .sortedBy { it.name }
                    .forEach { archive ->
                        runCatching {
                            val archiveManifest = ZipFile(archive).use { zip ->
                                val manifestEntry = requireNotNull(zip.getEntry(MANIFEST_NAME)) {
                                    "FMOD bank package has no manifest"
                                }
                                zip.getInputStream(manifestEntry).use(::readManifest)
                            }
                            require(archiveManifest.group == groupDirectory.name) {
                                "Archive group does not match ${groupDirectory.name}"
                            }
                            val existingManifest = installedDirectory(
                                archiveManifest.group,
                                archiveManifest.id,
                            )?.let { directory ->
                                File(directory, MANIFEST_NAME).inputStream().use(::readManifest)
                            }
                            if (existingManifest == archiveManifest) {
                                alreadyInstalled++
                            } else {
                                FileInputStream(archive).use { source ->
                                    install(archiveManifest.group, archiveManifest.id, source)
                                }
                                imported++
                            }
                            // The Mac/source copy remains the recovery artifact. The car-side
                            // staging copy is disposable once it has been verified and published.
                            archive.delete()
                        }.onFailure { error ->
                            failures += "${groupDirectory.name}/${archive.name}: ${error.message ?: error::class.java.simpleName}"
                        }
                    }
            }
        return FmodBankImportResult(imported, alreadyInstalled, failures)
    }

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
            "This is an old or unsupported audio pack. Replace the staged fmod-bank-import folder with a current bundle and reopen the app."
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
        const val ARCHIVE_EXTENSION = "bydbank"
        const val SCHEMA = "byd-fmod-bank-pack-v3"
        const val COPY_BUFFER_BYTES = 256 * 1024
        val SAFE_PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val SUPPORTED_IMPORT_GROUPS = setOf(
            FmodBankProfiles.originalCarsPackId,
            FmodBankProfiles.moddedCarsPackId,
        )
    }
}

internal class FmodBankResolver(context: Context) {
    private val appContext = context.applicationContext
    private val store = FmodBankStore(
        filesDirectory = appContext.filesDir,
        stagedImportDirectory = appContext.getExternalFilesDir(null)?.resolve(STAGED_IMPORT_DIRECTORY_NAME),
    )

    fun importStagedPacks(): FmodBankImportResult = store.importStagedPacks()

    fun ensureEmbeddedModdedPacks(): Int {
        val installed = store.installedPackIds()
        val assets = requireNotNull(appContext.assets.list("embedded-fmod-banks"))
        var imported = 0
        assets.sorted().forEach { assetName ->
            val (group, packId) = if (assetName.startsWith("modded-") && assetName.endsWith(".bydbank")) {
                FmodBankProfiles.moddedCarsPackId to assetName.removeSuffix(".bydbank")
            } else if (assetName == "assetto-common.bydbank") {
                FmodBankProfiles.originalCarsPackId to FmodBankProfiles.commonPackId
            } else if (assetName == "assetto-common-strings.bydbank") {
                FmodBankProfiles.originalCarsPackId to FmodBankProfiles.commonStringsPackId
            } else {
                return@forEach
            }
            if ("$group/$packId" in installed) return@forEach
            appContext.assets.open("embedded-fmod-banks/$assetName").use { source ->
                store.install(group, packId, source)
            }
            imported++
        }
        return imported
    }

    fun hasStagedPacks(): Boolean = store.hasStagedPacks()

    fun bankFiles(profile: FmodBankProfile): FmodBankFiles = FmodBankFiles(
        commonStrings = store.sharedBankFile(FmodBankProfiles.commonStringsPackId),
        common = store.sharedBankFile(FmodBankProfiles.commonPackId),
        car = store.bankFile(profile),
        physics = store.physicsFile(profile),
    )

    /**
     * A package is only selectable when its immutable physics contract belongs to the same
     * profile as its bank.  File presence alone is not sufficient: accepting a different car's
     * valid `physics.json` here would make the selected bank run with unrelated drivetrain data.
     */
    fun isInstalled(profile: FmodBankProfile): Boolean = runCatching {
        store.bankFile(profile)
        store.sharedBankFile(FmodBankProfiles.commonStringsPackId)
        store.sharedBankFile(FmodBankProfiles.commonPackId)
        physics(profile)
    }.isSuccess

    fun physics(profile: FmodBankProfile): AssettoPhysics =
        AssettoPhysicsLoader.load(store.physicsFile(profile)).also { physics ->
            require(physics.profileId == profile.id) {
                "Installed ${profile.displayName} package has physics for ${physics.profileId}, not ${profile.id}."
            }
        }

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

/**
 * File-manager destination below the shared internal-storage Android/data directory. Android
 * grants the owning app access without asking for broad storage permission, including on DiLink.
 */
internal const val STAGED_IMPORT_DIRECTORY_NAME = "fmod-bank-import"

internal data class FmodBankFiles(
    val commonStrings: File,
    val common: File,
    val car: File,
    val physics: File,
)
