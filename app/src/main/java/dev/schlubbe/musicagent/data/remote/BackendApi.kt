package dev.schlubbe.musicagent.data.remote

import dev.schlubbe.musicagent.data.remote.dto.EventCreateDto
import dev.schlubbe.musicagent.data.remote.dto.LoginRequestDto
import dev.schlubbe.musicagent.data.remote.dto.TokenResponseDto
import dev.schlubbe.musicagent.data.remote.dto.UpdateInfoDto
import dev.schlubbe.musicagent.data.remote.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Backend-less variant: search/streaming/likes/playlists/feed are all on-device
 * now (see data/extract, data/local). This interface only keeps what still talks to
 * the real backend — login (for the silent service account, see MusicAgentApp /
 * ServiceAccountAuthenticator), analytics events, and update checks. */
interface BackendApi {
    @GET("healthz")
    suspend fun healthz()

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): TokenResponseDto

    @GET("users/me")
    suspend fun me(): UserDto

    @POST("events")
    suspend fun recordEvent(@Body body: EventCreateDto)

    @GET("updates/latest")
    suspend fun getLatestUpdate(): UpdateInfoDto
}
