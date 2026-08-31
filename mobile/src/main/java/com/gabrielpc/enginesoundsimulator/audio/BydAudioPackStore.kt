package com.gabrielpc.enginesoundsimulator.audio

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal data class InstalledBydAudioPack(
    val rootDirectory: File,
    val manifest: BydAudioPackManifest,
) {
    fun contains(assetPath: String): Boolean = manifest.filesByPath.containsKey(assetPath)

    fun open(assetPath: String): InputStream {
        require(manifest.filesByPath.containsKey(assetPath)) { "Audio pack does not declare '$assetPath'" }
        val file = checkedMember(rootDirectory, assetPath)
        check(file.isFile) { "Installed audio pack is missing '$assetPath'" }

        return FileInputStream(file)
    }

    fun file(assetPath: String): File {
        require(manifest.filesByPath.containsKey(assetPath)) { "Audio pack does not declare '$assetPath'" }
        val file = checkedMember(rootDirectory, assetPath)
        check(file.isFile) { "Installed audio pack is missing '$assetPath'" }

        return file
    }
}

internal fun interface BydAudioPackCapacityProvider {
    fun availableBytes(directory: File): Long
}

/** Failure with a stage that can be shown unchanged by the cross-app installer contract. */
internal class BydAudioPackStorageException(
    val stage: BydAudioPackImportStage,
    requiredBytes: Long,
    availableBytes: Long,
    detail: String,
) : IOException(
    "$detail: requires ${requiredBytes} B but only ${availableBytes.coerceAtLeast(0L)} B is available in app-private storage",
)

