package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** "Canopy" design tokens — same palette as the Android app (app/.../ui/theme/Color.kt). */
@Immutable
data class Canopy(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val text: Color, val textMuted: Color, val textFaint: Color,
    val accent: Color, val accentSoft: Color, val accentStrong: Color, val accent2: Color, val accent2Soft: Color,
    val divider: Color, val isDark: Boolean,
)

val LightCanopy = Canopy(
    bg = Color(0xFFF7F5F0), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF1EFE9), text = Color(0xFF152922),
    textMuted = Color(0xFF6B6C5F), textFaint = Color(0xFFA9AA9E), accent = Color(0xFF2E5E4E), accentSoft = Color(0xFFE6F0EB),
    accentStrong = Color(0xFF1D3F34), accent2 = Color(0xFFFF5A3C), accent2Soft = Color(0xFFFFE9E2),
    divider = Color(0x1F152922), isDark = false,
)

val DarkCanopy = Canopy(
    bg = Color(0xFF10201A), surface = Color(0xFF1B2E26), surfaceHigh = Color(0xFF233B31), text = Color(0xFFF2F5F1),
    textMuted = Color(0xFFB4C4BA), textFaint = Color(0xFF7C9086), accent = Color(0xFF4A8F76), accentSoft = Color(0xFF1D3F34),
    accentStrong = Color(0xFF95C4AE), accent2 = Color(0xFFFF7A5C), accent2Soft = Color(0xFF3A1F16),
    divider = Color(0x24F2F5F1), isDark = true,
)

val LocalCanopy = staticCompositionLocalOf { DarkCanopy }

object C {
    val c: Canopy @Composable get() = LocalCanopy.current
}

private val Archivo = FontFamily(
    Font(resource = "fonts/archivo_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/archivo_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/archivo_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/archivo_bold.ttf", weight = FontWeight.Bold),
    Font(resource = "fonts/archivo_extrabold.ttf", weight = FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Int = (size * 1.35).toInt(), spacing: Double = 0.0) =
    TextStyle(fontFamily = Archivo, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = spacing.sp)

val GrooveoTypography = Typography(
    displayLarge = style(44, FontWeight.ExtraBold, 50, -1.0),
    displayMedium = style(34, FontWeight.Bold, 40, -0.6),
    headlineLarge = style(28, FontWeight.Bold, 34, -0.4),
    headlineMedium = style(22, FontWeight.Bold, 28, -0.2),
    titleLarge = style(18, FontWeight.SemiBold),
    titleMedium = style(15, FontWeight.SemiBold),
    titleSmall = style(13, FontWeight.SemiBold),
    bodyLarge = style(15, FontWeight.Normal),
    bodyMedium = style(14, FontWeight.Normal),
    bodySmall = style(12, FontWeight.Normal),
    labelLarge = style(14, FontWeight.Medium),
    labelMedium = style(12, FontWeight.Medium),
    labelSmall = style(11, FontWeight.Medium, 14, 0.4),
)

@Composable
fun GrooveoTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val t = if (dark) DarkCanopy else LightCanopy
    val scheme = if (dark) darkColorScheme(
        primary = t.accent, onPrimary = Color.White, secondary = t.accent2, background = t.bg, onBackground = t.text,
        surface = t.surface, onSurface = t.text, surfaceVariant = t.surfaceHigh, onSurfaceVariant = t.textMuted,
        outline = t.divider, primaryContainer = t.accentSoft, onPrimaryContainer = t.accentStrong,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surfaceHigh, surfaceContainerHighest = t.surfaceHigh,
    ) else lightColorScheme(
        primary = t.accent, onPrimary = Color.White, secondary = t.accent2, background = t.bg, onBackground = t.text,
        surface = t.surface, onSurface = t.text, surfaceVariant = t.surfaceHigh, onSurfaceVariant = t.textMuted,
        outline = t.divider, primaryContainer = t.accentSoft, onPrimaryContainer = t.accentStrong,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surfaceHigh, surfaceContainerHighest = t.surfaceHigh,
    )
    CompositionLocalProvider(LocalCanopy provides t) {
        MaterialTheme(colorScheme = scheme, typography = GrooveoTypography, content = content)
    }
}
