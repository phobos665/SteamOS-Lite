package com.steamoslite.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.frontend.FrontendExport
import com.steamoslite.runtime.ComponentStore
import com.steamoslite.runtime.Dxvk
import com.steamoslite.runtime.FexCore
import com.steamoslite.runtime.Protons
import com.steamoslite.runtime.Settings
import com.steamoslite.runtime.SteamShortcuts
import com.steamoslite.runtime.VulkanDrivers
import com.steamoslite.runtime.Vkd3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A component's versions the app has, the imported ones among them, and the one games use. */
internal data class ComponentPick(
    val versions: List<String> = emptyList(),
    val imported: List<String> = emptyList(),
    val selected: String = ComponentStore.PROTONS_OWN,
)

internal fun ComponentStore.pick(context: android.content.Context) =
    ComponentPick(available(context), imported(context), selected(context))

/** Everything the settings screen shows. */
internal data class SettingsState(
    val resolution: String = Settings.DEFAULT_RESOLUTION.id,
    val nativeResolution: Settings.Resolution? = null,
    val refreshRates: List<Int> = emptyList(),
    val refresh: Int = 0,
    val fpsLimit: Int = 0,
    val fexPreset: Settings.FexPreset = Settings.FexPreset.INTERMEDIATE,
    val compatLayer: Settings.CompatLayer = Settings.CompatLayer.ARM64,
    val x86Emulator: Settings.X86Emulator = Settings.X86Emulator.BOX64,
    val fex: ComponentPick = ComponentPick(),
    val dxvk: ComponentPick = ComponentPick(),
    val vkd3d: ComponentPick = ComponentPick(),
    val vkd3dFeatureLevel: String = "12_1",
    val vkd3dShaderModel: String = "6_0",
    val vkd3dConfig: String = Settings.AUTOMATIC,
    val compatFlags: Boolean = true,
    val steamUpdates: Boolean = true,
    val protonLog: Boolean = true,
    val clientAllCores: Boolean = true,
    val keepRunning: Boolean = true,
    val onScreen: Boolean = false,
    val gpu: VulkanDrivers.Gpu? = null,
    val drivers: List<VulkanDrivers.Installed> = emptyList(),
    val driver: String = VulkanDrivers.RUNTIME,
    val driverCatalog: List<VulkanDrivers.CatalogDriver> = emptyList(),
    /** The driver being downloaded and how far it is. */
    val driverDownload: Pair<String, Float>? = null,
    /** The frontend shortcut folder, or null when exporting is off. */
    val frontendDir: String? = null,
    val shortcutTest: Boolean = false,
    val message: String? = null,
)

/** What a change on the settings screen asks for; the route saves it. */
internal sealed interface SettingsChange {
    data class Resolution(val choice: String) : SettingsChange
    data class Refresh(val hz: Int) : SettingsChange
    data class FpsLimit(val fps: Int) : SettingsChange
    data class FexPreset(val preset: Settings.FexPreset) : SettingsChange
    data class CompatLayer(val layer: Settings.CompatLayer) : SettingsChange
    data class X86Emulator(val emulator: Settings.X86Emulator) : SettingsChange
    data class Component(val store: ComponentStore, val version: String) : SettingsChange
    data class Import(val store: ComponentStore) : SettingsChange
    data class Remove(val store: ComponentStore, val version: String) : SettingsChange
    data class SteamUpdates(val on: Boolean) : SettingsChange
    data class Vkd3dFeatureLevel(val level: String) : SettingsChange
    data class Vkd3dShaderModel(val model: String) : SettingsChange
    data class Vkd3dConfig(val config: String) : SettingsChange
    data class CompatFlags(val on: Boolean) : SettingsChange
    data class ProtonLog(val on: Boolean) : SettingsChange
    data class ClientAllCores(val on: Boolean) : SettingsChange
    data class KeepRunning(val on: Boolean) : SettingsChange
    data class OnScreen(val on: Boolean) : SettingsChange
    data class Driver(val id: String) : SettingsChange
    data class DriverInstall(val driver: VulkanDrivers.CatalogDriver) : SettingsChange
    data class DriverRemove(val id: String) : SettingsChange
    /** Opens the folder picker for frontend shortcuts. */
    data object FrontendPick : SettingsChange
    data class FrontendDir(val path: String?) : SettingsChange
    data object FrontendExportNow : SettingsChange
    data class ShortcutTest(val on: Boolean) : SettingsChange
}

