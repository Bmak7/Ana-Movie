package com.faselhd.app.utils

import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video

/**
 * Singleton object to hold large data for the VideoPlayerActivity.
 * This avoids the TransactionTooLargeException by preventing large objects
 * from being passed in the Intent bundle.
 */
object PlayerDataHolder {
    var anime: SAnime? = null
    var episodeList: List<SEpisode>? = null
    var videos: List<Video>? = null

    // Call this to clean up the data and prevent memory leaks
    fun clear() {
        anime = null
        episodeList = null
        videos = null
    }
}