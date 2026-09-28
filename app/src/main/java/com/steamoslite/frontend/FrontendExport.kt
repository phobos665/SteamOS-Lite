package com.steamoslite.frontend

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.steamoslite.games.InstalledGame
import com.steamoslite.games.SteamLibrary
import com.steamoslite.runtime.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shortcuts that let a frontend (Daijishō, Cocoon, ES-DE) list the installed Steam games and start
 * one straight into SteamOS, as GameNative's frontend export does.
 *
 * Each installed game becomes "<title>.steamos" in a folder the user picks, holding just its app id.
 * Beside them go a Daijishō platform file (Cocoon imports the same format) whose player hands the
 * shortcut's path to FrontendLaunchActivity, and a README with the ES-DE and plain-intent forms.
 * The folder is refreshed whenever the app's library is read, so installs and uninstalls follow.
 */
object FrontendExport {
    const val EXTENSION = "steamos"
    const val ACTION_LAUNCH = "com.steamoslite.LAUNCH_GAME"
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_PATH = "path"
    private const val PREF_DIR = "frontendDir"
    private const val PLATFORM_FILE = "SteamOS Lite (Daijisho, Cocoon).json"
    private const val README_FILE = "SteamOS Lite frontends README.txt"
    private const val TAG = "FrontendExport"
    private val INVALID = Regex("""[\\/:*?"<>|]""")

    /** Where shortcuts are kept, or null when exporting is off. */
    fun dir(context: Context): File? = Settings.prefs(context).getString(PREF_DIR, null)?.let(::File)

    fun setDir(context: Context, path: String?) {
        val old = dir(context)
        Settings.prefs(context).edit().apply { if (path == null) remove(PREF_DIR) else putString(PREF_DIR, path) }.commit()
        // Stopping (or moving) takes our shortcuts away with it; nothing else in the folder is touched.
        if (old != null && old.path != path) clear(old)
    }

    /** A sensible first folder: ROMs/steamos, where frontends usually look. */
    fun suggestedDir(): File = File(Environment.getExternalStorageDirectory(), "ROMs/steamos")

