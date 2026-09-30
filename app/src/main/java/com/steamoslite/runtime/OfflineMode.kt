package com.steamoslite.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Starting Steam without Valve's servers. The client can run offline - installed games, Proton and
 * everything local work - but only with credentials from an earlier sign-in, and two keys in
 * loginusers.vdf decide what it does then: WantsOfflineMode goes offline, SkipOfflineModeWarning
 * does so without asking. The client reads them once as it starts and rewrites the file as it
 * exits, so the choice is kept here and written back before every session.
 */
object OfflineMode {
    private const val TAG = "OfflineMode"
    private const val KEY = "steamOffline"
    private val KEYS = listOf("WantsOfflineMode", "SkipOfflineModeWarning")
    private val REMEMBERED = Regex(""""RememberPassword"\s*"1"""")
    private val PERSONA = Regex(""""PersonaName"\s*"([^"]*)"""")

    private fun loginUsers(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/loginusers.vdf")

    /** The remembered account offline mode would sign in as, or null when there is none. */
    fun account(context: Context): String? {
        val text = runCatching { loginUsers(context).readText() }.getOrNull() ?: return null
        if (!REMEMBERED.containsMatchIn(text)) return null
        return PERSONA.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "your account"
    }

    fun enabled(context: Context) = Settings.prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        Settings.prefs(context).edit().putBoolean(KEY, on).commit()
        apply(context)
    }

    /** Writes the choice into every account block; a file without one is left alone. */
    fun apply(context: Context) {
        val file = loginUsers(context)
        if (!file.isFile) return
        val want = if (enabled(context)) "1" else "0"
        runCatching {
            val out = mutableListOf<String>()
            var seen = mutableSetOf<String>()
            var indent = ""
            for (line in file.readLines()) {
                val key = KEYS.firstOrNull { Regex("""^\s*"$it"\s""").containsMatchIn(line) }
                when {
                    key != null -> {
                        seen += key
                        out += line.replace(Regex(""""$key"(\s*)"[01]""""), "\"$key\"$1\"$want\"")
                    }
                    line.contains("\"AccountName\"") -> {
                        indent = line.takeWhile { it.isWhitespace() }
                        seen = mutableSetOf()
                        out += line
                    }
                    line.trim() == "}" && indent.isNotEmpty() && line.takeWhile { it.isWhitespace() }.length < indent.length -> {
                        // The end of an account block: add the keys it did not carry.
                        (KEYS - seen).forEach { out += "$indent\"$it\"\t\t\"$want\"" }
                        indent = ""
                        out += line
                    }
                    else -> out += line
                }
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(out.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) error("could not replace ${file.name}")
        }.onFailure { Log.w(TAG, "could not write loginusers.vdf", it) }
    }
}