/** The screen with its data: every change is saved at once and applies from the next SteamOS start. */
@Composable
internal fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SettingsState()) }
    BackHandler(onBack = onBack)

    suspend fun reload() {
        state = withContext(Dispatchers.IO) {
            state.copy(
                resolution = Settings.resolutionChoice(context),
                nativeResolution = Settings.nativeResolution(context),
                refreshRates = Settings.refreshRates(context),
                refresh = Settings.refreshChoice(context),
                fpsLimit = Settings.fpsLimit(context),
                fexPreset = Settings.fexPreset(context),
                compatLayer = Settings.compatLayer(context),
                x86Emulator = Settings.x86Emulator(context),
                fex = FexCore.pick(context),
                dxvk = Dxvk.pick(context),
                vkd3d = Vkd3d.pick(context),
                vkd3dFeatureLevel = Settings.vkd3dFeatureLevel(context),
                vkd3dShaderModel = Settings.vkd3dShaderModel(context),
                vkd3dConfig = Settings.vkd3dConfig(context),
                compatFlags = Settings.compatFlags(context),
                steamUpdates = Settings.steamUpdates(context),
                protonLog = Settings.protonLog(context),
                clientAllCores = Settings.clientAllCores(context),
                keepRunning = Settings.keepRunning(context),
                onScreen = Settings.onScreenController(context),
                frontendDir = FrontendExport.dir(context)?.path,
                shortcutTest = SteamShortcuts.testEnabled(context),
                gpu = VulkanDrivers.gpu(),
                drivers = VulkanDrivers.installed(context),
                driver = VulkanDrivers.selected(context),
            )
        }
    }
    LaunchedEffect(Unit) {
        reload()
        state = state.copy(driverCatalog = withContext(Dispatchers.IO) { VulkanDrivers.catalog() })
    }

    // The component a package is being picked for; the picker's result has no room for it.
    var importingInto by remember { mutableStateOf<ComponentStore>(FexCore) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val store = importingInto
        scope.launch {
            val name = withContext(Dispatchers.IO) { Protons.displayName(context, uri) } ?: "${store.kind}.tzst"
            if (!store.looksLikePackage(name)) {
                state = state.copy(message = "$name is not a ${componentName(store)} package (.tzst or .wcp).")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { store.import(context, uri, name) } }
            state = state.copy(
                message = result.fold(
                    { componentLabel(store, it) + " imported. Choose it below to use it." },
                    { "Could not import $name: ${it.message}" },
                ),
            )
            reload()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree == null) return@rememberLauncherForActivityResult
        val path = FrontendExport.pathOfTree(context, tree)
        if (path == null) {
            state = state.copy(message = "That folder is not on a storage volume SteamOS Lite can write to; pick another.")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                FrontendExport.setDir(context, path)
                FrontendExport.sync(context)
            }
            reload()
            state = state.copy(message = exportMessage(count, path))
        }
    }

    SettingsScreen(state, onBack) { change ->
        if (change is SettingsChange.FrontendPick) {
            folderPicker.launch(null)
            return@SettingsScreen
        }
        if (change is SettingsChange.FrontendExportNow || change is SettingsChange.FrontendDir) {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    if (change is SettingsChange.FrontendDir) FrontendExport.setDir(context, change.path)
                    FrontendExport.sync(context)
                }
                reload()
                state = state.copy(
                    message = if (change is SettingsChange.FrontendDir && change.path == null) "Frontend shortcuts removed."
                    else exportMessage(count, FrontendExport.dir(context)?.path),
                )
            }
            return@SettingsScreen
        }
        if (change is SettingsChange.DriverInstall) {
            if (state.driverDownload != null) return@SettingsScreen
            val driver = change.driver
            state = state.copy(driverDownload = driver.id to 0f, message = null)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VulkanDrivers.install(context, driver) { f -> scope.launch { if (state.driverDownload?.first == driver.id) state = state.copy(driverDownload = driver.id to f) } }
                        VulkanDrivers.select(context, driver.id)
                    }
                }
                state = state.copy(
                    driverDownload = null,
                    message = result.fold({ "${driverLabel(driver)} installed and chosen." }, { "Could not install the driver: ${it.message}" }),
                )
                reload()
            }
            return@SettingsScreen
        }
        if (change is SettingsChange.Import) {
            importingInto = change.store
            picker.launch(arrayOf("application/*", "*/*"))
            return@SettingsScreen
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                when (change) {
                    is SettingsChange.Resolution -> Settings.setResolution(context, change.choice)
                    is SettingsChange.Refresh -> Settings.setRefresh(context, change.hz)
                    is SettingsChange.FpsLimit -> Settings.setFpsLimit(context, change.fps)
                    is SettingsChange.FexPreset -> Settings.setFexPreset(context, change.preset)
                    is SettingsChange.CompatLayer -> Settings.setCompatLayer(context, change.layer)
                    is SettingsChange.X86Emulator -> Settings.setX86Emulator(context, change.emulator)
                    is SettingsChange.Component -> change.store.select(context, change.version)
                    is SettingsChange.Remove -> change.store.remove(context, change.version)
                    is SettingsChange.SteamUpdates -> Settings.setSteamUpdates(context, change.on)
                    is SettingsChange.Vkd3dFeatureLevel -> Settings.setVkd3dFeatureLevel(context, change.level)
                    is SettingsChange.Vkd3dShaderModel -> Settings.setVkd3dShaderModel(context, change.model)
                    is SettingsChange.Vkd3dConfig -> Settings.setVkd3dConfig(context, change.config)
                    is SettingsChange.CompatFlags -> Settings.setCompatFlags(context, change.on)
                    is SettingsChange.ProtonLog -> Settings.setProtonLog(context, change.on)
                    is SettingsChange.ClientAllCores -> Settings.setClientAllCores(context, change.on)
                    is SettingsChange.KeepRunning -> Settings.setKeepRunning(context, change.on)
                    is SettingsChange.OnScreen -> Settings.setOnScreenController(context, change.on)
                    is SettingsChange.ShortcutTest -> SteamShortcuts.setTest(context, change.on)
                    is SettingsChange.Driver -> VulkanDrivers.select(context, change.id)
                    is SettingsChange.DriverRemove -> VulkanDrivers.remove(context, change.id)
                    is SettingsChange.Import, is SettingsChange.DriverInstall, SettingsChange.FrontendPick, SettingsChange.FrontendExportNow,
                    is SettingsChange.FrontendDir -> {}
                }
            }
            state = state.copy(message = null)
            reload()
        }
    }
}

