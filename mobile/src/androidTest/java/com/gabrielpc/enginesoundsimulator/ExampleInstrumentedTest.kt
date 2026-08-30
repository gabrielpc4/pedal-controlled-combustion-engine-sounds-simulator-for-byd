package com.gabrielpc.enginesoundsimulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.audio.FmodCarProfiles
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gabrielpc.enginesoundsimulator", appContext.packageName)
    }

    @Test
    fun apkPackagesOnlyTheVerifiedFmodBankSet() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val profile = FmodCarProfiles.default
        val expectedHashes = linkedMapOf(
            profile.stringsBankAssetName to
                "f9b633795f1c1634f1f1f7e9fed8a5c53c9c6b46554cc52b7e7880d8b3481381",
            profile.commonBankAssetName to
                "821df0944062f5bf134b184daf099ab68fcdb549d06be1c13e721bfbfc5a6b3e",
        ).apply {
            FmodCarProfiles.all.forEach { car ->
                put(car.carBankAssetName, car.carBankSha256)
            }
        }

        assertEquals(
            expectedHashes.keys.map { it.substringAfter("fmod/") }.sorted(),
            assets.list("fmod")?.sorted(),
        )
        expectedHashes.forEach { (assetPath, expectedHash) ->
            val actualHash = assets.open(assetPath).use(::sha256)
            assertEquals("Unexpected SHA-256 for $assetPath", expectedHash, actualHash)
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
