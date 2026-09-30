package com.steamoslite.runtime

import android.content.Context
import com.steamoslite.util.FileUtils
import com.steamoslite.util.Hashes
import com.steamoslite.util.ResumableDownload
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * The Android Turnip the app's own compositor puts each frame on the screen with, loaded through
 * adrenotools: the built-in build, one of the builds the APK carries under assets/display_drivers/,
 * or an AdrenoTools zip (meta.json naming the library beside it) downloaded from Banners-Turnip or
 * WinNative. It is loaded once per session process, so a change applies from the next SteamOS start.
 */
object DisplayDrivers {
    private const val SELECTED = "displayDriver"

    /** The driver the APK carries. */
    const val BUNDLED = ""

    data class Installed(
        val id: String,
        val name: String,
        /** Carried by the APK: always there, not removable. */
        val bundled: Boolean = false,
        /** The GPU families it is meant for; empty for any Adreno. */
        val families: Set<VulkanDrivers.Family> = emptySet(),
    )

    private const val ASSETS = "display_drivers"

    /** The builds the APK carries, by id (their zip's name under assets/display_drivers/). */
    private val BUNDLED_FAMILIES = mapOf(
        "turnip-26.3.0-r3" to setOf(VulkanDrivers.Family.A6XX, VulkanDrivers.Family.A7XX),
        "turnip-mrpurple-t30" to setOf(VulkanDrivers.Family.A7XX),
        "turnip-gen8-v34" to setOf(VulkanDrivers.Family.A8XX),
    )

    private fun root(context: Context) = File(context.filesDir, "display_drivers")

    fun installed(context: Context): List<Installed> {
        unpackBundled(context)
        return root(context).listFiles().orEmpty().filter { libraryOf(it) != null }.map { dir ->
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
            val families = BUNDLED_FAMILIES[dir.name]
            Installed(dir.name, meta?.optString("name")?.takeIf { it.isNotBlank() } ?: dir.name, families != null, families.orEmpty())
        }.sortedWith(compareBy({ !it.bundled }, { it.name.lowercase() }))
    }

    /** Unpacks the APK's builds once per APK version; blocking. */
    @Synchronized
    fun unpackBundled(context: Context) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        for (asset in context.assets.list(ASSETS).orEmpty().filter { it.endsWith(".zip") }) {
            val id = asset.removeSuffix(".zip")
            val marker = File(root(context), "$id/.apk-version")
            if (marker.isFile && marker.readText() == version) continue
            val zip = File(context.cacheDir, asset)
            try {
                context.assets.open("$ASSETS/$asset").use { input -> zip.outputStream().use { input.copyTo(it) } }
                unpack(context, zip, id, id)
                marker.writeText(version)
            } catch (e: Exception) {
                android.util.Log.w("DisplayDrivers", "could not unpack the bundled driver $asset", e)
            } finally {
                zip.delete()
            }
        }
    }

    fun selected(context: Context): String {
        val id = Settings.prefs(context).getString(SELECTED, BUNDLED)!!
        return if (id == BUNDLED || libraryOf(File(root(context), id)) != null) id else BUNDLED
    }

    fun select(context: Context, id: String) {
        Settings.prefs(context).edit().putString(SELECTED, id).commit()
    }

    /** The chosen driver as (directory with a trailing /, library name), or null for the bundled one. */
    fun chosen(context: Context): Pair<String, String>? {
        val id = selected(context).takeIf { it != BUNDLED } ?: return null
        val dir = File(root(context), id)
        return libraryOf(dir)?.let { dir.path + "/" to it }
    }

    fun catalog(): List<TurnipReleases.Asset> = TurnipReleases.fetch().filter { !it.linux }

    fun install(context: Context, driver: TurnipReleases.Asset, onProgress: (Float) -> Unit) {
        val zip = File(context.cacheDir, driver.name)
        try {
            val done = ResumableDownload(driver.url, zip, driver.size).run(object : ResumableDownload.Listener {
                override fun onProgress(done: Long, total: Long) {
                    if (total > 0) onProgress(done.toFloat() / total)
                }

                override fun onRetry(attempt: Int, delayMs: Long, reason: String) {}
            }) { false }
            check(done) { "the download did not finish" }
            check(Hashes.sha256(zip).equals(driver.sha256, true)) { "the download does not match its checksum" }
            unpack(context, zip, driver.id, driver.label)
        } finally {
            zip.delete()
        }
    }

    /** adrenotools reads the library beside meta.json, so the zip is flattened. */
    private fun unpack(context: Context, zip: File, id: String, label: String) {
        val dir = File(root(context), id)
        val staging = File(root(context), ".$id.part")
        FileUtils.delete(staging)
        staging.mkdirs()
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val base = e.name.substringAfterLast('/')
                if (base.isEmpty() || base.startsWith(".")) continue
                z.getInputStream(e).use { input -> File(staging, base).outputStream().use { input.copyTo(it) } }
            }
        }
        val meta = runCatching { JSONObject(File(staging, "meta.json").readText()) }.getOrNull() ?: error("no meta.json: not an AdrenoTools driver")
        val library = meta.optString("libraryName").takeIf { File(staging, it).isFile } ?: error("meta.json names no library in the zip")
        check(!Hashes.containsAscii(File(staging, library), "libc.so.6")) { "this is a Linux driver, not an Android one" }
        if (meta.optString("name").isBlank()) meta.put("name", label)
        FileUtils.writeString(File(staging, "meta.json"), meta.toString())
        FileUtils.delete(dir)
        if (!staging.renameTo(dir)) error("could not keep the driver")
    }

    fun remove(context: Context, id: String) {
        require(id.isNotEmpty() && !id.contains('/') && id !in BUNDLED_FAMILIES) { "not a removable driver: $id" }
        if (Settings.prefs(context).getString(SELECTED, BUNDLED) == id) select(context, BUNDLED)
        FileUtils.delete(File(root(context), id))
    }

    private fun libraryOf(dir: File): String? =
        runCatching { JSONObject(File(dir, "meta.json").readText()).optString("libraryName") }.getOrNull()
            ?.takeIf { it.isNotEmpty() && File(dir, it).isFile }
}
