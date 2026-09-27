package com.steamoslite.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.runtime.ComponentStore
import com.steamoslite.runtime.Dxvk
import com.steamoslite.runtime.FexCore
import com.steamoslite.runtime.Protons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the compatibility-tools screen shows. */
internal data class ProtonsState(
    val engines: List<String> = emptyList(),
    val installed: List<Protons.Installed> = emptyList(),
    val queued: List<String> = emptyList(),
    val catalog: List<Protons.CatalogBuild> = emptyList(),
    val catalogLoading: Boolean = true,
    /** Bytes copied so far while an import runs, or null. */
    val importing: Long? = null,
    val message: String? = null,
    val fex: ComponentPick = ComponentPick(),
    val dxvk: ComponentPick = ComponentPick(),
)

/** A component's versions the app has, the imported ones among them, and the one games use. */
internal data class ComponentPick(
    val versions: List<String> = emptyList(),
    val imported: List<String> = emptyList(),
    val selected: String = ComponentStore.PROTONS_OWN,
)

private fun ComponentStore.pick(context: android.content.Context) =
    ComponentPick(available(context), imported(context), selected(context))

/** The screen with its data: reads the runtime, queues work for the next session. */
@Composable
internal fun ProtonsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ProtonsState()) }
    BackHandler(onBack = onBack)

    suspend fun reload() {
        val (engines, installed, queued) = withContext(Dispatchers.IO) {
            Protons.cleanImports(context)
            Triple(Protons.valveEngines(context), Protons.installed(context), Protons.queued(context))
        }
        val (fex, dxvk) = withContext(Dispatchers.IO) { FexCore.pick(context) to Dxvk.pick(context) }
        state = state.copy(engines = engines, installed = installed, queued = queued, fex = fex, dxvk = dxvk)
    }

    LaunchedEffect(Unit) {
        reload()
        val catalog = withContext(Dispatchers.IO) { Protons.catalog() }
        state = state.copy(catalog = catalog, catalogLoading = false)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { Protons.displayName(context, uri) } ?: "proton.tar.gz"
            if (!Protons.looksLikeArchive(name)) {
                state = state.copy(message = "$name is not a Proton archive (.tar.gz, .tar.xz or .tar.zst).")
                return@launch
            }
            state = state.copy(importing = 0L, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Protons.import(context, uri, name) { bytes ->
                        scope.launch(Dispatchers.Main) { state = state.copy(importing = bytes) }
                    }
                }
            }
            state = state.copy(
                importing = null,
                message = result.fold(
                    { "$name is queued. It installs the next time you launch SteamOS." },
                    { "Could not import $name: ${it.message}" },
                ),
            )
            reload()
        }
    }

    // The component a package is being picked for; the picker's result has no room for it.
    var importingInto by remember { mutableStateOf<ComponentStore>(FexCore) }
    val componentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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

    ProtonsScreen(
        state = state,
        onSelectComponent = { store, version ->
            scope.launch {
                withContext(Dispatchers.IO) { store.select(context, version) }
                state = state.copy(message = "Games use " + componentLabel(store, version) + " from the next SteamOS start.")
                reload()
            }
        },
        onImportComponent = { store ->
            importingInto = store
            componentPicker.launch(arrayOf("application/*", "*/*"))
        },
        onRemoveComponent = { store, version ->
            scope.launch {
                withContext(Dispatchers.IO) { store.remove(context, version) }
                reload()
            }
        },
        onBack = onBack,
        onImport = { picker.launch(arrayOf("application/*", "*/*")) },
        onInstall = { build ->
            scope.launch {
                withContext(Dispatchers.IO) { Protons.queueCatalog(context, build) }
                state = state.copy(message = "${build.display} is queued. It downloads and installs the next time you launch SteamOS.")
                reload()
            }
        },
        onCancel = { request ->
            scope.launch {
                withContext(Dispatchers.IO) { Protons.cancel(context, request) }
                reload()
            }
        },
        onRemove = { build ->
            scope.launch {
                withContext(Dispatchers.IO) { Protons.remove(context, build.dir) }
                state = state.copy(message = "${build.display} removed. Games set to it go back to Bannerlator Proton.")
                reload()
            }
        },
    )
}

