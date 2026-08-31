package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal open class BydAudioPackValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal data class BydAudioPackImportResult(
    val packId: String,
    val packVersion: Int,
    val manifestSha256: String,
    val fileCount: Int,
    val installedBytes: Long,
    val replacedExistingPack: Boolean,
    val retentionWarnings: List<String>,
)

internal enum class BydAudioPackImportStage {
    RECEIVE,
    OPEN_ARCHIVE,
    PREPARE_STAGING,
    READ_MANIFEST,
    VALIDATE_CATALOG,
    VALIDATE_LAYOUT,
    EXTRACT_WAV,
    VERIFY_WAV,
    FINALIZE,
    COMMIT,
    CLEANUP,
}

internal data class BydAudioPackImportProgress(
    val stage: BydAudioPackImportStage,
    val detail: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = -1L,
)

internal fun interface BydAudioPackImportObserver {
    fun onProgress(progress: BydAudioPackImportProgress)

    companion object {
        val NONE = BydAudioPackImportObserver { }
    }
}

internal fun interface BydAudioPackAcceptancePolicy {
    fun requireAccepted(manifest: BydAudioPackManifest)

    companion object {
        val ACCEPT_ANY = BydAudioPackAcceptancePolicy { }
    }
}

/** Safety bounds apply to each family pack; a multi-select batch may install many such packs. */
internal data class BydAudioPackLimits(
    // Full 36-car graph audit: 597.7 MiB/1,011 waveform references when deliberately counting
    // duplicates, with a 71.6 MiB largest member. These bounds preserve
    // ample headroom without allowing one malformed family pack to consume the whole device.
    val maximumArchiveBytes: Long = 1_024L * 1_024L * 1_024L,
    val maximumMemberBytes: Long = 384L * 1_024L * 1_024L,
    val maximumExtractedBytes: Long = 1_536L * 1_024L * 1_024L,
    val maximumMemberCount: Int = 2_048,
    val maximumCentralDirectoryBytes: Long = 16L * 1_024L * 1_024L,
    val maximumEntryExtraBytes: Int = 4 * 1_024,
    val maximumEntryCommentBytes: Int = 4 * 1_024,
)

