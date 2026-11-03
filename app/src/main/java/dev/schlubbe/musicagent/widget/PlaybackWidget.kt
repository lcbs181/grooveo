package dev.schlubbe.musicagent.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.ColorFilter
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import dev.schlubbe.musicagent.MainActivity
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Canopy dark-theme tokens (see ui/theme/Color.kt's CanopyDark) - Glance widgets
// can't read a CompositionLocal from the app's process, so the values are
// duplicated here as plain constants. Pinned to the *dark* palette on purpose:
// a widget sits on the user's wallpaper, not on an app surface, so it doesn't
// follow the in-app light/dark choice.
//
// Dawn Music swaps in its "Sonnenaufgang am Meer" glass look: a light sunrise card
// (dawn/res/drawable/widget_card_gradient.xml) with navy text and the ocean-blue
// accent. The XML side of both palettes lives in res/values/widget_colors.xml.
private val DAWN = BuildConfig.DAWN
private val WIDGET_ACCENT = if (DAWN) Color(0xFF1665C8) else Color(0xFF4A8F76)
private val WIDGET_ACCENT2 = if (DAWN) Color(0xFFC93A78) else Color(0xFFFF7A5C)
private val WIDGET_BACKGROUND = if (DAWN) Color(0xFFE4F0FB) else Color(0xFF10201A)
private val WIDGET_SURFACE = if (DAWN) Color(0x660B1A2E) else Color(0xFF1B2E26)
private val WIDGET_TEXT_PRIMARY = if (DAWN) Color(0xFF0B1A2E) else Color.White
private val WIDGET_TEXT_SECONDARY = if (DAWN) Color(0xFF3B4A5D) else Color(0xFFAAAAAA)
private val WIDGET_TEXT_TERTIARY = if (DAWN) Color(0xFF526377) else Color(0xFF96A89D) // Canopy neutral500, dark theme
// Dawn: a bright glass rim instead of a dark hairline
private val WIDGET_DIVIDER = if (DAWN) Color(0xB3FFFFFF) else Color(0x33FFFFFF)
private val WIDGET_QUEUE_ROW_BG = if (DAWN) Color(0x66FFFFFF) else Color(0x14FFFFFF)
private val WIDGET_APP_NAME = if (DAWN) "Dawn Music" else "Grooveo"
private val WIDGET_COVER_LETTER = if (DAWN) "D" else "G"
private val WIDGET_COVER_LETTER_COLOR = if (DAWN) Color.White else Color(0xFF0D1F1A)

// Opaque, matching "Grooveo Widgets.dc.html"'s own var(--color-surface) card
// exactly. An earlier version of this file made the card translucent, but the
// design's radial-gradient backdrop is the mockup's own stand-in for a phone
// wallpaper *behind* the widget, not part of the card - the card itself has no
// transparency in the spec.
private val WIDGET_CARD_BG = if (DAWN) Color(0x99FFFFFF) else Color(0xFF1B2E26)

// Every one of the design's 5 tiers states the same `border-radius:24px` on its
// outer card. This file previously used 14dp (Canopy's --radius-md token for
// in-app surfaces), which reads as noticeably less rounded than the design.
private val WIDGET_CARD_RADIUS = 24.dp

// Default cover-art corner radius; every real call site below overrides this
// with the design's own per-tier value (12/14/20/22dp), since the design does
// not use one constant radius for the cover the way it does for the card.
private val WIDGET_ART_RADIUS = 8.dp

// design_handoff_grooveo/Grooveo Widgets.dc.html's own stated grid: "1 Zelle =
// 88 x 96 dp", with a 12dp gutter between cells - i.e. width(cols) = cols*100-12,
// height(rows) = rows*108-12 (verified against every one of the doc's own
// labeled dp values: 1x2=188x96, 2x2=188x204, 2x4=388x204, 2x5=488x204,
// 3x4=388x312, 3x7=688x312). This SUPERSEDES the older cols*70-30 formula the
// previous (Nocturne-era) 2-breakpoint widget used - Canopy's own grid is a
// different, larger cell size, not a tweak of the old one.
// Breakpoints are *minimums*: Responsive picks the largest one that fits. They sit
// below the design grid's nominal sizes because real launchers hand out smaller
// cells - a full-width 2-row widget on a 411dp-wide phone (launcher3, Pixel) is
// ~380 x 190dp, which never reached the old 388dp "Medium" tier and fell back to
// the 2x2 layout.
private val MINI_SIZE = DpSize(150.dp, 80.dp) // 1 row
private val COMPACT_SIZE = DpSize(150.dp, 170.dp) // 2 rows, narrow
private val MEDIUM_SIZE = DpSize(290.dp, 150.dp) // 2 rows, phone width
private val MEDIUM_WIDE_SIZE = DpSize(440.dp, 150.dp) // 2 rows, tablet width
private val LARGE_SIZE = DpSize(290.dp, 270.dp) // 3+ rows, phone width
private val XLARGE_SIZE = DpSize(600.dp, 270.dp) // 3+ rows, tablet/foldable

// Android's own accessibility guidance calls for a minimum 48dp touch target - the
// original version of this widget sized its clickable icons at 32-36dp with no
// extra hit-area padding, which is almost certainly why tapping them felt like it
// "didn't work": most taps landed just outside the actual clickable bounds.
private val CONTROL_TOUCH_TARGET = 48.dp

/** Home-screen widget mirroring whatever's currently loaded in [dev.schlubbe.musicagent.playback.PlayerController].
 * The composition started in [provideGlance] stays alive and recomposes on every
 * [PlaybackUiState] emission for as long as the widget's process is running - no
 * manual updateAll()/polling needed, unlike a plain RemoteViews implementation.
 * Six size breakpoints match design_handoff_grooveo/Grooveo Widgets.dc.html's own
 * five labeled tiers (2x4/2x5 share one layout, sized differently). */
class PlaybackWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(MINI_SIZE, COMPACT_SIZE, MEDIUM_SIZE, MEDIUM_WIDE_SIZE, LARGE_SIZE, XLARGE_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val playerController = widgetPlayerController(context)
        val likesRepository = widgetLikesRepository(context)
        // Preload the currently-playing track's artwork before the first
        // provideContent frame, per Glance's own recommended pattern (loading it
        // only reactively inside the composition means the very first render -
        // and any render before the LaunchedEffect's coroutine resolves - shows no
        // art at all). The reactive LaunchedEffect below still runs for every
        // later track change; this only fixes the initial paint.
        val initialState = playerController.playbackState.value
        val initialArtwork = initialState.artworkUrl?.let { loadArtwork(context, it) }
        provideContent {
            val state by playerController.playbackState.collectAsState()
            val likedTrackIds by likesRepository.likedTrackIds.collectAsState()
            var artwork by remember { mutableStateOf(initialArtwork) }

            LaunchedEffect(state.artworkUrl) {
                if (state.artworkUrl != initialState.artworkUrl) {
                    artwork = state.artworkUrl?.let { loadArtwork(context, it) }
                }
            }

            // Glance has no live position ticker of its own (unlike the in-app
            // Player's 1s polling coroutine) - PlaybackUiState only carries
            // duration, not a continuously updating position. This reads the
            // position once per recomposition (play/pause/track-change), which
            // updates the bar at those points rather than every second, but is
            // still meaningfully "where you left off" rather than a fake number.
            val progress = if (state.durationMs > 0) {
                (playerController.currentPositionMs().toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val isLiked = state.currentTrackId != null && state.currentTrackId in likedTrackIds
            val source = state.currentTrackId?.substringBefore(":")

            when (LocalSize.current) {
                MINI_SIZE -> MiniWidgetContent(state, artwork)
                COMPACT_SIZE -> CompactWidgetContent(state, artwork)
                MEDIUM_SIZE -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = false)
                MEDIUM_WIDE_SIZE -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = true)
                LARGE_SIZE -> LargeWidgetContent(state, artwork, progress, isLiked, source)
                XLARGE_SIZE -> XLargeWidgetContent(state, artwork, progress, isLiked, source)
                else -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = false)
            }
        }
    }
}