/** The screen as drawn (the screenshot tests draw it too). */
@Composable
internal fun ProtonsScreen(
    state: ProtonsState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onInstall: (Protons.CatalogBuild) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (Protons.Installed) -> Unit,
    onSelectComponent: (ComponentStore, String) -> Unit = { _, _ -> },
    onImportComponent: (ComponentStore) -> Unit = {},
    onRemoveComponent: (ComponentStore, String) -> Unit = { _, _ -> },
) {
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(16.dp))
                Text("Compatibility tools", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        item {
            Text(
                "The Protons Windows games can run with. In Steam, pick one per game under Properties → " +
                    "Compatibility; the ones that work here are marked (Bannerlator). Only ARM64 Linux builds run.",
                color = Color.LightGray,
            )
        }
        state.message?.let { item { Text(it, color = Color(0xFF8FD3FF)) } }
        state.importing?.let { bytes ->
            item {
                Text("Importing… ${bytes / 1_000_000} MB copied", color = Color.White)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        item { Section("Installed") }
        item {
            Row_(
                title = "Bannerlator Proton (ARM64)",
                detail = if (state.engines.isEmpty()) "Valve's ARM64 Proton, downloaded by Steam on first start"
                else "Runs Valve's " + state.engines.first() + ". The default for every Windows game.",
            ) {}
        }
        items(state.installed, key = { "i:" + it.dir }) { build ->
            Row_(build.display, build.dir) { OutlinedButton(onClick = { onRemove(build) }) { Text("Remove") } }
        }

        if (state.queued.isNotEmpty()) {
            item { Section("Installs the next time you launch SteamOS") }
            items(state.queued, key = { "q:$it" }) { request ->
                Row_(Protons.describe(request), "Queued") { OutlinedButton(onClick = { onCancel(request) }) { Text("Cancel") } }
            }
        }

        component(
            FexCore, state.fex,
            "The x86 emulator Proton runs games' Windows code with. Each Proton brings its own; choosing " +
                "a version here swaps it into every game's prefix from its next start (a game's very first " +
                "start always uses Proton's own). For one game only, set its launch option to " +
                "BL_FEXCORE=2605 %command%.",
            "libarm64ecfex.dll and libwow64fex.dll",
            onSelectComponent, onImportComponent, onRemoveComponent,
        )
        component(
            Dxvk, state.dxvk,
            "Direct3D 8-11 on Vulkan. The bundled versions are GameNative's x86-64 builds, which run " +
                "under FEXCore in place of Proton's native ARM64 one: try them when a game draws wrong. " +
                "For one game only, set its launch option to BL_DXVK=2.6.1-gplasync %command%.",
            "system32/ and syswow64/ with d3d11.dll",
            onSelectComponent, onImportComponent, onRemoveComponent,
        )

        item { Section("Add a Proton") }
        item {
            Row_("Import from a file", "A Proton build for ARM64 Linux: .tar.gz, .tar.xz or .tar.zst") {
                Button(onClick = onImport, enabled = state.importing == null) { Text("Import…") }
            }
        }
        when {
            state.catalogLoading -> item { Text("Loading the catalog…", color = Color.Gray) }
            state.catalog.isEmpty() -> item { Text("The catalog could not be loaded.", color = Color.Gray) }
        }
        items(state.catalog, key = { "c:" + it.dir }) { build ->
            val done = state.installed.any { it.dir == build.dir }
            val waiting = build.request in state.queued
            Row_(build.display, "${build.size / 1_000_000} MB download. ${build.notes}") {
                Button(onClick = { onInstall(build) }, enabled = !done && !waiting) {
                    Text(if (done) "Installed" else if (waiting) "Queued" else "Install")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Row_(title: String, detail: String, action: @Composable () -> Unit) {
    Surface(color = Color(0xFF1B2530), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp)
                if (detail.isNotEmpty()) Text(detail, color = Color.Gray, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

internal fun componentName(store: ComponentStore) = if (store == Dxvk) "DXVK" else "FEXCore"

internal fun componentLabel(store: ComponentStore, version: String) =
    if (version == ComponentStore.PROTONS_OWN) "Proton's own ${componentName(store)}" else componentName(store) + " " + version

/** A component's section: Proton's own, each version newest first, and an import row. */
private fun LazyListScope.component(
    store: ComponentStore,
    pick: ComponentPick,
    explanation: String,
    contents: String,
    onSelect: (ComponentStore, String) -> Unit,
    onImport: (ComponentStore) -> Unit,
    onRemove: (ComponentStore, String) -> Unit,
) {
    val name = componentName(store)
    item { Section(name) }
    item { Text(explanation, color = Color.LightGray) }
    items(listOf(ComponentStore.PROTONS_OWN) + pick.versions.reversed(), key = { store.kind + ":" + it }) { version ->
        val current = version == pick.selected
        Row_(
            if (version == ComponentStore.PROTONS_OWN) "Proton's own" else "$name $version",
            when {
                version == ComponentStore.PROTONS_OWN -> "Whatever the game's Proton ships. The default."
                version in pick.imported -> "Imported"
                else -> "Bundled"
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (version in pick.imported && !current) {
                    OutlinedButton(onClick = { onRemove(store, version) }) { Text("Remove") }
                }
                Button(onClick = { onSelect(store, version) }, enabled = !current) { Text(if (current) "In use" else "Use") }
            }
        }
    }
    item {
        Row_("Import a $name", "A GameNative or Winlator package (.tzst or .wcp) with $contents") {
            Button(onClick = { onImport(store) }) { Text("Import…") }
        }
    }
}
