package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import dev.schlubbe.musicagent.desktop.audio.SpectrumAnalyzer
import dev.schlubbe.musicagent.desktop.audio.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Visualizer styles of the Android player (Visualizer.kt), in selector order. */
val VISUALIZER_VARIANTS = listOf(
    "circle" to "Kreis",
    "bars" to "Balken",
    "particles" to "Partikel",
    "orb" to "Orb",
    "pulse" to "Puls",
    "none" to "Cover",
)

private val SEQ = floatArrayOf(1f, .55f, .8f, .35f, .95f, .6f, .75f, .45f, .9f, .5f, .7f, .85f, .4f, 1f, .65f, .55f)
private fun seq(i: Int) = SEQ[i % SEQ.size]

private val ACCENT200 = Color(0xFFC3DED2)
private val ACCENT900 = Color(0xFF0D1F1A)

/** Band level for element [i] of [count] (no fake animation while silent; rest shape while paused). */
private fun ampFor(i: Int, count: Int, bands: FloatArray, playing: Boolean): Float {
    if (!playing) return seq(i)
    if (bands.isEmpty() || count <= 0) return 0f
    return bands[(i * bands.size / count).coerceIn(0, bands.size - 1)]
}

private fun hump(p: Float) = sin(p * PI.toFloat())
private fun phaseOf(clockMs: Float, delayMs: Int, durationMs: Int) = ((clockMs + delayMs) % durationMs) / durationMs

/** One precomputed point of the Fibonacci sphere. */
class SpherePoint(val x: Float, val y: Float, val z: Float, val bandLow: Int, val bandHigh: Int, val bandBlend: Float, val colorBucket: Int)

const val SPHERE_POINTS = 1000
private const val COLOR_BUCKETS = 8
private const val LEVEL_BUCKETS = 6
private const val HALO_FROM_LEVEL = 5
private const val GOLDEN_ANGLE = 2.399963229728653

/**
 * Fibonacci lattice on the unit sphere. Index is latitude, so each point owns one
 * position along the spectrum (bass at one pole, treble at the other).
 * Longitude is accumulated in double precision (i * GOLDEN_ANGLE in Float collapses into spokes).
 */
fun fibonacciSphere(n: Int, bands: Int = SpectrumAnalyzer.BANDS): List<SpherePoint> {
    var lon = 0.0
    return List(n) { i ->
        val axial = 1f - (2 * i + 1f) / n
        val ring = sqrt((1f - axial * axial).coerceAtLeast(0f))
        val l = lon
        lon += GOLDEN_ANGLE
        if (lon >= 2 * PI) lon -= 2 * PI
        val pos = if (n > 1) i / (n - 1f) else 0f
        val bp = pos * (bands - 1)
        val low = bp.toInt().coerceIn(0, bands - 1)
        SpherePoint(
            (cos(l) * ring).toFloat(), axial, (sin(l) * ring).toFloat(),
            low, (low + 1).coerceAtMost(bands - 1), bp - low,
            (pos * (COLOR_BUCKETS - 1)).roundToInt().coerceIn(0, COLOR_BUCKETS - 1),
        )
    }
}

private class Ripple(val bornMs: Float, val strength: Float)

private class VizState(n: Int) {
    var clockMs = 0f
    var lastOnset = 0f
    val ripples = ArrayList<Ripple>()
    val points = fibonacciSphere(n)
    val screenXy = FloatArray(2 * n)
    val packed = FloatArray(2 * n)
    val bucketOf = IntArray(n)
    val counts = IntArray(COLOR_BUCKETS * LEVEL_BUCKETS)
    val starts = IntArray(COLOR_BUCKETS * LEVEL_BUCKETS)
    val colors = IntArray(COLOR_BUCKETS * LEVEL_BUCKETS)
    val paint = org.jetbrains.skia.Paint().apply {
        isAntiAlias = true
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
        blendMode = org.jetbrains.skia.BlendMode.PLUS
    }
}

/**
 * Port of the Android player's five visualizers, driven by the live
 * [SpectrumAnalyzer] frame polled on every display frame.
 */
