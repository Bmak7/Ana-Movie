// Add your project's package statement here
package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video
import com.faselhd.app.utils.PlaylistUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException


class MivalyoExtractor(private val client: OkHttpClient) {



    private val playlistUtils by lazy { PlaylistUtils(client) }
    /**
     * Fetches a Mivalyo URL and extracts video links.
     * @param url The Mivalyo embed URL (e.g., "https://mivalyo.com/v/3g9ziave38og").
     * @return A list of Video objects, typically containing one HLS stream.
     */
    fun videosFromUrl(url: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", url)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Error: Unsuccessful response (Code: ${response.code})")
                response.close()
                return emptyList()
            }

            val htmlContent = response.body?.string() ?: return emptyList()
            response.close()

            val videoUrl = extractVideoUrlFromHtml(htmlContent) ?: return emptyList()

            // Mivalyo typically provides a single HLS master playlist.
            return playlistUtils.extractFromHls(videoUrl)

//            return listOf(
//                Video(
//                    url = videoUrl,
//                    quality = "HLS",
//                    videoUrl = videoUrl,
//                    resolution = "Auto", // Quality is determined by the HLS player
//                    headers = mapOf(
//                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
//                        "Referer" to url
//                    ),
//                    subtitles = null // Subtitles are usually embedded in the HLS stream
//                )
//            )

        } catch (e: IOException) {
            println("Error fetching or parsing video: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Main extraction logic. Finds the packed script, de-obfuscates it, and finds the video URL.
     */
    private fun extractVideoUrlFromHtml(htmlCode: String): String? {
        // Regex to find the entire packer block and capture its arguments in one step.
        val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packerMatch = packerRegex.find(htmlCode)

        if (packerMatch != null && packerMatch.groupValues.size == 5) {
            val p = packerMatch.groupValues[1]
            val a = packerMatch.groupValues[2].toInt()
            val c = packerMatch.groupValues[3].toInt()
            val k = packerMatch.groupValues[4]

            val deobfuscatedCode = deobfuscatePackedJs(p, a, c, k)

            // A more robust regex to find the M3U8 URL anywhere in the resulting script.
            val m3u8Regex = Regex("""(https?://[^\'"]+\.m3u8[^\'"]*)""")
            val m3u8Match = m3u8Regex.find(deobfuscatedCode)

            return m3u8Match?.groupValues?.get(1) // Return the captured URL
        }

        println("Could not find or parse the packed JavaScript in the HTML.")
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

