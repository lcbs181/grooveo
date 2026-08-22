package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

// Canopy's deterministic cover generator, ported from `art(seed)` /`hash(seed)`
// in GrooveoApp.dc.html. The handoff is explicit that this is production intent,
// not a prototype placeholder: it "replaces the empty music-note tile" as the
// fallback wherever a track, playlist or artist has no artwork.
//
// The hash is reproduced exactly (h * 31 + charCode, wrapped to 32-bit signed,
// then abs) so a given title always yields the same tile as the mockups show.
// Kotlin's Int is already 32-bit two's-complement, so `h * 31 + c` overflows the
// same way JavaScript's `| 0` forces it to.
private val PAIRS = listOf(
    Color(0xFF2E5E4E) to Color(0xFF95C4AE),
    Color(0xFFFF5A3C) to Color(0xFF7A2415),
    Color(0xFF1D3F34) to Color(0xFF5E9E85),
    Color(0xFF0D1F1A) to Color(0xFF4A8F76),
    Color(0xFFFF7A5C) to Color(0xFF2E5E4E),
    Color(0xFFC3DED2) to Color(0xFF254C40),
    Color(0xFF152E27) to Color(0xFFFF5A3C),
    Color(0xFF4C4D42) to Color(0xFFA9AA9E),
)

private fun artHash(seed: String): Int {
    var h = 0
    for (ch in seed) h = h * 31 + ch.code
    // Math.abs(Int.MIN_VALUE) is still negative in both JS and Kotlin; JS's
    // Math.abs promotes to Double so it can't wrap, so clamp explicitly here
    // rather than letting one pathological seed produce a negative index.
    return if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h)
}

/** Draws the generated cover for [seed] into the current [DrawScope], scaled
 * from the generator's 200x200 viewBox to whatever size the caller gives it. */
private fun DrawScope.drawGeneratedArtwork(seed: String) {
    val h = artHash(seed)
    val (from, to) = PAIRS[h % PAIRS.size]
    val rotation = (h % 180).toFloat()
    // Scale factor from the source viewBox to this draw area. The generator is
    // square; non-square callers stretch, matching objectFit:cover closely
    // enough for a flat gradient with a simple mark.
    val s = size.minDimension / 200f
    fun x(v: Float) = v * s + (size.width - 200f * s) / 2f
    fun y(v: Float) = v * s + (size.height - 200f * s) / 2f

    // The CSS gradient rotates about the tile's centre; drawing the gradient
    // rotated is equivalent and avoids computing rotated endpoint offsets.
    rotate(degrees = rotation) {
        // SVG's default linearGradient runs left-to-right (x1=0,y1=0 -> x2=1,y2=0),
        // and the handoff rotates *that* about the centre. A diagonal start/end
        // here would silently add 45 degrees to every tile's angle. The rect is
        // oversized so the rotated fill still covers the corners.
        val overscan = size.maxDimension
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(from, to),
                start = Offset(size.width / 2f - overscan, size.height / 2f),
                end = Offset(size.width / 2f + overscan, size.height / 2f),
            ),
            topLeft = Offset(size.width / 2f - overscan, size.height / 2f - overscan),
            size = Size(overscan * 2f, overscan * 2f),
        )
    }

    val white32 = Color.White.copy(alpha = 0.32f)
    when (h % 3) {
        // kind 0: five bars, a miniature of the brand's level-meter mark.
        0 -> for (i in 0..4) {
            val jitter = ((h shr i) % 40).toFloat()
            drawRoundRect(
                color = white32,
                topLeft = Offset(x(22f + i * 26f), y(60f + jitter)),
                size = Size(12f * s, (90f - jitter) * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * s, 6f * s),
            )
        }
        // kind 1: two concentric rings.
        1 -> {
            drawCircle(
                color = Color.White.copy(alpha = 0.34f),
                radius = (34f + h % 18) * s,
                center = Offset(x(100f), y(100f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 10f * s),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = (62f + h % 14) * s,
                center = Offset(x(100f), y(100f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f * s),
            )
        }
        // kind 2: two stacked wave crests filling the lower half.
        else -> {
            val wave1 = Path().apply {
                moveTo(x(0f), y(120f + h % 30))
                quadraticTo(x(50f), y(60f + h % 40), x(100f), y(120f + h % 20))
                // The SVG's `T 200 ...` reflects the previous control point;
                // reflecting it manually keeps the same curve shape.
                quadraticTo(x(150f), y((120f + h % 20) * 2 - (60f + h % 40)), x(200f), y(100f + h % 30))
                lineTo(x(200f), y(200f))
                lineTo(x(0f), y(200f))
                close()
            }
            drawPath(wave1, Color.White.copy(alpha = 0.2f))

            val wave2 = Path().apply {
                moveTo(x(0f), y(150f + h % 20))
                quadraticTo(x(60f), y(100f + h % 30), x(120f), y(150f))
                // Reflected control point for the SVG's `T`: 2*P - C1, i.e.
                // (2*120 - 60, 2*150 - (100 + h%30)).
                quadraticTo(x(180f), y(150f * 2 - (100f + h % 30)), x(200f), y(140f))
                lineTo(x(200f), y(200f))
                lineTo(x(0f), y(200f))
                close()
            }
            drawPath(wave2, Color.Black.copy(alpha = 0.18f))
        }
    }
}

/** Canopy's fallback cover: a deterministic gradient tile derived from [seed]
 * (normally a track/playlist/artist title), used wherever there's no real
 * artwork. Same title always gives the same tile, so a given track keeps a
 * stable "cover" across screens and restarts. */
@Composable
fun GeneratedArtwork(seed: String, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) { drawGeneratedArtwork(seed.ifBlank { "grooveo" }) }
}