/** The screen as drawn (the screenshot tests draw it too). */
@Composable
internal fun SettingsScreen(state: SettingsState, onBack: () -> Unit, onChange: (SettingsChange) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().enterFade(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(16.dp))
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppColors.text)
            }
        }
        item { Text("Changes apply the next time SteamOS starts.", color = AppColors.textSecondary) }
        state.message?.let { item { Text(it, color = AppColors.info) } }

        item { Section("Display") }
        item {
            val options = Settings.RESOLUTIONS.map { it.id } + Settings.NATIVE
            Choice(
                "Resolution",
                "What Steam and every game render at, scaled to fit the screen. Lower is faster.",
                options, state.resolution,
                label = { id ->
                    if (id == Settings.NATIVE) "Native" + (state.nativeResolution?.let { " (${it.label})" } ?: "")
                    else Settings.RESOLUTIONS.first { it.id == id }.label
                },
            ) { onChange(SettingsChange.Resolution(it)) }
        }
        item {
            Choice(
                "Refresh rate",
                "The screen's refresh rate, which Steam and games are told too.",
                listOf(0) + state.refreshRates, state.refresh,
                label = { if (it == 0) "Highest" else "$it Hz" },
            ) { onChange(SettingsChange.Refresh(it)) }
        }
        item {
            Choice(
                "Frame rate limit",
                "A cap for the whole session: games with vsync run at most this fast. Saves battery.",
                Settings.FPS_LIMITS, state.fpsLimit,
                label = { if (it == 0) "Off" else "$it fps" },
            ) { onChange(SettingsChange.FpsLimit(it)) }
        }

        item { Section("Graphics") }
        item { DriverChoice(state, onChange) }

        item { Section("Emulation") }
        item {
            Choice(
                "Proton",
                "Which Proton Windows games run on. ARM64 runs Wine natively and emulates only the game's own code; " +
                    "x86_64 emulates all of Wine too, which is slower but starts some games ARM64 cannot. " +
                    "A game can pick its own in its Game settings. Applied from the next SteamOS start.",
                Settings.CompatLayer.entries, state.compatLayer, label = { it.label },
            ) { onChange(SettingsChange.CompatLayer(it)) }
        }
        item {
            Choice(
                "x86 emulator for Proton x86_64",
                "What runs Wine for games on Proton x86_64. Box64 is usually faster; FEX is the fallback when a game " +
                    "misbehaves under it. Proton ARM64 always uses FEXCore.",
                Settings.X86Emulator.entries, state.x86Emulator, label = { it.label },
            ) { onChange(SettingsChange.X86Emulator(it)) }
        }
        item {
            Choice(
                "FEX preset",
                state.fexPreset.detail + " A game's launch option (FEX_TSOENABLED=1 %command%) overrides it.",
                Settings.FexPreset.values().toList(), state.fexPreset,
                label = { it.label },
            ) { onChange(SettingsChange.FexPreset(it)) }
        }
        item {
            ComponentChoice(
                FexCore, state.fex,
                "The x86 emulator for games' Windows code. Swapped into each game's prefix from its " +
                    "second start. For one game: BL_FEXCORE=2605 %command%.",
                onChange,
            )
        }
        item {
            ComponentChoice(
                Dxvk, state.dxvk,
                "Direct3D 8-11 on Vulkan. The bundled versions are GameNative's x86-64 builds, which run " +
                    "under FEXCore: try them when a game draws wrong. For one game: BL_DXVK=2.6.1-gplasync %command%.",
                onChange,
            )
        }

        item {
            Toggle(
                "Crash-safety flags",
                "The settings Android Proton builds run games with to avoid known crashes: Turnip's " +
                    "noconform mode, esync, a larger shader cache and DXVK's async compilation.",
                state.compatFlags,
            ) { onChange(SettingsChange.CompatFlags(it)) }
        }

        item { Section("Direct3D 12") }
        item {
            ComponentChoice(
                Vkd3d, state.vkd3d,
                "VKD3D-Proton, Direct3D 12 on Vulkan. The bundled versions are x86-64 builds that run under " +
                    "FEXCore; try one when a DX12 game crashes or draws wrong.",
                onChange,
            )
        }
        item {
            Vkd3dChoices(
                state.vkd3dFeatureLevel, state.vkd3dShaderModel, state.vkd3dConfig, global = null,
                onLevel = { onChange(SettingsChange.Vkd3dFeatureLevel(it.orEmpty())) },
                onModel = { onChange(SettingsChange.Vkd3dShaderModel(it.orEmpty())) },
                onConfig = { onChange(SettingsChange.Vkd3dConfig(it.orEmpty())) },
            )
        }

        item { Section("Steam") }
        item {
            Toggle(
                "Keep SteamOS running",
                "Leaving SteamOS keeps it running in the background (a notification shows it), so coming back " +
                    "or starting a game takes seconds instead of a full boot. Uses memory and some battery while it runs.",
                state.keepRunning,
            ) { onChange(SettingsChange.KeepRunning(it)) }
        }
        item {
            Toggle(
                "Steam updates",
                "Steam checks for its own updates and verifies its files at every start. Off starts faster; " +
                    "turn it back on if Steam asks for a newer version or something stops working.",
                state.steamUpdates,
            ) { onChange(SettingsChange.SteamUpdates(it)) }
        }
        item {
            Toggle(
                "Game launch logs",
                "Proton writes a log of every game start into the session's logs (Share logs). Off saves a little time and space.",
                state.protonLog,
            ) { onChange(SettingsChange.ProtonLog(it)) }
        }
        item {
            Toggle(
                "Steam on every core",
                "Steam keeps its interface to some cores; this gives it all of them, for smoother menus.",
                state.clientAllCores,
            ) { onChange(SettingsChange.ClientAllCores(it)) }
        }

        item { Section("Frontends") }
        item {
            val dir = state.frontendDir
            SettingCard(
                "Shortcuts for Daijishō, Cocoon and ES-DE",
                if (dir == null) {
                    "Writes a shortcut for every installed game, plus a Daijishō platform file (Cocoon imports it too), " +
                        "so a frontend can start games straight into SteamOS. Kept up to date whenever SteamOS Lite reads its library."
                } else {
                    "Exporting to $dir. Import the platform file there into Daijishō or Cocoon; the README beside it has ES-DE's setup."
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dir == null) {
                        PrimaryButton(onClick = { onChange(SettingsChange.FrontendDir(FrontendExport.suggestedDir().path)) }) {
                            Text("Export to ROMs/steamos")
                        }
                        SecondaryButton(onClick = { onChange(SettingsChange.FrontendPick) }) { Text("Choose folder…") }
                    } else {
                        PrimaryButton(onClick = { onChange(SettingsChange.FrontendExportNow) }) { Text("Export now") }
                        SecondaryButton(onClick = { onChange(SettingsChange.FrontendPick) }) { Text("Change folder…") }
                        SecondaryButton(onClick = { onChange(SettingsChange.FrontendDir(null)) }) { Text("Stop exporting") }
                    }
                }
            }
        }

        item { Section("Controls") }
        item {
            Toggle(
                "On-screen controller",
                "Shown when SteamOS starts. The quick menu (back button) shows or hides it at any time.",
                state.onScreen,
            ) { onChange(SettingsChange.OnScreen(it)) }
        }

        item { Section("Experimental") }
        item {
            Toggle(
                "Steam shortcut test",
                "Adds \"SteamOS Lite shortcut test\" (Notepad) to the Steam library as a non-Steam game, to check " +
                    "that app-made shortcuts work before GOG and Epic games use them. Start SteamOS, run it from the " +
                    "library, close Notepad, exit, then Share logs (shortcut-probe.log shows how Steam launched it).",
                state.shortcutTest,
            ) { onChange(SettingsChange.ShortcutTest(it)) }
        }
    }
}

