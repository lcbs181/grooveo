package dev.schlubbe.musicagent.data.local.converter

import androidx.room.TypeConverter
import dev.schlubbe.musicagent.data.local.entity.DownloadState

class DownloadStateConverter {
    @TypeConverter
    fun fromState(state: DownloadState): String = state.name

    @TypeConverter
    fun toState(value: String): DownloadState = DownloadState.valueOf(value)
}
