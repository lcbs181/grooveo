package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached result of [dev.schlubbe.musicagent.playback.analysis.TrackAnalyzer] for one
 * downloaded track - see that class for how [mixOutMs]/[mixInMs] are derived. Analysis
 * needs the actual decoded audio, so this only ever exists for a track that has (or
 * had) a completed download; a track with no row here just gets today's plain
 * fixed-duration crossfade (see [dev.schlubbe.musicagent.playback.CrossfadeController]). */
@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val trackId: String,
    // Position (ms) to start fading *out of* this track when it's the outgoing one -
    // chosen to land before its outro/silence tail rather than blindly using the last
    // N seconds of raw file, which on a lot of real uploads is dead air or an
    // unrelated fade-to-silence.
    val mixOutMs: Long,
    // Position (ms) to start playing *from* when this track is the incoming one -
    // skips past a quiet/ambient intro straight to where the track's actual body
    // begins, instead of making the listener wait through several seconds of near-
    // silence on every single transition.
    val mixInMs: Long,
    val analyzedAt: Long,
)
