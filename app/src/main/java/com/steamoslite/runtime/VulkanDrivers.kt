package com.steamoslite.runtime

import android.content.Context
import android.os.Build
import com.steamoslite.util.Downloader
import com.steamoslite.util.FileUtils
import com.steamoslite.util.ResumableDownload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Turnip builds for the Linux runtime, which Steam, gamescope and every game draw with instead of
 * the Turnip the runtime ships. They are glibc builds from this project's "Turnip (Linux)"
 * releases; the Android builds emulators load through adrenotools cannot run in the session.
 *
 * Each lives in files/linux_vulkan_drivers/<id>/ with its ICD manifest; the files dir is bound
 * into the session at its own path, so the manifest's absolute library_path holds on both sides.
 */
object VulkanDrivers {
    private const val RELEASES_URL = "https://api.github.com/repos/phobos665/SteamOS-Lite/releases?per_page=20"
    private const val TAG_PREFIX = "turnip-linux-"
    private const val SELECTED = "vkDriver"
    private val ASSET = Regex("""^Turnip-(.+)-(a[678]xx)-Linux\.zip$""")

    /** The runtime's own Turnip, as a choice. */
    const val RUNTIME = ""

    enum class Family(val variant: String, val gpus: String) {
        A6XX("a6xx", "Adreno 6xx"),
        A7XX("a7xx", "Adreno 7xx · Snapdragon 8 Gen 1-3"),
        A8XX("a8xx", "Adreno 8xx · Snapdragon 8 Elite"),
    }

    data class Installed(val id: String, val name: String, val variant: String, val vulkanVersion: String, val icd: File)

    data class CatalogDriver(val id: String, val version: String, val variant: String, val url: String, val size: Long) {
        val family get() = Family.entries.firstOrNull { it.variant == variant }
    }

    /** What the device reports: its GPU's name where readable, and the Turnip family it needs. */
    data class Gpu(val name: String, val family: Family?)

    private fun root(context: Context) = File(context.filesDir, "linux_vulkan_drivers")

    fun installed(context: Context): List<Installed> =
        root(context).listFiles().orEmpty().mapNotNull { dir ->
            val icd = File(dir, "icd.json").takeIf { it.isFile } ?: return@mapNotNull null
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull() ?: JSONObject()
            Installed(dir.name, meta.optString("name", dir.name), meta.optString("variant"), meta.optString("vulkanVersion"), icd)
        }.sortedByDescending { it.id }

    /** The chosen driver's id, or [RUNTIME]. A driver removed since reads as the runtime's own. */
    fun selected(context: Context): String {
        val id = Settings.prefs(context).getString(SELECTED, RUNTIME)!!
        return if (id == RUNTIME || File(root(context), "$id/icd.json").isFile) id else RUNTIME
    }

    fun select(context: Context, id: String) {
        Settings.prefs(context).edit().putString(SELECTED, id).commit()
    }

    /** The manifest the session is told to use (BL_VK_DRIVER), or null for the runtime's own. */
    fun selectedIcd(context: Context): File? = icd(context, selected(context))

    /** An installed driver's manifest; [RUNTIME] is the one in the rootfs. */
    fun icd(context: Context, id: String): File? =
        if (id == RUNTIME) LinuxRuntime.vulkanIcd(context) else File(root(context), "$id/icd.json").takeIf { it.isFile }

    /** The newest release's builds, or empty when GitHub cannot be reached. */
    fun catalog(): List<CatalogDriver> {
        val body = Downloader.downloadString(RELEASES_URL) ?: return emptyList()
        return runCatching {
            val releases = JSONArray(body)
            val release = (0 until releases.length()).map { releases.getJSONObject(it) }
                .firstOrNull { it.optString("tag_name").startsWith(TAG_PREFIX) && !it.optBoolean("draft") }
                ?: return emptyList()
            val assets = release.getJSONArray("assets")
            (0 until assets.length()).mapNotNull { j ->
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                val m = ASSET.matchEntire(name) ?: return@mapNotNull null
                CatalogDriver(name.removeSuffix(".zip"), m.groupValues[1], m.groupValues[2], a.optString("browser_download_url"), a.optLong("size"))
            }.sortedBy { it.variant }
        }.getOrDefault(emptyList())
    }

