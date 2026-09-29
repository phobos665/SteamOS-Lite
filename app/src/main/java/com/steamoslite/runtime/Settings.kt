package com.steamoslite.runtime

import android.content.Context
import android.graphics.Point
import android.view.WindowManager

/**
 * The app's settings, read by the session when it starts. Everything lives in the "session"
 * preferences file, which the session process re-reads each time it starts, and every change is
 * committed rather than applied so the file is written before a session can be launched.
 */
object Settings {
    private const val RESOLUTION = "resolution"
    private const val REFRESH = "refreshHz"
    private const val FPS_LIMIT = "fpsLimit"
    private const val FEX_PRESET = "fexPreset"
    private const val STEAM_UPDATES = "steamUpdates"
    private const val PROTON_LOG = "protonLog"
    private const val CLIENT_ALL_CORES = "clientAllCores"
    private const val KEEP_RUNNING = "keepRunning"
    const val ON_SCREEN = "onScreenController"

    /** gamescope's output: what Steam and every game render at, scaled to the screen. */
    data class Resolution(val width: Int, val height: Int) {
        val id get() = "${width}x$height"
        val label get() = "$width × $height"
    }

    const val NATIVE = "native"
    val RESOLUTIONS = listOf(Resolution(854, 480), Resolution(960, 540), Resolution(1280, 720),
        Resolution(1600, 900), Resolution(1920, 1080))
    val DEFAULT_RESOLUTION = Resolution(1280, 720)

    /** The saved choice: one of RESOLUTIONS' ids, or NATIVE. */
    fun resolutionChoice(context: Context): String = prefs(context).getString(RESOLUTION, DEFAULT_RESOLUTION.id)!!

    fun setResolution(context: Context, choice: String) = put(context) { putString(RESOLUTION, choice) }

    /** What the choice comes to on this device: NATIVE is the screen's own size, landscape. */
    fun resolution(context: Context): Resolution {
        val choice = resolutionChoice(context)
        if (choice == NATIVE) return nativeResolution(context) ?: DEFAULT_RESOLUTION
        return RESOLUTIONS.firstOrNull { it.id == choice } ?: DEFAULT_RESOLUTION
    }

    fun nativeResolution(context: Context): Resolution? {
        val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay ?: return null
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        return Resolution(maxOf(size.x, size.y), minOf(size.x, size.y)).takeIf { it.width > 0 }
    }

    /** The screen's refresh rates, highest first. */
    fun refreshRates(context: Context): List<Int> =
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.supportedModes
            ?.map { Math.round(it.refreshRate) }?.distinct()?.sortedDescending().orEmpty()

    /** The refresh rate to run the screen at; 0 = the highest it has. */
    fun refreshChoice(context: Context) = prefs(context).getInt(REFRESH, 0)

    fun setRefresh(context: Context, hz: Int) = put(context) { putInt(REFRESH, hz) }

    /** gamescope's refresh cap, which a game with vsync runs at most at; 0 = the screen's rate. */
    val FPS_LIMITS = listOf(0, 30, 40, 45, 60, 90, 120)

    fun fpsLimit(context: Context) = prefs(context).getInt(FPS_LIMIT, 0)

    fun setFpsLimit(context: Context, fps: Int) = put(context) { putInt(FPS_LIMIT, fps) }

    /**
     * FEX's translation settings for every game, Bannerlator's presets: from the most faithful
     * memory ordering (slowest) to none. A game's own launch option (FEX_TSOENABLED=0 %command%)
     * still wins for that game.
     */
    enum class FexPreset(val label: String, val detail: String, val env: Map<String, String>) {
        STABILITY("Stability", "Full memory ordering, no block linking. Slowest.", fex(1, 1, 1, 1, 0, 0)),
        COMPATIBILITY("Compatibility", "Full memory ordering.", fex(1, 1, 1, 1, 0, 1)),
        INTERMEDIATE("Intermediate", "Memory ordering where it matters. The default.", fex(1, 0, 0, 1, 1, 1)),
        PERFORMANCE_TSO("Performance + TSO", "Only the main memory ordering.", fex(1, 0, 0, 0, 1, 1)),
        PERFORMANCE("Performance", "No memory ordering: fastest, but multithreaded games can hang.", fex(0, 0, 0, 0, 1, 1)),
    }

    fun fexPreset(context: Context): FexPreset =
        runCatching { FexPreset.valueOf(prefs(context).getString(FEX_PRESET, null)!!) }.getOrDefault(FexPreset.INTERMEDIATE)

    fun setFexPreset(context: Context, preset: FexPreset) = put(context) { putString(FEX_PRESET, preset.name) }

    /** Whether the Steam client checks for its own updates (and verifies its files) at every start. */
    fun steamUpdates(context: Context) = prefs(context).getBoolean(STEAM_UPDATES, true)

    fun setSteamUpdates(context: Context, on: Boolean) = put(context) { putBoolean(STEAM_UPDATES, on) }

    /** Proton's log of every game start, written into the session's log folder. */
    fun protonLog(context: Context) = prefs(context).getBoolean(PROTON_LOG, true)

    fun setProtonLog(context: Context, on: Boolean) = put(context) { putBoolean(PROTON_LOG, on) }

    /** Steam's interface re-pinned to every core rather than the subset Steam picks. */
    fun clientAllCores(context: Context) = prefs(context).getBoolean(CLIENT_ALL_CORES, true)

    fun setClientAllCores(context: Context, on: Boolean) = put(context) { putBoolean(CLIENT_ALL_CORES, on) }

    /** Leaving SteamOS keeps it running in the background, so coming back or starting a game skips the boot. */
    fun keepRunning(context: Context) = prefs(context).getBoolean(KEEP_RUNNING, true)

    fun setKeepRunning(context: Context, on: Boolean) = put(context) { putBoolean(KEEP_RUNNING, on) }

    /** The touch gamepad shown when a session starts (the quick menu toggles it, and saves that). */
    fun onScreenController(context: Context) = prefs(context).getBoolean(ON_SCREEN, false)

    fun setOnScreenController(context: Context, on: Boolean) = put(context) { putBoolean(ON_SCREEN, on) }

    fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun put(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(edit).commit()
    }
}

private fun fex(tso: Int, vectorTso: Int, memcpyTso: Int, halfBarrierTso: Int, x87Reduced: Int, multiblock: Int) = mapOf(
    "FEX_TSOENABLED" to "$tso",
    "FEX_VECTORTSOENABLED" to "$vectorTso",
    "FEX_MEMCPYSETTSOENABLED" to "$memcpyTso",
    "FEX_HALFBARRIERTSOENABLED" to "$halfBarrierTso",
    "FEX_X87REDUCEDPRECISION" to "$x87Reduced",
    "FEX_MULTIBLOCK" to "$multiblock",
)
