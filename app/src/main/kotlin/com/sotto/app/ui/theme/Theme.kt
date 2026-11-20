package com.sotto.app.ui.theme

import android.app.Activity
import android.os.Build
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/**
 * Colours Material's scheme has no slot for: the brand gradients, the glass
 * surfaces and the chat bubbles. Read them through [Aurora.colors].
 */
@Immutable
data class AuroraColors(
    val isDark: Boolean,
    /** Primary actions and unread badges. White content sits on it. */
    val brand: List<Color>,
    /** Looping spectrum for focus glows and the orb; first and last colour match. */
    val spectrum: List<Color>,
    /** Soft light sources painted behind content, and how strongly. */
    val lights: List<Color>,
    val lightStrength: Float,
    /** Translucent card fill and its hairline edge. */
    val glass: Color,
    val glassBorder: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingBorder: Color,
    /**
     * Sent bubbles: the brand's colours, muted to sit beside received ones (as dark beside
     * dark, or pale beside white) instead of glowing; with their text, time and tick colours.
     */
    val bubbleOutgoing: List<Color>,
    val bubbleOutgoingBorder: Color,
    val onBubbleOutgoing: Color,
    val bubbleOutgoingMeta: Color,
    val bubbleOutgoingTick: Color,
    /** The read tick, bright enough to stand out on either bubble. */
    val readTick: Color,
    /** Coloured shadow under glowing elements. */
    val glow: Color,
    val success: Color,
    val warning: Color,
)

private val DarkAurora = AuroraColors(
    isDark = true,
    brand = listOf(AuroraViolet, AuroraIndigo, AuroraBlue),
    spectrum = listOf(AuroraViolet, AuroraSky, AuroraTeal, AuroraPink, AuroraViolet),
    lights = listOf(Color(0xFF6D4BFF), Color(0xFF2F6BFF), Color(0xFF13B5CC), Color(0xFFB847D6)),
    lightStrength = 0.42f,
    glass = Color.White.copy(alpha = 0.05f),
    glassBorder = Color.White.copy(alpha = 0.09f),
    bubbleIncoming = Color(0xFF1B1B29),
    bubbleIncomingBorder = Color.White.copy(alpha = 0.06f),
    bubbleOutgoing = listOf(Color(0xFF382E69), Color(0xFF2E3069), Color(0xFF243565)),
    bubbleOutgoingBorder = AuroraViolet.copy(alpha = 0.22f),
    onBubbleOutgoing = Color(0xFFF1EFFF),
    bubbleOutgoingMeta = Color.White.copy(alpha = 0.6f),
    bubbleOutgoingTick = Color.White.copy(alpha = 0.5f),
    readTick = ReadTick,
    glow = AuroraViolet,
    success = AuroraMint,
    warning = Color(0xFFFBBF24),
)

private val LightAurora = AuroraColors(
    isDark = false,
    brand = listOf(AuroraVioletDeep, AuroraIndigoDeep, AuroraBlueDeep),
    spectrum = listOf(AuroraViolet, AuroraSky, AuroraTeal, AuroraPink, AuroraViolet),
    lights = listOf(Color(0xFF9D85FF), Color(0xFF7FA9FF), Color(0xFF6EE0EE), Color(0xFFF29AD9)),
    lightStrength = 0.30f,
    glass = Color.White.copy(alpha = 0.85f),
    glassBorder = Color(0xFF1E1B4B).copy(alpha = 0.08f),
    bubbleIncoming = Color.White,
    bubbleIncomingBorder = Color(0xFF1E1B4B).copy(alpha = 0.07f),
    bubbleOutgoing = listOf(Color(0xFFEDE8FF), Color(0xFFE8E9FF), Color(0xFFE3EBFD)),
    bubbleOutgoingBorder = AuroraVioletDeep.copy(alpha = 0.14f),
    onBubbleOutgoing = Color(0xFF1E1B4B),
    bubbleOutgoingMeta = Color(0xFF1E1B4B).copy(alpha = 0.55f),
    bubbleOutgoingTick = Color(0xFF1E1B4B).copy(alpha = 0.45f),
    readTick = Color(0xFF0E9F8A),
    glow = AuroraVioletDeep,
    success = Color(0xFF0F9F6E),
    warning = Color(0xFFB7791F),
)

private val LocalAuroraColors = staticCompositionLocalOf { DarkAurora }