/** Validates and atomically installs a streamed `.bydpack` into app-private storage. */
internal class BydAudioPackImporter private constructor(
    private val store: BydAudioPackStore,
    private val limits: BydAudioPackLimits,
    private val defaultAcceptancePolicy: BydAudioPackAcceptancePolicy,
) {
    constructor(
        context: Context,
        limits: BydAudioPackLimits = BydAudioPackLimits(),
    ) : this(
        store = BydAudioPackStore(context.applicationContext.filesDir),
        limits = limits,
        defaultAcceptancePolicy = CurrentCatalogAcceptancePolicy(context.applicationContext),
    )

    internal constructor(
        privateFilesDirectory: File,
        limits: BydAudioPackLimits = BydAudioPackLimits(),
        capacityProvider: BydAudioPackCapacityProvider = BydAudioPackCapacityProvider { directory -> directory.usableSpace },
        catalogRequirements: () -> Set<EngineAudioPackRequirement> = {
            EngineSampleProfiles.all.mapNotNull(EngineSampleProfile::audioPackRequirement).toSet()
        },
        defaultAcceptancePolicy: BydAudioPackAcceptancePolicy = BydAudioPackAcceptancePolicy.ACCEPT_ANY,
        fileOperations: BydAudioPackFileOperations = PlatformBydAudioPackFileOperations,
    ) : this(
        store = BydAudioPackStore(privateFilesDirectory, capacityProvider, catalogRequirements, fileOperations),
        limits = limits,
        defaultAcceptancePolicy = defaultAcceptancePolicy,
    )

    fun importFrom(
        input: InputStream,
        sourceBytes: Long = -1L,
        observer: BydAudioPackImportObserver = BydAudioPackImportObserver.NONE,
        acceptancePolicy: BydAudioPackAcceptancePolicy = defaultAcceptancePolicy,
    ): BydAudioPackImportResult = synchronized(BydAudioPackStore.IMPORT_LOCK) {
        observer.onProgress(
            BydAudioPackImportProgress(BydAudioPackImportStage.RECEIVE, "Checking private storage"),
        )
        if (sourceBytes > limits.maximumArchiveBytes) {
            throw BydAudioPackValidationException("Audio pack exceeds its size limit")
        }
        store.preflightIncomingArchive(sourceBytes)
        val transaction = store.beginImportTransaction()
        var completedResult: BydAudioPackImportResult? = null
        var primaryFailure: Throwable? = null
        try {
            observer.onProgress(
                BydAudioPackImportProgress(BydAudioPackImportStage.RECEIVE, "Copying pack", totalBytes = sourceBytes),
            )
            copyBounded(input, transaction.incomingFile, limits.maximumArchiveBytes) { copiedBytes ->
                observer.onProgress(
                    BydAudioPackImportProgress(
                        stage = BydAudioPackImportStage.RECEIVE,
                        detail = "Copying pack",
                        completedBytes = copiedBytes,
                        totalBytes = sourceBytes,
                    ),
                )
            }
            store.syncIncomingArchive()
            val validated = validateAndExtract(
                transaction.incomingFile,
                transaction.stagingDirectory,
                observer,
                acceptancePolicy,
            )
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.VALIDATE_CATALOG,
                    detail = "Rechecking the pack against the current app catalog",
                ),
            )
            acceptancePolicy.requireAccepted(validated.manifest)
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.COMMIT,
                    detail = "Installing validated pack",
                    completedBytes = validated.manifest.files.sumOf(BydAudioPackFile::sizeBytes),
                    totalBytes = validated.manifest.files.sumOf(BydAudioPackFile::sizeBytes),
                ),
            )
            val committed = store.commit(validated)
            val manifest = committed.pack.manifest

            completedResult = BydAudioPackImportResult(
                packId = manifest.packId,
                packVersion = manifest.packVersion,
                manifestSha256 = manifest.manifestSha256,
                fileCount = manifest.files.size,
                installedBytes = manifest.files.sumOf(BydAudioPackFile::sizeBytes),
                replacedExistingPack = committed.replacedExistingPack,
                retentionWarnings = committed.retentionWarnings,
            )
        } catch (error: InterruptedIOException) {
            primaryFailure = error
            throw error
        } catch (error: BydAudioPackValidationException) {
            primaryFailure = error
            throw error
        } catch (error: BydAudioPackStorageException) {
            primaryFailure = error
            throw error
        } catch (error: java.io.IOException) {
            primaryFailure = error
            throw java.io.IOException("Could not import audio pack: ${error.message ?: error::class.java.simpleName}", error)
        } catch (error: Exception) {
            primaryFailure = error
            throw BydAudioPackValidationException(
                "Audio-pack import failed: ${error.message ?: error::class.java.simpleName}",
                error,
            )
        } finally {
            if (completedResult != null) {
                runCatching {
                    observer.onProgress(
                        BydAudioPackImportProgress(
                            BydAudioPackImportStage.CLEANUP,
                            "Removing the private temporary archive and staging transaction",
                        ),
                    )
                }
            }
            val cleanupWarnings = store.finishImportTransaction(transaction)
            if (completedResult == null) {
                cleanupWarnings.forEach { warning ->
                    primaryFailure?.addSuppressed(java.io.IOException(warning))
                }
            }
            if (completedResult != null && cleanupWarnings.isNotEmpty()) {
                completedResult = completedResult?.copy(
                    retentionWarnings = completedResult?.retentionWarnings.orEmpty() + cleanupWarnings,
                )
            }
        }

        return@synchronized requireNotNull(completedResult)
    }

    private fun validateAndExtract(
        archiveFile: File,
        stagingDirectory: File,
        observer: BydAudioPackImportObserver,
        acceptancePolicy: BydAudioPackAcceptancePolicy,
    ): InstalledBydAudioPack {
        observer.onProgress(
            BydAudioPackImportProgress(BydAudioPackImportStage.OPEN_ARCHIVE, "Pre-validating bounded ZIP directory"),
        )
        BydAudioPackZipDirectoryValidator.validate(archiveFile, limits)
        observer.onProgress(
            BydAudioPackImportProgress(BydAudioPackImportStage.OPEN_ARCHIVE, "Opening validated ZIP directory"),
        )
        ZipFile(archiveFile).use { archive ->
            val entries = ArrayList<ZipEntry>(minOf(limits.maximumMemberCount, 256))
            val archiveEntries = archive.entries()
            while (archiveEntries.hasMoreElements()) {
                if (entries.size >= limits.maximumMemberCount) {
                    throw BydAudioPackValidationException("Audio pack contains too many members")
                }
                entries += archiveEntries.nextElement()
            }
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.VALIDATE_LAYOUT,
                    detail = "Checking ${entries.size} ZIP members",
                ),
            )
            val names = entries.map(ZipEntry::getName)
            if (names.size != names.toSet().size) {
                throw BydAudioPackValidationException("Audio pack contains duplicate ZIP members")
            }
            entries.forEach(::validateZipEntry)
            val manifestEntry = entries.singleOrNull { entry ->
                entry.name == BydAudioPackManifest.MANIFEST_NAME
            } ?: throw BydAudioPackValidationException("Audio pack must contain one root manifest.json")
            if (manifestEntry.size > BydAudioPackManifest.MAX_MANIFEST_BYTES) {
                throw BydAudioPackValidationException("manifest.json is too large")
            }
            observer.onProgress(
                BydAudioPackImportProgress(BydAudioPackImportStage.READ_MANIFEST, "Reading manifest.json"),
            )
            val manifestBytes = archive.getInputStream(manifestEntry).use { source ->
                source.readBounded(BydAudioPackManifest.MAX_MANIFEST_BYTES.toLong())
            }
            val manifest = try {
                BydAudioPackManifest.parse(manifestBytes)
            } catch (error: IllegalArgumentException) {
                throw BydAudioPackValidationException("Pack manifest is invalid: ${error.message}", error)
            }
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.VALIDATE_CATALOG,
                    detail = "Checking the pack against the current app catalog",
                ),
            )
            acceptancePolicy.requireAccepted(manifest)

            val expectedNames = linkedSetOf(BydAudioPackManifest.MANIFEST_NAME).apply {
                manifest.files.mapTo(this, BydAudioPackFile::path)
            }
            if (names.toSet() != expectedNames) {
                throw BydAudioPackValidationException("ZIP members do not exactly match manifest.json")
            }
            val entriesByPath = manifest.files.associateWith { member ->
                val entry = archive.getEntry(member.path)
                    ?: throw BydAudioPackValidationException("Missing ${member.path}")
                if (entry.size != member.sizeBytes || entry.size > limits.maximumMemberBytes) {
                    throw BydAudioPackValidationException("ZIP size does not match manifest for ${member.path}")
                }

                entry
            }
            val totalExtractedBytes = entriesByPath.values.fold(0L) { total, entry ->
                val updated = try {
                    Math.addExact(total, entry.size)
                } catch (error: ArithmeticException) {
                    throw BydAudioPackValidationException("Audio pack size overflow", error)
                }
                if (updated > limits.maximumExtractedBytes) {
                    throw BydAudioPackValidationException("Audio pack expands past its safety limit")
                }

                updated
            }
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.VALIDATE_LAYOUT,
                    detail = "Manifest and ZIP layout match",
                    totalBytes = totalExtractedBytes,
                ),
            )
            observer.onProgress(
                BydAudioPackImportProgress(
                    stage = BydAudioPackImportStage.EXTRACT_WAV,
                    detail = "Checking private storage for extraction",
                ),
            )
            store.preflightExtraction(
                incomingArchiveBytes = archiveFile.length(),
                manifest = manifest,
                extractedBytes = totalExtractedBytes,
                metadataBytes = manifestBytes.size.toLong() +
                    BydAudioPackStore.readyToken(manifest).toByteArray(Charsets.US_ASCII).size,
            )
            observer.onProgress(
                BydAudioPackImportProgress(
                    BydAudioPackImportStage.PREPARE_STAGING,
                    "Creating private staging directories",
                ),
            )
            store.ensureStagingDirectory(stagingDirectory)
            var extractedBytes = 0L
            manifest.files.forEachIndexed { index, member ->
                val entry = requireNotNull(entriesByPath[member])
                val destination = checkedDestination(stagingDirectory, member.path)
                destination.parentFile?.let(store::ensureStagingDirectory)
                val memberLabel = "${index + 1}/${manifest.files.size} ${member.path}"
                observer.onProgress(
                    BydAudioPackImportProgress(
                        stage = BydAudioPackImportStage.EXTRACT_WAV,
                        detail = memberLabel,
                        completedBytes = extractedBytes,
                        totalBytes = totalExtractedBytes,
                    ),
                )
                extractAndHash(
                    archive = archive,
                    entry = entry,
                    destination = destination,
                    expectedHash = member.sha256,
                    completedBeforeMember = extractedBytes,
                    totalExtractedBytes = totalExtractedBytes,
                    detail = memberLabel,
                    observer = observer,
                )
                extractedBytes += entry.size
                observer.onProgress(
                    BydAudioPackImportProgress(
                        stage = BydAudioPackImportStage.VERIFY_WAV,
                        detail = memberLabel,
                        completedBytes = extractedBytes,
                        totalBytes = totalExtractedBytes,
                    ),
                )
                validateWav(member, destination)
            }

            observer.onProgress(
                BydAudioPackImportProgress(
                    BydAudioPackImportStage.FINALIZE,
                    "Writing and syncing validated pack metadata",
                ),
            )
            writeSynced(File(stagingDirectory, BydAudioPackManifest.MANIFEST_NAME), manifestBytes)
            writeSynced(
                File(stagingDirectory, BydAudioPackStore.READY_NAME),
                BydAudioPackStore.readyToken(manifest).toByteArray(Charsets.US_ASCII),
            )
            store.syncStagedPack(stagingDirectory)

            return InstalledBydAudioPack(stagingDirectory, manifest)
        }
    }

    private fun validateZipEntry(entry: ZipEntry) {
        val name = entry.name
        if (
            entry.isDirectory || name.isEmpty() || name.length > 240 || name.startsWith('/') ||
            name.startsWith('\\') || '\\' in name || '\u0000' in name
        ) {
            throw BydAudioPackValidationException("ZIP contains a non-normalized member")
        }
        val parts = name.split('/')
        if (parts.any { part -> part.isEmpty() || part == "." || part == ".." || ':' in part }) {
            throw BydAudioPackValidationException("ZIP member path is unsafe")
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw BydAudioPackValidationException("ZIP member uses an unsupported compression method")
        }
        if (entry.size < 0 || entry.size > limits.maximumMemberBytes || entry.compressedSize < 0) {
            throw BydAudioPackValidationException("ZIP member size is invalid")
        }
    }

    private fun extractAndHash(
        archive: ZipFile,
        entry: ZipEntry,
        destination: File,
        expectedHash: String,
        completedBeforeMember: Long,
        totalExtractedBytes: Long,
        detail: String,
        observer: BydAudioPackImportObserver,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.getInputStream(entry).use { source ->
            FileOutputStream(destination).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkNotInterrupted()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > entry.size) {
                        throw BydAudioPackValidationException("ZIP member expanded past its declared size")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    observer.onProgress(
                        BydAudioPackImportProgress(
                            stage = BydAudioPackImportStage.EXTRACT_WAV,
                            detail = detail,
                            completedBytes = completedBeforeMember + total,
                            totalBytes = totalExtractedBytes,
                        ),
                    )
                }
                if (total != entry.size) {
                    throw BydAudioPackValidationException("ZIP member ended before its declared size")
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        if (digest.digest().toHex() != expectedHash) {
            throw BydAudioPackValidationException("Hash mismatch for ${entry.name}")
        }
    }

    private fun validateWav(member: BydAudioPackFile, file: File) {
        val wav = try {
            WavFileInspector.inspect(file)
        } catch (error: IllegalArgumentException) {
            throw BydAudioPackValidationException("Invalid WAV ${member.path}: ${error.message}", error)
        }
        if (
            wav.sampleRate != member.sampleRate ||
            wav.channels != member.channels ||
            wav.frameCount != member.frameCount
        ) {
            throw BydAudioPackValidationException("WAV metadata does not match manifest for ${member.path}")
        }
    }

    private fun checkedDestination(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, relative).canonicalFile
        if (!destination.path.startsWith(canonicalRoot.path + File.separator)) {
            throw BydAudioPackValidationException("Pack member escaped its private staging directory")
        }

        return destination
    }

    private fun copyBounded(
        input: InputStream,
        destination: File,
        maximumBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        check(destination.parentFile?.isDirectory == true) { "Incoming archive directory is missing" }
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkNotInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) throw BydAudioPackValidationException("Audio pack exceeds its size limit")
                output.write(buffer, 0, count)
                onProgress(total)
            }
            output.fd.sync()
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}

