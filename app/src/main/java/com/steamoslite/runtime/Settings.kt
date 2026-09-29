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
    private const val COMPAT_LAYER = "compatLayer"
    private const val X86_EMULATOR = "x86Emulator"
    private const val STEAM_UPDATES = "steamUpdates"
    private const val PROTON_LOG = "protonLog"
    private const val CLIENT_ALL_CORES = "clientAllCores"
    private const val CLIENT_TUNING = "clientTuning"
    private const val NO_XALIA = "noXalia"
    private const val DECK_MODE = "deckMode"
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

    /** Which Proton a Windows game runs on: the native ARM64 one, or an x86_64 one under an emulator. */
    enum class CompatLayer(val id: String, val label: String) {
        ARM64("arm64", "Proton ARM64"),
        X86_64("x86_64", "Proton x86_64"),
    }

    /** What runs an x86_64 Proton's Wine; the ARM64 Proton uses FEXCore instead. */
    enum class X86Emulator(val id: String, val label: String) {
        BOX64("box64", "Box64"),
        FEX("fex", "FEX"),
    }

    fun compatLayer(context: Context): CompatLayer =
        runCatching { CompatLayer.valueOf(prefs(context).getString(COMPAT_LAYER, null)!!) }.getOrDefault(CompatLayer.ARM64)

    fun setCompatLayer(context: Context, layer: CompatLayer) = put(context) { putString(COMPAT_LAYER, layer.name) }

    fun x86Emulator(context: Context): X86Emulator =
        runCatching { X86Emulator.valueOf(prefs(context).getString(X86_EMULATOR, null)!!) }.getOrDefault(X86Emulator.BOX64)

    fun setX86Emulator(context: Context, emulator: X86Emulator) = put(context) { putString(X86_EMULATOR, emulator.name) }

    /** Whether the Steam client checks for its own updates (and verifies its files) at every start. */
    fun steamUpdates(context: Context) = prefs(context).getBoolean(STEAM_UPDATES, true)

    fun setSteamUpdates(context: Context, on: Boolean) = put(context) { putBoolean(STEAM_UPDATES, on) }

    /** Proton's log of every game start, written into the session's log folder. */
    fun protonLog(context: Context) = prefs(context).getBoolean(PROTON_LOG, true)

    fun setProtonLog(context: Context, on: Boolean) = put(context) { putBoolean(PROTON_LOG, on) }

    /** Steam's interface re-pinned to every core rather than the subset Steam picks. */
    fun clientAllCores(context: Context) = prefs(context).getBoolean(CLIENT_ALL_CORES, true)

    fun setClientAllCores(context: Context, on: Boolean) = put(context) { putBoolean(CLIENT_ALL_CORES, on) }

    /** Steam's interface is OpenGL on Zink: lazy descriptors, threaded GL and no GL error checks. */
    val CLIENT_TUNING_ENV = mapOf("ZINK_DESCRIPTORS" to "lazy", "mesa_glthread" to "true", "MESA_NO_ERROR" to "1")

    fun clientTuning(context: Context) = prefs(context).getBoolean(CLIENT_TUNING, true)

    fun setClientTuning(context: Context, on: Boolean) = put(context) { putBoolean(CLIENT_TUNING, on) }

    /** Proton's xalia helper, which drives game menus with a pad through accessibility; off skips it. */
    fun noXalia(context: Context) = prefs(context).getBoolean(NO_XALIA, true)

    fun setNoXalia(context: Context, on: Boolean) = put(context) { putBoolean(NO_XALIA, on) }

    /** Steam as on a Steam Deck: the Quick Access Menu, battery, NIS scaling and the performance overlay. */
    fun deckMode(context: Context) = prefs(context).getBoolean(DECK_MODE, false)

    fun setDeckMode(context: Context, on: Boolean) = put(context) { putBoolean(DECK_MODE, on) }

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
