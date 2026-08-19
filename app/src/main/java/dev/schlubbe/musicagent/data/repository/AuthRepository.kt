package dev.schlubbe.musicagent.data.repository

import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.remote.dto.LoginRequestDto
import dev.schlubbe.musicagent.data.remote.dto.UserDto
import javax.inject.Inject
import javax.inject.Singleton

/** Backend-less variant: no login UI exists anywhere in this app. [login] is only
 * ever called by the silent service-account flow (see MusicAgentApp.onCreate and
 * ServiceAccountAuthenticator) that keeps analytics events flowing to the real
 * backend without the user ever seeing a sign-in screen. */
@Singleton
class AuthRepository @Inject constructor(
    private val backendApi: BackendApi,
    private val authTokenHolder: AuthTokenHolder,
) {
    suspend fun login(email: String, password: String) {
        val token = backendApi.login(LoginRequestDto(email, password))
        authTokenHolder.saveToken(token.accessToken)
    }

    suspend fun me(): UserDto = backendApi.me()
}
