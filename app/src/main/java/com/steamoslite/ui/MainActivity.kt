package com.steamoslite.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.games.InstalledGame
import com.steamoslite.games.SteamLibrary
import com.steamoslite.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home: install the runtime once, then launch SteamOS or go straight into an installed game.
 * Everything else - signing in, installing games, settings - happens inside SteamOS itself.
 */
class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the list re-reads what Steam installed during the last session. */
    private var resumeCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Session logs go to Download/SteamOS-Lite when the app may write there. Optional.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1A9FFF), background = Color(0xFF0E141B))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Home(resumeCount, ::launch)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    private fun launch(appId: String?) {
        startActivity(Intent(this, SessionActivity::class.java).apply {
            if (appId != null) putExtra(SessionActivity.EXTRA_APP_ID, appId)
        })
    }
}

private sealed interface RuntimeState {
    data object Checking : RuntimeState
    data class Missing(val release: RuntimeInstaller.Release?) : RuntimeState
    data class Installing(val stage: String, val percent: Int) : RuntimeState
    data class Failed(val message: String) : RuntimeState
    data class Ready(val version: String, val update: RuntimeInstaller.Release?) : RuntimeState
}

@Composable
private fun Home(resumeCount: Int, onLaunch: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RuntimeState>(RuntimeState.Checking) }

    suspend fun refresh() {
        val installed = withContext(Dispatchers.IO) { RuntimeInstaller.installedVersion(context) }
        state = if (installed != null) RuntimeState.Ready(installed, null) else RuntimeState.Missing(null)
        val release = withContext(Dispatchers.IO) { RuntimeInstaller.fetchRelease() }
        state = when {
            installed == null -> RuntimeState.Missing(release)
            release != null && release.version != installed -> RuntimeState.Ready(installed, release)
            else -> RuntimeState.Ready(installed, null)
        }
    }

    fun install(release: RuntimeInstaller.Release) {
        scope.launch {
            state = RuntimeState.Installing("Downloading", 0)
            val ok = withContext(Dispatchers.IO) {
                RuntimeInstaller.install(context, release) { stage, percent ->
                    scope.launch(Dispatchers.Main) { state = RuntimeState.Installing(stage, percent) }
                }
            }
            if (ok) refresh() else state = RuntimeState.Failed("The install did not finish. Check the connection and free space, then try again.")
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("SteamOS Lite", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            RuntimeState.Checking -> Text("Checking…", color = Color.Gray)
            is RuntimeState.Missing -> Setup(s.release, ::install)
            is RuntimeState.Installing -> Progress(s)
            is RuntimeState.Failed -> {
                Text(s.message, color = Color(0xFFFF8080))
                Spacer(Modifier.height(12.dp))
                FocusedButton("Try again") { scope.launch { refresh() } }
            }
            is RuntimeState.Ready -> Library(s, resumeCount, onLaunch) { s.update?.let(::install) }
        }
    }
}

@Composable
private fun Setup(release: RuntimeInstaller.Release?, onInstall: (RuntimeInstaller.Release) -> Unit) {
    Text(
        "SteamOS needs its runtime: a Linux system with gamescope that runs Valve's own Steam client. " +
            "It is downloaded once" + (release?.let { " (${it.size / (1024 * 1024)} MB)" } ?: "") +
            ", then Steam fetches itself the first time you launch.",
        color = Color.LightGray,
    )
    Spacer(Modifier.height(16.dp))
    if (release == null) Text("Looking up the latest runtime…", color = Color.Gray)
    else FocusedButton("Install runtime ${release.version}") { onInstall(release) }
}

@Composable
private fun Progress(s: RuntimeState.Installing) {
    Text(if (s.percent >= 0) "${s.stage} ${s.percent}%" else "${s.stage}…", color = Color.White)
    Spacer(Modifier.height(12.dp))
    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Library(s: RuntimeState.Ready, resumeCount: Int, onLaunch: (String?) -> Unit, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val games by produceState(initialValue = emptyList<InstalledGame>(), resumeCount) {
        value = withContext(Dispatchers.IO) { SteamLibrary.installedGames(context) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FocusedButton("Launch SteamOS", requestFocus = true) { onLaunch(null) }
        if (s.update != null) {
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onUpdate) { Text("Update runtime to ${s.update.version}") }
        }
    }
    Spacer(Modifier.height(24.dp))
    if (games.isEmpty()) {
        Text("No games installed yet. Launch SteamOS, sign in and install some - they appear here.", color = Color.Gray)
        return
    }
    Text("Installed", color = Color.LightGray, fontSize = 16.sp)
    Spacer(Modifier.height(8.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(games, key = { it.appId }) { game -> GameCard(game) { onLaunch(game.appId) } }
    }
}

@Composable
private fun GameCard(game: InstalledGame, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val cover by produceState<Bitmap?>(initialValue = null, game.cover) {
        value = game.cover?.let { withContext(Dispatchers.IO) { decode(it) } }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(2f / 3f)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .background(Color(0xFF1E2A36), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), game.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(game.name, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun FocusedButton(label: String, requestFocus: Boolean = false, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Button(
        onClick = onClick,
        modifier = Modifier.focusRequester(focus),
        colors = ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = 18.sp) }
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
