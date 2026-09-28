package com.steamoslite.runtime

import android.content.Context

/** Choices about the Steam client itself, read by the session when it starts. */
object SteamSettings {
    private const val UPDATES = "steamUpdates"

    /**
     * Whether the client checks for its own updates (and verifies its files) at every start. Off
     * saves that time, but Steam can eventually refuse an outdated client, so it stays the user's
     * call and defaults to on.
     */
    fun updates(context: Context) = prefs(context).getBoolean(UPDATES, true)

    fun setUpdates(context: Context, on: Boolean) {
        // commit, not apply: the session runs in its own process and reads the file when it starts.
        prefs(context).edit().putBoolean(UPDATES, on).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)
}
