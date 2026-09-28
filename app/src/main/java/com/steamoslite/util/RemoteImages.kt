package com.steamoslite.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Images from Steam's CDN (achievement icons, store screenshots), kept in the app's cache so each
 * is fetched once. Small and dependency-free: the details page is the only user.
 */
object RemoteImages {
    private const val TAG = "RemoteImages"

    /** The image at [url], decoded no larger than about [maxPx] on its long side; null offline. */
    fun load(context: Context, url: String, maxPx: Int): Bitmap? {
        val file = File(context.cacheDir, "remote-images/" + sha1(url))
        if (!file.isFile) {
            try {
                file.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != 200) return null
                    val partial = File(file.path + ".part")
                    conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                    partial.renameTo(file)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not fetch $url: ${e.message}")
                return null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
