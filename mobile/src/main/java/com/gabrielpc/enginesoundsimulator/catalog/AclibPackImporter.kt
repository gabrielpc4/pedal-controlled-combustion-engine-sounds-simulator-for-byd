package com.gabrielpc.enginesoundsimulator.catalog

import android.content.Context
import android.net.Uri
import com.gabrielpc.enginesoundsimulator.audio.DecodedAudioBudget
import com.gabrielpc.enginesoundsimulator.audio.NativeFlacDecoder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal class PackValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal data class DecodedPcmIntegrity(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val frameCount: Long,
    val pcmSha256: String,
)

internal fun interface FlacPcmIntegrityVerifier {
    fun decodeAndHash(file: File, maximumDecodedBytes: Long): DecodedPcmIntegrity
}

/** Uses the pinned native libFLAC decoder, never a vendor media codec. */
internal object NativeFlacPcmIntegrityVerifier : FlacPcmIntegrityVerifier {
    override fun decodeAndHash(file: File, maximumDecodedBytes: Long): DecodedPcmIntegrity {
        NativeFlacDecoder.decode(file, maximumDecodedBytes).use { clip ->
            if (clip.channelCount != SoundFamilyManifestV1.CHANNELS) {
                throw PackValidationException("Decoded FLAC is not stereo")
            }
            val left = clip.channel(0)
            val right = clip.channel(1)
            return DecodedPcmIntegrity(
                sampleRate = clip.sampleRate,
                channels = clip.channelCount,
                bitsPerSample = SoundFamilyManifestV1.BITS_PER_SAMPLE,
                frameCount = clip.frameCount,
                pcmSha256 = sha256InterleavedLittleEndian(left, right, clip.frameCount.toInt()),
            )
        }
    }

    private fun sha256InterleavedLittleEndian(
        left: ShortBuffer,
        right: ShortBuffer,
        frames: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val blockFrames = 4_096
        val block = ByteArray(blockFrames * 4)
        var frame = 0
        while (frame < frames) {
            val count = minOf(blockFrames, frames - frame)
            var byteIndex = 0
            var local = 0
            while (local < count) {
                val leftSample = left.get(frame + local).toInt()
                val rightSample = right.get(frame + local).toInt()
                block[byteIndex++] = leftSample.toByte()
                block[byteIndex++] = (leftSample ushr 8).toByte()
                block[byteIndex++] = rightSample.toByte()
                block[byteIndex++] = (rightSample ushr 8).toByte()
                local += 1
            }
            digest.update(block, 0, byteIndex)
            frame += count
        }
        return digest.digest().toHex()
    }
}

internal data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
)

internal object FlacStreamInfoReader {
    fun read(file: File): FlacStreamInfo = BufferedInputStream(FileInputStream(file)).use { input ->
        val marker = ByteArray(4)
        input.readExactly(marker)
        if (!marker.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))) {
            throw PackValidationException("Audio member is not a native FLAC stream")
        }
        var blockCount = 0
        while (blockCount++ < 128) {
            val first = input.read()
            if (first < 0) throw PackValidationException("FLAC metadata is truncated")
            val isLast = first and 0x80 != 0
            val type = first and 0x7f
            val length = (input.readRequired() shl 16) or (input.readRequired() shl 8) or input.readRequired()
            if (length > MAX_METADATA_BLOCK_BYTES) throw PackValidationException("FLAC metadata block is too large")
            if (type == 0) {
                if (length != 34) throw PackValidationException("FLAC STREAMINFO must be 34 bytes")
                val streamInfo = ByteArray(length)
                input.readExactly(streamInfo)
                var packed = 0L
                var index = 10
                while (index < 18) {
                    packed = (packed shl 8) or (streamInfo[index].toLong() and 0xffL)
                    index += 1
                }
                return FlacStreamInfo(
                    sampleRate = ((packed ushr 44) and 0xfffffL).toInt(),
                    channels = (((packed ushr 41) and 0x7L) + 1L).toInt(),
                    bitsPerSample = (((packed ushr 36) and 0x1fL) + 1L).toInt(),
                    totalSamples = packed and 0xfffffffffL,
                )
            }
            input.skipExactly(length.toLong())
            if (isLast) break
        }
        throw PackValidationException("FLAC has no STREAMINFO metadata block")
    }

    private const val MAX_METADATA_BLOCK_BYTES = 16 * 1024 * 1024
}