/** Owns atomic transactions under one app-private directory. */
internal class BydAudioPackStore(
    private val privateFilesDirectory: File,
    private val capacityProvider: BydAudioPackCapacityProvider = BydAudioPackCapacityProvider { directory ->
        directory.usableSpace
    },
    private val catalogRequirements: () -> Set<EngineAudioPackRequirement> = {
        EngineSampleProfiles.all.mapNotNull(EngineSampleProfile::audioPackRequirement).toSet()
    },
    private val fileOperations: BydAudioPackFileOperations = PlatformBydAudioPackFileOperations,
) {
    fun find(requirement: EngineAudioPackRequirement): InstalledBydAudioPack? = synchronized(INSTALL_LOCK) {
        recoverInterruptedTransactionsIfIdle()
        val directory = File(installedRoot, storageKey(requirement))
        load(directory)?.takeIf { pack ->
            pack.manifest.packVersion == requirement.packVersion &&
                pack.manifest.manifestSha256 == requirement.manifestSha256
        }
    }

    fun installed(): List<InstalledBydAudioPack> = synchronized(INSTALL_LOCK) {
        recoverInterruptedTransactionsIfIdle()
        installedRoot.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull(::load)
            .sortedWith(compareBy({ pack -> pack.manifest.packId }, { pack -> pack.manifest.packVersion }))
    }

    fun beginImportTransaction(): BydAudioPackTransaction = synchronized(INSTALL_LOCK) {
        recoverInterruptedTransactionsIfIdle()
        val incomingExisted = incomingRoot.isDirectory
        if (!fileOperations.ensureDirectory(incomingRoot)) {
            throw IOException("Could not create the app-private incoming archive directory")
        }
        val stagingExisted = stagingRoot.isDirectory
        if (!fileOperations.ensureDirectory(stagingRoot)) {
            throw IOException("Could not create the app-private staging directory")
        }
        if (!incomingExisted || !stagingExisted) fileOperations.syncDirectory(root)
        val transaction = BydAudioPackTransaction(
            incomingFile = File(incomingRoot, "${UUID.randomUUID()}.bydpack"),
            stagingDirectory = File(stagingRoot, UUID.randomUUID().toString()),
        )
        val key = transactionRootKey
        activeImportCounts[key] = activeImportCounts.getOrDefault(key, 0) + 1

        transaction
    }

    fun preflightIncomingArchive(sourceBytes: Long) {
        if (sourceBytes < 0L) return
        requireCapacity(
            stage = BydAudioPackImportStage.RECEIVE,
            additionalBytes = sourceBytes,
            detail = "Not enough space to copy the incoming archive",
        )
    }

    fun syncIncomingArchive() {
        fileOperations.syncDirectory(incomingRoot)
    }

    /**
     * At this point the archive is already stored in .incoming. The remaining peak is the staged
     * WAV tree plus the only possible commit overhead: a same-key non-ready directory is moved
     * into .backup before the staged directory is renamed in place. Renames never duplicate WAVs.
     */
    fun preflightExtraction(
        incomingArchiveBytes: Long,
        manifest: BydAudioPackManifest,
        extractedBytes: Long,
        metadataBytes: Long,
    ) {
        val destination = File(installedRoot, storageKey(manifest))
        val backupOverhead = if (destination.exists() && load(destination) == null) {
            BACKUP_ENTRY_OVERHEAD_BYTES
        } else {
            0L
        }
        val stagingBytes = Math.addExact(extractedBytes, metadataBytes)
        val additionalBytes = Math.addExact(stagingBytes, backupOverhead)
        val peakBytes = Math.addExact(incomingArchiveBytes, additionalBytes)
        requireCapacity(
            stage = BydAudioPackImportStage.EXTRACT_WAV,
            additionalBytes = additionalBytes,
            detail = "Not enough space to extract the pack (incoming archive $incomingArchiveBytes B, " +
                "staging $stagingBytes B, atomic commit overhead $backupOverhead B, peak $peakBytes B)",
        )
    }

    fun finishImportTransaction(transaction: BydAudioPackTransaction): List<String> {
        val warnings = mutableListOf<String>()
        try {
            runCatching {
                if (!fileOperations.deleteFile(transaction.incomingFile)) {
                    throw IOException("Could not delete the private incoming archive")
                }
                fileOperations.syncDirectory(incomingRoot)
            }.onFailure { error ->
                warnings += "Cleanup warning: incoming archive cleanup failed: ${error.message ?: error::class.java.simpleName}"
            }
            runCatching {
                if (!fileOperations.deleteTree(transaction.stagingDirectory)) {
                    throw IOException("Could not delete the private staging transaction")
                }
                fileOperations.syncDirectory(stagingRoot)
            }.onFailure { error ->
                warnings += "Cleanup warning: staging cleanup failed: ${error.message ?: error::class.java.simpleName}"
            }
        } finally {
            synchronized(INSTALL_LOCK) {
                val key = transactionRootKey
                val remaining = activeImportCounts.getOrDefault(key, 0) - 1
                if (remaining > 0) {
                    activeImportCounts[key] = remaining
                } else {
                    activeImportCounts.remove(key)
                }
            }
        }

        return warnings
    }

    private fun recoverInterruptedTransactionsIfIdle() {
        if (activeImportCounts.getOrDefault(transactionRootKey, 0) == 0) {
            recoverInterruptedTransactions()
        }
    }

    fun commit(staged: InstalledBydAudioPack): BydAudioPackCommitResult = synchronized(INSTALL_LOCK) {
        val manifest = staged.manifest
        val destination = File(installedRoot, storageKey(manifest))
        val existing = load(destination)
        if (existing?.manifest?.manifestSha256 == manifest.manifestSha256) {
            return@synchronized BydAudioPackCommitResult(
                pack = existing,
                replacedExistingPack = false,
                retentionWarnings = pruneObsoleteReadyVersions(manifest),
            )
        }

        if (!fileOperations.ensureDirectory(installedRoot) || !fileOperations.ensureDirectory(backupRoot)) {
            throw IOException("Could not create audio-pack transaction directories")
        }
        fileOperations.syncDirectory(root)
        val backup = File(backupRoot, destination.name)
        check(!backup.exists()) { "An audio-pack backup transaction is still active" }

        if (destination.exists() && !fileOperations.move(destination, backup)) {
            throw IllegalStateException("Could not stage the existing audio pack for replacement")
        }
        if (backup.exists()) syncMove(installedRoot, backupRoot)

        try {
            if (!fileOperations.move(staged.rootDirectory, destination)) {
                throw IllegalStateException("Could not atomically install the audio pack")
            }
            syncMove(stagingRoot, installedRoot)
            val commitWarnings = mutableListOf<String>()
            if (backup.exists()) {
                if (fileOperations.deleteTree(backup)) {
                    fileOperations.syncDirectory(backupRoot)
                } else {
                    commitWarnings += "Cleanup warning: installed ${manifest.packId}, but its transaction backup remains"
                }
            }

            return@synchronized BydAudioPackCommitResult(
                pack = InstalledBydAudioPack(destination, manifest),
                replacedExistingPack = existing != null,
                retentionWarnings = commitWarnings + pruneObsoleteReadyVersions(manifest),
            )
        } catch (error: Throwable) {
            if (!destination.exists() && backup.exists()) {
                if (fileOperations.move(backup, destination)) {
                    syncMove(backupRoot, installedRoot)
                } else {
                    error.addSuppressed(IOException("Could not roll back ${backup.name}; recovery will retry"))
                }
            }
            throw error
        }
    }

    fun syncStagedPack(stagingDirectory: File) {
        val rootPath = stagingDirectory.canonicalFile
        check(rootPath.isDirectory) { "Cannot finalize a missing staging directory" }
        rootPath.walkBottomUp()
            .filter(File::isDirectory)
            .forEach(fileOperations::syncDirectory)
        fileOperations.syncDirectory(stagingRoot)
    }

    fun ensureStagingDirectory(directory: File) {
        val canonicalStagingRoot = stagingRoot.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        check(
            canonicalDirectory == canonicalStagingRoot ||
                canonicalDirectory.path.startsWith(canonicalStagingRoot.path + File.separator),
        ) { "Staging directory escaped its private root" }
        if (!fileOperations.ensureDirectory(canonicalDirectory)) {
            throw IOException("Could not create private staging directory ${canonicalDirectory.path}")
        }
    }

    fun cleanupObsolete(retainedRequirements: Set<EngineAudioPackRequirement>): List<String> =
        synchronized(INSTALL_LOCK) {
            recoverInterruptedTransactionsIfIdle()
            val removed = mutableListOf<String>()
            installedRoot.listFiles().orEmpty().forEach { directory ->
                val pack = load(directory)
                if (pack != null && pack.manifest.requirement() !in retainedRequirements) {
                    if (!fileOperations.deleteTree(directory)) {
                        throw IOException("Could not remove obsolete audio pack ${directory.name}")
                    }
                    fileOperations.syncDirectory(installedRoot)
                    removed += directory.name
                }
            }

            removed
        }

    private fun recoverInterruptedTransactions() {
        val rootExisted = root.isDirectory
        val installedExisted = installedRoot.isDirectory
        if (!fileOperations.ensureDirectory(root) || !fileOperations.ensureDirectory(installedRoot)) {
            throw IOException("Could not create the audio-pack storage root")
        }
        val incomingExisted = incomingRoot.isDirectory
        val stagingExisted = stagingRoot.isDirectory
        val backupExisted = backupRoot.isDirectory
        if (!fileOperations.ensureDirectory(incomingRoot) ||
            !fileOperations.ensureDirectory(stagingRoot) ||
            !fileOperations.ensureDirectory(backupRoot)
        ) {
            throw IOException("Could not create the audio-pack recovery directories")
        }
        if (!rootExisted) fileOperations.syncDirectory(privateFilesDirectory)
        if (!installedExisted || !incomingExisted || !stagingExisted || !backupExisted) {
            fileOperations.syncDirectory(root)
        }
        val incomingEntries = incomingRoot.listFiles().orEmpty()
        incomingEntries.forEach { file ->
            if (!fileOperations.deleteFile(file)) throw IOException("Could not recover ${file.name}")
        }
        if (incomingEntries.isNotEmpty()) fileOperations.syncDirectory(incomingRoot)
        val stagingEntries = stagingRoot.listFiles().orEmpty()
        stagingEntries.forEach { directory ->
            if (!fileOperations.deleteTree(directory)) throw IOException("Could not recover ${directory.name}")
        }
        if (stagingEntries.isNotEmpty()) fileOperations.syncDirectory(stagingRoot)
        backupRoot.listFiles().orEmpty().forEach { backup ->
            if (load(backup) == null) {
                if (!fileOperations.deleteTree(backup)) throw IOException("Could not remove invalid backup ${backup.name}")
                fileOperations.syncDirectory(backupRoot)
                return@forEach
            }
            val destination = File(installedRoot, backup.name)
            val destinationReady = load(destination) != null
            when {
                destinationReady -> {
                    if (!fileOperations.deleteTree(backup)) throw IOException("Could not remove recovered backup ${backup.name}")
                    fileOperations.syncDirectory(backupRoot)
                }
                destination.exists() -> {
                    if (!fileOperations.deleteTree(destination)) {
                        throw IOException("Could not remove invalid recovery destination ${destination.name}")
                    }
                    fileOperations.syncDirectory(installedRoot)
                    check(fileOperations.move(backup, destination)) { "Could not recover ${backup.name}" }
                    syncMove(backupRoot, installedRoot)
                }
                else -> {
                    check(fileOperations.move(backup, destination)) { "Could not recover ${backup.name}" }
                    syncMove(backupRoot, installedRoot)
                }
            }
        }
        installedRoot.listFiles().orEmpty()
            .filter { candidate -> !candidate.isDirectory || load(candidate) == null }
            .forEach { invalid ->
                if (!fileOperations.deleteTree(invalid)) {
                    throw IOException("Could not remove invalid installed entry ${invalid.name}")
                }
                fileOperations.syncDirectory(installedRoot)
            }
    }

    /**
     * A ready directory is immutable. Existing streams and native memory mappings remain valid
     * after unlink on the app-private Linux filesystem. More importantly, every exact pack
     * referenced by the currently installed catalog is retained; only unreferenced ready packs
     * from the same family are removed after the new directory is ready.
     */
    private fun pruneObsoleteReadyVersions(installed: BydAudioPackManifest): List<String> {
        val warnings = mutableListOf<String>()
        val retainedRequirements = runCatching { catalogRequirements() + installed.requirement() }
            .getOrElse { error ->
                return listOf("Retention warning: installed ${installed.packId}; cleanup deferred: ${error.message ?: error::class.java.simpleName}")
            }
        runCatching {
            installedRoot.listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .mapNotNull { directory -> load(directory) }
                .filter { pack -> pack.manifest.packId == installed.packId }
                .filter { pack -> pack.manifest.requirement() !in retainedRequirements }
                .forEach { obsolete ->
                    val directory = obsolete.rootDirectory
                    if (!fileOperations.deleteTree(directory)) {
                        warnings += "Retention warning: installed ${installed.packId}, but could not remove obsolete ${directory.name}"
                    } else {
                        fileOperations.syncDirectory(installedRoot)
                    }
                }
        }.onFailure { error ->
            warnings += "Retention warning: installed ${installed.packId}; cleanup deferred: ${error.message ?: error::class.java.simpleName}"
        }

        return warnings
    }

    private fun requireCapacity(
        stage: BydAudioPackImportStage,
        additionalBytes: Long,
        detail: String,
    ) {
        require(additionalBytes >= 0L) { "Storage requirement overflow" }
        val availableBytes = capacityProvider.availableBytes(privateFilesDirectory).coerceAtLeast(0L)
        if (availableBytes < additionalBytes) {
            throw BydAudioPackStorageException(stage, additionalBytes, availableBytes, detail)
        }
    }

    private fun load(directory: File): InstalledBydAudioPack? {
        if (!directory.isDirectory) return null
        val manifestFile = File(directory, BydAudioPackManifest.MANIFEST_NAME)
        val readyFile = File(directory, READY_NAME)
        if (!manifestFile.isFile || !readyFile.isFile) return null
        val manifestBytes = runCatching {
            manifestFile.inputStream().use { input -> input.readBounded(BydAudioPackManifest.MAX_MANIFEST_BYTES.toLong()) }
        }.getOrNull() ?: return null
        val manifest = runCatching { BydAudioPackManifest.parse(manifestBytes) }.getOrNull() ?: return null
        if (storageKey(manifest) != directory.name) return null
        val expectedReady = readyToken(manifest)
        val actualReady = runCatching {
            readyFile.inputStream().use { input ->
                String(input.readBounded(MAX_READY_BYTES), Charsets.US_ASCII)
            }
        }.getOrNull() ?: return null
        if (actualReady != expectedReady) return null
        if (manifest.files.any { member ->
                val file = checkedMember(directory, member.path)
                !file.isFile || file.length() != member.sizeBytes
            }
        ) {
            return null
        }

        return InstalledBydAudioPack(directory, manifest)
    }

    private val root: File get() = File(privateFilesDirectory, ROOT_DIRECTORY)
    private val incomingRoot: File get() = File(root, ".incoming")
    private val stagingRoot: File get() = File(root, ".staging")
    private val backupRoot: File get() = File(root, ".backup")
    private val installedRoot: File get() = File(root, "installed")
    private val transactionRootKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { root.canonicalPath }

    private fun syncMove(sourceDirectory: File, destinationDirectory: File) {
        fileOperations.syncDirectory(sourceDirectory)
        if (sourceDirectory.canonicalPath != destinationDirectory.canonicalPath) {
            fileOperations.syncDirectory(destinationDirectory)
        }
    }

    companion object {
        const val ROOT_DIRECTORY = "byd_audio_packs_v1"
        const val READY_NAME = ".ready"
        const val MAX_READY_BYTES = 256L
        private const val BACKUP_ENTRY_OVERHEAD_BYTES = 4L * 1024L
        val INSTALL_LOCK = Any()
        val IMPORT_LOCK = Any()
        private val activeImportCounts = mutableMapOf<String, Int>()

        fun readyToken(manifest: BydAudioPackManifest): String =
            "${manifest.packId}:${manifest.packVersion}:${manifest.manifestSha256}"

        private fun storageKey(requirement: EngineAudioPackRequirement): String =
            "${requirement.packId}--v${requirement.packVersion}--${requirement.manifestSha256}"

        private fun storageKey(manifest: BydAudioPackManifest): String =
            "${manifest.packId}--v${manifest.packVersion}--${manifest.manifestSha256}"
    }
}

internal data class BydAudioPackTransaction(
    val incomingFile: File,
    val stagingDirectory: File,
)

internal data class BydAudioPackCommitResult(
    val pack: InstalledBydAudioPack,
    val replacedExistingPack: Boolean,
    val retentionWarnings: List<String>,
)

private fun checkedMember(root: File, relative: String): File {
    require(BydAudioPackManifest.isSafeAssetPath(relative)) { "Unsafe installed audio path" }
    val canonicalRoot = root.canonicalFile
    val member = File(canonicalRoot, relative).canonicalFile
    check(member.path.startsWith(canonicalRoot.path + File.separator)) {
        "Installed audio pack member escaped its private root"
    }

    return member
}

internal fun InputStream.readBounded(maximumBytes: Long): ByteArray {
    require(maximumBytes in 1..Int.MAX_VALUE.toLong())
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024L).toInt())
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "Stream exceeds $maximumBytes bytes" }
        output.write(buffer, 0, count)
    }

    return output.toByteArray()
}
