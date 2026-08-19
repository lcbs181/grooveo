package dev.schlubbe.musicagent.data.remote

import dev.schlubbe.musicagent.data.repository.AuthTokenHolder
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authTokenHolder: AuthTokenHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("X-API-Key", settingsRepository.apiKeyCached)

        authTokenHolder.tokenCached?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(builder.build())
    }
}
