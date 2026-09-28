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
 * Proton. For now the only one is the shortcut test, which checks that path on a device before
 * GOG and Epic games are sent down it.
 */
object SteamShortcuts {
    private const val TEST_KEY = "test-notepad"

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
        val f = file(context)
        f.parentFile?.mkdirs()
        FileUtils.writeString(f, JSONObject().put("shortcuts", kept).toString(2))
    }
}
