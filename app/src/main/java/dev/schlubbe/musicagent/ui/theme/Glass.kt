package dev.schlubbe.musicagent.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Dawn Music's "liquid glass": the content behind a bar is blurred
 * ([hazeEffect] reads it from the screen content marked with hazeSource) under a
 * half-opaque milky tint, a soft sheen sits on the upper half like light on curved
 * glass, and a bright rim fading downwards edges the pane. Tint and blur come from
 * the Dawn design canvas tweaks. Null outside the Dawn chrome. */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Height of Dawn Music's floating chrome (mini player + tab bar). Screens
 * scroll on under it, so their lists end with this much padding; 0 elsewhere. */
val LocalChromeInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** "Morgen am Meer": Dawn Music's page background, a soft golden first light
 * at the top turning into sky blue and fading into the pale [OceanDawn]
 * background; on a holiday its own sky ([HolidayLook.sky]). At night a deep
 * pre-dawn blue fading into [OceanDawnNight]'s navy. Tab screens draw on it with
 * a transparent container (see [dawnContainer]). */
val DawnSky: Brush
    @Composable
    get() {
        val sky = when {
            Canopy.isDark -> listOf(Color(0xFF1C2D4A), Color(0xFF102039), Color(0xFF0A1626))
            else -> LocalHoliday.current?.look?.sky ?: listOf(Color(0xFFF5E2C2), Color(0xFFB4D6F4), Color(0xFFE4F0FB))
        }
        return Brush.verticalGradient(0f to sky[0], 0.35f to sky[1], 1f to sky[2])
    }

/** Container colour for screens that sit on [DawnSky] in Dawn Music. */
val dawnContainer: Color
    @androidx.compose.runtime.Composable
    get() = if (dev.schlubbe.musicagent.BuildConfig.DAWN) Color.Transparent else Canopy.bg

@Composable
fun Modifier.glassSurface(shape: Shape): Modifier {
    val state = LocalGlassState.current
    val dark = Canopy.isDark
    val glow = Canopy.accent.copy(alpha = if (dark) 0.20f else 0.35f)
    val warmEdge = Canopy.accent2.copy(alpha = 0.10f)
    // milky glass by day, smoked navy glass at night
    val tint = if (dark) Canopy.surface else Color.White
    val sheen = if (dark) 0.10f else 0.25f
    val rim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.28f else 0.95f),
            Color.White.copy(alpha = if (dark) 0.08f else 0.35f),
            Canopy.accent2.copy(alpha = if (dark) 0.18f else 0.25f),
        ),
    )
    return this
        .shadow(elevation = 16.dp, shape = shape, ambientColor = glow, spotColor = glow)
        .clip(shape)
        .hazeEffect(
            state,
            HazeStyle(
                tint = HazeTint(tint.copy(alpha = if (dark) 0.62f else 0.5f)),
                blurRadius = 12.dp,
                noiseFactor = 0.03f,
                // no blur below Android 12: nearly opaque instead, so text stays readable
                fallbackTint = HazeTint(tint.copy(alpha = 0.92f)),
            ),
        )
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = sheen),
                    0.5f to Color.White.copy(alpha = sheen / 3),
                    1f to Color.Transparent,
                ),
            )
            // sunrise light caught at the lower edge
            drawRect(
                Brush.radialGradient(
                    listOf(warmEdge, Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 1.1f),
                    radius = size.width * 0.6f,
                ),
            )
        }
        .border(1.dp, rim, shape)
}