private class CurrentCatalogAcceptancePolicy(context: Context) : BydAudioPackAcceptancePolicy {
    private val authority = AudioPackCatalogAuthority(context)

    override fun requireAccepted(manifest: BydAudioPackManifest) {
        authority.requireAccepted(manifest)
    }
}

private data class WavFileInfo(
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
)

private object WavFileInspector {
    fun inspect(file: File): WavFileInfo = RandomAccessFile(file, "r").use { wav ->
        require(wav.length() >= 44L) { "File is too short" }
        require(wav.readAscii(4) == "RIFF") { "Not a RIFF file" }
        val riffSize = wav.readUInt32Le()
        require(riffSize + 8L == wav.length()) { "RIFF size does not match the file" }
        require(wav.readAscii(4) == "WAVE") { "Not a WAVE file" }

        var format: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var dataBytes: Long? = null
        while (wav.filePointer < wav.length()) {
            require(wav.length() - wav.filePointer >= 8L) { "Truncated WAV chunk header" }
            val chunkId = wav.readAscii(4)
            val chunkSize = wav.readUInt32Le()
            val chunkStart = wav.filePointer
            val chunkEnd = chunkStart + chunkSize
            require(chunkEnd >= chunkStart && chunkEnd <= wav.length()) { "WAV chunk exceeds the file" }
            val paddedChunkEnd = chunkEnd + (chunkSize and 1L)
            require(paddedChunkEnd >= chunkEnd && paddedChunkEnd <= wav.length()) {
                "WAV chunk padding exceeds the file"
            }

            when (chunkId) {
                "fmt " -> {
                    require(format == null && chunkSize >= 16L) { "Invalid duplicate or short format chunk" }
                    format = wav.readUInt16Le()
                    channels = wav.readUInt16Le()
                    sampleRate = wav.readUInt32Le().toInt()
                    wav.seek(wav.filePointer + 6L)
                    bitsPerSample = wav.readUInt16Le()
                }
                "data" -> {
                    require(format != null) { "WAV data appeared before the format chunk" }
                    require(dataBytes == null) { "WAV has multiple data chunks" }
                    dataBytes = chunkSize
                }
            }
            wav.seek(paddedChunkEnd)
        }

        require(format == 1) { "Only uncompressed PCM WAV is supported" }
        val channelCount = requireNotNull(channels) { "WAV has no format chunk" }
        require(channelCount in 1..2) { "Only mono/stereo WAV is supported" }
        val rate = requireNotNull(sampleRate) { "WAV has no sample rate" }
        require(rate in 8_000..192_000) { "WAV sample rate is invalid" }
        require(bitsPerSample == 16) { "Only PCM16 WAV is supported" }
        val pcmBytes = requireNotNull(dataBytes) { "WAV has no data chunk" }
        val frameBytes = channelCount * Short.SIZE_BYTES
        // Some FSB exports end with one partial stereo frame. The renderer intentionally drops
        // that tail, so the manifest records the same complete-frame count instead of rejecting it.
        val frames = pcmBytes / frameBytes
        require(frames >= 32L) { "WAV is too short" }

        WavFileInfo(rate, channelCount, frames)
    }
}

private fun RandomAccessFile.readAscii(count: Int): String {
    val bytes = ByteArray(count)
    readFully(bytes)

    return String(bytes, Charsets.US_ASCII)
}

private fun RandomAccessFile.readUInt16Le(): Int {
    val low = read()
    val high = read()
    require(low >= 0 && high >= 0) { "Unexpected end of WAV" }

    return low or (high shl 8)
}

private fun RandomAccessFile.readUInt32Le(): Long {
    var value = 0L
    repeat(4) { shift ->
        val byte = read()
        require(byte >= 0) { "Unexpected end of WAV" }
        value = value or (byte.toLong() shl (shift * 8))
    }

    return value
}

private fun checkNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio-pack import was cancelled")
}
