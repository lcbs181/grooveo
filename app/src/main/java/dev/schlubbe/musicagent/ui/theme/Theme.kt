package dev.schlubbe.musicagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Canopy ships both themes (unlike Nocturne, which was dark-only), so these map
// each palette onto M3's slots. Dynamic color (Android 12+ wallpaper-derived
// palettes) is still deliberately not offered: Canopy's forest-green/coral pair
// is the whole brand identity, and a wallpaper-derived scheme would discard it.
private fun darkScheme(c: CanopyColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.accent900,
    primaryContainer = c.accent200,
    onPrimaryContainer = c.accent800,
    secondary = c.accent2,
    onSecondary = c.accent2_100,
    secondaryContainer = c.accent2_100,
    onSecondaryContainer = c.accent2_800,
    tertiary = c.accent600,
    background = c.bg,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.neutral200,
    onSurfaceVariant = c.neutral500,
    outline = c.divider,
    outlineVariant = c.neutral300,
)

private fun lightScheme(c: CanopyColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.neutral100,
    primaryContainer = c.accent100,
    onPrimaryContainer = c.accent800,
    secondary = c.accent2,
    onSecondary = c.neutral100,
    secondaryContainer = c.accent2_100,
    onSecondaryContainer = c.accent2_800,
    tertiary = c.accent400,
    background = c.bg,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.neutral200,
    onSurfaceVariant = c.neutral500,
    outline = c.divider,
    outlineVariant = c.neutral300,
)

/** Applies the Canopy design system. [darkTheme] defaults to true because the
 * handoff specifies dark as the app default (the light palette is `:root` in
 * the CSS purely because that's how CSS custom properties cascade -- it is not
 * the app's default state). */
@Composable
fun GrooveoTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val canopy = if (darkTheme) CanopyDark else CanopyLight
    CompositionLocalProvider(LocalCanopyColors provides canopy) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(canopy) else lightScheme(canopy),
            typography = CanopyTypography,
            shapes = CanopyShapes,
            content = content,
        )
    }
}
