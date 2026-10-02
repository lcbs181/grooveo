package dev.schlubbe.musicagent.desktop.ui.screens

import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.LikedTrack
import dev.schlubbe.musicagent.desktop.data.Playlist
import dev.schlubbe.musicagent.desktop.data.key
import java.io.File
import java.text.Collator
import java.util.Locale

/** Pure list logic behind the library / playlist screens (unit-tested). */

enum class LikeSort(val label: String) { RECENT("Zuletzt hinzugefügt"), TITLE("Titel A–Z"), ARTIST("Künstler A–Z") }

private val collator: Collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }

private fun TrackResultDto.matches(q: String) =
    title.contains(q, true) || artist?.contains(q, true) == true || album?.contains(q, true) == true

/** Favoriten: text filter, "Nur offline", sort. */
fun filterLikes(
    likes: List<LikedTrack>,
    query: String = "",
    sort: LikeSort = LikeSort.RECENT,
    offlineOnly: Boolean = false,
    offlineKeys: Set<String> = emptySet(),
): List<LikedTrack> {
    val q = query.trim()
    val filtered = likes.filter { l ->
        (q.isEmpty() || l.track.matches(q)) && (!offlineOnly || l.track.key in offlineKeys)
    }
    return when (sort) {
        LikeSort.RECENT -> filtered.sortedByDescending { it.createdAt }
        LikeSort.TITLE -> filtered.sortedWith(compareBy(collator) { it.track.title })
        LikeSort.ARTIST -> filtered.sortedWith(compareBy<LikedTrack, String>(collator) { it.track.artist.orEmpty() }.thenBy(collator) { it.track.title })
    }
}

/** Sortable columns of the desktop track table. */
enum class TrackColumn { INDEX, TITLE, ARTIST, DURATION }

data class TrackSort(val column: TrackColumn = TrackColumn.INDEX, val ascending: Boolean = true) {
    /** Clicking a header: same column flips direction, a third click returns to the original order. */
    fun click(col: TrackColumn): TrackSort = when {
        col == TrackColumn.INDEX -> TrackSort()
        col != column -> TrackSort(col, true)
        ascending -> TrackSort(col, false)
        else -> TrackSort()
    }
}

fun sortTracks(tracks: List<TrackResultDto>, sort: TrackSort): List<TrackResultDto> {
    val sorted = when (sort.column) {
        TrackColumn.INDEX -> tracks
        TrackColumn.TITLE -> tracks.sortedWith(compareBy(collator) { it.title })
        TrackColumn.ARTIST -> tracks.sortedWith(compareBy<TrackResultDto, String>(collator) { it.artist.orEmpty() }.thenBy(collator) { it.title })
        TrackColumn.DURATION -> tracks.sortedBy { it.durationSec ?: Int.MAX_VALUE }
    }
    return if (sort.ascending || sort.column == TrackColumn.INDEX) sorted else sorted.reversed()
}

fun totalDurationSec(tracks: List<TrackResultDto>): Long = tracks.sumOf { (it.durationSec ?: 0).toLong() }

/** "12 Titel · 1 Std. 4 Min." */
fun trackCountLabel(count: Int, totalSec: Long): String {
    val titles = if (count == 1) "1 Titel" else "$count Titel"
    if (totalSec <= 0) return titles
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val dur = when {
        h > 0 -> "$h Std. $m Min."
        m > 0 -> "$m Min."
        else -> "$totalSec Sek."
    }
    return "$titles · $dur"
}

/** Local playlists have no URL; "Teilen" copies a plain-text summary (same as Android). */
fun playlistShareText(p: Playlist): String =
    "${p.name} (${p.tracks.size} Titel)\n" + p.tracks.joinToString("\n") { t ->
        "- ${t.track.title}" + (t.track.artist?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
    }

/** Destination of a copied custom cover inside `dataDir/covers`. */
fun coverTargetFile(coversDir: File, playlistId: String, sourceName: String, now: Long = System.currentTimeMillis()): File {
    val ext = sourceName.substringAfterLast('.', "").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") } ?: "jpg"
    return File(coversDir, "$playlistId-$now.$ext")
}

/** Image path -> coil model. */
fun fileModel(path: String?): String? = path?.takeIf { it.isNotBlank() && File(it).isFile }?.let { "file://" + File(it).absolutePath }

/** "vor 5 Min." style relative time for the history list. */
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "gerade eben"
        s < 3600 -> "vor ${s / 60} Min."
        s < 86_400 -> "vor ${s / 3600} Std."
        s < 2 * 86_400 -> "gestern"
        s < 30 * 86_400 -> "vor ${s / 86_400} Tagen"
        else -> java.time.Instant.ofEpochMilli(then).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}

/** Index after moving an item one step (Nach oben / Nach unten); null when it can't move. */
fun stepTarget(index: Int, delta: Int, size: Int): Int? = (index + delta).takeIf { index in 0 until size && it in 0 until size }
