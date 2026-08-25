package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

/** Canopy `TrackThumbnail`: a radius-md rounded cover used for every track row,
 * tile and shelf card.
 *
 * When there's no artwork this draws the generated Canopy cover rather than an
 * empty music-note tile -- the handoff calls that generator production intent,
 * not a mockup placeholder. [seed] is what the tile is derived from (normally
 * the track title), so the same track keeps a stable cover everywhere. Callers
 * that genuinely have nothing to seed with fall back to a plain surface block. */
@Composable
fun TrackThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    seed: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CanopyShapes.medium)
            .background(Canopy.neutral200),
        contentAlignment = Alignment.Center,
    ) {
        // The generated cover is the fallback for a *failed* load as well as for a
        // missing url. Previously only `url == null` fell back, so a url that 404s -
        // which happens routinely, since SoundCloud artwork is rewritten to a
        // -t500x500 rendition that not every upload has - drew nothing at all and
        // looked like the artwork simply never loaded.
        val fallbackSeed = seed
        when {
            url != null -> SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                error = {
                    if (fallbackSeed != null) {
                        GeneratedArtwork(seed = fallbackSeed, modifier = Modifier.fillMaxSize())
                    }
                },
            )
            fallbackSeed != null -> GeneratedArtwork(seed = fallbackSeed, modifier = Modifier.fillMaxSize())
        }
    }
}
