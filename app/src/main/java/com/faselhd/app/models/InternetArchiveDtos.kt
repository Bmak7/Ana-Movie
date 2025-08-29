package com.faselhd.app.models

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.math.roundToInt

// DTOs for parsing the search results from the API
data class InternetArchiveSearchResult(
    val response: DocsResponse
)

data class DocsResponse(
    val docs: List<SearchEntry>
)

data class SearchEntry(
    val identifier: String,
    val mediatype: String,
    val title: String?
)

// DTOs for parsing the detailed metadata of a single item
data class MetadataResult(
    val metadata: MediaEntry,
    val files: List<MediaFile>,
    val dir: String,
    val server: String
)

data class MediaEntry(
    val identifier: String,
    val mediatype: String,
    val title: String?,
    val description: String?,
    val subject: List<String>?,
    val creator: List<String>?,
    val date: String?
)

data class MediaFile(
    val name: String,
    val format: String,
    val title: String?,
    val original: String?,
    val length: String?,
    val size: String?, // API can return size as string
    val height: String? // API can return height as string
) {
    // Lazy property to calculate length in seconds from various formats
    val lengthInSeconds: Float by lazy {
        length?.toFloatOrNull() ?: run {
            if (length?.contains(":") == true) {
                val parts = length.split(":")
                when (parts.count()) {
                    2 -> (parts[0].toFloatOrNull() ?: 0f) * 60 + (parts[1].toFloatOrNull() ?: 0f)
                    3 -> (parts[0].toFloatOrNull() ?: 0f) * 3600 + (parts[1].toFloatOrNull() ?: 0f) * 60 + (parts[2].toFloatOrNull() ?: 0f)
                    else -> 0f
                }
            } else 0f
        }
    }
}

// Data class for storing video link information to pass to the player
data class VideoLinkData(
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String
)