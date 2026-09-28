package com.steamoslite.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.steamoslite.frontend.FrontendExport
import com.steamoslite.frontend.HomeShortcuts
import com.steamoslite.games.InstalledGame
import com.steamoslite.games.SteamLibrary
import com.steamoslite.runtime.InstallService
import com.steamoslite.runtime.InstallStatus
import com.steamoslite.runtime.RuntimeInstaller
import com.steamoslite.stores.Store
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.Stores
import com.steamoslite.util.LogShare
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: install the runtime once, then launch SteamOS or go straight into an installed game. The
 * library has a tab each for Steam, GOG and Epic; GOG and Epic games are installed from here and
 * handed to Steam as non-Steam shortcuts.
 */
class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the list re-reads what Steam installed during the last session. */
    private var resumeCount by mutableStateOf(0)
    private var showProtons by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    /** The game whose page is open (long press on its tile, or Y / Menu on a pad). */
    private var detailsFor by mutableStateOf<InstalledGame?>(null)
    private var storeGameFor by mutableStateOf<StoreGame?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Session logs go to Download/SteamOS-Lite when the app may write there. Optional.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        setContent {
            AppTheme {
                when {
                    showProtons -> ProtonsRoute(onBack = { showProtons = false })
                    showSettings -> SettingsRoute(onBack = { showSettings = false })
                    detailsFor != null -> detailsFor?.let { game ->
                        GameDetailsRoute(
                            game,
                            onBack = { detailsFor = null },
                            onPlay = { launch(game.appId) },
                            onPin = if (HomeShortcuts.supported(this)) ({ pin(game) }) else null,
                        )
                    }
                    storeGameFor != null -> storeGameFor?.let { game ->
                        StoreGameRoute(game, onBack = { storeGameFor = null }, onPlay = { launch(Stores.launchId(game)) })
                    }
                    else -> Home(
                        resumeCount, ::launch,
                        onOpenProtons = { showProtons = true },
                        onOpenSettings = { showSettings = true },
                        onOpenDetails = { detailsFor = it },
                        onOpenStoreGame = { storeGameFor = it },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    private fun pin(game: InstalledGame) {
        if (!HomeShortcuts.pin(this, game)) {
            Toast.makeText(this, "The home screen did not accept the shortcut.", Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(appId: String?) {
        startActivity(Intent(this, SessionActivity::class.java).apply {
            if (appId != null) putExtra(SessionActivity.EXTRA_APP_ID, appId)
            putExtra(SessionActivity.EXTRA_TAPPED_AT, System.currentTimeMillis())
        })
    }
}

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1A9FFF), onPrimary = Color.White, background = Color(0xFF0E141B))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

internal sealed interface RuntimeState {
    data object Checking : RuntimeState
    /** [partialBytes]: how much of [release] an earlier, interrupted download left on disk. */
    data class Missing(val release: RuntimeInstaller.Release?, val partialBytes: Long = 0) : RuntimeState
    data class Installing(val stage: String, val percent: Int) : RuntimeState
    data class Failed(val message: String) : RuntimeState
    data class Ready(val version: String, val update: RuntimeInstaller.Release?) : RuntimeState
}

/** Home's state and actions: the runtime check, the install, and the installed-games scan. */
@Composable
private fun Home(
    resumeCount: Int,
    onLaunch: (String?) -> Unit,
    onOpenProtons: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetails: (InstalledGame) -> Unit,
    onOpenStoreGame: (StoreGame) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RuntimeState>(RuntimeState.Checking) }
    var tabId by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = Store.entries.firstOrNull { it.id == tabId }
    val (storeTab, storeActions) = rememberStoreTab(tab, resumeCount, onOpenStoreGame) { onLaunch(Stores.launchId(it)) }

    suspend fun refresh() {
        val installed = withContext(Dispatchers.IO) { RuntimeInstaller.installedVersion(context) }
        state = if (installed != null) RuntimeState.Ready(installed, null) else RuntimeState.Missing(null)
        val release = withContext(Dispatchers.IO) { RuntimeInstaller.fetchRelease() }
        val partial = release?.let { withContext(Dispatchers.IO) { RuntimeInstaller.partialBytes(context, it) } } ?: 0L
        state = when {
            installed == null -> RuntimeState.Missing(release, partial)
            release != null && release.version != installed -> RuntimeState.Ready(installed, release)
            else -> RuntimeState.Ready(installed, null)
        }
    }

    // The install itself runs in InstallService, so it carries on while the app is in the
    // background; this screen only mirrors it.
    val install by InstallService.status.collectAsState()
    LaunchedEffect(install) {
        when (install) {
            // Acknowledging turns it back to Idle, which refreshes below.
            is InstallStatus.Done -> InstallService.acknowledge()
            // On first show, and after an install finished or was cancelled: re-read what is on disk.
            is InstallStatus.Idle -> refresh()
            else -> {}
        }
    }
    val ready = state is RuntimeState.Ready
    val games by produceState(initialValue = emptyList<InstalledGame>(), resumeCount, ready) {
        if (ready) value = withContext(Dispatchers.IO) {
            // Frontends' shortcuts follow the library: games installed or removed in the last
            // session are added or dropped here (nothing happens while exporting is off).
            SteamLibrary.installedGames(context).also { FrontendExport.sync(context, it) }
        }
    }

    val hasLogs by produceState(initialValue = false, resumeCount) {
        value = withContext(Dispatchers.IO) { LogShare.hasLogs(context) }
    }
    val shown = when (val i = install) {
        is InstallStatus.Running -> RuntimeState.Installing(i.stage, i.percent)
        is InstallStatus.Failed -> RuntimeState.Failed(i.message)
        else -> state
    }
    HomeScreen(
        state = shown,
        games = games,
        onInstall = { InstallService.start(context, it) },
        onCancel = { InstallService.cancel(context) },
        onRetry = {
            InstallService.acknowledge()
            scope.launch { refresh() }
        },
        onLaunch = onLaunch,
        onShareLogs = if (hasLogs) ({ LogShare.share(context) }) else null,
        onOpenDetails = onOpenDetails,
        onOpenProtons = onOpenProtons,
        onOpenSettings = onOpenSettings,
        tab = tab,
        onSelectTab = { tabId = it?.id },
        storeTab = storeTab,
        storeActions = storeActions,
    )
}

/** Home as drawn: everything it shows comes in as arguments (the screenshot tests draw it too). */
@Composable
internal fun HomeScreen(
    state: RuntimeState,
    games: List<InstalledGame>,
    onInstall: (RuntimeInstaller.Release) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onLaunch: (String?) -> Unit,
    /** Shares the last session's logs; null hides the button (no session has run yet). */
    onShareLogs: (() -> Unit)? = null,
    /** Opens the compatibility-tools screen; null hides the button. */
    onOpenProtons: (() -> Unit)? = null,
    /** Opens a game's page (long press or Y / Menu on its tile); null leaves that out. */
    onOpenDetails: ((InstalledGame) -> Unit)? = null,
    /** Opens the settings screen; null hides the cog. */
    onOpenSettings: (() -> Unit)? = null,
    coverOf: @Composable (InstalledGame) -> Bitmap? = { loadCover(it) },
    /** The library tab shown: null is Steam. */
    tab: Store? = null,
    /** Switches the library tab; null hides the tabs. */
    onSelectTab: ((Store?) -> Unit)? = null,
    storeTab: StoreTabState? = null,
    storeActions: StoreTabActions = StoreTabActions(),
    storeCoverOf: @Composable (StoreGame) -> Bitmap? = { rememberImage(it.coverUrl.ifEmpty { null }, 400) },
) {
    // Phones in landscape have little height: everything is sized from it, and the library scrolls
    // as one grid with the header, so the header does not keep a strip of the screen for itself.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = HomeLayout.of(maxHeight)
        val title: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SteamOS Lite", fontSize = layout.titleSize, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.weight(1f))
                if (onOpenSettings != null) {
                    OutlinedButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings")
                    }
                }
            }
        }
        if (state is RuntimeState.Ready) {
            Library(state, games, layout, title, onLaunch, { state.update?.let(onInstall) }, onShareLogs, onOpenProtons, onOpenDetails, coverOf,
                tab, onSelectTab, storeTab, storeActions, storeCoverOf)
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(layout.padding)) {
                title()
                Spacer(Modifier.height(layout.gap))
                when (state) {
                    RuntimeState.Checking -> Text("Checking…", color = Color.Gray)
                    is RuntimeState.Missing -> Setup(state.release, state.partialBytes, onInstall)
                    is RuntimeState.Installing -> Progress(state, onCancel)
                    is RuntimeState.Failed -> {
                        Text(state.message, color = Color(0xFFFF8080))
                        Spacer(Modifier.height(12.dp))
                        FocusedButton("Try again", onClick = onRetry)
                    }
                    is RuntimeState.Ready -> {}
                }
            }
        }
    }
}

