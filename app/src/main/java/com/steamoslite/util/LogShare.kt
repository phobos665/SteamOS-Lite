package com.steamoslite.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.steamoslite.runtime.Session
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hands the last session's logs to the share sheet as one zip - the folder may sit in the app's own
 * external directory, which a file manager on a current Android cannot open.
 */
object LogShare {
    fun hasLogs(context: Context) = Session.latestLogDir(context) != null

    fun share(context: Context): Boolean {
        val dir = Session.latestLogDir(context) ?: return false
        val out = File(context.cacheDir, "shared").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val zip = File(out, "steamos-lite-logs-${dir.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "SteamOS Lite logs ${dir.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
