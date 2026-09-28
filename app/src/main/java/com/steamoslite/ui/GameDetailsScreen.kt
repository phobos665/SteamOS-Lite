package com.steamoslite.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.games.GameDetails
import com.steamoslite.games.GameDetailsReader
import com.steamoslite.games.InstalledGame
import com.steamoslite.games.StoreDetails
import com.steamoslite.games.StoreDetailsCache
import com.steamoslite.util.RemoteImages
import com.steamoslite.util.SteamFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A game's page with its data: read from the runtime's Steam client, plus the store's description. */
@Composable
internal fun GameDetailsRoute(game: InstalledGame, onBack: () -> Unit, onPlay: () -> Unit, onPin: (() -> Unit)?) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val details by produceState<GameDetails?>(null, game.appId) {
        value = withContext(Dispatchers.IO) { GameDetailsReader.read(context, game) }
    }
    var store by remember(game.appId) { mutableStateOf<StoreDetails?>(null) }
    LaunchedEffect(game.appId) {
        store = withContext(Dispatchers.IO) { StoreDetailsCache.cached(context, game.appId) }
        withContext(Dispatchers.IO) { StoreDetailsCache.load(context, game.appId) }?.let { store = it }
    }
    GameDetailsScreen(game, details, store, onBack, onPlay, onPin)
}

/** The page as drawn (the screenshot tests draw it too, with [image] loading nothing). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameDetailsScreen(
    game: InstalledGame,
    details: GameDetails?,
    store: StoreDetails?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPin: (() -> Unit)?,
    image: @Composable (source: Any?, maxPx: Int) -> Bitmap? = { source, maxPx -> rememberImage(source, maxPx) },
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        val pad = if (compact) 12.dp else 24.dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(pad),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        ) {
            item(key = "top") {
                val play = remember { FocusRequester() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Text(game.name, color = Color.White, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Button(onClick = onPlay, modifier = Modifier.focusRequester(play)) { Text("Play", fontSize = 18.sp) }
                    if (onPin != null) OutlinedButton(onClick = onPin) { Text("Add to home screen") }
                }
                LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
            }
            details?.hero?.let { hero ->
                item(key = "hero") {
                    image(hero, 1280)?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 110.dp else 200.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
            }
            item(key = "facts") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts(details).forEach { (label, value) -> Fact(label, value) }
                }
            }
            if (store != null && (store.description.isNotEmpty() || store.genres.isNotEmpty())) {
                item(key = "about") {
                    Column {
                        if (store.description.isNotEmpty()) Text(store.description, color = Color.LightGray)
                        if (store.genres.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(store.genres.joinToString(" · "), color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (!store?.screenshots.isNullOrEmpty()) {
                item(key = "shots") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(store!!.screenshots) { url ->
                            Box(Modifier.height(if (compact) 100.dp else 150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1B2530))) {
                                image(url, 600)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                        }
                    }
                }
            }
            achievementItems(details, game, image)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.achievementItems(
    details: GameDetails?,
    game: InstalledGame,
    image: @Composable (Any?, Int) -> Bitmap?,
) {
    val list = details?.achievements
    item(key = "ach-head") {
        Column {
            Text("Achievements", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when {
                details == null -> Text("Reading…", color = Color.Gray)
                list == null -> Text(
                    "None to show yet. Steam fetches a game's achievements the first time it runs in SteamOS; " +
                        "they appear here after that session.",
                    color = Color.Gray,
                )
                else -> {
                    val done = list.count { it.unlocked }
                    Text("$done of ${list.size} unlocked", color = Color.LightGray)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { done / list.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    // Unlocked first, most recent at the top; then the rest in the game's own order.
    val sorted = list.orEmpty().sortedWith(compareByDescending<SteamFiles.Achievement> { it.unlocked }.thenByDescending { it.unlockedAt })
    items(sorted, key = { "ach-" + it.id }) { a -> AchievementRow(game.appId, a, image) }
}

@Composable
private fun AchievementRow(appId: String, a: SteamFiles.Achievement, image: @Composable (Any?, Int) -> Bitmap?) {
    val secret = a.hidden && !a.unlocked
    Surface(color = Color(0xFF1B2530), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = (if (a.unlocked) a.icon else a.iconGray ?: a.icon)?.let { SteamFiles.iconUrl(appId, it) }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0E141B))) {
                image(icon, 128)?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (a.unlocked) 1f else 0.6f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (secret) "Hidden achievement" else a.name, color = if (a.unlocked) Color.White else Color.LightGray, fontSize = 15.sp)
                val desc = if (secret) "Details are shown once it is unlocked." else a.description
                if (desc.isNotEmpty()) Text(desc, color = Color.Gray, fontSize = 13.sp)
            }
            if (a.unlocked && a.unlockedAt > 0) {
                Spacer(Modifier.width(12.dp))
                Text(dateOf(a.unlockedAt), color = Color(0xFF8FD3FF), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Surface(color = Color(0xFF1B2530), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, color = Color.Gray, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 15.sp)
        }
    }
}

/** The facts row: what is known, in the order a player cares about. */
private fun facts(d: GameDetails?): List<Pair<String, String>> {
    if (d == null) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    val minutes = d.playtime?.minutes ?: 0
    out += "Played" to when {
        minutes <= 0 -> "Never"
        minutes < 60 -> "$minutes min"
        else -> String.format(java.util.Locale.US, "%.1f h", minutes / 60.0)
    }
    d.playtime?.lastPlayed?.takeIf { it > 0 }?.let { out += "Last played" to dateOf(it) }
    d.achievements?.let { list -> out += "Achievements" to "${list.count { it.unlocked }} / ${list.size}" }
    d.info?.developer?.let { out += "Developer" to it }
    d.info?.publisher?.takeIf { it != d.info?.developer }?.let { out += "Publisher" to it }
    d.info?.releaseDate?.let { out += "Released" to dateOf(it) }
    d.info?.metacritic?.let { out += "Metacritic" to "$it" }
    d.info?.deckCategory?.let { out += "Steam Deck" to (when (it) { 3 -> "Verified"; 2 -> "Playable"; 1 -> "Unsupported"; else -> "Unknown" }) }
    d.info?.controllerSupport?.let { out += "Controller" to it.replaceFirstChar(Char::uppercase) }
    return out
}

private fun dateOf(unixSeconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000))

/** A local file or a URL as a bitmap, loaded off the main thread and remembered for the composition. */
@Composable
internal fun rememberImage(source: Any?, maxPx: Int): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, source) {
        value = withContext(Dispatchers.IO) {
            when (source) {
                is File -> runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
                is String -> RemoteImages.load(context, source, maxPx)
                else -> null
            }
        }
    }
    return bitmap
}
