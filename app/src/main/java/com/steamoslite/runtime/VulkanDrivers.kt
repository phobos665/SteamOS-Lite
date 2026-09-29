package com.steamoslite.runtime

import android.content.Context
import android.os.Build
import com.steamoslite.util.Hashes
import com.steamoslite.util.FileUtils
import com.steamoslite.util.ResumableDownload
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Turnip builds for the Linux runtime, which Steam, gamescope and every game draw with instead of
 * the Turnip the runtime ships: glibc builds, from this project's "Turnip (Linux)" releases,
 * Banners-Turnip and WinNative. The Android builds adrenotools loads cannot run in the session.
 *
 * Each lives in files/linux_vulkan_drivers/<id>/ with its ICD manifest; the files dir is bound
 * into the session at its own path, so the manifest's absolute library_path holds on both sides.
 */
object VulkanDrivers {
    private const val SELECTED = "vkDriver"

    /** The runtime's own Turnip, as a choice. */
    const val RUNTIME = ""

    enum class Family(val variant: String, val gpus: String) {
        A6XX("a6xx", "Adreno 6xx"),
        A7XX("a7xx", "Adreno 7xx · Snapdragon 8 Gen 1-3"),
        A8XX("a8xx", "Adreno 8xx · Snapdragon 8 Elite"),
    }

    data class Installed(val id: String, val name: String, val variant: String, val vulkanVersion: String, val icd: File)

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

    /**
     * When a session had to fall back from the chosen driver (it crashed the Steam client as it
     * started), the choice goes back to the runtime's own. Returns that driver's name, once.
     */
    fun takeFailure(context: Context): String? {
        val note = File(LinuxRuntime.rootDir(context), "root/.bl-driver-failed")
        val icd = runCatching { note.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        note.delete()
        val dir = File(icd).parentFile ?: return null
        if (selected(context) == dir.name) select(context, RUNTIME)
        return installed(context).firstOrNull { it.id == dir.name }?.name ?: dir.name
    }

    /** The manifest the session is told to use (BL_VK_DRIVER), or null for the runtime's own. */
    fun selectedIcd(context: Context): File? = icd(context, selected(context))

    /** An installed driver's manifest; [RUNTIME] is the one in the rootfs. */
    fun icd(context: Context, id: String): File? =
        if (id == RUNTIME) LinuxRuntime.vulkanIcd(context) else File(root(context), "$id/icd.json").takeIf { it.isFile }

    /** The Linux builds on offer, from every source, or empty when GitHub cannot be reached. */
    fun catalog(): List<TurnipReleases.Asset> = TurnipReleases.fetch().filter { it.linux }

    /** Downloads and installs [driver]; [onProgress] gets the fraction done. */
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

    /**
     * Keeps the driver library and writes the ICD manifest. Builds differ in what else they carry,
     * so only the library is required, and it must be a glibc build: an Android one (bionic) would
     * leave the session without Vulkan.
     */
    private fun unpack(context: Context, zip: File, id: String, label: String) {
        val dir = File(root(context), id)
        val staging = File(root(context), ".$id.part")
        FileUtils.delete(staging)
        staging.mkdirs()
        var meta = JSONObject()
        ZipFile(zip).use { z ->
            val lib = z.entries().asSequence().firstOrNull {
                val base = it.name.substringAfterLast('/')
                !it.isDirectory && base.startsWith("libvulkan_freedreno") && base.endsWith(".so")
            } ?: error("no libvulkan_freedreno library in the driver")
            z.getInputStream(lib).use { input -> File(staging, LIBRARY).outputStream().use { input.copyTo(it) } }
            z.entries().asSequence().firstOrNull { it.name.substringAfterLast('/') == "meta.json" }?.let { e ->
                meta = runCatching { JSONObject(z.getInputStream(e).bufferedReader().readText()) }.getOrDefault(JSONObject())
            }
        }
        check(Hashes.containsAscii(File(staging, LIBRARY), "libc.so.6")) { "this is an Android driver, not a Linux one" }
        if (!meta.has("name")) meta.put("name", label)
        meta.put("kind", "linux-vulkan-icd")
        FileUtils.writeString(File(staging, "meta.json"), meta.toString())
        val vk = meta.optString("vulkanVersion").takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "1.3.0"
        val lib = File(dir, LIBRARY).path
        FileUtils.writeString(
            File(staging, "icd.json"),
            """{"file_format_version":"1.0.1","ICD":{"library_path":"$lib","api_version":"$vk"}}""" + "\n",
        )
        FileUtils.delete(dir)
        if (!staging.renameTo(dir)) error("could not keep the driver")
    }

    private const val LIBRARY = "libvulkan_freedreno.so"


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
