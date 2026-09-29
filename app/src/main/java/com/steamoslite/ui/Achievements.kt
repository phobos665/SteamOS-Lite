package com.steamoslite.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.util.SteamFiles

/**
 * The achievements as rows: unlocked ones first (most recent at the top), then locked ones in the
 * game's own order, each group under a hairline with its label and count.
 */
internal fun LazyListScope.achievementRows(
    appId: String,
    list: List<SteamFiles.Achievement>,
    image: @Composable (Any?, Int) -> Bitmap?,
    rowModifier: @Composable (index: Int) -> Modifier = { Modifier },
) {
    val unlocked = list.filter { it.unlocked }.sortedByDescending { it.unlockedAt }
    val locked = list.filterNot { it.unlocked }
    var start = 0
    for ((label, group) in listOf("Unlocked" to unlocked, "Locked" to locked)) {
        if (group.isEmpty()) continue
        item(key = "ach-group-$label") { AchievementDivider("$label · ${group.size}") }
        val offset = start
        itemsIndexed(group, key = { _, a -> "ach-" + a.id }) { i, a -> AchievementRow(appId, a, image, rowModifier(offset + i)) }
        start += group.size
    }
}

/** A thin line with a small label in front: divides the list without starting a new section. */
@Composable
internal fun AchievementDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = AppColors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(AppColors.outline))
    }
}

@Composable
internal fun AchievementRow(appId: String, a: SteamFiles.Achievement, image: @Composable (Any?, Int) -> Bitmap?, modifier: Modifier = Modifier) {
    val secret = a.hidden && !a.unlocked
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = (if (a.unlocked) a.icon else a.iconGray ?: a.icon)?.let { SteamFiles.iconUrl(appId, it) }
            Box(Modifier.size(48.dp).clip(AppShapes.small).background(AppColors.background)) {
                image(icon, 128)?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (a.unlocked) 1f else 0.6f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (secret) "Hidden achievement" else a.name, color = if (a.unlocked) AppColors.text else AppColors.textSecondary, fontSize = 15.sp)
                val desc = if (secret) "Details are shown once it is unlocked." else a.description
                if (desc.isNotEmpty()) Text(desc, color = AppColors.textMuted, fontSize = 13.sp)
            }
            if (a.unlocked && a.unlockedAt > 0) {
                Spacer(Modifier.width(12.dp))
                Text(dateOf(a.unlockedAt), color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * The quick menu's achievements page for the game the session was started for, as a panel over
 * the picture where the menu sits. [read] is false while the list is still being read; a null
 * [list] once read means the client has none saved for the game. B, back or a tap outside closes it.
 */
@Composable
internal fun SessionAchievements(
    appId: String,
    title: String?,
    read: Boolean,
    list: List<SteamFiles.Achievement>?,
    onClose: () -> Unit,
    image: @Composable (source: Any?, maxPx: Int) -> Bitmap? = { source, maxPx -> rememberImage(source, maxPx) },
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        val compact = maxHeight < 480.dp
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .padding(start = 24.dp, top = if (compact) 12.dp else 24.dp, bottom = if (compact) 12.dp else 24.dp)
                .width(minOf(460.dp, maxWidth - 48.dp))
                .fillMaxHeight()
                .background(AppColors.surface.copy(alpha = 0.94f), shape)
                .border(1.dp, AppColors.outline, shape)
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = 14.dp, vertical = if (compact) 12.dp else 20.dp),
        ) {
            val done = list?.count { it.unlocked } ?: 0
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Achievements", color = AppColors.text, fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Bold)
                    if (title != null) {
                        Text(title, color = AppColors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!list.isNullOrEmpty()) {
                    Text("$done / ${list.size}", color = AppColors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!list.isNullOrEmpty()) {
                LinearProgressIndicator(
                    progress = { done / list.size.toFloat() },
                    modifier = Modifier.padding(horizontal = 6.dp).padding(top = 8.dp).fillMaxWidth().clip(AppShapes.pill),
                )
            }
            if (list.isNullOrEmpty()) {
                Text(
                    if (!read) "Reading…" else "None to show yet. Steam saves a game's achievements once it has run; check back in a little while.",
                    color = AppColors.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp).padding(top = 12.dp),
                )
            } else {
                val first = remember { FocusRequester() }
                LazyColumn(
                    Modifier.weight(1f).padding(top = 6.dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                ) {
                    achievementRows(appId, list, image) { i ->
                        val interaction = remember { MutableInteractionSource() }
                        (if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .focusHighlight(interaction, AppShapes.card, scale = 1.02f)
                            .focusable(interactionSource = interaction)
                    }
                }
                LaunchedEffect(list) {
                    withFrameNanos {} // the list's rows are laid out a frame after it is composed
                    runCatching { first.requestFocus() }
                }
            }
        }
    }
}
