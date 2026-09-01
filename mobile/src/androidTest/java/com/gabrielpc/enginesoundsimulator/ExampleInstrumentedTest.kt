package com.gabrielpc.enginesoundsimulator

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gabrielpc.enginesoundsimulator.audio.FmodBankStore
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
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
    fun everyProfileDeclaresAnInstallerSuppliedNativeBank() {
        FmodBankProfiles.all.forEach { profile ->
            assertTrue(profile.bankPackId.isNotBlank())
            assertFalse(profile.bankPackId.endsWith(".wav", ignoreCase = true))
        }
    }

    @Test
    fun sharedBankProfileReadsTheVerifiedOwnerPack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(context.cacheDir, "fmod-bank-store-test").apply { deleteRecursively(); mkdirs() }
        try {
            val owner = FmodBankProfiles.find("nissan-350z")
            val sharedProfile = FmodBankProfiles.find("nissan-370z-widebody")
            val payload = "test bank payload".toByteArray()
            val path = "bank/car.bank"
            FmodBankStore(testRoot).also { store ->
                store.install(owner.bankPackId, pack(owner.bankPackId, path, payload))

                assertTrue(store.isInstalled(sharedProfile))
                assertArrayEquals(
                    payload,
                    store.bankFile(sharedProfile).readBytes(),
                )
            }
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun pack(id: String, path: String, payload: ByteArray): ByteArrayInputStream {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val manifest = """{"schema":"byd-fmod-bank-pack-v1","id":"$id","version":1,"files":[{"path":"$path","bytes":${payload.size},"sha256":"$digest"}]}"""
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
