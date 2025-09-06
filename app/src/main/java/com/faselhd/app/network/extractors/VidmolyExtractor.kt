package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// --- Placeholder Models ---
// Ensure these data classes exist in your project.
class VidmolyExtractor(private val client: OkHttpClient) {

    /**
     * Fetches a Vidmoly URL and extracts the video link.
     * @param url The Vidmoly embed URL (e.g., "https://vidmoly.net/embed-vji0nk376ovl.html").
     * @return A list of Video objects, typically containing one HLS stream.
     */
    fun videosFromUrl(url: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", "https://vidmoly.net/")
                .build()

            // The 'use' block ensures the response is closed automatically
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Error: Unsuccessful response (Code: ${response.code})")
                    return emptyList()
                }

                val htmlContent = response.body?.string()
                if (htmlContent.isNullOrEmpty()) {
                    println("Error: Response body was empty.")
                    return emptyList()
                }

                // Extract the URL directly from the HTML
                val videoUrl = extractVideoUrlFromHtml(htmlContent) ?: return emptyList()

                // Vidmoly typically provides a single HLS master playlist.
                return listOf(
                    Video(
                        url = videoUrl,
                        quality = "HLS",
                        videoUrl = videoUrl,
                        resolution = "Auto", // Quality is determined by the HLS player
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                            "Referer" to url
                        ),
                        subtitles = null
                    )
                )
            }

        } catch (e: IOException) {
            println("Error fetching or parsing video: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Main extraction logic. Finds the JWPlayer setup script and extracts the video URL.
     * This extractor does NOT need a de-obfuscation function.
     */
    private fun extractVideoUrlFromHtml(htmlCode: String): String? {
        // This regex looks for the 'sources: [{file:"<URL>"}]' pattern inside the script.
        val playerRegex = Regex("""sources:\s*\[\{file:"(.*?m3u8.*?)"""")
        val match = playerRegex.find(htmlCode)

        // The URL is in the first captured group (index 1).
        // groupValues[0] is the full match.
        return if (match != null && match.groupValues.size > 1) {
            match.groupValues[1]
        } else {
            println("Could not find the video source URL in the page script.")
            null
        }
    }
}


// You can remove this when integrating into your Android app.


