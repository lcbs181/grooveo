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
 * The login itself goes through [AuthRepository.login], which uses the separate
 * @[LoginBackendApi] client (own dispatcher, no authenticator - see NetworkModule).
 * Logging in through the same client this Authenticator is attached to deadlocked:
 * authenticate() blocks an OkHttp dispatcher thread, and once every
 * maxRequestsPerHost slot for the backend was held by a request waiting here, the
 * login call queued behind them could never start.
 *
 * [AuthRepository] is injected via [Provider] to avoid a DI cycle: this Authenticator
 * is attached to the OkHttpClient that Retrofit/BackendApi is built from, and
 * AuthRepository itself depends on BackendApi. */
class ServiceAccountAuthenticator internal constructor(
    private val refresher: SingleFlightTokenRefresher,
) : Authenticator {

    @Inject constructor(
        authRepository: Provider<AuthRepository>,
        authTokenHolder: AuthTokenHolder,
    ) : this(
        SingleFlightTokenRefresher(
            currentToken = { authTokenHolder.tokenCached },
            login = {
                runBlocking {
                    runCatching {
                        authRepository.get().login(BuildConfig.SERVICE_ACCOUNT_EMAIL, BuildConfig.SERVICE_ACCOUNT_PASSWORD)
                    }.isSuccess
                }
            },
        ),
    )

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only re-auth requests that were already carrying a token - avoids retrying
        // (and looping on) calls that fail for unrelated reasons.
        val failedHeader = response.request.header("Authorization") ?: return null
        if (responseCount(response) >= MAX_RESPONSES) return null
        if (BuildConfig.SERVICE_ACCOUNT_EMAIL.isBlank()) return null

        val newToken = refresher.freshToken(failedHeader.removePrefix("Bearer ")) ?: return null
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

    private companion object {
        /** One retry with a refreshed token; a second 401 on top means the fresh
         * token is rejected too, and another login would not change that. */
        const val MAX_RESPONSES = 2
    }
}

/** Collapses concurrent token refreshes into one login. When N requests fail with
 * 401 at the same moment (the token expired while the app was idle, then a burst of
 * /events goes out), each of them lands in [freshToken] - the first one logs in, and
 * every other one, finding on entry that the token no longer matches the one its own
 * request was rejected with, just reuses the new token instead of logging in again.
 *
 * A failed login is not retried for [failureBackoffMs], so a backend that is down or
 * rejecting the account doesn't get one login attempt per queued request. */
internal class SingleFlightTokenRefresher(
    private val currentToken: () -> String?,
    private val login: () -> Boolean,
    private val failureBackoffMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var lastFailureAtMs = Long.MIN_VALUE / 2

    /** Returns a token to retry with, or null to give up. [failedToken] is the token
     * the rejected request carried. */
    fun freshToken(failedToken: String): String? = synchronized(lock) {
        val current = currentToken()
        // Someone else already refreshed while this request was in flight or waiting
        // for the lock.
        if (current != null && current != failedToken) return current
        if (clock() - lastFailureAtMs < failureBackoffMs) return null
        if (!login()) {
            lastFailureAtMs = clock()
            return null
        }
        currentToken()
    }
}