private const val WIDGET_ARTWORK_PX = 512

private suspend fun loadArtwork(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Glance's ImageProvider needs a plain software bitmap - a hardware
            // bitmap (Coil/Android's default for decoded images) can't be shared
            // with the widget host process this way.
            .allowHardware(false)
            // SizeMode.Responsive sends one layout per size tier, each carrying its own
            // copy of the cover - at full size (YouTube covers decode to ~960px, 3.6 MB)
            // the six copies broke the host's RemoteViews bitmap budget (~15 MB on a
            // 1080p screen) and the widget showed "Can't show content". 512px (1 MB, six
            // copies ~6 MB) stays sharp for the 3x4 banner and the 3x7 tier's 284dp cover.
            .size(WIDGET_ARTWORK_PX)
            .build()
        (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }.getOrNull()
}

/** Wraps [content] in the widget's card: a dark green-to-near-black gradient
 * fill, `border-radius:24px`, and a `1px solid var(--color-divider)` outline.
 * Glance 1.1.1 (this project's pinned version) has no `GlanceModifier.border()`
 * - that only shipped in a later release - so the outline is simulated the
 * standard RemoteViews way: an outer box painted the divider color, holding an
 * inner box inset by 1dp, leaving exactly a 1dp ring visible. `GlanceModifier
 * .background()` only accepts a solid color, not a gradient, so the fill itself
 * is a plain `Image` of [R.drawable.widget_card_gradient] instead, clipped to
 * the same rounded corners and stacked underneath [content]. */
