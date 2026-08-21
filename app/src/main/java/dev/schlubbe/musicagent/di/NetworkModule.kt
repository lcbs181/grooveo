package dev.schlubbe.musicagent.di

import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.schlubbe.musicagent.data.extract.di.ExtractionHttpClient
import dev.schlubbe.musicagent.data.remote.AuthInterceptor
import dev.schlubbe.musicagent.data.remote.BackendApi
import dev.schlubbe.musicagent.data.remote.DynamicBaseUrlInterceptor
import dev.schlubbe.musicagent.data.remote.ServiceAccountAuthenticator
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the OkHttpClient backing ExoPlayer's actual audio [OkHttpDataSource] --
 * deliberately its own client rather than reusing [ExtractionHttpClient], which was
 * the previous (buggy) wiring. That client's 30s readTimeout is fine for the quick
 * JSON calls it was designed for (client_id fetch, track resolve, search), but was
 * silently inherited here too since [provideOkHttpDataSourceFactory] built its
 * factory straight from it. A real audio stream can legitimately go longer than 30s
 * without the socket delivering a new byte -- e.g. once the (deliberately small, see
 * PlaybackService's DefaultLoadControl) playback buffer is already full and the
 * loader has paused reading ahead, or during any ordinary mobile-network hiccup --
 * and OkHttp's readTimeout kills the connection outright when that happens, which
 * ExoPlayer surfaces as a fatal, non-retried load error: the track just stops, with
 * no toast and no skip to the next one. Since *when* a >30s gap occurs is purely a
 * function of transient network conditions, not track content, this reproduced
 * exactly as reported -- an inconsistent cutoff sometimes ~30s in, sometimes later. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        serviceAccountAuthenticator: ServiceAccountAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(dynamicBaseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(serviceAccountAuthenticator)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideBackendApi(retrofit: Retrofit): BackendApi = retrofit.create(BackendApi::class.java)

    // @PlaybackHttpClient, not @ExtractionHttpClient/the main backend client -- see
    // that annotation's kdoc. Still deliberately not the backend-authed client either
    // way: this is the data source ExoPlayer actually streams audio bytes through,
    // which means direct requests to YouTube's/SoundCloud's own CDNs, and using the
    // backend-authed client here would leak the X-API-Key/service-account bearer
    // token to those third-party hosts on every playback request.
    @Provides
    @Singleton
    @PlaybackHttpClient
    fun providePlaybackOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // No read timeout (see @PlaybackHttpClient kdoc) -- a stalled/paused stream
        // is normal for real playback and must not hard-kill the connection.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpDataSourceFactory(
        @PlaybackHttpClient okHttpClient: OkHttpClient,
    ): OkHttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
}
