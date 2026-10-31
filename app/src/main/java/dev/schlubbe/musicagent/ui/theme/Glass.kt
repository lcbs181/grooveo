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

/** "Morgen am Meer": Dawn Music's page background, a soft sunrise pink
 * at the top turning into sky blue and fading into the pale [OceanDawn]
 * background; on a holiday its own sky ([HolidayLook.sky]). Tab screens draw on
 * it with a transparent container (see [dawnContainer]). */
val DawnSky: Brush
    @Composable
    get() {
        val sky = LocalHoliday.current?.look?.sky ?: listOf(Color(0xFFF9CFC6), Color(0xFFB4D6F4), Color(0xFFE4F0FB))
        return Brush.verticalGradient(0f to sky[0], 0.35f to sky[1], 1f to sky[2])
    }

/** Container colour for screens that sit on [DawnSky] in Dawn Music. */
val dawnContainer: Color
    @androidx.compose.runtime.Composable
    get() = if (dev.schlubbe.musicagent.BuildConfig.DAWN) Color.Transparent else Canopy.bg

@Composable
fun Modifier.glassSurface(shape: Shape): Modifier {
    val state = LocalGlassState.current
    val glow = Canopy.accent.copy(alpha = 0.35f)
    val warmEdge = Canopy.accent2.copy(alpha = 0.10f)
    val rim = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Canopy.accent2.copy(alpha = 0.25f)),
    )
    return this
        .shadow(elevation = 16.dp, shape = shape, ambientColor = glow, spotColor = glow)
        .clip(shape)
        .hazeEffect(
            state,
            HazeStyle(
                tint = HazeTint(Color.White.copy(alpha = 0.5f)),
                blurRadius = 12.dp,
                noiseFactor = 0.03f,
                // no blur below Android 12: nearly opaque instead, so text stays readable
                fallbackTint = HazeTint(Color.White.copy(alpha = 0.9f)),
            ),
        )
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.25f),
                    0.5f to Color.White.copy(alpha = 0.08f),
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