internal data class InstalledSoundFamily(
    val rootDirectory: File,
    val manifest: SoundFamilyManifestV1,
) {
    fun trackFile(track: SoundTrackManifestV1): File = checkedMember(track.path)

    fun previewFile(carId: String): File? {
        val car = manifest.car(carId) ?: return null
        val relative = car.previewPath ?: return null
        return checkedMember(relative).takeIf(File::isFile)
    }

    private fun checkedMember(relative: String): File {
        val member = File(rootDirectory, relative).canonicalFile
        val root = rootDirectory.canonicalFile
        if (member.parentFile != root && !member.path.startsWith(root.path + File.separator)) {
            throw IllegalStateException("Installed pack member escaped its private root")
        }
        return member
    }
}

internal data class AclibImportResult(
    val family: InstalledSoundFamily,
    val replacedExistingFamily: Boolean,
)

internal data class AclibPackLimits(
    val maximumArchiveBytes: Long = 512L * 1024L * 1024L,
    val maximumMemberBytes: Long = 256L * 1024L * 1024L,
    val maximumExtractedBytes: Long = 768L * 1024L * 1024L,
    val maximumMemberCount: Int = 512,
)

internal fun validateAclibZipEntry(entry: ZipEntry, limits: AclibPackLimits) {
    val name = entry.name
    if (name.toByteArray(Charsets.UTF_8).size > 240) throw PackValidationException("ZIP member path is too long")
    if (entry.isDirectory || name.isEmpty() || name.startsWith('/') || name.startsWith('\\') || name.contains('\\')) {
        throw PackValidationException("ZIP contains a non-file or non-normalized member")
    }
    val parts = name.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || ':' in it || '\u0000' in it }) {
        throw PackValidationException("ZIP member path is unsafe")
    }
    if (entry.method != ZipEntry.STORED) throw PackValidationException("Pack members must use deterministic ZIP storage")
    if (entry.size < 0 || entry.size > limits.maximumMemberBytes) throw PackValidationException("ZIP member size is invalid")
    if (entry.compressedSize != entry.size) throw PackValidationException("Stored ZIP member has inconsistent sizes")
}

/**
 * Validates and atomically installs a private `.aclib` representation.
 *
 * This API performs blocking I/O and native decoding; callers must invoke it from their
 * cancellable background import worker, never from the UI, runtime-core, or audio thread.
 */
