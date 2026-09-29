package com.steamoslite.runtime

import android.content.Context
import com.steamoslite.util.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * One game's own settings over the global ones in Settings. A null field follows the global
 * setting; [ComponentStore.PROTONS_OWN] as a version means Proton's own.
 */
data class GameSettings(
    val fexCore: String? = null,
    val dxvk: String? = null,
    val fexPreset: Settings.FexPreset? = null,
    val compatLayer: Settings.CompatLayer? = null,
    val x86Emulator: Settings.X86Emulator? = null,
) {
    val isDefault get() = this == GameSettings()

    fun toJson(): JSONObject = JSONObject().apply {
        fexCore?.let { put("fexCore", it) }
        dxvk?.let { put("dxvk", it) }
        fexPreset?.let { put("fexPreset", it.name) }
        compatLayer?.let { put("compatLayer", it.name) }
        x86Emulator?.let { put("x86Emulator", it.name) }
    }

    /** What the Proton launchers read when the game starts: the same variables the session sets globally. */
    fun toEnv(): String = buildString {
        fexCore?.let { append("BL_FEXCORE=").append(it.ifEmpty { "proton" }).append('\n') }
        dxvk?.let { append("BL_DXVK=").append(it.ifEmpty { "proton" }).append('\n') }
        fexPreset?.env?.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
        x86Emulator?.let { append("BL_X86_EMU=").append(it.id).append('\n') }
    }

    companion object {
        fun fromJson(o: JSONObject) = GameSettings(
            fexCore = o.optString("fexCore").takeIf { o.has("fexCore") },
            dxvk = o.optString("dxvk").takeIf { o.has("dxvk") },
            fexPreset = o.optString("fexPreset").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Settings.FexPreset.valueOf(it) }.getOrNull() },
            compatLayer = o.optString("compatLayer").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Settings.CompatLayer.valueOf(it) }.getOrNull() },
            x86Emulator = o.optString("x86Emulator").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Settings.X86Emulator.valueOf(it) }.getOrNull() },
        )
    }
}

/**
 * Game settings, keyed by the id Steam starts the game with: its Steam app id, or the id of one of
 * the app's non-Steam shortcuts (GOG and Epic games). Kept in the app's files, and written into the
 * runtime as ~/.bl-games/<id>.env, which the Proton launchers read however the game is started -
 * from the app, a frontend, or Steam's own library.
 */
object GameSettingsStore {
    private fun dir(context: Context) = File(context.filesDir, "game-settings").apply { mkdirs() }

    private fun envFile(context: Context, id: String) = File(LinuxRuntime.rootDir(context), "root/.bl-games/$id.env")

    fun get(context: Context, id: String): GameSettings =
        runCatching { GameSettings.fromJson(JSONObject(File(dir(context), "$id.json").readText())) }.getOrDefault(GameSettings())

    fun set(context: Context, id: String, settings: GameSettings) {
        require(id.all(Char::isDigit)) { "not an app id: $id" }
        val json = File(dir(context), "$id.json")
        val env = envFile(context, id)
        if (settings.isDefault) {
            json.delete()
            env.delete()
            return
        }
        FileUtils.writeString(json, settings.toJson().toString())
        env.parentFile?.mkdirs()
        FileUtils.writeString(env, settings.toEnv())
    }

    /**
     * ~/.bl-compat-layers, which bannerlator-steam-compat maps each title's Proton from before the
     * client starts: the global layer, then every game with one of its own.
     */
    fun writeCompatLayers(context: Context) {
        val text = buildString {
            append("default=").append(Settings.compatLayer(context).id).append('\n')
            all(context).forEach { (id, s) -> s.compatLayer?.let { append(id).append('=').append(it.id).append('\n') } }
        }
        FileUtils.writeString(File(LinuxRuntime.rootDir(context), "root/.bl-compat-layers"), text)
    }

    fun all(context: Context): Map<String, GameSettings> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .associate { it.name.removeSuffix(".json") to get(context, it.name.removeSuffix(".json")) }
}
