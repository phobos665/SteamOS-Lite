package com.steamoslite.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.steamoslite.util.Downloader
import com.steamoslite.util.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * The Proton builds a game can be run with, besides Valve's ARM64 Proton.
 *
 * Installing one is the session's job, not the app's: the runtime's bannerlator-proton-extra
 * unpacks a build into Steam's compatibilitytools.d at the next session start, and the registrar
 * (bannerlator-steam-compat) patches it to run without the Steam Linux Runtime container, which
 * Android cannot provide. It then shows in Steam's Compatibility menu as "<name> (Bannerlator)".
 * So the app only queues work: one bannerlator-proton-extra request per line of ~/.bl-proton-extra.
 *
 * Only native ARM64 Linux builds can run here; an x86_64 Proton cannot start.
 */
object Protons {
    /** Bannerlator's catalog of ARM64 Proton builds, beside its runtime catalog. */
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linux-protons.json"

    /** Our own tool, which runs Valve's ARM64 Proton; not removable. */
    private const val OWN_TOOL = "bannerlator-proton-arm64"
    private val VALVE_ENGINES = listOf("Proton Experimental (ARM64)", "Proton 11.0 (ARM64)")
    private val ARCHIVE = Regex("""\.(tar\.gz|tgz|tar\.xz|txz|tar\.zst|tzst|tar)$""", RegexOption.IGNORE_CASE)

    data class Installed(val dir: String, val display: String)

    data class CatalogBuild(val display: String, val dir: String, val notes: String, val size: Long, val request: String)

    private fun root(context: Context) = LinuxRuntime.rootDir(context)
    private fun steam(context: Context) = File(root(context), "root/.local/share/Steam")
    private fun tools(context: Context) = File(steam(context), "compatibilitytools.d")
    private fun requests(context: Context) = File(root(context), "root/.bl-proton-extra")

    /** Where imported archives wait for the session; the app's files dir is bound at its own path. */
    private fun importDir(context: Context) = File(context.filesDir, "protons")

    /** Builds unpacked into compatibilitytools.d, other than ours. */
    fun installed(context: Context): List<Installed> =
        tools(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name != OWN_TOOL && File(it, "toolmanifest.vdf").isFile }
            .map { Installed(it.name, displayName(it)) }
            .sortedBy { it.display.lowercase() }

    /** Which of Valve's ARM64 Protons Steam has downloaded - the engine behind Bannerlator Proton. */
    fun valveEngines(context: Context): List<String> =
        VALVE_ENGINES.filter { File(steam(context), "steamapps/common/$it/proton").isFile }

    /** Requests waiting for the next session start, as written. */
    fun queued(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()

    /** How a queued request reads to a person: an imported file's name, or the catalog entry. */
    fun describe(request: String): String =
        if (request.startsWith("/")) File(request).name else request.replace(" --tag ", " ")

    fun cancel(context: Context, request: String) {
        writeRequests(context, queued(context) - request)
        cleanImports(context)
    }

    fun remove(context: Context, dir: String) {
        require(dir != OWN_TOOL && !dir.contains('/')) { "not a removable build: $dir" }
        FileUtils.delete(File(tools(context), dir))
    }

    /** The catalog's builds, or empty when it cannot be reached. */
    fun catalog(): List<CatalogBuild> {
        val body = Downloader.downloadString(CATALOG_URL) ?: return emptyList()
        return runCatching {
            val rows = JSONObject(body).getJSONArray("protons")
            (0 until rows.length()).mapNotNull { i ->
                val o = rows.getJSONObject(i)
                if (o.optString("kind", "tarball") != "tarball") return@mapNotNull null
                val url = o.optString("url")
                CatalogBuild(
                    display = o.optString("display", o.optString("name")),
                    dir = o.optString("dir"),
                    notes = o.optString("notes"),
                    size = o.optLong("size"),
                    request = requestFor(url) ?: return@mapNotNull null,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The bannerlator-proton-extra request for a catalog URL: the project's own name and release tag
     * where it is one the script knows (it then checks the project's published sha512), the URL
     * itself otherwise.
     */
    private fun requestFor(url: String): String? {
        if (url.isEmpty()) return null
        val tag = Regex("""/releases/download/([^/]+)/""").find(url)?.groupValues?.get(1)
        return when {
            tag != null && "GloriousEggroll/proton-ge-custom" in url -> "ge --tag $tag"
            tag != null && "CachyOS/proton-cachyos" in url -> "cachyos --tag $tag"
            else -> url
        }
    }

    fun queueCatalog(context: Context, build: CatalogBuild) {
        if (build.request !in queued(context)) writeRequests(context, queued(context) + build.request)
    }

    /** The picked file's name, or null. */
    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun looksLikeArchive(name: String) = ARCHIVE.containsMatchIn(name)

    /**
     * Copies a Proton archive the user picked into the app's storage and queues it. [onProgress]
     * gets bytes copied so far; the archive is hundreds of megabytes.
     */
    fun import(context: Context, uri: Uri, name: String, onProgress: (Long) -> Unit) {
        val dir = importDir(context).apply { mkdirs() }
        val safe = name.replace(Regex("""[^A-Za-z0-9._+-]"""), "_")
        val target = File(dir, safe)
        val partial = File(dir, "$safe.part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            partial.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var copied = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (copied - reported >= 8L shl 20) {
                        reported = copied
                        onProgress(copied)
                    }
                }
                onProgress(copied)
            }
        }
        if (!partial.renameTo(target)) error("could not keep the imported file")
        if (target.path !in queued(context)) writeRequests(context, queued(context) + target.path)
    }

    /** Imported archives the session has installed (its request line is gone) are dead weight. */
    fun cleanImports(context: Context) {
        val wanted = queued(context).toSet()
        importDir(context).listFiles()?.forEach { if (it.path !in wanted) it.delete() }
    }

    private fun writeRequests(context: Context, lines: List<String>) {
        val file = requests(context)
        file.parentFile?.mkdirs()
        FileUtils.writeString(file, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    /** The name Steam shows: the tool's own display_name, else its directory. */
    private fun displayName(dir: File): String {
        val vdf = FileUtils.readString(File(dir, "compatibilitytool.vdf")) ?: return dir.name
        return Regex(""""display_name"\s+"([^"]+)"""").find(vdf)?.groupValues?.get(1) ?: dir.name
    }
}
