package dev.schlubbe.musicagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Nocturne (see the design_handoff bundle) is a single dark-mode brand identity —
// like Spotify/Apple Music/SoundCloud's own apps, there's no light variant in the
// design at all, so this always applies the dark scheme regardless of system
// theme rather than switching on isSystemInDarkTheme(). Dynamic color (Android
// 12+ wallpaper-derived palettes) is deliberately not offered for the same
// reason: Nocturne's single accent is the whole point of the identity.
private val NocturneColors = darkColorScheme(
    primary = Nocturne.accent,
    onPrimary = Nocturne.accent900,
    primaryContainer = Nocturne.accent800,
    onPrimaryContainer = Nocturne.accent100,
    secondary = Nocturne.accent2,
    onSecondary = Nocturne.accent900,
    secondaryContainer = Nocturne.accent2_800,
    onSecondaryContainer = Nocturne.accent2_100,
    tertiary = Nocturne.accent300,
    background = Nocturne.bg,
    onBackground = Nocturne.text,
    surface = Nocturne.surface,
    onSurface = Nocturne.text,
    surfaceVariant = Nocturne.neutral800,
    onSurfaceVariant = Nocturne.neutral400,
    outline = Nocturne.divider,
    outlineVariant = Nocturne.neutral700,
)

@Composable
fun MusicAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColors,
        typography = NocturneTypography,
        shapes = NocturneShapes,
        content = content,
    )
}
