package com.steamoslite.stores.epic

import android.content.Context
import com.steamoslite.stores.DownloadProgress
import com.steamoslite.stores.Installation
import com.steamoslite.stores.Net
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.epic.manifest.ChunkInfo
import com.steamoslite.stores.epic.manifest.ChunkPart
import com.steamoslite.stores.epic.manifest.EpicManifest
import com.steamoslite.stores.epic.manifest.FileManifest
import com.steamoslite.stores.epic.manifest.ManifestUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.Inflater

object EpicDownloader {
    private const val PARALLEL = 6
    private const val MAX_CHUNK_RETRIES = 3
    private const val RETRY_DELAY_MS = 1000L
    private const val CDN_USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"

    /**
     * Downloads [game]'s live build into [installDir] and returns how it starts. Files already on
     * disk with the manifest's SHA-1 are kept, so an interrupted download resumes.
     */
    suspend fun download(
        context: Context,
        game: StoreGame,
        installDir: File,
        guestDir: String,
        progress: DownloadProgress,
        language: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
    ): Installation = withContext(Dispatchers.IO) {
        progress.stage = "Fetching the manifest"
        val manifestData = EpicManager.fetchManifest(context, game).getOrThrow()
        val cdnUrls = manifestData.cdnUrls.filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            .ifEmpty { manifestData.cdnUrls }
        val manifest = EpicManifest.readAll(manifestData.manifestBytes)
        val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, EpicConstants.containerLanguageToEpicInstallTags(language))
        if (files.isEmpty()) throw Exception("The manifest lists no files for this game")
        val chunkDir = manifest.getChunkDir()

        installDir.mkdirs()
        progress.stage = "Checking existing files"
        val pending = files.filter { file ->
            progress.checkActive()
            !fileExistsWithCorrectHash(File(installDir, file.filename), file.fileSize, file.hash)
        }

        // Each chunk may feed parts of several files; every part is written straight to its file
        // at its offset once the chunk arrives, after which the cached chunk is no longer needed.
        val consumers = LinkedHashMap<String, MutableList<Pair<FileManifest, ChunkPart>>>()
        for (file in pending) {
            for (part in file.chunkParts) consumers.getOrPut(part.guidStr) { mutableListOf() } += file to part
        }
        val chunks = consumers.keys.map {
            manifest.chunkDataList?.getChunkByGuid(it) ?: throw IllegalStateException("Chunk $it referenced by a file but not in the manifest")
        }
        progress.bytesTotal = chunks.sumOf { it.fileSize }
        progress.bytesDone.set(0)

        for (file in pending) {
            val out = File(installDir, file.filename)
            out.parentFile?.mkdirs()
            RandomAccessFile(out, "rw").use { it.setLength(file.fileSize) }
        }

        val cacheDir = File(context.cacheDir, "epic_chunks/${game.id}").apply { mkdirs() }
        val http = Net.httpForParallelDownloads(PARALLEL)
        val gate = Semaphore(PARALLEL)
        progress.stage = "Downloading"
        coroutineScope {
            chunks.map { chunk ->
                async {
                    gate.withPermit {
                        progress.checkActive()
                        val cached = downloadChunkWithRetry(chunk, cacheDir, chunkDir, cdnUrls, progress, http)
                        for ((file, part) in consumers.getValue(chunk.guidStr)) writePart(cached, part, File(installDir, file.filename))
                        cached.delete()
                    }
                }
            }.awaitAll()
        }
        cacheDir.deleteRecursively()

