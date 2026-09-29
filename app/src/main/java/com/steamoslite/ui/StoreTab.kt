package com.steamoslite.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.stores.Store
import com.steamoslite.stores.StoreDownload
import com.steamoslite.stores.StoreDownloadService
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.StoreLibrary
import com.steamoslite.stores.StoreLoginActivity
import com.steamoslite.stores.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A GOG or Epic tab as drawn. */
internal data class StoreTabState(
    val store: Store,
    val signedIn: Boolean,
    val games: List<StoreGame> = emptyList(),
    val installed: Set<String> = emptySet(),
    val downloads: Map<String, StoreDownload> = emptyMap(),
    val busy: String? = null,
    val error: String? = null,
)

internal class StoreTabActions(
    val onSignIn: (Store) -> Unit = {},
    val onRefresh: (Store) -> Unit = {},
    val onSignOut: (Store) -> Unit = {},
    val onOpen: (StoreGame) -> Unit = {},
    val onPlay: (StoreGame) -> Unit = {},
)

/** The selected store tab's state, read from disk and the download service, and its actions. */
@Composable
internal fun rememberStoreTab(
    store: Store?,
    resumeCount: Int,
    onOpen: (StoreGame) -> Unit,
    onPlay: (StoreGame) -> Unit,
): Pair<StoreTabState?, StoreTabActions> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val busy = remember { mutableStateMapOf<Store, String>() }
    val errors = remember { mutableStateMapOf<Store, String>() }
    val autoRefreshed = remember { mutableSetOf<Store>() }
    val downloads by StoreDownloadService.downloads.collectAsState()
    val finished = downloads.values.count { it.done }

    fun refresh(s: Store) {
        if (s in busy) return
        busy[s] = "Refreshing…"
        errors.remove(s)
        scope.launch {
            Stores.refresh(context, s) { done, total -> busy[s] = "Refreshing… $done/$total" }
                .onFailure { errors[s] = it.message ?: "Refreshing failed." }
            busy.remove(s)
            version++
        }
    }

    var signingIn by remember { mutableStateOf<Store?>(null) }
    val login = rememberLauncherForActivityResult(StoreLoginActivity.Contract()) { code ->
        val s = signingIn ?: return@rememberLauncherForActivityResult
        signingIn = null
        if (code == null) return@rememberLauncherForActivityResult
        busy[s] = "Signing in…"
        scope.launch {
            val result = Stores.signIn(context, s, code)
            busy.remove(s)
            result.onSuccess { version++; refresh(s) }.onFailure { errors[s] = "Sign-in failed: ${it.message}" }
        }
    }

    val loaded by produceState<StoreTabState?>(null, store, resumeCount, version, finished) {
        value = store?.let { s ->
            withContext(Dispatchers.IO) {
                val library = StoreLibrary(context, s)
                val installed = library.installed().keys
                StoreTabState(
                    s, Stores.signedIn(context, s),
                    library.games().sortedWith(compareBy({ it.id !in installed }, { it.title.lowercase() })),
                    installed,
                )
            }
        }
    }
    val state = loaded?.takeIf { it.store == store }?.copy(
        downloads = downloads.values.filter { it.store == store }.associateBy { it.id },
        busy = busy[store],
        error = errors[store],
    )

    LaunchedEffect(state?.store, state?.signedIn, state?.games?.isEmpty()) {
        val s = state ?: return@LaunchedEffect
        if (s.signedIn && s.games.isEmpty() && autoRefreshed.add(s.store)) refresh(s.store)
    }

    return state to StoreTabActions(
        onSignIn = { signingIn = it; login.launch(it) },
        onRefresh = ::refresh,
        onSignOut = { s -> scope.launch { Stores.signOut(context, s); version++ } },
        onOpen = onOpen,
        onPlay = onPlay,
    )
}

@Composable
internal fun LibraryTabs(selected: Store?, gap: androidx.compose.ui.unit.Dp, onSelect: (Store?) -> Unit) {
    Row(Modifier.focusScrollRow().padding(vertical = gap / 4)) {
        PillTabs(listOf<Store?>(null) + Store.entries, selected, label = { it?.label ?: "Steam" }, onSelect = onSelect)
    }
}

internal fun LazyGridScope.storeTabItems(
    state: StoreTabState?,
    actions: StoreTabActions,
    layout: HomeLayout,
    coverOf: @Composable (StoreGame) -> Bitmap?,
    /** A game's cover when its tile takes focus, for the library's backdrop. */
    onFocusArt: (Bitmap?) -> Unit = {},
) {
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    if (state == null) {
        item(key = "store-loading", span = full) { Text("Loading…", color = AppColors.textMuted) }
        return
    }
    val label = state.store.label
    if (!state.signedIn) {
        item(key = "store-signin", span = full) {
            Column(Modifier.padding(top = layout.gap / 2)) {
                Text(
                    "Sign in to $label to see your library here. Games you install from it are added to Steam as " +
                        "non-Steam games and run with Proton.",
                    color = AppColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                if (state.busy != null) Text(state.busy, color = AppColors.textMuted)
                else PrimaryButton(onClick = { actions.onSignIn(state.store) }) { Text("Sign in to $label", fontSize = layout.buttonText) }
                state.error?.let { Text(it, color = AppColors.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        return
    }
    item(key = "store-header", span = full) {
        Column(Modifier.padding(top = layout.gap / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(layout.gap)) {
                Text(
                    state.busy ?: "$label library (${state.games.size})",
                    color = AppColors.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                SecondaryButton(onClick = { actions.onRefresh(state.store) }, enabled = state.busy == null) { Text("Refresh") }
                SecondaryButton(onClick = { actions.onSignOut(state.store) }, enabled = state.busy == null) { Text("Sign out") }
            }
            state.error?.let { Text(it, color = AppColors.error) }
        }
    }
    if (state.games.isEmpty() && state.busy == null) {
        item(key = "store-empty", span = full) { Text("No games found in your $label library.", color = AppColors.textMuted) }
    }
    items(state.games, key = { "${it.store.id}:${it.id}" }) { game ->
        val installed = game.id in state.installed
        val download = state.downloads[game.id]
        val badge = when {
            installed -> "Installed"
            download == null || download.done -> null
            download.error != null -> "Failed"
            else -> "${(download.fraction * 100).toInt()}%"
        }
        val cover = coverOf(game)
        Tile(
            game.title, cover, badge,
            onMenu = { actions.onOpen(game) },
            onFocused = { onFocusArt(cover) },
            onClick = { actions.onOpen(game) },
        )
    }
}