internal class AclibPackImporter private constructor(
    private val store: InstalledSoundFamilyStore,
    private val uriOpener: ((Uri) -> InputStream?)?,
    private val verifier: FlacPcmIntegrityVerifier,
    private val decodedSoftBudgetBytes: Long,
    private val decodedHardBudgetBytes: Long,
    private val limits: AclibPackLimits,
    private val officialFamilyMembership: Map<String, Set<String>>?,
    private val expectedCatalogSha256: String?,
) {
    constructor(
        context: Context,
        verifier: FlacPcmIntegrityVerifier = NativeFlacPcmIntegrityVerifier,
        decodedSoftBudgetBytes: Long = DecodedAudioBudget.forDevice(context).softBytes,
        decodedHardBudgetBytes: Long = DecodedAudioBudget.forDevice(context).hardBytes,
        limits: AclibPackLimits = AclibPackLimits(),
        officialFamilyMembership: Map<String, Set<String>>? = null,
        expectedCatalogSha256: String? = null,
    ) : this(
        store = InstalledSoundFamilyStore(context.applicationContext.filesDir),
        uriOpener = { uri -> context.applicationContext.contentResolver.openInputStream(uri) },
        verifier = verifier,
        decodedSoftBudgetBytes = decodedSoftBudgetBytes,
        decodedHardBudgetBytes = decodedHardBudgetBytes,
        limits = limits,
        officialFamilyMembership = officialFamilyMembership,
        expectedCatalogSha256 = expectedCatalogSha256,
    )

    internal constructor(
        privateFilesDirectory: File,
        verifier: FlacPcmIntegrityVerifier,
        decodedHardBudgetBytes: Long,
        decodedSoftBudgetBytes: Long = decodedHardBudgetBytes,
        limits: AclibPackLimits = AclibPackLimits(),
        officialFamilyMembership: Map<String, Set<String>>? = null,
        expectedCatalogSha256: String? = null,
    ) : this(
        store = InstalledSoundFamilyStore(privateFilesDirectory),
        uriOpener = null,
        verifier = verifier,
        decodedSoftBudgetBytes = decodedSoftBudgetBytes,
        decodedHardBudgetBytes = decodedHardBudgetBytes,
        limits = limits,
        officialFamilyMembership = officialFamilyMembership,
        expectedCatalogSha256 = expectedCatalogSha256,
    )

    init {
        require(decodedSoftBudgetBytes > 0L) { "Decoded-audio soft budget must be positive" }
        require(decodedHardBudgetBytes >= decodedSoftBudgetBytes) {
            "Decoded-audio hard budget must cover the soft budget"
        }
    }

    fun importFromUri(uri: Uri): AclibImportResult {
        val input = uriOpener?.invoke(uri)
            ?: throw PackValidationException("The selected document could not be opened")
        return input.use(::importFrom)
    }

    fun importFrom(input: InputStream): AclibImportResult = synchronized(INSTALL_LOCK) {
        store.recoverInterruptedTransactions()
        val incoming = store.newIncomingFile()
        var staging: File? = null
        try {
            copyBounded(input, incoming, limits.maximumArchiveBytes)
            val stagingDirectory = store.newStagingDirectory()
            staging = stagingDirectory
            val validated = validateAndExtract(incoming, stagingDirectory)
            store.commit(validated)
        } catch (error: PackValidationException) {
            staging?.deleteRecursively()
            throw error
        } catch (error: Throwable) {
            staging?.deleteRecursively()
            throw PackValidationException("Sound-pack import failed safely", error)
        } finally {
            incoming.delete()
        }
    }

    fun installedFamilies(): Map<String, InstalledSoundFamily> = synchronized(INSTALL_LOCK) {
        store.recoverInterruptedTransactions()
        store.loadInstalled(expectedCatalogSha256, officialFamilyMembership)
    }

    private fun validateAndExtract(archiveFile: File, stagingDirectory: File): InstalledSoundFamily {
        if (!stagingDirectory.mkdirs()) throw PackValidationException("Could not create private staging directory")
        ZipFile(archiveFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            if (entries.size > limits.maximumMemberCount) throw PackValidationException("Pack contains too many members")
            val names = entries.map { it.name }
            if (names.size != names.toSet().size) throw PackValidationException("Pack contains duplicate ZIP members")
            entries.forEach { validateAclibZipEntry(it, limits) }
            val manifestEntry = entries.singleOrNull { it.name == MANIFEST_NAME }
                ?: throw PackValidationException("Pack must contain exactly one root manifest.json")
            if (manifestEntry.size > SoundFamilyManifestV1.MAX_MANIFEST_BYTES) {
                throw PackValidationException("manifest.json is too large")
            }
            val manifestBytes = archive.getInputStream(manifestEntry).use {
                it.readBounded(SoundFamilyManifestV1.MAX_MANIFEST_BYTES.toLong())
            }
            val manifest = try {
                SoundFamilyManifestV1.parse(manifestBytes)
            } catch (error: IllegalArgumentException) {
                throw PackValidationException("Pack manifest is invalid: ${error.message}", error)
            }
            val officialIds = OfficialCarIndex.cars.mapTo(hashSetOf()) { it.id }
            if (!manifest.memberCarIds.all(officialIds::contains)) {
                throw PackValidationException("Pack contains a non-official or unusable car")
            }
            expectedCatalogSha256?.let { expectedHash ->
                if (manifest.catalogSha256 != expectedHash) {
                    throw PackValidationException("Pack was compiled for a different official catalog")
                }
            }
            officialFamilyMembership?.let { families ->
                val expected = families[manifest.familyId]
                    ?: throw PackValidationException("Pack family is absent from the installed official catalog")
                if (expected != manifest.memberCarIds.toSet()) {
                    throw PackValidationException("Pack membership differs from the installed official catalog")
                }
            }
            if (manifest.totalDecodedBytes > decodedHardBudgetBytes) {
                throw PackValidationException(
                    "Decoded profile needs ${manifest.totalDecodedBytes} bytes; device hard budget is $decodedHardBudgetBytes",
                )
            }
            if (manifest.totalDecodedBytes > decodedSoftBudgetBytes) {
                throw PackValidationException(
                    "Decoded profile needs ${manifest.totalDecodedBytes} bytes; device soft budget is " +
                        "$decodedSoftBudgetBytes. Recompile this family with compiler-defined RPM windows.",
                )
            }
            val expected = linkedSetOf(MANIFEST_NAME).apply {
                manifest.tracks.mapTo(this) { it.path }
                manifest.assets.mapTo(this) { it.path }
            }
            if (names.toSet() != expected) {
                throw PackValidationException("Pack members do not exactly match its manifest")
            }
            var extractedBytes = 0L
            // Multiple authored roles may point at the same verified FLAC. Extract, decode/hash,
            // and account for that physical ZIP member exactly once.
            manifest.tracks.distinctBy(SoundTrackManifestV1::path).forEach { track ->
                val entry = archive.getEntry(track.path)
                    ?: throw PackValidationException("Missing ${track.path}")
                if (entry.size > MAX_AUDIO_MEMBER_BYTES) {
                    throw PackValidationException("Audio member exceeds 128 MiB: ${track.path}")
                }
                extractedBytes = Math.addExact(extractedBytes, entry.size)
                if (extractedBytes > limits.maximumExtractedBytes) throw PackValidationException("Pack expands past its limit")
                val destination = checkedDestination(stagingDirectory, track.path)
                extractAndHash(archive, entry, destination, track.flacSha256)
                validateFlac(track, destination)
            }
            manifest.assets.forEach { asset ->
                val entry = archive.getEntry(asset.path)
                    ?: throw PackValidationException("Missing ${asset.path}")
                if (entry.size > MAX_PREVIEW_MEMBER_BYTES) {
                    throw PackValidationException("Preview member exceeds 16 MiB: ${asset.path}")
                }
                extractedBytes = Math.addExact(extractedBytes, entry.size)
                if (extractedBytes > limits.maximumExtractedBytes) throw PackValidationException("Pack expands past its limit")
                val destination = checkedDestination(stagingDirectory, asset.path)
                extractAndHash(archive, entry, destination, asset.sha256)
                validatePreviewSignature(destination, asset.mediaType)
            }
            File(stagingDirectory, MANIFEST_NAME).writeBytes(manifestBytes)
            File(stagingDirectory, READY_NAME).writeText(manifest.familyId, Charsets.US_ASCII)
            return InstalledSoundFamily(stagingDirectory, manifest)
        }
    }

    private fun validateFlac(track: SoundTrackManifestV1, file: File) {
        val stream = FlacStreamInfoReader.read(file)
        if (
            stream.sampleRate != SoundFamilyManifestV1.SAMPLE_RATE ||
            stream.channels != SoundFamilyManifestV1.CHANNELS ||
            stream.bitsPerSample != SoundFamilyManifestV1.BITS_PER_SAMPLE ||
            stream.totalSamples != track.frameCount
        ) {
            throw PackValidationException("${track.path} STREAMINFO does not match the manifest")
        }
        val decoded = verifier.decodeAndHash(file, track.decodedBytes)
        if (
            decoded.sampleRate != SoundFamilyManifestV1.SAMPLE_RATE ||
            decoded.channels != SoundFamilyManifestV1.CHANNELS ||
            decoded.bitsPerSample != SoundFamilyManifestV1.BITS_PER_SAMPLE ||
            decoded.frameCount != track.frameCount ||
            decoded.pcmSha256 != track.pcmSha256
        ) {
            throw PackValidationException("${track.path} decoded PCM integrity does not match the compiler")
        }
    }

    private fun extractAndHash(archive: ZipFile, entry: ZipEntry, destination: File, expectedHash: String) {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        archive.getInputStream(entry).use { source ->
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkNotInterrupted()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > entry.size) throw PackValidationException("ZIP member expanded past its declared size")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                if (total != entry.size) throw PackValidationException("ZIP member ended before its declared size")
            }
        }
        if (digest.digest().toHex() != expectedHash) throw PackValidationException("Hash mismatch for ${entry.name}")
    }

    private fun checkedDestination(root: File, relative: String): File {
        val destination = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!destination.path.startsWith(canonicalRoot.path + File.separator)) {
            throw PackValidationException("Pack member escaped its private staging directory")
        }
        return destination
    }

    private fun validatePreviewSignature(file: File, mediaType: String) {
        val header = FileInputStream(file).use { it.readPrefix(12) }
        val valid = when (mediaType) {
            "image/jpeg" -> header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "image/png" -> header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            )
            "image/webp" -> header.size >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
            else -> false
        }
        if (!valid) throw PackValidationException("Preview contents do not match $mediaType")
    }

    private fun copyBounded(source: InputStream, destination: File, maximumBytes: Long) {
        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkNotInterrupted()
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) throw PackValidationException("Selected pack exceeds $maximumBytes bytes")
                output.write(buffer, 0, count)
            }
        }
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val READY_NAME = ".ready-v1"
        val INSTALL_LOCK = Any()
        const val MAX_AUDIO_MEMBER_BYTES = 128L * 1024L * 1024L
        const val MAX_PREVIEW_MEMBER_BYTES = 16L * 1024L * 1024L
    }
}

