package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineAudioFocusInstrumentedTest {
    @Test
    fun duckTransientAndPermanentLossFollowTheRuntimeContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val engine = EngineAudioEngine(context)
        val competitors = mutableListOf<CompetingFocus>()
        try {
            engine.start()
            await("engine focus acquisition") {
                engine.state().running && engine.focusHeldForTests() &&
                    engine.focusGainForTests() >= 0.999
            }

            competitors += requestCompetingFocus(audioManager, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            await("duck focus loss") {
                abs(engine.focusGainForTests() - 0.20) <= 0.001 &&
                    !engine.state().focusGranted && engine.state().running
            }
            competitors.removeLast().abandon(audioManager)
            await("focus gain after duck") {
                engine.focusHeldForTests() && engine.focusGainForTests() >= 0.999
            }

            competitors += requestCompetingFocus(audioManager, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            await("transient focus silence") {
                engine.focusGainForTests() == 0.0 && !engine.state().focusGranted &&
                    engine.state().running
            }
            competitors.removeLast().abandon(audioManager)
            await("focus gain after transient loss") {
                engine.focusHeldForTests() && engine.focusGainForTests() >= 0.999
            }

            competitors += requestCompetingFocus(audioManager, AudioManager.AUDIOFOCUS_GAIN)
            await("permanent focus silence") {
                engine.focusGainForTests() == 0.0 && !engine.focusHeldForTests() &&
                    !engine.state().focusGranted && engine.state().running
            }
            competitors.removeLast().abandon(audioManager)
            SystemClock.sleep(250)
            assertEquals(0.0, engine.focusGainForTests(), 0.0)
            assertFalse(engine.focusHeldForTests())

            engine.start()
            await("explicit permanent-loss reacquisition") {
                engine.focusHeldForTests() && engine.focusGainForTests() >= 0.999 &&
                    engine.state().focusGranted
            }
        } finally {
            competitors.asReversed().forEach { runCatching { it.abandon(audioManager) } }
            engine.close()
        }
    }

    private fun requestCompetingFocus(audioManager: AudioManager, gain: Int): CompetingFocus {
        val listener = AudioManager.OnAudioFocusChangeListener { }
        val focus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            CompetingFocus(listener, request)
        } else {
            CompetingFocus(listener, null)
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(checkNotNull(focus.request))
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)
        }
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
        return focus
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private data class CompetingFocus(
        val listener: AudioManager.OnAudioFocusChangeListener,
        val request: AudioFocusRequest?,
    ) {
        fun abandon(audioManager: AudioManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(checkNotNull(request))
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
    }
}
