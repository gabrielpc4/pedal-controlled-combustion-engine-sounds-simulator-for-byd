package com.gabrielpc.enginesoundsimulator.audio

import java.security.MessageDigest

internal data class BydAudioPackFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
)

internal data class BydAudioPackManifest(
    val packId: String,
    val packVersion: Int,
    val files: List<BydAudioPackFile>,
    val manifestSha256: String,
) {
    val filesByPath: Map<String, BydAudioPackFile> = files.associateBy(BydAudioPackFile::path)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_NAME = "manifest.json"
        const val MAX_MANIFEST_BYTES = 128 * 1024

        private val packIdPattern = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
        private val sha256Pattern = Regex("^[0-9a-f]{64}$")

        fun parse(bytes: ByteArray): BydAudioPackManifest {
            require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) { "Manifest size is invalid" }
            val root = StrictPackJson.parse(bytes).objectValues("manifest")
            root.requireExactKeys("manifest", ROOT_KEYS)
            val schemaVersion = root.getValue("schemaVersion").integerValue("schemaVersion")
            require(schemaVersion == SCHEMA_VERSION.toLong()) { "Unsupported pack schema $schemaVersion" }
            val packId = root.getValue("packId").stringValue("packId")
            require(isValidPackId(packId)) { "Invalid packId" }
            val packVersion = root.getValue("packVersion").integerValue("packVersion")
            require(packVersion in 1..Int.MAX_VALUE.toLong()) { "Invalid packVersion" }
            val fileValues = root.getValue("files").arrayValues("files")
            require(fileValues.isNotEmpty()) { "Pack must declare at least one WAV" }

            val files = fileValues.mapIndexed { index, value ->
                parseFile(value.objectValues("files[$index]"), index)
            }
            require(files.map(BydAudioPackFile::path).distinct().size == files.size) {
                "Pack declares duplicate file paths"
            }

            return BydAudioPackManifest(
                packId = packId,
                packVersion = packVersion.toInt(),
                files = files,
                manifestSha256 = sha256(bytes),
            )
        }

        fun isValidPackId(value: String): Boolean = packIdPattern.matches(value)

        fun isSha256(value: String): Boolean = sha256Pattern.matches(value)

        fun isSafeAssetPath(value: String): Boolean {
            if (
                value.isEmpty() || value.length > 240 || value.startsWith('/') ||
                value.startsWith('\\') || '\\' in value || '\u0000' in value
            ) {
                return false
            }
            val parts = value.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || ':' in it }) return false

            return value.startsWith("sample_engine/") && value.endsWith(".wav", ignoreCase = false)
        }

        private fun parseFile(values: Map<String, PackJsonValue>, index: Int): BydAudioPackFile {
            values.requireExactKeys("files[$index]", FILE_KEYS)
            val path = values.getValue("path").stringValue("files[$index].path")
            require(isSafeAssetPath(path)) { "Unsafe WAV path '$path'" }
            val sizeBytes = values.getValue("sizeBytes").integerValue("files[$index].sizeBytes")
            require(sizeBytes > 0) { "WAV size must be positive for '$path'" }
            val sha256 = values.getValue("sha256").stringValue("files[$index].sha256")
            require(isSha256(sha256)) { "Invalid WAV hash for '$path'" }
            val sampleRate = values.getValue("sampleRate").integerValue("files[$index].sampleRate")
            require(sampleRate in 8_000..192_000) { "Invalid sample rate for '$path'" }
            val channels = values.getValue("channels").integerValue("files[$index].channels")
            require(channels in 1..2) { "Invalid channel count for '$path'" }
            val frameCount = values.getValue("frameCount").integerValue("files[$index].frameCount")
            require(frameCount >= 32) { "WAV is too short for '$path'" }

            return BydAudioPackFile(
                path = path,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                sampleRate = sampleRate.toInt(),
                channels = channels.toInt(),
                frameCount = frameCount,
            )
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private val ROOT_KEYS = setOf("schemaVersion", "packId", "packVersion", "files")
        private val FILE_KEYS = setOf("path", "sizeBytes", "sha256", "sampleRate", "channels", "frameCount")
    }
}

private fun Map<String, PackJsonValue>.requireExactKeys(label: String, expected: Set<String>) {
    val missing = expected - keys
    val extra = keys - expected
    require(missing.isEmpty() && extra.isEmpty()) {
        buildString {
            append("$label keys do not match the schema")
            if (missing.isNotEmpty()) append("; missing ${missing.sorted().joinToString()}")
            if (extra.isNotEmpty()) append("; unknown ${extra.sorted().joinToString()}")
        }
    }
}

internal fun ByteArray.toHex(): String {
    val characters = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        characters[index * 2] = HEX_DIGITS[value ushr 4]
        characters[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }

    return String(characters)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
