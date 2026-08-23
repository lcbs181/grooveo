package dev.schlubbe.musicagent.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.playback.VISUALIZER_BAND_COUNT
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Ported verbatim from the design's own `_ds_bundle.js` (`const SEQ = [...]`) - used
// now only as (a) each element's ANIMATION TIMING seed (duration/delay stagger,
// which real audio data has no equivalent of) and (b) a startup fallback shape for
// the brief window before the first real FFT capture arrives / if the platform
// Visualizer effect failed to attach at all.
private val SEQ = floatArrayOf(1f, .55f, .8f, .35f, .95f, .6f, .75f, .45f, .9f, .5f, .7f, .85f, .4f, 1f, .65f, .55f)
private fun seq(i: Int) = SEQ[i % SEQ.size]

/** Maps element [i] of [elementCount] onto [bands] (a fixed [VISUALIZER_BAND_COUNT]-
 * length real-time FFT magnitude array - see AudioVisualizerController) and returns
 * its 0f..1f amplitude, falling back to the seeded pseudo-random [seq] shape when
 * [bands] is still all-zero (no real capture data yet). This is what makes every
 * variant below genuinely audio-reactive instead of decorative. */
private fun amplitudeFor(i: Int, elementCount: Int, bands: FloatArray): Float {
    val bandIndex = (i * bands.size / elementCount).coerceIn(0, bands.size - 1)
    val real = bands[bandIndex]
    return if (real > 0.001f) real else seq(i)
}

/** A raised-sine stand-in for a 3-keyframe (0%/50%/100%) CSS animation whose middle
 * keyframe is the peak and both ends match - `sin(progress * PI)` is 0 at both ends
 * and 1 at the midpoint, the same shape `eqBounce`/`vizPop`/etc. trace. Still used
 * for animation phase/stagger even though amplitude itself now comes from real
 * audio via [amplitudeFor]. */
private fun hump(progress: Float): Float = sin(progress * PI.toFloat())

/** A single shared "elapsed ms" clock every variant below derives its own
 * per-bar/per-point/per-shot ANIMATION PHASE from via plain arithmetic - separate
 * from the actual audio-reactive amplitude ([amplitudeFor]/[bands]), this only
 * drives timing/stagger. Returns the raw [State] (not a resolved value) so callers
 * can read `.value` inside a `Canvas`/`graphicsLayer` draw-phase lambda without
 * forcing the enclosing composable to recompose on every animation tick - reading
 * it via `by` at composition time was the previous version's biggest performance
 * issue (recomposing the whole Visualizer, and everything it's nested inside, up
 * to 60 times a second). */
@Composable
private fun rememberVizClockMs(isPlaying: Boolean): State<Float> {
    val transition = rememberInfiniteTransition(label = "vizClock")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = VIZ_CLOCK_PERIOD_MS,
        animationSpec = infiniteRepeatable(
            tween(if (isPlaying) VIZ_CLOCK_PERIOD_MS.toInt() else Int.MAX_VALUE / 2, easing = LinearEasing),
        ),
        label = "vizClockMs",
    )
}
private const val VIZ_CLOCK_PERIOD_MS = 3_600_000f

/** Phase (0f..1f) of an element with its own [durationMs]/[delayMs] against the
 * shared [clockMs] - the building block every variant's per-element timing below
 * is computed from. */
private fun phaseOf(clockMs: Float, delayMs: Int, durationMs: Int): Float =
    ((clockMs + delayMs) % durationMs) / durationMs

/** The Player's five-way "Visualizer" overlay drawn on the artwork's bottom scrim -
 * a field-for-field port of design_handoff_grooveo's `Visualizer` component
 * (`_ds_bundle.js` / `components/Visualizer/Visualizer.jsx`, confirmed against the
 * live "Copy of Canopy" design-system project via the DesignSync MCP) and its CSS
 * keyframes, now driven by [bands] - real-time FFT magnitude data from
 * [dev.schlubbe.musicagent.playback.AudioVisualizerController] via
 * [dev.schlubbe.musicagent.playback.PlayerController.visualizerBands] - instead of
 * the design's own purely decorative seeded-pseudo-random loop. [isPlaying] still
 * gates the phase clock (see [rememberVizClockMs]); when false every variant
 * renders its plain un-animated rest shape, matching the design's own
 * `animation: paused ? 'none' : ...` behavior. */
