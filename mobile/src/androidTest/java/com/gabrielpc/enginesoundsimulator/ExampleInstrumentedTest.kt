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
                store.install(
                    FmodBankProfiles.commonStringsPackId,
                    pack(FmodBankProfiles.commonStringsPackId, "bank/common.strings.bank", payload, "shared"),
                )
                store.install(
                    FmodBankProfiles.commonPackId,
                    pack(FmodBankProfiles.commonPackId, "bank/common.bank", payload, "shared"),
                )
                store.install(
                    owner.bankPackId,
                    pack(owner.bankPackId, path, payload, sharedProfile.id),
                )

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

    private fun pack(id: String, path: String, payload: ByteArray, profileId: String): ByteArrayInputStream {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val physics = "{}".toByteArray()
        val physicsPath = "profiles/$profileId/physics.json"
        val physicsDigest = MessageDigest.getInstance("SHA-256").digest(physics).joinToString("") { "%02x".format(it) }
        val manifest = """{"schema":"byd-fmod-bank-pack-v2","id":"$id","version":1,"files":[{"path":"$path","bytes":${payload.size},"sha256":"$digest"},{"path":"$physicsPath","bytes":${physics.size},"sha256":"$physicsDigest"}]}"""
        return ByteArrayInputStream(ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.json"))
                archive.write(manifest.toByteArray())
                archive.closeEntry()
                archive.putNextEntry(ZipEntry(path))
                archive.write(payload)
                archive.closeEntry()
                archive.putNextEntry(ZipEntry(physicsPath))
                archive.write(physics)
                archive.closeEntry()
            }
            bytes.toByteArray()
        })
    }
}