    /**
     * Writes a shortcut for every installed game and removes those of games that are gone. Returns
     * how many there are, or null when exporting is off or the folder cannot be written.
     */
    fun sync(context: Context, games: List<InstalledGame> = SteamLibrary.installedGames(context)): Int? {
        val dir = dir(context) ?: return null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) error("cannot create $dir")
            val wanted = games.associateBy { fileName(it) }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".$EXTENSION") && it.name !in wanted }
                ?.forEach { it.delete() }
            for ((name, game) in wanted) {
                val file = File(dir, name)
                if (file.isFile && file.readText().trim() == game.appId) continue
                file.writeText(game.appId)
            }
            File(dir, PLATFORM_FILE).writeText(platformJson().toString(4))
            File(dir, README_FILE).writeText(readme(dir))
            wanted.size
        } catch (e: Exception) {
            Log.w(TAG, "could not export frontend shortcuts to $dir", e)
            null
        }
    }

    private fun clear(dir: File) {
        dir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".$EXTENSION") || it.name == PLATFORM_FILE || it.name == README_FILE)
        }?.forEach { it.delete() }
    }

    private fun fileName(game: InstalledGame) =
        game.name.replace(INVALID, "_").trim().ifEmpty { "App ${game.appId}" } + ".$EXTENSION"

    /**
     * Daijishō's platform file: one platform, "SteamOS Lite", taking *.steamos, with a player that
     * starts FrontendLaunchActivity and passes the shortcut's path. Cocoon imports the same file.
     */
    fun platformJson(): JSONObject {
        val regex = "^(.*)\\.(?:$EXTENSION)$"
        return JSONObject()
            .put("databaseVersion", 11)
            .put("revisionNumber", 1)
            .put(
                "platform",
                JSONObject()
                    .put("name", "SteamOS Lite")
                    .put("uniqueId", "steamoslite")
                    .put("shortname", "steamos")
                    .put("acceptedFilenameRegex", regex)
                    .put("scraperSourceList", JSONArray().put("TGDB:1").put("IGDB:6"))
                    .put("boxArtAspectRatioId", 0)
                    .put("useCustomBoxArtAspectRatio", false)
                    .put("customBoxArtAspectRatio", JSONObject.NULL)
                    .put("screenAspectRatioId", 1)
                    .put("boxArtScaleType", 0)
                    .put("extra", ""),
            )
            .put(
                "playerList",
                JSONArray().put(
                    JSONObject()
                        .put("name", "SteamOS Lite")
                        .put("description", "Starts the game in SteamOS (Steam Big Picture) on SteamOS Lite.")
                        .put("acceptedFilenameRegex", regex)
                        .put("amStartArguments", amStartArguments())
                        .put("killPackageProcesses", false)
                        .put("killPackageProcessesWarning", false)
                        .put("extra", ""),
                ),
            )
    }

    /** The launch, one `am start` argument per line as Daijishō writes them. */
    fun amStartArguments() = listOf(
        "-n com.steamoslite/.frontend.FrontendLaunchActivity",
        "-a $ACTION_LAUNCH",
        "-e $EXTRA_PATH {file.path}",
    ).joinToString("\n")

    private fun readme(dir: File) = """
        SteamOS Lite - frontend shortcuts
        =================================

        Each *.$EXTENSION file here is one installed Steam game; it holds the game's Steam app id.
        SteamOS Lite rewrites this folder whenever it reads its library, so new games appear and
        uninstalled ones go.

        Daijisho / Cocoon
          Import "$PLATFORM_FILE" as a platform, then point the platform at this folder:
            $dir

        ES-DE (custom system, es_systems.xml)
          <extension>.$EXTENSION</extension>
          <command label="SteamOS Lite">%EMULATOR_STEAMOS-LITE% %ACTION%=$ACTION_LAUNCH %EXTRA_$EXTRA_PATH%=%ROM%</command>
          with the emulator entry (es_find_rules.xml) pointing at com.steamoslite/.frontend.FrontendLaunchActivity

        Any launcher that can send an intent
          action $ACTION_LAUNCH, component com.steamoslite/.frontend.FrontendLaunchActivity, and either
            extra "$EXTRA_PATH" = the shortcut file's path, or
            extra "$EXTRA_APP_ID" = the Steam app id (string or number)
          or open the link  steamoslite://run?appid=<app id>
    """.trimIndent() + "\n"

    /**
     * The Steam app id a launch intent names, in any of the forms above: an app_id extra, a path
     * extra or data URI naming a shortcut file, or steamoslite://run?appid=<id> (and, sent to us
     * directly, steam://rungameid/<id>).
     */
    fun appIdOf(context: Context, intent: Intent): String? {
        val extras = intent.extras
        extras?.get(EXTRA_APP_ID)?.toString()?.let(::digits)?.let { return it }
        extras?.get("appid")?.toString()?.let(::digits)?.let { return it }
        extras?.getString(EXTRA_PATH)?.let { path -> readShortcut(context, Uri.fromFile(File(path)))?.let { return it } }
        val data = intent.data ?: return null
        when (data.scheme) {
            "steamoslite" -> return data.getQueryParameter("appid")?.let(::digits) ?: data.lastPathSegment?.let(::digits)
            "steam" -> return Regex("""rungameid/(\d+)""").find(data.toString())?.groupValues?.get(1)
            ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_CONTENT -> return readShortcut(context, data)
        }
        return null
    }

    private fun readShortcut(context: Context, uri: Uri): String? = try {
        val text = if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).readText()
        else context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        text?.let(::digits)
    } catch (e: Exception) {
        Log.w(TAG, "could not read the shortcut $uri", e)
        null
    }

    /** The first run of digits in [text], trimmed: "12345\n" and "appid=12345" both give 12345. */
    private fun digits(text: String): String? = Regex("""\d+""").find(text.trim())?.value

    /**
     * A folder picked with ACTION_OPEN_DOCUMENT_TREE as a plain path, which this app can write
     * directly (it targets API 28 and holds the storage permission). Null when it is not on a
     * volume that maps to a path.
     */
    fun pathOfTree(context: Context, tree: Uri): String? = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path
        else "/storage/$volume"
        if (rel.isEmpty()) root else "$root/$rel"
    } catch (e: Exception) {
        null
    }
}
