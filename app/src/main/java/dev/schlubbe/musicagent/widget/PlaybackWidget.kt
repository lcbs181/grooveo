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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import dev.schlubbe.musicagent.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Nocturne design tokens (see ui/theme/Color.kt's Nocturne object) - Glance
// widgets can't reference Compose's MaterialTheme, so the same hex values are
// duplicated here as plain Color constants.
private val WIDGET_ACCENT = Color(0xFF9184D9)
private val WIDGET_BACKGROUND = Color(0xFF161826)
private val WIDGET_SURFACE = Color(0xFF232532)

// The design handoff's widget card (Music Agent Widget.dc.html) uses
// `background: color-mix(in srgb, var(--color-surface) 88%, transparent)` -
// a translucent surface-toned card, not the flat opaque --color-bg the
// widget was using before. 0xE0 alpha is 88% of 0xFF.
private val WIDGET_CARD_BG = Color(0xE0232532)

// --radius-lg from nocturne-tokens.css (14px), the card's own corner radius
// in the handoff. --radius-sm (4px) is AlbumArt's.
private val WIDGET_CARD_RADIUS = 14.dp
private val WIDGET_ART_RADIUS = 4.dp

// Grid-cell size formula this project already established for the widget host's
// own sizing quirks (see round-5 notes: minHeight per row is (rows*70)-30dp,
// width follows the same per-column formula) - used here to pick the two
// breakpoints SizeMode.Responsive renders between.
private val COMPACT_SIZE = DpSize(110.dp, 40.dp) // 2 cols x 1 row
private val LARGE_SIZE = DpSize(250.dp, 110.dp) // 4 cols x 2 rows

// Android's own accessibility guidance calls for a minimum 48dp touch target - the
// original version of this widget sized its clickable icons at 32-36dp with no
// extra hit-area padding, which is almost certainly why tapping them felt like it
// "didn't work": most taps landed just outside the actual clickable bounds.
private val CONTROL_TOUCH_TARGET = 48.dp

/** Home-screen widget mirroring whatever's currently loaded in [dev.schlubbe.musicagent.playback.PlayerController].
 * The composition started in [provideGlance] stays alive and recomposes on every
 * [PlaybackUiState] emission for as long as the widget's process is running - no
 * manual updateAll()/polling needed, unlike a plain RemoteViews implementation.
 * Visual design deliberately echoes SoundCloud's own app (dark surface, its
 * signature orange reserved for the one primary action) since that's the reference
 * the user asked to match. */
class PlaybackWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, LARGE_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val playerController = widgetPlayerController(context)
        provideContent {
            val state by playerController.playbackState.collectAsState()
            var artwork by remember { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(state.artworkUrl) {
                artwork = state.artworkUrl?.let { loadArtwork(context, it) }
            }

            val size = LocalSize.current
            if (size.width < LARGE_SIZE.width) {
                CompactWidgetContent(state, artwork)
            } else {
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
                LargeWidgetContent(state, artwork, progress)
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

/** The 4x2 large variant: cover, title/artist, then a *separate* full-width
 * transport row (shuffle/skip-back/play-pause/skip-forward/repeat) plus a thin
 * progress bar - matching the design spec's two-row layout (header row, then a
 * `justify-content:space-between` control row) rather than cramming every
 * control into the header row alongside the art and title. */
@Composable
private fun LargeWidgetContent(state: PlaybackUiState, artwork: Bitmap?, progress: Float) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_CARD_BG)
            .cornerRadius(WIDGET_CARD_RADIUS)
            .padding(10.dp),
    ) {
        val context = LocalContext.current
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

        Row(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // 44dp, matching the design's own large-size thumb spec (down from the
            // 52dp used elsewhere).
            AlbumArt(artwork, onClick = openApp, size = 44.dp)

            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(horizontal = 6.dp)
                    .clickable(openApp),
            ) {
                Text(
                    text = state.title ?: "Nichts wird abgespielt",
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = state.artist ?: "Music Agent",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color(0xFFAAAAAA)), fontSize = 11.sp),
                )
            }
        }

        // Full-width control row: shuffle - skip-back - play/pause - skip-forward -
        // repeat, matching the design's five-icon spec exactly. Glance's Row has no
        // "space-between" arrangement, so equal-weight Spacers between each icon
        // reproduce it the standard Glance way.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            WidgetControlButton(
                icon = R.drawable.ic_widget_shuffle,
                contentDescription = "Zufallswiedergabe",
                action = actionRunCallback<ToggleShuffleAction>(),
                touchTarget = 36.dp,
                iconSize = 16.dp,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())

            WidgetControlButton(
                icon = R.drawable.ic_widget_skip_previous,
                contentDescription = "Vorheriger Titel",
                action = actionRunCallback<SkipPreviousAction>(),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())

            PlayPauseIcon(isPlaying = state.isPlaying, iconSize = 30.dp)
            Spacer(modifier = GlanceModifier.defaultWeight())

            WidgetControlButton(
                icon = R.drawable.ic_widget_skip_next,
                contentDescription = "Nächster Titel",
                action = actionRunCallback<SkipNextAction>(),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())

            WidgetControlButton(
                icon = R.drawable.ic_widget_repeat,
                contentDescription = "Wiederholen",
                action = actionRunCallback<CycleRepeatModeAction>(),
                touchTarget = 36.dp,
                iconSize = 16.dp,
            )
        }

        WidgetProgressBar(progress)
    }
}

