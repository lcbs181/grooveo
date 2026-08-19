package dev.schlubbe.musicagent.di

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun provideMediaSourceFactory(
        @ApplicationContext context: Context,
        okHttpDataSourceFactory: OkHttpDataSource.Factory,
    ): MediaSource.Factory {
        // OkHttpDataSource only understands http(s) - it can't open the content://
        // MediaStore URIs downloaded tracks are played from, which silently failed
        // playback despite the file itself being fine (playable in any other app).
        // DefaultDataSource.Factory keeps OkHttp for http(s) streaming and falls back
        // to Android's own ContentDataSource/FileDataSource for everything else.
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)
        return DefaultMediaSourceFactory(dataSourceFactory)
    }
}
