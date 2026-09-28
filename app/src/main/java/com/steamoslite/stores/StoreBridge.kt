package com.steamoslite.stores

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.stores.epic.EpicGameLauncher
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Answers bl-store-launch while a session runs: an Epic game started from the Steam library
 * needs sign-in arguments minted at that moment, and only the app holds the account's tokens.
 * Listens on 127.0.0.1 only; the port and a per-session secret are left in the runtime for the
 * wrapper, so nothing else on the device can ask.
 */
class StoreBridge(context: Context) {
    private val context = context.applicationContext
    private val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private val file = File(LinuxRuntime.rootDir(this.context), "root/.bl-store-bridge")

    fun start() {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        file.writeText("${socket.localPort} $secret\n")
        file.setReadable(false, false)
        file.setReadable(true, true)
        thread(name = "StoreBridge", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "StoreBridgeRequest", isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        file.delete()
    }

    /** One request line: "<secret> epic <app name>"; the answer is one argument per line. */
    private fun handle(client: Socket) = client.use {
        runCatching {
            client.soTimeout = 60_000
            val line = client.getInputStream().bufferedReader().readLine() ?: return@runCatching
            val parts = line.trim().split(' ')
            val out = client.getOutputStream().bufferedWriter()
            if (parts.size != 3 || parts[0] != secret || parts[1] != "epic") {
                out.write("ERROR bad request\n")
            } else {
                val game = StoreLibrary(context, Store.EPIC).game(parts[2])
                val args = game?.let { runBlocking { EpicGameLauncher.buildLaunchParameters(context, it) } }
                when {
                    game == null -> out.write("ERROR unknown game\n")
                    args!!.isFailure -> out.write("ERROR ${args.exceptionOrNull()?.message?.replace('\n', ' ')}\n")
                    else -> args.getOrThrow().forEach { out.write(it.replace('\n', ' ') + "\n") }
                }
            }
            out.flush()
        }.onFailure { Timber.tag("Stores").w(it, "bridge request failed") }
    }
}