internal fun componentName(store: ComponentStore) = when (store) {
    Dxvk -> "DXVK"
    Vkd3d -> "VKD3D-Proton"
    else -> "FEXCore"
}

internal fun vkd3dLabel(value: String) = when (value) {
    Settings.AUTOMATIC -> "Automatic"
    "nodxr" -> "No ray tracing"
    "nodxr,no_upload_hvv" -> "No ray tracing, no VRAM uploads"
    else -> value.replace('_', '.')
}

/**
 * The VKD3D-Proton variables. For one game, [global] holds the global values and null follows them;
 * globally it is null and every choice is a value.
 */
@Composable
internal fun Vkd3dChoices(
    level: String?,
    model: String?,
    config: String?,
    global: Triple<String, String, String>?,
    onLevel: (String?) -> Unit,
    onModel: (String?) -> Unit,
    onConfig: (String?) -> Unit,
) {
    fun options(all: List<String>): List<String?> = if (global == null) all else listOf(null) + all
    fun label(value: String?, globalValue: String?) = value?.let(::vkd3dLabel) ?: "Global (${vkd3dLabel(globalValue.orEmpty())})"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice(
            "Feature level",
            "The Direct3D 12 level games are told the GPU has. 12.1 is what most games want; lower it for one that refuses to start.",
            options(Settings.VKD3D_FEATURE_LEVELS), level, label = { label(it, global?.first) }, onSelect = onLevel,
        )
        Choice(
            "Shader model",
            "The newest shader model games may use. 6.0 keeps them off driver features that crash on Adreno; " +
                "raise it for a game that needs a newer one.",
            options(Settings.VKD3D_SHADER_MODELS), model, label = { label(it, global?.second) }, onSelect = onModel,
        )
        Choice(
            "Ray tracing and memory",
            "Turning ray tracing off, and keeping textures out of video memory, fix crashes and stutter in some DX12 games.",
            options(Settings.VKD3D_CONFIGS), config, label = { label(it, global?.third) }, onSelect = onConfig,
        )
    }
}

