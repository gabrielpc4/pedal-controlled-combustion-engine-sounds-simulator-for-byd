package com.gabrielpc.enginesoundsimulator.audio

import java.io.File
import java.io.RandomAccessFile

/** Bounded central-directory validation that runs before java.util.zip.ZipFile allocates metadata. */
internal object BydAudioPackZipDirectoryValidator {
    fun validate(archiveFile: File, limits: BydAudioPackLimits) {
        RandomAccessFile(archiveFile, "r").use { archive ->
            val archiveBytes = archive.length()
            if (archiveBytes !in MINIMUM_EOCD_BYTES..limits.maximumArchiveBytes) {
                throw BydAudioPackValidationException("Audio pack ZIP size is invalid")
            }
            val eocdOffset = findEocd(archive, archiveBytes)
            val diskNumber = archive.readUInt16LeAt(eocdOffset + 4L)
            val directoryDisk = archive.readUInt16LeAt(eocdOffset + 6L)
            val diskEntries = archive.readUInt16LeAt(eocdOffset + 8L)
            val totalEntries = archive.readUInt16LeAt(eocdOffset + 10L)
            val directoryBytes32 = archive.readUInt32LeAt(eocdOffset + 12L)
            val directoryOffset32 = archive.readUInt32LeAt(eocdOffset + 16L)
            val requiresZip64 = diskEntries == UINT16_MAX || totalEntries == UINT16_MAX ||
                directoryBytes32 == UINT32_MAX || directoryOffset32 == UINT32_MAX

            val directory = if (requiresZip64) {
                readZip64Directory(archive, eocdOffset)
            } else {
                requireSingleDisk(diskNumber.toLong(), directoryDisk.toLong(), diskEntries.toLong(), totalEntries.toLong())
                CentralDirectory(
                    entryCount = totalEntries.toLong(),
                    offset = directoryOffset32,
                    sizeBytes = directoryBytes32,
                    expectedEnd = eocdOffset,
                )
            }
            validateDirectoryBounds(directory, archiveBytes, limits)
            validateCentralHeaders(archive, directory, limits)
        }
    }

    private fun findEocd(archive: RandomAccessFile, archiveBytes: Long): Long {
        val searchBytes = minOf(archiveBytes, MINIMUM_EOCD_BYTES + MAXIMUM_ZIP_COMMENT_BYTES).toInt()
        val searchOffset = archiveBytes - searchBytes
        val tail = ByteArray(searchBytes)
        archive.seek(searchOffset)
        archive.readFully(tail)
        for (index in tail.size - MINIMUM_EOCD_BYTES.toInt() downTo 0) {
            if (tail.hasSignature(index, EOCD_SIGNATURE)) {
                val commentBytes = tail.readUInt16Le(index + 20)
                val candidate = searchOffset + index
                if (candidate + MINIMUM_EOCD_BYTES + commentBytes == archiveBytes) {
                    return candidate
                }
            }
        }

        throw BydAudioPackValidationException("Audio pack has no bounded ZIP end record")
    }

