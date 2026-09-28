package com.steamoslite.stores.gog

import android.content.Context
import com.steamoslite.stores.Store
import com.steamoslite.stores.StoreGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object GOGManager {
    private const val GALAXY_CLIENT_ID = "1801418160"

    /**
     * The account's games, with details for the ones not already known. Details are one request
     * per game, so [known] (the last refresh) is reused rather than fetched again.
     */
    suspend fun refreshLibrary(
        context: Context,
        known: List<StoreGame>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<List<StoreGame>> = withContext(Dispatchers.IO) {
        try {
            val ids = GOGLibraryClient.getGameIds(context).getOrElse { return@withContext Result.failure(it) }
            val hidden = GOGLibraryClient.getHiddenGameIds(context).getOrDefault(emptySet())
            val byId = known.associateBy { it.id }
            val games = mutableListOf<StoreGame>()
            val wanted = ids.filter { it != GALAXY_CLIENT_ID && it !in hidden }
            for ((index, id) in wanted.withIndex()) {
                onProgress(index + 1, wanted.size)
                byId[id]?.let { games += it; continue }
                try {
                    val parsed = GOGLibraryClient.getGameById(context, id).getOrNull() ?: continue
                    val excluded = parsed.title == "Unknown Game" || parsed.title.startsWith("product_title_") ||
                        parsed.title == "Unknown" || parsed.downloadSize == 0L || parsed.isSecret ||
                        parsed.title.endsWith("Amazon Prime") || parsed.isDlc
                    if (excluded) continue
                    games += StoreGame(
                        store = Store.GOG,
                        id = parsed.id,
                        title = parsed.title,
                        coverUrl = GOGLibraryClient.getVerticalCoverUrl(id).ifEmpty { parsed.imageUrl },
                        heroUrl = parsed.backgroundUrl,
                        developer = parsed.developer,
                        publisher = parsed.publisher,
                        description = parsed.description,
                        releaseDate = parsed.releaseDate,
                        downloadSize = parsed.downloadSize,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "Failed to read details for $id")
                }
            }
            Result.success(games.sortedBy { it.title.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to refresh GOG library")
            Result.failure(e)
        }
    }

    data class PlayTask(val path: String, val arguments: String, val workingDir: String)

    /** The primary play task in the goggame-<id>.info GOG writes into every install. */
    fun primaryPlayTask(installDir: File, gameId: String): PlayTask? {
        val info = findInfoFile(installDir, gameId) ?: findInfoFile(installDir, null) ?: return null
        return runCatching {
            val tasks = JSONObject(info.readText()).getJSONArray("playTasks")
            val task = (0 until tasks.length()).map { tasks.getJSONObject(it) }
                .firstOrNull { it.optBoolean("isPrimary") } ?: tasks.getJSONObject(0)
            val base = info.parentFile!!.relativeTo(installDir).path
            fun rel(p: String) = listOf(base, p.replace('\\', '/')).filter { it.isNotEmpty() }.joinToString("/")
            PlayTask(
                path = rel(task.getString("path")),
                arguments = task.optString("arguments", ""),
                workingDir = task.optString("workingDir", "").let { if (it.isEmpty()) "" else rel(it) },
            )
        }.getOrNull()
    }

    private fun findInfoFile(dir: File, gameId: String?, depth: Int = 0): File? {
        val files = dir.listFiles() ?: return null
        files.firstOrNull {
            it.isFile && if (gameId != null) it.name == "goggame-$gameId.info" else it.name.startsWith("goggame-") && it.name.endsWith(".info")
        }?.let { return it }
        if (depth >= 3) return null
        return files.filter { it.isDirectory }.firstNotNullOfOrNull { findInfoFile(it, gameId, depth + 1) }
    }
}
