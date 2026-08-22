package dev.schlubbe.musicagent.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

// Matches PlayerController.DRM_UNAVAILABLE_MESSAGE's short form - the full sentence
// ("... DRM-geschützt und kann von dieser App nicht abgespielt werden.") is too long
// for a notification's single-line title.
private const val DRM_UNAVAILABLE_TITLE = "Titel nicht verfügbar"

/**
 * Matches the app's lock-screen/notification-shade player design (see the German
 * user feedback thread's design HTML) on top of Media3's [DefaultMediaNotificationProvider]
 * rather than hand-rolling a NotificationCompat notification, which would fight
 * MediaSessionService's own notification lifecycle.
 *
 * [DefaultMediaNotificationProvider.createNotification] itself is `final` (can't be
 * overridden), but it exposes two protected hooks that ARE overridable and are
 * exactly what's needed here:
 * - [getNotificationContentTitle]: swapped to "Titel nicht verfügbar" while
 *   [PlayerController.playbackState] is sitting on a DRM-unavailable queue slot (see
 *   PlaybackUiState.isUnavailable's kdoc) - a client-side-only concept computed by
 *   PlayerController, not something the session's own player/MediaMetadata knows
 *   about, hence reading it from the injected [PlayerController] singleton rather
 *   than from [mediaMetadata] itself.
 * - [getNotificationContentText]: appended with "· SoundCloud"/"· YouTube Music" the
 *   same way PlayerScreen's CanopyTag derives a source label, so the notification
 *   shows "Artist · Source" instead of a bare artist name. Deliberately reads
 *   [PlayerController.nowPlayingTrack] for the source rather than writing the source
 *   into the MediaItem's own MediaMetadata.artist field, which every other screen
 *   (Player screen, mini player) also reads via their own MediaController connection
 *   - overloading that field here would leak "· Source" into their artist text too.
 *
 * The 5-button row (like/skip-back/play-pause/skip-forward/download) needs no
 * override here at all: CommandButton.SLOT_BACK_SECONDARY/SLOT_FORWARD_SECONDARY
 * (set on the buttons built in PlaybackService) already tell
 * DefaultMediaNotificationProvider's default [getMediaButtons] where to place them
 * relative to the standard transport controls.
 */
@UnstableApi
class MusicNotificationProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val playerController: PlayerController,
) : DefaultMediaNotificationProvider(context) {

    override fun getNotificationContentTitle(mediaMetadata: MediaMetadata): CharSequence =
        if (playerController.playbackState.value.isUnavailable) {
            DRM_UNAVAILABLE_TITLE
        } else {
            super.getNotificationContentTitle(mediaMetadata) ?: ""
        }

    override fun getNotificationContentText(mediaMetadata: MediaMetadata): CharSequence {
        val baseText = super.getNotificationContentText(mediaMetadata)
        val sourceLabel = when (playerController.nowPlayingTrack()?.source) {
            "soundcloud" -> "SoundCloud"
            "ytmusic" -> "YouTube Music"
            else -> null
        }
        return if (sourceLabel != null && !baseText.isNullOrBlank()) "$baseText · $sourceLabel" else baseText ?: ""
    }
}
