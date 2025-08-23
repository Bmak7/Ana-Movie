// In file: app/src/main/java/com/faselhd/app/network/extractors/BigWarpExtractor.kt
package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class BigWarpExtractor(private val client: OkHttpClient) {

    /**
     * Extracts video links from a BigWarp.io embed page.
     *
     * @param url The embed URL from BigWarp.
     * @param quality The quality label to be used for the video.
     * @return A list containing the found Video, or an empty list on failure.
     */
    fun videosFromUrl(url: String, quality: String = "BigWarp"): List<Video> {
        return try {
            // 1. Fetch the HTML content of the page
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val document = Jsoup.parse(response.body!!.string())

            // 2. Concatenate the content of all script tags into a single string
            val scriptsContent = document.select("script").joinToString("\n") { it.data() }

            // 3. Use Regex to find the direct video URL
            val matcher = VIDEO_URL_REGEX.matcher(scriptsContent)
            if (!matcher.find()) {
                return emptyList() // Return empty if no match is found
            }

            val videoUrl = matcher.group(0) // group(0) is the entire matched string
                ?: return emptyList()

            // 4. Return the found video link in the project's Video model
            listOf(Video(videoUrl, quality, videoUrl))

        } catch (e: Exception) {
            // If any error occurs, print it for debugging and return an empty list
            e.printStackTrace()
            emptyList()
        }
    }

    companion object {
        // The regex pattern translated from the Dart code
        private val VIDEO_URL_REGEX = Pattern.compile(
            """https://(?:fs\d+\.)?bigwarp\.io/v/[\w/]+\.mp4\?[^\s"]+"""
        )
    }
}