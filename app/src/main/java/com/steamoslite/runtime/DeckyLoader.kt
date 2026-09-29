package com.steamoslite.runtime

import android.content.Context
import com.steamoslite.util.Downloader
import com.steamoslite.util.ResumableDownload
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * Decky Loader, the plugin loader for Steam's Big Picture, from Droid-Deck's ARM64 builds. It talks
 * to Steam through the client's CEF remote debugging port, which the client opens only when
 * .cef-enable-remote-debugging exists, so the marker is kept exactly while Decky is enabled: that
 * port lets any local process act inside Steam.
 */
object DeckyLoader {
    private const val RELEASES = "https://api.github.com/repos/Droid-Deck/decky-loader/releases?per_page=20"
    private const val ASSET = "PluginLoader-arm64"
    private const val ELF_AARCH64 = 183

    data class Release(val tag: String, val url: String, val size: Long, val sha256: String)

    private fun root(context: Context) = LinuxRuntime.rootDir(context)
    private fun loader(context: Context) = File(root(context), "root/homebrew/services/PluginLoader")
    private fun versionFile(context: Context) = File(root(context), "root/homebrew/services/.bl-decky-version")
    private fun enabledMarker(context: Context) = File(root(context), "root/.bl-decky-enabled")
    private fun cefMarker(context: Context) = File(root(context), "root/.local/share/Steam/.cef-enable-remote-debugging")

    /** The installed version, or null. */
    fun installed(context: Context): String? =
        loader(context).takeIf { it.isFile && it.canExecute() }?.let { versionFile(context).takeIf { f -> f.isFile }?.readText()?.trim() ?: "installed" }

    fun enabled(context: Context) = enabledMarker(context).isFile

    fun setEnabled(context: Context, on: Boolean) {
        if (on && installed(context) != null) {
            enabledMarker(context).apply { parentFile?.mkdirs() }.writeText("enabled\n")
            cefMarker(context).apply { parentFile?.mkdirs() }.createNewFile()
        } else {
            enabledMarker(context).delete()
            cefMarker(context).delete()
        }
    }

    /** The newest stable release with an ARM64 loader and a GitHub digest, or null. */
    fun latest(): Release? {
        val body = Downloader.downloadString(RELEASES) ?: return null
        return runCatching {
            val releases = JSONArray(body)
            (0 until releases.length()).map { releases.getJSONObject(it) }
                .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                .firstNotNullOfOrNull { r ->
                    val assets = r.getJSONArray("assets")
                    (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == ASSET }?.let {
                        val digest = it.optString("digest").takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:") ?: return@let null
                        Release(r.optString("tag_name"), it.optString("browser_download_url"), it.optLong("size"), digest)
                    }
                }
        }.getOrNull()
    }

    /** Downloads, checks and activates [release]; the session must not be running. */
    fun install(context: Context, release: Release, onProgress: (Float) -> Unit) {
        val target = loader(context).apply { parentFile?.mkdirs() }
        val temp = File(target.parentFile, "PluginLoader.download")
        try {
            val done = ResumableDownload(release.url, temp, release.size).run(object : ResumableDownload.Listener {
                override fun onProgress(done: Long, total: Long) {
                    if (total > 0) onProgress(done.toFloat() / total)
                }

                override fun onRetry(attempt: Int, delayMs: Long, reason: String) {}
            }) { false }
            check(done) { "the download did not finish" }
            check(sha256(temp).equals(release.sha256, true)) { "the download does not match its checksum" }
            check(isAarch64Elf(temp)) { "the download is not an ARM64 Linux program" }
            check(temp.setExecutable(true, false) && temp.renameTo(target)) { "could not install the loader" }
            versionFile(context).writeText(release.tag + "\n")
        } finally {
            temp.delete()
        }
    }

    fun uninstall(context: Context) {
        setEnabled(context, false)
        loader(context).delete()
        versionFile(context).delete()
    }

    private fun isAarch64Elf(file: File): Boolean = runCatching {
        val h = ByteArray(20)
        file.inputStream().use { if (it.read(h) != h.size) return false }
        h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
            h[4] == 2.toByte() && (h[18].toInt() and 255) + ((h[19].toInt() and 255) shl 8) == ELF_AARCH64
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
