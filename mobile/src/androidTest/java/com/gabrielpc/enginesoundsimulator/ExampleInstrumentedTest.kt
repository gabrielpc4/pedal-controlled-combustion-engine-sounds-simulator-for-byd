package com.gabrielpc.enginesoundsimulator

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gabrielpc.enginesoundsimulator.audio.AudioPackStore
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gabrielpc.enginesoundsimulator", appContext.packageName)
    }

    @Test
    fun everyProfileDeclaresInstallerSuppliedWavAssets() {
        EngineSampleProfiles.all.forEach { profile ->
            EngineSoundPerspective.entries.forEach { perspective ->
                assertTrue(profile.requiredAssets(perspective).all { assetName -> assetName.endsWith(".wav") })
                profile.program(perspective).layers.forEach { layer ->
                    assertTrue(layer.assetName.endsWith(".wav"))
                }
            }
        }
    }

    @Test
    fun sharedBankProfileReadsTheVerifiedOwnerPack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(context.cacheDir, "audio-pack-store-test").apply { deleteRecursively(); mkdirs() }
        try {
            val owner = EngineSampleProfiles.find("nissan-350z")
            val sharedProfile = EngineSampleProfiles.find("nissan-370z-widebody")
            val payload = "test wav payload".toByteArray()
            val path = "audio/idle.wav"
            AudioPackStore(testRoot).also { store ->
                store.install(owner.audioPackId, pack(owner.audioPackId, path, payload))

                assertTrue(store.isInstalled(sharedProfile))
                assertArrayEquals(
                    payload,
                    store.open(sharedProfile, "sample_engine/${sharedProfile.assetDirectory}/idle.wav").use { it.readBytes() },
                )
            }
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun pack(id: String, path: String, payload: ByteArray): ByteArrayInputStream {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val manifest = """{"schema":"byd-wav-audio-pack-v1","id":"$id","version":1,"files":[{"path":"$path","bytes":${payload.size},"sha256":"$digest"}]}"""
        return ByteArrayInputStream(ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.json"))
                archive.write(manifest.toByteArray())
                archive.closeEntry()
                archive.putNextEntry(ZipEntry(path))
                archive.write(payload)
                archive.closeEntry()
            }
            bytes.toByteArray()
        })
    }
}
