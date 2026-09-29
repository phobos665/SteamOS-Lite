package com.steamoslite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Every colour the launcher draws with, by role. */
@Immutable
internal data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    /** Hairlines around cards and unfocused controls. */
    val outline: Color,
    /** The ring around whatever the controller has focused; its glow is the accent. */
    val focusRing: Color,
    val accent: Color,
    val onAccent: Color,
    /** Informational text: the accent, lightened to read as body text. */
    val info: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    /** Badges and panels laid over artwork or the running session. */
    val scrim: Color,
)

internal object Palettes {
    /** Warm charcoal with an amber-orange accent. */
    val Ember = Palette(
        background = Color(0xFF0E0C0B),
        surface = Color(0xFF1A1715),
        surfaceHigh = Color(0xFF26221F),
        outline = Color(0xFF3A342F),
        focusRing = Color(0xFFFFFFFF),
        accent = Color(0xFFFF7A2F),
        onAccent = Color(0xFF1C0C02),
        info = Color(0xFFFFB787),
        text = Color(0xFFF6F1EC),
        textSecondary = Color(0xFFCEC6BD),
        textMuted = Color(0xFF9D948B),
        success = Color(0xFF6FD68A),
        warning = Color(0xFFFFD15C),
        error = Color(0xFFFF6B6B),
        scrim = Color(0xCC0E0C0B),
    )

    /** Green-tinted graphite with a jade accent. */
    val Jade = Palette(
        background = Color(0xFF0B0E0D),
        surface = Color(0xFF151A18),
        surfaceHigh = Color(0xFF1F2623),
        outline = Color(0xFF33403A),
        focusRing = Color(0xFFFFFFFF),
        accent = Color(0xFF34D399),
        onAccent = Color(0xFF03140C),
        info = Color(0xFF9EEBC8),
        text = Color(0xFFF1F5F3),
        textSecondary = Color(0xFFC4CEC9),
        textMuted = Color(0xFF8F9C96),
        success = Color(0xFFB5E36B),
        warning = Color(0xFFFFC05C),
        error = Color(0xFFFF6E6E),
        scrim = Color(0xCC0B0E0D),
    )

    /** Neutral graphite with a champagne-gold accent. */
    val GraphiteGold = Palette(
        background = Color(0xFF0D0D0D),
        surface = Color(0xFF181818),
        surfaceHigh = Color(0xFF232323),
        outline = Color(0xFF383838),
        focusRing = Color(0xFFFFFFFF),
        accent = Color(0xFFE6C27A),
        onAccent = Color(0xFF1E1606),
        info = Color(0xFFF0D9A8),
        text = Color(0xFFF5F5F4),
        textSecondary = Color(0xFFCFCFCC),
        textMuted = Color(0xFF9C9C98),
        success = Color(0xFF7ED491),
        warning = Color(0xFFFFB454),
        error = Color(0xFFFF6B6B),
        scrim = Color(0xCC0D0D0D),
    )

    /** Near-black with an acid-lime accent. */
    val Volt = Palette(
        background = Color(0xFF0B0C0A),
        surface = Color(0xFF161814),
        surfaceHigh = Color(0xFF20231D),
        outline = Color(0xFF363B30),
        focusRing = Color(0xFFFFFFFF),
        accent = Color(0xFFC8F135),
        onAccent = Color(0xFF141800),
        info = Color(0xFFE1F79A),
        text = Color(0xFFF3F5EF),
        textSecondary = Color(0xFFC9CEC1),
        textMuted = Color(0xFF959B8C),
        success = Color(0xFF5FD88C),
        warning = Color(0xFFFFB547),
        error = Color(0xFFFF6B6B),
        scrim = Color(0xCC0B0C0A),
    )
}

/** The palette the whole app is drawn with, the Compose screens and the in-session views alike. */
internal val AppColors: Palette = Palettes.Ember

internal object AppShapes {
    val card = RoundedCornerShape(14.dp)
    val tile = RoundedCornerShape(12.dp)
    val small = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(50)
}

internal object Motion {
    /** Focus and press: short enough that holding the D-pad down never lags behind. */
    const val FOCUS_MS = 160
    const val FADE_MS = 240
}

/** True where only one frame is drawn (the screenshot tests): entrance animations start settled. */
internal val LocalStillFrame = staticCompositionLocalOf { false }

/** [overlay]: drawn over the running session, so with no background of its own. */
@Composable
internal fun AppTheme(stillFrame: Boolean = false, overlay: Boolean = false, content: @Composable () -> Unit) {
    val c = AppColors
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.surfaceHigh,
        onPrimaryContainer = c.text,
        secondary = c.info,
        onSecondary = c.onAccent,
        secondaryContainer = c.surfaceHigh,
        onSecondaryContainer = c.text,
        tertiary = c.info,
        background = c.background,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.accent,
        outline = c.outline,
        outlineVariant = c.outline,
        error = c.error,
        onError = c.onAccent,
        errorContainer = c.surfaceHigh,
        onErrorContainer = c.error,
        tertiaryContainer = c.surfaceHigh,
        onTertiaryContainer = c.text,
        inversePrimary = c.onAccent,
        inverseSurface = c.text,
        inverseOnSurface = c.background,
        surfaceBright = c.surfaceHigh,
        surfaceDim = c.background,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh,
        surfaceContainerHighest = c.surfaceHigh,
    )
    CompositionLocalProvider(LocalStillFrame provides stillFrame) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = if (overlay) Color.Transparent else c.background, content = content)
        }
    }
}

/**
 * The controller's highlight: a focused control grows a little and gets an accent ring and glow, a
 * pressed one dips. Put it before any clip, so the ring and glow are not cut off.
 */
@Composable
internal fun Modifier.focusHighlight(interaction: InteractionSource, shape: Shape, scale: Float = 1.05f): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val grow by animateFloatAsState(
        when {
            pressed -> 0.97f
            focused -> scale
            else -> 1f
        },
        tween(Motion.FOCUS_MS), label = "focusScale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FOCUS_MS), label = "focusGlow")
    val accent = AppColors.accent
    val ring = AppColors.focusRing
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = grow
            scaleY = grow
            this.shape = shape
            clip = false
            shadowElevation = 14.dp.toPx() * glow
            ambientShadowColor = accent
            spotShadowColor = accent
        }
        .drawWithContent {
            drawContent()
            if (glow > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), ring.copy(alpha = glow), style = Stroke(3.dp.toPx()))
            }
        }
}

/** A page's content fading and rising into place when it is first shown. */
@Composable
internal fun Modifier.enterFade(): Modifier {
    val still = LocalInspectionMode.current || LocalStillFrame.current
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(Motion.FADE_MS)) }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 16.dp.toPx()
    }
}

/**
 * A row that scrolls sideways, with room at its ends for a focused control's ring and glow: the
 * scroll's clip is pushed out past the row's edges, and the content kept where it was.
 */
@Composable
internal fun Modifier.focusScrollRow(): Modifier {
    val room = 10.dp
    return this
        .layout { measurable, constraints ->
            val px = room.roundToPx()
            val placeable = measurable.measure(constraints.offset(horizontal = 2 * px))
            layout((placeable.width - 2 * px).coerceAtLeast(0), placeable.height) { placeable.place(-px, 0) }
        }
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = room)
}
