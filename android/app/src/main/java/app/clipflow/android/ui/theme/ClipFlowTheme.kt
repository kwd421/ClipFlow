package app.clipflow.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ClipFlowPalette(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
    val strongBorder: Color,
    val accent: Color,
    val accentTint: Color,
    val danger: Color,
    val success: Color,
    val progressTrack: Color,
    val thumbPlaceholder: Color,
)

val LightPalette = ClipFlowPalette(
    canvas = Color(0xFFF5F5F5),
    surface = Color(0xFFFAFAFA),
    raised = Color(0xFFFFFFFF),
    ink = Color(0xFF262626),
    muted = Color(0xFF737373),
    border = Color(0xFFD4D4D4),
    strongBorder = Color(0xFF171717),
    accent = Color(0xFF0070F3),
    accentTint = Color(0xFFE8F1FE),
    danger = Color(0xFFE5484D),
    success = Color(0xFF0F7B3F),
    progressTrack = Color(0xFFE5E5E5),
    thumbPlaceholder = Color(0xFFE5E5E5),
)

val DarkPalette = ClipFlowPalette(
    canvas = Color(0xFF121212),
    surface = Color(0xFF1C1C1C),
    raised = Color(0xFF222222),
    ink = Color(0xFFE8E8E8),
    muted = Color(0xFFA3A3A3),
    border = Color(0xFF333333),
    strongBorder = Color(0xFF5A5A5A),
    accent = Color(0xFF3B9EFF),
    accentTint = Color(0xFF1A2A3D),
    danger = Color(0xFFFF6B6F),
    success = Color(0xFF3DDB84),
    progressTrack = Color(0xFF333333),
    thumbPlaceholder = Color(0xFF2C2C2C),
)

val LocalClipFlowPalette = staticCompositionLocalOf { LightPalette }

// Backward-compatible aliases used across the UI.
val Canvas get() = LightPalette.canvas
val Surface get() = LightPalette.surface
val Raised get() = LightPalette.raised
val Ink get() = LightPalette.ink
val Muted get() = LightPalette.muted
val Border get() = LightPalette.border
val StrongBorder get() = LightPalette.strongBorder
val Accent get() = LightPalette.accent
val AccentTint get() = LightPalette.accentTint
val Danger get() = LightPalette.danger
val Success get() = LightPalette.success

@Composable
fun clipPalette(): ClipFlowPalette = LocalClipFlowPalette.current

private fun ClipFlowPalette.toLightScheme() = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accentTint,
    onPrimaryContainer = ink,
    secondary = ink,
    onSecondary = Color.White,
    secondaryContainer = surface,
    onSecondaryContainer = ink,
    tertiary = ink,
    onTertiary = Color.White,
    tertiaryContainer = surface,
    onTertiaryContainer = ink,
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = raised,
    onSurfaceVariant = muted,
    surfaceTint = Color.Transparent,
    inverseSurface = ink,
    inverseOnSurface = raised,
    outline = border,
    outlineVariant = border,
    error = danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E9),
    onErrorContainer = ink,
    scrim = Color.Black,
    surfaceBright = raised,
    surfaceContainer = raised,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = raised,
    surfaceContainerLow = surface,
    surfaceContainerLowest = raised,
    surfaceDim = canvas,
)

private fun ClipFlowPalette.toDarkScheme() = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF121212),
    primaryContainer = accentTint,
    onPrimaryContainer = ink,
    secondary = ink,
    onSecondary = Color(0xFF121212),
    secondaryContainer = surface,
    onSecondaryContainer = ink,
    tertiary = ink,
    onTertiary = Color(0xFF121212),
    tertiaryContainer = surface,
    onTertiaryContainer = ink,
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = raised,
    onSurfaceVariant = muted,
    surfaceTint = Color.Transparent,
    inverseSurface = ink,
    inverseOnSurface = canvas,
    outline = border,
    outlineVariant = border,
    error = danger,
    onError = Color(0xFF121212),
    errorContainer = Color(0xFF3A1A1B),
    onErrorContainer = ink,
    scrim = Color.Black,
    surfaceBright = raised,
    surfaceContainer = raised,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = raised,
    surfaceContainerLow = surface,
    surfaceContainerLowest = raised,
    surfaceDim = canvas,
)

@Composable
fun ClipFlowTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalClipFlowPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) palette.toDarkScheme() else palette.toLightScheme(),
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
