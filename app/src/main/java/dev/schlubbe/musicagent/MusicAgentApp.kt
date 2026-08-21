package dev.schlubbe.musicagent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import dev.schlubbe.musicagent.data.extract.youtube.NewPipeDownloader
import dev.schlubbe.musicagent.data.repository.AuthRepository
import dev.schlubbe.musicagent.data.repository.AuthTokenHolder
import dev.schlubbe.musicagent.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MusicAgentApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var newPipeDownloader: NewPipeDownloader

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var authTokenHolder: AuthTokenHolder

    @Inject
    lateinit var workManager: WorkManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        NewPipe.init(newPipeDownloader)
        schedulePeriodicUpdateCheck()

        // No login screen in this backend-less variant, but analytics events
        // (/events) and update checks still go through the real backend, and
        // /events needs a logged-in JWT the same way the server-backed app's user
        // would provide. Rather than changing the shared backend, quietly
        // authenticate as a single fixed service account the user provisions once
        // (BuildConfig.SERVICE_ACCOUNT_EMAIL/PASSWORD, see app/build.gradle.kts) --
        // no UI ever shows this. ServiceAccountAuthenticator (NetworkModule) handles
        // re-login once the token expires.
        if (authTokenHolder.tokenCached == null) {
            appScope.launch {
                runCatching {
                    authRepository.login(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PASSWORD)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Background counterpart to Settings' manual "Nach Updates suchen" button and
     * NavGraph's once-per-launch silent check - see UpdateCheckWorker's kdoc.
     * ExistingPeriodicWorkPolicy.KEEP: onCreate() running again (process restarts
     * etc.) should never reset the 24h schedule's next-run time. Any network is fine
     * here - unlike the ~80-90MB APK download itself, this is a single small GitHub
     * API call, so it doesn't need the download flow's wifi-only opt-in
     * (SettingsRepository.downloadsWifiOnly) to be worth gating on. */
    private fun schedulePeriodicUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