    private fun readZip64Directory(archive: RandomAccessFile, eocdOffset: Long): CentralDirectory {
        val locatorOffset = eocdOffset - ZIP64_LOCATOR_BYTES
        if (locatorOffset < 0L || archive.readUInt32LeAt(locatorOffset) != ZIP64_LOCATOR_SIGNATURE) {
            throw BydAudioPackValidationException("ZIP64 end locator is missing or corrupt")
        }
        val zip64Disk = archive.readUInt32LeAt(locatorOffset + 4L)
        val zip64Offset = archive.readUInt64LeAt(locatorOffset + 8L, "ZIP64 end-record offset")
        val diskCount = archive.readUInt32LeAt(locatorOffset + 16L)
        if (zip64Disk != 0L || diskCount != 1L) {
            throw BydAudioPackValidationException("Multi-disk ZIP64 audio packs are not supported")
        }
        if (zip64Offset > locatorOffset - MINIMUM_ZIP64_EOCD_BYTES) {
            throw BydAudioPackValidationException("ZIP64 end-record offset is outside the archive")
        }
        if (archive.readUInt32LeAt(zip64Offset) != ZIP64_EOCD_SIGNATURE) {
            throw BydAudioPackValidationException("ZIP64 end record is missing or corrupt")
        }
        val recordBodyBytes = archive.readUInt64LeAt(zip64Offset + 4L, "ZIP64 end-record size")
        if (recordBodyBytes !in MINIMUM_ZIP64_EOCD_BODY_BYTES..MAXIMUM_ZIP64_EOCD_BODY_BYTES) {
            throw BydAudioPackValidationException("ZIP64 end record is outside its metadata limit")
        }
        val recordEnd = checkedAdd(zip64Offset, checkedAdd(12L, recordBodyBytes, "ZIP64 end record"), "ZIP64 end record")
        if (recordEnd != locatorOffset) {
            throw BydAudioPackValidationException("ZIP64 end record does not end at its locator")
        }
        val diskNumber = archive.readUInt32LeAt(zip64Offset + 16L)
        val directoryDisk = archive.readUInt32LeAt(zip64Offset + 20L)
        val diskEntries = archive.readUInt64LeAt(zip64Offset + 24L, "ZIP64 disk entry count")
        val totalEntries = archive.readUInt64LeAt(zip64Offset + 32L, "ZIP64 total entry count")
        requireSingleDisk(diskNumber, directoryDisk, diskEntries, totalEntries)

        return CentralDirectory(
            entryCount = totalEntries,
            sizeBytes = archive.readUInt64LeAt(zip64Offset + 40L, "ZIP64 central-directory size"),
            offset = archive.readUInt64LeAt(zip64Offset + 48L, "ZIP64 central-directory offset"),
            expectedEnd = zip64Offset,
        )
    }

    private fun requireSingleDisk(
        diskNumber: Long,
        directoryDisk: Long,
        diskEntries: Long,
        totalEntries: Long,
    ) {
        if (diskNumber != 0L || directoryDisk != 0L || diskEntries != totalEntries) {
            throw BydAudioPackValidationException("Multi-disk ZIP audio packs are not supported")
        }
    }

    private fun validateDirectoryBounds(
        directory: CentralDirectory,
        archiveBytes: Long,
        limits: BydAudioPackLimits,
    ) {
        if (directory.entryCount !in 1L..limits.maximumMemberCount.toLong()) {
            throw BydAudioPackValidationException("Audio pack contains too many members")
        }
        if (directory.sizeBytes !in CENTRAL_HEADER_BYTES..limits.maximumCentralDirectoryBytes) {
            throw BydAudioPackValidationException("Audio pack central directory exceeds its metadata limit")
        }
        val directoryEnd = checkedAdd(directory.offset, directory.sizeBytes, "central directory")
        if (directory.offset < 0L || directoryEnd != directory.expectedEnd || directoryEnd > archiveBytes) {
            throw BydAudioPackValidationException("Audio pack central directory is outside the archive")
        }
    }

