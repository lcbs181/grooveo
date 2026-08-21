package dev.schlubbe.musicagent.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The small animated "now playing" bars badge used on track cover art across
 * Home's resume card, MiniPlayerBar, and the full Player screen (see the design
 * handoff's `eqBounceLg` CSS keyframe) -- four bars pulsing at staggered
 * durations/delays, visible only while [isPlaying]. Clipped to [size]-tall pill
 * so bars never overflow their background, matching the design note that this
 * stays a fixed 30px on Player and 15px on the smaller surfaces. */
@Composable
fun EqualizerBadge(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    barColor: Color = Color.White,
) {
    if (!isPlaying) return
    Box(
        modifier = modifier
            .height(size)
            .clip(RoundedCornerShape(size / 2))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = size * 0.2f, vertical = size * 0.15f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Staggered durations (0.5-0.85s) + phase offsets so bars don't move
            // in lockstep -- matches the design's per-bar animation-delay values.
            // Bottom-aligned so each bar grows *up* from a shared baseline like a
            // real equalizer meter -- Row defaults to top alignment, which anchored
            // bars to their top edge and made them grow *down* instead, reading as
            // an upside-down/mirrored visualizer.
            val durations = listOf(500, 650, 720, 850)
            durations.forEachIndexed { index, durationMs ->
                EqBar(durationMs = durationMs, maxHeight = size * 0.7f, color = barColor)
                if (index != durations.lastIndex) Box(modifier = Modifier.width(size * 0.12f))
            }
        }
    }
}

@Composable
private fun EqBar(durationMs: Int, maxHeight: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "eqBar")
    val scale by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(durationMs, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eqBarScale",
    )
    Box(
        modifier = Modifier
            .width(maxHeight * 0.22f)
            .height(maxHeight * scale)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}
