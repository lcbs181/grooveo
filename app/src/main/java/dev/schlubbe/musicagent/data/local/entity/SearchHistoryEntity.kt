package dev.schlubbe.musicagent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A recently searched query — used by the search tab to show a tappable history
 * when the query field is empty. Timestamp tracks the last time this query was
 * searched, so we can sort by recency and cap at ~20 entries. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    val query: String, // Full trimmed search text
    val searchedAt: String, // ISO-8601
)
