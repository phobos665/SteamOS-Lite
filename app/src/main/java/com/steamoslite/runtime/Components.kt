package com.steamoslite.runtime

import android.content.Context
import android.net.Uri
import com.steamoslite.util.FileUtils
import com.steamoslite.util.TarZstd
import java.io.File

/**
 * Versions of a Windows component games can use instead of the one their Proton ships, as
 * GameNative lets you choose them. The APK carries GameNative's packages under assets/<kind>/ as
 * <kind>-<version>.tzst, and more can be imported (a GameNative .tzst or Winlator .wcp). Each
 * version is unpacked to filesDir/<kind>/<version>/, which the guest sees at the same path; the
 * Proton launchers (bannerlator-steam-compat) put the chosen one in front of the game.
 */
abstract class ComponentStore(val kind: String) {
    private val bundledName = Regex("""^$kind-(.+)\.tzst$""")

    /** Unpacks what the session needs out of an unpacked package into [dir], or throws. */
    protected abstract fun keep(unpacked: File, dir: File)

    /** Whether [dir] holds a usable unpacked version. */
    protected abstract fun isComplete(dir: File): Boolean

    fun root(context: Context) = File(context.filesDir, kind)

    /** The versions the APK carries, oldest first. */
    fun bundled(context: Context): List<String> =
        context.assets.list(kind).orEmpty().mapNotNull { bundledName.find(it)?.groupValues?.get(1) }.sorted()

    fun imported(context: Context): List<String> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && File(it, ".imported").isFile }
            .map { it.name }

    /** Bundled and imported versions, oldest first. */
    fun available(context: Context): List<String> = (bundled(context) + imported(context)).distinct().sorted()

    fun selected(context: Context): String =
        prefs(context).getString(kind, PROTONS_OWN).orEmpty().takeIf { it == PROTONS_OWN || it in available(context) }
            ?: PROTONS_OWN

    fun select(context: Context, version: String) {
        // commit, not apply: the session runs in its own process and reads the file when it starts.
        prefs(context).edit().putString(kind, version).commit()
    }

    /**
     * Unpacks the bundled versions not unpacked yet, so any of them can also be picked for one game
     * with a Steam launch option. Returns the directory the guest reads them from.
     */
    fun prepare(context: Context): File {
        val root = root(context)
        for (version in bundled(context)) {
            val dir = File(root, version)
            if (isComplete(dir)) continue
            val staging = File(root, ".$version")
            FileUtils.delete(staging)
            FileUtils.delete(dir)
            TarZstd.extractAsset(context, "$kind/$kind-$version.tzst", staging)
            keep(staging, dir)
            FileUtils.delete(staging)
        }
        return root
    }

    fun looksLikePackage(name: String) = IMPORTABLE.containsMatchIn(name)

    /** Unpacks a package the user picked; returns the version it is listed as (its file name). */
    fun import(context: Context, uri: Uri, name: String): String {
        val version = name.replace(IMPORTABLE, "").removePrefix("$kind-")
            .replace(Regex("""[^A-Za-z0-9._+-]"""), "_").trim('.').ifEmpty { "imported" }
        val root = root(context)
        val staging = File(root, ".import")
        FileUtils.delete(staging)
        try {
            context.contentResolver.openInputStream(uri)!!.use { TarZstd.extract(it, staging) }
            val dir = File(root, version)
            FileUtils.delete(dir)
            keep(staging, dir)
            File(dir, ".imported").writeText(name)
        } finally {
            FileUtils.delete(staging)
        }
        return version
    }

    fun remove(context: Context, version: String) {
        require(version in imported(context)) { "not an imported version: $version" }
        FileUtils.delete(File(root(context), version))
        if (prefs(context).getString(kind, PROTONS_OWN) == version) select(context, PROTONS_OWN)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    companion object {
        const val PROTONS_OWN = ""
        private val IMPORTABLE = Regex("""\.(tzst|tar\.zst|wcp)$""", RegexOption.IGNORE_CASE)

        /** A real file of a package, not one of the macOS "._" resource forks some carry. */
        fun isPayload(file: File) = file.isFile && !file.name.startsWith("._") && file.name.endsWith(".dll", true)
    }
}

/**
 * The FEXCore x86 emulator: libarm64ecfex.dll for 64-bit code and libwow64fex.dll for 32-bit, which
 * every ARM64 Proton links into a game prefix's system32. The launchers copy the chosen pair over
 * those before each start and put Proton's own back when it is chosen again - GameNative's swap.
 */
object FexCore : ComponentStore("fexcore") {
    val DLLS = listOf("libarm64ecfex.dll", "libwow64fex.dll")

    override fun isComplete(dir: File) = DLLS.all { File(dir, it).isFile }

    /** The two DLLs, wherever the package keeps them (a .wcp nests them in system32/). */
    override fun keep(unpacked: File, dir: File) {
        val found = unpacked.walkTopDown().filter { isPayload(it) && it.name in DLLS }.associateBy { it.name }
        val missing = DLLS - found.keys
        require(missing.isEmpty()) { "not a FEXCore package: no " + missing.joinToString(" or ") }
        dir.mkdirs()
        found.values.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    }
}

/**
 * DXVK, Direct3D 8-11 on Vulkan. Proton copies its own DXVK into a game's prefix at every start, so
 * a swap in the prefix would not survive; the launchers instead run Proton from a mirror of its
 * tree whose DXVK directory holds the chosen version, and Proton installs that one itself.
 * Packages keep 64-bit DLLs in system32/ and 32-bit ones in syswow64/, the layout kept here.
 */
object Dxvk : ComponentStore("dxvk") {
    private val ARCHES = listOf("system32", "syswow64")

    override fun isComplete(dir: File) = File(dir, "system32/d3d11.dll").isFile

    override fun keep(unpacked: File, dir: File) {
        val base = unpacked.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == "system32" && File(it, "d3d11.dll").isFile }?.parentFile
        requireNotNull(base) { "not a DXVK package: no system32/d3d11.dll" }
        for (arch in ARCHES) {
            val from = File(base, arch)
            if (!from.isDirectory) continue
            val to = File(dir, arch).apply { mkdirs() }
            from.listFiles().orEmpty().filter { isPayload(it) }.forEach { it.copyTo(File(to, it.name), overwrite = true) }
        }
    }
}
