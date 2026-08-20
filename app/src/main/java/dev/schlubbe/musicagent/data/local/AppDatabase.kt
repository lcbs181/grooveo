package dev.schlubbe.musicagent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.schlubbe.musicagent.data.local.converter.DownloadStateConverter
import dev.schlubbe.musicagent.data.local.dao.DownloadDao
import dev.schlubbe.musicagent.data.local.dao.FollowedArtistDao
import dev.schlubbe.musicagent.data.local.dao.LikeDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistDao
import dev.schlubbe.musicagent.data.local.dao.PlaylistTrackDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity
import dev.schlubbe.musicagent.data.local.entity.LikeEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistTrackEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        DownloadEntity::class,
        LikeEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FollowedArtistEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(DownloadStateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun likeDao(): LikeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun followedArtistDao(): FollowedArtistDao
}

// Backend-less variant: likes/playlists move from the (removed) server to local
// Room tables. Denormalized track columns (see LocalTrackEntity) so this migration
// never touches the existing tracks/downloads tables.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS likes (
                trackId TEXT NOT NULL PRIMARY KEY,
                source TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                album TEXT,
                durationSec INTEGER,
                thumbnailUrl TEXT,
                webpageUrl TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS playlists (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS playlist_tracks (
                playlistId TEXT NOT NULL,
                trackId TEXT NOT NULL,
                source TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                album TEXT,
                durationSec INTEGER,
                thumbnailUrl TEXT,
                webpageUrl TEXT NOT NULL,
                position INTEGER NOT NULL,
                addedAt TEXT NOT NULL,
                PRIMARY KEY(playlistId, trackId),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
    }
}

// Playlist redesign (Nocturne): a description, a chosen accent color, and mood
// tags, editable via the playlist edit sheet -- see PlaylistEntity for the
// column semantics. All three are nullable with no default, which SQLite's
// ALTER TABLE ADD COLUMN allows without a full table rebuild.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN description TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN accentColorKey TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN moodTags TEXT")
    }
}

// Artist page's new follow/unfollow button (Nocturne redesign) - also backs
// Home's "Neu von Künstlern" shelf.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS followed_artists (
                source TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                name TEXT NOT NULL,
                thumbnailUrl TEXT,
                followedAt TEXT NOT NULL,
                PRIMARY KEY(source, sourceId)
            )""",
        )
    }
}
