package com.steamoslite.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.stores.Installation
import com.steamoslite.stores.StoreDownload
import com.steamoslite.stores.StoreDownloadService
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.StoreLibrary
import com.steamoslite.stores.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A GOG or Epic game's page with its data and actions. */
@Composable
internal fun StoreGameRoute(game: StoreGame, onBack: () -> Unit, onPlay: () -> Unit, onSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var installation by remember(game.id) { mutableStateOf<Installation?>(null) }
    val downloads by StoreDownloadService.downloads.collectAsState()
    val download = downloads[StoreDownloadService.keyOf(game.store, game.id)]
    LaunchedEffect(game.id, download?.done) {
        installation = withContext(Dispatchers.IO) { StoreLibrary(context, game.store).installation(game.id) }
    }
    StoreGameScreen(
        game, installation, download, onBack, onPlay,
        onSettings = onSettings,
        onInstall = { StoreDownloadService.enqueue(context, game) },
        onCancel = { StoreDownloadService.cancel(context, game.store, game.id) },
        onUninstall = {
            scope.launch {
                Stores.uninstall(context, game)
                StoreDownloadService.dismiss(game.store, game.id)
                installation = null
            }
        },
    )
}

@Composable
internal fun StoreGameScreen(
    game: StoreGame,
    installation: Installation?,
    download: StoreDownload?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onSettings: (() -> Unit)? = null,
    image: @Composable (source: Any?, maxPx: Int) -> Bitmap? = { source, maxPx -> rememberImage(source, maxPx) },
) {
    var confirmUninstall by remember(game.id) { mutableStateOf(false) }
    val running = download != null && !download.done && download.error == null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        val pad = if (compact) 12.dp else 24.dp
        val hero = game.heroUrl.ifEmpty { null }?.let { image(it, 1280) }
        Backdrop(hero, Modifier.fillMaxWidth().fillMaxHeight(0.8f), alpha = 0.55f)
        LazyColumn(Modifier.fillMaxSize().enterFade(), contentPadding = PaddingValues(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(onClick = onBack) { Text("Back") }
                    Text(game.title, color = AppColors.text, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            item {
                val primary = remember { FocusRequester() }
                Row(
                    Modifier.focusScrollRow(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        running -> SecondaryButton(onClick = onCancel, modifier = Modifier.focusRequester(primary)) { Text("Cancel download") }
                        installation != null -> {
                            PrimaryButton(onClick = onPlay, modifier = Modifier.focusRequester(primary)) {
                                Text("Play", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (onSettings != null) SecondaryButton(onClick = onSettings) { Text("Game settings") }
                            SecondaryButton(onClick = onInstall) { Text("Update") }
                            SecondaryButton(onClick = { if (confirmUninstall) onUninstall() else confirmUninstall = true }) {
                                Text(if (confirmUninstall) "Confirm uninstall" else "Uninstall")
                            }
                        }
                        else -> PrimaryButton(onClick = onInstall, modifier = Modifier.focusRequester(primary)) {
                            Text(if (download?.error != null) "Retry" else "Install" + sizeLabel(game.downloadSize), fontSize = 18.sp)
                        }
                    }
                }
                LaunchedEffect(installation != null, running) { runCatching { primary.requestFocus() } }
            }
            if (hero != null) {
                item {
                    Image(hero.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(if (compact) 110.dp else 200.dp).clip(AppShapes.card))
                }
            }
            if (download != null && (installation == null || running || download.error != null)) {
                item {
                    if (download.error != null) {
                        Text("Download failed: ${download.error}", color = AppColors.error)
                    } else {
                        Text("${download.stage} · ${(download.fraction * 100).toInt()}%", color = AppColors.text)
                        LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth().clip(AppShapes.pill))
                        Text("It carries on in the background.", color = AppColors.textMuted, fontSize = 13.sp)
                    }
                }
            }
            if (installation != null) {
                item {
                    Text(
                        "Installed" + sizeLabel(installation.sizeBytes) + ". It is in the Steam library as a " +
                            "non-Steam game from the next time SteamOS starts; Play starts it straight away.",
                        color = AppColors.info,
                    )
                }
            }
            val dlc = installation?.dlc ?: game.dlc.map { it.title }
            if (dlc.isNotEmpty()) {
                item {
                    Text(
                        (if (installation != null) "DLC installed: " else "Owned DLC, installed with the game: ") + dlc.joinToString(", "),
                        color = AppColors.textSecondary,
                    )
                }
            }
            if (installation != null) {
                item {
                    Text("Update fetches the latest build and any DLC bought since, keeping files that are already right.",
                        color = AppColors.textMuted, fontSize = 13.sp)
                }
            }
            if (game.thirdPartyManagedApp.isNotEmpty()) {
                item {
                    Text(
                        "This game needs ${game.thirdPartyManagedApp}, which does not run here, so it may not start.",
                        color = AppColors.warning,
                    )
                }
            }
            val facts = listOfNotNull(
                game.developer.takeIf { it.isNotEmpty() }?.let { "Developer: $it" },
                game.publisher.takeIf { it.isNotEmpty() && it != game.developer }?.let { "Publisher: $it" },
                game.releaseDate.takeIf { it.isNotEmpty() }?.let { "Released: ${it.take(10)}" },
            )
            if (facts.isNotEmpty()) item { Text(facts.joinToString("   ·   "), color = AppColors.textSecondary, fontSize = 14.sp) }
            if (game.description.isNotEmpty()) {
                item { Text(stripHtml(game.description), color = AppColors.textSecondary) }
            }
        }
    }
}

private fun sizeLabel(bytes: Long) = if (bytes > 0) " (%.1f GB)".format(bytes / 1e9) else ""

private fun stripHtml(html: String) = html
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