@Composable
private fun WidgetCard(content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_DIVIDER)
            .cornerRadius(WIDGET_CARD_RADIUS),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(1.dp)
                .cornerRadius(WIDGET_CARD_RADIUS),
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_card_gradient),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            content()
        }
    }
}

// Layout rules for every tier (reworked after real-device feedback: half-empty
// cards, tiny controls, a stamp-sized cover and decorative bars that said nothing):
// - the cover is the main element and takes whatever room is left (defaultWeight),
//   so a card never shows an empty band whatever cell size the launcher gives it;
// - controls are big and spread evenly across the full width;
// - no decorative equalizer bars.

/** 1x2: cover filling the card's height, title/artist, play/pause. */
@Composable
private fun MiniWidgetContent(state: PlaybackUiState, artwork: Bitmap?) {
    WidgetCard {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            ArtBox(artwork, GlanceModifier.width(64.dp).fillMaxHeight(), cornerRadius = 14.dp)
            Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 10.dp).clickable(openAppAction())) {
                TitleText(state, 14.sp, maxLines = 2)
                ArtistText(state, 12.sp)
            }
            AccentPlayPauseButton(isPlaying = state.isPlaying, size = 48.dp, iconSize = 22.dp)
        }
    }
}

/** 2x2: cover on top filling the free height, one title line, big transport row. */
@Composable
private fun CompactWidgetContent(state: PlaybackUiState, artwork: Bitmap?) {
    WidgetCard {
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            ArtBox(artwork, GlanceModifier.fillMaxWidth().defaultWeight(), cornerRadius = 16.dp)
            Spacer(modifier = GlanceModifier.height(6.dp))
            TitleText(state, 13.sp, maxLines = 1)
            Spacer(modifier = GlanceModifier.height(2.dp))
            BigTransport(state, isLiked = false, playSize = 48.dp, sideIcon = 22.dp, withExtras = false)
        }
    }
}

/** 2x4 / 2x5: square cover over the full height on the left; title, progress and
 * transport on the right. [wide] has room for shuffle and like in the transport. */
@Composable
private fun MediumWidgetContent(
    state: PlaybackUiState,
    artwork: Bitmap?,
    progress: Float,
    isLiked: Boolean,
    source: String?,
    wide: Boolean,
) {
    WidgetCard {
        Row(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            ArtBox(artwork, GlanceModifier.width(150.dp).fillMaxHeight(), cornerRadius = 20.dp)
            Spacer(modifier = GlanceModifier.width(14.dp))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Row(verticalAlignment = Alignment.Vertical.Top, modifier = GlanceModifier.fillMaxWidth()) {
                    Column(modifier = GlanceModifier.defaultWeight().clickable(openAppAction())) {
                        SourceText(source)
                        TitleText(state, 16.sp, maxLines = 2)
                        ArtistText(state, 13.sp)
                    }
                    if (!wide) LikeButton(isLiked)
                }
                Spacer(modifier = GlanceModifier.defaultWeight())
                ProgressWithTimes(state, progress)
                Spacer(modifier = GlanceModifier.height(4.dp))
                BigTransport(state, isLiked, playSize = 52.dp, sideIcon = 24.dp, withExtras = wide)
            }
        }
    }
}

/** 3x4: wide cover banner over the free height, then title + like, progress and
 * a full transport row with shuffle and like. */
@Composable
private fun LargeWidgetContent(state: PlaybackUiState, artwork: Bitmap?, progress: Float, isLiked: Boolean, source: String?) {
    WidgetCard {
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            ArtBox(artwork, GlanceModifier.fillMaxWidth().defaultWeight(), cornerRadius = 20.dp)
            Spacer(modifier = GlanceModifier.height(10.dp))
            Column(modifier = GlanceModifier.fillMaxWidth().clickable(openAppAction())) {
                TitleText(state, 16.sp, maxLines = 1)
                ArtistText(state, 13.sp, suffix = sourceSuffix(source))
            }
            Spacer(modifier = GlanceModifier.height(8.dp))
            ProgressWithTimes(state, progress)
            Spacer(modifier = GlanceModifier.height(2.dp))
            BigTransport(state, isLiked, playSize = 56.dp, sideIcon = 26.dp, withExtras = true)
        }
    }
}

