package com.faselhd.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey val episodeUrl: String,
    val animeTitle: String,
    val episodeName: String?,
    val thumbnailUrl: String?,
    var downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    var progress: Int = 0,
    var localFilePath: String? = null, // Add this for the final file path
    var timeLeft: String? = "..." ,// Add this for the time remaining
    var isFinished: Boolean = false,
    val mediaUri: String?


)
