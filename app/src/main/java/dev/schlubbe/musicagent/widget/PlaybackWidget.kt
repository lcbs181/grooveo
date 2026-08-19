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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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

// SoundCloud's own signature orange, used the same way their app does: as the one
// accent color on the primary action (play/pause), not smeared across every icon.
private val SOUNDCLOUD_ORANGE = Color(0xFFFF5500)
private val WIDGET_BACKGROUND = Color(0xFF121212)
private val WIDGET_SURFACE = Color(0xFF1E1E1E)

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

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val playerController = widgetPlayerController(context)
        provideContent {
            val state by playerController.playbackState.collectAsState()
            var artwork by remember { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(state.artworkUrl) {
                artwork = state.artworkUrl?.let { loadArtwork(context, it) }
            }

            PlaybackWidgetContent(state, artwork)
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

@Composable
private fun PlaybackWidgetContent(state: PlaybackUiState, artwork: Bitmap?) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_BACKGROUND)
            .cornerRadius(16.dp)
            .padding(10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        val context = LocalContext.current
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

        AlbumArt(artwork, onClick = openApp)

        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 10.dp)
                .clickable(openApp),
        ) {
            Text(
                text = state.title ?: "Nichts wird abgespielt",
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = state.artist ?: "Music Agent",
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Color(0xFFAAAAAA)), fontSize = 12.sp),
            )
        }

        WidgetControlButton(
            icon = R.drawable.ic_widget_skip_previous,
            contentDescription = "Vorheriger Titel",
            action = actionRunCallback<SkipPreviousAction>(),
        )

        Spacer(modifier = GlanceModifier.width(6.dp))

        PlayPauseButton(isPlaying = state.isPlaying)

        Spacer(modifier = GlanceModifier.width(6.dp))

        WidgetControlButton(
            icon = R.drawable.ic_widget_skip_next,
            contentDescription = "Nächster Titel",
            action = actionRunCallback<SkipNextAction>(),
        )
    }
}

@Composable
private fun AlbumArt(artwork: Bitmap?, onClick: Action) {
    Box(
        modifier = GlanceModifier
            .size(52.dp)
            .cornerRadius(8.dp)
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
                modifier = GlanceModifier.size(24.dp),
            )
        }
    }
}

/** Skip-previous/-next: a plain icon, but with its clickable bounds padded out to
 * [CONTROL_TOUCH_TARGET] instead of matching the visible glyph size. */
@Composable
private fun WidgetControlButton(icon: Int, contentDescription: String, action: Action) {
    Box(
        modifier = GlanceModifier
            .size(CONTROL_TOUCH_TARGET)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(22.dp),
        )
    }
}

/** The one primary action, styled the way SoundCloud reserves its brand orange for
 * exactly this: a filled circular button rather than a bare icon. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(CONTROL_TOUCH_TARGET)
            .cornerRadius(CONTROL_TOUCH_TARGET / 2)
            .background(SOUNDCLOUD_ORANGE)
            .clickable(actionRunCallback<TogglePlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = if (isPlaying) "Pause" else "Wiedergabe",
            modifier = GlanceModifier.size(22.dp),
        )
    }
}