/** 3x7 (tablet/foldable): big square cover on the left; title, the next two
 * tracks, progress and the full transport on the right. */
@Composable
private fun XLargeWidgetContent(state: PlaybackUiState, artwork: Bitmap?, progress: Float, isLiked: Boolean, source: String?) {
    WidgetCard {
        Row(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            ArtBox(artwork, GlanceModifier.width(250.dp).fillMaxHeight(), cornerRadius = 22.dp)
            Spacer(modifier = GlanceModifier.width(18.dp))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Column(modifier = GlanceModifier.fillMaxWidth().clickable(openAppAction())) {
                    SourceText(source)
                    TitleText(state, 20.sp, maxLines = 1)
                    ArtistText(state, 14.sp)
                }
                Spacer(modifier = GlanceModifier.height(10.dp))
                QueueRows(state, count = 2)
                Spacer(modifier = GlanceModifier.defaultWeight())
                ProgressWithTimes(state, progress)
                Spacer(modifier = GlanceModifier.height(4.dp))
                BigTransport(state, isLiked, playSize = 56.dp, sideIcon = 26.dp, withExtras = true)
            }
        }
    }
}

@Composable
private fun openAppAction(): Action =
    actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))

@Composable
private fun TitleText(state: PlaybackUiState, size: androidx.compose.ui.unit.TextUnit, maxLines: Int) {
    Text(
        text = state.title ?: "Nichts wird abgespielt",
        maxLines = maxLines,
        style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = size, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun ArtistText(state: PlaybackUiState, size: androidx.compose.ui.unit.TextUnit, suffix: String? = null) {
    val artist = state.artist ?: WIDGET_APP_NAME
    Text(
        text = if (suffix != null && state.artist != null) "$artist · $suffix" else artist,
        maxLines = 1,
        style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = size),
    )
}

@Composable
private fun SourceText(source: String?) {
    Text(
        text = sourceLabel(source).uppercase(),
        maxLines = 1,
        style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 10.sp, fontWeight = FontWeight.Bold),
    )
}

private fun sourceSuffix(source: String?): String? = when (source) {
    "soundcloud" -> "SoundCloud"
    "ytmusic" -> "YouTube Music"
    else -> null
}

/** Progress bar over the full available width, elapsed/total underneath. */
@Composable
private fun ProgressWithTimes(state: PlaybackUiState, progress: Float) {
    LinearProgressIndicator(
        progress = progress,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = ColorProvider(WIDGET_ACCENT),
        backgroundColor = ColorProvider(WIDGET_SURFACE),
    )
    Spacer(modifier = GlanceModifier.height(3.dp))
    TimeRow(state, progress)
}

/** Big, evenly spread transport: [shuffle] prev (play) next [like] - the outer two
 * only with [withExtras]. Every control keeps a 48dp touch target. */
@Composable
private fun BigTransport(state: PlaybackUiState, isLiked: Boolean, playSize: Dp, sideIcon: Dp, withExtras: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (withExtras) {
            WidgetControlButton(
                R.drawable.ic_widget_shuffle,
                "Zufallswiedergabe",
                actionRunCallback<ToggleShuffleAction>(),
                iconSize = sideIcon - 4.dp,
                tint = if (state.shuffleEnabled) WIDGET_ACCENT else null,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
        } else {
            Spacer(modifier = GlanceModifier.defaultWeight())
        }
        WidgetControlButton(R.drawable.ic_widget_skip_previous, "Vorheriger Titel", actionRunCallback<SkipPreviousAction>(), iconSize = sideIcon)
        Spacer(modifier = GlanceModifier.defaultWeight())
        AccentPlayPauseButton(isPlaying = state.isPlaying, size = playSize, iconSize = playSize * 0.42f)
        Spacer(modifier = GlanceModifier.defaultWeight())
        WidgetControlButton(R.drawable.ic_widget_skip_next, "Nächster Titel", actionRunCallback<SkipNextAction>(), iconSize = sideIcon)
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (withExtras) LikeButton(isLiked, touchTarget = CONTROL_TOUCH_TARGET, iconSize = sideIcon - 4.dp)
    }
}

@Composable
private fun QueueRows(state: PlaybackUiState, count: Int) {
    val upcoming = state.queue.withIndex()
        .drop(if (state.queueIndex >= 0) state.queueIndex + 1 else 0)
        .take(count)
    Column {
        upcoming.forEachIndexed { rowIndex, indexedTrack ->
            QueueRow(indexedTrack)
            if (rowIndex != upcoming.lastIndex) Spacer(modifier = GlanceModifier.height(6.dp))
        }
    }
}

