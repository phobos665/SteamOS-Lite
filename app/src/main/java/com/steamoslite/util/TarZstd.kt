package com.steamoslite.util

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Unpacks the small .tzst bundles the APK carries (the PulseAudio modules, the Turnip driver). */
object TarZstd {
    fun extractAsset(context: Context, asset: String, destination: File) =
        context.assets.open(asset).use { extract(it, destination) }

    /**
     * Unpacks a compressed tar read from [input] into [destination]: zstd, or XZ, which is what
     * many Winlator .wcp packages use. Told apart by their magic bytes, not the file name.
     */
    fun extract(input: InputStream, destination: File) {
        destination.mkdirs()
        val base = destination.canonicalPath + File.separator
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(6)
        val magic = ByteArray(6)
        val read = buffered.read(magic)
        buffered.reset()
        val xz = read == 6 && magic.contentEquals(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(),
            'X'.code.toByte(), 'Z'.code.toByte(), 0))
        val decompressed = if (xz) XZCompressorInputStream(buffered) else ZstdCompressorInputStream(buffered)
        run {
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val file = File(destination, entry.name)
                    if (!(file.canonicalPath + File.separator).startsWith(base) &&
                        file.canonicalPath != destination.canonicalPath
                    ) throw IOException("entry outside the destination: ${entry.name}")
                    when {
                        entry.isDirectory -> file.mkdirs()
                        entry.isSymbolicLink -> {
                            file.parentFile?.mkdirs()
                            FileUtils.symlink(entry.linkName, file.path)
                        }
                        entry.isFile -> {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { tar.copyTo(it) }
                            if ((entry.mode and "111".toInt(8)) != 0) file.setExecutable(true, false)
                        }
                    }
                }
            }
        }
    }
}
