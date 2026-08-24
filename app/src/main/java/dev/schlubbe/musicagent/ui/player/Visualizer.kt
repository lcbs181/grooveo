package dev.schlubbe.musicagent.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
import dev.schlubbe.musicagent.playback.bassAmplitude
import dev.schlubbe.musicagent.playback.overallAmplitude
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Ported verbatim from the design's own `_ds_bundle.js` (`const SEQ = [...]`) - used
// now only as (a) each element's ANIMATION TIMING seed (duration/delay stagger,
// which real audio data has no equivalent of) and (b) the static rest shape shown
// while playback is paused.
private val SEQ = floatArrayOf(1f, .55f, .8f, .35f, .95f, .6f, .75f, .45f, .9f, .5f, .7f, .85f, .4f, 1f, .65f, .55f)
private fun seq(i: Int) = SEQ[i % SEQ.size]

/** Amplitude (0f..1f) for element [i] of [elementCount]: while playing, the live
 * level of the frequency band that element is mapped onto, read from [bands] (a
 * [VISUALIZER_BAND_COUNT]-length dB-scaled spectrum - see
 * [dev.schlubbe.musicagent.playback.AudioVisualizerController]); while paused, the
 * design's own static rest shape.
 *
 * Deliberately has **no** pseudo-random fallback during playback. The previous
 * version fell back to the seeded [seq] shape whenever the spectrum read all-zero,
 * which is precisely what allowed a completely dead audio tap to still look
 * convincingly animated - the visualizer appeared to dance while being entirely
 * disconnected from the sound. If the spectrum is silent now, the visual sits still:
 * honest, and immediately obvious if the pipeline ever breaks again. */
private fun ampFor(i: Int, elementCount: Int, bands: FloatArray, isPlaying: Boolean): Float {
    if (!isPlaying) return seq(i)
    if (bands.isEmpty() || elementCount <= 0) return 0f
    val bandIndex = (i * bands.size / elementCount).coerceIn(0, bands.size - 1)
    return bands[bandIndex]
}

/** A raised-sine stand-in for a 3-keyframe (0%/50%/100%) CSS animation whose middle
 * keyframe is the peak and both ends match - `sin(progress * PI)` is 0 at both ends
 * and 1 at the midpoint, the same shape `eqBounce`/`vizPop`/etc. trace. Still used
 * for animation phase/stagger even though amplitude itself comes from real audio. */
private fun hump(progress: Float): Float = sin(progress * PI.toFloat())

/** A single shared "elapsed ms" clock every variant below derives its own
 * per-bar/per-point/per-shot ANIMATION PHASE from via plain arithmetic - separate
 * from the actual audio-reactive amplitude ([ampFor]/[bands]), this only drives
 * timing/stagger. Returns the raw [State] (not a resolved value) so callers can read
 * `.value` inside a `Canvas`/`graphicsLayer` draw-phase lambda without forcing the
 * enclosing composable to recompose on every animation tick.
 *
 * Driven by an explicit [withFrameNanos] loop rather than
 * `rememberInfiniteTransition` + `animateFloat`, which was actively broken here:
 * `InfiniteTransition.animateValue` only calls `updateValues` when `initialValue` or
 * `targetValue` change - it never compares `animationSpec` - so swapping the tween
 * duration on `isPlaying` was silently discarded and the clock kept whatever spec it
 * was built with on *first* composition. Since `PlaybackUiState.isPlaying` starts
 * false (the Player screen opens while the stream is still resolving), the clock was
 * built with the `Int.MAX_VALUE / 2` "paused" tween and stayed at ~1/298 speed for
 * the whole visit: the sphere never rotated, the wave never travelled, the pulse
 * rings never expanded, and the confetti simulation received a `dt` so small it
 * froze in place. That was the second, independent reason the visualizer looked
 * disconnected from the audio (the first being the dead capture path - see
 * AudioVisualizerController's kdoc).
 *
 * The loop also stops when paused, instead of requesting frames at 60fps forever for
 * as long as the Player screen is open. */
@Composable
private fun rememberVizClockMs(isPlaying: Boolean): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var previousFrameNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (previousFrameNanos != 0L) {
                    val deltaMs = (frameNanos - previousFrameNanos) / 1_000_000f
                    clock.floatValue = (clock.floatValue + deltaMs) % VIZ_CLOCK_PERIOD_MS
                }
                previousFrameNanos = frameNanos
            }
        }
    }
    return clock
}
private const val VIZ_CLOCK_PERIOD_MS = 3_600_000f

