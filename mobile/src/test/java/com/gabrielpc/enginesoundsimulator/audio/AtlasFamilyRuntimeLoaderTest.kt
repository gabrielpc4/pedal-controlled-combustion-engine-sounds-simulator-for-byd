package com.gabrielpc.enginesoundsimulator.audio

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AtlasFamilyRuntimeLoaderTest {
    @Test
    fun constructionDoesNotReadAnyFamilyAssetAndMissingSelectedFamilyFailsLocally() {
        val reads = AtomicInteger()
        val descriptor = descriptor(bytes = 1, hash = "0".repeat(64))
        val loader = AtlasFamilyRuntimeLoader(
            openAsset = { reads.incrementAndGet(); throw FileNotFoundException("missing") },
            descriptors = mapOf(descriptor.id to descriptor),
        )

        assertEquals(0, reads.get())
        assertThrows(FileNotFoundException::class.java) { loader.load(descriptor) }
        assertEquals(1, reads.get())
    }

    @Test
    fun byteHashOversizeAndCorruptFamilyRuntimesFailClosedBeforePlayback() {
        val oneByte = byteArrayOf(7)
        fun loader(descriptor: AtlasFamilyRuntimeDescriptor, bytes: ByteArray): AtlasFamilyRuntimeLoader =
            AtlasFamilyRuntimeLoader({ ByteArrayInputStream(bytes) }, mapOf(descriptor.id to descriptor))

        assertThrows(IllegalArgumentException::class.java) {
            loader(descriptor(bytes = 2, hash = sha(oneByte)), oneByte).load(descriptor(bytes = 2, hash = sha(oneByte)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            loader(descriptor(bytes = 1, hash = "0".repeat(64)), oneByte).load(descriptor(bytes = 1, hash = "0".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val oversized = descriptor(bytes = 4L * 1024L * 1024L + 1, hash = sha(oneByte))
            loader(oversized, oneByte).load(oversized)
        }
        val corrupt = "{}".toByteArray()
        val corruptDescriptor = descriptor(bytes = corrupt.size.toLong(), hash = sha(corrupt))
        assertThrows(Exception::class.java) { loader(corruptDescriptor, corrupt).load(corruptDescriptor) }
    }

    private fun descriptor(bytes: Long, hash: String) = AtlasFamilyRuntimeDescriptor(
        id = "family",
        assetDirectory = "family",
        requirement = EngineAudioPackRequirement("family_pack", 1, "a".repeat(64)),
        runtimeAssetName = "families/family.json",
        runtimeBytes = bytes,
        runtimeSha256 = hash,
        eagerCapabilities = AtlasEagerCapabilities(emptySet(), emptyMap()),
    )

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
