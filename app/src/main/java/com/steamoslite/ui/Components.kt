package com.steamoslite.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The screen's main action: filled with the accent. */
@Composable
internal fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.focusHighlight(interaction, AppShapes.pill),
        enabled = enabled,
        shape = AppShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.accent,
            contentColor = AppColors.onAccent,
            disabledContainerColor = AppColors.surfaceHigh,
            disabledContentColor = AppColors.textMuted,
        ),
        interactionSource = interaction,
        content = content,
    )
}

/** Every other action: a raised surface with a hairline edge. */
@Composable
internal fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.focusHighlight(interaction, AppShapes.pill),
        enabled = enabled,
        shape = AppShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.surfaceHigh,
            contentColor = AppColors.text,
            disabledContainerColor = AppColors.surface,
            disabledContentColor = AppColors.textMuted,
        ),
        border = BorderStroke(1.dp, AppColors.outline),
        interactionSource = interaction,
        content = content,
    )
}

/** A raised card: the settings rows, facts and achievements sit on these. */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = AppColors.surface,
        shape = AppShapes.card,
        border = BorderStroke(1.dp, AppColors.outline.copy(alpha = 0.6f)),
        modifier = modifier,
        content = content,
    )
}

/** A card holding a column, the common case. */
@Composable
internal fun CardColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), content = content) }
}

/**
 * A segmented control: the options in one pill, with the chosen one's highlight sliding over to
 * whichever is picked next.
 */
@Composable
internal fun <T> PillTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val index = options.indexOf(selected)
    // Each option's left edge and width, once laid out.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val x = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val target = bounds[index]
    LaunchedEffect(target) {
        val (left, w) = target ?: return@LaunchedEffect
        if (width.value == 0f) {
            x.snapTo(left)
            width.snapTo(w)
        } else coroutineScope {
            launch { x.animateTo(left, tween(220)) }
            width.animateTo(w, tween(220))
        }
    }
    // Until the sliding highlight has a position (the first frame), the chosen option fills itself.
    val sliding by remember { derivedStateOf { width.value > 0f } }
    val accent = AppColors.accent
    Row(
        modifier
            .background(AppColors.surface, AppShapes.pill)
            .border(1.dp, AppColors.outline, AppShapes.pill)
            .padding(4.dp)
            .drawBehind {
                if (width.value > 0f) {
                    drawRoundRect(accent, Offset(x.value, 0f), Size(width.value, size.height), CornerRadius(size.height / 2))
                }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, option ->
            val interaction = remember { MutableInteractionSource() }
            val chosen = i == index
            val textColor by animateColorAsState(if (chosen) AppColors.onAccent else AppColors.textSecondary, tween(Motion.FOCUS_MS),
                label = "tabText")
            Box(
                Modifier
                    .onPlaced { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                    .focusHighlight(interaction, AppShapes.pill)
                    .clip(AppShapes.pill)
                    .background(if (chosen && !sliding) accent else Color.Transparent)
                    .clickable(interaction, indication = null, role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 6.dp else 10.dp),
            ) {
                Text(label(option), color = textColor, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 16.sp)
            }
        }
    }
}

/**
 * Artwork washed out behind a page, as a console's home screen does with the selected game. The art
 * is shrunk to a few pixels and stretched back up, which blurs it on every Android version for the
 * cost of one tiny bitmap.
 */
@Composable
internal fun Backdrop(art: Bitmap?, modifier: Modifier = Modifier, alpha: Float = 0.45f) {
    val soft = remember(art) {
        art?.takeIf { it.width > 0 && it.height > 0 }?.let {
            Bitmap.createScaledBitmap(it, 16, (16f * it.height / it.width).toInt().coerceAtLeast(1), true).asImageBitmap()
        }
    }
    Crossfade(soft, modifier, tween(400), label = "backdrop") { image ->
        if (image != null) Box(Modifier.fillMaxSize()) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha, filterQuality = FilterQuality.Low)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to AppColors.background.copy(alpha = 0.2f), 1f to AppColors.background),
                ),
            )
        }
    }
}
