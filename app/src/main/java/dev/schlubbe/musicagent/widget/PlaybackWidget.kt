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
import androidx.glance.appwidget.GlanceAppWidget
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
import coil.ImageLoader
import coil.request.ImageRequest
import dev.schlubbe.musicagent.MainActivity
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
private val WIDGET_ACCENT = Color(0xFF4A8F76)
private val WIDGET_ACCENT2 = Color(0xFFFF7A5C)
private val WIDGET_BACKGROUND = Color(0xFF10201A)
private val WIDGET_SURFACE = Color(0xFF1B2E26)
private val WIDGET_TEXT_PRIMARY = Color.White
private val WIDGET_TEXT_SECONDARY = Color(0xFFAAAAAA)
private val WIDGET_TEXT_TERTIARY = Color(0xFF96A89D) // Canopy neutral500, dark theme
private val WIDGET_DIVIDER = Color(0x33FFFFFF)
private val WIDGET_QUEUE_ROW_BG = Color(0x14FFFFFF)

// Opaque, matching "Grooveo Widgets.dc.html"'s own var(--color-surface) card
// exactly. An earlier version of this file made the card translucent, but the
// design's radial-gradient backdrop is the mockup's own stand-in for a phone
// wallpaper *behind* the widget, not part of the card - the card itself has no
// transparency in the spec.
private val WIDGET_CARD_BG = Color(0xFF1B2E26)

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
private val MINI_SIZE = DpSize(188.dp, 96.dp) // 1 row x 2 cols
private val COMPACT_SIZE = DpSize(188.dp, 204.dp) // 2 rows x 2 cols
private val MEDIUM_SIZE = DpSize(388.dp, 204.dp) // 2 rows x 4 cols
private val MEDIUM_WIDE_SIZE = DpSize(488.dp, 204.dp) // 2 rows x 5 cols
private val LARGE_SIZE = DpSize(388.dp, 312.dp) // 3 rows x 4 cols
private val XLARGE_SIZE = DpSize(688.dp, 312.dp) // 3 rows x 7 cols (tablet/foldable)

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
                COMPACT_SIZE -> CompactWidgetContent(state, artwork, source)
                MEDIUM_SIZE -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = false)
                MEDIUM_WIDE_SIZE -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = true)
                LARGE_SIZE -> LargeWidgetContent(state, artwork, progress, isLiked, source)
                XLARGE_SIZE -> XLargeWidgetContent(state, artwork, progress, isLiked, source)
                else -> MediumWidgetContent(state, artwork, progress, isLiked, source, wide = false)
            }
        }
    }
}

private suspend fun loadArtwork(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Glance's ImageProvider needs a plain software bitmap - a hardware
            // bitmap (Coil/Android's default for decoded images) can't be shared
            // with the widget host process this way.
            .allowHardware(false)
            .build()
        (ImageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
    }.getOrNull()
}

// A fixed "equalizer bars" silhouette, the same for every track. Glance/
// RemoteViews has no continuous-animation capability (a home-screen widget is a
// static snapshot, repainted only on real state changes, not a live render
// loop), so the design's `wgPulse` CSS keyframe can't be reproduced as actual
// motion without polling the widget many times a second, which would be a real
// battery cost for a purely decorative flourish. An earlier version derived the
// bar heights from a per-track hash so the shape "looked different per track",
// but a static decoration that changes per track just reads as visually
// unstable rather than alive - one deliberately-shaped, constant pattern reads
// as intentional instead.
private val EQUALIZER_STATIC_PATTERN = listOf(0.42f, 0.68f, 0.55f, 0.88f, 1f, 0.9f, 0.6f, 0.75f, 0.5f, 0.35f)

private fun equalizerBars(isPlaying: Boolean, count: Int): List<Float> {
    if (!isPlaying) return List(count) { 0.22f }
    return List(count) { i -> EQUALIZER_STATIC_PATTERN[i % EQUALIZER_STATIC_PATTERN.size] }
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

/** 1x2 "Mini": cover, title/artist, a single play/pause tap target - the
 * smallest tier, no transport or progress (matches the design's own "nur Cover,
 * Titel, Play" spec). */
@Composable
private fun MiniWidgetContent(state: PlaybackUiState, artwork: Bitmap?) {
    WidgetCard {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            val context = LocalContext.current
            val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

            AlbumArt(artwork, onClick = openApp, size = 40.dp, cornerRadius = 12.dp)
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(horizontal = 10.dp)
                    .clickable(openApp),
            ) {
                Text(
                    text = state.title ?: "Nichts wird abgespielt",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = state.artist ?: "Grooveo",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = 12.sp),
                )
            }
            AccentPlayPauseButton(isPlaying = state.isPlaying, size = 32.dp, iconSize = 16.dp)
        }
    }
}

