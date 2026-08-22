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
import dev.schlubbe.musicagent.data.local.dao.SavedPlaylistDao
import dev.schlubbe.musicagent.data.local.dao.SearchHistoryDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.local.entity.DownloadEntity
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity
import dev.schlubbe.musicagent.data.local.entity.LikeEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.PlaylistTrackEntity
import dev.schlubbe.musicagent.data.local.entity.SavedPlaylistEntity
import dev.schlubbe.musicagent.data.local.entity.SearchHistoryEntity
import dev.schlubbe.musicagent.data.local.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        DownloadEntity::class,
        LikeEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FollowedArtistEntity::class,
        SavedPlaylistEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 10,
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
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
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

// Playlist redesign (Canopy): a description, a chosen accent color, and mood
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

// Artist page's new follow/unfollow button (Canopy redesign) - also backs
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

// Share feature: the "tracks" cache table (recently played/searched, backing the
// Downloads tab's metadata) never stored a permalink, unlike every other track
// table (likes/playlist_tracks already have webpageUrl from MIGRATION_1_2). Existing
// rows get an empty string, same "no link available yet" fallback already used by
// TrackEntity.toTrackResultDto() - they'll get a real URL next time that track is
// searched/played and re-upserted.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN webpageUrl TEXT NOT NULL DEFAULT ''")
    }
}

// SoundCloud downloads + pause/resume/retry: DownloadEntity gained a PAUSED state
// and partial-transfer bookkeeping (see that entity's doc comment) so a paused or
// failed-mid-transfer progressive download can resume via an HTTP Range request
// instead of restarting from byte 0.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN tempFilePath TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDownloaded INTEGER NOT NULL DEFAULT 0")
    }
}

// "Liking" a public playlist/album from Search - same local-bookmark pattern as
// followed_artists (MIGRATION_3_4), keyed by (source, sourceId) instead of a
// synthetic id since a remote playlist is already uniquely identified that way.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS saved_playlists (
                source TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnailUrl TEXT,
                owner TEXT,
                trackCount INTEGER,
                webpageUrl TEXT NOT NULL,
                savedAt TEXT NOT NULL,
                PRIMARY KEY(source, sourceId)
            )""",
        )
    }
}

// Search history: store recent queries to display in the search tab when the
// query field is empty or on focus. Keyed by the trimmed query string; tapping
// a past entry re-runs that search. Timestamp allows sorting by recency and
// capping at ~20 entries.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS search_history (
                query TEXT NOT NULL PRIMARY KEY,
                searchedAt TEXT NOT NULL
            )""",
        )
    }
}

// Background genre capture (see TrackEntity.genre) - SoundCloud's track JSON already
// carries a "genre" field that was being discarded on the way into the cache; ytmusic
// tracks have no equivalent source field and stay null. Nullable with no default, same
// no-rebuild-needed ALTER TABLE ADD COLUMN as MIGRATION_2_3/MIGRATION_4_5.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT")
    }
}

// File size indicator for downloads: DownloadEntity gained totalBytes (Long, nullable)
// to store the total file size from the HTTP Content-Length header (progressive downloads)
// or calculated from HLS segments. Used to display download size to the user.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN totalBytes INTEGER")
    }
}