/** Phase (0f..1f) of an element with its own [durationMs]/[delayMs] against the
 * shared [clockMs] - the building block every variant's per-element timing below
 * is computed from. */
private fun phaseOf(clockMs: Float, delayMs: Int, durationMs: Int): Float =
    ((clockMs + delayMs) % durationMs) / durationMs

/** The Player's five-way "Visualizer" overlay drawn on the artwork's bottom scrim -
 * a port of design_handoff_grooveo's `Visualizer` component, driven by [bands]:
 * real-time FFT levels of the audio actually playing, tapped from ExoPlayer's own
 * PCM pipeline (see [dev.schlubbe.musicagent.playback.AudioVisualizerController]).
 * [isPlaying] gates both the phase clock (see [rememberVizClockMs]) and the live
 * amplitude (see [ampFor]); when false every variant renders its plain un-animated
 * rest shape, matching the design's own `animation: paused ? 'none' : ...`. */
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
 * scaled by that bar's own live frequency band. All state is read inside each bar's
 * own `graphicsLayer` block, not in this function's body, so a clock/band tick
 * redraws without recomposing. */
@Composable
private fun WaveVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    // spacedBy, not SpaceBetween: SpaceBetween distributes *leftover* space, and every
    // child here is weight(1f) with fill = true, so the children consumed the entire
    // row and there was nothing left to distribute - the bars rendered edge to edge as
    // one solid ribbon instead of a row of discrete bars.
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(0.7f)
                    .graphicsLayer {
                        val amp = ampFor(i, count, bands.value, isPlaying)
                        val t = if (isPlaying) hump(phaseOf(clockMs.value, delayMs = i * 80, durationMs = 1400)) else 1f
                        scaleY = (0.5f + 0.5f * t) * (0.35f + 0.65f * amp)
                        translationY = (0.35f - 0.70f * t) * size.height
                        alpha = 0.35f + amp * 0.65f
                    }
                    .background(color),
            )
        }
    }
}

/** `eqBounce`: each bar's height tracks its own live frequency band and still
 * breathes via `scaleY(.28 -> 1 -> .28)` from its own bottom edge at its own
 * duration/stagger - the classic "equalizer forest" look. */
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
                        val amp = ampFor(i, count, bands.value, isPlaying)
                        // A small floor keeps a silent passage as a visible thin line
                        // rather than a bar that vanishes entirely.
                        val bounce = if (isPlaying) 0.55f + 0.45f * hump(phaseOf(clockMs.value, delayMs = i * 55, durationMs = durationMs)) else 1f
                        scaleY = (0.04f + 0.96f * amp) * bounce
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
 * counter-rotating at 5200ms/7600ms, over a dark radial disc with a thin outline ring.
 *
 * The ring's radius is driven directly by bass energy ([bassAmplitude], the lowest
 * bands of the live spectrum): it swings from 50% to 84% of the available radius - a
 * ~68% growth from quiet to a full kick, so the hit unmistakably punches the ring
 * outward and it collapses back in during quiet passages. That wide a swing is the
 * point; the previous version's ±9% breathe was too subtle to read as reacting to
 * anything even when the data was live. */
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
    // drawWithCache rather than Canvas: both brushes depend only on the colors and the
    // layout size, so they are built once per size/color change instead of per frame.
    // The disc's radial gradient in particular used to be constructed inside the draw
    // lambda, which allocated a brush *and* a native android.graphics.RadialGradient
    // 60 times a second (a fresh brush instance can never hit ShaderBrush's internal
    // shader cache, so nothing was being reused).
    Spacer(
        modifier.drawWithCache {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val ring = Brush.sweepGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    60f / 360f to color,
                    120f / 360f to accent200,
                    200f / 360f to Color.Transparent,
                    300f / 360f to color,
                    1f to Color.Transparent,
                ),
            )
            // Sized for the disc's maximum radius and left fixed as the disc itself
            // grows and shrinks - the falloff reads as a fixed light source on a
            // sphere, which looks better than a gradient that rescales every frame.
            val discRefRadius = r * 0.40f
            val discBrush = Brush.radialGradient(
                colors = listOf(accent900, Color(0xFF06110D)),
                center = Offset(center.x, center.y - discRefRadius * 0.16f),
                radius = discRefRadius * 1.3f,
            )
            onDrawBehind {
                drawOrb(r, center, ring, discBrush, color, bands.value, isPlaying, clockMs.value)
            }
        },
    )
}