    private fun validateCentralHeaders(
        archive: RandomAccessFile,
        directory: CentralDirectory,
        limits: BydAudioPackLimits,
    ) {
        val directoryEnd = directory.offset + directory.sizeBytes
        var position = directory.offset
        repeat(directory.entryCount.toInt()) {
            if (position > directoryEnd - CENTRAL_HEADER_BYTES ||
                archive.readUInt32LeAt(position) != CENTRAL_HEADER_SIGNATURE
            ) {
                throw BydAudioPackValidationException("Audio pack central directory is truncated or corrupt")
            }
            val flags = archive.readUInt16LeAt(position + 8L)
            if (flags and ENCRYPTED_FLAG != 0) {
                throw BydAudioPackValidationException("Encrypted audio-pack members are not supported")
            }
            val nameBytes = archive.readUInt16LeAt(position + 28L)
            val extraBytes = archive.readUInt16LeAt(position + 30L)
            val commentBytes = archive.readUInt16LeAt(position + 32L)
            val diskStart = archive.readUInt16LeAt(position + 34L)
            if (nameBytes !in 1..MAXIMUM_MEMBER_NAME_BYTES ||
                extraBytes > limits.maximumEntryExtraBytes ||
                commentBytes > limits.maximumEntryCommentBytes || diskStart != 0
            ) {
                throw BydAudioPackValidationException("Audio pack member metadata exceeds its safety limit")
            }
            val variableBytes = nameBytes.toLong() + extraBytes + commentBytes
            position = checkedAdd(position, CENTRAL_HEADER_BYTES + variableBytes, "central member metadata")
            if (position > directoryEnd) {
                throw BydAudioPackValidationException("Audio pack central member exceeds its directory")
            }
        }
        if (position != directoryEnd) {
            throw BydAudioPackValidationException("Audio pack central directory has unexpected trailing metadata")
        }
    }

    private fun RandomAccessFile.readUInt16LeAt(offset: Long): Int {
        val bytes = readAt(offset, 2)

        return bytes.readUInt16Le(0)
    }

    private fun RandomAccessFile.readUInt32LeAt(offset: Long): Long {
        val bytes = readAt(offset, 4)

        return (bytes[0].toLong() and 0xffL) or
            ((bytes[1].toLong() and 0xffL) shl 8) or
            ((bytes[2].toLong() and 0xffL) shl 16) or
            ((bytes[3].toLong() and 0xffL) shl 24)
    }

    private fun RandomAccessFile.readUInt64LeAt(offset: Long, label: String): Long {
        val bytes = readAt(offset, 8)
        if (bytes[7].toInt() and 0x80 != 0) {
            throw BydAudioPackValidationException("$label overflows the supported signed file range")
        }
        var value = 0L
        repeat(8) { index -> value = value or ((bytes[index].toLong() and 0xffL) shl (index * 8)) }

        return value
    }

    private fun RandomAccessFile.readAt(offset: Long, count: Int): ByteArray {
        if (offset < 0L || offset > length() - count) {
            throw BydAudioPackValidationException("ZIP metadata points outside the archive")
        }
        val bytes = ByteArray(count)
        seek(offset)
        readFully(bytes)

        return bytes
    }

    private fun ByteArray.hasSignature(offset: Int, signature: Long): Boolean =
        offset >= 0 && offset <= size - 4 &&
            (this[offset].toLong() and 0xffL) == (signature and 0xffL) &&
            (this[offset + 1].toLong() and 0xffL) == ((signature ushr 8) and 0xffL) &&
            (this[offset + 2].toLong() and 0xffL) == ((signature ushr 16) and 0xffL) &&
            (this[offset + 3].toLong() and 0xffL) == ((signature ushr 24) and 0xffL)

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw BydAudioPackValidationException("$label offset overflow", error)
    }

    private data class CentralDirectory(
        val entryCount: Long,
        val offset: Long,
        val sizeBytes: Long,
        val expectedEnd: Long,
    )

    private const val MINIMUM_EOCD_BYTES = 22L
    private const val MAXIMUM_ZIP_COMMENT_BYTES = 65_535L
    private const val ZIP64_LOCATOR_BYTES = 20L
    private const val MINIMUM_ZIP64_EOCD_BYTES = 56L
    private const val MINIMUM_ZIP64_EOCD_BODY_BYTES = 44L
    private const val MAXIMUM_ZIP64_EOCD_BODY_BYTES = 1_024L
    private const val CENTRAL_HEADER_BYTES = 46L
    private const val MAXIMUM_MEMBER_NAME_BYTES = 240
    private const val ENCRYPTED_FLAG = 1
    private const val UINT16_MAX = 0xffff
    private const val UINT32_MAX = 0xffff_ffffL
    private const val EOCD_SIGNATURE = 0x0605_4b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x0606_4b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x0706_4b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x0201_4b50L
}
