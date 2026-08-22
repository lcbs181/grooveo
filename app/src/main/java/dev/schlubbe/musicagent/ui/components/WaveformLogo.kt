package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.theme.Canopy

// Logo direction "A (Pegel)" from Grooveo Logos.dc.html -- Pegel as in a level
// meter, which is why the bars stand on a baseline rather than hanging from the
// top, and why the centre (tallest) bar is the coral accent-2 while the other
// four are the base accent.
private val BAR_HEIGHT_RATIOS = listOf(0.43f, 0.73f, 1f, 0.63f, 0.33f)

/** The brand mark: five bottom-aligned bars, an abstracted level meter rather
 * than a letterform. Shared by the splash screen and onboarding so both keep
 * identical proportions. */
@Composable
fun WaveformLogo(modifier: Modifier = Modifier, barWidth: Dp = 8.dp, maxBarHeight: Dp = 60.dp, gap: Dp = 6.dp) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        BAR_HEIGHT_RATIOS.forEachIndexed { index, fraction ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxBarHeight * fraction)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(if (index == 2) Canopy.accent2 else Canopy.accent),
            )
        }
    }
}

// Proportions taken from the onboarding header's own 64px badge in
// GrooveoApp.dc.html (radius 17, bar width 5, gap 4, tallest bar 37, bottom
// padding 19) and expressed as ratios so the badge stays correct at any size
// rather than only at one.
private const val RADIUS_RATIO = 17f / 64f
private const val BAR_WIDTH_RATIO = 5f / 64f
private const val GAP_RATIO = 4f / 64f
private const val MAX_BAR_RATIO = 37f / 64f
private const val BOTTOM_PAD_RATIO = 19f / 64f

/** The mark as it appears wherever it's shown standalone (splash, onboarding):
 * inside a rounded gradient badge rather than floating bare on whatever is
 * behind it. Gradient is Canopy's `linear-gradient(160deg, accent-300, bg 70%)`. */
@Composable
fun WaveformLogoBadge(modifier: Modifier = Modifier, size: Dp = 104.dp) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = size * (10f / 64f),
                shape = RoundedCornerShape(size * RADIUS_RATIO),
                clip = false,
            )
            .clip(RoundedCornerShape(size * RADIUS_RATIO))
            .background(
                // 160deg in CSS runs top-ish to bottom-ish; linearGradient's
                // default vertical sweep is the closest Compose equivalent
                // without hand-computing offsets for a 20-degree tilt.
                Brush.linearGradient(
                    colorStops = arrayOf(0f to Canopy.accent300, 0.7f to Canopy.bg, 1f to Canopy.bg),
                ),
            )
            .padding(bottom = size * BOTTOM_PAD_RATIO),
        contentAlignment = Alignment.BottomCenter,
    ) {
        WaveformLogo(
            barWidth = size * BAR_WIDTH_RATIO,
            maxBarHeight = size * MAX_BAR_RATIO,
            gap = size * GAP_RATIO,
        )
    }
}