@Composable
private fun QueueRow(indexedTrack: kotlin.collections.IndexedValue<TrackResultDto>) {
    val (index, track) = indexedTrack
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WIDGET_QUEUE_ROW_BG)
            .cornerRadius(14.dp)
            .padding(8.dp)
            .clickable(actionRunCallback<PlayQueueIndexAction>(actionParametersOf(QUEUE_INDEX_KEY to index))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(34.dp).cornerRadius(10.dp)) {
            Image(
                provider = ImageProvider(R.drawable.widget_cover_gradient),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 10.dp)) {
            Text(track.title, maxLines = 1, style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 12.5.sp))
            Text(track.artist ?: "", maxLines = 1, style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = 11.sp))
        }
        durationLabel(track.durationSec)?.let {
            Text(it, style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 11.sp))
        }
    }
}

@Composable
private fun LikeButton(isLiked: Boolean, touchTarget: Dp = 40.dp, iconSize: Dp = 20.dp) {
    Box(
        modifier = GlanceModifier.size(touchTarget).clickable(actionRunCallback<ToggleLikeAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart_outline),
            contentDescription = if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir",
            modifier = GlanceModifier.size(iconSize),
        )
    }
}

@Composable
private fun TimeRow(state: PlaybackUiState, progress: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.Start) {
        Text(elapsedLabel(state, progress), style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 11.sp))
        Spacer(modifier = GlanceModifier.defaultWeight())
        Text(totalLabel(state), style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 11.sp))
    }
}

private fun sourceLabel(source: String?, offline: Boolean = false): String = when {
    offline -> "Offline"
    source == "soundcloud" -> "SoundCloud"
    source == "ytmusic" -> "YouTube Music"
    else -> WIDGET_APP_NAME
}

private fun mmss(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun elapsedLabel(state: PlaybackUiState, progress: Float): String =
    mmss(((state.durationMs / 1000L) * progress).toInt())

private fun totalLabel(state: PlaybackUiState): String = mmss((state.durationMs / 1000L).toInt())

private fun durationLabel(durationSec: Int?): String? = durationSec?.let { mmss(it) }

@Composable
private fun ArtBox(artwork: Bitmap?, modifier: GlanceModifier, cornerRadius: Dp = WIDGET_ART_RADIUS) {
    Box(
        modifier = modifier
            .cornerRadius(cornerRadius)
            .clickable(openAppAction()),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CoverPlaceholder()
        }
    }
}

/** The design's own brand-mark placeholder - `linear-gradient(135deg,#4a8f76,
 * #ff7a5c)` with a bold "G" - shown only when a track has no artwork or it
 * failed to load, so the fallback still looks intentional instead of a bare
 * system icon. Real per-track artwork (loaded via Coil in [loadArtwork]) is
 * shown whenever available, matching how the rest of the app (Player, mini
 * player, search) always shows real covers. */
@Composable
private fun CoverPlaceholder() {
    Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(R.drawable.widget_cover_gradient),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Text(
            WIDGET_COVER_LETTER,
            style = TextStyle(
                color = ColorProvider(WIDGET_COVER_LETTER_COLOR),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** A transport icon with its clickable bounds padded out beyond the visible glyph
 * size - defaults to [CONTROL_TOUCH_TARGET], with call sites in the denser tiers
 * passing a smaller [touchTarget]/[iconSize] so every control still fits on one
 * row without shrinking below what each tier has room for. */
@Composable
private fun WidgetControlButton(
    icon: Int,
    contentDescription: String,
    action: Action,
    touchTarget: Dp = CONTROL_TOUCH_TARGET,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
) {
    Box(
        modifier = GlanceModifier.size(touchTarget).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(iconSize),
            colorFilter = tint?.let { ColorFilter.tint(ColorProvider(it)) },
        )
    }
}

/** The design's primary transport control across every tier: a solid
 * accent-green circle (`background:var(--color-accent)`) with a dark play/pause
 * glyph (`color:#0d1f1a`) - composed here as a Box + bare glyph rather than the
 * old single "white-on-purple filled circle" icon resource, which baked in a
 * stale Nocturne accent color that no longer matches Canopy's accent-background/
 * dark-icon convention (see ic_widget_play_glyph.xml's own comment). */
@Composable
private fun AccentPlayPauseButton(isPlaying: Boolean, size: Dp, iconSize: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .background(WIDGET_ACCENT)
            .clickable(actionRunCallback<TogglePlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_widget_pause_glyph else R.drawable.ic_widget_play_glyph),
            contentDescription = if (isPlaying) "Pause" else "Wiedergabe",
            modifier = GlanceModifier.size(iconSize),
        )
    }
}