    /** Downloads and installs [driver]; [onProgress] gets the fraction done. */
    fun install(context: Context, driver: CatalogDriver, onProgress: (Float) -> Unit) {
        val zip = File(context.cacheDir, driver.id + ".zip")
        val done = ResumableDownload(driver.url, zip, driver.size).run(object : ResumableDownload.Listener {
            override fun onProgress(done: Long, total: Long) {
                if (total > 0) onProgress(done.toFloat() / total)
            }

            override fun onRetry(attempt: Int, delayMs: Long, reason: String) {}
        }) { false }
        check(done) { "the download did not finish" }
        try {
            unpack(context, zip, driver.id)
        } finally {
            zip.delete()
        }
    }

    private fun unpack(context: Context, zip: File, id: String) {
        val dir = File(root(context), id)
        val staging = File(root(context), ".$id.part")
        FileUtils.delete(staging)
        staging.mkdirs()
        ZipFile(zip).use { z ->
            for (name in listOf("meta.json", "libvulkan_freedreno.so")) {
                val entry = z.getEntry(name) ?: error("$name is missing from the driver")
                z.getInputStream(entry).use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
            }
        }
        val meta = JSONObject(File(staging, "meta.json").readText())
        check(meta.optString("kind") == "linux-vulkan-icd") { "not a Linux Vulkan driver" }
        val vk = meta.optString("vulkanVersion").takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "1.3.0"
        val lib = File(dir, "libvulkan_freedreno.so").path
        FileUtils.writeString(
            File(staging, "icd.json"),
            """{"file_format_version":"1.0.1","ICD":{"library_path":"$lib","api_version":"$vk"}}""" + "\n",
        )
        FileUtils.delete(dir)
        if (!staging.renameTo(dir)) error("could not keep the driver")
    }

    /** Removes a driver; whatever used it (the global choice, a game's own) falls back to the global one. */
    fun remove(context: Context, id: String) {
        require(id.isNotEmpty() && !id.contains('/')) { "not a driver: $id" }
        if (Settings.prefs(context).getString(SELECTED, RUNTIME) == id) select(context, RUNTIME)
        for ((game, settings) in GameSettingsStore.all(context)) {
            if (settings.vkDriver == id) GameSettingsStore.set(context, game, settings.copy(vkDriver = null))
        }
        FileUtils.delete(File(root(context), id))
    }

    fun gpu(): Gpu {
        val model = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim() }.getOrNull().orEmpty()
        // "Adreno740v2", "Adreno830v1": the first digit is the generation.
        Regex("""Adreno\s*(\d)(\d\d)""", RegexOption.IGNORE_CASE).find(model)?.let { m ->
            return Gpu("Adreno ${m.groupValues[1]}${m.groupValues[2]}", family(m.groupValues[1].toInt()))
        }
        // Without the sysfs node: the SoC, by model number or codename.
        val soc = listOfNotNull(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null, Build.BOARD, Build.HARDWARE)
            .flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }.toSet()
        fun has(vararg names: String) = names.any { n -> soc.any { it == n || (n[0].isDigit() && it.contains(n)) } }
        return when {
            has("8750", "8735", "8850", "sun", "canoe") -> Gpu("Snapdragon 8 Elite", Family.A8XX)
            has("8650", "pineapple") -> Gpu("Snapdragon 8 Gen 3", Family.A7XX)
            has("8550", "kalama") -> Gpu("Snapdragon 8 Gen 2", Family.A7XX)
            has("8450", "8475", "taro") -> Gpu("Snapdragon 8 Gen 1", Family.A7XX)
            else -> Gpu(model.ifEmpty { "Unknown GPU" }, null)
        }
    }

    private fun family(generation: Int) = when (generation) {
        6 -> Family.A6XX
        7 -> Family.A7XX
        8 -> Family.A8XX
        else -> null
    }
}