/** Per-frame Orb painting, split out of [OrbVisualizer] so the cached brushes above
 * stay out of the hot path. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(
    r: Float,
    center: Offset,
    ring: Brush,
    discBrush: Brush,
    color: Color,
    bandsValue: FloatArray,
    isPlaying: Boolean,
    clockMs: Float,
) {
    val bass = if (isPlaying) bassAmplitude(bandsValue) else 0f
    val level = if (isPlaying) overallAmplitude(bandsValue) else 0f

    // Radius swings 0.50r -> 0.84r with bass (a ~68% growth, unmistakable), and the
    // widths are chosen so the ring's *outer* edge - radius plus half the glow
    // stroke - stays within r even at full bass: 0.84 + 0.12 = 0.96. The overlay is
    // only a 64dp-tall strip, and while Compose does not clip a Canvas to its own
    // bounds, overshooting here would spill the ring up out of the artwork scrim it
    // is supposed to sit inside.
    val ringRadius = r * if (isPlaying) 0.50f + 0.34f * bass else 0.72f
    // Thicker stroke on a bass hit too, so the ring reads as "energized" rather than
    // merely bigger.
    val glowWidth = r * (0.14f + 0.10f * bass)
    val sharpWidth = r * (0.09f + 0.07f * bass)
    val spinDeg = if (isPlaying) (clockMs / 5200f * 360f) % 360f else 0f
    val spinRevDeg = if (isPlaying) -(clockMs / 7600f * 360f) % 360f else 0f

    rotate(spinDeg, center) {
        drawCircle(brush = ring, radius = ringRadius, center = center, alpha = 0.4f + 0.35f * bass, style = Stroke(width = glowWidth))
    }
    rotate(spinRevDeg, center) {
        drawCircle(brush = ring, radius = ringRadius, center = center, style = Stroke(width = sharpWidth))
    }

    // The inner disc tracks overall loudness rather than bass, so the two move
    // semi-independently instead of pulsing as one solid blob.
    val discRadius = r * (0.28f + 0.12f * level)
    drawCircle(brush = discBrush, radius = discRadius, center = center)
    drawCircle(color = color, radius = discRadius, center = center, alpha = 0.35f, style = Stroke(width = 1.dp.toPx()))
}

private data class ParticleSpec(val lat: Float, val lon: Float, val durationMs: Int, val delayMs: Int)

/** `vizSphere`/`vizPop`: a Fibonacci-lattice point cloud, continuously spun around
 * Y (16000ms) with a fixed -16° X tilt. Real perspective-projected 3D with
 * back-to-front depth sorting, using preallocated parallel arrays reused every frame
 * rather than allocating a fresh list + sort per frame.
 *
 * Each point is mapped onto its own frequency band and pushed radially outward by
 * that band's live dB level - because the Fibonacci lattice orders points by
 * latitude, the spectrum wraps around the sphere from pole to pole, so bass literally
 * moves one end of the sphere while treble shimmers at the other and individual
 * points spike independently. Its dot size and brightness scale with the same value,
 * so a hot band reads as a bright flare rather than only a displacement.
 *
 * Layered on top: one traveling bass wave, `sin(latitude*3 - time*speed)` scaled by
 * [bassAmplitude], so a kick sends a visible wavefront sweeping across the surface
 * instead of every point merely pulsing in place. */
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
        // Base radius plus the maximum band spike and wave offset, multiplied by the
        // worst-case perspective scale (~1.2 for the nearest points), has to stay
        // inside sizePx/2 - the overlay is only a 64dp-tall strip, so a larger sphere
        // would push its poles up out of the artwork scrim on loud passages.
        val rPx = sizePx * 0.22f
        val dotRadiusPx = maxOf(1.5.dp.toPx(), sizePx * 0.015f)
        val perspective = sizePx * 2.6f
        val spinRad = if (isPlaying) (clockMs.value / 16000f) * 2f * PI.toFloat() else 0f
        val cosSpin = cos(spinRad)
        val sinSpin = sin(spinRad)
        val cosTilt = cos(tiltRad)
        val sinTilt = sin(tiltRad)
        val bandsValue = bands.value
        val bass = if (isPlaying) bassAmplitude(bandsValue) else 0f
        // Wave travels one full pole-to-pole cycle every ~2200ms; the per-point phase
        // offset by latitude is what makes it sweep across the sphere rather than
        // every point pulsing in lockstep.
        val wavePhase = clockMs.value / 2200f * 2f * PI.toFloat()

        for (i in points.indices) {
            val p = points[i]
            val amp = ampFor(i, n, bandsValue, isPlaying)
            // Paused only: a fixed per-point offset (the clock is stopped, so this is a
            // constant, not an animation) that keeps the resting sphere from looking
            // like a perfectly smooth ball. During playback the band level moves the
            // point, not this.
            val idle = if (isPlaying) 0f else hump(phaseOf(clockMs.value, p.delayMs, p.durationMs)) * 0.04f
            val wave = if (isPlaying) sin(p.lat * 3f - wavePhase) * bass * 0.08f else 0f
            val radius = rPx * (1f + amp * 0.45f + idle) + wave * sizePx
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
            dotRadius[i] = dotRadiusPx * (0.75f + 1.15f * amp) * scaleProj
            alpha[i] = 0.32f + 0.68f * amp
        }

        // In-place insertion sort of `order` by `depth` - back-to-front (farthest
        // first) so nearer points correctly paint over farther ones. Cheap (near O(n))
        // since particle order only shifts gradually frame to frame as the sphere
        // rotates, unlike a fresh sort from scratch every time.
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

