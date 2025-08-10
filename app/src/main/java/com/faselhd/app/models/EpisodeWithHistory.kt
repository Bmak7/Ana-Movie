package com.faselhd.app.models

data class EpisodeWithHistory(
    val episode: SEpisode,
    val history: WatchHistory?,
    var isFetchingDownload: Boolean = false // <-- ADD THIS NEW FIELD
)