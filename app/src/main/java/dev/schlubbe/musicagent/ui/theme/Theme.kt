package dev.schlubbe.musicagent.ui.theme

import androidx.compose.material3.MaterialTheme
import dev.schlubbe.musicagent.BuildConfig
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
    // Dialogs, menus, sheets, search bars and snackbars draw from these; left
    // unset they fall back to Material's purple-grey defaults.
    surfaceTint = c.accent,
    surfaceDim = c.bg,
    surfaceBright = c.neutral300,
    surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.neutral200,
    surfaceContainerHighest = c.neutral300,
    inverseSurface = c.text,
    inverseOnSurface = c.bg,
    inversePrimary = c.accent400,
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
    surfaceTint = c.accent,
    surfaceDim = c.neutral200,
    surfaceBright = c.surface,
    surfaceContainerLowest = c.surface,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.neutral100,
    inverseSurface = c.neutral900,
    inverseOnSurface = c.neutral100,
    inversePrimary = c.accent300,
)

/** Applies the Canopy design system. [darkTheme] defaults to true because the
 * handoff specifies dark as the app default (the light palette is `:root` in
 * the CSS purely because that's how CSS custom properties cascade -- it is not
 * the app's default state). */
@Composable
fun GrooveoTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    // Dawn Music: dressed for today's holiday (Halloween, Advent ...), if any
    val holiday = if (BuildConfig.DAWN) androidx.compose.runtime.remember { holidayFor(HolidayClock.today()) } else null
    val canopy = when {
        // at night the holiday keeps its greeting and art, not its daylight colours
        BuildConfig.DAWN && darkTheme -> OceanDawnNight
        BuildConfig.DAWN -> OceanDawn.withHoliday(holiday?.look)
        darkTheme -> CanopyDark
        else -> CanopyLight
    }
    if (BuildConfig.DAWN) {
        // status and navigation bar icons dark on the light sky, light at night
        val view = androidx.compose.ui.platform.LocalView.current
        if (!view.isInEditMode) {
            androidx.compose.runtime.SideEffect {
                val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !canopy.isDark
                    isAppearanceLightNavigationBars = !canopy.isDark
                }
            }
        }
    }
    CompositionLocalProvider(LocalCanopyColors provides canopy, LocalHoliday provides holiday) {
        MaterialTheme(
            colorScheme = if (canopy.isDark) darkScheme(canopy) else lightScheme(canopy),
            typography = CanopyTypography,
            shapes = CanopyShapes,
            content = content,
        )
    }
}
