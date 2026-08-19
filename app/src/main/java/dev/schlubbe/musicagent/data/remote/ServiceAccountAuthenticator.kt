package dev.schlubbe.musicagent.data.remote

import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.repository.AuthRepository
import dev.schlubbe.musicagent.data.repository.AuthTokenHolder
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider

/** This backend-less app has no login screen, but /events (analytics) still needs a
 * valid JWT the way the server-backed app's logged-in user would provide one. A
 * fixed service account (provisioned once by the user against the real backend, see
 * app/build.gradle.kts's SERVICE_ACCOUNT_* fields) is logged into silently at app
 * start (see MusicAgentApp.onCreate). That token eventually expires
 * (jwt_expire_minutes in the backend's config) — this Authenticator re-logs-in once
 * on a 401 and retries, the standard OkHttp hook for "credential expired, refresh
 * and retry" (as opposed to Interceptor, which can't distinguish a stale-token 401
 * from any other failure).
 *
 * [AuthRepository] is injected via [Provider] to avoid a DI cycle: this Authenticator
 * is attached to the OkHttpClient that Retrofit/BackendApi is built from, and
 * AuthRepository itself depends on BackendApi. */
class ServiceAccountAuthenticator @Inject constructor(
    private val authRepository: Provider<AuthRepository>,
    private val authTokenHolder: AuthTokenHolder,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only re-auth requests that were already carrying a token - avoids retrying
        // (and looping on) calls that fail for unrelated reasons.
        if (response.request.header("Authorization") == null) return null
        if (responseCount(response) >= 2) return null
        if (BuildConfig.SERVICE_ACCOUNT_EMAIL.isBlank()) return null

        val refreshed = runBlocking {
            runCatching {
                authRepository.get().login(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PASSWORD)
            }
        }
        if (refreshed.isFailure) return null

        val newToken = authTokenHolder.tokenCached ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
