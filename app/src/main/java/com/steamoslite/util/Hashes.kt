package com.steamoslite.util

import java.io.File
import java.security.MessageDigest

object Hashes {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Whether [needle] appears in the file: how a library's libc (glibc "libc.so.6", bionic "libc.so") is told apart. */
    fun containsAscii(file: File, needle: String): Boolean {
        val bytes = file.readBytes()
        val n = needle.toByteArray()
        outer@ for (i in 0..bytes.size - n.size) {
            for (j in n.indices) if (bytes[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
