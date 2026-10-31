package dev.schlubbe.musicagent.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cover's most colourful tone, for tinting a screen behind it (Spotify's
 * player). Null until the image is loaded or when it has none. Pixels on a 24x24
 * thumbnail are weighted by saturation, so a small bright subject wins over a big
 * grey background. */
@Composable
fun rememberArtworkColor(url: String?): Color? {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(24).allowHardware(false).build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable?.let { it as? BitmapDrawable }?.bitmap ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) { dominantColor(bitmap) }
    }
    return color
}

private fun dominantColor(bitmap: Bitmap): Color? {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val p = bitmap.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        // skip near-black and near-white, they say nothing about the cover's mood
        if (hsv[2] < 0.15f || (hsv[1] < 0.08f && hsv[2] > 0.9f)) continue
        val w = 0.05 + hsv[1] * hsv[1]
        r += android.graphics.Color.red(p) * w
        g += android.graphics.Color.green(p) * w
        b += android.graphics.Color.blue(p) * w
        total += w
    }
    if (total == 0.0) return null
    return Color((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
}
