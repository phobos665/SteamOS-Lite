package com.steamoslite.runtime

import android.content.Context
import android.os.Environment
import android.util.Log
import com.steamoslite.input.FakeInputWriter
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

    /** Where the session's log goes; the folder to hand over when something goes wrong. */
    var logDir: File? = null
        private set

    fun start() {
        check(LinuxRuntime.isInstalled(context)) { "The SteamOS runtime is not installed." }
        LinuxRuntime.writeAccounts(context)

        // Refreshed every session, so what runs is always what this APK carries.
        TarZstd.extractAsset(context, "pulseaudio.tzst", PulseAudio.workingDir(context))
        stageSessionFiles()
        // Android has no /dev/shm; a cache directory stands in and keeps whatever a session leaves
        // (the client abandons tens of megabytes of streams every run). Cleared before each start.
        FileUtils.clear(File(context.cacheDir, "shm"))

        val runtimeDir = xdgRuntimeDir(context).apply { mkdirs() }
        val logs = openLogDir().also { logDir = it }
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
            // Never capped: gamescope's rate holds for the whole session, menus included.
            "BL_FPS=0",
            "BL_LOG=" + File(logs, "session.log").path,
            "BL_DEBUG_DIR=" + logs.path,
            // Steam pins its interface to a subset of cores; the session re-pins it to all of them
            // (measured 66 -> 90+ fps in the menus on a Pocket FIT).
            "BL_CLIENT_CPUS=" + (0 until Runtime.getRuntime().availableProcessors()).joinToString(","),
            // Games launched from the client run x86 code under FEX. Bannerlator's default preset
            // (Intermediate): without store ordering, multithreaded titles can hang at load.
            "FEX_TSOENABLED=1",
            "FEX_VECTORTSOENABLED=0",
            "FEX_MEMCPYSETTSOENABLED=0",
            "FEX_HALFBARRIERTSOENABLED=1",
            "FEX_X87REDUCEDPRECISION=1",
            "FEX_MULTIBLOCK=1",
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
        LinuxRuntime.vulkanIcd(context)?.let { guest += "VK_ICD_FILENAMES=" + it.path }
        FakeInputWriter.getRingEnv(fakeInputDir).takeIf { it.isNotEmpty() }?.let { guest += "FAKE_EVDEV_MEMFD_PATHS=$it" }
        guest += LinuxRuntime.SESSION_SCRIPT
        guest += LinuxRuntime.MODE_STEAM
        appId?.let { guest += "steam://rungameid/$it" }

        val binds = listOf(fakeInputDir.path + ":/dev/input")
        val command = LinuxRuntime.command(context, runtimeDir, Environment.getExternalStorageDirectory(), binds, guest)

        val hostEnv = HashMap<String, String>()
        hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
        hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
        // The runtime's proot links a libtalloc that sits beside it, which Android's linker does
        // not look for there on its own.
        LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }

        network.publish()
        network.start()
        pulse.start()
        process = SessionProcess(command, hostEnv, root, File(logs, "proot.log")) { status ->
            Log.i(TAG, "session ended: $status")
            stopServices()
            onExit(status)
        }.also { it.start() }
        Log.i(TAG, "session started" + (appId?.let { " for app $it" } ?: "") + ", log: $logs")
    }

    fun stop() {
        process?.stop()
        process = null
        stopServices()
    }

    private fun stopServices() {
        network.stop()
        pulse.stop()
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

    /** Public Downloads when the app may write there, the app's own external dir otherwise. */
    private fun openLogDir(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SteamOS-Lite/$stamp")
        if (public.mkdirs() || public.isDirectory) return public
        val private = File(context.getExternalFilesDir(null), "logs/$stamp")
        private.mkdirs()
        return private
    }

    companion object {
        private const val TAG = "Session"

        /** asset under linuxfs/ -> path under the runtime root. */
        private val SESSION_FILES = listOf(
            "libblsession.so" to "usr/local/lib/libblsession.so",
            "libfakeinput.so" to "usr/local/lib/libfakeinput.so",
        ) + listOf(
            "session", "steam-install", "steam-compat", "steam-library",
            "seed-redists", "netmanager", "proton-extra",
        ).map { "usr/local/bin/bannerlator-$it" }.map { it to it }

        /** The compositor's socket directory; never cleared, and bound into the session. */
        fun xdgRuntimeDir(context: Context) = File(context.filesDir, ".wayland-rt")

        /** The fake evdev nodes; their rings sit beside them in fakeinput-rings/. */
        fun fakeInputDir(context: Context) = File(context.filesDir, "input/devices")
    }
}