@Composable
fun Visualizer(
    variant: String,
    isPlaying: Boolean,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.92f),
    count: Int = 22,
) {
    val clockMs = rememberVizClockMs(isPlaying)
    when (variant) {
        "bars" -> BarsVisualizer(count, color, isPlaying, clockMs, bands, modifier)
        "orb" -> OrbVisualizer(color, isPlaying, clockMs, bands, modifier)
        "particles" -> ParticlesVisualizer(count, color, isPlaying, clockMs, bands, modifier)
        "pulse" -> PulseVisualizer(color, isPlaying, clockMs, bands, modifier)
        else -> WaveVisualizer(count, color, isPlaying, clockMs, bands, modifier)
    }
}

/** `vizWave`: every bar travels through `translateY(35%) scaleY(.5) ->
 * translateY(-35%) scaleY(1) -> translateY(35%) scaleY(.5)` on a shared 1400ms
 * cycle, staggered 80ms per bar for a rippling look; its peak-to-peak amplitude is
 * now scaled by that bar's real audio band instead of being a fixed 55% for every
 * bar. All state is read inside each bar's own `graphicsLayer` block, not in this
 * function's body, so a clock/band tick redraws without recomposing. */
@Composable
private fun WaveVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(0.7f)
                    .graphicsLayer {
                        // Both the real audio amplitude and the wave-phase envelope
                        // are read here, inside the draw-phase graphicsLayer block,
                        // not in this function's body - a tick of either redraws
                        // without recomposing the Row/Visualizer/Player screen above it.
                        val amp = amplitudeFor(i, count, bands.value)
                        val t = if (isPlaying) hump(phaseOf(clockMs.value, delayMs = i * 80, durationMs = 1400)) else 1f
                        scaleY = (0.5f + 0.5f * t) * (0.5f + 0.5f * amp)
                        translationY = (0.35f - 0.70f * t) * size.height
                        alpha = 0.35f + amp * 0.65f
                    }
                    .background(color),
            )
        }
    }
}

/** `eqBounce`: each bar's baseline height now tracks its real audio band (was a
 * fixed seeded value) and still breathes via `scaleY(.28 -> 1 -> .28)` from its own
 * bottom edge at its own duration/stagger - the classic "equalizer forest" look,
 * now genuinely reactive. */
@Composable
private fun BarsVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until count) {
            val durationMs = 480 + (i * 97) % 420
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // Real audio amplitude and the eqBounce envelope are
                        // combined into one scaleY here, inside the draw-phase
                        // graphicsLayer block - see WaveVisualizer's comment.
                        val amp = amplitudeFor(i, count, bands.value)
                        val bounce = if (isPlaying) 0.28f + 0.72f * hump(phaseOf(clockMs.value, delayMs = i * 55, durationMs = durationMs)) else 1f
                        scaleY = amp * bounce
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/** `vizSpin`/`vizSpinRev`/`vizOrb`: a conic-gradient ring (color -> accent200 ->
 * transparent) drawn twice - a wider/dimmer glow copy and a narrower sharp copy -
 * counter-rotating at 5200ms/7600ms, wrapped in a slow breathing scale whose
 * amplitude now tracks the track's overall real loudness (mean of all bands)
 * instead of a fixed .18 swing, over a dark radial disc with a thin outline ring. */
@Composable
private fun OrbVisualizer(
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    val accent200 = Canopy.accent200
    val accent900 = Canopy.accent900
    // Hoisted out of the per-frame draw path - only the rotation applied via
    // rotate(...) below changes frame to frame, the gradient itself is constant
    // for a given (color, accent200) pair.
    val ring = remember(color, accent200) {
        Brush.sweepGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                60f / 360f to color,
                120f / 360f to accent200,
                200f / 360f to Color.Transparent,
                300f / 360f to color,
                1f to Color.Transparent,
            ),
        )
    }
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val loudness = bands.value.let { b -> if (b.isEmpty()) 0.5f else b.average().toFloat() }.let { if (it > 0.001f) it else 0.5f }
        val breathe = if (isPlaying) 0.82f + 0.18f * loudness else 1f
        val spinDeg = if (isPlaying) (clockMs.value / 5200f * 360f) % 360f else 0f
        val spinRevDeg = if (isPlaying) -(clockMs.value / 7600f * 360f) % 360f else 0f
        val ringRadius = r * breathe * 0.93f

        rotate(spinDeg, center) {
            drawCircle(brush = ring, radius = ringRadius, center = center, alpha = 0.55f, style = Stroke(width = r * 0.22f))
        }
        rotate(spinRevDeg, center) {
            drawCircle(brush = ring, radius = ringRadius, center = center, style = Stroke(width = r * 0.14f))
        }

        val discRadius = r * breathe * 0.43f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent900, Color(0xFF06110D)),
                center = Offset(center.x, center.y - discRadius * 0.16f),
                radius = discRadius * 1.3f,
            ),
            radius = discRadius,
            center = center,
        )
        drawCircle(color = color, radius = discRadius, center = center, alpha = 0.35f, style = Stroke(width = 1.dp.toPx()))
    }
}

