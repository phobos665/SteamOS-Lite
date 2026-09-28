package com.steamoslite.stores.epic

import android.content.Context
import com.steamoslite.stores.Net
import com.steamoslite.stores.Store
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.sanitizeForFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object EpicManager {
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private val httpClient = Net.http

    data class ParsedLibraryItem(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val sandboxType: String?,
        val country: String?,
    )

    data class ManifestResult(
        val manifestBytes: ByteArray,
        val cdnUrls: List<CdnUrl>,
    )

    data class CdnUrl(
        val baseUrl: String,
        val authQueryParams: String,
        val cloudDir: String = "",
    )

    private data class CustomAttributes(
        val canRunOffline: Boolean = false,
        val ownershipToken: Boolean = false,
        val thirdPartyManagedApp: String? = null,
        val thirdPartyManagedProvider: String? = null,
        val partnerLinkType: String? = null,
        val additionalCommandline: String? = null,
    )

    /** The account's Windows games, DLC and add-ons left out. */
    suspend fun refreshLibrary(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<List<StoreGame>> =
        withContext(Dispatchers.IO) {
            try {
                val accessToken = EpicAuthManager.getStoredCredentials(context).getOrElse { return@withContext Result.failure(it) }.accessToken
                val items = fetchLibrary(accessToken).getOrElse { return@withContext Result.failure(it) }
                val games = mutableListOf<StoreGame>()
                for ((index, item) in items.withIndex()) {
                    onProgress(index + 1, items.size)
                    val data = runCatching {
                        fetchCatalogItem(item.namespace, item.catalogItemId, accessToken, item.country ?: "US", includeMainGameDetails = true)
                    }.getOrNull() ?: continue
                    if (data.has("mainGameItem")) continue
                    games += parseGameFromCatalog(data, item.appName)
                }
                Result.success(games.sortedBy { it.title.lowercase() })
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to refresh Epic library")
                Result.failure(e)
            }
        }

    private fun fetchLibrary(accessToken: String): Result<List<ParsedLibraryItem>> {
        val gameList = mutableListOf<ParsedLibraryItem>()
        var cursor: String? = null
        do {
            val url = buildString {
                append("${EpicConstants.EPIC_LIBRARY_API_URL}?includeMetadata=true")
                if (cursor != null) append("&cursor=$cursor")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()
            val json = httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code}: $body"))
                if (body.isNullOrEmpty()) return Result.failure(Exception("Empty response"))
                JSONObject(body)
            }
            val records = json.optJSONArray("records") ?: JSONArray()
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (!record.has("appName")) continue
                val appName = record.getString("appName")
                val namespace = record.getString("namespace")
                val sandboxType = record.optString("sandboxType", "")
                val platformsArray = record.optJSONArray("platform")
                val platforms = List(platformsArray?.length() ?: 0) { platformsArray!!.getString(it) }
                if (namespace == "ue" || sandboxType == "PRIVATE" || appName == "1") continue
                if (platforms.isNotEmpty() && "Win32" !in platforms && "Windows" !in platforms) continue
                gameList += ParsedLibraryItem(appName, namespace, record.getString("catalogItemId"), sandboxType, record.optString("country", ""))
            }
            val oldCursor = cursor
            cursor = json.optJSONObject("responseMetadata")?.optString("nextCursor")?.takeIf { it.isNotEmpty() }
        } while (cursor != null && cursor != oldCursor)
        return Result.success(gameList.distinctBy { it.appName })
    }

    private fun fetchCatalogItem(
        namespace: String,
        catalogItemId: String,
        accessToken: String,
        country: String = "US",
        includeMainGameDetails: Boolean = false,
    ): JSONObject? {
        val params = buildString {
            append("?id=").append(catalogItemId)
            append("&includeDLCDetails=true")
            if (includeMainGameDetails) append("&includeMainGameDetails=true")
            append("&country=").append(country.ifEmpty { "US" })
        }
        val request = Request.Builder()
            .url("${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/$namespace/bulk/items$params")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string()
            if (body.isNullOrEmpty()) return@use null
            JSONObject(body).optJSONObject(catalogItemId)
        }
    }

    private fun parseCustomAttributes(json: JSONObject?): CustomAttributes {
        if (json == null) return CustomAttributes()
        fun attr(name: String) = json.optJSONObject(name)?.optString("value")?.takeIf { it.isNotEmpty() }
        fun flag(name: String) = attr(name)?.lowercase() == "true"
        return CustomAttributes(
            canRunOffline = flag("CanRunOffline"),
            ownershipToken = flag("OwnershipToken"),
            thirdPartyManagedApp = attr("ThirdPartyManagedApp"),
            thirdPartyManagedProvider = attr("ThirdPartyManagedProvider"),
            partnerLinkType = attr("partnerLinkType"),
            additionalCommandline = attr("AdditionalCommandLine"),
        )
    }

    private fun parseGameFromCatalog(data: JSONObject, appName: String): StoreGame {
        val keyImages = data.optJSONArray("keyImages")
        var tall = ""
        var box = ""
        var wide = ""
        for (i in 0 until (keyImages?.length() ?: 0)) {
            val img = keyImages!!.getJSONObject(i)
            val url = img.optString("url", "")
            when (img.optString("type")) {
                "DieselGameBoxTall" -> tall = url
                "DieselGameBox" -> box = url
                "DieselStoreFrontWide" -> wide = url
                "Thumbnail" -> if (box.isEmpty()) box = url
            }
        }
        val attributes = parseCustomAttributes(data.optJSONObject("customAttributes"))
        val releaseInfo = data.optJSONArray("releaseInfo")
        return StoreGame(
            store = Store.EPIC,
            id = appName,
            title = data.getString("title"),
            coverUrl = tall.ifEmpty { box },
            heroUrl = wide.ifEmpty { box },
            developer = data.optString("developer", ""),
            description = data.optString("description", ""),
            releaseDate = if (releaseInfo != null && releaseInfo.length() > 0) releaseInfo.getJSONObject(0).optString("dateAdded", "") else "",
            namespace = data.getString("namespace"),
            catalogId = data.getString("id"),
            requiresOwnershipToken = attributes.ownershipToken,
            canRunOffline = attributes.canRunOffline,
            thirdPartyManagedApp = listOfNotNull(
                attributes.thirdPartyManagedApp, attributes.thirdPartyManagedProvider, attributes.partnerLinkType,
            ).firstOrNull() ?: "",
        )
    }

    /** The launcher API's entry for the game's live build: its manifest locations and sidecar. */
    private fun fetchAssetElement(accessToken: String, namespace: String, catalogItemId: String, appName: String): JSONObject? {
        val url = "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
            "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app/$appName/label/Live"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Manifest API request failed: ${response.code}")
            val body = response.body?.string()
            if (body.isNullOrEmpty()) throw Exception("Empty manifest API response")
            JSONObject(body).optJSONArray("elements")?.takeIf { it.length() > 0 }?.getJSONObject(0)
        }
    }

    suspend fun fetchManifest(context: Context, game: StoreGame): Result<ManifestResult> = withContext(Dispatchers.IO) {
        try {
            val accessToken = EpicAuthManager.getStoredCredentials(context).getOrElse { return@withContext Result.failure(it) }.accessToken
            val element = fetchAssetElement(accessToken, game.namespace, game.catalogId, game.id)
                ?: return@withContext Result.failure(Exception("No elements in manifest API response"))
            val manifests = element.optJSONArray("manifests")
            if (manifests == null || manifests.length() == 0) {
                return@withContext Result.failure(Exception("No manifests in API response"))
            }

            fun queryOf(manifest: JSONObject): String {
                val params = manifest.optJSONArray("queryParams") ?: return ""
                if (params.length() == 0) return ""
                return (0 until params.length()).joinToString("&", prefix = "?") {
                    val p = params.getJSONObject(it)
                    "${p.getString("name")}=${p.getString("value")}"
                }
            }

            // Every manifest entry is the same build on a different CDN; the chunks live under the
            // manifest's own directory (its "CloudDir") on each of them.
            val cdnUrls = (0 until manifests.length()).mapNotNull { i ->
                val manifest = manifests.getJSONObject(i)
                val uri = manifest.getString("uri")
                val baseUrl = uri.substringBefore("/Builds")
                if (baseUrl.isEmpty() || !baseUrl.startsWith("http")) return@mapNotNull null
                val cloudDir = if ("/Builds" in uri) uri.substringAfter(baseUrl).substringBeforeLast("/") else ""
                CdnUrl(baseUrl, queryOf(manifest), cloudDir)
            }
            if (cdnUrls.isEmpty()) return@withContext Result.failure(Exception("No CDN URLs found in manifest API response"))

            val first = manifests.getJSONObject(0)
            val manifestRequest = Request.Builder()
                .url(first.getString("uri") + queryOf(first))
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()
            val bytes = Net.httpForParallelDownloads(4).newCall(manifestRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("Failed to download manifest: ${response.code}"))
                response.body?.bytes() ?: return@withContext Result.failure(Exception("Empty manifest"))
            }
            Result.success(ManifestResult(bytes, cdnUrls))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching manifest")
            Result.failure(e)
        }
    }

    /**
     * The EOS deployment id from the manifest API's sidecar, which EOS games need on their command
     * line. Most games have none; that answer is cached too.
     */
    suspend fun fetchDeploymentId(context: Context, game: StoreGame): String? = withContext(Dispatchers.IO) {
        val cacheFile = File(File(context.filesDir, "epic/deployment_ids").apply { mkdirs() }, "${game.id.sanitizeForFilename()}.txt")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
        }
        try {
            val accessToken = EpicAuthManager.getStoredCredentials(context).getOrNull()?.accessToken ?: return@withContext null
            val element = fetchAssetElement(accessToken, game.namespace, game.catalogId, game.id) ?: return@withContext null
            // sidecar.config is a JSON document encoded as a string, not a nested object.
            val config = element.optJSONObject("sidecar")?.optString("config", "") ?: ""
            if (config.isEmpty()) {
                cacheFile.writeText("")
                return@withContext null
            }
            val deploymentId = runCatching { JSONObject(config).optString("deploymentId", "").takeIf { it.isNotEmpty() } }
                .getOrElse { return@withContext null }
            cacheFile.writeText(deploymentId ?: "")
            deploymentId
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching deployment id for ${game.id}")
            null
        }
    }

    suspend fun fetchAdditionalCommandLine(context: Context, game: StoreGame): String? = withContext(Dispatchers.IO) {
        val cacheFile = File(File(context.filesDir, "epic/additional_cmdline").apply { mkdirs() }, "${game.id.sanitizeForFilename()}.txt")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            return@withContext cacheFile.readText().trim().takeIf { it.isNotEmpty() }
        }
        try {
            val accessToken = EpicAuthManager.getStoredCredentials(context).getOrNull()?.accessToken ?: return@withContext null
            val data = fetchCatalogItem(game.namespace, game.catalogId, accessToken) ?: return@withContext null
            val line = parseCustomAttributes(data.optJSONObject("customAttributes")).additionalCommandline
            cacheFile.writeText(line ?: "")
            line
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching additional command line for ${game.id}")
            null
        }
    }
}
