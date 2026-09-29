package com.steamoslite.runtime

import android.content.Context
import android.os.Environment
import android.util.Log
import com.steamoslite.input.FakeInputWriter
import com.steamoslite.stores.StoreBridge
import com.steamoslite.util.FileUtils
import com.steamoslite.util.TarZstd
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One SteamOS session: proot runs the runtime's session script, which starts gamescope as a Wayland
 * client of the compositor the session activity already brought up; gamescope then runs the Steam
 * client in Big Picture, optionally straight into one game.
 *
 * This is Bannerlator's XServerDisplayActivity.setupLinuxSession reduced to the Steam mode, with
 * everything that belonged to Wine, containers or the app's own Steam store taken out.
 */
class Session(
    context: Context,
    /** A Steam app id to launch straight into, or null for Big Picture. */
    private val appId: String?,
    private val width: Int,
    private val height: Int,
    private val refreshHz: Int,
    private val onExit: (Int) -> Unit,
) {
    private val context = context.applicationContext
    private val root = LinuxRuntime.rootDir(this.context)
    private val pulse = PulseAudio(this.context)
    private val network = NetworkLink(this.context, root)
    private var process: SessionProcess? = null
    private val storeBridge = StoreBridge(this.context)

    /** Told of each setup step as it finishes, for the startup timeline. */
    var onMark: (String) -> Unit = {}

    private fun mark(what: String) = onMark(what)

    /** Where the session's log goes; the folder to hand over when something goes wrong. */
    var logDir: File? = null
        private set

    fun start() {
        check(LinuxRuntime.isInstalled(context)) { "The SteamOS runtime is not installed." }
        LinuxRuntime.writeAccounts(context)

        // Refreshed every session, so what runs is always what this APK carries.
        TarZstd.extractAsset(context, "pulseaudio.tzst", PulseAudio.workingDir(context))
        mark("audio modules unpacked")
        stageSessionFiles()
        // Android has no /dev/shm; a cache directory stands in and keeps whatever a session leaves
        // (the client abandons tens of megabytes of streams every run). Cleared before each start.
        FileUtils.clear(File(context.cacheDir, "shm"))

        val runtimeDir = xdgRuntimeDir(context).apply { mkdirs() }
        val logs = openLogDir(context).also { logDir = it }
        // This process's Android log: the compositor, adrenotools and Vulkan report there, and its
        // earlier lines (the compositor starts before the session) are in the buffer already.
        startAppLog(File(logs, "app.log"))
        val fakeInputDir = fakeInputDir(context).apply { mkdirs() }
        FakeInputWriter.prepareRingSlots(fakeInputDir, 4)

        val guest = mutableListOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TZ=" + TimeZone.getDefault().id,
            "XDG_RUNTIME_DIR=" + runtimeDir.path,
            "XDG_SESSION_TYPE=wayland",
            "WAYLAND_DISPLAY=wayland-0",
            "GAMESCOPE_FORCE_GENERAL_QUEUE=1",
            // Steam's CEF needs GL and the rootfs ships no native GL driver: route it through Zink.
            "MESA_LOADER_DRIVER_OVERRIDE=zink",
            "GALLIUM_DRIVER=zink",
            "LIBGL_KOPPER_DRI2=true",
            "PULSE_SERVER=unix:" + PulseAudio.socket(context).path,
            "BL_WIDTH=$width",
            "BL_HEIGHT=$height",
            // Settings' frame rate limit: gamescope's refresh, which holds for the whole session,
            // menus included. 0 = the screen's rate.
            "BL_FPS=" + Settings.fpsLimit(context),
            "BL_LOG=" + File(logs, "session.log").path,
            "BL_DEBUG_DIR=" + logs.path,
            // Proton's own log of every game start (steam-<appid>.log) goes straight into this
            // session's log folder, so Share logs carries it; a launch that fails says why only
            // there. Settings can turn it off (PROTON_LOG unset).
            "PROTON_LOG_DIR=" + logs.path,
            // Controllers: libfakeinput.so (named in /etc/ld.so.preload) serves the app's rings as
            // /dev/input/eventN, as an Xbox 360 pad Steam and SDL know without configuration.
            "FAKE_EVDEV_DIR=" + fakeInputDir.path,
            "FAKE_EVDEV_IDENTITY=xbox360",
            "FAKE_EVDEV_VIBRATION=1",
            "FAKE_EVDEV_STEAM_VIRTUAL=1",
            // No udev runs in the runtime: SDL and Steam's hidapi scan /dev/input themselves.
            "SDL_JOYSTICK_DISABLE_UDEV=1",
            "SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1",
            "SDL_JOYSTICK_HIDAPI=0",
        )
        if (refreshHz > 1) guest += "BL_REFRESH=$refreshHz"
        // Steam pins its interface to a subset of cores; the session re-pins it to all of them
        // (measured 66 -> 90+ fps in the menus on a Pocket FIT) unless Settings says not to.
        if (Settings.clientAllCores(context)) {
            guest += "BL_CLIENT_CPUS=" + (0 until Runtime.getRuntime().availableProcessors()).joinToString(",")
        }
        // Games launched from the client run x86 code under FEX, with Settings' preset
        // (Intermediate by default: without store ordering, multithreaded titles can hang at load).
        Settings.fexPreset(context).env.forEach { (k, v) -> guest += "$k=$v" }
        if (Settings.protonLog(context)) guest += "PROTON_LOG=1"
        // FEXCore for games: the Proton launchers swap the chosen version's DLLs into the game's
        // prefix, or put Proton's own back when none is chosen. A launch option can override it per
        // game (BL_FEXCORE=2605 %command%, or BL_FEXCORE=proton).
        // Only the chosen versions are unpacked before the session starts (the first start after
        // an install would otherwise unpack ~220 MB first); the rest follow in the background and
        // are needed no sooner than a game launch that names one.
        // Which Proton each title runs on, and what runs an x86_64 one.
        GameSettingsStore.writeCompatLayers(context)
        guest += "BL_X86_EMU=" + Settings.x86Emulator(context).id
        // Versions a game's own settings name are needed as early as the global one.
        val games = GameSettingsStore.all(context).values
        guest += "BL_FEXCORE_ROOT=" + FexCore.prepare(context, (games.mapNotNull { it.fexCore } + FexCore.selected(context)).distinct()).path
        guest += "BL_FEXCORE=" + FexCore.selected(context)
        // DXVK the same way, by running Proton from a mirror of its tree (Proton reinstalls its own
        // DXVK into the prefix at every start). Per game: BL_DXVK=2.6.1-gplasync %command%.
        guest += "BL_DXVK_ROOT=" + Dxvk.prepare(context, (games.mapNotNull { it.dxvk } + Dxvk.selected(context)).distinct()).path
        guest += "BL_DXVK=" + Dxvk.selected(context)
        mark("chosen FEXCore and DXVK ready")
        // 0 starts the client without its update check and file verification.
        guest += "BL_STEAM_UPDATES=" + if (Settings.steamUpdates(context)) "1" else "0"
        LinuxRuntime.vulkanIcd(context)?.let { guest += "VK_ICD_FILENAMES=" + it.path }
        if (VulkanDrivers.selected(context) != VulkanDrivers.RUNTIME) VulkanDrivers.selectedIcd(context)?.let { guest += "BL_VK_DRIVER=" + it.path }
        FakeInputWriter.getRingEnv(fakeInputDir).takeIf { it.isNotEmpty() }?.let { guest += "FAKE_EVDEV_MEMFD_PATHS=$it" }
        guest += LinuxRuntime.SESSION_SCRIPT
        guest += LinuxRuntime.MODE_STEAM
        appId?.let { guest += "steam://rungameid/$it" }

        val binds = mutableListOf(fakeInputDir.path + ":/dev/input")
        // The SD card's library: the scripts register /mnt/bannerlator-sd with the client as its
        // "SD Card" library folder, but nothing was bound there, so it never appeared.
        sdLibrary(context)?.let {
            binds += it.path + ":" + SD_GUEST_PATH
            mark("SD card library: $it")
        }
        val command = LinuxRuntime.command(context, runtimeDir, Environment.getExternalStorageDirectory(), binds, guest)

        val hostEnv = HashMap<String, String>()
        hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
        hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
        // The runtime's proot links a libtalloc that sits beside it, which Android's linker does
        // not look for there on its own.
        LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }

        Thread({
            try {
                FexCore.prepare(context)
                Dxvk.prepare(context)
            } catch (e: Exception) {
                Log.w(TAG, "could not unpack the bundled FEXCore/DXVK versions", e)
            }
        }, "UnpackComponents").apply { priority = Thread.MIN_PRIORITY }.start()
        mark("session files staged")
        runCatching { storeBridge.start() }.onFailure { Log.w(TAG, "store bridge did not start", it) }
        network.publish()
        network.start()
        pulse.start()
        mark("network link and audio started")
        process = SessionProcess(command, hostEnv, root, File(logs, "proot.log")) { status ->
            Log.i(TAG, "session ended: $status")
            stopServices()
            onExit(status)
        }.also { it.start() }
        mark("proot started")
        Log.i(TAG, "session started" + (appId?.let { " for app $it" } ?: "") + ", log: $logs")
    }

    fun stop() {
        process?.stop()
        process = null
        stopServices()
    }

    private fun stopServices() {
        storeBridge.stop()
        network.stop()
        pulse.stop()
        logDir?.let { collectLogs(it) }
        stopAppLog()
    }

    private var appLog: Process? = null
    private val sessionStart = System.currentTimeMillis()

    private fun startAppLog(target: File) {
        try {
            appLog = ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "--pid=" + android.os.Process.myPid())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(target))
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start the app log", e)
        }
    }

    private fun stopAppLog() {
        appLog?.destroy()
        appLog = null
    }

    /**
     * What is only worth reading once the session is over: the audio daemon's log, the compositor's
     * own session log, and Steam's logs - reduced to their tails, and without connection_log.txt,
     * which carries the account's session token.
     */
    @Synchronized
    private fun collectLogs(dir: File) {
        if (File(dir, ".collected").exists()) return
        try {
            File(PulseAudio.workingDir(context), "pulse.log").takeIf { it.isFile }?.copyTo(File(dir, "audio.log"), true)
            val wayland = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Wayland-logs")
            wayland.listFiles()?.filter { it.isFile && it.lastModified() >= sessionStart }?.forEach {
                it.copyTo(File(dir, "compositor-" + it.name), true)
            }
            val steamLogs = File(root, "root/.local/share/Steam/logs")
            val out = File(dir, "steam")
            steamLogs.listFiles()?.filter { it.isFile && it.name != "connection_log.txt" }?.forEach { src ->
                out.mkdirs()
                val lines = src.readLines()
                File(out, src.name).writeText(lines.takeLast(3000).joinToString("\n", postfix = "\n"))
            }
            File(dir, ".collected").createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "could not collect the session's logs", e)
        }
    }

    /**
     * The session's preload libraries and scripts, copied from the APK over the runtime's own at
     * every launch. /etc/ld.so.preload names libblsession.so, so it has to match the build starting
     * it; this is also how a fix reaches an installed runtime without a new 800 MB image. Each file
     * lands by rename, so a library a running process still has mapped keeps the file it opened.
     */
    private fun stageSessionFiles() {
        for ((asset, target) in SESSION_FILES) {
            val dest = File(root, target)
            val staged = File(dest.parentFile, dest.name + ".staged")
            try {
                dest.parentFile?.mkdirs()
                context.assets.open("linuxfs/$asset").use { input -> staged.outputStream().use { input.copyTo(it) } }
                if (!(staged.setExecutable(true, false) && staged.renameTo(dest))) error("rename failed")
            } catch (e: Exception) {
                staged.delete()
                Log.e(TAG, "could not stage $asset", e)
            }
        }
        // Not LD_PRELOAD: the client rebuilds that for every process it starts and appends its own
        // overlay without a separator, silently dropping whatever was there.
        val etc = File(root, "etc")
        val staged = File(etc, "ld.so.preload.staged")
        FileUtils.writeString(staged, "/usr/local/lib/libblsession.so\n/usr/local/lib/libfakeinput.so\n")
        if (!staged.renameTo(File(etc, "ld.so.preload"))) Log.e(TAG, "could not write ld.so.preload")
    }

    companion object {
        private const val TAG = "Session"

        /** Where session log folders go: public Downloads when the app may write there, else its own dir. */
        fun logRoots(context: Context): List<File> = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SteamOS-Lite"),
            context.getExternalFilesDir(null)?.let { File(it, "logs") },
        )

        /** The most recent session's log folder, or null when there has been none. */
        fun latestLogDir(context: Context): File? =
            logRoots(context).flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }.maxByOrNull { it.lastModified() }

        private fun openLogDir(context: Context): File {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            for (root in logRoots(context)) {
                val dir = File(root, stamp)
                if (dir.mkdirs() || dir.isDirectory) return dir
            }
            return File(context.cacheDir, "logs/$stamp").apply { mkdirs() }
        }

        /** asset under linuxfs/ -> path under the runtime root. */
        private val SESSION_FILES = listOf(
            "libblsession.so" to "usr/local/lib/libblsession.so",
            "libfakeinput.so" to "usr/local/lib/libfakeinput.so",
            "box64" to "usr/local/bin/box64",
        ) + listOf(
            "session", "steam-install", "steam-compat", "steam-library",
            "seed-redists", "netmanager", "proton-extra", "steam-shortcuts",
        ).map { "usr/local/bin/bannerlator-$it" }.map { it to it } +
            listOf("usr/local/bin/bl-store-launch").map { it to it }

        const val SD_GUEST_PATH = "/mnt/bannerlator-sd"

        /**
         * The Steam library folder on the SD card, when one is inserted: the app's own directory on
         * the card (apps may not write elsewhere on it), created with its steamapps/ so the client
         * accepts it as a library. Null without a card.
         */
        fun sdLibrary(context: Context): File? {
            val card = context.getExternalFilesDirs(null).drop(1).firstOrNull {
                it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            } ?: return null
            val library = File(card, "steam-library")
            File(library, "steamapps").mkdirs()
            return library.takeIf { File(it, "steamapps").isDirectory }
        }

        /** The compositor's socket directory; never cleared, and bound into the session. */
        fun xdgRuntimeDir(context: Context) = File(context.filesDir, ".wayland-rt")

        /** The fake evdev nodes; their rings sit beside them in fakeinput-rings/. */
        fun fakeInputDir(context: Context) = File(context.filesDir, "input/devices")
    }
}
