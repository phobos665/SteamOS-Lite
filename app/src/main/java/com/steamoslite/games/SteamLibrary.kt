package com.steamoslite.games

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.runtime.Session
import com.steamoslite.util.SteamFiles
import java.io.File

data class InstalledGame(val appId: String, val name: String, val cover: File?)

/** An owned Steam game that is not (fully) installed, and how far its download is when it has begun. */
data class UninstalledGame(val appId: String, val name: String, val cover: File?, val download: Download?) {
    data class Download(val done: Long, val total: Long) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    fun asGame() = InstalledGame(appId, name, cover)
}

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
        // Internal storage, and the SD card's library when there is one (see Session.sdLibrary).
        val libraries = listOfNotNull(File(root, "steamapps"), Session.sdLibrary(context)?.let { File(it, "steamapps") })
        val manifests = libraries.flatMap { dir ->
            dir.listFiles { f -> f.name.matches(Regex("appmanifest_\\d+\\.acf")) }.orEmpty().asList()
        }
        return manifests.mapNotNull { parse(it) }
            .distinctBy { it.first }
            .filter { (appId, name, flags) ->
                appId !in NOT_GAMES && TOOL_PREFIXES.none { name.startsWith(it) } &&
                    (flags and STATE_FULLY_INSTALLED) != 0
            }
            .map { (appId, name) -> InstalledGame(appId, name, cover(root, appId)) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Owned games that are not installed: those the client is part-way through downloading (their
     * manifest is there but not fully installed), then every other game the client keeps library
     * art for. The client has no file listing licenses, so ownership is inferred from the art it
     * caches for the library, filtered by appinfo.vdf to games and demos.
     */
    fun uninstalledGames(context: Context, installed: Collection<String>): List<UninstalledGame> {
        val root = steamRoot(context)
        val installedIds = installed.toSet()
        val pending = downloads(context).filter { it.appId !in installedIds }
        val known = installedIds + pending.map { it.appId } + NOT_GAMES
        val cached = File(root, "appcache/librarycache").listFiles().orEmpty()
            .mapNotNull { f -> Regex("^(\\d+)(_.*)?").find(f.name)?.groupValues?.get(1) }
            .filter { it !in known }
            .toSet()
        val kinds = SteamFiles.appKinds(File(root, "appcache/appinfo.vdf"), cached.mapNotNull { it.toLongOrNull() }.toSet())
        val owned = kinds.filter { (_, kind) -> kind.type == "game" || kind.type == "demo" }
            .map { (id, kind) -> UninstalledGame(id.toString(), kind.name, cover(root, id.toString()), null) }
            .sortedBy { it.name.lowercase() }
        return pending + owned
    }

    /** Whether the client has [appId] fully installed. */
    fun isInstalled(context: Context, appId: String) = installedGames(context).any { it.appId == appId }

    /** Games the client has a manifest for but has not finished installing: queued, downloading or updating. */
    fun downloads(context: Context): List<UninstalledGame> {
        val root = steamRoot(context)
        val libraries = listOfNotNull(File(root, "steamapps"), Session.sdLibrary(context)?.let { File(it, "steamapps") })
        return libraries.flatMap { dir ->
            dir.listFiles { f -> f.name.matches(Regex("appmanifest_\\d+\\.acf")) }.orEmpty().asList()
        }.mapNotNull { manifest ->
            val text = runCatching { manifest.readText() }.getOrNull() ?: return@mapNotNull null
            fun field(key: String) = Regex("\"$key\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val appId = field("appid") ?: return@mapNotNull null
            val flags = field("StateFlags")?.toIntOrNull() ?: 0
            val name = field("name")?.takeIf { it.isNotBlank() } ?: "App $appId"
            if (appId in NOT_GAMES || TOOL_PREFIXES.any { name.startsWith(it) } ||
                (flags and STATE_FULLY_INSTALLED) != 0) return@mapNotNull null
            val download = UninstalledGame.Download(
                field("BytesDownloaded")?.toLongOrNull() ?: 0L,
                field("BytesToDownload")?.toLongOrNull() ?: 0L,
            )
            UninstalledGame(appId, name, cover(root, appId), download)
        }.distinctBy { it.appId }.sortedBy { it.name.lowercase() }
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
    private fun cover(root: File, appId: String) = art(root, appId, listOf("library_600x900", "library_capsule", "header"))

    /** The wide banner the client shows at the top of a game's page, if it has cached one. */
    fun hero(context: Context, appId: String) = art(steamRoot(context), appId, listOf("library_hero", "header"))

    private fun art(root: File, appId: String, preferred: List<String>): File? {
        val cache = File(root, "appcache/librarycache")
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
