package com.steamoslite.runtime

import android.content.Context
import com.steamoslite.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The non-Steam shortcuts the app puts in the Steam library. The app only lists them in the
 * runtime's ~/.bl-shortcuts.json; the session's bannerlator-steam-shortcuts writes them into the
 * client's shortcuts.vdf before the client starts, and bannerlator-steam-compat maps them to our
 * Proton. They are the installed GOG and Epic games (StoreShortcuts), and the shortcut test,
 * which checks that path on a device.
 */
object SteamShortcuts {
    private const val TEST_KEY = "test-notepad"

    /** One shortcut; [portrait] and [hero] are guest paths of images for its library art. */
    data class Entry(
        val key: String,
        val name: String,
        val exe: String,
        val startDir: String,
        val launchOptions: String,
        val tags: List<String>,
        val portrait: String? = null,
        val hero: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key).put("name", name).put("exe", exe).put("startDir", startDir)
            .put("launchOptions", launchOptions).put("tags", JSONArray(tags))
            .put("art", JSONObject().apply {
                portrait?.let { put("p", it) }
                hero?.let { put("hero", it) }
            })
    }

    private val STORE_KEY = Regex("^(gog|epic):")

    /** Replaces the GOG and Epic shortcuts with [entries], leaving the rest (the test) alone. */
    fun setStoreEntries(context: Context, entries: List<Entry>) {
        val list = entries(context)
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            if (!STORE_KEY.containsMatchIn(e.optString("key"))) kept.put(e)
        }
        entries.forEach { kept.put(it.toJson()) }
        write(context, kept)
    }

    private fun file(context: Context) = File(LinuxRuntime.rootDir(context), "root/.bl-shortcuts.json")

    private fun entries(context: Context): JSONArray =
        runCatching { JSONObject(FileUtils.readString(file(context))!!).getJSONArray("shortcuts") }.getOrDefault(JSONArray())

    fun testEnabled(context: Context): Boolean {
        val list = entries(context)
        return (0 until list.length()).any { list.getJSONObject(it).optString("key") == TEST_KEY }
    }

    /**
     * The test: Notepad from Valve's ARM64 Proton as a non-Steam game, launched through
     * bl-store-launch's probe, which records in the session's logs how Steam started it.
     */
    fun setTest(context: Context, on: Boolean) {
        val list = entries(context)
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            if (e.optString("key") != TEST_KEY) kept.put(e)
        }
        if (on) {
            kept.put(
                JSONObject()
                    .put("key", TEST_KEY)
                    .put("name", "SteamOS Lite shortcut test")
                    .put("exe", "@proton:notepad.exe")
                    .put("launchOptions", "/usr/local/bin/bl-store-launch probe %command%")
                    .put("tags", JSONArray().put("SteamOS Lite")),
            )
        }
        write(context, kept)
    }

    private fun write(context: Context, list: JSONArray) {
        val f = file(context)
        f.parentFile?.mkdirs()
        FileUtils.writeString(f, JSONObject().put("shortcuts", list).toString(2))
    }
}