/**
 * Home's sizes for the screen's height. A landscape phone (~360-420 dp tall) gets small tiles and
 * tight margins, so a full row of covers fits under the header; a handheld's taller screen keeps
 * the roomier layout.
 */
internal data class HomeLayout(val padding: Dp, val gap: Dp, val titleSize: TextUnit, val buttonText: TextUnit, val tileWidth: Dp) {
    companion object {
        fun of(height: Dp): HomeLayout {
            val compact = height < 480.dp
            // A tile about half the screen tall (2:3 covers), between 96 and 210 dp.
            val tileHeight = (height * 0.5f).coerceIn(96.dp, 210.dp)
            return HomeLayout(
                padding = if (compact) 12.dp else 24.dp,
                gap = if (compact) 8.dp else 16.dp,
                titleSize = if (compact) 20.sp else 28.sp,
                buttonText = if (compact) 14.sp else 18.sp,
                tileWidth = tileHeight * (2f / 3f),
            )
        }
    }
}

@Composable
private fun Setup(release: RuntimeInstaller.Release?, partialBytes: Long, onInstall: (RuntimeInstaller.Release) -> Unit) {
    Text(
        "SteamOS needs its runtime: a Linux system with gamescope that runs Valve's own Steam client. " +
            "It is downloaded once" + (release?.let { " (${it.size / 1_000_000} MB)" } ?: "") +
            ", then Steam fetches itself the first time you launch.",
        color = Color.LightGray,
    )
    Spacer(Modifier.height(16.dp))
    if (release == null) Text("Looking up the latest runtime…", color = Color.Gray)
    else if (partialBytes > 0) FocusedButton(
        "Resume download (${partialBytes / 1_000_000} of ${release.size / 1_000_000} MB)", requestFocus = true,
    ) { onInstall(release) }
    else FocusedButton("Install runtime ${release.version}", requestFocus = true) { onInstall(release) }
}

