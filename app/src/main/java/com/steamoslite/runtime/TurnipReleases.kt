package com.steamoslite.runtime

import com.steamoslite.util.Downloader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turnip builds published on GitHub: this project's Linux builds, and Banners-Turnip's and
 * WinNative's, which carry both Linux (glibc, for the runtime) and Android (AdrenoTools, for the
 * app's compositor) zips. Each variant is offered from the newest release that carries it, and
 * only assets GitHub has a sha256 digest for.
 */
object TurnipReleases {
    data class Asset(
        val source: String,
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
        /** A glibc build for the runtime; otherwise an Android one for the compositor. */
        val linux: Boolean,
        val label: String,
        /** The GPU families it is built for; empty for "any Adreno". */
        val families: Set<VulkanDrivers.Family>,
    ) {
        val id get() = name.removeSuffix(".zip")
    }

    private class Source(val label: String, val repo: String, val classify: (name: String, tag: String) -> Triple<Boolean, String, Set<VulkanDrivers.Family>>?)

    private val SOURCES = listOf(
        // Turnip-<version>-<commit>-<a6xx|a7xx|a8xx>-Linux.zip, from the turnip-linux-* releases.
        Source("SteamOS Lite", "phobos665/SteamOS-Lite") { name, tag ->
            if (!tag.startsWith("turnip-linux-")) return@Source null
            val m = Regex("""^Turnip-(.+?)-[0-9a-f]{7,}-(a[678]xx)-Linux\.zip$""").matchEntire(name) ?: return@Source null
            val family = VulkanDrivers.Family.entries.first { it.variant == m.groupValues[2] }
            Triple(true, "Turnip ${m.groupValues[1].substringBefore('-')} ${family.variant}", setOf(family))
        },
        // Turnip-<tag>[-variant][-Linux|-Wayland].zip; -Wayland is for a path this app does not have.
        Source("Banners-Turnip", "The412Banner/Banners-Turnip") { name, tag ->
            val prefix = "Turnip-$tag"
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) return@Source null
            var variant = name.removePrefix(prefix).removeSuffix(".zip")
            if (variant.endsWith("-Wayland")) return@Source null
            val linux = variant.endsWith("-Linux")
            variant = variant.removeSuffix("-Linux")
            val (what, families) = when {
                variant.isEmpty() -> "6xx/7xx" to setOf(VulkanDrivers.Family.A6XX, VulkanDrivers.Family.A7XX)
                variant == "-A8xx" -> "8xx" to setOf(VulkanDrivers.Family.A8XX)
                else -> variant.trimStart('-') to emptySet()
            }
            Triple(linux, "Banners $tag · $what", families)
        },
        // WN-Turnip-<ver>-<b|p>_Axxx.zip (Android) and WN-Linux-Turnip-<ver>-<b|p>_Axxx.zip (Linux).
        Source("WinNative", "WinNative-Emu/Drivers") { name, _ ->
            val m = Regex("""^WN-(Linux-)?Turnip-([^-]+)-([a-z]+)_(\w+)\.zip$""").find(name) ?: return@Source null
            val flavour = when (m.groupValues[3]) { "b" -> "Balanced"; "p" -> "Performance"; else -> m.groupValues[3] }
            Triple(m.groupValues[1].isNotEmpty(), "WinNative ${m.groupValues[2]} · $flavour", emptySet())
        },
    )

    /** Every source's current builds; a source that cannot be reached is left out. */
    fun fetch(): List<Asset> = SOURCES.flatMap { src ->
        val body = Downloader.downloadString("https://api.github.com/repos/${src.repo}/releases?per_page=15") ?: return@flatMap emptyList()
        runCatching {
            val releases = JSONArray(body)
            val seen = HashSet<String>()
            (0 until releases.length()).map { releases.getJSONObject(it) }.filter { !it.optBoolean("draft") }.flatMap { r ->
                val tag = r.optString("tag_name")
                val assets = r.optJSONArray("assets") ?: JSONArray()
                (0 until assets.length()).map { assets.getJSONObject(it) }.mapNotNull { a ->
                    val name = a.optString("name")
                    val (linux, label, families) = src.classify(name, tag) ?: return@mapNotNull null
                    val sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:") ?: return@mapNotNull null
                    // Newest first: the first release carrying a variant is the one offered.
                    if (!seen.add("$linux|${label.substringAfter(" · ", "")}|$families")) return@mapNotNull null
                    Asset(src.label, name, a.optString("browser_download_url"), a.optLong("size"), sha, linux, label, families)
                }
            }
        }.getOrDefault(emptyList())
    }
}
