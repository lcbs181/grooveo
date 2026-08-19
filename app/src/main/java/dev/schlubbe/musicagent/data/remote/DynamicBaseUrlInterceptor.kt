package dev.schlubbe.musicagent.data.remote

import dev.schlubbe.musicagent.data.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Retrofit/ExoPlayer need a fixed base URL at construction time, but the backend
 * address is a user-editable Settings value. This rewrites the scheme/host/port
 * of every request to whatever is currently configured, leaving the path/query
 * (defined per-call, e.g. via Retrofit's @GET) untouched.
 */
class DynamicBaseUrlInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val configured = settingsRepository.backendBaseUrlCached.toHttpUrlOrNull()
            ?: return chain.proceed(original)

        val newUrl = original.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
