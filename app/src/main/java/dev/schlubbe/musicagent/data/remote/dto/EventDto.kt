package dev.schlubbe.musicagent.data.remote.dto

import com.google.gson.annotations.SerializedName

data class EventCreateDto(
    @SerializedName("event_type") val eventType: String,
    val track: TrackResultDto? = null,
    val query: String? = null,
    @SerializedName("duration_ms") val durationMs: Long? = null,
)
