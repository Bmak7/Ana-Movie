package com.faselhd.app.models

import com.fasterxml.jackson.annotation.JsonProperty

// DTO for parsing the remote domains list

// DTOs for parsing the rich metadata from the Cinemeta API
data class CinemetaResponse(
    val meta: CinemetaMeta?
)

data class CinemetaMeta(
    val id: String?,
    @JsonProperty("imdb_id") val imdbId: String?,
    val type: String?,
    val poster: String?,
    val background: String?,
    val name: String?,
    val description: String?,
    val releaseInfo: String?,
    val cast: List<String>?,
    val videos: List<CinemetaEpisode>?
)

data class CinemetaEpisode(
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val thumbnail: String?,
    val overview: String?
)