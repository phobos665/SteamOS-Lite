package com.steamoslite.games

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import java.io.File

data class InstalledGame(val appId: String, val name: String, val cover: File?)

/**
 * The games the runtime's Steam client has installed, read straight from its own files - no login,
 * no Steam API. Each installed app has an appmanifest_<id>.acf in steamapps/, and the client caches
 * the library artwork it shows under appcache/librarycache/.
 */
object SteamLibrary {
    private const val STEAM_ROOT = "root/.local/share/Steam"
    /** StateFlags bit 4: fully installed (not queued, downloading or half-updated). */
    private const val STATE_FULLY_INSTALLED = 4

    /** Things the client installs that are not games. */
    private val NOT_GAMES = setOf(
        "228980",  // Steamworks Common Redistributables
        "4427310", // Proton (ARM64)
        "4185400", // Steam Linux Runtime 4 (ARM64)
        "1070560", "1391110", "1628350", // Steam Linux Runtime 1-3
    )
    private val TOOL_PREFIXES = listOf("Proton", "Steam Linux Runtime", "Steamworks")

    fun steamRoot(context: Context) = File(LinuxRuntime.rootDir(context), STEAM_ROOT)

    fun installedGames(context: Context): List<InstalledGame> {
        val root = steamRoot(context)
        val manifests = File(root, "steamapps").listFiles { f -> f.name.matches(Regex("appmanifest_\\d+\\.acf")) }
            ?: return emptyList()
        return manifests.mapNotNull { parse(it) }
            .filter { (appId, name, flags) ->
                appId !in NOT_GAMES && TOOL_PREFIXES.none { name.startsWith(it) } &&
                    flags and STATE_FULLY_INSTALLED != 0
            }
            .map { (appId, name) -> InstalledGame(appId, name, cover(root, appId)) }
            .sortedBy { it.name.lowercase() }
    }

    /** (appid, name, StateFlags) from a manifest, or null when it cannot be read. */
    private fun parse(manifest: File): Triple<String, String, Int>? {
        val text = try { manifest.readText() } catch (e: Exception) { return null }
        fun field(key: String) = Regex("\"$key\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val appId = field("appid") ?: return null
        val name = field("name")?.takeIf { it.isNotBlank() } ?: "App $appId"
        val flags = field("StateFlags")?.toIntOrNull() ?: 0
        return Triple(appId, name, flags)
    }

    /**
     * The portrait capsule the client itself shows, if it has cached one. Current clients keep a
     * directory per app (sometimes with the image one level further down); older ones used flat
     * <appid>_library_600x900.jpg names.
     */
    private fun cover(root: File, appId: String): File? {
        val cache = File(root, "appcache/librarycache")
        val preferred = listOf("library_600x900", "library_capsule", "header")
        val perApp = File(cache, appId)
        if (perApp.isDirectory) {
            val files = perApp.walkTopDown().maxDepth(2).filter { it.isFile }.toList()
            for (want in preferred) files.firstOrNull { it.name.startsWith(want) }?.let { return it }
        }
        for (want in preferred) {
            File(cache, "${appId}_$want.jpg").takeIf { it.isFile }?.let { return it }
        }
        return null
    }
}
