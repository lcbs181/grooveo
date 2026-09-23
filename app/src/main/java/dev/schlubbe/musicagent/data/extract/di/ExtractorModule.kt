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

/** Deliberately a *desktop* browser: soundcloud.com 307-redirects any mobile
 * User-Agent to m.soundcloud.com, whose page ships none of the JS bundles the
 * client_id is read from - which is what made every SoundCloud request fail with
 * "Unable to extract SoundCloud client_id". */
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Module
@InstallIn(SingletonComponent::class)
object ExtractorModule {

    @Provides
    @Singleton
    @ExtractionHttpClient
    fun provideExtractionOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Without a browser User-Agent soundcloud.com answers with a stripped page
        // whose scripts carry no client_id, which broke every SoundCloud request with
        // "Unable to extract SoundCloud client_id" (and made 401s far more likely).
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                    .build(),
            )
        }
        .build()
}
