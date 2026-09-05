package com.gabrielpc.enginesoundsimulator.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver

/**
 * Keeps a media session active while the drivetrain loop runs so steering-wheel and head-unit
 * media buttons can trigger manual shifts even when the dashboard activity is in the background.
 */
internal class MediaShiftButtonCoordinator(
    context: Context,
    private val onMediaShiftKey: (Int) -> Boolean,
) {
    private val appContext = context.applicationContext
    private var mediaSession: MediaSessionCompat? = null

    fun start() {
        val existingSession = mediaSession
        if (existingSession != null) {
            existingSession.isActive = true
            activeSession = existingSession
            return
        }

        val session = MediaSessionCompat(appContext, SESSION_TAG)
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val keyEvent = readKeyEvent(mediaButtonEvent) ?: return super.onMediaButtonEvent(mediaButtonEvent)
                if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                    return isMediaShiftKeyCode(keyEvent.keyCode)
                }

                return onMediaShiftKey(keyEvent.keyCode)
            }

            override fun onSkipToNext() {
                onMediaShiftKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }

            override fun onSkipToPrevious() {
                onMediaShiftKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        })
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )

        val receiverComponent = ComponentName(appContext, MediaShiftButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = receiverComponent
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session.setMediaButtonReceiver(pendingIntent)
        session.isActive = true
        mediaSession = session
        activeSession = session
    }

    fun stop() {
        val session = mediaSession
        mediaSession = null
        activeSession = null
        session?.run {
            isActive = false
            release()
        }
    }

    companion object {
        private const val SESSION_TAG = "EngineShiftMediaSession"

        @Volatile
        private var activeSession: MediaSessionCompat? = null

        fun deliverMediaButtonIntent(intent: Intent) {
            val session = activeSession ?: return
            MediaButtonReceiver.handleIntent(session, intent)
        }

        fun isMediaShiftKeyCode(keyCode: Int): Boolean {
            return when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_LEFT,
                -> true
                else -> false
            }
        }

        private fun readKeyEvent(mediaButtonEvent: Intent): KeyEvent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
        }
    }
}
