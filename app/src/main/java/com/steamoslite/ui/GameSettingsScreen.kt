package com.steamoslite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.runtime.ComponentStore
import com.steamoslite.runtime.Dxvk
import com.steamoslite.runtime.FexCore
import com.steamoslite.runtime.GameSettings
import com.steamoslite.runtime.GameSettingsStore
import com.steamoslite.runtime.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the game settings screen shows: the game's own choices and the global ones they override. */
internal data class GameSettingsState(
    val title: String,
    val settings: GameSettings = GameSettings(),
    val fex: ComponentPick = ComponentPick(),
    val dxvk: ComponentPick = ComponentPick(),
    val globalPreset: Settings.FexPreset = Settings.FexPreset.INTERMEDIATE,
)

/** One game's settings. [id] is the id Steam starts it with (see GameSettingsStore). */
@Composable
internal fun GameSettingsRoute(id: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var state by remember(id) { mutableStateOf(GameSettingsState(title)) }
    LaunchedEffect(id) {
        state = withContext(Dispatchers.IO) {
            GameSettingsState(title, GameSettingsStore.get(context, id), FexCore.pick(context), Dxvk.pick(context), Settings.fexPreset(context))
        }
    }
    GameSettingsScreen(state, onBack) { changed ->
        state = state.copy(settings = changed)
        scope.launch(Dispatchers.IO) { GameSettingsStore.set(context, id, changed) }
    }
}

@Composable
internal fun GameSettingsScreen(state: GameSettingsState, onBack: () -> Unit, onChange: (GameSettings) -> Unit) {
    val s = state.settings
    LazyColumn(Modifier.fillMaxSize().enterFade(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(onClick = onBack) { Text("Back") }
                Text(
                    "Game settings · ${state.title}", color = AppColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!s.isDefault) SecondaryButton(onClick = { onChange(GameSettings()) }) { Text("Use global settings") }
            }
        }
        item {
            Text(
                "These override Settings for this game only, however it is started: from here, a frontend or Steam. " +
                    "They take effect the next time the game starts.",
                color = AppColors.textMuted, fontSize = 14.sp,
            )
        }
        item {
            Choice(
                "FEX preset",
                "How strictly x86 memory ordering is emulated. A stricter preset fixes hangs and crashes at some speed.",
                listOf<Settings.FexPreset?>(null) + Settings.FexPreset.entries, s.fexPreset,
                label = { it?.label ?: "Global (${state.globalPreset.label})" },
            ) { onChange(s.copy(fexPreset = it)) }
        }
        item { VersionChoice(FexCore, state.fex, s.fexCore) { onChange(s.copy(fexCore = it)) } }
        item { VersionChoice(Dxvk, state.dxvk, s.dxvk) { onChange(s.copy(dxvk = it)) } }
    }
}

/** A FEXCore or DXVK version for one game: the global choice, Proton's own, or a version, newest first. */
@Composable
private fun VersionChoice(store: ComponentStore, pick: ComponentPick, selected: String?, onSelect: (String?) -> Unit) {
    fun name(version: String) = if (version == ComponentStore.PROTONS_OWN) "Proton's own" else version
    Choice(
        "${componentName(store)} version",
        if (store == Dxvk) "Direct3D 8-11 on Vulkan." else "The x86 emulator Proton runs the game's code with.",
        listOf<String?>(null, ComponentStore.PROTONS_OWN) + pick.versions.reversed(), selected,
        label = { it?.let(::name) ?: "Global (${name(pick.selected)})" },
        onSelect = onSelect,
    )
}
