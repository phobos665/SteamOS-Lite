package com.steamoslite.stores.gog

import android.content.Context
import com.steamoslite.stores.DownloadCancelled
import com.steamoslite.stores.DownloadProgress
import com.steamoslite.stores.Installation
import com.steamoslite.stores.Net
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.gog.api.Depot
import com.steamoslite.stores.gog.api.DepotDirectory
import com.steamoslite.stores.gog.api.DepotFile
import com.steamoslite.stores.gog.api.DepotLink
import com.steamoslite.stores.gog.api.FileChunk
import com.steamoslite.stores.gog.api.GOGApiClient
import com.steamoslite.stores.gog.api.GOGManifestParser
import com.steamoslite.stores.gog.api.V1DepotFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.Inflater

object GOGDownloader {
    private const val PARALLEL = 6
    private const val MAX_CHUNK_RETRIES = 4
    private const val RETRY_DELAY_MS = 1000L
    private const val PLACEHOLDER_PRODUCT_ID = "2147483047"

    private class HttpStatus(val code: Int) : Exception("HTTP $code")

    /**
     * Downloads [game]'s Windows build into [installDir] and returns how it starts. Files already on
     * disk with the manifest's MD5 are kept, so a download resumes.
     */
    suspend fun download(
        context: Context,
        game: StoreGame,
        installDir: File,
        guestDir: String,
        progress: DownloadProgress,
        language: String = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
    ): Installation = withContext(Dispatchers.IO) {
        val parser = GOGManifestParser()
        val api = GOGApiClient(context, parser)

        progress.stage = "Fetching builds"
        val build = parser.selectBuild(api.getBuildsForGame(game.id, "windows", generation = 2).getOrThrow().items, 2, "windows")
            ?: parser.selectBuild(api.getBuildsForGame(game.id, "windows", generation = 1).getOrThrow().items, 1, "windows")
            ?: throw Exception("GOG has no Windows build of this game")

        progress.stage = "Fetching the manifest"
        val manifest = api.fetchManifest(build.link).getOrThrow()
        val owned = GOGLibraryClient.getGameIds(context).getOrThrow().toSet()
        val depots = parser.filterDepotsByOwnership(parser.filterDepotsByLanguage(manifest, language).first, owned)
        if (depots.isEmpty()) throw Exception("No depots of this game are owned for this language")
        val dlc = manifest.products
            .filter { p -> p.productId != manifest.baseProductId && depots.any { it.productId == p.productId } }
            .map { it.name }

        val timestamp = manifest.productTimestamp
        if (build.generation == 1 && timestamp != null) {
            val size = downloadGen1(api, parser, depots, build.platform, timestamp, installDir, progress)
            return@withContext installation(installDir, game, guestDir, size, build.versionName, dlc)
        }

        data class Owned(val file: DepotFile, val productId: String)
        val all = mutableListOf<Owned>()
        val links = mutableListOf<DepotLink>()
        val directories = mutableListOf<DepotDirectory>()
        for ((index, depot) in depots.withIndex()) {
            progress.checkActive()
            progress.stage = "Fetching depot ${index + 1} of ${depots.size}"
            val depotManifest = api.fetchDepotManifest(depot.manifest).getOrThrow()
            depotManifest.files.forEach { all += Owned(it, depot.productId) }
            links += depotManifest.links
            directories += depotManifest.directories
        }

        // The game and its owned DLC share one directory; where both list a path, the later depot
        // (the DLC) wins. Support files (redistributable installers) land in the game directory
        // with their leading "app/" removed, as Galaxy lays them out.
        val depotFiles = all.map { it.file }.associateBy { it.path }.values.toList()
        val productOf = all.associate { (file, depotProduct) ->
            file.path to (file.productId?.takeIf { it != PLACEHOLDER_PRODUCT_ID } ?: depotProduct)
        }
        val files = depotFiles.map { if (it.isSupportFile() && it.path.startsWith("app/")) it.copy(path = it.path.removePrefix("app/")) else it }
        val originalPath = depotFiles.zip(files).associate { (orig, remapped) -> remapped.path to orig.path }

        installDir.mkdirs()
        progress.stage = "Checking existing files"
        val pending = files.filter { file ->
            progress.checkActive()
            !fileMatches(File(installDir, file.path), file.chunks.sumOf { it.size }, file.md5)
        }

        // A chunk (by compressed MD5) may be used by several files, each at the offset where it
        // falls in that file: the sum of the sizes of the chunks before it.
        data class Use(val file: DepotFile, val chunk: FileChunk, val offset: Long)
        val uses = LinkedHashMap<String, MutableList<Use>>()
        val productOfChunk = HashMap<String, String>()
        for (file in pending) {
            var offset = 0L
            for (chunk in file.chunks) {
                uses.getOrPut(chunk.compressedMd5) { mutableListOf() } += Use(file, chunk, offset)
                productOfChunk[chunk.compressedMd5] = productOf[originalPath[file.path]] ?: manifest.baseProductId
                offset += chunk.size
            }
        }
        val chunkSize = uses.mapValues { (_, list) -> list.first().chunk.compressedSize ?: list.first().chunk.size }
        progress.bytesTotal = chunkSize.values.sum()
        progress.bytesDone.set(0)

        for (file in pending) {
            val out = File(installDir, file.path)
            out.parentFile?.mkdirs()
            RandomAccessFile(out, "rw").use { it.setLength(file.chunks.sumOf { c -> c.size }) }
        }

        val products = productOfChunk.values.toSet()
        val linkLock = Mutex()
        var productUrls = secureLinks(api, products)

        val cacheDir = File(context.cacheDir, "gog_chunks/${game.id}").apply { mkdirs() }
        val http = Net.httpForParallelDownloads(PARALLEL)
        val gate = Semaphore(PARALLEL)
        progress.stage = "Downloading"
        coroutineScope {
            uses.keys.map { md5 ->
                async {
                    gate.withPermit {
                        var attempt = 0
                        while (true) {
                            progress.checkActive()
                            val urls = productUrls[productOfChunk.getValue(md5)].orEmpty().map { chunkUrl(it, md5) }
                            try {
                                val cached = downloadChunk(md5, urls[attempt % urls.size], cacheDir, progress, http)
                                for (use in uses.getValue(md5)) decompressInto(cached, use.chunk, File(installDir, use.file.path), use.offset)
                                cached.delete()
                                break
                            } catch (e: Exception) {
                                if (e is DownloadCancelled || ++attempt >= MAX_CHUNK_RETRIES) throw e
                                // Secure links are time-limited; an expired one answers 401/403.
                                if (e is HttpStatus && (e.code == 401 || e.code == 403)) {
                                    linkLock.withLock { productUrls = secureLinks(api, products) }
                                }
                                delay(RETRY_DELAY_MS * (1 shl attempt))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        cacheDir.deleteRecursively()
        createDirectoriesAndLinks(installDir, directories, links)
        installation(installDir, game, guestDir, files.sumOf { f -> f.chunks.sumOf { it.size } }, build.versionName, dlc)
    }

    private fun installation(installDir: File, game: StoreGame, guestDir: String, size: Long, version: String, dlc: List<String>): Installation {
        val task = GOGManager.primaryPlayTask(installDir, game.id)
        return Installation(
            guestDir = guestDir,
            exe = task?.path ?: "",
            args = task?.arguments ?: "",
            workingDir = task?.workingDir ?: "",
            sizeBytes = size,
            version = version,
            dlc = dlc,
        )
    }

    /**
     * Older builds keep each depot as one main.bin: a file is the byte range [offset, offset+size)
     * of it, fetched with a Range request and checked against its MD5. Returns the install size.
     */
    private suspend fun downloadGen1(
        api: GOGApiClient,
        parser: GOGManifestParser,
        depots: List<Depot>,
        platform: String,
        timestamp: String,
        installDir: File,
        progress: DownloadProgress,
    ): Long {
        data class Entry(val file: V1DepotFile, val productId: String)
        val entries = mutableListOf<Entry>()
        for ((index, depot) in depots.withIndex()) {
            progress.checkActive()
            progress.stage = "Fetching depot ${index + 1} of ${depots.size}"
            val json = api.fetchDepotManifestV1(depot.productId, platform, timestamp, depot.manifest).getOrThrow()
            parser.parseV1DepotManifest(json).filter { !it.isSupport }.forEach { entries += Entry(it, depot.productId) }
        }
        if (entries.isEmpty()) throw Exception("The build lists no files")
        val files = entries.associateBy { it.file.path }.values.toList()

        installDir.mkdirs()
        progress.stage = "Checking existing files"
        val pending = files.filter { (file) ->
            progress.checkActive()
            !fileMatches(File(installDir, file.path), file.size, file.hash)
        }
        progress.bytesTotal = pending.sumOf { it.file.size }
        progress.bytesDone.set(0)

        val products = pending.map { it.productId }.toSet()
        suspend fun mainBins() = products.associateWith { product ->
            val base = api.getSecureLink(product, "/$platform/$timestamp/", generation = 1).getOrThrow().urls.firstOrNull()
                ?: throw Exception("GOG returned no download link")
            val q = base.indexOf('?')
            if (q < 0) base.trimEnd('/') + "/main.bin" else base.substring(0, q).trimEnd('/') + "/main.bin" + base.substring(q)
        }
        val linkLock = Mutex()
        var mainBin = mainBins()

        val http = Net.httpForParallelDownloads(PARALLEL)
        val gate = Semaphore(PARALLEL)
        progress.stage = "Downloading"
        coroutineScope {
            pending.map { (file, product) ->
                async {
                    gate.withPermit {
                        val out = File(installDir, file.path)
                        out.parentFile?.mkdirs()
                        if (file.size == 0L) {
                            out.writeBytes(ByteArray(0))
                            return@withPermit
                        }
                        val offset = file.offset ?: throw Exception("${file.path} has no offset in main.bin")
                        var attempt = 0
                        while (true) {
                            progress.checkActive()
                            try {
                                downloadRange(http, mainBin.getValue(product), offset, file.size, file.hash, out, progress)
                                break
                            } catch (e: Exception) {
                                if (e is DownloadCancelled || ++attempt >= MAX_CHUNK_RETRIES) throw e
                                if (e is HttpStatus && (e.code == 401 || e.code == 403)) {
                                    linkLock.withLock { mainBin = mainBins() }
                                }
                                delay(RETRY_DELAY_MS * (1 shl attempt))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return files.sumOf { it.file.size }
    }

    private fun downloadRange(http: OkHttpClient, url: String, offset: Long, size: Long, md5: String, out: File, progress: DownloadProgress) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "GOG Galaxy")
            .header("Range", "bytes=$offset-${offset + size - 1}")
            .build()
        val digest = MessageDigest.getInstance("MD5")
        var got = 0L
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatus(response.code)
                if (response.code != 206 && offset != 0L) throw Exception("The server ignored the byte range")
                response.body!!.byteStream().use { input ->
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            progress.checkActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            got += n
                            progress.bytesDone.addAndGet(n.toLong())
                        }
                    }
                }
            }
            if (got != size) throw Exception("${out.name}: got $got of $size bytes")
            if (md5.isNotEmpty() && !digest.digest().joinToString("") { "%02x".format(it) }.equals(md5, ignoreCase = true)) {
                throw Exception("${out.name} failed verification")
            }
        } catch (e: Exception) {
            progress.bytesDone.addAndGet(-got)
            out.delete()
            throw e
        }
    }

    private suspend fun secureLinks(api: GOGApiClient, products: Set<String>): Map<String, List<String>> =
        products.associateWith { api.getSecureLink(productId = it, path = "/", generation = 2).getOrThrow().urls }

    /** Chunk URLs are the secure link's base with the Galaxy path (ab/cd/abcd...) before its query. */
    private fun chunkUrl(base: String, md5: String): String {
        val path = "/${md5.substring(0, 2)}/${md5.substring(2, 4)}/$md5"
        val q = base.indexOf('?')
        return if (q < 0) base.trimEnd('/') + path else base.substring(0, q).trimEnd('/') + path + base.substring(q)
    }

    private fun downloadChunk(md5: String, url: String, cacheDir: File, progress: DownloadProgress, http: OkHttpClient): File {
        val target = File(File(cacheDir, md5.take(2)).apply { mkdirs() }, "$md5.chunk")
        if (target.exists() && md5Hex(target) == md5) return target
        val part = File(target.parentFile, "$md5.part")
        val digest = MessageDigest.getInstance("MD5")
        var got = 0L
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "GOG Galaxy").build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatus(response.code)
                response.body!!.byteStream().use { input ->
                    part.outputStream().buffered().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            progress.checkActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            got += n
                            progress.bytesDone.addAndGet(n.toLong())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            progress.bytesDone.addAndGet(-got)
            part.delete()
            throw e
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != md5) {
            progress.bytesDone.addAndGet(-got)
            part.delete()
            throw Exception("Chunk $md5 failed verification")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    /** Inflates a chunk (zlib, or stored as-is when it has no compressed size) into [outputFile] at [offset]. */
    private fun decompressInto(chunkFile: File, chunk: FileChunk, outputFile: File, offset: Long) {
        val digest = MessageDigest.getInstance("MD5")
        var written = 0L
        FileChannel.open(outputFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
            channel.position(offset)
            fun write(buf: ByteArray, n: Int) {
                digest.update(buf, 0, n)
                val bb = ByteBuffer.wrap(buf, 0, n)
                while (bb.hasRemaining()) channel.write(bb)
                written += n
            }
            chunkFile.inputStream().buffered().use { input ->
                val inBuf = ByteArray(65536)
                if (chunk.compressedSize == null) {
                    while (true) {
                        val n = input.read(inBuf)
                        if (n == -1) break
                        write(inBuf, n)
                    }
                } else {
                    val outBuf = ByteArray(65536)
                    val inflater = Inflater()
                    try {
                        while (!inflater.finished()) {
                            if (inflater.needsInput()) {
                                val n = input.read(inBuf)
                                if (n == -1) break
                                inflater.setInput(inBuf, 0, n)
                            }
                            val count = inflater.inflate(outBuf)
                            if (count > 0) write(outBuf, count) else if (inflater.needsDictionary()) throw Exception("Chunk needs a zlib dictionary")
                        }
                    } finally {
                        inflater.end()
                    }
                }
            }
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it) }
        if (written != chunk.size || md5 != chunk.md5) throw Exception("Chunk ${chunk.compressedMd5} inflated to the wrong data")
    }

    private fun createDirectoriesAndLinks(installDir: File, directories: List<DepotDirectory>, links: List<DepotLink>) {
        directories.forEach { dir ->
            val rel = dir.path.removePrefix("/")
            if (rel.isNotBlank()) File(installDir, rel).mkdirs()
        }
        links.forEach { link ->
            val rel = link.path.replace("\\", "/").removePrefix("/")
            if (rel.isBlank() || link.target.isBlank()) return@forEach
            try {
                val file = File(installDir, rel)
                file.parentFile?.mkdirs()
                if (file.exists() || Files.isSymbolicLink(file.toPath())) file.delete()
                Files.createSymbolicLink(file.toPath(), Paths.get(link.target))
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Failed to create symlink ${link.path}")
            }
        }
    }

    private fun fileMatches(file: File, size: Long, md5: String?): Boolean {
        if (!file.isFile || file.length() != size || md5.isNullOrBlank()) return false
        return runCatching { md5Hex(file).equals(md5, ignoreCase = true) }.getOrDefault(false)
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
