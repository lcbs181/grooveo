package dev.schlubbe.musicagent.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

/** The 3 container treatments the design's `EqualizerBadge.jsx` supports --
 * [Tile] and [Wide] are opaque `accent900`-background chips (a square for a grid
 * cell, an auto-width pill-corner strip for a row), [Inline] is bars only, no
 * background, meant to sit directly among a track row's own text/icons (see
 * GrooveoApp.dc.html's genre-trends "now playing" row, the only real call site
 * this component has in the whole handoff). */
enum class EqualizerBadgeVariant { Tile, Wide, Inline }

// Design's own BARS constant (components/EqualizerBadge/EqualizerBadge.jsx) --
// 4 bars, each a fixed height fraction of the badge's own track height, that
// then individually breathe via the eqBounce keyframe (scaleY .28 -> 1 -> .28,
// see styles.css) at its own duration/delay so the 4 bars never move in lockstep.
private data class EqBarSpec(val durationMs: Int, val heightFraction: Float, val delayMs: Int)
private val EQ_BARS = listOf(
    EqBarSpec(durationMs = 700, heightFraction = 1f, delayMs = 0),
    EqBarSpec(durationMs = 520, heightFraction = 0.62f, delayMs = 120),
    EqBarSpec(durationMs = 840, heightFraction = 0.86f, delayMs = 60),
    EqBarSpec(durationMs = 600, heightFraction = 0.70f, delayMs = 190),
)

/** The small animated "now playing" bars badge -- shown only while [isPlaying].
 * Field-for-field port of the design's `EqualizerBadge.jsx`: [variant] controls
 * both the container chrome and the default bar/background colors (overridable
 * via [barColor]/[background]), and [size] drives every other dimension off the
 * same formulas the component source uses (track height, bar width, container
 * size) rather than a handful of independently-guessed constants. */
@Composable
fun EqualizerBadge(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    variant: EqualizerBadgeVariant = EqualizerBadgeVariant.Tile,
    barColor: Color? = null,
    background: Color? = null,
) {
    if (!isPlaying) return
    val inline = variant == EqualizerBadgeVariant.Inline
    val bg = background ?: if (inline) Color.Transparent else Canopy.accent900
    val fg = barColor ?: if (inline) Canopy.accent else Canopy.accent300
    val trackHeight = size * (if (inline) 1f else 0.62f)
    val barWidth = maxOf(2.dp, size * 0.1f)

    val bars: @Composable () -> Unit = {
        Row(
            modifier = Modifier.height(trackHeight),
            horizontalArrangement = Arrangement.spacedBy(barWidth),
            verticalAlignment = Alignment.Bottom,
        ) {
            EQ_BARS.forEach { spec ->
                EqBar(spec = spec, width = barWidth, maxHeight = trackHeight * spec.heightFraction, color = fg)
            }
        }
    }

    when (variant) {
        EqualizerBadgeVariant.Inline -> Box(modifier = modifier) { bars() }
        EqualizerBadgeVariant.Tile -> Box(
            modifier = modifier
                .width(size * 1.32f)
                .height(size * 1.32f)
                .clip(CanopyShapes.small)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) { bars() }
        EqualizerBadgeVariant.Wide -> Box(
            modifier = modifier
                .height(size * 1.32f)
                .widthIn(min = size * 1.32f)
                .clip(CanopyShapes.small)
                .background(bg)
                .padding(horizontal = size * 0.42f),
            contentAlignment = Alignment.Center,
        ) { bars() }
    }
}

@Composable
private fun EqBar(spec: EqBarSpec, width: Dp, maxHeight: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "eqBar")
    // Not destructured with `by` - read as `.value` only inside graphicsLayer below,
    // so a scale tick redraws this one bar without recomposing (badges appear in
    // several list rows at once, e.g. Home's genre-trends shelf).
    val scale = transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(spec.durationMs, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(spec.delayMs, StartOffsetType.Delay),
        ),
        label = "eqBarScale",
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(maxHeight)
            .graphicsLayer {
                scaleY = scale.value
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .background(color),
    )
}
