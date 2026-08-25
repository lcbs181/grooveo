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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.playback.EMPTY_VISUALIZER_FRAME
import dev.schlubbe.musicagent.playback.VISUALIZER_BAND_COUNT
import dev.schlubbe.musicagent.playback.VisualizerFrame
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

// Ported verbatim from the design's own `_ds_bundle.js` (`const SEQ = [...]`) - used
// now only as (a) each element's ANIMATION TIMING seed (duration/delay stagger,
// which real audio data has no equivalent of) and (b) the static rest shape shown
// while playback is paused.
private val SEQ = floatArrayOf(1f, .55f, .8f, .35f, .95f, .6f, .75f, .45f, .9f, .5f, .7f, .85f, .4f, 1f, .65f, .55f)
private fun seq(i: Int) = SEQ[i % SEQ.size]

/** Amplitude (0f..1f) for element [i] of [elementCount]: while playing, the live
 * level of the frequency band that element is mapped onto, read from [bands] (a
 * [VISUALIZER_BAND_COUNT]-length spectrum, adaptively normalized - see
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

/** The live frame while playing, and a silent one while paused, so every variant
 * below can read `.bass`/`.level`/`.bassOnset` without repeating the check. */
private fun frameFor(frame: State<VisualizerFrame>, isPlaying: Boolean): VisualizerFrame =
    if (isPlaying) frame.value else EMPTY_VISUALIZER_FRAME

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
    frame: State<VisualizerFrame>,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.92f),
    count: Int = 22,
) {
    val clockMs = rememberVizClockMs(isPlaying)
    when (variant) {
        "bars" -> BarsVisualizer(count, color, isPlaying, clockMs, frame, modifier)
        "orb" -> OrbVisualizer(color, isPlaying, clockMs, frame, modifier)
        "particles" -> ParticlesVisualizer(count, color, isPlaying, clockMs, frame, modifier)
        "pulse" -> PulseVisualizer(color, isPlaying, clockMs, frame, modifier)
        // Also the fallback, so a persisted variant name that no longer exists (the
        // "wave" this replaced) lands somewhere sensible rather than on nothing.
        else -> CircleVisualizer(color, isPlaying, clockMs, frame, modifier)
    }
}

/** Bars radiating outward from a ring - the standard circular spectrum, and the
 * simplest of the five to read at a glance.
 *
 * The spectrum is laid over **half** the circle and mirrored across the vertical axis.
 * That is how essentially every circular visualizer does it, and for a good reason: a
 * spectrum wrapped once around a full circle has a seam where the highest frequency
 * meets the lowest, which reads as a defect. Mirroring removes the seam and makes the
 * figure symmetric, at the cost of showing each frequency twice - a trade every
 * implementation of this takes.
 *
 * Bass sits at the top of the ring and sweeps down both sides to treble at the bottom,
 * with colour following the same path, so the ramp reinforces the frequency layout.
 * The whole figure turns very slowly; fast rotation is the main thing that makes this
 * archetype look cheap, because the eye tracks the spin instead of the spectrum. */
