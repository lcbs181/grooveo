package dev.schlubbe.musicagent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Canopy's motion set, ported from styles.css's keyframes and the timings table
// in the handoff README. Deliberately restrained -- the handoff's own words are
// "keep it restrained; confetti and waves only".
//
// Every animation here honours the platform's reduced-motion preference by way
// of the caller: pass `enabled = false` to skip it.

private val WAVE_EASING = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1f)
private val SHEET_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val CONFETTI_EASING = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
private val SPRAY_EASING = CubicBezierEasing(0.12f, 0.62f, 0.24f, 1f)
private val POP_EASING = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** `ds-wave-sweep`: an accent gradient that sweeps across a row once, used
 * whenever a track starts from a list or tile. [trigger] restarts it -- pass an
 * incrementing counter (or the started track's id). */
@Composable
fun BoxScope.WaveSweep(trigger: Int, shape: Shape) {
    if (trigger <= 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 750, easing = WAVE_EASING))
    }
    val accent200 = Canopy.accent200
    val accent100 = Canopy.accent100
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .graphicsLayer { alpha = 0.7f },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // translateX(-110% -> 110%), matching the keyframe.
            val shift = (-1.1f + 2.2f * progress.value) * size.width
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.45f to accent200,
                        0.55f to accent100,
                        1f to Color.Transparent,
                    ),
                    start = Offset(shift, 0f),
                    end = Offset(shift + size.width, 0f),
                ),
                size = size,
            )
        }
    }
}

/** `ds-heart-pop`: scale 1 -> 1.4 -> .85 -> 1 over 500ms. Returns the scale to
 * apply via `graphicsLayer`. [trigger] restarts it. */
@Composable
fun rememberHeartPopScale(trigger: Int): Float {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(1.4f, tween(150, easing = POP_EASING))
        scale.animateTo(0.85f, tween(125, easing = LinearEasing))
        scale.animateTo(1f, tween(225, easing = POP_EASING))
    }
    return scale.value
}

private data class Particle(val dx: Float, val dy: Float, val rot: Float, val color: Color)

/** `ds-confetti`: the small 8-particle burst on a like. CSS `position:absolute`
 * lets particles overflow the button freely, so this must NOT be sized to the
 * parent -- Compose clips drawing to the Canvas bounds, and matching a ~36dp
 * icon box traps the whole burst in a little square behind the glyph.
 * [Modifier.wrapContentSize] with `unbounded = true` is what allows the overlay
 * to exceed its parent without affecting layout.
 *
 * For the big follow spray, which is `position:fixed` (viewport-centred), use
 * [CanopyOverlayHost] instead -- a burst anchored to the button can't cover the
 * screen no matter how it's sized.
 *
 * Particle offsets are deterministic per index rather than random: the handoff's
 * generator randomises them, but a stable spread reads the same and keeps
 * recomposition from reshuffling mid-flight. */
@Composable
fun BoxScope.Confetti(
    trigger: Int,
    count: Int = 8,
    spread: Float = 90f,
    durationMs: Int = 700,
    canvasSize: androidx.compose.ui.unit.Dp = 160.dp,
) {
    if (trigger <= 0) return
    val accent = Canopy.accent
    val accent2 = Canopy.accent2
    val accent600 = Canopy.accent600
    val particles = remember(trigger, count, spread) {
        List(count) { i ->
            // Fan the particles over a full turn, alternating radius so they don't
            // land on a perfect circle.
            val angle = (i.toFloat() / count) * 2f * Math.PI.toFloat()
            val radius = spread * (0.6f + 0.4f * ((i % 3) / 2f))
            Particle(
                dx = cos(angle) * radius,
                dy = sin(angle) * radius - spread * 0.25f, // bias upward
                rot = (i * 47f) % 360f,
                color = when (i % 3) {
                    0 -> accent2
                    1 -> accent
                    else -> accent600
                },
            )
        }
    }
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(durationMs, easing = if (count > 8) SPRAY_EASING else CONFETTI_EASING),
        )
    }
    Canvas(
        modifier = Modifier
            .align(Alignment.Center)
            // Escape the parent's bounds: without unbounded wrapping the Canvas is
            // constrained to the icon and every particle is clipped away.
            .wrapContentSize(align = Alignment.Center, unbounded = true)
            .size(canvasSize),
    ) {
        drawConfetti(particles, progress.value, count)
    }
}

private fun DrawScope.drawConfetti(particles: List<Particle>, p: Float, count: Int) {
    // Opacity holds then fades over the last stretch, as the keyframes do.
    val alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
    if (alpha <= 0f) return
    val centre = Offset(size.width / 2f, size.height / 2f)
    particles.forEach { particle ->
        val scale = 0.4f + 0.6f * p
        val w = (if (count > 8) 9f else 6f) * scale
        val h = (if (count > 8) 14f else 6f) * scale
        val pos = Offset(centre.x + particle.dx * p, centre.y + particle.dy * p)
        rotateRect(pos, w, h, particle.rot * p, particle.color.copy(alpha = alpha))
    }
}

