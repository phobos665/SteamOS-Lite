package com.steamoslite.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * A large download that survives a bad connection: every failure resumes from the last byte on disk
 * with an HTTP Range request, after an exponential backoff. The partial file is left in place when
 * the download gives up, so the next attempt - a retry, or the service restarted after the process
 * was killed - carries on from there instead of starting over.
 *
 * Gives up after [maxFailures] failures in a row with no bytes received in between; any progress
 * resets the count, so a flaky connection keeps going and a dead one stops.
 */
class ResumableDownload @JvmOverloads constructor(
    private val url: String,
    private val file: File,
    /** The complete size, when known (the catalog states it); 0 = trust the server. */
    private val expectedSize: Long,
    private val maxFailures: Int = 8,
    /** Where failures are reported; replaceable so the JVM tests need no android.util.Log. */
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    /** Wait before retry N (1-based). Replaceable so tests need not sleep for real. */
    private val backoff: (Int) -> Long = { defaultBackoff(it) },
) {
    interface Listener {
        /** Bytes on disk so far and the total (0 when unknown). */
        fun onProgress(done: Long, total: Long)

        /** A failure; the next attempt starts in [delayMs]. */
        fun onRetry(attempt: Int, delayMs: Long, reason: String)
    }

    class Cancelled : IOException("cancelled")

    /** True once the whole file is on disk. Throws [Cancelled] if [cancelled] turns true. */
    fun run(listener: Listener, cancelled: () -> Boolean): Boolean {
        var failures = 0
        while (true) {
            if (cancelled()) throw Cancelled()
            val before = if (file.exists()) file.length() else 0L
            if (expectedSize > 0 && before == expectedSize) return true
            if (expectedSize > 0 && before > expectedSize) {
                log("partial file is larger than the release ($before > $expectedSize); starting over")
                file.delete()
                continue
            }
            try {
                if (attempt(listener, cancelled)) return true
                throw IOException("the connection closed before the end of the file")
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                val after = if (file.exists()) file.length() else 0L
                failures = if (after > before) 1 else failures + 1
                if (failures > maxFailures) {
                    log("giving up after $maxFailures failures without progress: ${e.message}")
                    return false
                }
                val delay = backoff(failures)
                log("download failed at $after bytes (${e.message}); retry $failures in ${delay}ms")
                listener.onRetry(failures, delay, e.message ?: e.javaClass.simpleName)
                sleep(delay, cancelled)
            }
        }
    }

    /** One connection: resumes from what is on disk. True when the file is complete. */
    private fun attempt(listener: Listener, cancelled: () -> Boolean): Boolean {
        val existing = if (file.exists()) file.length() else 0L
        val http = URL(url).openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 20_000
            http.readTimeout = 30_000
            if (existing > 0) http.setRequestProperty("Range", "bytes=$existing-")
            val code = http.responseCode
            val append: Boolean
            val total: Long
            when {
                existing > 0 && code == HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    total = existing + http.contentLengthLong.coerceAtLeast(0)
                }
                existing > 0 && code == 416 -> {
                    // Nothing past what we have. Complete if the size agrees; otherwise the partial
                    // is not a prefix of this file, so start it again.
                    if (expectedSize <= 0 || existing == expectedSize) return true
                    file.delete()
                    throw IOException("server refused to resume (416)")
                }
                code / 100 == 2 -> {
                    // A plain 200: the server ignored the range and is sending it all again.
                    append = false
                    total = http.contentLengthLong.coerceAtLeast(0)
                }
                else -> throw IOException("HTTP $code")
            }
            val size = if (expectedSize > 0) expectedSize else total
            var done = if (append) existing else 0L
            file.parentFile?.mkdirs()
            http.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 250_000_000L) {
                            lastReport = now
                            listener.onProgress(done, size)
                        }
                    }
                }
            }
            listener.onProgress(done, size)
            return size <= 0 || done >= size
        } finally {
            http.disconnect()
        }
    }

    private fun sleep(ms: Long, cancelled: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cancelled()) throw Cancelled()
            Thread.sleep(minOf(250L, end - System.currentTimeMillis()).coerceAtLeast(1L))
        }
    }

    companion object {
        private const val TAG = "ResumableDownload"

        /** 2 s, 4 s, 8 s ... capped at 60 s, with up to 20% jitter. */
        @JvmStatic
        fun defaultBackoff(failures: Int): Long {
            val base = (2_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
            return base + Random.nextLong(base / 5 + 1)
        }
    }
}
