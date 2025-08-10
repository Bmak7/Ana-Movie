package com.faselhd.app.utils

import com.faselhd.app.models.Track
import com.faselhd.app.models.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistUtils(private val client: OkHttpClient) {

    /**
     * Extracts videos from a .m3u8 file.
     *
     * @param playlistUrl the URL of the HLS playlist.
     * @param customHeaders Optional headers for the request.
     * @return A list of Video objects.
     */
    fun extractFromHls(playlistUrl: String, customHeaders: Headers? = null): List<Video> {
        return try {
            val request = Request.Builder().url(playlistUrl).apply {
                if (customHeaders != null) {
                    headers(customHeaders)
                }
            }.build()

            val responseBody = client.newCall(request).execute().body?.string() ?: ""

            if (PLAYLIST_SEPARATOR !in responseBody) {
                return listOf(Video(playlistUrl, "Video", playlistUrl,"1080x920"))
            }

            val masterPlaylistUrl = playlistUrl.toHttpUrl()
            val masterBaseUrl = masterPlaylistUrl.newBuilder()
                .removePathSegment(masterPlaylistUrl.pathSize - 1)
                .addPathSegment("")
                .query(null)
                .fragment(null)
                .build()
                .toString()

            val subtitleTracks = SUBTITLE_REGEX.findAll(responseBody).mapNotNull {
                val url = getAbsoluteUrl(it.groupValues[2], playlistUrl, masterBaseUrl) ?: return@mapNotNull null
                Track(url, it.groupValues[1])
            }.toList()

            responseBody.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull { stream ->
                val resolution = RESOLUTION_REGEX.find(stream)?.groupValues?.get(1) ?: ""
                val quality = if (resolution.isNotBlank()) "${resolution}p" else "Default"

                val streamUrl = stream.substringAfter("\n").substringBefore("\n").let {
                    getAbsoluteUrl(it, playlistUrl, masterBaseUrl)?.trim()
                } ?: return@mapNotNull null

                Video(
                    url = streamUrl,
                    quality = quality,
                    videoUrl = streamUrl,
                    resolution = "1080x920"
//                    headers = customHeaders,
//                    subtitleTracks = subtitleTracks
                )
            }.sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val host = playlistUrl.toHttpUrl().host
                val scheme = playlistUrl.toHttpUrl().scheme
                "$scheme://$host$url"
            }
            else -> masterBase + url
        }
    }

    companion object {
        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"
        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=\d{1,4}x(\d{1,4})""") }
    }
}