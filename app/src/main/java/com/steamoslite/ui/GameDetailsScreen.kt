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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
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
import com.steamoslite.games.GameDetails
import com.steamoslite.games.GameDetailsReader
import com.steamoslite.games.InstalledGame
import com.steamoslite.games.StoreDetails
import com.steamoslite.games.StoreDetailsCache
import com.steamoslite.util.RemoteImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A game's page with its data: read from the runtime's Steam client, plus the store's description. */
@Composable
internal fun GameDetailsRoute(
    game: InstalledGame,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPin: (() -> Unit)?,
    onSettings: (() -> Unit)? = null,
) {
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
    GameDetailsScreen(game, details, store, onBack, onPlay, onPin, onSettings)
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
    onSettings: (() -> Unit)? = null,
    image: @Composable (source: Any?, maxPx: Int) -> Bitmap? = { source, maxPx -> rememberImage(source, maxPx) },
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        val pad = if (compact) 12.dp else 24.dp
        val hero = details?.hero?.let { image(it, 1280) }
        Backdrop(hero, Modifier.fillMaxWidth().fillMaxHeight(0.8f), alpha = 0.55f)
        LazyColumn(
            Modifier.fillMaxSize().enterFade(),
            contentPadding = PaddingValues(pad),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        ) {
            item(key = "top") {
                val play = remember { FocusRequester() }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(onClick = onBack) { Text("Back") }
                        Text(game.name, color = AppColors.text, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Row(
                        Modifier.padding(top = 10.dp).focusScrollRow(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrimaryButton(onClick = onPlay, modifier = Modifier.focusRequester(play)) {
                            Text("Play", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (onSettings != null) SecondaryButton(onClick = onSettings) { Text("Game settings") }
                        if (onPin != null) SecondaryButton(onClick = onPin) { Text("Add to home screen") }
                    }
                }
                LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
            }
            if (hero != null) {
                item(key = "hero") {
                    Image(hero.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(if (compact) 110.dp else 200.dp).clip(AppShapes.card))
                }
            }
            item(key = "facts") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val complete = details?.achievements?.allUnlocked() == true
                    facts(details).forEach { (label, value) -> Fact(label, value, trophy = complete && label == "Achievements") }
                }
            }
            if (store != null && (store.description.isNotEmpty() || store.genres.isNotEmpty())) {
                item(key = "about") {
                    Column {
                        if (store.description.isNotEmpty()) Text(store.description, color = AppColors.textSecondary)
                        if (store.genres.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(store.genres.joinToString(" · "), color = AppColors.textMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (!store?.screenshots.isNullOrEmpty()) {
                item(key = "shots") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(store!!.screenshots) { url ->
                            Box(Modifier.height(if (compact) 100.dp else 150.dp).aspectRatio(16f / 9f).clip(AppShapes.small)
                                .background(AppColors.surface)) {
                                image(url, 600)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                        }
                    }
                }
            }
            achievementItems(details, game, image, if (compact) AchievementDensity.Compact else AchievementDensity.Regular)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.achievementItems(
    details: GameDetails?,
    game: InstalledGame,
    image: @Composable (Any?, Int) -> Bitmap?,
    density: AchievementDensity,
) {
    val list = details?.achievements
    item(key = "ach-head") {
        Column {
            Text("Achievements", color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when {
                details == null -> Text("Reading…", color = AppColors.textMuted)
                list == null -> Text(
                    "None to show yet. Steam fetches a game's achievements the first time it runs in SteamOS; " +
                        "they appear here after that session.",
                    color = AppColors.textMuted,
                )
                else -> {
                    val done = list.count { it.unlocked }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$done of ${list.size} unlocked", color = AppColors.textSecondary)
                        if (list.allUnlocked()) Trophy()
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { done / list.size.toFloat() }, modifier = Modifier.fillMaxWidth().clip(AppShapes.pill))
                }
            }
        }
    }
    list?.let { achievementRows(game.appId, it, image, density) }
}

@Composable
private fun Fact(label: String, value: String, trophy: Boolean = false) {
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label.uppercase(), color = AppColors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (trophy) Trophy(16.dp)
            }
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

internal fun dateOf(unixSeconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSeconds * 1000))

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
