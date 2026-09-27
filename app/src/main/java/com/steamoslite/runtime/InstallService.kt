package com.steamoslite.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.steamoslite.ui.MainActivity
import com.steamoslite.util.ResumableDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/** What the runtime install is doing, for the home screen and the notification. */
sealed interface InstallStatus {
    data object Idle : InstallStatus
    data class Running(val stage: String, val percent: Int) : InstallStatus
    data class Failed(val message: String) : InstallStatus
    data object Done : InstallStatus
}

/**
 * Runs the runtime install - download, verify, unpack - as a foreground service, so it carries on
 * when the app is minimised or the screen turns off. Holds a wake lock and a Wi-Fi lock for the
 * length of it, shows progress in a notification, and can be cancelled from there.
 *
 * Started with the release in the intent and redelivered if Android kills the process, so an
 * interrupted install picks up again on its own; the download resumes from the partial file.
 */
class InstallService : Service() {
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }
        val release = intent?.let { releaseFrom(it) } ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_REDELIVER_INTENT

        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing…", -1, ongoing = true))
        acquireLocks()
        cancelled = false
        publish(InstallStatus.Running("Downloading", RuntimeInstaller.partialBytes(this, release)
            .let { if (release.size > 0) (it * 100 / release.size).toInt() else 0 }))

        worker = thread(name = "RuntimeInstall") {
            val result = try {
                RuntimeInstaller.install(this, release, { stage, percent ->
                    publish(InstallStatus.Running(stage, percent))
                }, { cancelled })
                InstallStatus.Done
            } catch (e: ResumableDownload.Cancelled) {
                InstallStatus.Idle
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                InstallStatus.Failed(e.message ?: "The install failed.")
            }
            publish(result)
            finish(result)
        }
        return START_REDELIVER_INTENT
    }

    private fun finish(result: InstallStatus) {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A result the user may not be looking at when it lands stays as a notification.
        val manager = getSystemService(NotificationManager::class.java)
        when (result) {
            is InstallStatus.Done -> manager.notify(RESULT_ID, notification("SteamOS is ready", 100, ongoing = false))
            is InstallStatus.Failed -> manager.notify(RESULT_ID, notification(result.message, -1, ongoing = false))
            else -> {}
        }
        stopSelf()
    }

    private fun publish(status: InstallStatus) {
        _status.value = status
        if (status !is InstallStatus.Running) return
        // The notification is rate-limited; Android drops updates that come too fast anyway.
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000) return
        lastNotified = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status.stage, status.percent, ongoing = true))
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steamoslite:install")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "steamoslite:install")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        cancelled = true
        releaseLocks()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Runtime install", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("SteamOS runtime")
            .setContentText(if (percent >= 0 && ongoing) "$text · $percent%" else text)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            builder.setProgress(100, percent.coerceAtLeast(0), percent < 0)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, InstallService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL = "install"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_ID = 2
        private const val ACTION_CANCEL = "com.steamoslite.CANCEL_INSTALL"

        private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
        val status: StateFlow<InstallStatus> = _status.asStateFlow()

        fun start(context: Context, release: RuntimeInstaller.Release) {
            _status.value = InstallStatus.Running("Starting", 0)
            context.startForegroundService(
                Intent(context, InstallService::class.java)
                    .putExtra("version", release.version)
                    .putExtra("url", release.url)
                    .putExtra("sha256", release.sha256)
                    .putExtra("size", release.size),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, InstallService::class.java).setAction(ACTION_CANCEL))
        }

        /** Clears a finished or failed result once the home screen has acted on it. */
        fun acknowledge() {
            if (_status.value !is InstallStatus.Running) _status.value = InstallStatus.Idle
        }

        private fun releaseFrom(intent: Intent): RuntimeInstaller.Release? {
            val version = intent.getStringExtra("version") ?: return null
            val url = intent.getStringExtra("url") ?: return null
            val sha = intent.getStringExtra("sha256") ?: return null
            return RuntimeInstaller.Release(version, url, sha, intent.getLongExtra("size", 0L))
        }
    }
}
