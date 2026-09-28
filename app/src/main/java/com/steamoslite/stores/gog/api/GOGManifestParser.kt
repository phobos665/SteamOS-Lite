package com.steamoslite.stores.gog.api

import com.steamoslite.stores.gog.GOGConstants
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import org.json.JSONObject

class GOGManifestParser() {
    companion object {
        private const val TAG = "GOG"
    }

    fun selectBuild(
        builds: List<GOGBuild>,
        preferredGeneration: Int = 2,
        platform: String = "windows"
    ): GOGBuild? {
        if (builds.isEmpty()) {
            Timber.tag(TAG).w("No builds available")
            return null
        }

        val filtered = builds.filter {
            it.generation == preferredGeneration && it.platform.equals(platform, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            val summary = builds.joinToString { "Gen ${it.generation}/${it.platform}" }
            Timber.tag(TAG).w("No Gen $preferredGeneration builds for platform: $platform. Available: [$summary]")
            return null
        }

        val selected = filtered.first()
        Timber.tag(TAG).d("Selected build ${selected.buildId} (Gen ${selected.generation}, ${selected.platform})")
        return selected
    }

    fun filterDepotsByLanguage(manifest: GOGManifestMeta, containerLanguage: String): Pair<List<Depot>, String> {
        val allDepotLangCodes = manifest.depots.flatMap { it.languages }.distinct().sorted()
        Timber.tag(TAG).d("Language depots codes in manifest: $allDepotLangCodes")

        val starDepots = manifest.depots.filter { it.matchesLanguage("*") }
        fun filter(lang: String): List<Depot> = manifest.depots.filter { it.matchesLanguage(lang) }
        val requestedCodes = GOGConstants.containerLanguageToGogCodes(containerLanguage)
        val englishCodes = GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE)

        for (lang in requestedCodes) {
            val matched = filter(lang)
            if (matched.isNotEmpty()) {
                val result = (matched + starDepots).distinct()
                Timber.tag(TAG).d("Filtered ${result.size}/${manifest.depots.size} depots for language: $lang")
                return result to lang
            }
        }

        for (fallbackLang in englishCodes) {
            val matched = filter(fallbackLang)
            if (matched.isNotEmpty()) {
                val result = (matched + starDepots).distinct()
                Timber.tag(TAG).d("No depots for requested language codes, fell back to $fallbackLang: ${result.size}/${manifest.depots.size} depots")
                return result to fallbackLang
            }
        }

        val effectiveLang = requestedCodes.firstOrNull() ?: "en"
        Timber.tag(TAG).d("No language match for $containerLanguage, using all ${manifest.depots.size} depots with effective: $effectiveLang")
        return manifest.depots to effectiveLang
    }

    fun filterDepotsByOwnership(depots: List<Depot>, ownedProductIds: Set<String>): List<Depot> {
        val filtered = depots.filter { depot ->
            depot.productId in ownedProductIds
        }

        Timber.tag(TAG).d("Filtered ${filtered.size}/${depots.size} depots for owned products")
        return filtered
    }

    fun separateBaseDLC(files: List<DepotFile>, baseProductId: String): Pair<List<DepotFile>, List<DepotFile>> {
        val baseFiles = mutableListOf<DepotFile>()
        val dlcFiles = mutableListOf<DepotFile>()

        files.forEach { file ->
            if (file.productId == null || file.productId == baseProductId) {
                baseFiles.add(file)
            } else {
                dlcFiles.add(file)
            }
        }

        Timber.tag(TAG).d("Separated: ${baseFiles.size} base files, ${dlcFiles.size} DLC files")
        return Pair(baseFiles, dlcFiles)
    }

    fun separateSupportFiles(files: List<DepotFile>): Pair<List<DepotFile>, List<DepotFile>> {
        val gameFiles = mutableListOf<DepotFile>()
        val supportFiles = mutableListOf<DepotFile>()

        files.forEach { file ->
            if (file.isSupportFile()) {
                supportFiles.add(file)
            } else {
                gameFiles.add(file)
            }
        }

        Timber.tag(TAG).d("Separated: ${gameFiles.size} game files, ${supportFiles.size} support files")
        return Pair(gameFiles, supportFiles)
    }

    fun calculateTotalSize(files: List<DepotFile>): Long {
        return files.sumOf { file ->
            file.chunks.sumOf { chunk ->
                chunk.compressedSize ?: chunk.size
            }
        }
    }

    fun calculateUncompressedSize(files: List<DepotFile>): Long {
        return files.sumOf { file ->
            file.chunks.sumOf { it.size }
        }
    }

    fun findDLCProducts(manifest: GOGManifestMeta): List<Product> {
        return manifest.products.filter { it.productId != manifest.baseProductId }
    }

    fun hasDLC(manifest: GOGManifestMeta): Boolean {
        return findDLCProducts(manifest).isNotEmpty()
    }