private data class ParticleSpec(val lat: Float, val lon: Float, val durationMs: Int, val delayMs: Int)

/** `vizSphere`/`vizPop`: a Fibonacci-lattice point cloud, continuously spun around
 * Y (16000ms) with a fixed -16° X tilt, each point breathing radially
 * outward/inward along its own normal - the breathe amplitude for point [i] now
 * comes from its mapped real audio band instead of a fixed seeded value. Real
 * perspective-projected 3D with back-to-front depth sorting, same as before, but
 * reworked to a single set of preallocated parallel arrays (positions/depths/an
 * index-sort array) reused every frame instead of allocating a fresh
 * `List<ProjectedParticle>` + `sortedBy{}` per frame (was ~110 allocations x the
 * screen's frame rate, continuously, while this variant was on screen). */
@Composable
private fun ParticlesVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    val n = if (count < 24) 110 else count
    val points = remember(n) {
        List(n) { i ->
            val y = 1f - (2 * i + 1f) / n
            ParticleSpec(
                lat = asin(y.toDouble()).toFloat(),
                lon = ((i * 137.508f) % 360f) * (PI.toFloat() / 180f),
                durationMs = 520 + (i * 211) % 620,
                delayMs = (i * 61) % 700,
            )
        }
    }
    // Preallocated, reused every frame - no per-frame heap allocation.
    val screenX = remember(n) { FloatArray(n) }
    val screenY = remember(n) { FloatArray(n) }
    val depth = remember(n) { FloatArray(n) }
    val dotRadius = remember(n) { FloatArray(n) }
    val alpha = remember(n) { FloatArray(n) }
    val order = remember(n) { IntArray(n) { it } }
    val tiltRad = -16f * PI.toFloat() / 180f

    Canvas(modifier = modifier) {
        val sizePx = min(this.size.width, this.size.height)
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val rPx = sizePx * 0.34f
        val dotRadiusPx = maxOf(1.5.dp.toPx(), sizePx * 0.016f)
        val perspective = sizePx * 2.6f
        val spinRad = if (isPlaying) (clockMs.value / 16000f) * 2f * PI.toFloat() else 0f
        val cosSpin = cos(spinRad)
        val sinSpin = sin(spinRad)
        val cosTilt = cos(tiltRad)
        val sinTilt = sin(tiltRad)
        val bandsValue = bands.value

        for (i in points.indices) {
            val p = points[i]
            val popT = if (isPlaying) hump(phaseOf(clockMs.value, p.delayMs, p.durationMs)) else 0f
            val amp = amplitudeFor(i, n, bandsValue) * 0.14f
            val radius = rPx + amp * sizePx * popT
            val x0 = cos(p.lat) * sin(p.lon)
            val y0 = sin(p.lat)
            val z0 = cos(p.lat) * cos(p.lon)
            val y1 = y0 * cosTilt - z0 * sinTilt
            val z1 = y0 * sinTilt + z0 * cosTilt
            val x2 = x0 * cosSpin + z1 * sinSpin
            val z2 = -x0 * sinSpin + z1 * cosSpin
            val d = z2 * radius
            val scaleProj = perspective / (perspective - d)
            screenX[i] = center.x + x2 * radius * scaleProj
            screenY[i] = center.y + y1 * radius * scaleProj
            depth[i] = d
            dotRadius[i] = dotRadiusPx * (if (isPlaying) 0.7f + 0.3f * popT else 1f) * scaleProj
            alpha[i] = if (isPlaying) 0.45f + 0.55f * popT else 1f
        }

        // In-place insertion sort of `order` by `depth` - back-to-front (farthest
        // first) so nearer points correctly paint over farther ones, the same
        // depth-ordering `preserve-3d` gives the real DOM version for free. Cheap
        // (near O(n)) since particle order only shifts gradually frame to frame as
        // the sphere rotates, unlike a fresh sort from scratch every time.
        for (i in 1 until order.size) {
            val idx = order[i]
            val key = depth[idx]
            var j = i - 1
            while (j >= 0 && depth[order[j]] > key) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = idx
        }

        for (k in order.indices) {
            val i = order[k]
            drawCircle(color = color.copy(alpha = alpha[i]), radius = dotRadius[i], center = Offset(screenX[i], screenY[i]))
        }
    }
}

