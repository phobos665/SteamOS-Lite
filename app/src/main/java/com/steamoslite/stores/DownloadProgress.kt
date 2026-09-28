package com.steamoslite.stores

import java.util.concurrent.atomic.AtomicLong

/** Shared between a store's downloader and the service showing it. */
class DownloadProgress {
    val bytesDone = AtomicLong(0)
    @Volatile var bytesTotal: Long = 0
    @Volatile var stage: String = "Preparing"
    @Volatile var cancelled = false

    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.get().toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    fun checkActive() {
        if (cancelled) throw DownloadCancelled()
    }
}

class DownloadCancelled : Exception("Download cancelled")
