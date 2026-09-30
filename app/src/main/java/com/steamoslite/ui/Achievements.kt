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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steamoslite.util.SteamFiles

/** How tightly the achievement rows are drawn: compact where the screen is short (a phone on its side). */
internal enum class AchievementDensity(
    val icon: Dp,
    val padding: Dp,
    val gap: Dp,
    val title: TextUnit,
    val description: TextUnit,
    val descriptionLines: Int,
    /** Line heights; the theme's default leaves small text with lines far taller than it needs. */
    val titleLine: TextUnit = TextUnit.Unspecified,
    val descriptionLine: TextUnit = TextUnit.Unspecified,
) {
    Regular(48.dp, 10.dp, 12.dp, 15.sp, 13.sp, Int.MAX_VALUE),
    Compact(32.dp, 7.dp, 10.dp, 13.sp, 12.sp, 1, titleLine = 17.sp, descriptionLine = 15.sp);

    companion object {
        fun forHeight(height: Dp) = if (height < 520.dp) Compact else Regular
    }
}

internal fun List<SteamFiles.Achievement>.allUnlocked() = isNotEmpty() && all { it.unlocked }

private val TrophyGold = Color(0xFFF5C542)

private val TrophyIcon: ImageVector by lazy {
    ImageVector.Builder("Trophy", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1" +
                "c1.63-.33 2.98-1.46 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8z" +
                "m14 0c0 1.3-.84 2.4-2 2.82V7h2v1z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/** The gold trophy shown beside the count once every achievement is unlocked. */
@Composable
internal fun Trophy(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Icon(TrophyIcon, "Every achievement unlocked", modifier.size(size), tint = TrophyGold)
}

/**
 * The achievements as rows: unlocked ones first (most recent at the top), then locked ones in the
 * game's own order, each group under a hairline with its label and count.
 */
internal fun LazyListScope.achievementRows(
    appId: String,
    list: List<SteamFiles.Achievement>,
    image: @Composable (Any?, Int) -> Bitmap?,
    density: AchievementDensity = AchievementDensity.Regular,
    rowModifier: @Composable (index: Int) -> Modifier = { Modifier },
) {
    val unlocked = list.filter { it.unlocked }.sortedByDescending { it.unlockedAt }
    val locked = list.filterNot { it.unlocked }
    var start = 0
    for ((label, group) in listOf("Unlocked" to unlocked, "Locked" to locked)) {
        if (group.isEmpty()) continue
        item(key = "ach-group-$label") { LabelDivider("$label · ${group.size}") }
        val offset = start
        itemsIndexed(group, key = { _, a -> "ach-" + a.id }) { i, a -> AchievementRow(appId, a, image, density, rowModifier(offset + i)) }
        start += group.size
    }
}

/** A thin line with a small label in front: divides the list without starting a new section. */
@Composable
internal fun LabelDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = AppColors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(AppColors.outline))
    }
}

@Composable
internal fun AchievementRow(
    appId: String,
    a: SteamFiles.Achievement,
    image: @Composable (Any?, Int) -> Bitmap?,
    density: AchievementDensity = AchievementDensity.Regular,
    modifier: Modifier = Modifier,
) {
    val secret = a.hidden && !a.unlocked
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(density.padding), verticalAlignment = Alignment.CenterVertically) {
            val icon = (if (a.unlocked) a.icon else a.iconGray ?: a.icon)?.let { SteamFiles.iconUrl(appId, it) }
            Box(Modifier.size(density.icon).clip(AppShapes.small).background(AppColors.background)) {
                image(icon, 128)?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (a.unlocked) 1f else 0.6f))
                }
            }
            Spacer(Modifier.width(density.gap))
            Column(Modifier.weight(1f)) {
                Text(if (secret) "Hidden achievement" else a.name, color = if (a.unlocked) AppColors.text else AppColors.textSecondary,
                    fontSize = density.title, lineHeight = density.titleLine, maxLines = density.descriptionLines, overflow = TextOverflow.Ellipsis)
                val desc = if (secret) "Details are shown once it is unlocked." else a.description
                if (desc.isNotEmpty()) {
                    Text(desc, color = AppColors.textMuted, fontSize = density.description, lineHeight = density.descriptionLine,
                        maxLines = density.descriptionLines,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (a.unlocked && a.unlockedAt > 0) {
                Spacer(Modifier.width(density.gap))
                Text(dateOf(a.unlockedAt), color = AppColors.accent, fontSize = density.description, fontWeight = FontWeight.Medium)
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
        val density = AchievementDensity.forHeight(maxHeight)
        val compact = density == AchievementDensity.Compact
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .padding(start = 24.dp, top = if (compact) 10.dp else 24.dp, bottom = if (compact) 10.dp else 24.dp)
                .width(minOf(460.dp, maxWidth - 48.dp))
                .fillMaxHeight()
                .background(AppColors.surface.copy(alpha = 0.94f), shape)
                .border(1.dp, AppColors.outline, shape)
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 10.dp else 20.dp),
        ) {
            val done = list?.count { it.unlocked } ?: 0
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    // One line: the title, then the game's name in what room is left.
                    Text("Achievements", color = AppColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(title?.let { "  ·  $it" }.orEmpty(), color = AppColors.textMuted, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("Achievements", color = AppColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (title != null) {
                            Text(title, color = AppColors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!list.isNullOrEmpty()) {
                    if (list.allUnlocked()) Trophy(if (compact) 16.dp else 20.dp, Modifier.padding(end = 6.dp))
                    Text("$done / ${list.size}", color = AppColors.accent, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!list.isNullOrEmpty()) {
                LinearProgressIndicator(
                    progress = { done / list.size.toFloat() },
                    modifier = Modifier.padding(horizontal = 6.dp).padding(top = if (compact) 6.dp else 8.dp).fillMaxWidth().clip(AppShapes.pill),
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
                    Modifier.weight(1f).padding(top = if (compact) 2.dp else 6.dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    achievementRows(appId, list, image, density) { i ->
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
