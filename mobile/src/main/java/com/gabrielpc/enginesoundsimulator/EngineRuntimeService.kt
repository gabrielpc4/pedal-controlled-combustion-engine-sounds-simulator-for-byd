package com.gabrielpc.enginesoundsimulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps engine mixing alive while the dashboard activity is in the background. */
class EngineRuntimeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        // A process restart cannot reconstruct the live controller/FMOD session from this
        // notification-only service. Keep normal in-process background mixing, but do not leave
        // a restarted process alive with no audio work attached to it.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as EngineSoundsApplication).shutdownEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openDashboardIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.engine_runtime_notification_title))
            .setContentText(getString(R.string.engine_runtime_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openDashboardIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.engine_runtime_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.engine_runtime_notification_text)
            setShowBadge(false)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "engine_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.gabrielpc.enginesoundsimulator.action.STOP_ENGINE_RUNTIME"

        fun startIntent(context: Context): Intent {
            return Intent(context, EngineRuntimeService::class.java)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, EngineRuntimeService::class.java).setAction(ACTION_STOP)
        }
    }
}
