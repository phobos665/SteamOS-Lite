package com.steamoslite.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Readers for the Steam client's own caches, so the app can show a game's details and achievements
 * without an API key or a login of its own. Everything here is plain parsing (no Android), and fails
 * soft: a file the client has not written yet, or one in a format it has since changed, gives null.
 */
object SteamFiles {
    /** SteamID64 of account id 0; an account id is the low 32 bits. */
    private const val STEAM_ID_BASE = 76561197960265728L

    // ---------------------------------------------------------------- account

    /** The signed-in account from config/loginusers.vdf: the most recent one. */
    data class Account(val steamId: Long, val accountId: Long, val name: String)

    fun account(loginUsers: File): Account? = runCatching {
        val users = KeyValues.map(KeyValues.parseText(loginUsers.readText()), "users") ?: return null
        val chosen = users.entries.maxByOrNull { (_, v) ->
            val recent = KeyValues.string(v, "MostRecent") == "1"
            (if (recent) Long.MAX_VALUE / 2 else 0L) + (KeyValues.long(v, "Timestamp") ?: 0L)
        } ?: return null
        val id = chosen.key.toLongOrNull() ?: return null
        Account(id, id - STEAM_ID_BASE, KeyValues.string(chosen.value, "PersonaName") ?: KeyValues.string(chosen.value, "AccountName") ?: "")
    }.getOrNull()

    // ---------------------------------------------------------------- playtime

    /** Minutes played and when last played (unix seconds, 0 = never), from the account's localconfig.vdf. */
    data class Playtime(val minutes: Long, val lastPlayed: Long)

    fun playtime(localConfig: File, appId: String): Playtime? = runCatching {
        val root = KeyValues.parseText(localConfig.readText())
        val app = KeyValues.get(root, "UserLocalConfigStore", "Software", "Valve", "Steam", "apps", appId) ?: return null
        Playtime(KeyValues.long(app, "Playtime") ?: 0L, KeyValues.long(app, "LastPlayed") ?: 0L)
    }.getOrNull()

    // ---------------------------------------------------------------- appinfo.vdf

    /** What the client's app metadata says about a game (store descriptions are not in it). */
    data class AppInfo(
        val name: String?,
        val developer: String?,
        val publisher: String?,
        /** Unix seconds, or null. */
        val releaseDate: Long?,
        val metacritic: Int?,
        /** Steam Deck compatibility: 1 unsupported, 2 playable, 3 verified; null = unknown. */
        val deckCategory: Int?,
        /** "full" or "partial", or null. */
        val controllerSupport: String?,
        val reviewPercentage: Int?,
    )

