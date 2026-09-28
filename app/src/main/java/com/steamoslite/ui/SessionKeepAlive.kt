package com.steamoslite.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the :session process in the foreground while SteamOS runs with no screen showing it, so
 * Android does not reclaim it. Its notification returns to SteamOS or stops it.
 */
class SessionKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (SessionHost.session != null) SessionHost.end(this) else stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "SteamOS running", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SessionActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionKeepAlive::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SteamOS is running")
            .setContentText("Tap to return. Games start straight away while it runs.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop SteamOS", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.steamoslite.STOP_SESSION"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionKeepAlive::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionKeepAlive::class.java))
        }

        /** Ends a running session from another process (the home screen's Stop button). */
        fun requestStop(context: Context) {
            context.startService(Intent(context, SessionKeepAlive::class.java).setAction(ACTION_STOP))
        }
    }
}
