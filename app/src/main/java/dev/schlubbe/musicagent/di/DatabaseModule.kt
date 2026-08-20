package dev.schlubbe.musicagent.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.schlubbe.musicagent.data.local.AppDatabase
import dev.schlubbe.musicagent.data.local.MIGRATION_1_2
import dev.schlubbe.musicagent.data.local.MIGRATION_2_3
import dev.schlubbe.musicagent.data.local.MIGRATION_3_4
import dev.schlubbe.musicagent.data.local.MIGRATION_4_5
import dev.schlubbe.musicagent.data.local.MIGRATION_5_6
import dev.schlubbe.musicagent.data.local.MIGRATION_6_7
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.FollowedArtistDao
import dev.schlubbe.musicagent.data.local.dao.LikeDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistTrackDao
import dev.schlubbe.musicagent.data.local.dao.SavedPlaylistDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "music-agent.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides
    fun provideTrackDao(database: AppDatabase): TrackDao = database.trackDao()

    @Provides
    fun provideDownloadDao(database: AppDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideLikeDao(database: AppDatabase): LikeDao = database.likeDao()

    @Provides
    fun providePlaylistDao(database: AppDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun providePlaylistTrackDao(database: AppDatabase): PlaylistTrackDao = database.playlistTrackDao()

    @Provides
    fun provideFollowedArtistDao(database: AppDatabase): FollowedArtistDao = database.followedArtistDao()

    @Provides
    fun provideSavedPlaylistDao(database: AppDatabase): SavedPlaylistDao = database.savedPlaylistDao()
}
