package com.steamoslite.util

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Unpacks the small .tzst bundles the APK carries (the PulseAudio modules, the Turnip driver). */
object TarZstd {
    fun extractAsset(context: Context, asset: String, destination: File) =
        context.assets.open(asset).use { extract(it, destination) }

    /** Unpacks a zstd-compressed tar read from [input] into [destination]. */
    fun extract(input: InputStream, destination: File) {
        destination.mkdirs()
        val base = destination.canonicalPath + File.separator
        input.let { raw ->
            TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
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
