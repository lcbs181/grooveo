package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.theme.Nocturne

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
            val color = if (index == 2) Nocturne.accent300 else Nocturne.accent
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
