package dev.schlubbe.musicagent.data.extract.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExtractorModule {

    @Provides
    @Singleton
    @ExtractionHttpClient
    fun provideExtractionOkHttpClient(): OkHttpClient = extractionHttpClient()
}
