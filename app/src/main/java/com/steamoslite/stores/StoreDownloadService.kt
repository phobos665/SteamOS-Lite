package com.steamoslite.stores

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
import com.steamoslite.stores.epic.EpicDownloader
import com.steamoslite.stores.gog.GOGDownloader
import com.steamoslite.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/** A game's download as the UI shows it. */
data class StoreDownload(
    val store: Store,
    val id: String,
    val title: String,
    val fraction: Float = 0f,
    val stage: String = "Queued",
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Downloads GOG and Epic games one at a time as a foreground service, so they carry on in the
 * background. A finished game is recorded as installed and added to the Steam library.
 */
class StoreDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var current: DownloadProgress? = null
    private var currentKey: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                if (key == null || key == currentKey) current?.cancelled = true
                queue.removeAll { key == null || keyOf(it.first, it.second) == key }
                if (key != null) _downloads.update { it - key }
                if (!running) stopSelf()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        running = true
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing…", -1))
        acquireLocks()
        scope.launch { drain() }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        while (true) {
            val (store, id) = queue.poll() ?: break
            val key = keyOf(store, id)
            val library = StoreLibrary(this, store)
            val game = library.game(id) ?: continue
            val progress = DownloadProgress()
            current = progress
            currentKey = key
            val ticker = scope.launch {
                while (true) {
                    _downloads.update { it + (key to StoreDownload(store, id, game.title, progress.fraction, progress.stage)) }
                    notify("${game.title}: ${progress.stage}", (progress.fraction * 100).toInt())
                    delay(500)
                }
            }
            val folder = StoreLibrary.folderName(game.title)
            val installDir = java.io.File(StoreLibrary.gamesRoot(this, store), folder)
            val guestDir = StoreLibrary.guestPath(store, folder)
            val result = runCatching {
                when (store) {
                    Store.GOG -> GOGDownloader.download(this, game, installDir, guestDir, progress)
                    Store.EPIC -> EpicDownloader.download(this, game, installDir, guestDir, progress)
                }
            }
            ticker.cancel()
            result.onSuccess { installation ->
                library.setInstalled(id, installation)
                StoreShortcuts.sync(this)
                _downloads.update { it + (key to StoreDownload(store, id, game.title, 1f, "Installed", done = true)) }
            }.onFailure { e ->
                if (e is DownloadCancelled) {
                    _downloads.update { it - key }
                } else {
                    Timber.tag("Stores").e(e, "Download of ${game.title} failed")
                    _downloads.update { it + (key to StoreDownload(store, id, game.title, progress.fraction, "Failed", e.message ?: "Download failed")) }
                }
            }
        }
        current = null
        currentKey = null
        running = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, percent))
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "steamoslite:games")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "steamoslite:games")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        current?.cancelled = true
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, StoreDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Downloading games")
            .setContentText(if (percent >= 0) "$text · $percent%" else text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "games"
        private const val NOTIFICATION_ID = 10
        private const val ACTION_CANCEL = "com.steamoslite.CANCEL_GAME_DOWNLOAD"
        private const val EXTRA_KEY = "key"

        private val queue = ConcurrentLinkedQueue<Pair<Store, String>>()
        private val _downloads = MutableStateFlow<Map<String, StoreDownload>>(emptyMap())

        /** Downloads by "<store>:<id>", queued, running, failed or just finished. */
        val downloads: StateFlow<Map<String, StoreDownload>> = _downloads.asStateFlow()

        fun keyOf(store: Store, id: String) = "${store.id}:$id"

        fun enqueue(context: Context, game: StoreGame) {
            val key = keyOf(game.store, game.id)
            if (queue.none { keyOf(it.first, it.second) == key }) queue += game.store to game.id
            _downloads.update { it + (key to StoreDownload(game.store, game.id, game.title)) }
            context.startForegroundService(Intent(context, StoreDownloadService::class.java))
        }

        fun cancel(context: Context, store: Store, id: String) {
            context.startService(Intent(context, StoreDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_KEY, keyOf(store, id)))
        }

        fun dismiss(store: Store, id: String) {
            _downloads.update { it - keyOf(store, id) }
        }
    }
}
