package app.clipflow.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Canvas = Color(0xFFF5F5F5)
val Surface = Color(0xFFFAFAFA)
val Raised = Color(0xFFFFFFFF)
val Ink = Color(0xFF262626)
val Muted = Color(0xFF737373)
val Border = Color(0xFFD4D4D4)
val StrongBorder = Color(0xFF171717)
val Accent = Color(0xFF0070F3)
val AccentTint = Color(0xFFE8F1FE)
val Danger = Color(0xFFE5484D)
val Success = Color(0xFF0F7B3F)

private val ClipFlowColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentTint,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = Surface,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Color.White,
    tertiaryContainer = Surface,
    onTertiaryContainer = Ink,
    background = Canvas,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink,
    inverseOnSurface = Raised,
    outline = Border,
    outlineVariant = Border,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E9),
    onErrorContainer = Ink,
    scrim = Color.Black,
    surfaceBright = Raised,
    surfaceContainer = Raised,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Raised,
    surfaceContainerLow = Surface,
    surfaceContainerLowest = Raised,
    surfaceDim = Canvas,
)

@Composable
fun ClipFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClipFlowColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