/** 2x2 "Kompakt": header (cover, source label, title), a static equalizer-bar
 * strip, and a full transport row - no progress bar (not enough vertical room
 * once the bars are shown, matching the design's own layout). The design lays
 * out its 3 direct children (header / bars / transport) with a uniform 12dp
 * column gap - not a flexible spacer pushing content to the bottom, which an
 * earlier version of this file used and which left a large, wrong-looking empty
 * gap under the header on real devices. */
@Composable
private fun CompactWidgetContent(state: PlaybackUiState, artwork: Bitmap?, source: String?) {
    WidgetCard {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            val context = LocalContext.current
            val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

            Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = GlanceModifier.clickable(openApp)) {
                AlbumArt(artwork, onClick = openApp, size = 44.dp, cornerRadius = 14.dp)
                Column(modifier = GlanceModifier.defaultWeight().padding(start = 10.dp)) {
                    Text(
                        text = sourceLabel(source),
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = state.title ?: "Nichts wird abgespielt",
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            Spacer(modifier = GlanceModifier.height(12.dp))
            EqualizerBarsRow(isPlaying = state.isPlaying, height = 34.dp, barCount = EQUALIZER_BAR_COUNT)
            Spacer(modifier = GlanceModifier.height(12.dp))
            TransportRow(
                hasPrev = true,
                hasNext = true,
                isPlaying = state.isPlaying,
                playSize = 48.dp,
                playIconSize = 20.dp,
                sideTouchTarget = 34.dp,
                sideIconSize = 15.dp,
                trailing = null,
            )
        }
    }
}

/** 2x4 / 2x5 "Now Playing": a bigger cover, title/artist, real progress bar with
 * elapsed/total, and a transport row - [wide] adds shuffle and a source-toggle
 * chip (the design's own distinction between its 388dp and 488dp variants). */
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
        Row(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
            val context = LocalContext.current
            val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

            AlbumArt(artwork, onClick = openApp, size = 100.dp, cornerRadius = 20.dp)
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 16.dp)) {
                Text(
                    text = sourceLabel(source),
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = state.title ?: "Nichts wird abgespielt",
                    maxLines = 1,
                    modifier = GlanceModifier.padding(top = 4.dp).clickable(openApp),
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = state.artist ?: "Grooveo",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = 12.sp),
                )
                Spacer(modifier = GlanceModifier.height(10.dp))
                // Fixed per-breakpoint width: SizeMode.Responsive reports LocalSize.current
                // as exactly one of the declared DpSize buckets, so the space actually
                // available here (widget width, minus the card's 16dp+16dp padding, the
                // 100dp cover, and its 16dp leading gap) is a known constant per bucket
                // rather than something to measure at runtime.
                WidgetProgressBar(progress, width = if (wide) 340.dp else 240.dp)
                TimeRow(state, progress)
                Spacer(modifier = GlanceModifier.defaultWeight())
                TransportRow(
                    hasPrev = true,
                    hasNext = true,
                    isPlaying = state.isPlaying,
                    playSize = 42.dp,
                    playIconSize = 18.dp,
                    leading = if (wide) {
                        { WidgetControlButton(R.drawable.ic_widget_shuffle, "Zufallswiedergabe", actionRunCallback<ToggleShuffleAction>(), touchTarget = 34.dp, iconSize = 15.dp) }
                    } else {
                        null
                    },
                    trailing = {
                        LikeButton(isLiked)
                        if (wide) {
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            SourceChip(sourceLabel(source, offline = state.isLocalPlayback))
                        }
                    },
                )
            }
        }
    }
}

/** 3x4 "Player + Warteschlange": header row (cover, source/title/artist with an
 * inline progress bar), a transport row with an elapsed/total label and like
 * button, then up to 2 "up next" rows, each directly tappable. */