@Composable
fun Visualizer(
    variant: String,
    playing: Boolean,
    frameSource: () -> VisualizerFrame,
    accent2: Color,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.92f),
) {
    if (variant == "none") return
    val tick = remember { mutableLongStateOf(0L) }
    val st = remember { VizState(SPHERE_POINTS) }
    LaunchedEffect(playing) {
        if (!playing) { tick.longValue++; return@LaunchedEffect }
        var prev = 0L
        while (true) {
            withFrameNanos { now ->
                if (prev != 0L) st.clockMs = (st.clockMs + (now - prev) / 1_000_000f) % 3_600_000f
                prev = now
                tick.longValue = now
            }
        }
    }
    Canvas(modifier) {
        tick.longValue // read => redraw every frame
        val frame = if (playing) frameSource() else VisualizerFrame.EMPTY
        when (variant) {
            "bars" -> drawBars(frame, playing, st.clockMs, color)
            "orb" -> drawOrb(frame, playing, st.clockMs, color)
            "particles" -> drawParticles(st, frame, playing, st.clockMs, color, accent2)
            "pulse" -> drawPulse(st, frame, playing, st.clockMs, color, accent2)
            else -> drawCircleViz(frame, playing, st.clockMs, color, accent2)
        }
    }
}

private fun DrawScope.drawCircleViz(frame: VisualizerFrame, playing: Boolean, clockMs: Float, color: Color, accent2: Color) {
    val sizePx = min(size.width, size.height)
    if (sizePx <= 0f) return
    val center = center
    val inner0 = sizePx * 0.29f
    val maxBar = sizePx * 0.19f
    val bars = 48
    val half = bars / 2
    val slot = 2f * PI.toFloat() * inner0 / bars
    val stroke = maxOf(dp(1.4), slot * 0.62f)
    val rot = if (playing) clockMs / 60_000f * 2f * PI.toFloat() else 0f
    drawCircle(color.copy(alpha = 0.22f), inner0, center, style = Stroke(maxOf(1f, sizePx * 0.006f)))
    for (k in 0 until bars) {
        val m = if (k < half) k else bars - 1 - k
        val amp = ampFor(m, half, frame.bands, playing)
        val a = -PI.toFloat() / 2f + k.toFloat() / bars * 2f * PI.toFloat() + rot
        val len = maxBar * (0.08f + 0.92f * amp)
        val ri = inner0 - len * 0.38f
        val ro = inner0 + len
        val tint = lerp(accent2, ACCENT200, m / (half - 1f))
        drawLine(
            tint.copy(alpha = 0.30f + 0.70f * amp),
            Offset(center.x + cos(a) * ri, center.y + sin(a) * ri), Offset(center.x + cos(a) * ro, center.y + sin(a) * ro),
            stroke, StrokeCap.Round, blendMode = BlendMode.Plus,
        )
    }
    drawCircle(color.copy(alpha = 0.16f + 0.30f * frame.bass), inner0 * (0.30f + 0.22f * frame.bass), center, blendMode = BlendMode.Plus)
}

private fun DrawScope.dp(v: Double) = (v * density).toFloat()

private fun DrawScope.drawBars(frame: VisualizerFrame, playing: Boolean, clockMs: Float, color: Color) {
    val count = 48
    val gap = dp(3.0)
    val w = (size.width - gap * (count - 1)) / count
    if (w <= 0f) return
    val maxH = size.height * 0.8f
    val base = size.height * 0.9f
    for (i in 0 until count) {
        val amp = ampFor(i, count, frame.bands, playing)
        val bounce = if (playing) 0.93f + 0.07f * hump(phaseOf(clockMs, i * 55, 480 + (i * 97) % 420)) else 1f
        val h = maxH * (0.04f + 0.96f * amp) * bounce
        val x = i * (w + gap)
        drawRoundRect(color, Offset(x, base - h), Size(w, h), CornerRadius(w / 2))
        // faint floor reflection
        drawRoundRect(color.copy(alpha = 0.12f), Offset(x, base + gap), Size(w, h * 0.12f), CornerRadius(w / 2))
    }
}