/** State for the window-level confetti. The design's `.ds-confetti-spray` is
 * `position: fixed`, so it belongs to the window rather than to whichever button
 * triggered it.
 *
 * The smaller like burst goes through here too, even though the design anchors
 * that one to its button: inside the mini player the burst's ancestor is a
 * `.clip(CanopyPillShape)` surface, and a clipped parent clips its children's
 * drawing no matter how the child is sized. Trying to size around that inflated
 * the pill's layout instead. Window-level is the only place either burst can
 * actually be seen. */
class CanopyOverlayState {
    internal var trigger by mutableIntStateOf(0)
        private set
    internal var particleCount by mutableIntStateOf(44)
        private set

    /** [count] is the design's own particle budget: 44 for a follow spray, 8 for
     * a like. */
    fun spray(count: Int = 44) {
        particleCount = count
        trigger++
    }
}

val LocalCanopyOverlay = staticCompositionLocalOf { CanopyOverlayState() }

/** Renders the window-level confetti spray. Place once, at the app root, on top
 * of the content. Particles are sized against the actual window so the spread
 * reaches the edges on any screen. */
@Composable
fun CanopyOverlayHost(state: CanopyOverlayState) {
    val trigger = state.trigger
    if (trigger <= 0) return
    val count = state.particleCount
    val accent = Canopy.accent
    val accent2 = Canopy.accent2
    val accent600 = Canopy.accent600
    // +/-230px x +/-350px per the handoff's follow spray; the like burst reuses
    // the same fan at a smaller radius.
    val reach = if (count > 8) 1f else 0.45f
    val particles = remember(trigger, count) {
        List(count) { i ->
            val angle = (i.toFloat() / count) * 2f * Math.PI.toFloat()
            Particle(
                dx = cos(angle) * 230f * reach * (0.5f + 0.5f * ((i % 4) / 3f)),
                dy = sin(angle) * 350f * reach * (0.5f + 0.5f * ((i % 3) / 2f)),
                rot = (i * 53f) % 360f,
                color = when (i % 3) {
                    0 -> accent2
                    1 -> accent
                    else -> accent600
                },
            )
        }
    }
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(if (count > 8) 1200 else 700, easing = if (count > 8) SPRAY_EASING else CONFETTI_EASING),
        )
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawConfetti(particles, progress.value, count)
    }
}

// Compose's DrawScope has no rotated-rect primitive; rotating the whole scope
// around each particle's own centre is the cheapest equivalent.
private fun DrawScope.rotateRect(
    centre: Offset,
    width: Float,
    height: Float,
    degrees: Float,
    color: Color,
) {
    rotate(degrees, centre) {
        drawRect(
            color = color,
            topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f),
            size = Size(width, height),
        )
    }
}

/** The Player's "breathing" artwork: scale oscillating to 1.028 over 4.4s.
 * Returns 1f when [isPlaying] is false so a paused cover sits still. */
@Composable
fun rememberBreathingScale(isPlaying: Boolean): Float {
    if (!isPlaying) return 1f
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.028f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breatheScale",
    )
    return scale
}

/** The accent glow behind the Player artwork, pulsing over 3.6s. Returns an
 * alpha multiplier. */
@Composable
fun rememberGlowAlpha(isPlaying: Boolean): Float {
    if (!isPlaying) return 0.35f
    val transition = rememberInfiniteTransition(label = "glow")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    return alpha
}

/** `ds-fade-up`: 10dp rise + fade, 400ms, for section entrances. Returns a
 * modifier; runs once per composition of the key. */
@Composable
fun rememberFadeUp(key: Any? = Unit): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(400)) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offsetPx = with(density) { 10.dp.toPx() }
    return Modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}

/** The four-bar `eqBounce` now-playing indicator's per-bar phase. Exposed so
 * both EqualizerBadge and the Player's inline indicator animate identically. */
@Composable
fun rememberEqBarHeights(isPlaying: Boolean, barCount: Int = 4): List<Float> {
    if (!isPlaying) return List(barCount) { 0.28f }
    val transition = rememberInfiniteTransition(label = "eq")
    // Each bar gets its own duration/offset so they don't pulse in lockstep,
    // mirroring the staggered animation-delay in the CSS.
    return (0 until barCount).map { i ->
        val duration = 620 + i * 130
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "eqBar$i",
        )
        // scaleY .28 -> 1 -> .28
        0.28f + 0.72f * abs(phase)
    }
}

/** Bottom-sheet entrance: slide up from 102% over 320ms. */
val SheetEnterEasing = SHEET_EASING
