package dev.schlubbe.musicagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import dev.schlubbe.musicagent.ui.navigation.MusicAgentNavGraph
import dev.schlubbe.musicagent.ui.theme.MusicAgentTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        requestNotificationPermissionIfNeeded()
        setContent {
            MusicAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicAgentNavGraph()
                }
            }
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
