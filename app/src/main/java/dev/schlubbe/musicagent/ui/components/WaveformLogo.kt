package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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

/** The app's brand mark: five vertical bars of varying height, an abstracted
 * waveform rather than a letterform (see
 * "Music Agent Splash & Logo.dc.html"). Only the middle, tallest bar carries
 * the brighter accent-300 tone; the other four use the base accent. Shared by
 * the splash screen and the onboarding intro animation so both use the exact
 * same proportions. */
@Composable
fun WaveformLogo(modifier: Modifier = Modifier, barWidth: Dp = 8.dp, maxBarHeight: Dp = 60.dp, gap: Dp = 6.dp) {
    val heights = listOf(0.43f, 0.73f, 1f, 0.63f, 0.33f)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        heights.forEachIndexed { index, fraction ->
            val color = if (index == 2) Canopy.accent300 else Canopy.accent
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxBarHeight * fraction)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(color),
            )
        }
    }
}

/** The waveform mark as it actually appears wherever it's shown standalone
 * (splash, onboarding intro): sitting inside a rounded gradient "badge", not
 * floating bare on whatever background is behind it (see "Music Agent Splash
 * & Logo.dc.html"'s `.logo-mark` -- 104x104, 28dp corner radius,
 * linear-gradient(160deg, neutral-800 -> bg), shadow-lg). [size] scales the
 * whole badge; corner radius and the inner [WaveformLogo]'s bar dimensions
 * scale with it at the same ratio the design uses for its 104dp and 96dp
 * variants, so this stays correct at any call size rather than just the
 * default. */
@Composable
fun WaveformLogoBadge(modifier: Modifier = Modifier, size: Dp = 104.dp) {
    val scale = size / 104.dp
    val cornerRadius = 28.dp * scale
    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 16.dp * scale, shape = RoundedCornerShape(cornerRadius), clip = false)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(0f to Canopy.neutral800, 0.7f to Canopy.bg, 1f to Canopy.bg),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        WaveformLogo(barWidth = 8.dp * scale, maxBarHeight = 60.dp * scale, gap = 6.dp * scale)
    }
}
