package com.gabrielpc.enginesoundsimulator.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes background media-button broadcasts into the active [MediaShiftButtonCoordinator] session. */
class MediaShiftButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) {
            return
        }

        MediaShiftButtonCoordinator.deliverMediaButtonIntent(intent)
    }
}
