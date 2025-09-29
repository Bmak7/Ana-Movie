package com.faselhd.app.models // Or your actual models package

import com.google.gson.annotations.SerializedName

// For the /home endpoint
data class CartoonyHomeResponse(
    val slider: List<CartoonyShow>?,
    @SerializedName("latest_episodes") val latestEpisodes: List<CartoonyEpisode>?,
    @SerializedName("new_shows") val newShows: List<CartoonyShow>?,
    @SerializedName("most_watched") val mostWatched: List<CartoonyShow>?
)

// For show/movie details from /shows/{id} and search results
data class CartoonyShow(
    val id: Int,
    val title: String,
    val description: String?,
    @SerializedName("poster_url") val posterUrl: String?,
    val type: String?, // e.g., "مسلسل" or "فيلم"
    val year: Int?,
    val episodes: List<CartoonyEpisode>? // Included in the details endpoint
)

// For episode details and lists
data class CartoonyEpisode(
    val id: Int,
    @SerializedName("show_id") val showId: Int?,
    @SerializedName("show_title") val showTitle: String?,
    @SerializedName("episode_number") val episodeNumber: Int?,
    val title: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("video_url") val videoUrl: String? // Only present in the /episodes/{id} response
)

// For the /search endpoint
data class CartoonySearchResponse(
    val shows: List<CartoonyShow>?
)