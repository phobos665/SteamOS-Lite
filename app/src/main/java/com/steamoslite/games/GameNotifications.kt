package com.steamoslite.games

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.steamoslite.ui.MainActivity

/** The "Game downloads" channel every store's downloads report on: GOG and Epic from the app, Steam from its client. */
object GameNotifications {
    const val CHANNEL = "games"

    fun channel(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Steam downloads on the notification shade. The client itself tells nothing outside Big Picture,
 * so while a session runs its manifests are read every few seconds: a progress notification per
 * game that is queued or downloading, and one saying it is ready when it finishes.
 */
class SteamDownloadNotifier(private val context: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null
    /** Games with a progress notification up, by app id. */
    private val shown = HashMap<String, String>()

    fun start() {
        running = true
        thread = Thread({
            while (running) {
                runCatching { poll() }
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
            }
        }, "steam-downloads").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        val manager = context.getSystemService(NotificationManager::class.java)
        shown.keys.forEach { manager.cancel(idOf(it)) }
        shown.clear()
    }

    private fun poll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val downloads = SteamLibrary.downloads(context).associateBy { it.appId }
        for ((appId, game) in downloads) {
            val download = game.download ?: continue
            val percent = (download.fraction * 100).toInt()
            val text = if (download.total > 0) "$percent% · ${gigabytes(download.done)} of ${gigabytes(download.total)}" else "Queued"
            manager.notify(idOf(appId), Notification.Builder(context, GameNotifications.channel(context))
                .setContentTitle(game.name)
                .setContentText(text)
                .setSubText("Steam")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(GameNotifications.openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, download.total <= 0)
                .build())
            shown[appId] = game.name
        }
        for ((appId, name) in shown.filterKeys { it !in downloads }) {
            shown.remove(appId)
            if (SteamLibrary.isInstalled(context, appId)) {
                manager.notify(idOf(appId), Notification.Builder(context, GameNotifications.channel(context))
                    .setContentTitle(name)
                    .setContentText("Installed and ready to play")
                    .setSubText("Steam")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(GameNotifications.openApp(context))
                    .setAutoCancel(true)
                    .build())
            } else {
                manager.cancel(idOf(appId))
            }
        }
    }

    private fun idOf(appId: String) = 20_000 + (appId.toLongOrNull()?.rem(1_000_000)?.toInt() ?: appId.hashCode() and 0xFFFF)

    private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

    companion object {
        private const val PERIOD_MS = 3_000L
    }
}