internal fun componentLabel(store: ComponentStore, version: String) =
    if (version == ComponentStore.PROTONS_OWN) "Proton's own ${componentName(store)}" else componentName(store) + " " + version

/** A card with a title, what it does, and whatever controls it. */
@Composable
internal fun SettingCard(title: String, detail: String, content: @Composable () -> Unit) {
    CardColumn {
        Text(title, color = AppColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) Text(detail, color = AppColors.textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** One of several options, all shown: the chosen one filled, the rest outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choice(
    title: String,
    detail: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    extra: @Composable () -> Unit = {},
    onSelect: (T) -> Unit,
) = SettingCard(title, detail) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            if (option == selected) PrimaryButton(onClick = {}) { Text(label(option)) }
            else SecondaryButton(onClick = { onSelect(option) }) { Text(label(option)) }
        }
        extra()
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = AppColors.textMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = onChange)
        }
    }
}

/** FEXCore or DXVK: Proton's own or a version, newest first, plus import and removing imports. */
@Composable
private fun ComponentChoice(store: ComponentStore, pick: ComponentPick, detail: String, onChange: (SettingsChange) -> Unit) {
    val name = componentName(store)
    Choice(
        "$name version", detail,
        listOf(ComponentStore.PROTONS_OWN) + pick.versions.reversed(), pick.selected,
        label = { if (it == ComponentStore.PROTONS_OWN) "Proton's own" else it + if (it in pick.imported) " (imported)" else "" },
        extra = {
            SecondaryButton(onClick = { onChange(SettingsChange.Import(store)) }) { Text("Import…") }
            for (version in pick.imported) {
                if (version != pick.selected) {
                    SecondaryButton(onClick = { onChange(SettingsChange.Remove(store, version)) }) { Text("Remove $version") }
                }
            }
        },
    ) { onChange(SettingsChange.Component(store, it)) }
}

