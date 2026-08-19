package dev.schlubbe.musicagent.data.remote.dto

import com.google.gson.annotations.SerializedName

data class UpdateInfoDto(
    @SerializedName("version_code") val versionCode: Long,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("download_url") val downloadUrl: String,
)
