package dev.schlubbe.musicagent.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Canopy design tokens. Unlike the Nocturne system this replaced, Canopy ships
 * *both* a light and a dark palette, and the accent ramp inverts between them
 * (accent100 is the lightest tint in light mode and the darkest shade in dark
 * mode). That's why these are instance properties resolved through
 * [LocalCanopyColors] rather than a single static object: reading a fixed
 * `accent100` outside the theme would silently give the wrong end of the ramp.
 *
 * Values are copied verbatim from the handoff's `_ds/copy-of-canopy-<id>/styles.css`
 * so this file stays a direct diff target if the tokens change. */
data class CanopyColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val accent2: Color,
    val divider: Color,
    val neutral100: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val neutral900: Color,
    val accent100: Color,
    val accent200: Color,
    val accent300: Color,
    val accent400: Color,
    val accent500: Color,
    val accent600: Color,
    val accent700: Color,
    val accent800: Color,
    val accent900: Color,
    val accent2_100: Color,
    val accent2_500: Color,
    val accent2_800: Color,
    val isDark: Boolean,
)

/** styles.css `:root` (the light palette). */
val CanopyLight = CanopyColors(
    bg = Color(0xFFF7F5F0),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF152922),
    accent = Color(0xFF2E5E4E),
    accent2 = Color(0xFFFF5A3C),
    // rgba(21,41,34,.12) -- expressed as a Compose ARGB alpha (12% of 255 = 0x1F)
    // rather than a runtime color-mix.
    divider = Color(0x1F152922),
    neutral100 = Color(0xFFFFFFFF),
    neutral200 = Color(0xFFF1EFE9),
    neutral300 = Color(0xFFE3E0D6),
    neutral400 = Color(0xFFA9AA9E),
    neutral500 = Color(0xFF8B8C7F),
    neutral600 = Color(0xFF6B6C5F),
    neutral700 = Color(0xFF4C4D42),
    neutral800 = Color(0xFF2E2F28),
    neutral900 = Color(0xFF181910),
    accent100 = Color(0xFFE6F0EB),
    accent200 = Color(0xFFC3DED2),
    accent300 = Color(0xFF95C4AE),
    accent400 = Color(0xFF5E9E85),
    accent500 = Color(0xFF2E5E4E),
    accent600 = Color(0xFF254C40),
    accent700 = Color(0xFF1D3F34),
    accent800 = Color(0xFF152E27),
    accent900 = Color(0xFF0D1F1A),
    accent2_100 = Color(0xFFFFE9E2),
    accent2_500 = Color(0xFFFF5A3C),
    accent2_800 = Color(0xFF7A2415),
    isDark = false,
)

/** styles.css `[data-theme="dark"]` -- the app's default. */
val CanopyDark = CanopyColors(
    bg = Color(0xFF10201A),
    surface = Color(0xFF1B2E26),
    text = Color(0xFFF2F5F1),
    accent = Color(0xFF4A8F76),
    accent2 = Color(0xFFFF7A5C),
    // rgba(242,245,241,.14) -- 14% of 255 = 0x24.
    divider = Color(0x24F2F5F1),
    neutral100 = Color(0xFF1B2E26),
    neutral200 = Color(0xFF233B31),
    neutral300 = Color(0xFF2E4A3E),
    neutral400 = Color(0xFF7C9086),
    neutral500 = Color(0xFF96A89D),
    neutral600 = Color(0xFFB4C4BA),
    neutral700 = Color(0xFFCFDBD3),
    neutral800 = Color(0xFFE4EAE5),
    neutral900 = Color(0xFFF2F5F1),
    accent100 = Color(0xFF152E27),
    accent200 = Color(0xFF1D3F34),
    accent300 = Color(0xFF254C40),
    accent400 = Color(0xFF2E5E4E),
    accent500 = Color(0xFF4A8F76),
    accent600 = Color(0xFF6FB49A),
    accent700 = Color(0xFF95C4AE),
    accent800 = Color(0xFFC3DED2),
    accent900 = Color(0xFF0D1F1A),
    accent2_100 = Color(0xFF3A1F16),
    accent2_500 = Color(0xFFFF7A5C),
    accent2_800 = Color(0xFFFFCEC0),
    isDark = true,
)

val LocalCanopyColors = staticCompositionLocalOf { CanopyDark }

/** Token accessor mirroring `MaterialTheme.colorScheme`'s own shape: call sites
 * read `Canopy.accent` and get whichever palette the enclosing [GrooveoTheme]
 * provided, so a screen never hardcodes one theme's value. */
object Canopy {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.bg
    val surface: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.surface
    val text: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.text
    val accent: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent
    val accent2: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent2
    val divider: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.divider
    val neutral100: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral100
    val neutral200: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral200
    val neutral300: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral300
    val neutral400: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral400
    val neutral500: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral500
    val neutral600: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral600
    val neutral700: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral700
    val neutral800: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral800
    val neutral900: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.neutral900
    val accent100: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent100
    val accent200: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent200
    val accent300: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent300
    val accent400: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent400
    val accent500: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent500
    val accent600: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent600
    val accent700: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent700
    val accent800: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent800
    val accent900: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent900
    val accent2_100: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent2_100
    val accent2_500: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent2_500
    val accent2_800: Color @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.accent2_800
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalCanopyColors.current.isDark
}

/** The 4 playlist accent swatches from the edit sheet: "Auto" (a deterministic
 * per-id hash color, matching the handoff's generated-artwork approach for
 * covers with no real image), then the 3 explicit choices. [colorKey] is the
 * persisted [dev.schlubbe.musicagent.data.local.entity.PlaylistEntity.accentColorKey]
 * ("accent"/"accent2"/"neutral", or null for Auto) -- shared by Home's shelf
 * swatch, Library's playlist cards, the detail screen's cover slot, and the
 * edit sheet's own swatch preview, so all four always agree on the same color. */
@Composable
@ReadOnlyComposable
fun accentColorFor(colorKey: String?, autoSeed: String): Color = when (colorKey) {
    "accent" -> Canopy.accent
    "accent2" -> Canopy.accent2
    "neutral" -> Canopy.neutral600
    else -> {
        val autoPalette = listOf(Canopy.accent500, Canopy.accent400, Canopy.accent600, Canopy.accent300)
        autoPalette[kotlin.math.abs(autoSeed.hashCode()) % autoPalette.size]
    }
}