@Composable
private fun CircleVisualizer(
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    frame: State<VisualizerFrame>,
    modifier: Modifier = Modifier,
) {
    val accent2 = Canopy.accent2
    val accent200 = Canopy.accent200
    Canvas(modifier = modifier) {
        val sizePx = min(size.width, size.height)
        if (sizePx <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = sizePx * 0.29f
        // Bar length is capped so the longest bar plus the ring still fits the strip's
        // short edge: 0.29 + 0.19 = 0.48 of half the height.
        val maxBar = sizePx * 0.19f
        val liveFrame = frameFor(frame, isPlaying)
        val bands = liveFrame.bands
        val bass = liveFrame.bass

        val half = CIRCLE_BARS / 2
        val slotWidth = 2f * PI.toFloat() * innerRadius / CIRCLE_BARS
        val stroke = maxOf(1.4.dp.toPx(), slotWidth * 0.62f)
        val rotation = if (isPlaying) (clockMs.value / CIRCLE_ROTATION_MS) * 2f * PI.toFloat() else 0f

        // The ring the bars stand on. Fixed alpha rather than audio-scaled: it is the
        // baseline the bar lengths are read against, so it should not move.
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = innerRadius,
            center = center,
            style = Stroke(width = maxOf(1f, sizePx * 0.006f)),
        )

        for (k in 0 until CIRCLE_BARS) {
            // Mirror: slots 0..half-1 run bass to treble, and the far half walks back.
            val mirrored = if (k < half) k else CIRCLE_BARS - 1 - k
            val amp = ampFor(mirrored, half, bands, isPlaying)
            // Straight up is -PI/2, so bass lands at the top of the ring.
            val angle = -PI.toFloat() / 2f + (k.toFloat() / CIRCLE_BARS) * 2f * PI.toFloat() + rotation
            val cosA = cos(angle)
            val sinA = sin(angle)
            // Grows both ways off the ring, roughly a third inward and two thirds
            // outward, which is what Wave.js's Shine calls its `offset` mode. A bar that
            // only grows outward reads as a spike stuck to a circle; one that straddles
            // the ring reads as the ring itself being displaced.
            val barLength = maxBar * (0.08f + 0.92f * amp)
            val inner = innerRadius - barLength * 0.38f
            val outer = innerRadius + barLength
            val tint = lerp(accent2, accent200, mirrored / (half - 1f))
            drawLine(
                color = tint.copy(alpha = 0.30f + 0.70f * amp),
                start = Offset(center.x + cosA * inner, center.y + sinA * inner),
                end = Offset(center.x + cosA * outer, center.y + sinA * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }

        // Core disc, the one element allowed to follow the bass - it is at the centre of
        // the ring rather than on it, so it does not compete with the bar lengths.
        drawCircle(
            color = color.copy(alpha = 0.16f + 0.30f * bass),
            radius = innerRadius * (0.30f + 0.22f * bass),
            center = center,
            blendMode = BlendMode.Plus,
        )
    }
}

/** Radial slots around the ring. Even, so the mirror is symmetric.
 *
 * Wave.js's Shine defaults to 30 around a full circle; 48 here because these are
 * mirrored, so it is 24 distinct frequencies rather than 48 - close to the reference
 * once the mirroring is accounted for, and about the point at this size where adjacent
 * bars stop being individually distinguishable. */
private const val CIRCLE_BARS = 48

/** One full revolution. Deliberately slow - around six degrees a second. */
private const val CIRCLE_ROTATION_MS = 60_000f

/** `eqBounce`: the classic "equalizer forest" - each bar's height is its own live
 * frequency band, with only a slight timer breathe left on top.
 *
 * That breathe used to swing 0.55..1.0, i.e. it moved each bar by up to 45% of its
 * height on a cycle unrelated to the sound, comfortably out-shouting the band level
 * underneath. It is now ±7%, enough to keep the forest from looking mechanically
 * still, far too little to be mistaken for a reaction. */
@Composable
private fun BarsVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    frame: State<VisualizerFrame>,
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
                        val amp = ampFor(i, count, frameFor(frame, isPlaying).bands, isPlaying)
                        // A small floor keeps a silent passage as a visible thin line
                        // rather than a bar that vanishes entirely.
                        val bounce = if (isPlaying) 0.93f + 0.07f * hump(phaseOf(clockMs.value, delayMs = i * 55, durationMs = durationMs)) else 1f
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
    frame: State<VisualizerFrame>,
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
                drawOrb(r, center, ring, discBrush, color, frameFor(frame, isPlaying), isPlaying, clockMs.value)
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
    frame: VisualizerFrame,
    isPlaying: Boolean,
    clockMs: Float,
) {
    val bass = frame.bass
    val level = frame.level
    val onset = frame.bassOnset

    // Radius swings 0.50r -> 0.84r with bass (a ~68% growth, unmistakable), and the
    // widths are chosen so the ring's *outer* edge - radius plus half the glow
    // stroke - stays within r even at full bass: 0.84 + 0.12 = 0.96. The overlay is
    // only a 64dp-tall strip, and while Compose does not clip a Canvas to its own
    // bounds, overshooting here would spill the ring up out of the artwork scrim it
    // is supposed to sit inside.
    val ringRadius = r * if (isPlaying) 0.50f + 0.34f * bass else 0.72f
    // Thicker stroke on a bass hit too, so the ring reads as "energized" rather than
    // merely bigger - and thicker again for the instant of an actual kick, which is
    // what turns a smooth breathe into something that visibly lands on the beat.
    val glowWidth = r * (0.14f + 0.10f * bass + 0.06f * onset)
    val sharpWidth = r * (0.09f + 0.07f * bass + 0.04f * onset)
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

/** One point of the sphere, precomputed: its unit direction, which two bands its level
 * is interpolated between, and which colour bucket it belongs to. */
private class SpherePoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val bandLow: Int,
    val bandHigh: Int,
    val bandBlend: Float,
    /** 0f at one pole, 1f at the other - drives the colour ramp. */
    val axial: Float,
    val colorBucket: Int,
)

/** Points on the sphere.
 *
 * Each one owns its own frequency (see the band mapping below), so this is really a
 * statement about how finely the spectrum is sampled across the surface, not just about
 * density. Many small points read as a surface; few large ones read as scattered blobs.
 *
 * A count this high is only affordable because the points are drawn in batches rather
 * than one at a time - see [SPHERE_COLOR_BUCKETS]. */
private const val SPHERE_POINTS = 1000

/**
 * Points are bucketed by colour and by brightness, and each bucket is drawn with a
 * single batched call, so the per-frame draw count is a few dozen rather than one per
 * point.
 *
 * This is not premature optimization: measured on device, 1000 individual `drawCircle`
 * calls a frame ran at 36ms per frame - about 28fps, with every single frame reported
 * as janky. Batching is what makes "way more points" possible at all. Colour depends
 * only on a point's fixed position along the sphere's axis, so its bucket is assigned
 * once at construction; brightness depends on the point's live band level and depth, so
 * that bucket is chosen per frame.
 *
 * The bucket count is the trade: too few and the colour ramp and the size steps become
 * visibly banded, too many and the batching stops saving anything. Eight by six is 48
 * possible batches over 1000 points, i.e. around twenty points per batch.
 */
private const val SPHERE_COLOR_BUCKETS = 8
private const val SPHERE_LEVEL_BUCKETS = 6

/** Brightness buckets at or above this index also get the wide, faint halo pass that
 * produces the bloom. Restricted to the brightest points because the halo covers ~9x
 * the area of the core dot, so it costs fill rate rather than draw calls. */
private const val SPHERE_HALO_FROM_LEVEL = 4

/** Golden angle, in radians, at full double precision.
 *
 * Accumulated per point rather than computed as `i * GOLDEN_ANGLE`, which is a real trap
 * here: that product grows large enough that Float's 24-bit mantissa quantizes the
 * result, and the lattice visibly collapses into radial spokes instead of staying evenly
 * spread. */
private const val GOLDEN_ANGLE = 2.399963229728653

/** `vizSphere`: a Fibonacci-lattice point cloud, spun slowly around Y under a fixed
 * tilt, drawn with perspective and additive blending.
 *
 * **Every point owns one frequency and moves only on that frequency's loudness.** The
 * Fibonacci lattice orders its points monotonically by latitude, so index *is* latitude:
 * the spectrum is laid along the sphere's axis, bass at one pole and treble at the other,
 * and each point blends the two bands it falls between so band edges are not visible as
 * steps. There is deliberately no global pulse and no travelling deformation field - any
 * sphere-wide motion is motion that is not that point's own frequency doing it, and it
 * drowns out the per-point response underneath.
 *
 * Two rendering choices do most of the work of making that read as a lit surface rather
 * than as noise. **Additive blending** means overlapping points sum instead of occluding,
 * so density becomes brightness and the cloud glows where it is thick - and it makes draw
 * order irrelevant, which removes the per-frame depth sort entirely. **A halo** behind the
 * brightest points, much wider and very faint, sums across neighbours into bloom without
 * any post-processing pass. */
@Composable
private fun ParticlesVisualizer(
    count: Int,
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    frame: State<VisualizerFrame>,
    modifier: Modifier = Modifier,
) {
    val n = if (count < 24) SPHERE_POINTS else count
    val points = remember(n) {
        var longitude = 0.0
        List(n) { i ->
            val axial = 1f - (2 * i + 1f) / n
            val ringRadius = sqrt((1f - axial * axial).coerceAtLeast(0f))
            val lon = longitude
            longitude += GOLDEN_ANGLE
            if (longitude >= 2.0 * PI) longitude -= 2.0 * PI
            val position = i / (n - 1f)
            val bandPosition = position * (VISUALIZER_BAND_COUNT - 1)
            val low = bandPosition.toInt().coerceIn(0, VISUALIZER_BAND_COUNT - 1)
            SpherePoint(
                x = (cos(lon) * ringRadius).toFloat(),
                y = axial,
                z = (sin(lon) * ringRadius).toFloat(),
                bandLow = low,
                bandHigh = (low + 1).coerceAtMost(VISUALIZER_BAND_COUNT - 1),
                bandBlend = bandPosition - low,
                axial = position,
                colorBucket = (position * (SPHERE_COLOR_BUCKETS - 1)).roundToInt()
                    .coerceIn(0, SPHERE_COLOR_BUCKETS - 1),
            )
        }
    }

    // Scratch reused every frame. Positions are collected first, then packed into
    // per-bucket runs by a counting sort, so each bucket's points end up contiguous and
    // can be handed to one drawPoints call without allocating anything per frame.
    val bucketCount = SPHERE_COLOR_BUCKETS * SPHERE_LEVEL_BUCKETS
    val screenXy = remember(n) { FloatArray(2 * n) }
    val packed = remember(n) { FloatArray(2 * n) }
    val bucketOf = remember(n) { IntArray(n) }
    val bucketCounts = remember(bucketCount) { IntArray(bucketCount) }
    val bucketStarts = remember(bucketCount) { IntArray(bucketCount) }
    val bucketColors = remember(bucketCount) { IntArray(bucketCount) }
    // One reusable native Paint - drawPoints draws a round dot per point when the paint
    // is a round-capped stroke, with strokeWidth as the dot's diameter.
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            blendMode = android.graphics.BlendMode.PLUS
        }
    }

    val accent2 = Canopy.accent2
    val accent200 = Canopy.accent200
    val tiltRad = -16f * PI.toFloat() / 180f

    Canvas(modifier = modifier) {
        val sizePx = min(this.size.width, this.size.height)
        if (sizePx <= 0f || points.isEmpty()) return@Canvas
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // Larger sphere, smaller dots. Sized off the strip's short edge, and allowed to
        // reach past it horizontally, which is free - the overlay sits on the artwork and
        // there is far more width available than height.
        val baseRadius = sizePx * 0.34f
        val dotUnit = maxOf(0.85.dp.toPx(), sizePx * 0.0072f)
        // Perspective divisor as a multiple of the sphere's radius; ~3r gives a clear
        // near/far difference without the near pole ballooning.
        val perspective = baseRadius * 3.2f
        val liveFrame = frameFor(frame, isPlaying)
        val bands = liveFrame.bands
        val onset = liveFrame.bassOnset

        val spinRad = if (isPlaying) (clockMs.value / 16000f) * 2f * PI.toFloat() else 0f
        val cosSpin = cos(spinRad)
        val sinSpin = sin(spinRad)
        val cosTilt = cos(tiltRad)
        val sinTilt = sin(tiltRad)

        bucketCounts.fill(0)

        for (i in points.indices) {
            val p = points[i]
            val amp = if (!isPlaying) {
                seq(i) * 0.5f
            } else if (bands.isEmpty()) {
                0f
            } else {
                val low = bands[p.bandLow]
                low + (bands[p.bandHigh] - low) * p.bandBlend
            }

            // Radial displacement is this point's own band level, and nothing else.
            val radius = baseRadius * (1f + amp * 0.55f)
            val y1 = p.y * cosTilt - p.z * sinTilt
            val z1 = p.y * sinTilt + p.z * cosTilt
            val x2 = p.x * cosSpin + z1 * sinSpin
            val z2 = -p.x * sinSpin + z1 * cosSpin
            val projection = perspective / (perspective - z2 * radius)

            screenXy[2 * i] = center.x + x2 * radius * projection
            screenXy[2 * i + 1] = center.y + y1 * radius * projection

            // Depth cue folded into the brightness bucket, so nearer points come out both
            // larger and brighter - which is what makes a flat scatter resolve into a
            // sphere.
            val depthFade = (projection * 0.68f).coerceIn(0.30f, 1.05f)
            val visual = ((0.12f + 0.88f * amp) * depthFade).coerceIn(0f, 1f)
            val level = (visual * (SPHERE_LEVEL_BUCKETS - 1)).roundToInt()
                .coerceIn(0, SPHERE_LEVEL_BUCKETS - 1)
            val bucket = p.colorBucket * SPHERE_LEVEL_BUCKETS + level
            bucketOf[i] = bucket
            bucketCounts[bucket]++
        }

        // Prefix sums, then scatter each point into its bucket's run.
        var running = 0
        for (b in 0 until bucketCount) {
            bucketStarts[b] = running
            running += bucketCounts[b]
        }
        val cursor = bucketStarts.copyOf()
        for (i in points.indices) {
            val slot = cursor[bucketOf[i]]++
            packed[2 * slot] = screenXy[2 * i]
            packed[2 * slot + 1] = screenXy[2 * i + 1]
        }

        // Colour per bucket: the ramp along the sphere's axis, washed towards white by a
        // kick. Recomputed per frame because the wash is global, but only once per bucket
        // rather than once per point.
        for (c in 0 until SPHERE_COLOR_BUCKETS) {
            val tint = lerp(accent2, accent200, c / (SPHERE_COLOR_BUCKETS - 1f))
            for (l in 0 until SPHERE_LEVEL_BUCKETS) {
                val rep = l / (SPHERE_LEVEL_BUCKETS - 1f)
                val washed = lerp(tint, Color.White, (0.25f * rep + 0.55f * onset).coerceIn(0f, 1f))
                bucketColors[c * SPHERE_LEVEL_BUCKETS + l] = washed.toArgb()
            }
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // Halo pass first so the cores land on top of their own glow.
            for (b in 0 until bucketCount) {
                val n0 = bucketCounts[b]
                if (n0 == 0) continue
                val level = b % SPHERE_LEVEL_BUCKETS
                if (level < SPHERE_HALO_FROM_LEVEL) continue
                val rep = level / (SPHERE_LEVEL_BUCKETS - 1f)
                paint.color = bucketColors[b]
                paint.alpha = ((0.10f + 0.62f * rep) * 0.11f * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = dotUnit * 2f * (0.45f + 1.15f * rep) * 3.0f
                native.drawPoints(packed, 2 * bucketStarts[b], 2 * n0, paint)
            }
            for (b in 0 until bucketCount) {
                val n0 = bucketCounts[b]
                if (n0 == 0) continue
                val rep = (b % SPHERE_LEVEL_BUCKETS) / (SPHERE_LEVEL_BUCKETS - 1f)
                paint.color = bucketColors[b]
                paint.alpha = ((0.10f + 0.62f * rep) * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = dotUnit * 2f * (0.45f + 1.15f * rep)
                native.drawPoints(packed, 2 * bucketStarts[b], 2 * n0, paint)
            }
        }

        // A faint, fixed core glow so the sphere does not read as hollow. Deliberately not
        // audio-scaled: anything that grows and shrinks here is a second, global motion
        // competing with the per-point response.
        drawCircle(
            color = color.copy(alpha = 0.05f),
            radius = baseRadius * 0.34f,
            center = center,
            blendMode = BlendMode.Plus,
        )
    }
}

/** `vizRing`/`vizShoot`/`vizOrb`: expanding-and-fading ring outlines, rotating
 * streaks "shooting" outward (their reach scaled by live bass) and a breathing core
 * dot - the loudest, most exaggerated of the five variants.
 *
 * The confetti spray that belongs to this variant is *not* drawn here: it has to be
 * able to cover a good part of the screen, and every ancestor of this canvas clips.
 * It lives at window level instead - see
 * [dev.schlubbe.musicagent.ui.player.AudioConfettiHost]. */
@Composable
private fun PulseVisualizer(
    color: Color,
    isPlaying: Boolean,
    clockMs: State<Float>,
    frame: State<VisualizerFrame>,
    modifier: Modifier = Modifier,
) {
    val accent2 = Canopy.accent2
    Canvas(modifier = modifier) {
        val r = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val liveFrame = frameFor(frame, isPlaying)
        val bass = liveFrame.bass
        val onset = liveFrame.bassOnset

        // --- rest-state rings ------------------------------------------------
        // Only while paused. During playback the expanding rings come from the
        // window-level bass ripples (see AudioConfettiHost): those fire on actual kick
        // hits rather than a fixed timer, and are free to expand well past this small
        // canvas. Drawing a second, timer-driven set here on top of them just muddied
        // both and reintroduced motion that had nothing to do with the audio.
        if (!isPlaying) {
            for (i in 0..3) {
                drawCircle(
                    color = color.copy(alpha = 0.45f),
                    radius = (r / 2f) * (0.42f + 0.17f * i),
                    center = center,
                    style = Stroke(width = 1.5f.dp.toPx()),
                )
            }
        }

        // --- shooting streaks ------------------------------------------------
        // The streaks travel on the shared timer (a projectile has to move smoothly;
        // it cannot be repositioned from the spectrum each frame), but their *presence*
        // is gated by the audio: at full opacity they were 24 objects flying outward on
        // pseudo-random per-index durations regardless of what was playing, and that
        // was the single biggest contributor to this variant looking like noise. They
        // now fade almost to nothing between hits and flare on each kick.
        if (isPlaying) {
            val energy = (0.55f * bass + 0.75f * onset).coerceIn(0f, 1f)
            for (i in 0 until 24) {
                val angle = (360f / 24f * i + if (i % 2 == 1) 7.5f else 0f) * (PI.toFloat() / 180f)
                val amp = (r / 2f) * (0.30f + energy * 1.25f)
                val dx = cos(angle) * amp
                val dy = sin(angle) * amp
                val rotDeg = (if (i % 2 == 1) 1f else -1f) * (120f + seq(i) * 240f)
                val durationMs = 1200 + (i * 173) % 900
                val delayMs = (i % 3) * 560 + (i * 97) % 320
                val w = maxOf(3.dp.toPx(), r * 0.055f * (0.55f + 0.85f * energy))

                val progress = phaseOf(clockMs.value, delayMs, durationMs)
                val envelope = if (progress < 0.14f) progress / 0.14f else 1f - (progress - 0.14f) / 0.86f
                val opacity = envelope * (0.08f + 0.92f * energy)
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
        val coreBreathe = if (isPlaying) 0.50f + 0.75f * bass + 0.55f * onset else 1f
        drawCircle(color = color, radius = (r / 2f) * 0.26f * coreBreathe, center = center)
    }
}
