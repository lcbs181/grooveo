package dev.schlubbe.musicagent.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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

/** Dawn Music: "Sonnenaufgang am Meer" - pale sky-blue background, ocean
 * blue as the main accent, a sunrise amber as the second one, deep navy text.
 * Light-mode ramp order like [CanopyLight] (accent100 lightest). Tuned for
 * contrast on the dawn gradient's darkest stop (#B4D6F4): text 11:1, secondary
 * greys (500+) at least 5.9:1, icon grey (400) 4:1; white on the accent 5.6:1. */
val OceanDawn = CanopyColors(
    bg = Color(0xFFE4F0FB),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF0B1A2E),
    accent = Color(0xFF1665C8),
    accent2 = Color(0xFF9A4E08),
    divider = Color(0x290B1A2E),
    neutral100 = Color(0xFFFFFFFF),
    neutral200 = Color(0xFFD5E5F5),
    neutral300 = Color(0xFFB9D0E8),
    neutral400 = Color(0xFF526377),
    neutral500 = Color(0xFF3B4A5D),
    neutral600 = Color(0xFF2F3D50),
    neutral700 = Color(0xFF223042),
    neutral800 = Color(0xFF263447),
    neutral900 = Color(0xFF101A28),
    accent100 = Color(0xFFE3F0FD),
    accent200 = Color(0xFFC3DEFA),
    accent300 = Color(0xFF94C4F5),
    accent400 = Color(0xFF5AA2EC),
    accent500 = Color(0xFF1665C8),
    accent600 = Color(0xFF1356AB),
    accent700 = Color(0xFF10478F),
    accent800 = Color(0xFF0D3973),
    accent900 = Color(0xFF06203F),
    accent2_100 = Color(0xFFFCEBD6),
    accent2_500 = Color(0xFF9A4E08),
    accent2_800 = Color(0xFF6B3606),
    isDark = false,
)

/** Dawn Music at night: "Meer vor der Dämmerung" - deep navy background, a lit
 * sky blue as the main accent, warm amber as the second one. Dark-mode ramp
 * order like [CanopyDark] (accent100 darkest, accent900 the dark text on the
 * light accent). Text on bg 15:1, greys (500+) at least 6:1, accent on bg 7:1,
 * navy on the accent 7.5:1. */
val OceanDawnNight = CanopyColors(
    bg = Color(0xFF0A1626),
    surface = Color(0xFF13233A),
    text = Color(0xFFEAF2FB),
    accent = Color(0xFF6CB0F0),
    accent2 = Color(0xFFF2A65A),
    divider = Color(0x29EAF2FB),
    neutral100 = Color(0xFF13233A),
    neutral200 = Color(0xFF1A2D47),
    neutral300 = Color(0xFF243A58),
    neutral400 = Color(0xFF8193A8),
    neutral500 = Color(0xFF9DAEC2),
    neutral600 = Color(0xFFB8C6D6),
    neutral700 = Color(0xFFD0DBE7),
    neutral800 = Color(0xFFE2EAF3),
    neutral900 = Color(0xFFF2F6FB),
    accent100 = Color(0xFF0F2440),
    accent200 = Color(0xFF15335A),
    accent300 = Color(0xFF1C4677),
    accent400 = Color(0xFF2F69A8),
    accent500 = Color(0xFF6CB0F0),
    accent600 = Color(0xFF8CC3F4),
    accent700 = Color(0xFFAFD5F7),
    accent800 = Color(0xFFD3E8FB),
    accent900 = Color(0xFF06203F),
    accent2_100 = Color(0xFF3A2410),
    accent2_500 = Color(0xFFF2A65A),
    accent2_800 = Color(0xFFFAD6B0),
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

    /** Text/icons on an [accent] fill: the dark palette's light accent takes
     * accent900, a dark accent (light palettes, Dawn Music) takes white. */
    val onAccent: Color
        @Composable @ReadOnlyComposable
        get() = LocalCanopyColors.current.let { if (it.accent.luminance() < 0.35f) Color.White else it.accent900 }
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