/** `vizRing`/`vizShoot`/`vizOrb`: 3 expanding-and-fading ring outlines staggered
 * 700ms apart, 18 small rotating streaks "shooting" outward to a seeded distance
 * (scaled by the track's real loudness) and fading on their own quick-in/slow-out
 * curve, and a breathing core dot whose amplitude also tracks real loudness. */
@Composable
private fun PulseVisualizer(
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    val accent2 = Canopy.accent2
    Canvas(modifier = modifier) {
        val r = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val loudness = bands.value.let { b -> if (b.isEmpty()) 0.5f else b.average().toFloat() }.let { if (it > 0.001f) it else 0.5f }

        for (i in 0..2) {
            val progress = if (isPlaying) phaseOf(clockMs.value, delayMs = i * 700, durationMs = 2100) else 0f
            val scale = if (isPlaying) 0.35f + 0.65f * progress else 1f
            val alpha = if (isPlaying) 0.85f * (1f - progress) else 0.85f
            drawCircle(color = color.copy(alpha = alpha), radius = (r / 2f) * scale, center = center, style = Stroke(width = 2.dp.toPx()))
        }

        if (isPlaying) {
            for (i in 0 until 18) {
                val angle = (360f / 18f * i + if (i % 2 == 1) 9f else 0f) * (PI.toFloat() / 180f)
                val amp = (r / 2f) * (0.4f + loudness * 0.9f)
                val dx = cos(angle) * amp
                val dy = sin(angle) * amp
                val rotDeg = (if (i % 2 == 1) 1f else -1f) * (120f + seq(i) * 240f)
                val durationMs = 1500 + (i * 173) % 900
                val delayMs = (i % 3) * 700 + (i * 97) % 320
                val w = maxOf(3.dp.toPx(), r * 0.05f)

                val progress = phaseOf(clockMs.value, delayMs, durationMs)
                val opacity = if (progress < 0.14f) progress / 0.14f else 1f - (progress - 0.14f) / 0.86f
                val shotColor = if (i % 3 == 0) accent2 else color

                translate(left = center.x + dx * progress, top = center.y + dy * progress) {
                    rotate(degrees = rotDeg * progress, pivot = Offset.Zero) {
                        drawRoundRect(
                            color = shotColor.copy(alpha = opacity),
                            topLeft = Offset(-w / 2f, -(w * 1.7f) / 2f),
                            size = Size(w, w * 1.7f),
                            cornerRadius = CornerRadius(1f, 1f),
                        )
                    }
                }
            }
        }

        val coreBreathe = if (isPlaying) 0.82f + 0.18f * loudness else 1f
        drawCircle(color = color, radius = (r / 2f) * 0.28f * coreBreathe, center = center)
    }
}