internal class InstalledSoundFamilyStore(private val filesDirectory: File) {
    private val root = File(filesDirectory, "assetto_sound_library_v1")
    private val installedRoot = File(root, "installed")
    private val incomingRoot = File(root, "incoming")

    init {
        installedRoot.mkdirs()
        incomingRoot.mkdirs()
    }

    fun newIncomingFile(): File = File(incomingRoot, "${UUID.randomUUID()}.aclib.partial")

    fun newStagingDirectory(): File = File(installedRoot, ".staging-${UUID.randomUUID()}")

    fun commit(staged: InstalledSoundFamily): AclibImportResult {
        val familyId = staged.manifest.familyId
        val destination = File(installedRoot, familyId)
        val backup = File(installedRoot, ".backup-$familyId-${UUID.randomUUID()}")
        val replacing = destination.exists()
        if (replacing && !destination.renameTo(backup)) {
            throw PackValidationException("Could not stage the previous installed family")
        }
        if (!staged.rootDirectory.renameTo(destination)) {
            if (replacing) backup.renameTo(destination)
            throw PackValidationException("Could not atomically activate the imported family")
        }
        if (backup.exists()) backup.deleteRecursively()
        return AclibImportResult(InstalledSoundFamily(destination, staged.manifest), replacing)
    }

