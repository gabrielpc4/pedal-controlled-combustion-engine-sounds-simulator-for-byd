package com.gabrielpc.enginesoundsimulator

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.audio.EngineSoundPerspective
import com.gabrielpc.enginesoundsimulator.audio.WavPcmDecoder

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
    fun everyProfilePackagesItsRequiredEffectsAsDecodablePcm() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        EngineSampleProfiles.all.forEach { profile ->
            EngineSoundPerspective.entries.forEach { perspective ->
                profile.requiredAssets(perspective).forEach { assetName ->
                    val path = "sample_engine/${profile.assetDirectory}/$assetName"
                    assets.open(path).use { stream ->
                        val header = ByteArray(4)
                        assertEquals(4, stream.read(header))
                        assertEquals("RIFF", String(header, Charsets.US_ASCII))
                    }
                }
                profile.program(perspective).layers.forEach { layer ->
                    val path = "sample_engine/${profile.assetDirectory}/${layer.assetName}"
                    val decoded = assets.open(path).use(WavPcmDecoder::decode)
                    assertTrue("$path has no audio", decoded.frameCount > 32)
                    assertTrue("$path is not mono/stereo", decoded.sourceChannels in 1..2)
                    assertTrue("$path has unsupported rate", decoded.sampleRate == 44_100 || decoded.sampleRate == 48_000)
                }
            }
        }
    }
}