/** The compact 2x1 variant: cover, title (no artist/transport/progress - too
 * little room to stay legible), and a plain accent play/pause-circle tap target
 * - matches the design's own compact spec (thumb + title + a `ph-fill
 * ph-play-circle` indicator, not a full button). Tapping the cover or title
 * opens the app; the play/pause circle toggles playback directly. */
@Composable
private fun CompactWidgetContent(state: PlaybackUiState, artwork: Bitmap?) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_CARD_BG)
            .cornerRadius(WIDGET_CARD_RADIUS)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        val context = LocalContext.current
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

        // 34dp, matching the design's own compact-size thumb spec.
        AlbumArt(artwork, onClick = openApp, size = 34.dp)
        Text(
            text = state.title ?: "Nichts wird abgespielt",
            maxLines = 1,
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 8.dp)
                .clickable(openApp),
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        PlayPauseIcon(isPlaying = state.isPlaying, iconSize = 22.dp, touchTarget = 32.dp)
    }
}

/** A thin, non-seekable progress indicator reflecting [progress] (0f-1f) - see
 * the caller for why this only updates on play/pause/track-change rather than
 * ticking every second. */
@Composable
private fun WidgetProgressBar(progress: Float) {
    // Neither Glance's fillMaxWidth() nor defaultWeight() take a fraction/weight
    // argument in this version (unlike Compose foundation's) - LocalSize.current
    // gives this widget instance's actual measured width, so an absolute Dp
    // width is computed directly instead of relying on either.
    val totalWidth = LocalSize.current.width
    val playedWidth = totalWidth * progress.coerceIn(0f, 1f)
    Box(
        modifier = GlanceModifier
            .width(totalWidth)
            .height(3.dp)
            .padding(top = 6.dp)
            .background(WIDGET_SURFACE)
            .cornerRadius(2.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .width(playedWidth)
                .height(3.dp)
                .background(WIDGET_ACCENT)
                .cornerRadius(2.dp),
        ) {}
    }
}

@Composable
private fun AlbumArt(artwork: Bitmap?, onClick: Action, size: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(WIDGET_ART_RADIUS)
            .background(WIDGET_SURFACE)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(size / 2),
            )
        }
    }
}

/** A transport icon with its clickable bounds padded out beyond the visible glyph
 * size - defaults to [CONTROL_TOUCH_TARGET]/22dp for skip-previous/-next, but the
 * design's smaller/secondary shuffle+repeat icons pass a smaller [touchTarget]/
 * [iconSize] instead so all five controls fit across one row without shrinking
 * skip-previous/-next or play/pause below their own spec sizes. */
@Composable
private fun WidgetControlButton(
    icon: Int,
    contentDescription: String,
    action: Action,
    touchTarget: androidx.compose.ui.unit.Dp = CONTROL_TOUCH_TARGET,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Box(
        modifier = GlanceModifier
            .size(touchTarget)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(iconSize),
        )
    }
}

/** The design's own play/pause control (both the compact and large size specs in
 * Music Agent Widget.dc.html): a plain `ph-fill ph-play-circle`/`ph-pause-circle`
 * glyph at accent color - the icon itself already reads as a filled circle, no
 * separate button background drawn behind it. [iconSize] differs between the
 * compact (22dp) and large (30dp, since it's the standout control in a 5-icon
 * row) call sites; [touchTarget] pads the tappable bounds out to the icon's own
 * accessible minimum without changing what's drawn. */
@Composable
private fun PlayPauseIcon(
    isPlaying: Boolean,
    iconSize: androidx.compose.ui.unit.Dp,
    touchTarget: androidx.compose.ui.unit.Dp = CONTROL_TOUCH_TARGET,
) {
    Box(
        modifier = GlanceModifier
            .size(touchTarget)
            .clickable(actionRunCallback<TogglePlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(
                if (isPlaying) R.drawable.ic_widget_pause_circle else R.drawable.ic_widget_play_circle,
            ),
            contentDescription = if (isPlaying) "Pause" else "Wiedergabe",
            modifier = GlanceModifier.size(iconSize),
        )
    }
}
