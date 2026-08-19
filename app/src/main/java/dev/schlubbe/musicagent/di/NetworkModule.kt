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
import javax.inject.Singleton

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

    // Deliberately the @ExtractionHttpClient (no AuthInterceptor/DynamicBaseUrlInterceptor)
    // -- this is the data source ExoPlayer actually streams audio bytes through, which
    // now means direct requests to YouTube's/SoundCloud's own CDNs, not our backend.
    // Using the backend-authed client here would leak the X-API-Key/service-account
    // bearer token to those third-party hosts on every playback request.
    @Provides
    @Singleton
    fun provideOkHttpDataSourceFactory(
        @ExtractionHttpClient okHttpClient: OkHttpClient,
    ): OkHttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
}