/** Maximum simultaneously-live confetti pieces in [PulseVisualizer]. Fixed-size pool,
 * recycled oldest-first, so a sustained loud passage can't grow the allocation. */
private const val MAX_CONFETTI = 260

private class ConfettiPiece {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var rot = 0f
    var vrot = 0f
    var life = 0f
    var maxLife = 1f
    var halfW = 0f
    var halfH = 0f
    var colorIndex = 0
}

/** Mutable confetti simulation state. Lives in `remember` and is mutated from inside
 * the Canvas draw lambda - deliberately plain objects rather than Compose State, so
 * advancing the simulation each frame redraws without triggering recomposition (the
 * same reason the particle sphere above uses raw FloatArrays). */
private class ConfettiField {
    val pieces = Array(MAX_CONFETTI) { ConfettiPiece() }
    var nextSlot = 0
    var lastClockMs = Float.NaN
    var baseline = 0f
    var spawnAccumulator = 0f
    var burstCooldownMs = 0f
    val random = Random(0x51F7)

    fun spawn(count: Int, cx: Float, cy: Float, scalePx: Float, power: Float) {
        repeat(count) {
            val piece = pieces[nextSlot]
            nextSlot = (nextSlot + 1) % MAX_CONFETTI
            val angle = random.nextFloat() * 2f * PI.toFloat()
            // Speeds are deliberately modest relative to [scalePx]: the overlay is a
            // 200x64dp strip, and anything faster crosses it (and gets clipped away)
            // in under two frames, which reads as a flicker rather than a spray. At
            // these values a piece stays visible for roughly its whole lifetime.
            val speed = scalePx * (0.25f + 0.85f * power) * (0.4f + random.nextFloat() * 0.7f)
            piece.x = cx
            piece.y = cy
            piece.vx = cos(angle) * speed
            piece.vy = sin(angle) * speed - scalePx * 0.25f
            piece.rot = random.nextFloat() * 360f
            piece.vrot = (random.nextFloat() - 0.5f) * 900f
            piece.maxLife = 0.45f + random.nextFloat() * 0.55f
            piece.life = piece.maxLife
            piece.halfW = scalePx * (0.012f + random.nextFloat() * 0.012f)
            piece.halfH = piece.halfW * (1.6f + random.nextFloat() * 1.1f)
            piece.colorIndex = random.nextInt(CONFETTI_COLOR_COUNT)
        }
    }

    fun advance(dt: Float, gravityPx: Float) {
        // Frame-rate-independent exponential drag: 0.12 of the velocity bled off per
        // 16ms, expressed as a per-dt factor so a dropped frame doesn't make confetti
        // sail further than it should.
        val drag = 1f - (0.12f * (dt / 0.016f)).coerceIn(0f, 0.9f)
        for (piece in pieces) {
            if (piece.life <= 0f) continue
            piece.vy += gravityPx * dt
            piece.vx *= drag
            piece.vy *= drag
            piece.x += piece.vx * dt
            piece.y += piece.vy * dt
            piece.rot += piece.vrot * dt
            piece.life -= dt
        }
    }
}

private const val CONFETTI_COLOR_COUNT = 4

/** `vizRing`/`vizShoot`/`vizOrb`: 3 expanding-and-fading ring outlines staggered
 * 700ms apart, 18 small rotating streaks "shooting" outward (their reach scaled by
 * live bass), a breathing core dot - and a confetti spray.
 *
 * The confetti is the loudness read-out: pieces are sprayed continuously at a rate
 * proportional to overall level (so a loud passage genuinely buries the view in
 * confetti and a quiet one produces almost none), plus an extra burst whenever the
 * energy jumps clear of its own rolling baseline, which is what makes drops and
 * beat hits land as a visible pop. Each piece is a rotating rounded rect under
 * gravity with drag, fading out over its lifetime. */
