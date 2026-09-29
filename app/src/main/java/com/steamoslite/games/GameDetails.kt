package com.steamoslite.games

import android.content.Context
import com.steamoslite.util.Downloader
import com.steamoslite.util.SteamFiles
import org.json.JSONObject
import java.io.File

/** Everything the details page shows for one game. */
data class GameDetails(
    val game: InstalledGame,
    /** The wide library hero image the client cached, or null. */
    val hero: File?,
    val playtime: SteamFiles.Playtime?,
    val info: SteamFiles.AppInfo?,
    /** Null when the client has no achievement data for the game (never run, or it has none). */
    val achievements: List<SteamFiles.Achievement>?,
)

/** What Steam's store says about a game: the part the client does not keep on disk. */
data class StoreDetails(
    val description: String,
    val genres: List<String>,
    val screenshots: List<String>,
)

/**
 * Reads a game's details out of the runtime's Steam client: its app metadata (appinfo.vdf), the
 * account's playtime (localconfig.vdf) and achievements (appcache/stats), and cached artwork. All
 * of it is what the client last wrote, i.e. as of the last SteamOS session.
 */
object GameDetailsReader {
    fun read(context: Context, game: InstalledGame): GameDetails {
        val root = SteamLibrary.steamRoot(context)
        val accountDir = accountDir(root)
        val playtime = accountDir?.let { SteamFiles.playtime(File(it, "config/localconfig.vdf"), game.appId) }
        val info = game.appId.toLongOrNull()?.let { SteamFiles.appInfo(File(root, "appcache/appinfo.vdf"), it) }
        return GameDetails(game, SteamLibrary.hero(context, game.appId), playtime, info, achievements(root, accountDir, game.appId))
    }

    /** Just the achievements, as the client last wrote them; null when it has none for [appId]. */
    fun achievements(context: Context, appId: String): List<SteamFiles.Achievement>? {
        val root = SteamLibrary.steamRoot(context)
        return achievements(root, accountDir(root), appId)
    }

    private fun accountDir(root: File): File? {
        val account = SteamFiles.account(File(root, "config/loginusers.vdf"))
        return account?.let { File(root, "userdata/${it.accountId}") }?.takeIf { it.isDirectory }
            ?: File(root, "userdata").listFiles()?.firstOrNull { it.isDirectory && it.name.toLongOrNull() != null }
    }

    private fun achievements(root: File, accountDir: File?, appId: String): List<SteamFiles.Achievement>? = runCatching {
        val stats = File(root, "appcache/stats")
        val schema = File(stats, "UserGameStatsSchema_$appId.bin").takeIf { it.isFile } ?: return@runCatching null
        val progress = accountDir?.name?.let { File(stats, "UserGameStats_${it}_$appId.bin") }?.takeIf { it.isFile }
        SteamFiles.achievements(schema.readBytes(), progress?.readBytes())
    }.getOrNull()
}

/**
 * Store descriptions and screenshots from store.steampowered.com's appdetails (no key needed),
 * cached for a week in the app's files so the page works offline after one visit.
 */
object StoreDetailsCache {
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun cached(context: Context, appId: String): StoreDetails? = file(context, appId).takeIf { it.isFile }?.let { parse(appId, it.readText()) }

    /** Fresh from the store when the cache is missing or old; the cached copy when offline. */
    fun load(context: Context, appId: String): StoreDetails? {
        val f = file(context, appId)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return cached(context, appId)
        val body = Downloader.downloadString("https://store.steampowered.com/api/appdetails?appids=$appId&l=english")
        val details = body?.let { parse(appId, it) }
        if (details != null) {
            f.parentFile?.mkdirs()
            f.writeText(body)
            return details
        }
        return cached(context, appId)
    }

    private fun file(context: Context, appId: String) = File(context.filesDir, "store/$appId.json")

    private fun parse(appId: String, body: String): StoreDetails? = runCatching {
        val entry = JSONObject(body).optJSONObject(appId) ?: return null
        if (!entry.optBoolean("success")) return null
        val data = entry.getJSONObject("data")
        val genres = data.optJSONArray("genres")
        val shots = data.optJSONArray("screenshots")
        StoreDetails(
            description = htmlToText(data.optString("short_description")),
            genres = (0 until (genres?.length() ?: 0)).mapNotNull { genres!!.getJSONObject(it).optString("description").ifEmpty { null } },
            screenshots = (0 until (shots?.length() ?: 0)).mapNotNull { shots!!.getJSONObject(it).optString("path_thumbnail").ifEmpty { null } },
        )
    }.getOrNull()

    private fun htmlToText(html: String) =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
}
