package com.steamoslite.runtime

import android.content.Context
import android.net.Uri
import com.steamoslite.util.FileUtils
import com.steamoslite.util.TarZstd
import java.io.File

/**
 * The FEXCore x86 emulator a game's Proton runs x86 code with, as GameNative lets you choose it.
 *
 * Every ARM64 Proton ships its own FEXCore (libarm64ecfex.dll for 64-bit code, libwow64fex.dll for
 * 32-bit), linked into each game prefix's system32. Choosing another version here makes the Proton
 * launchers (bannerlator-steam-compat) copy that pair over the prefix's before each game start, and
 * put Proton's own back when "Proton's own" is chosen again - the same swap GameNative makes.
 *
 * The APK carries GameNative's packages under assets/fexcore; more can be imported. Each version is
 * unpacked to filesDir/fexcore/<version>/, which the guest sees at the same path.
 */
object FexCore {
    const val PROTONS_OWN = ""
    val DLLS = listOf("libarm64ecfex.dll", "libwow64fex.dll")
    private const val PREFS = "session"
    private const val PREF = "fexcore"
    private val PACKAGE = Regex("""^fexcore-(.+)\.tzst$""")
    private val IMPORTABLE = Regex("""\.(tzst|tar\.zst|wcp)$""", RegexOption.IGNORE_CASE)

    fun root(context: Context) = File(context.filesDir, "fexcore")

    /** The versions the APK carries, oldest first. */
    fun bundled(context: Context): List<String> =
        context.assets.list("fexcore").orEmpty().mapNotNull { PACKAGE.find(it)?.groupValues?.get(1) }.sorted()

    /** Bundled and imported versions, oldest first. */
    fun available(context: Context): List<String> =
        (bundled(context) + imported(context)).distinct().sorted()

    fun imported(context: Context): List<String> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && File(it, ".imported").isFile }
            .map { it.name }

    fun selected(context: Context): String =
        prefs(context).getString(PREF, PROTONS_OWN).orEmpty().takeIf { it == PROTONS_OWN || it in available(context) }
            ?: PROTONS_OWN

    fun select(context: Context, version: String) {
        // commit, not apply: the session runs in its own process and reads the file when it starts.
        prefs(context).edit().putString(PREF, version).commit()
    }

    /**
     * Makes every version usable by the session: bundled ones are unpacked once (a Steam launch
     * option `BL_FEXCORE=<version> %command%` can pick any of them for one game). Returns the
     * directory the guest reads them from.
     */
    fun prepare(context: Context): File {
        val root = root(context)
        for (version in bundled(context)) {
            val dir = File(root, version)
            if (DLLS.all { File(dir, it).isFile }) continue
            val staging = File(root, ".$version")
            FileUtils.delete(staging)
            TarZstd.extractAsset(context, "fexcore/fexcore-$version.tzst", staging)
            keepDlls(staging, dir)
            FileUtils.delete(staging)
        }
        return root
    }

    fun looksLikePackage(name: String) = IMPORTABLE.containsMatchIn(name)

    /**
     * Unpacks a FEXCore package the user picked (a GameNative/Winlator .tzst or .wcp) and returns
     * the version it is listed as: its file name without "fexcore-" and the extension.
     */
    fun import(context: Context, uri: Uri, name: String): String {
        val version = name.replace(IMPORTABLE, "").removePrefix("fexcore-").removePrefix("FEXCore-")
            .replace(Regex("""[^A-Za-z0-9._+-]"""), "_").trim('.').ifEmpty { "imported" }
        val root = root(context)
        val staging = File(root, ".import")
        FileUtils.delete(staging)
        try {
            context.contentResolver.openInputStream(uri)!!.use { TarZstd.extract(it, staging) }
            val dir = File(root, version)
            FileUtils.delete(dir)
            keepDlls(staging, dir)
            File(dir, ".imported").writeText(name)
        } finally {
            FileUtils.delete(staging)
        }
        return version
    }

    fun remove(context: Context, version: String) {
        require(version in imported(context)) { "not an imported version: $version" }
        FileUtils.delete(File(root(context), version))
        if (prefs(context).getString(PREF, PROTONS_OWN) == version) select(context, PROTONS_OWN)
    }

    /**
     * Copies the two DLLs out of an unpacked package, wherever it keeps them (a .wcp nests them in
     * system32/), skipping the macOS "._" resource forks some packages carry.
     */
    private fun keepDlls(unpacked: File, dir: File) {
        val found = unpacked.walkTopDown().filter { it.isFile && it.name in DLLS }.associateBy { it.name }
        val missing = DLLS - found.keys
        require(missing.isEmpty()) { "not a FEXCore package: no " + missing.joinToString(" or ") }
        dir.mkdirs()
        found.values.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
