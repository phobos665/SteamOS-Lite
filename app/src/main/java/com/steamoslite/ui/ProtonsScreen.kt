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
    /** FEXCore versions the app has, the imported ones among them, and the one games use. */
    val fexVersions: List<String> = emptyList(),
    val fexImported: List<String> = emptyList(),
    val fexSelected: String = FexCore.PROTONS_OWN,
)

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
        val fex = withContext(Dispatchers.IO) {
            Triple(FexCore.available(context), FexCore.imported(context), FexCore.selected(context))
        }
        state = state.copy(
            engines = engines, installed = installed, queued = queued,
            fexVersions = fex.first, fexImported = fex.second, fexSelected = fex.third,
        )
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

    val fexPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { Protons.displayName(context, uri) } ?: "fexcore.tzst"
            if (!FexCore.looksLikePackage(name)) {
                state = state.copy(message = "$name is not a FEXCore package (.tzst or .wcp).")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { runCatching { FexCore.import(context, uri, name) } }
            state = state.copy(
                message = result.fold(
                    { "FEXCore $it imported. Choose it below to use it." },
                    { "Could not import $name: ${it.message}" },
                ),
            )
            reload()
        }
    }

    ProtonsScreen(
        state = state,
        onSelectFex = { version ->
            scope.launch {
                withContext(Dispatchers.IO) { FexCore.select(context, version) }
                state = state.copy(message = "Games use " + fexLabel(version) + " from the next SteamOS start.")
                reload()
            }
        },
        onImportFex = { fexPicker.launch(arrayOf("application/*", "*/*")) },
        onRemoveFex = { version ->
            scope.launch {
                withContext(Dispatchers.IO) { FexCore.remove(context, version) }
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
    onSelectFex: (String) -> Unit = {},
    onImportFex: () -> Unit = {},
    onRemoveFex: (String) -> Unit = {},
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

        item { Section("FEXCore") }
        item {
            Text(
                "The x86 emulator Proton runs games' Windows code with. Each Proton brings its own; " +
                    "choosing a version here swaps it into every game's prefix from its next start " +
                    "(a game's very first start always uses Proton's own). For one game only, set its " +
                    "launch option to BL_FEXCORE=2605 %command%.",
                color = Color.LightGray,
            )
        }
        items(listOf(FexCore.PROTONS_OWN) + state.fexVersions.reversed(), key = { "f:$it" }) { version ->
            val current = version == state.fexSelected
            Row_(
                fexLabel(version),
                when {
                    version == FexCore.PROTONS_OWN -> "Whatever the game's Proton ships. The default."
                    version in state.fexImported -> "Imported"
                    else -> "Bundled"
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (version in state.fexImported && !current) {
                        OutlinedButton(onClick = { onRemoveFex(version) }) { Text("Remove") }
                    }
                    Button(onClick = { onSelectFex(version) }, enabled = !current) { Text(if (current) "In use" else "Use") }
                }
            }
        }
        item {
            Row_("Import a FEXCore", "A GameNative or Winlator package: .tzst or .wcp with libarm64ecfex.dll and libwow64fex.dll") {
                Button(onClick = onImportFex) { Text("Import…") }
            }
        }

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

internal fun fexLabel(version: String) = if (version == FexCore.PROTONS_OWN) "Proton's own" else "FEXCore $version"
