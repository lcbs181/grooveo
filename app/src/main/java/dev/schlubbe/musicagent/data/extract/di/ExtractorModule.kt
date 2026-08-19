package dev.schlubbe.musicagent.data.extract.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the plain OkHttpClient used for on-device SoundCloud/YouTube extraction —
 * deliberately NOT the app's main OkHttpClient (see NetworkModule), since that one
 * carries AuthInterceptor/DynamicBaseUrlInterceptor which attach the private
 * backend's X-API-Key/Authorization headers and rewrite the base URL. Those must
 * never be sent to soundcloud.com/youtube.com. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExtractionHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ExtractorModule {

    @Provides
    @Singleton
    @ExtractionHttpClient
    fun provideExtractionOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