object Aurora {
    val colors: AuroraColors
        @Composable @ReadOnlyComposable get() = LocalAuroraColors.current
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA996FF),
    onPrimary = Color(0xFF1B0F5C),
    primaryContainer = Color(0xFF30267A),
    onPrimaryContainer = Color(0xFFE5DEFF),
    inversePrimary = Color(0xFF6A4CF0),
    secondary = Color(0xFF8DB8FF),
    onSecondary = Color(0xFF0B2552),
    secondaryContainer = Color(0xFF1C3264),
    onSecondaryContainer = Color(0xFFD8E5FF),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF0D4A43),
    onTertiaryContainer = Color(0xFFB7FFF1),
    background = Night1,
    onBackground = NightText,
    surface = Night1,
    onSurface = NightText,
    surfaceVariant = Night4,
    onSurfaceVariant = NightTextMuted,
    surfaceTint = Color(0xFFA996FF),
    inverseSurface = NightText,
    inverseOnSurface = Night2,
    error = Color(0xFFFF7A8F),
    onError = Color(0xFF4D0016),
    errorContainer = Color(0xFF431425),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = Night5,
    surfaceDim = Night1,
    surfaceContainerLowest = Night0,
    surfaceContainerLow = Night2,
    surfaceContainer = Night3,
    surfaceContainerHigh = Night4,
    surfaceContainerHighest = Night5,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1E0F66),
    inversePrimary = Color(0xFFA996FF),
    secondary = Color(0xFF2F6FE8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7FF),
    onSecondaryContainer = Color(0xFF0B2A66),
    tertiary = Color(0xFF0E8F80),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDF5EE),
    onTertiaryContainer = Color(0xFF00413A),
    background = Day1,
    onBackground = DayText,
    surface = Day1,
    onSurface = DayText,
    surfaceVariant = Day4,
    onSurfaceVariant = DayTextMuted,
    surfaceTint = Color(0xFF5B3FE6),
    inverseSurface = Color(0xFF1C1C26),
    inverseOnSurface = Color(0xFFF2F2F8),
    error = Color(0xFFD12D4E),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E6),
    onErrorContainer = Color(0xFF5C0019),
    outline = DayOutline,
    outlineVariant = DayOutlineVariant,
    scrim = Color.Black,
    surfaceBright = Day0,
    surfaceDim = Day4,
    surfaceContainerLowest = Day0,
    surfaceContainerLow = Day2,
    surfaceContainer = Day3,
    surfaceContainerHigh = Day4,
    surfaceContainerHighest = Day5,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * The app theme. Always the brand palette (no wallpaper-derived dynamic colour),
 * so the app looks the same on every device; follows the system light/dark mode.
 * System bars stay transparent (edge-to-edge) and only their icons are tinted.
 */
@Composable
fun SottoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** The "Reduce animations" setting; Android's "Remove animations" counts too. */
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val systemOff = remember(view) { !view.isInEditMode && systemAnimationsOff(view.context) }
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // A forced-dark screen (a call) owns the bar icons while it is shown.
            if ((forcedDarkScreens[window] ?: 0) > 0) return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalReduceMotion provides (reduceMotion || systemOff)) {
        AppTheme(darkTheme = darkTheme, content = content)
    }
}

/**
 * The dark look regardless of the system setting (the call screen). The bar icons
 * turn light while it is shown and go back to the app theme's once the last such
 * screen has left.
 */
@Composable
fun ForcedDarkTheme(content: @Composable () -> Unit) {
    // Read before the override below: the theme the bars return to afterwards.
    val appIsDark = Aurora.colors.isDark
    LightSystemBarIconsWhileShown(restoreLightIcons = !appIsDark)
    AppTheme(darkTheme = true, content = content)
}

@Composable
private fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAuroraColors provides if (darkTheme) DarkAurora else LightAurora) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * How many forced-dark screens each window is showing. Counted rather than saving
 * and restoring the previous icon colours: when a new call screen opens while the
 * last one is still animating out, a saved "previous" value would be stale.
 */
private val forcedDarkScreens = WeakHashMap<Window, Int>()

@Composable
private fun LightSystemBarIconsWhileShown(restoreLightIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val restore by rememberUpdatedState(restoreLightIcons)
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        forcedDarkScreens[window] = (forcedDarkScreens[window] ?: 0) + 1
        controller.setBarIcons(light = false)
        onDispose {
            val remaining = (forcedDarkScreens[window] ?: 1) - 1
            if (remaining > 0) {
                forcedDarkScreens[window] = remaining
            } else {
                forcedDarkScreens.remove(window)
                controller.setBarIcons(light = restore)
            }
        }
    }
}

private fun WindowInsetsControllerCompat.setBarIcons(light: Boolean) {
    isAppearanceLightStatusBars = light
    // Below API 29 edge-to-edge keeps an opaque light scrim behind the navigation
    // buttons in light mode; light (white) buttons would vanish on it, so those
    // stay as the app theme set them.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isAppearanceLightNavigationBars = light
}