@Composable
private fun Progress(s: RuntimeState.Installing, onCancel: () -> Unit) {
    Text(if (s.percent >= 0) "${s.stage} ${s.percent}%" else "${s.stage}…", color = Color.White)
    Spacer(Modifier.height(12.dp))
    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Text("You can leave the app: the install carries on in the background.", color = Color.Gray)
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun Library(
    s: RuntimeState.Ready,
    games: List<InstalledGame>,
    layout: HomeLayout,
    title: @Composable () -> Unit,
    onLaunch: (String?) -> Unit,
    onUpdate: () -> Unit,
    onShareLogs: (() -> Unit)?,
    onOpenProtons: (() -> Unit)?,
    onOpenDetails: ((InstalledGame) -> Unit)?,
    coverOf: @Composable (InstalledGame) -> Bitmap?,
    tab: Store?,
    onSelectTab: ((Store?) -> Unit)?,
    storeTab: StoreTabState?,
    storeActions: StoreTabActions,
    storeCoverOf: @Composable (StoreGame) -> Bitmap?,
) {
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    val tabs = listOf<Store?>(null) + Store.entries
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.tileWidth),
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // L1 / R1 step through the tabs from anywhere in the grid, as Steam's own library does.
            val step = when (e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> -1
                KeyEvent.KEYCODE_BUTTON_R1 -> 1
                else -> 0
            }
            if (step == 0 || onSelectTab == null) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown) onSelectTab(tabs[(tabs.indexOf(tab) + step).mod(tabs.size)])
            true
        },
        contentPadding = PaddingValues(layout.padding),
        horizontalArrangement = Arrangement.spacedBy(layout.gap),
        verticalArrangement = Arrangement.spacedBy(layout.gap),
    ) {
        item(key = "title", span = full) { title() }
        item(key = "actions", span = full) {
            // One line whatever the width: on a narrow screen it scrolls sideways instead of wrapping.
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(layout.gap)) {
                FocusedButton("Launch SteamOS", requestFocus = true, fontSize = layout.buttonText) { onLaunch(null) }
                if (s.update != null) OutlinedButton(onClick = onUpdate) { Text("Update runtime to ${s.update.version}") }
                if (onOpenProtons != null) OutlinedButton(onClick = onOpenProtons) { Text("Compatibility tools") }
                if (onShareLogs != null) OutlinedButton(onClick = onShareLogs) { Text("Share logs") }
            }
        }
        if (onSelectTab != null) item(key = "tabs", span = full) { LibraryTabs(tab, layout.gap, onSelectTab) }
        if (tab != null) {
            storeTabItems(storeTab, storeActions, layout, storeCoverOf)
            return@LazyVerticalGrid
        }
        item(key = "label", span = full) {
            Text(
                if (games.isEmpty()) "No games installed yet. Launch SteamOS, sign in and install some - they appear here."
                else "Installed (${games.size})",
                color = if (games.isEmpty()) Color.Gray else Color.LightGray, fontSize = 16.sp,
                modifier = Modifier.padding(top = layout.gap / 2),
            )
        }
        items(games, key = { it.appId }) { game ->
            Tile(game.name, coverOf(game), onMenu = { onOpenDetails?.invoke(game) }) { onLaunch(game.appId) }
        }
    }
}

/** The card's cover, decoded off the main thread. */
@Composable
private fun loadCover(game: InstalledGame): Bitmap? {
    val cover by produceState<Bitmap?>(initialValue = null, game.cover) {
        value = game.cover?.let { withContext(Dispatchers.IO) { decode(it) } }
    }
    return cover
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tile(title: String, cover: Bitmap?, badge: String? = null, onMenu: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(2f / 3f)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clip(shape)
            .background(Color(0xFF1E2A36), shape)
            .onKeyEvent { e ->
                val menuKey = e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                    e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                if (menuKey && e.type == KeyEventType.KeyUp) onMenu()
                menuKey
            }
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onMenu, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(title, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
        if (badge != null) {
            Text(
                badge, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xCC0E141B), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FocusedButton(label: String, requestFocus: Boolean = false, fontSize: TextUnit = 18.sp, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Button(
        onClick = onClick,
        modifier = Modifier.focusRequester(focus),
        colors = ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = fontSize) }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** A cover scaled down to card size, so a long library does not hold full-size images. */
private fun decode(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 300) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
