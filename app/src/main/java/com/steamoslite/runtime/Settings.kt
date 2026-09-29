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
    private const val VKD3D_FEATURE_LEVEL = "vkd3dFeatureLevel"
    private const val VKD3D_SHADER_MODEL = "vkd3dShaderModel"
    private const val VKD3D_CONFIG = "vkd3dConfig"
    private const val COMPAT_FLAGS = "compatFlags"
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

    /** An empty value in the VKD3D-Proton choices leaves it to VKD3D-Proton. */
    const val AUTOMATIC = ""
    val VKD3D_FEATURE_LEVELS = listOf(AUTOMATIC, "12_2", "12_1", "12_0", "11_1", "11_0")
    val VKD3D_SHADER_MODELS = listOf(AUTOMATIC, "6_6", "6_5", "6_0", "5_1")
    /** VKD3D_CONFIG: no ray tracing, and no textures uploaded straight into video memory. */
    val VKD3D_CONFIGS = listOf(AUTOMATIC, "nodxr", "nodxr,no_upload_hvv")

    /** The Direct3D 12 feature level games are told the GPU has. */
    fun vkd3dFeatureLevel(context: Context) = prefs(context).getString(VKD3D_FEATURE_LEVEL, "12_1")!!

    fun setVkd3dFeatureLevel(context: Context, level: String) = put(context) { putString(VKD3D_FEATURE_LEVEL, level) }

    /** The highest shader model games are offered: newer ones reach driver features that crash on Adreno. */
    fun vkd3dShaderModel(context: Context) = prefs(context).getString(VKD3D_SHADER_MODEL, "6_0")!!

    fun setVkd3dShaderModel(context: Context, model: String) = put(context) { putString(VKD3D_SHADER_MODEL, model) }

    fun vkd3dConfig(context: Context) = prefs(context).getString(VKD3D_CONFIG, AUTOMATIC)!!

    fun setVkd3dConfig(context: Context, config: String) = put(context) { putString(VKD3D_CONFIG, config) }

    /** The environment Android Proton builds run games with to avoid known crashes. */
    val COMPAT_ENV = mapOf(
        "TU_DEBUG" to "noconform",
        "WINEESYNC" to "1",
        "MESA_SHADER_CACHE_DISABLE" to "false",
        "MESA_SHADER_CACHE_MAX_SIZE" to "512MB",
        "DXVK_ASYNC" to "1",
        "DXVK_GPLASYNCCACHE" to "1",
    )

    fun compatFlags(context: Context) = prefs(context).getBoolean(COMPAT_FLAGS, true)

    fun setCompatFlags(context: Context, on: Boolean) = put(context) { putBoolean(COMPAT_FLAGS, on) }

    /** Every VKD3D-Proton and compatibility variable the session sets for games, from the settings above. */
    fun gameEnv(context: Context): Map<String, String> = buildMap {
        if (compatFlags(context)) putAll(COMPAT_ENV)
        vkd3dFeatureLevel(context).takeIf { it.isNotEmpty() }?.let { put("VKD3D_FEATURE_LEVEL", it) }
        vkd3dShaderModel(context).takeIf { it.isNotEmpty() }?.let { put("VKD3D_SHADER_MODEL", it) }
        vkd3dConfig(context).takeIf { it.isNotEmpty() }?.let { put("VKD3D_CONFIG", it) }
    }

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