private fun DrawScope.drawOrb(frame: VisualizerFrame, playing: Boolean, clockMs: Float, color: Color) {
    val r = size.minDimension / 2f
    if (r <= 0f) return
    val c = center
    val ring = Brush.sweepGradient(
        0f to Color.Transparent, 60f / 360f to color, 120f / 360f to ACCENT200,
        200f / 360f to Color.Transparent, 300f / 360f to color, 1f to Color.Transparent, center = c,
    )
    val bass = frame.bass
    val ringR = r * if (playing) 0.50f + 0.34f * bass else 0.72f
    val glowW = r * (0.14f + 0.10f * bass + 0.06f * frame.onset)
    val sharpW = r * (0.09f + 0.07f * bass + 0.04f * frame.onset)
    val spin = if (playing) (clockMs / 5200f * 360f) % 360f else 0f
    val spinRev = if (playing) -(clockMs / 7600f * 360f) % 360f else 0f
    rotate(spin, c) { drawCircle(ring, ringR, c, alpha = 0.4f + 0.35f * bass, style = Stroke(glowW)) }
    rotate(spinRev, c) { drawCircle(ring, ringR, c, style = Stroke(sharpW)) }
    val discRef = r * 0.40f
    val disc = Brush.radialGradient(listOf(ACCENT900, Color(0xFF06110D)), Offset(c.x, c.y - discRef * 0.16f), discRef * 1.3f)
    val discR = r * (0.28f + 0.12f * frame.level)
    drawCircle(disc, discR, c)
    drawCircle(color, discR, c, alpha = 0.35f, style = Stroke(dp(1.0)))
}

private fun DrawScope.drawParticles(st: VizState, frame: VisualizerFrame, playing: Boolean, clockMs: Float, color: Color, accent2: Color) {
    val sizePx = min(size.width, size.height)
    if (sizePx <= 0f) return
    val c = center
    val baseR = sizePx * 0.30f
    val dotUnit = maxOf(dp(0.85), sizePx * 0.0042f)
    val persp = baseR * 3.2f
    val bands = frame.bands
    val spin = if (playing) clockMs / 16000f * 2f * PI.toFloat() else 0f
    val cs = cos(spin); val ss = sin(spin)
    val tilt = -16f * PI.toFloat() / 180f
    val ct = cos(tilt); val stl = sin(tilt)
    st.counts.fill(0)
    val pts = st.points
    for (i in pts.indices) {
        val p = pts[i]
        val amp = if (!playing) seq(i) * 0.5f else { val lo = bands[p.bandLow]; lo + (bands[p.bandHigh] - lo) * p.bandBlend }
        val rad = baseR * (1f + amp * 0.55f)
        val y1 = p.y * ct - p.z * stl
        val z1 = p.y * stl + p.z * ct
        val x2 = p.x * cs + z1 * ss
        val z2 = -p.x * ss + z1 * cs
        val proj = persp / (persp - z2 * rad)
        st.screenXy[2 * i] = c.x + x2 * rad * proj
        st.screenXy[2 * i + 1] = c.y + y1 * rad * proj
        val depth = (proj * 0.68f).coerceIn(0.30f, 1.05f)
        val vis = ((0.12f + 0.88f * amp) * depth).coerceIn(0f, 1f)
        val lvl = (vis * (LEVEL_BUCKETS - 1)).roundToInt().coerceIn(0, LEVEL_BUCKETS - 1)
        val b = p.colorBucket * LEVEL_BUCKETS + lvl
        st.bucketOf[i] = b
        st.counts[b]++
    }
    var run = 0
    for (b in st.counts.indices) { st.starts[b] = run; run += st.counts[b] }
    val cursor = st.starts.copyOf()
    for (i in pts.indices) {
        val s = cursor[st.bucketOf[i]]++
        st.packed[2 * s] = st.screenXy[2 * i]; st.packed[2 * s + 1] = st.screenXy[2 * i + 1]
    }
    for (ci in 0 until COLOR_BUCKETS) {
        val tint = lerp(accent2, ACCENT200, ci / (COLOR_BUCKETS - 1f))
        for (l in 0 until LEVEL_BUCKETS) {
            val rep = l / (LEVEL_BUCKETS - 1f)
            st.colors[ci * LEVEL_BUCKETS + l] = lerp(tint, Color.White, (0.25f * rep + 0.55f * frame.onset).coerceIn(0f, 1f)).toArgb()
        }
    }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val paint = st.paint
        for (pass in 0..1) for (b in st.counts.indices) {
            val n0 = st.counts[b]
            if (n0 == 0) continue
            val lvl = b % LEVEL_BUCKETS
            if (pass == 0 && lvl < HALO_FROM_LEVEL) continue
            val rep = lvl / (LEVEL_BUCKETS - 1f)
            val alpha = (0.10f + 0.62f * rep) * (if (pass == 0) 0.11f else 1f)
            paint.color = (st.colors[b] and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
            paint.strokeWidth = dotUnit * 2f * (0.45f + 1.15f * rep) * (if (pass == 0) 3f else 1f)
            nc.drawPoints(st.packed.copyOfRange(2 * st.starts[b], 2 * (st.starts[b] + n0)), paint)
        }
    }
    drawCircle(color.copy(alpha = 0.05f), baseR * 0.34f, c, blendMode = BlendMode.Plus)
}