        val meta = manifest.meta
        val exe = meta?.launchExe?.replace('\\', '/')?.trimStart('/').orEmpty()
            .ifEmpty { guessExecutable(installDir) }
        Installation(
            guestDir = guestDir,
            exe = exe,
            args = meta?.launchCommand.orEmpty(),
            sizeBytes = files.sumOf { it.fileSize },
            version = meta?.buildVersion.orEmpty(),
        )
    }

    private fun guessExecutable(installDir: File): String =
        installDir.walk()
            .filter { it.extension.equals("exe", ignoreCase = true) }
            .filterNot { it.name.contains("CrashHandler", true) || it.name.contains("CEFSubProcess", true) }
            .minByOrNull { it.absolutePath.length }
            ?.relativeTo(installDir)?.path ?: ""

    private suspend fun downloadChunkWithRetry(
        chunk: ChunkInfo,
        cacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        progress: DownloadProgress,
        http: OkHttpClient,
    ): File {
        var last: Exception? = null
        repeat(MAX_CHUNK_RETRIES) { attempt ->
            progress.checkActive()
            try {
                return downloadChunk(chunk, cacheDir, chunkDir, cdnUrls, progress, http)
            } catch (e: Exception) {
                last = e
                if (attempt < MAX_CHUNK_RETRIES - 1) delay(RETRY_DELAY_MS * (1 shl attempt))
            }
        }
        throw last ?: Exception("Failed to download chunk ${chunk.guidStr}")
    }

    private fun downloadChunk(
        chunk: ChunkInfo,
        cacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        progress: DownloadProgress,
        http: OkHttpClient,
    ): File {
        val target = File(File(cacheDir, chunk.guidStr.take(2)).apply { mkdirs() }, chunk.guidStr)
        if (target.exists() && target.length() == chunk.windowSize.toLong() && sha1(target).contentEquals(chunk.shaHash)) {
            progress.bytesDone.addAndGet(chunk.fileSize)
            return target
        }
        var last: Exception? = null
        for (cdn in cdnUrls) {
            try {
                val request = Request.Builder()
                    .url("${cdn.baseUrl}${cdn.cloudDir}/${chunk.getPath(chunkDir)}")
                    .header("User-Agent", CDN_USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code} from ${cdn.baseUrl}")
                    response.body!!.byteStream().use { decompressChunk(it, target, chunk.windowSize.toLong(), chunk.shaHash, progress) }
                }
                return target
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("All CDNs failed for chunk ${chunk.guidStr}")
    }

    /**
     * Reads a chunk file from the CDN (a header, then the data, zlib-compressed when bit 0 of
     * storedAs is set) into [outputFile], checking its size and SHA-1 as it goes.
     */
    private fun decompressChunk(input: InputStream, outputFile: File, expectedSize: Long, expectedHash: ByteArray, progress: DownloadProgress) {
        val digest = MessageDigest.getInstance("SHA-1")
        var written = 0L
        input.buffered().use { stream ->
            val head = ByteArray(12)
            readFully(stream, head)
            val start = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (start.int != 0xB1FE3AA2.toInt()) throw Exception("Invalid chunk magic")
            val headerVersion = start.int
            val headerSize = start.int
            if (headerSize !in 62..66) throw Exception("Invalid chunk header size $headerSize")
            val rest = ByteArray(headerSize - 12)
            readFully(stream, rest)
            // After magic, version and size: compressed size (4), GUID (16), rolling hash (8),
            // storedAs (1), then from version 2 the SHA-1 (20) and hash type (1), and from version
            // 3 the uncompressed size (4).
            val header = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
            val compressedSize = header.int
            header.position(header.position() + 24)
            val compressed = (header.get().toInt() and 0x1) == 0x1
            if (headerVersion >= 2) header.position(header.position() + minOf(21, header.remaining()))
            val uncompressedSize = if (headerVersion >= 3 && header.remaining() >= 4) header.int.toLong() else expectedSize

            outputFile.outputStream().buffered().use { out ->
                val inBuf = ByteArray(65536)
                val outBuf = ByteArray(65536)
                if (compressed) {
                    val inflater = Inflater()
                    try {
                        var ended = false
                        while (written < uncompressedSize && !ended) {
                            if (inflater.needsInput()) {
                                val n = stream.read(inBuf)
                                if (n == -1) ended = true else {
                                    progress.bytesDone.addAndGet(n.toLong())
                                    inflater.setInput(inBuf, 0, n)
                                }
                            }
                            val count = inflater.inflate(outBuf)
                            if (count > 0) {
                                out.write(outBuf, 0, count)
                                digest.update(outBuf, 0, count)
                                written += count
                            } else if (inflater.finished() || ended) {
                                break
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                } else {
                    var remaining = compressedSize
                    while (remaining > 0) {
                        val n = stream.read(inBuf, 0, minOf(remaining, inBuf.size))
                        if (n == -1) break
                        progress.bytesDone.addAndGet(n.toLong())
                        out.write(inBuf, 0, n)
                        digest.update(inBuf, 0, n)
                        written += n
                        remaining -= n
                    }
                }
            }
        }
        if (written != expectedSize || !digest.digest().contentEquals(expectedHash)) {
            outputFile.delete()
            throw Exception("Chunk failed verification ($written of $expectedSize bytes)")
        }
    }

    private fun readFully(stream: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = stream.read(into, off, into.size - off)
            if (n == -1) throw Exception("Chunk ended inside its header")
            off += n
        }
    }

    private fun writePart(chunkFile: File, part: ChunkPart, outputFile: File) {
        RandomAccessFile(chunkFile, "r").use { input ->
            input.seek(part.offset.toLong())
            FileChannel.open(outputFile.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.position(part.fileOffset)
                val buffer = ByteArray(65536)
                var remaining = part.size.toLong()
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n == -1) break
                    val bb = ByteBuffer.wrap(buffer, 0, n)
                    while (bb.hasRemaining()) channel.write(bb)
                    remaining -= n
                }
            }
        }
    }

    private fun fileExistsWithCorrectHash(file: File, expectedSize: Long, expectedHash: ByteArray): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        if (expectedHash.all { it == 0.toByte() }) return false
        return runCatching { sha1(file).contentEquals(expectedHash) }.getOrDefault(false)
    }

    private fun sha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }
}
