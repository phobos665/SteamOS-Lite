package com.steamoslite.stores.gog

import android.content.Context
import com.steamoslite.stores.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

data class ParsedGogGame(
    val id: String,
    val title: String,
    val slug: String,
    val imageUrl: String,
    val iconUrl: String,
    val backgroundUrl: String,
    val developer: String,
    val publisher: String,
    val genres: List<String>,
    val languages: List<String>,
    val description: String,
    val releaseDate: String,
    val downloadSize: Long,
    val isSecret: Boolean,
    val isDlc: Boolean
)

data class RawGogApiResponse(
    val id: String?,
    val title: String?,
    val slug: String?,
    val images: Images?,
    val developers: List<Developer>?,
    val publisher: Any?,
    val genres: List<Genre>?,
    val languages: Map<String, String>?,
    val description: Description?,
    val release_date: String?,
    val downloads: Downloads?
) {
    data class Images(
        val background: String?,
        val logo2x: String?,
        val logo: String?,
        val icon: String?
    )

    data class Developer(
        val name: String?
    )

    data class Genre(
        val name: String?
    )

    data class Description(
        val lead: String?
    )

    data class Downloads(
        val installers: List<Installer>?
    )

    data class Installer(
        val id: String?,
        val name: String?,
        val os: String?,
        val language: String?,
        val total_size: Long?
    )
}

object GOGLibraryClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getGameIds(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching GOG game IDs...")

            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.tag("GOG").e(error, "Cannot list games: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.tag("GOG").e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            val url = "${GOGConstants.GOG_EMBED_URL}/user/data/games"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", Net.USER_AGENT)
                .get()
                .build()

            Timber.tag("GOG").d("Requesting game IDs from: $url")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.e("Failed to fetch game IDs: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game IDs: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.w("Empty response when fetching game IDs")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                val userData = JSONObject(responseBody)
                val ownedGames = userData.optJSONArray("owned") ?: JSONArray()

                val gameIds = List(ownedGames.length()) {
                    ownedGames.get(it).toString()
                }

                Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs")
                Timber.tag("GOG").d("First 10 game IDs: ${gameIds.take(10).joinToString()}")
                return@withContext Result.success(gameIds)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching game IDs: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    suspend fun getHiddenGameIds(context: Context): Result<Set<String>> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching hidden GOG game IDs...")

            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.tag("GOG").e(error, "Cannot list hidden games: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.tag("GOG").e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            val primaryResult = fetchHiddenGameIdsFrom(credentials, GOGConstants.GOG_EMBED_URL)
            if (primaryResult.isFailure) {
                return@withContext primaryResult
            }
            val primaryIds = primaryResult.getOrNull() ?: emptySet()
            if (primaryIds.isNotEmpty()) {
                Timber.tag("GOG").i("Successfully fetched ${primaryIds.size} hidden GOG game IDs")
                return@withContext Result.success(primaryIds)
            }

            val fallbackResult = fetchHiddenGameIdsFrom(credentials, "https://www.gog.com")
            if (fallbackResult.isFailure) {
                return@withContext fallbackResult
            }
            val mergedIds = buildSet {
                addAll(primaryIds)
                fallbackResult.getOrNull()?.let { addAll(it) }
            }
            Timber.tag("GOG").i("Successfully fetched ${mergedIds.size} hidden GOG game IDs")
            return@withContext Result.success(mergedIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Exception fetching hidden GOG game IDs: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    private suspend fun fetchHiddenGameIdsFrom(
        credentials: GOGCredentials,
        baseUrl: String,
    ): Result<Set<String>> {
        return try {
            var page = 1
            var totalPages = 1
            val hiddenIds = mutableSetOf<String>()
            while (page <= totalPages) {
                val url = "$baseUrl/account/getFilteredProducts?hiddenFlag=1&mediaType=1&page=$page"
                Timber.tag("GOG").d("Requesting hidden game IDs from: $url")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                    .addHeader("User-Agent", Net.USER_AGENT)

                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Timber.tag("GOG").e("Failed to fetch hidden game IDs: HTTP ${response.code} - $errorBody")
                        return Result.failure(
                            Exception("Failed to fetch hidden game IDs: HTTP ${response.code}")
                        )
                    }

                    val responseBody = response.body?.string()
                        ?: return Result.failure(Exception("Empty response from GOG"))
                    val parsed = GogFilteredProductsParser.parseHiddenPage(responseBody)
                    hiddenIds.addAll(parsed.hiddenProductIds)
                    totalPages = parsed.totalPages
                }
                page++
            }

            Timber.tag("GOG").d("Fetched ${hiddenIds.size} hidden GOG game IDs from $baseUrl")
            Result.success(hiddenIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGameById(
        context: Context,
        gameId: String,
        expanded: List<String> = listOf("downloads", "description", "screenshots")
    ): Result<ParsedGogGame> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching game details for gameId: $gameId")

            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.e(error, "Cannot fetch game details: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            val expandedParam = if (expanded.isNotEmpty()) {
                "?expand=${expanded.joinToString(",")}"
            } else {
                ""
            }
            val url = "${GOGConstants.GOG_BASE_API_URL}/products/$gameId$expandedParam"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", Net.USER_AGENT)
                .get()
                .build()

            Timber.tag("GOG").d("Requesting game details from: $url")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to fetch game details for $gameId: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game details: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.tag("GOG").w("Empty response when fetching game details for $gameId")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                val rawApiResponse = JSONObject(responseBody)

                val transformedResponse = transformGameDetails(rawApiResponse, gameId)

                return@withContext Result.success(transformedResponse)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Exception fetching game details for $gameId: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    suspend fun getVerticalCoverUrl(gameId: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "${GOGConstants.GOG_GAMESDB_URL}/platforms/gog/external_releases/$gameId"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", Net.USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GOG").d("No GamesDB entry for $gameId: HTTP ${response.code}")
                    return@withContext ""
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) return@withContext ""

                val urlFormat = JSONObject(responseBody)
                    .optJSONObject("game")
                    ?.optJSONObject("vertical_cover")
                    ?.optString("url_format")
                    .orEmpty()

                if (urlFormat.isEmpty()) return@withContext ""

                return@withContext urlFormat
                    .replace("{formatter}", "_glx_vertical_cover")
                    .replace("{ext}", "webp")
            }
        } catch (e: Exception) {
            Timber.tag("GOG").d(e, "Failed to fetch vertical cover for $gameId: ${e.message}")
            return@withContext ""
        }
    }

    private fun transformGameDetails(rawResponse: JSONObject, gameId: String): ParsedGogGame {
        val images = rawResponse.optJSONObject("images")
        var background = images?.optString("background", "") ?: ""
        var logo2x = images?.optString("logo2x", "") ?: ""
        var logo = images?.optString("logo", "") ?: ""
        var icon = images?.optString("icon", "") ?: ""

        if (background.startsWith("//")) background = "https:$background"
        if (logo2x.startsWith("//")) logo2x = "https:$logo2x"
        if (logo.startsWith("//")) logo = "https:$logo"
        if (icon.startsWith("//")) icon = "https:$icon"

        val imageUrl = logo2x.ifEmpty { logo }
        val backgroundUrl = background.ifEmpty { imageUrl }

        val developers = rawResponse.optJSONArray("developers")
        val developer = if (developers != null && developers.length() > 0) {
            developers.optJSONObject(0)?.optString("name", "") ?: ""
        } else {
            ""
        }

        val publisherObj = rawResponse.opt("publisher")
        val publisher = when (publisherObj) {
            is JSONObject -> publisherObj.optString("name", "")
            is String -> publisherObj
            else -> ""
        }

        val genresArray = rawResponse.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.opt(i)
                val genreName = when (genreObj) {
                    is JSONObject -> genreObj.optString("name", "")
                    is String -> genreObj
                    else -> ""
                }
                if (genreName.isNotEmpty()) {
                    genres.add(genreName)
                }
            }
        }

        val languages = mutableListOf<String>()
        val langObj = rawResponse.optJSONObject("languages")
        if (langObj != null) {
            val keys = langObj.keys()
            while (keys.hasNext()) {
                languages.add(keys.next())
            }
        }

        val descriptionObj = rawResponse.opt("description")
        val description = when (descriptionObj) {
            is JSONObject -> descriptionObj.optString("lead", "")
            is String -> descriptionObj
            else -> ""
        }

        val downloads = rawResponse.optJSONObject("downloads")

        val isSecret = rawResponse.optBoolean("is_secret", false)
        val gameType = rawResponse.optString("game_type", "dlc")
        val isDlc = gameType == "dlc"

        val installers = downloads?.optJSONArray("installers")
        val downloadSize = if (installers != null && installers.length() > 0) {
            installers.optJSONObject(0)?.optLong("total_size", 0L) ?: 0L
        } else {
            0L
        }

        return ParsedGogGame(
            id = gameId,
            title = rawResponse.optString("title", "Unknown"),
            slug = rawResponse.optString("slug", ""),
            imageUrl = imageUrl,
            iconUrl = icon,
            backgroundUrl = backgroundUrl,
            developer = developer,
            publisher = publisher,
            genres = genres,
            languages = languages,
            description = description,
            releaseDate = rawResponse.optString("release_date", ""),
            downloadSize = downloadSize,
            isSecret = isSecret,
            isDlc = isDlc
        )
    }
}