private fun DrawScope.drawPulse(st: VizState, frame: VisualizerFrame, playing: Boolean, clockMs: Float, color: Color, accent2: Color) {
    val r = size.minDimension * 0.6f
    if (r <= 0f) return
    val c = center
    val bass = frame.bass
    val onset = frame.onset
    if (!playing) {
        for (i in 0..3) drawCircle(color.copy(alpha = 0.45f), r / 2f * (0.42f + 0.17f * i), c, style = Stroke(dp(1.5)))
    } else {
        // kick-triggered ripples (the Android app draws these at window level, AudioConfettiHost)
        if (onset > 0.95f && st.lastOnset <= 0.95f) st.ripples.add(Ripple(clockMs, (0.5f + bass).coerceAtMost(1f)))
        st.lastOnset = onset
        st.ripples.removeAll { clockMs - it.bornMs > 1600f || clockMs < it.bornMs }
        for (rp in st.ripples) {
            val p = (clockMs - rp.bornMs) / 1600f
            drawCircle(lerp(color, accent2, p).copy(alpha = (1f - p) * 0.55f * rp.strength), r / 2f * (0.3f + 1.3f * p), c, style = Stroke(dp(2.0) * (1f - p) + 1f))
        }
        val energy = (0.55f * bass + 0.75f * onset).coerceIn(0f, 1f)
        for (i in 0 until 24) {
            val ang = (360f / 24f * i + if (i % 2 == 1) 7.5f else 0f) * (PI.toFloat() / 180f)
            val amp = r / 2f * (0.30f + energy * 1.25f)
            val dx = cos(ang) * amp
            val dy = sin(ang) * amp
            val rotDeg = (if (i % 2 == 1) 1f else -1f) * (120f + seq(i) * 240f)
            val prog = phaseOf(clockMs, (i % 3) * 560 + (i * 97) % 320, 1200 + (i * 173) % 900)
            val w = maxOf(dp(3.0), r * 0.055f * (0.55f + 0.85f * energy))
            val env = if (prog < 0.14f) prog / 0.14f else 1f - (prog - 0.14f) / 0.86f
            val op = env * (0.08f + 0.92f * energy)
            translate(c.x + dx * prog, c.y + dy * prog) {
                rotate(rotDeg * prog, Offset.Zero) {
                    drawRoundRect((if (i % 3 == 0) accent2 else color).copy(alpha = op), Offset(-w / 2f, -w * 0.85f), Size(w, w * 1.7f), CornerRadius(1f))
                }
            }
        }
    }
    val breathe = if (playing) 0.50f + 0.75f * bass + 0.55f * onset else 1f
    drawCircle(color, r / 2f * 0.26f * breathe, c)
}