    /**
     * The entry for [appId] in appcache/appinfo.vdf. The file holds every app the account has seen
     * (tens of megabytes), so entries are skipped by their size until the wanted one; only that one
     * is parsed. Formats 27-29 are understood (29, from 2024, moved keys into a string table).
     */
    fun appInfo(file: File, appId: Long): AppInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            val kv = appInfoEntry(buf, appId) ?: return null
            val common = KeyValues.get(kv, "appinfo", "common") ?: return null
            fun association(type: String): String? = KeyValues.map(common, "associations")?.values
                ?.firstOrNull { KeyValues.string(it, "type") == type }?.let { KeyValues.string(it, "name") }
            AppInfo(
                name = KeyValues.string(common, "name"),
                developer = association("developer") ?: KeyValues.string(kv, "appinfo", "extended", "developer"),
                publisher = association("publisher") ?: KeyValues.string(kv, "appinfo", "extended", "publisher"),
                releaseDate = (KeyValues.long(common, "steam_release_date") ?: KeyValues.long(common, "original_release_date"))
                    ?.takeIf { it > 0 },
                metacritic = KeyValues.long(common, "metacritic_score")?.toInt()?.takeIf { it > 0 },
                deckCategory = KeyValues.long(common, "steam_deck_compatibility", "category")?.toInt()?.takeIf { it > 0 },
                controllerSupport = KeyValues.string(common, "controller_support"),
                reviewPercentage = KeyValues.long(common, "review_percentage")?.toInt(),
            )
        }
    }.getOrNull()

    /** An app's name and type ("game", "demo", "tool", "dlc"...), lowercased type. */
    data class AppKind(val name: String, val type: String)

    /** Name and type of each of [appIds] that appinfo.vdf has, in one pass over the file. */
    fun appKinds(file: File, appIds: Set<Long>): Map<Long, AppKind> = runCatching {
        if (appIds.isEmpty()) return emptyMap()
        RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            val out = HashMap<Long, AppKind>()
            forEachAppInfoEntry(buf, appIds) { id, kv ->
                val common = KeyValues.get(kv, "appinfo", "common") ?: return@forEachAppInfoEntry
                val name = KeyValues.string(common, "name")?.takeIf { it.isNotBlank() } ?: return@forEachAppInfoEntry
                out[id] = AppKind(name, KeyValues.string(common, "type").orEmpty().lowercase())
            }
            out
        }
    }.getOrDefault(emptyMap())

    /** The parsed KeyValues of [appId]'s entry, or null when the file has none. */
    fun appInfoEntry(buf: ByteBuffer, appId: Long): Map<String, Any>? {
        var found: Map<String, Any>? = null
        forEachAppInfoEntry(buf, setOf(appId)) { _, kv -> found = kv }
        return found
    }

    /** Parses the entries of [wanted] only, skipping the rest by their size; stops once all are seen. */
    private fun forEachAppInfoEntry(buf: ByteBuffer, wanted: Set<Long>, onEntry: (Long, Map<String, Any>) -> Unit) {
        buf.position(0)
        val magic = buf.int
        val version = magic and 0xFF
        if ((magic ushr 8) != 0x075644 || version !in 0x27..0x29) return
        buf.int // universe
        var keys: List<String>? = null
        if (version >= 0x29) {
            val tableAt = buf.long
            val table = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(tableAt.toInt()) as ByteBuffer
            val count = table.int
            keys = List(count) { KeyValues.cString(table) }
        }
        var left = wanted.size
        while (buf.remaining() >= 8 && left > 0) {
            val id = buf.int.toLong() and 0xFFFFFFFFL
            if (id == 0L) return
            val size = buf.int
            val start = buf.position()
            if (id in wanted) {
                // infoState, lastUpdated, picsToken, sha1, changeNumber, and from 28 the binary sha1.
                buf.position(start + 4 + 4 + 8 + 20 + 4 + if (version >= 0x28) 20 else 0)
                runCatching { KeyValues.parseBinary(buf, keys) }.getOrNull()?.let { onEntry(id, it) }
                left--
            }
            buf.position(start + size)
        }
    }

    // ---------------------------------------------------------------- achievements

    data class Achievement(
        val id: String,
        val name: String,
        val description: String,
        val hidden: Boolean,
        val unlocked: Boolean,
        /** Unix seconds, or 0 when locked or unknown. */
        val unlockedAt: Long,
        /** Icon file names on Steam's CDN (see [iconUrl]), or null. */
        val icon: String?,
        val iconGray: String?,
    )

    fun iconUrl(appId: String, icon: String) =
        "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appId/$icon"

    /**
     * A game's achievements from appcache/stats: the definitions in UserGameStatsSchema_<app>.bin
     * (written once the game has run or its achievements were viewed) and the account's progress in
     * UserGameStats_<account>_<app>.bin (null [stats] = none unlocked yet). Null when there is no
     * schema, or it defines no achievements.
     */
    fun achievements(schema: ByteArray, stats: ByteArray?): List<Achievement>? {
        val schemaRoot = KeyValues.parseBinary(schema)
        val statsRoot = stats?.let { runCatching { KeyValues.parseBinary(it) }.getOrNull() }
        // The schema's root is the app id; its "stats" hold one block per stat, achievements being
        // the ones with "bits" (up to 32 achievements per stat).
        val statBlocks = schemaRoot.values.firstNotNullOfOrNull { KeyValues.map(it, "stats") } ?: return null
        val progress = statsRoot?.let(::progressBlocks).orEmpty()
        val out = ArrayList<Achievement>()
        for ((statId, stat) in statBlocks) {
            val bits = KeyValues.map(stat, "bits") ?: continue
            val done = progress[statId]
            val data = KeyValues.long(done, "data") ?: 0L
            for ((bitKey, bit) in bits) {
                val index = (KeyValues.long(bit, "bit") ?: bitKey.toLongOrNull() ?: continue).toInt()
                val display = KeyValues.get(bit, "display")
                val unlocked = ((data ushr index) and 1L) == 1L
                out += Achievement(
                    id = KeyValues.string(bit, "name") ?: "$statId.$index",
                    name = localized(KeyValues.get(display, "name")) ?: KeyValues.string(bit, "name") ?: "Achievement",
                    description = localized(KeyValues.get(display, "desc")).orEmpty(),
                    hidden = KeyValues.string(display, "hidden").let { it == "1" || it.equals("true", true) },
                    unlocked = unlocked,
                    unlockedAt = if (unlocked) KeyValues.long(done, "AchievementTimes", index.toString()) ?: 0L else 0L,
                    icon = KeyValues.string(display, "icon")?.takeIf { it.isNotBlank() },
                    iconGray = KeyValues.string(display, "icon_gray")?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The per-stat progress blocks ("<stat id>" { "data" ... }), at the root or one level down ("cache"). */
    private fun progressBlocks(root: Map<String, Any>): Map<String, Any> {
        fun blocks(map: Map<String, Any>) = map.filter { (k, v) -> k.toLongOrNull() != null && v is Map<*, *> }
        blocks(root).takeIf { it.isNotEmpty() }?.let { return it }
        for (v in root.values) {
            @Suppress("UNCHECKED_CAST")
            (v as? Map<String, Any>)?.let(::blocks)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyMap()
    }

    /** A display string: plain, or a map of languages (English preferred). */
    private fun localized(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is Map<*, *> -> (KeyValues.string(value, "english")
            ?: value.entries.firstOrNull { it.key != "token" && it.value is String }?.value as? String)?.takeIf { it.isNotBlank() }
        else -> null
    }
}
