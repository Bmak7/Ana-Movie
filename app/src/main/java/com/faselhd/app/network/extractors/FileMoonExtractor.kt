package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class FileMoonExtractor(private val client: OkHttpClient) {

    /**
     * Fetches a FileMoon URL, follows the iframe, and extracts the video link.
     * @param url The FileMoon embed URL (e.g., "https://filemoon.sx/e/602iw9q9iy2o").
     * @return A list of Video objects for the stream found.
     */
    fun videosFromUrl(url: String, qualityLabel: String): List<Video> {
        try {
            // --- Step 1 & 2: Fetch main page and find the iframe URL ---
            println("1. Fetching main page to find iframe...")
            val mainRequest = Request.Builder().url(url).build()
            val iframeUrl = client.newCall(mainRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Error: Failed to fetch main page (Code: ${response.code})")
                    return emptyList()
                }
                val html = response.body?.string() ?: return emptyList()
                extractIframeUrl(html)
            }

            if (iframeUrl == null) {
                println("Error: Could not find iframe URL on the main page.")
                return emptyList()
            }
            println("2. Found iframe URL: $iframeUrl")

            // --- Step 3: Fetch the iframe page content ---
            println("3. Fetching iframe content...")
            val playerRequest = Request.Builder()
                .url(iframeUrl)
                .addHeader("Referer", url) // Add a referer header, as this is often required
                .build()

            val videoUrl = client.newCall(playerRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Error: Failed to fetch iframe page (Code: ${response.code})")
                    return emptyList()
                }
                val playerHtml = response.body?.string() ?: return emptyList()
                extractVideoUrlFromPlayer(playerHtml)
            } ?: return emptyList()


            return listOf(
                Video(
                    url = videoUrl,
                    quality = "FileMoon $qualityLabel",
                    videoUrl = videoUrl,
                    resolution = "Auto",
                    headers = mapOf("Referer" to iframeUrl), // The most direct referer is the iframe page itself
                    subtitles = null
                )
            )

        } catch (e: IOException) {
            println("Error during network request: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Extracts the URL from the first iframe tag found in the HTML.
     */
    private fun extractIframeUrl(htmlCode: String): String? {
        val iframeRegex = Regex("""<iframe src="([^"]+)"""")
        val match = iframeRegex.find(htmlCode)
        return match?.groupValues?.get(1)
    }

    /**
     * Finds the packed script, de-obfuscates it, and extracts the video URL.
     */
    private fun extractVideoUrlFromPlayer(playerHtml: String): String? {
        val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packerMatch = packerRegex.find(playerHtml)

        if (packerMatch != null && packerMatch.groupValues.size == 5) {
            val p = packerMatch.groupValues[1]
            val a = packerMatch.groupValues[2].toInt()
            val c = packerMatch.groupValues[3].toInt()
            val k = packerMatch.groupValues[4]

            println("4. De-obfuscating player script...")
            val deobfuscatedCode = deobfuscatePackedJs(p, a, c, k)

            val sourceRegex = Regex("""sources:\s*\[\{file:"(.*?m3u8.*?)"""")
            val sourceMatch = sourceRegex.find(deobfuscatedCode)
            if (sourceMatch != null && sourceMatch.groupValues.size > 1) {
                println("5. Success! Found video URL.")
                return sourceMatch.groupValues[1]
            }
        }

        println("Error: Could not find or de-obfuscate the player script.")
        return null
    }

    /**
     * De-obfuscates the "packed" JavaScript code.
     */
    private fun deobfuscatePackedJs(p: String, a: Int, c: Int, kString: String): String {
        val k = kString.split('|')
        var count = c
        val dictionary = mutableMapOf<String, String>()

        while (count-- > 0) {
            val key = count.toString(a)
            val value = if (count < k.size && k[count].isNotEmpty()) k[count] else key
            dictionary[key] = value
        }

        val wordRegex = Regex("\\b\\w+\\b")
        return wordRegex.replace(p) { matchResult ->
            dictionary[matchResult.value] ?: matchResult.value
        }
    }
}

// --- Main function for testing ---
//fun main() {
//    // --- Placeholder Models for testing ---
//    data class Subtitle(val lang: String, val url: String)
//
//    val okHttpClient = OkHttpClient()
//    val extractor = FileMoonExtractor(okHttpClient)
//    val testUrl = "https://filemoon.sx/e/602iw9q9iy2o"
//
//    println("Attempting to extract videos from: $testUrl\n")
//    val videos = extractor.videosFromUrl(testUrl)
//
//    if (videos.isNotEmpty()) {
//        println("\n✅ Success! Extracted ${videos.size} video stream(s):")
//        videos.forEach { video ->
//            println("  - URL: ${video.url}")
//        }
//    } else {
//        println("\n❌ Failed to extract video streams.")
//    }
//}