    fun buildChunkUrlCandidates(chunks: List<String>, baseUrls: List<String>): Map<String, List<String>> {
        if (baseUrls.isEmpty()) {
            Timber.tag(TAG).w("No base CDN URLs provided")
            return emptyMap()
        }

        return chunks.associateWith { chunkMd5 ->
            baseUrls.map { baseCdnUrl -> buildChunkUrl(baseCdnUrl, chunkMd5) }
        }
    }

    fun buildChunkUrlMap(chunks: List<String>, baseUrls: List<String>): Map<String, String> {
        return buildChunkUrlCandidates(chunks, baseUrls)
            .mapNotNull { (chunk, urls) -> urls.firstOrNull()?.let { chunk to it } }
            .toMap()
    }

    fun buildChunkUrlCandidatesWithProducts(
        chunks: List<String>,
        chunkToProductMap: Map<String, String>,
        productUrlMap: Map<String, List<String>>
    ): Map<String, List<String>> {
        val chunkUrlCandidates = mutableMapOf<String, List<String>>()

        for (chunkMd5 in chunks) {
            val productId = chunkToProductMap[chunkMd5]
            if (productId == null) {
                Timber.tag(TAG).w("No product ID found for chunk $chunkMd5")
                continue
            }

            val productUrls = productUrlMap[productId]
            if (productUrls.isNullOrEmpty()) {
                Timber.tag(TAG).w("No URLs found for product $productId (chunk $chunkMd5)")
                continue
            }

            chunkUrlCandidates[chunkMd5] = productUrls.map { baseCdnUrl ->
                buildChunkUrl(baseCdnUrl, chunkMd5)
            }
        }

        Timber.tag(TAG).d("Built ${chunkUrlCandidates.size} chunk URL candidate sets from ${productUrlMap.size} product(s)")
        return chunkUrlCandidates
    }

    fun buildChunkUrlMapWithProducts(
        chunks: List<String>,
        chunkToProductMap: Map<String, String>,
        productUrlMap: Map<String, List<String>>
    ): Map<String, String> {
        return buildChunkUrlCandidatesWithProducts(chunks, chunkToProductMap, productUrlMap)
            .mapNotNull { (chunk, urls) -> urls.firstOrNull()?.let { chunk to it } }
            .toMap()
    }

    private fun buildChunkUrl(baseCdnUrl: String, chunkMd5: String): String {
        val chunkPath = if (chunkMd5.length >= 4) {
            val first2 = chunkMd5.substring(0, 2)
            val next2 = chunkMd5.substring(2, 4)
            "$first2/$next2/$chunkMd5"
        } else {
            chunkMd5
        }

        return appendPathBeforeQuery(baseCdnUrl, chunkPath)
    }

    private fun appendPathBeforeQuery(baseUrl: String, path: String): String {
        val queryIndex = baseUrl.indexOf('?')
        val pathBase = if (queryIndex >= 0) baseUrl.substring(0, queryIndex) else baseUrl
        val querySuffix = if (queryIndex >= 0) baseUrl.substring(queryIndex) else ""
        val normalizedPathBase = pathBase.trimEnd('/')
        val normalizedPath = path.trimStart('/')
        return "$normalizedPathBase/$normalizedPath$querySuffix"
    }

    fun extractChunkHashes(files: List<DepotFile>): List<String> {
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<String>()

        files.forEach { file ->
            file.chunks.forEach { chunk ->
                if (seen.add(chunk.compressedMd5)) {
                    ordered.add(chunk.compressedMd5)
                }
            }
        }

        Timber.tag(TAG).d("Extracted ${ordered.size} unique chunks from ${files.size} files")
        return ordered
    }

    fun detectGeneration(build: GOGBuild): Int {
        return build.generation
    }

    fun parseBuilds(json: String): BuildsResponse {
        return BuildsResponse.fromJson(JSONObject(json))
    }

    fun parseDependencyManifest(json: String): GOGDependencyManifestMeta {
        return GOGDependencyManifestMeta.fromJson(JSONObject(json))
    }

    fun parseManifest(json: String): GOGManifestMeta {
        val obj = JSONObject(json)
        if (obj.has("product")) {
            val product = obj.getJSONObject("product")
            if (product.has("depots")) return parseManifestV1(obj)
        }
        return GOGManifestMeta.fromJson(obj)
    }