/**
 * The Vulkan driver for the whole session: the runtime's Turnip or a downloaded build, with the
 * builds for this device's GPU offered first.
 */
@Composable
internal fun DriverChoice(state: SettingsState, onChange: (SettingsChange) -> Unit) {
    val family = state.gpu?.family
    val installed = state.drivers.map { it.id }.toSet()
    val offered = state.driverCatalog.filter { it.id !in installed }.sortedBy { if (it.family == family) 0 else 1 }
    Choice(
        "Vulkan driver",
        (state.gpu?.let { "This device: ${it.name}" + (it.family?.let { f -> " (${f.variant} builds)" } ?: "") + ". " } ?: "") +
            "What Steam, gamescope and every game draw with. Newer Turnip builds can run games faster or fix " +
            "rendering; if one misbehaves, go back to the built-in one. A game's own settings can pick another.",
        listOf(VulkanDrivers.RUNTIME) + state.drivers.map { it.id }, state.driver,
        label = { id ->
            if (id == VulkanDrivers.RUNTIME) "Built-in Turnip"
            else state.drivers.first { it.id == id }.let { it.name + if (it.variant == family?.variant) " ★" else "" }
        },
        extra = {
            for (d in offered) {
                val progress = state.driverDownload?.takeIf { it.first == d.id }?.second
                SecondaryButton(onClick = { onChange(SettingsChange.DriverInstall(d)) }) {
                    Text(
                        if (progress != null) "Downloading ${(progress * 100).toInt()}%"
                        else "Download ${driverLabel(d)}" + if (d.family == family) " (recommended)" else "",
                    )
                }
            }
            for (d in state.drivers) {
                if (d.id != state.driver) SecondaryButton(onClick = { onChange(SettingsChange.DriverRemove(d.id)) }) { Text("Remove ${d.name}") }
            }
        },
    ) { onChange(SettingsChange.Driver(it)) }
}

internal fun driverLabel(d: VulkanDrivers.CatalogDriver) = "Turnip ${d.version.substringBefore('-')} ${d.variant}"

private fun exportMessage(count: Int?, dir: String?) = when {
    count == null -> "Could not write to ${dir ?: "the folder"}. Allow SteamOS Lite storage access, or pick another folder."
    count == 1 -> "1 game exported to $dir."
    else -> "$count games exported to $dir."
}
