package com.steamoslite.stores

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.runtime.SteamShortcuts
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/**
 * Installed GOG and Epic games as non-Steam shortcuts in the Steam library. The session writes
 * them into Steam before the client starts and maps them to our Proton (see SteamShortcuts);
 * Epic games go through bl-store-launch, which fetches their per-launch sign-in arguments.
 */
object StoreShortcuts {
    fun key(store: Store, id: String) = "${store.id}:$id"

    /** The id bannerlator-steam-shortcuts gives a shortcut: CRC32 of "bl:<key>", top bit set. */
    fun shortId(key: String): Long = CRC32().apply { update("bl:$key".toByteArray()) }.value or 0x80000000L

    /** What steam://rungameid/ takes to start the shortcut. */
    fun launchId(store: Store, id: String): String {
        val short = shortId(key(store, id))
        return ((short shl 32) or 0x02000000L).toULong().toString()
    }

    /** Rewrites the store games' shortcuts from what is installed now, keeping any others. */
    fun sync(context: Context) {
        val entries = Store.entries.flatMap { store ->
            val library = StoreLibrary(context, store)
            library.installed().mapNotNull { (id, installation) ->
                val game = library.game(id) ?: return@mapNotNull null
                entry(context, game, installation)
            }
        }
        SteamShortcuts.setStoreEntries(context, entries)
    }

    private fun entry(context: Context, game: StoreGame, inst: Installation): SteamShortcuts.Entry {
        val exe = "${inst.guestDir}/${inst.exe}"
        val startDir = if (inst.workingDir.isNotEmpty()) "${inst.guestDir}/${inst.workingDir}" else exe.substringBeforeLast('/')
        val args = inst.args.trim()
        val launch = when (game.store) {
            Store.GOG -> if (args.isEmpty()) "" else "%command% $args"
            Store.EPIC -> "/usr/local/bin/bl-store-launch epic ${game.id} %command%" + if (args.isEmpty()) "" else " $args"
        }
        val k = key(game.store, game.id)
        return SteamShortcuts.Entry(
            key = k,
            name = game.title,
            exe = exe,
            startDir = startDir,
            launchOptions = launch,
            tags = listOf(game.store.label),
            portrait = cacheArt(context, k, "p", game.coverUrl),
            hero = cacheArt(context, k, "_hero", game.heroUrl),
        )
    }

    /** The image at [url] saved in the runtime for the shortcut's library art, as a guest path. */
    private fun cacheArt(context: Context, key: String, suffix: String, url: String): String? {
        if (url.isEmpty()) return null
        val name = key.sanitizeForFilename() + suffix + ".jpg"
        val file = File(LinuxRuntime.rootDir(context), "root/.bl-art/$name")
        if (!file.isFile) {
            runCatching {
                file.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                try {
                    if (conn.responseCode == 200) conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
            }
        }
        return if (file.isFile && file.length() > 0) "/root/.bl-art/$name" else null
    }
}