@Composable
private fun LargeWidgetContent(state: PlaybackUiState, artwork: Bitmap?, progress: Float, isLiked: Boolean, source: String?) {
    WidgetCard {
        Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
            val context = LocalContext.current
            val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

            Row {
                AlbumArt(artwork, onClick = openApp, size = 88.dp, cornerRadius = 20.dp)
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 14.dp)) {
                    Text(
                        text = sourceLabel(source),
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = state.title ?: "Nichts wird abgespielt",
                        maxLines = 1,
                        modifier = GlanceModifier.padding(top = 4.dp).clickable(openApp),
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = state.artist ?: "Grooveo",
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = 12.sp),
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    // LARGE_SIZE (388dp) minus the card's 16+16dp padding, the 88dp
                    // cover, and its 14dp leading gap.
                    WidgetProgressBar(progress, width = 254.dp)
                }
            }
            Spacer(modifier = GlanceModifier.height(12.dp))
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                WidgetControlButton(R.drawable.ic_widget_skip_previous, "Vorheriger Titel", actionRunCallback<SkipPreviousAction>(), touchTarget = 34.dp, iconSize = 16.dp)
                Spacer(modifier = GlanceModifier.width(6.dp))
                AccentPlayPauseButton(isPlaying = state.isPlaying, size = 42.dp, iconSize = 18.dp)
                Spacer(modifier = GlanceModifier.width(6.dp))
                WidgetControlButton(R.drawable.ic_widget_skip_next, "Nächster Titel", actionRunCallback<SkipNextAction>(), touchTarget = 34.dp, iconSize = 16.dp)
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = timeLabel(state, progress),
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 11.sp),
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                LikeButton(isLiked)
            }
            Spacer(modifier = GlanceModifier.height(12.dp))
            Text(
                text = "Als Nächstes",
                style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            QueueRows(state, count = 2)
        }
    }
}

/** 3x7 "Tablet / Foldable": the large tier's player (with shuffle added) on the
 * left, a vertical divider, and a longer "Warteschlange" list plus a
 * source-toggle chip on the right - matches the widest declared bucket, for
 * launchers with enough grid width to offer it. */
@Composable
private fun XLargeWidgetContent(state: PlaybackUiState, artwork: Bitmap?, progress: Float, isLiked: Boolean, source: String?) {
    WidgetCard {
        Row(modifier = GlanceModifier.fillMaxSize().padding(18.dp)) {
            val context = LocalContext.current
            val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

            // The design states this left column as a fixed 288dp (not the full
            // 688dp widget width - the right half holds the queue list).
            Column(modifier = GlanceModifier.width(288.dp).fillMaxHeight()) {
                Row {
                    AlbumArt(artwork, onClick = openApp, size = 96.dp, cornerRadius = 22.dp)
                    Column(modifier = GlanceModifier.defaultWeight().padding(start = 14.dp)) {
                        Text(
                            text = sourceLabel(source),
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = state.title ?: "Nichts wird abgespielt",
                            maxLines = 2,
                            modifier = GlanceModifier.padding(top = 4.dp).clickable(openApp),
                            style = TextStyle(color = ColorProvider(WIDGET_TEXT_PRIMARY), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = state.artist ?: "Grooveo",
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(WIDGET_TEXT_SECONDARY), fontSize = 12.sp),
                        )
                    }
                }
                Spacer(modifier = GlanceModifier.height(16.dp))
                EqualizerBarsRow(isPlaying = state.isPlaying, height = 40.dp, barCount = EQUALIZER_BAR_COUNT)
                Spacer(modifier = GlanceModifier.height(14.dp))
                // 288dp left column minus its own 96dp cover and 14dp leading gap.
                WidgetProgressBar(progress, width = 178.dp)
                TimeRow(state, progress)
                Spacer(modifier = GlanceModifier.defaultWeight())
                TransportRow(
                    hasPrev = true,
                    hasNext = true,
                    isPlaying = state.isPlaying,
                    playSize = 46.dp,
                    playIconSize = 20.dp,
                    leading = { WidgetControlButton(R.drawable.ic_widget_shuffle, "Zufallswiedergabe", actionRunCallback<ToggleShuffleAction>(), touchTarget = 34.dp, iconSize = 15.dp) },
                    trailing = { LikeButton(isLiked) },
                )
            }

            Box(modifier = GlanceModifier.width(1.dp).fillMaxHeight().padding(horizontal = 18.dp).background(WIDGET_DIVIDER)) {}

            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Text(
                        text = "Warteschlange",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    )
                    SourceChip(sourceLabel(source, offline = state.isLocalPlayback))
                }
                Spacer(modifier = GlanceModifier.height(8.dp))
                QueueRows(state, count = 4)
            }
        }
    }
}

// ---- Shared pieces ----

// RemoteViews - and therefore every Glance Row/Column, which just compiles down
// to a LinearLayout - hard-caps a single container at 10 direct children. The
// design's own "wgPulse" bar strip calls for 14 bars; at 14 this widget silently
// rendered only a handful of merged, chunky-looking segments instead of throwing,
// which is what actually caused the broken-looking equalizer strip on-device.
// 10 is the most bars this container can hold at all.
private const val EQUALIZER_BAR_COUNT = 10

