package com.steamoslite.ui

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When each step of bringing SteamOS up happened, counted from the tap that asked for it, written
 * into the session's log folder as startup.txt: the app's own setup, the session script's
 * "== STEP" milestones (stamped in the guest), Steam's bootstrap log, and the interface appearing.
 */
internal class StartupTimeline(private val t0: Long) {
    private val marks = mutableListOf<Pair<Long, String>>()
    private var lastBootstrap = ""

    @Synchronized
    fun mark(what: String, at: Long = System.currentTimeMillis()) {
        marks += at to what
    }

    /** A session-script line: "== STEP 12:34:56.789 text", stamped in UTC. */
    fun step(stamp: String, text: String) = mark("STEP " + text, utcToday(stamp) ?: System.currentTimeMillis())

    /**
     * A bootstrap_log.txt line: "[2026-09-27 12:00:00] text", in local time. Runs of the same
     * message with different numbers (download progress) keep only their first line.
     */
    @Synchronized
    fun bootstrap(line: String) {
        val text = line.substringAfter("] ", "").trim().ifEmpty { return }
        val shape = text.replace(Regex("[0-9.,]+"), "#")
        if (shape == lastBootstrap) return
        lastBootstrap = shape
        val at = runCatching { BOOTSTRAP.parse(line.substring(1, 20))!!.time }.getOrNull() ?: System.currentTimeMillis()
        marks += at to "steam: $text"
    }

    @Synchronized
    fun write(dir: File?, name: String = "startup.txt") {
        dir ?: return
        val text = buildString {
            append("Seconds from the launch tap. STEP lines come from the session script, steam: lines from\n")
            append("Steam's bootstrap log (whole seconds only).\n\n")
            for ((at, what) in marks.sortedBy { it.first }) {
                append(String.format(Locale.US, "%7.1f  %s\n", (at - t0) / 1000.0, what))
            }
        }
        runCatching { File(dir, name).writeText(text) }
    }

    private fun utcToday(stamp: String): Long? {
        val parts = stamp.split(':', '.').mapNotNull { it.toLongOrNull() }
        if (parts.size < 3) return null
        val dayStart = System.currentTimeMillis() / DAY * DAY
        var at = dayStart + ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000 + (parts.getOrNull(3) ?: 0)
        if (at - t0 < -DAY / 2) at += DAY // the day rolled over since the tap
        return at
    }

    private companion object {
        const val DAY = 86_400_000L
        val BOOTSTRAP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
}