    fun loadInstalled(
        expectedCatalogSha256: String? = null,
        officialFamilyMembership: Map<String, Set<String>>? = null,
    ): Map<String, InstalledSoundFamily> {
        val result = linkedMapOf<String, InstalledSoundFamily>()
        installedRoot.listFiles()?.filter { it.isDirectory && FAMILY_ID.matches(it.name) }?.sortedBy { it.name }?.forEach { directory ->
            try {
                val ready = File(directory, ".ready-v1")
                val manifestFile = File(directory, "manifest.json")
                if (ready.readText(Charsets.US_ASCII) != directory.name) return@forEach
                val manifest = SoundFamilyManifestV1.parse(manifestFile.readBytes())
                val allMembersPresent = manifest.tracks.all { File(directory, it.path).isFile } &&
                    manifest.assets.all { File(directory, it.path).isFile }
                val expectedMembers = officialFamilyMembership?.get(manifest.familyId)
                val catalogCompatible = expectedCatalogSha256 == null ||
                    manifest.catalogSha256 == expectedCatalogSha256
                val membershipCompatible = officialFamilyMembership == null ||
                    expectedMembers == manifest.memberCarIds.toSet()
                if (manifest.familyId == directory.name && allMembersPresent &&
                    catalogCompatible && membershipCompatible
                ) {
                    result[directory.name] = InstalledSoundFamily(directory, manifest)
                }
            } catch (_: Exception) {
                // An incomplete/corrupt family is not exposed to the selector or decoder.
            }
        }
        return result
    }

    fun recoverInterruptedTransactions() {
        installedRoot.mkdirs()
        incomingRoot.mkdirs()
        incomingRoot.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach(File::delete)
        installedRoot.listFiles()?.filter { it.name.startsWith(".staging-") }?.forEach(File::deleteRecursively)
        installedRoot.listFiles()?.filter { it.name.startsWith(".backup-") }?.forEach { backup ->
            val remainder = backup.name.removePrefix(".backup-")
            val candidate = remainder.take(64)
            val family = candidate.takeIf(FAMILY_ID::matches)
            if (family == null) {
                backup.deleteRecursively()
            } else {
                val destination = File(installedRoot, family)
                if (destination.exists()) backup.deleteRecursively() else backup.renameTo(destination)
            }
        }
    }

    private companion object {
        val FAMILY_ID = Regex("^[0-9a-f]{64}$")
    }
}

private fun InputStream.readRequired(): Int = read().also {
    if (it < 0) throw PackValidationException("Unexpected end of stream")
}

private fun InputStream.readExactly(destination: ByteArray) {
    var offset = 0
    while (offset < destination.size) {
        val count = read(destination, offset, destination.size - offset)
        if (count < 0) throw PackValidationException("Unexpected end of stream")
        offset += count
    }
}

private fun InputStream.skipExactly(bytes: Long) {
    var remaining = bytes
    val buffer = ByteArray(8 * 1024)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) throw PackValidationException("Unexpected end of stream")
        remaining -= count
    }
}

private fun InputStream.readBounded(maximumBytes: Long): ByteArray {
    if (maximumBytes > Int.MAX_VALUE) throw IllegalArgumentException("Byte-array limit is too large")
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024L).toInt())
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw PackValidationException("Stream exceeds $maximumBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun InputStream.readPrefix(maximumBytes: Int): ByteArray {
    val destination = ByteArray(maximumBytes)
    var offset = 0
    while (offset < destination.size) {
        val count = read(destination, offset, destination.size - offset)
        if (count < 0) break
        offset += count
    }
    return destination.copyOf(offset)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun checkNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Sound-pack import was cancelled")
}