    fun parseManifestV1(json: JSONObject): GOGManifestMeta {
        val product = json.getJSONObject("product")
        val installDirectory = product.optString("installDirectory", "")
        val baseProductId = product.optString("rootGameID", "")
        val timestamp = product.optString("timestamp", "")

        val depotsArray = product.optJSONArray("depots")
        val depots = mutableListOf<Depot>()
        val dependencies = mutableListOf<String>()

        if (depotsArray != null) {
            for (i in 0 until depotsArray.length()) {
                val d = depotsArray.getJSONObject(i)
                if (d.has("redist")) {
                    dependencies.add(d.getString("redist"))
                    continue
                }
                val gameIds = d.optJSONArray("gameIDs")
                val productId = if (gameIds != null && gameIds.length() > 0) gameIds.getString(0) else ""
                val langs = mutableListOf<String>()
                val langArr = d.optJSONArray("languages")
                if (langArr != null) {
                    for (j in 0 until langArr.length()) {
                        val lang = langArr.getString(j)
                        langs.add(if (lang == "Neutral") "*" else lang)
                    }
                }

                if (langs.isEmpty()) langs.add("*")
                depots.add(
                    Depot(
                        productId = productId,
                        languages = langs,
                        manifest = d.optString("manifest", ""),
                        compressedSize = d.optLong("size", 0),
                        size = d.optLong("size", 0),
                        osBitness = null
                    )
                )
            }
        }

        val products = mutableListOf<Product>()
        val gameIdsArray = product.optJSONArray("gameIDs")
        if (gameIdsArray != null) {
            for (i in 0 until gameIdsArray.length()) {
                val g = gameIdsArray.getJSONObject(i)
                val pid = g.optString("gameID", "")
                val nameObj = g.optJSONObject("name")
                val name = nameObj?.optString("en", nameObj.optString("English", pid)) ?: pid
                products.add(Product(productId = pid, name = name))
            }
        }

        val supportCommands = mutableListOf<SupportCommand>()
        val supportArray = product.optJSONArray("support_commands")
        if (supportArray != null) {
            for (i in 0 until supportArray.length()) {
                val c = supportArray.getJSONObject(i)
                val executable = c.optString("executable", "")
                if (executable.isEmpty()) continue
                val systemsArr = c.optJSONArray("systems")
                if (systemsArr != null && systemsArr.length() > 0) {
                    var forWindows = false
                    for (j in 0 until systemsArr.length()) {
                        if (systemsArr.getString(j).equals("Windows", ignoreCase = true)) {
                            forWindows = true
                            break
                        }
                    }
                    if (!forWindows) continue
                }
                val cmdLangs = mutableListOf<String>()
                val cmdLangArr = c.optJSONArray("languages")
                if (cmdLangArr != null) {
                    for (j in 0 until cmdLangArr.length()) cmdLangs.add(cmdLangArr.getString(j))
                }
                supportCommands.add(
                    SupportCommand(
                        executable = executable,
                        gameId = c.optString("gameID", ""),
                        argument = c.optString("argument", ""),
                        languages = cmdLangs,
                    ),
                )
            }
        }

        Timber.tag(TAG).d(
            "Parsed Gen 1 manifest: installDirectory=$installDirectory, depots=${depots.size}, timestamp=$timestamp, supportCommands=${supportCommands.size}",
        )
        return GOGManifestMeta(
            baseProductId = baseProductId,
            installDirectory = installDirectory,
            depots = depots,
            dependencies = dependencies,
            products = products,
            productTimestamp = timestamp,
            supportCommands = supportCommands
        )
    }

    fun parseV1DepotManifest(json: String): List<V1DepotFile> {
        val obj = JSONObject(json)
        val depot = obj.optJSONObject("depot") ?: obj
        val filesArray = depot.optJSONArray("files") ?: return emptyList()
        val list = mutableListOf<V1DepotFile>()
        for (i in 0 until filesArray.length()) {
            val f = filesArray.getJSONObject(i)
            if (f.has("directory")) continue
            val path = f.optString("path", "").replace("\\", "/").removePrefix("/")
            val size = f.optLong("size", 0)
            val hash = f.optString("hash", "")
            val url = if (f.has("url") && !f.isNull("url")) f.getString("url") else null
            val offset = if (f.has("offset") && !f.isNull("offset")) f.optLong("offset", 0) else null
            val isSupport = f.optBoolean("support", false)
            list.add(
                V1DepotFile(
                    path = path,
                    size = size,
                    hash = hash,
                    url = url,
                    offset = offset,
                    isSupport = isSupport
                )
            )
        }
        return list
    }

    fun parseDepotManifest(json: String): DepotManifest {
        return DepotManifest.fromJson(JSONObject(json))
    }

    fun parseSecureLinks(json: String): SecureLinksResponse {
        return SecureLinksResponse.fromJson(JSONObject(json))
    }

    fun decompressManifest(data: ByteArray): String {
        val isGzipped = data.size >= 2 &&
            data[0] == 0x1f.toByte() &&
            data[1] == 0x8b.toByte()

        val isZlib = data.size >= 2 &&
            data[0] == 0x78.toByte() &&
            (
                data[1] == 0x9c.toByte() ||
                    data[1] == 0x01.toByte() ||
                    data[1] == 0xda.toByte()
                )

        return when {
            isGzipped -> {
                GZIPInputStream(ByteArrayInputStream(data)).use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
            }

            isZlib -> {
                val inflater = Inflater()
                try {
                    inflater.setInput(data)
                    val outputStream = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)

                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count > 0) {
                            outputStream.write(buffer, 0, count)
                        } else if (inflater.needsInput()) {
                            throw Exception("Incomplete or malformed zlib data: decompression ended prematurely")
                        }
                    }

                    outputStream.toString("UTF-8")
                } finally {
                    inflater.end()
                }
            }

            else -> {
                String(data, Charsets.UTF_8)
            }
        }
    }
}
