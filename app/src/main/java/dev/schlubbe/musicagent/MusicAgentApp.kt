package dev.schlubbe.musicagent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.schlubbe.musicagent.data.extract.youtube.NewPipeDownloader
import dev.schlubbe.musicagent.data.repository.AuthRepository
import dev.schlubbe.musicagent.data.repository.AuthTokenHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        NewPipe.init(newPipeDownloader)

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
}
