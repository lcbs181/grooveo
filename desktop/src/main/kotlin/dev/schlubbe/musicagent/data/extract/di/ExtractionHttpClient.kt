package dev.schlubbe.musicagent.data.extract.di

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExtractionHttpClient

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** Same configuration as the Android app's ExtractorModule (browser UA is required by SoundCloud). */
fun extractionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                .build(),
        )
    }
    .build()
