package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class YourUploadExtractor(private val client: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Private data class to parse the JSON found in the script tag
    @Serializable
    private data class ResponseSource(
        @SerialName("file") val file: String,
    )

    /**
     * Extracts video links from a YourUpload page URL.
     * @param url The URL of the YourUpload page.
     * @param headers Optional headers to be used for the request.
     * @return A list of Video objects.
     */
    fun videosFromUrl(url: String, headers: Map<String, String> = emptyMap()): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }
            val request = requestBuilder.build()

            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            // Try to get quality from the page title, e.g., "My Video 720p"
            val quality = Regex("""(\d{3,4})p""").find(document.select("title").text())?.groupValues?.get(1) + "p"

            // Find the script containing the jwplayer options
            val script = document.select("script").firstOrNull { it.data().contains("var jwplayerOptions = {") }
            if (script != null) {
                // Extract the JSON-like string from the script
                val dataString = script.data()
                    .substringAfter("var jwplayerOptions = {")
                    .substringBefore(",\n") // Stop before the next JS property

                // Clean up the string to make it valid JSON
                val validJsonString = "{${
                    dataString
                        .replace("file", "\"file\"") // Add quotes around the key
                        .replace("'", "\"") // Replace single quotes with double quotes
                }}"

                // Parse the JSON to get the file URL
                val responseSource = json.decodeFromString<ResponseSource>(validJsonString)
                val videoUrl = responseSource.file

                if (videoUrl.isNotBlank()) {
                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = quality,
                            videoUrl = videoUrl,
                            headers = mapOf("Referer" to url) // YourUpload often requires a referer
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Log the error or handle it silently
            e.printStackTrace()
        }
        return videos
    }
}