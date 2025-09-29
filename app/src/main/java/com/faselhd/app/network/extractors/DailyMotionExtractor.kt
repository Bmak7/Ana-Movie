package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

class DailyMotionExtractor(private val client: OkHttpClient) {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

    // Regex to capture the video ID from various Dailymotion URL formats
    private val videoIdPattern = Pattern.compile("dailymotion\\.com(?:/video/|/embed/video/|/player/metadata/video/)([a-zA-Z0-9]+)")


    private fun extractVideoId(url: String): String? {
        val matcher = videoIdPattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    fun videosFromUrl(url: String): List<Video> {
        println("DEBUG: Starting Dailymotion API extraction for URL: $url")
        try {
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("ERROR: Could not extract Video ID from URL: $url")
                return emptyList()
            }
            println("DEBUG: Extracted Video ID: $videoId")

            val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            println("DEBUG: Constructed metadata API URL: $metadataUrl")

            val request = Request.Builder()
                .url(metadataUrl)
                .header("User-Agent", userAgent)
                // This Referer header is CRITICAL to bypass the 403 Forbidden error
                .header("Referer", url)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                println("ERROR: Failed to fetch video metadata. HTTP status: ${response.code} for URL: $metadataUrl")
                println("DEBUG: Response Body: ${response.body?.string()}")
                return emptyList()
            }

            val body = response.body?.string() ?: run {
                println("ERROR: Metadata response body is null.")
                return emptyList()
            }

            println("DEBUG: Successfully fetched metadata JSON. Parsing...")
            val jsonObj = JSONObject(body)

            // Log the error from the JSON if it exists, for better diagnostics
            if (jsonObj.has("error")) {
                val errorTitle = jsonObj.optJSONObject("error")?.optString("title", "Unknown Error")
                println("WARN: Dailymotion API returned an error: '$errorTitle'. Attempting to extract stream URL anyway.")
            }

            // The M3U8 link is consistently found in the 'ad_url' field
            val m3u8Url = jsonObj.optJSONObject("advertising")?.optString("ad_url")

            if (m3u8Url != null && m3u8Url.contains(".m3u8")) {
                println("SUCCESS: Extracted M3U8 URL: $m3u8Url")
                return listOf(Video(url = m3u8Url, quality = "Dailymotion HLS", videoUrl = m3u8Url))
            } else {
                println("ERROR: Could not find '.m3u8' URL in the metadata response.")
                println("DEBUG: Full JSON Response: ${jsonObj.toString(2)}")
                return emptyList()
            }

        } catch (e: Exception) {
            println("ERROR: An unexpected error occurred during API extraction: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}

