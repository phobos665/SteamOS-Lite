package com.steamoslite.util

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * [ResumableDownload] against a local server that misbehaves on purpose: connections that die
 * part-way, a server that ignores Range, and one that never answers.
 */
class ResumableDownloadTest {
    private val data = Random(7).nextBytes(1_000_000)
    private lateinit var server: HttpServer
    private lateinit var dir: File

    /** Range headers the server saw, one per request ("" for none). */
    private val ranges = CopyOnWriteArrayList<String>()
    /** Connections to cut: each is cut after this many body bytes, then removed. */
    private val cutAfter = CopyOnWriteArrayList<Int>()
    @Volatile private var honourRange = true
    @Volatile private var alwaysFail = false

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val range = ex.requestHeaders.getFirst("Range").orEmpty()
            ranges += range
            if (alwaysFail) {
                ex.sendResponseHeaders(503, -1)
                ex.close()
                return@createContext
            }
            val start = if (honourRange && range.startsWith("bytes=")) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val body = data.copyOfRange(start, data.size)
            if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            val cut = cutAfter.firstOrNull()?.also { cutAfter.removeAt(0) }
            try {
                if (cut != null) {
                    ex.responseBody.write(body, 0, minOf(cut, body.size))
                    ex.responseBody.flush()
                    // Close short of the declared length: the client sees the connection drop
                    // mid-body, as it would when the signal goes.
                    throw java.io.IOException("cut")
                }
                ex.responseBody.write(body)
            } catch (_: java.io.IOException) {
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/file"

    private class Recorder : ResumableDownload.Listener {
        val retries = CopyOnWriteArrayList<Int>()
        override fun onProgress(done: Long, total: Long) {}
        override fun onRetry(attempt: Int, delayMs: Long, reason: String) { retries += attempt }
    }

    private fun download(file: File, maxFailures: Int = 8) =
        ResumableDownload(url, file, data.size.toLong(), maxFailures) { 1L }

    @Test
    fun resumesAfterDroppedConnections() {
        cutAfter += 300_000
        cutAfter += 250_000
        val file = File(dir, "a")
        val rec = Recorder()
        assertTrue(download(file).run(rec) { false })
        assertArrayEquals(data, file.readBytes())
        // First request from zero, then two resumes from exactly where each cut left off.
        assertEquals(listOf("", "bytes=300000-", "bytes=550000-"), ranges.toList())
        assertEquals(2, rec.retries.size)
    }

    @Test
    fun resumesAPartialLeftByAnEarlierRun() {
        val file = File(dir, "b")
        file.writeBytes(data.copyOfRange(0, 400_000))
        assertTrue(download(file).run(Recorder()) { false })
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf("bytes=400000-"), ranges.toList())
    }

    @Test
    fun restartsWhenTheServerIgnoresRange() {
        honourRange = false
        val file = File(dir, "c")
        file.writeBytes(data.copyOfRange(0, 400_000))
        assertTrue(download(file).run(Recorder()) { false })
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun alreadyCompleteNeedsNoRequest() {
        val file = File(dir, "d")
        file.writeBytes(data)
        assertTrue(download(file).run(Recorder()) { false })
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun givesUpWithoutProgressAndKeepsThePartial() {
        val file = File(dir, "e")
        file.writeBytes(data.copyOfRange(0, 123_456))
        alwaysFail = true
        val rec = Recorder()
        assertFalse(download(file, maxFailures = 3).run(rec) { false })
        assertEquals(listOf(1, 2, 3), rec.retries.toList())
        // Kept, so the next attempt continues from here.
        assertEquals(123_456L, file.length())
    }

    @Test(expected = ResumableDownload.Cancelled::class)
    fun cancelStopsIt() {
        download(File(dir, "f")).run(Recorder()) { true }
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val first = ResumableDownload.defaultBackoff(1)
        assertTrue(first in 2_000L..2_400L)
        assertTrue(ResumableDownload.defaultBackoff(3) in 8_000L..9_600L)
        assertTrue(ResumableDownload.defaultBackoff(20) in 60_000L..72_000L)
    }
}