@Composable
private fun EqualizerBarsRow(isPlaying: Boolean, height: Dp, barCount: Int) {
    val bars = equalizerBars(isPlaying, barCount)
    Row(modifier = GlanceModifier.fillMaxWidth().height(height), verticalAlignment = Alignment.Vertical.Bottom) {
        bars.forEachIndexed { i, h ->
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .padding(end = if (i != bars.lastIndex) 3.dp else 0.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(height * h)
                        .background(WIDGET_ACCENT)
                        .cornerRadius(2.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun TransportRow(
    hasPrev: Boolean,
    hasNext: Boolean,
    isPlaying: Boolean,
    playSize: Dp,
    playIconSize: Dp,
    sideTouchTarget: Dp = 34.dp,
    sideIconSize: Dp = 16.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        leading?.let { it(); Spacer(modifier = GlanceModifier.width(4.dp)) }
        if (hasPrev) {
            WidgetControlButton(R.drawable.ic_widget_skip_previous, "Vorheriger Titel", actionRunCallback<SkipPreviousAction>(), touchTarget = sideTouchTarget, iconSize = sideIconSize)
        }
        AccentPlayPauseButton(isPlaying = isPlaying, size = playSize, iconSize = playIconSize)
        if (hasNext) {
            WidgetControlButton(R.drawable.ic_widget_skip_next, "Nächster Titel", actionRunCallback<SkipNextAction>(), touchTarget = sideTouchTarget, iconSize = sideIconSize)
        }
        if (trailing != null) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            trailing()
        }
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
private fun LikeButton(isLiked: Boolean) {
    Box(
        modifier = GlanceModifier.size(34.dp).clickable(actionRunCallback<ToggleLikeAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart_outline),
            contentDescription = if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir",
            modifier = GlanceModifier.size(17.dp),
        )
    }
}

/** The design specifies `background:transparent;border:1px solid
 * var(--color-divider)` - an outlined pill, not a filled one. Simulated the same
 * 1dp-ring way as [WidgetCard] since Glance 1.1.1 has no `.border()`. */
@Composable
private fun SourceChip(label: String) {
    Box(
        modifier = GlanceModifier.background(WIDGET_DIVIDER).cornerRadius(999.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .padding(1.dp)
                .background(WIDGET_CARD_BG)
                .cornerRadius(999.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp)
                .clickable(actionRunCallback<ToggleSourceAction>()),
        ) {
            Text(label, style = TextStyle(color = ColorProvider(WIDGET_TEXT_TERTIARY), fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
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
    else -> "Grooveo"
}

private fun mmss(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun elapsedLabel(state: PlaybackUiState, progress: Float): String =
    mmss(((state.durationMs / 1000L) * progress).toInt())

private fun totalLabel(state: PlaybackUiState): String = mmss((state.durationMs / 1000L).toInt())

private fun timeLabel(state: PlaybackUiState, progress: Float): String =
    "${elapsedLabel(state, progress)} / ${totalLabel(state)}"

private fun durationLabel(durationSec: Int?): String? = durationSec?.let { mmss(it) }

/** A thin, non-seekable progress indicator reflecting [progress] (0f-1f) - see
 * the caller for why this only updates on play/pause/track-change rather than
 * ticking every second. */
@Composable
private fun WidgetProgressBar(progress: Float, width: Dp) {
    val playedWidth = width * progress.coerceIn(0f, 1f)
    Box(
        modifier = GlanceModifier
            .width(width)
            .height(4.dp)
            .background(WIDGET_SURFACE)
            .cornerRadius(2.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .width(playedWidth)
                .height(4.dp)
                .background(WIDGET_ACCENT)
                .cornerRadius(2.dp),
        ) {}
    }
}

@Composable
private fun AlbumArt(artwork: Bitmap?, onClick: Action, size: Dp = 52.dp, cornerRadius: Dp = WIDGET_ART_RADIUS) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(cornerRadius)
            .clickable(onClick),
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
            CoverPlaceholder(size)
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
private fun CoverPlaceholder(size: Dp) {
    Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(R.drawable.widget_cover_gradient),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Text(
            "G",
            style = TextStyle(
                color = ColorProvider(Color(0xFF0D1F1A)),
                fontSize = (size.value * 0.34f).sp,
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
) {
    Box(
        modifier = GlanceModifier.size(touchTarget).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(iconSize),
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
