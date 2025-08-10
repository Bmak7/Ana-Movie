package com.faselhd.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey val episodeUrl: String,
    val animeUrl: String, // <-- ADD THIS NEW FIELD
    val animeTitle: String,
    val animeThumbnailUrl: String?,
    val episodeName: String?,
    val lastWatchedPosition: Long,
    val duration: Long,
    val timestamp: Long,
    val isFinished: Boolean = false, // <-- ADD THIS NEW FIELD
    val episodeNumber: Int, // <-- ADD THIS
    val seasonEpisodes: List<SEpisode> // <-- ADD THIS
)


