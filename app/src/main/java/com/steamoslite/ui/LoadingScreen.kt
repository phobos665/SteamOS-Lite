package com.steamoslite.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What the loading screen says; the session updates it as the start goes on. */
internal data class LoadingState(
    /** The game being started, or null for SteamOS itself. */
    val title: String? = null,
    /** How far the start is, 0 until [stages]: the runtime, Steam, (the game,) running. */
    val stage: Int = 0,
    val stages: Int = 3,
    val step: String = "",
    val detail: String = "",
    /** Steam is up far enough that a tap may show the screen underneath. */
    val tapToShow: Boolean = false,
    val error: String? = null,
)

private val TIPS = listOf(
    "Back opens the quick menu: keyboard, on-screen controller, and leaving SteamOS running.",
    "With Keep SteamOS running on, a game started from the app skips the whole boot.",
    "Each game has its own settings: FEX preset, FEXCore, DXVK and which Proton it runs on.",
    "Turn off Steam updates in Settings for a faster start once Steam is set up.",
    "Games on the SD card are listed with the rest; Steam sees the card as its own library.",
)

/**
 * The launch screen: the game's art blurred and darkened behind a crisp cover, its name, and where
 * the start has got to. Without a game (SteamOS itself) a slow ember glow stands in for the art.
 */
@Composable
internal fun LoadingScreen(state: LoadingState, cover: Bitmap?, backdrop: Bitmap?, onTap: () -> Unit = {}) {
    val motion = rememberInfiniteTransition(label = "loading")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val pulse by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val sweep by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "sweep")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "spin")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state.tapToShow, onClick = onTap),
    ) {
        val compact = maxHeight < 480.dp
        LoadingBackdrop(backdrop, drift)
        // Darkest at the bottom and on the left, where the text sits.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.55f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f)),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Transparent)))

        Row(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 28.dp else 64.dp, vertical = if (compact) 20.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 44.dp),
        ) {
            if (cover != null) CoverCard(cover, pulse, Modifier.height(maxHeight * if (compact) 0.55f else 0.5f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                Text(
                    if (state.title == null) "STARTING" else "LAUNCHING",
                    color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                )
                Text(
                    state.title ?: "SteamOS",
                    color = AppColors.text, fontSize = if (compact) 28.sp else 40.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                if (state.error != null) {
                    Text(state.error, color = AppColors.error, fontSize = 16.sp)
                } else {
                    StageBar(state.stage, state.stages, pulse, sweep, Modifier.fillMaxWidth(if (compact) 0.9f else 0.75f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Spinner(spin, Modifier.size(18.dp))
                        Text(state.step.ifEmpty { "Starting…" }, color = AppColors.textSecondary, fontSize = 16.sp, maxLines = 2)
                    }
                    if (state.detail.isNotEmpty()) {
                        Text(state.detail, color = AppColors.textMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = if (compact) 28.dp else 64.dp, vertical = if (compact) 14.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.tapToShow) Text("Tap to show the screen now", color = AppColors.info, fontSize = 13.sp)
            if (state.error == null) Tips()
        }
    }
}

@Composable
private fun LoadingBackdrop(art: Bitmap?, drift: Float) {
    // Shrunk to a few pixels and drawn back up: a blur on every Android version, for nothing.
    val soft = remember(art) {
        art?.takeIf { it.width > 0 && it.height > 0 }?.let {
            Bitmap.createScaledBitmap(it, 24, (24f * it.height / it.width).toInt().coerceAtLeast(1), true).asImageBitmap()
        }
    }
    if (soft != null) {
        Image(
            soft, null,
            Modifier.fillMaxSize().graphicsLayer {
                val zoom = 1.12f + 0.08f * drift
                scaleX = zoom
                scaleY = zoom
                translationX = (drift - 0.5f) * size.width * 0.04f
            },
            contentScale = ContentScale.Crop, alpha = 0.7f, filterQuality = FilterQuality.Low,
        )
    } else {
        val accent = AppColors.accent
        Box(
            Modifier.fillMaxSize().drawBehind {
                val c = Offset(size.width * (0.25f + 0.5f * drift), size.height * (0.8f - 0.3f * drift))
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), c, size.minDimension * 0.9f))
                val d = Offset(size.width * (0.85f - 0.3f * drift), size.height * 0.15f)
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent), d, size.minDimension * 0.7f))
            },
        )
    }
}

@Composable
private fun CoverCard(cover: Bitmap, pulse: Float, modifier: Modifier) {
    val glow = AppColors.accent.copy(alpha = 0.35f + 0.35f * pulse)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(24.dp + 12.dp * pulse, AppShapes.card, ambientColor = glow, spotColor = glow)
            .clip(AppShapes.card),
    ) {
        Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** One segment per stage: done ones filled, the current one pulsing with a light sweeping across it. */
@Composable
private fun StageBar(stage: Int, stages: Int, pulse: Float, sweep: Float, modifier: Modifier) {
    val accent = AppColors.accent
    Canvas(modifier.height(6.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (stages - 1)) / stages
        val r = CornerRadius(size.height / 2)
        for (i in 0 until stages) {
            val x = i * (w + gap)
            val color = when {
                i < stage -> accent
                i == stage -> accent.copy(alpha = 0.45f + 0.4f * pulse)
                else -> Color.White.copy(alpha = 0.14f)
            }
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), r)
            if (i == stage) {
                val band = w * 0.35f
                val start = x - band + (w + band) * sweep
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent), start, start + band),
                    Offset(maxOf(x, start), 0f), Size((minOf(x + w, start + band) - maxOf(x, start)).coerceAtLeast(0f), size.height), r,
                )
            }
        }
    }
}

/** An accent arc chasing its tail. */
@Composable
private fun Spinner(angle: Float, modifier: Modifier) {
    val accent = AppColors.accent
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(stroke))
        drawArc(accent, angle, 100f, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Tips() {
    var index by remember { mutableStateOf(TIPS.indices.random()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(8_000)
            index = (index + 1) % TIPS.size
        }
    }
    AnimatedContent(index, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) }, label = "tip") {
        Text(TIPS[it], color = AppColors.textMuted, fontSize = 13.sp, modifier = Modifier.widthIn(max = 620.dp))
    }
}
