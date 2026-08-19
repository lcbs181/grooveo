package dev.schlubbe.musicagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A considered palette rather than Material3's raw defaults — same violet
// family used across the backend's admin dashboard and the roadmap doc, for
// one consistent identity across the whole product.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3E96),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DBF7),
    onPrimaryContainer = Color(0xFF230C55),
    secondary = Color(0xFF6B4B8A),
    background = Color(0xFFF6F4FA),
    onBackground = Color(0xFF211B2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF211B2E),
    surfaceVariant = Color(0xFFECE5F7),
    onSurfaceVariant = Color(0xFF5B5468),
    outline = Color(0xFFCDBDEC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB198E8),
    onPrimary = Color(0xFF2C2340),
    primaryContainer = Color(0xFF453667),
    onPrimaryContainer = Color(0xFFEDE9F5),
    secondary = Color(0xFFC7B3E0),
    background = Color(0xFF17121F),
    onBackground = Color(0xFFEDE9F5),
    surface = Color(0xFF1F1A2B),
    onSurface = Color(0xFFEDE9F5),
    surfaceVariant = Color(0xFF2A2438),
    onSurfaceVariant = Color(0xFFA89DBD),
    outline = Color(0xFF453667),
)

@Composable
fun MusicAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
