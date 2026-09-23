package dev.schlubbe.musicagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.data.extract.SharedLinkResolver
import dev.schlubbe.musicagent.data.extract.SharedLinkTarget
import dev.schlubbe.musicagent.playback.PlayerController
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import dev.schlubbe.musicagent.ui.navigation.MusicAgentNavGraph
import dev.schlubbe.musicagent.ui.navigation.Routes
import dev.schlubbe.musicagent.ui.navigation.SharedLinkNavHolder
import dev.schlubbe.musicagent.ui.theme.GrooveoTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var sharedLinkResolver: SharedLinkResolver
    @Inject lateinit var sharedLinkNavHolder: SharedLinkNavHolder
    @Inject lateinit var downloadDao: DownloadDao

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // No-op result handler: playback still works without the permission, it just
    // means the media notification (and the update-check dialog's notifications,
    // once wired) won't show on Android 13+ (POST_NOTIFICATIONS is required there).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() per the SplashScreen API's contract -
        // it reads the calling activity's theme (Theme.MusicAgent.Splash, set in
        // the manifest) to know what to show, then hands off to
        // postSplashScreenTheme (Theme.MusicAgent) once dismissed.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Asked when something first plays - that's when the media notification (the
        // reason for the permission) appears - rather than on the first frame, before
        // the user has seen what the app even is.
        lifecycleScope.launch {
            playerController.playbackState.first { it.isPlaying }
            requestNotificationPermissionIfNeeded()
        }
        lifecycleScope.launch { requestMediaAccessIfDownloadsUnreadable() }
        setContent {
            GrooveoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicAgentNavGraph()
                }
            }
        }
        handleSharedLinkIntent(intent)
    }

    // launchMode="singleTask" (see the manifest) routes a re-share while the app is
    // already running back through here instead of a fresh onCreate().
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLinkIntent(intent)
    }

    /** Resolves a SoundCloud/YouTube link this activity was opened or re-shared with
     * (see the manifest's ACTION_VIEW/ACTION_SEND intent filters and
     * [SharedLinkResolver]) and either starts playback or hands the target route to
     * [MusicAgentNavGraph] via [sharedLinkNavHolder]. A plain launcher/MAIN intent
     * has no data/EXTRA_TEXT, so [SharedLinkResolver.extractUrl] simply returns null
     * for it and nothing here fires. */
    private fun handleSharedLinkIntent(intent: Intent?) {
        val url = intent?.let(sharedLinkResolver::extractUrl) ?: return
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Link wird geöffnet …", Toast.LENGTH_SHORT).show()
            when (val target = sharedLinkResolver.resolveUrl(url)) {
                is SharedLinkTarget.Track -> {
                    // Open the Player first so its loading state is visible while the
                    // stream resolves.
                    sharedLinkNavHolder.pendingRoute.value = Routes.PLAYER
                    playerController.playTrack(target.track)
                }
                is SharedLinkTarget.Playlist ->
                    sharedLinkNavHolder.pendingRoute.value = Routes.remotePlaylistDetail(target.source, target.sourceId)
                is SharedLinkTarget.Artist ->
                    sharedLinkNavHolder.pendingRoute.value = Routes.artistDetail(target.source, target.sourceId)
                null -> Toast.makeText(this@MainActivity, "Link konnte nicht geöffnet werden", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** After a reinstall the downloads' files are still in Music/, but Android no
     * longer counts them as this app's own, so opening them fails until the user
     * grants audio read access. Probe one download and ask only in that case. */
    private suspend fun requestMediaAccessIfDownloadsUnreadable() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        val uri = downloadDao.anyCompleted()?.mediaStoreUri ?: return
        val readable = withContext(Dispatchers.IO) {
            runCatching { contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close() }.isSuccess
        }
        if (!readable) {
            Toast.makeText(this, "Bitte erlaube den Zugriff auf Audiodateien, damit deine Downloads wieder abspielbar sind.", Toast.LENGTH_LONG).show()
            mediaPermissionLauncher.launch(permission)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
