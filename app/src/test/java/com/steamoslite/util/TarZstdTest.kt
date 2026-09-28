package com.steamoslite.util

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TarZstdTest {
    /** A Winlator .wcp is an XZ tar with the DLLs under system32/; it unpacks like a .tzst. */
    @Test
    fun unpacksAnXzPackage() {
        val bytes = ByteArrayOutputStream()
        XZOutputStream(bytes, LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                for ((name, body) in listOf("profile.json" to "{}", "system32/libarm64ecfex.dll" to "fex")) {
                    val data = body.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        val out = Files.createTempDirectory("wcp").toFile()
        try {
            TarZstd.extract(ByteArrayInputStream(bytes.toByteArray()), out)
            assertEquals("fex", File(out, "system32/libarm64ecfex.dll").readText())
            assertEquals("{}", File(out, "profile.json").readText())
        } finally {
            out.deleteRecursively()
        }
    }
}
