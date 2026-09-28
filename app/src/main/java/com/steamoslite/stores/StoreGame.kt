package com.steamoslite.stores

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Store(val id: String, val label: String) {
    GOG("gog", "GOG"),
    EPIC("epic", "Epic"),
}

/** A game in a GOG or Epic library. [id] is GOG's product id or Epic's app name. */
data class StoreGame(
    val store: Store,
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val heroUrl: String = "",
    val developer: String = "",
    val publisher: String = "",
    val description: String = "",
    val releaseDate: String = "",
    val downloadSize: Long = 0,
    val namespace: String = "",
    val catalogId: String = "",
    val requiresOwnershipToken: Boolean = false,
    val canRunOffline: Boolean = true,
    val thirdPartyManagedApp: String = "",
    /** Owned DLC that installs separately (Epic); GOG's comes with the game's own build. */
    val dlc: List<StoreDlc> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("store", store.id).put("id", id).put("title", title)
        .put("coverUrl", coverUrl).put("heroUrl", heroUrl)
        .put("developer", developer).put("publisher", publisher)
        .put("description", description).put("releaseDate", releaseDate)
        .put("downloadSize", downloadSize).put("namespace", namespace).put("catalogId", catalogId)
        .put("requiresOwnershipToken", requiresOwnershipToken).put("canRunOffline", canRunOffline)
        .put("thirdPartyManagedApp", thirdPartyManagedApp)
        .put("dlc", JSONArray().apply { dlc.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = StoreGame(
            store = Store.entries.first { it.id == o.getString("store") },
            id = o.getString("id"),
            title = o.optString("title"),
            coverUrl = o.optString("coverUrl"),
            heroUrl = o.optString("heroUrl"),
            developer = o.optString("developer"),
            publisher = o.optString("publisher"),
            description = o.optString("description"),
            releaseDate = o.optString("releaseDate"),
            downloadSize = o.optLong("downloadSize"),
            namespace = o.optString("namespace"),
            catalogId = o.optString("catalogId"),
            requiresOwnershipToken = o.optBoolean("requiresOwnershipToken"),
            canRunOffline = o.optBoolean("canRunOffline", true),
            thirdPartyManagedApp = o.optString("thirdPartyManagedApp"),
            dlc = o.optJSONArray("dlc")?.let { a -> List(a.length()) { StoreDlc.fromJson(a.getJSONObject(it)) } }.orEmpty(),
        )
    }
}

data class StoreDlc(val id: String, val title: String, val namespace: String = "", val catalogId: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("namespace", namespace).put("catalogId", catalogId)

    companion object {
        fun fromJson(o: JSONObject) = StoreDlc(o.getString("id"), o.optString("title"), o.optString("namespace"), o.optString("catalogId"))
    }
}

/** Where an installed game is and how it starts, as the session sees it. */
data class Installation(
    /** The install directory as a path inside the runtime (/root/Games/...). */
    val guestDir: String,
    /** The executable, relative to [guestDir]. */
    val exe: String,
    val args: String = "",
    val workingDir: String = "",
    val sizeBytes: Long = 0,
    val version: String = "",
    /** Titles of the DLC installed with it. */
    val dlc: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("guestDir", guestDir).put("exe", exe).put("args", args)
        .put("workingDir", workingDir).put("sizeBytes", sizeBytes).put("version", version)
        .put("dlc", JSONArray(dlc))

    companion object {
        fun fromJson(o: JSONObject) = Installation(
            o.getString("guestDir"), o.getString("exe"), o.optString("args"),
            o.optString("workingDir"), o.optLong("sizeBytes"), o.optString("version"),
            o.optJSONArray("dlc")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
        )
    }
}

/**
 * A store's library and installs, kept as JSON in the app's files: the library is replaced on each
 * refresh, installs are one file per game. Games install inside the runtime under /root/Games, so
 * the session reaches them at the same path the shortcuts use.
 */
class StoreLibrary(private val context: Context, val store: Store) {
    private val dir get() = File(context.filesDir, "stores/${store.id}").apply { mkdirs() }
    private val libraryFile get() = File(dir, "library.json")
    private val installsDir get() = File(dir, "installed").apply { mkdirs() }

    fun games(): List<StoreGame> = runCatching {
        val arr = JSONArray(libraryFile.readText())
        List(arr.length()) { StoreGame.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(games: List<StoreGame>) {
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(JSONArray().apply { games.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(libraryFile)
    }

    fun game(id: String): StoreGame? = games().firstOrNull { it.id == id }

    fun installation(id: String): Installation? =
        runCatching { Installation.fromJson(JSONObject(File(installsDir, id.sanitizeForFilename() + ".json").readText())) }.getOrNull()

    fun installed(): Map<String, Installation> = games().mapNotNull { g -> installation(g.id)?.let { g.id to it } }.toMap()

    fun setInstalled(id: String, installation: Installation?) {
        val f = File(installsDir, id.sanitizeForFilename() + ".json")
        if (installation == null) f.delete() else f.writeText(installation.toJson().toString())
    }

    fun clear() {
        libraryFile.delete()
    }

    companion object {
        fun gamesRoot(context: Context, store: Store) = File(LinuxRuntime.rootDir(context), "root/Games/${store.id}")

        fun guestPath(store: Store, folder: String) = "/root/Games/${store.id}/$folder"

        fun folderName(title: String) = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().ifEmpty { "Game" }
    }
}