@Composable
private fun PulseVisualizer(
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    bands: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    val accent2 = Canopy.accent2
    val accent = Canopy.accent
    val accent200 = Canopy.accent200
    val field = remember { ConfettiField() }
    val confettiColors = remember(color, accent, accent2, accent200) {
        arrayOf(accent2, accent, accent200, color)
    }

    Canvas(modifier = modifier) {
        val r = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val bandsValue = bands.value
        val bass = if (isPlaying) bassAmplitude(bandsValue) else 0f
        val level = if (isPlaying) overallAmplitude(bandsValue) else 0f

        // --- confetti simulation ---------------------------------------------
        // dt from the shared clock. Clamped: the clock wraps once an hour (negative
        // delta) and barely advances while paused, and a backgrounded/janked frame can
        // produce an arbitrarily large gap that would teleport every piece.
        val nowMs = clockMs.value
        val rawDt = if (field.lastClockMs.isNaN()) 0f else nowMs - field.lastClockMs
        field.lastClockMs = nowMs
        val dtMs = rawDt.coerceIn(0f, 48f)
        val dt = dtMs / 1000f

        if (isPlaying) {
            // Rolling baseline of recent energy; an onset is energy jumping clear of
            // it. Comparing against a moving baseline rather than a fixed threshold is
            // what makes this work at any track volume.
            val energy = maxOf(level, bass)
            field.baseline += (energy - field.baseline) * (dt * 2.4f).coerceIn(0f, 1f)
            if (field.burstCooldownMs > 0f) field.burstCooldownMs -= dtMs
            if (energy > field.baseline + 0.09f && energy > 0.22f && field.burstCooldownMs <= 0f) {
                field.spawn((10 + energy * 52f).toInt(), center.x, center.y, r, energy)
                field.burstCooldownMs = 90f
            }
            // Continuous spray, quadratic in level so quiet passages stay nearly clear
            // while loud ones genuinely fill the view.
            field.spawnAccumulator += level * level * 150f * dt
            val steady = field.spawnAccumulator.toInt()
            if (steady > 0) {
                field.spawnAccumulator -= steady
                field.spawn(steady, center.x, center.y, r, level)
            }
        }
        field.advance(dt, gravityPx = r * 1.2f)

        // --- rings -----------------------------------------------------------
        for (i in 0..2) {
            val progress = if (isPlaying) phaseOf(clockMs.value, delayMs = i * 700, durationMs = 2100) else 0f
            // Tops out at 0.94 rather than 1.0 so the outermost ring plus its 1dp
            // half-stroke stays inside r/2 instead of poking a pixel past it. Paused,
            // the three rings sit at staggered radii instead of all three landing on
            // the same circle and overdrawing into one thick ring.
            val scale = if (isPlaying) 0.35f + 0.59f * progress else 0.5f + 0.22f * i
            val ringAlpha = if (isPlaying) 0.85f * (1f - progress) else 0.5f
            drawCircle(color = color.copy(alpha = ringAlpha), radius = (r / 2f) * scale, center = center, style = Stroke(width = 2.dp.toPx()))
        }

        // --- shooting streaks ------------------------------------------------
        if (isPlaying) {
            for (i in 0 until 18) {
                val angle = (360f / 18f * i + if (i % 2 == 1) 9f else 0f) * (PI.toFloat() / 180f)
                val amp = (r / 2f) * (0.4f + bass * 0.9f)
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

        // --- core ------------------------------------------------------------
        val coreBreathe = if (isPlaying) 0.7f + 0.5f * bass else 1f
        drawCircle(color = color, radius = (r / 2f) * 0.24f * coreBreathe, center = center)

        // --- confetti (drawn last, over everything) --------------------------
        for (piece in field.pieces) {
            if (piece.life <= 0f) continue
            val lifeFraction = (piece.life / piece.maxLife).coerceIn(0f, 1f)
            translate(left = piece.x, top = piece.y) {
                rotate(degrees = piece.rot, pivot = Offset.Zero) {
                    drawRoundRect(
                        color = confettiColors[piece.colorIndex].copy(alpha = lifeFraction),
                        topLeft = Offset(-piece.halfW, -piece.halfH),
                        size = Size(piece.halfW * 2f, piece.halfH * 2f),
                        cornerRadius = CornerRadius(piece.halfW * 0.5f, piece.halfW * 0.5f),
                    )
                }
            }
        }
    }
}
