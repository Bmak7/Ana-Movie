import com.faselhd.app.models.Video
import com.faselhd.app.utils.PlaylistUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class StreamGHExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    fun videosFromUrl(url: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", url)
                .build()

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

                val videoUrls = extractVideoUrlsFromHtml(htmlContent)
                if (videoUrls.isEmpty()) {
                    println("No video URLs found in StreamGH response")
                    return emptyList()
                }

                println("Found ${videoUrls.size} video URLs from StreamGH: $videoUrls")
                return playlistUtils.extractFromHls(videoUrls[0])
//                return videoUrls.mapIndexed { index, videoUrl ->
//                    Video(
//                        url = videoUrl,
//                        quality = "Stream ${index + 1}",
//                        videoUrl = videoUrl,
//                        resolution = "Auto",
//                        headers = mapOf(
//                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
//                            "Referer" to url
//                        ),
//                        subtitles = null
//                    )
//                }
            }
        } catch (e: IOException) {
            println("Error fetching video from StreamGH: ${e.message}")
            return emptyList()
        } catch (e: Exception) {
            println("Unexpected error in StreamGH extractor: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Finds the packed script in the HTML, de-obfuscates it, and extracts all video URLs.
     * Based on the proven working implementation.
     */
    private fun extractVideoUrlsFromHtml(htmlCode: String): List<String> {
        try {
            // Regex to find the packer function and capture its arguments - exact same as working code
            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            val packerMatch = packerRegex.find(htmlCode)

            if (packerMatch == null || packerMatch.groupValues.size < 5) {
                println("Error: Could not find the packed JavaScript block in StreamGH.")
                return emptyList()
            }

            // groupValues[0] is the full match, captures start from index 1
            val p = packerMatch.groupValues[1]
            val a = packerMatch.groupValues[2].toInt()
            val c = packerMatch.groupValues[3].toInt()
            val k = packerMatch.groupValues[4]

            // De-obfuscate the JavaScript using the proven working method
            val unpackedJs = unpackJs(p, a, c, k)

            // Regex to find all valid M3U8 URLs within the entire unpacked script
            val m3u8Regex = Regex("""(https?://[^\'"]+\.m3u8[^\'"]*)""")

            // Use findAll to get all stream URLs and map the result to a list of strings
            val urls = m3u8Regex.findAll(unpackedJs)
                .map { it.groupValues[1] }
                .toList()

            if (urls.isEmpty()) {
                println("Error: Could not find any M3U8 URLs in the unpacked JavaScript from StreamGH.")
                return emptyList()
            }

            return urls

        } catch (e: Exception) {
            println("An unexpected error occurred during StreamGH extraction: ${e.message}")
            return emptyList()
        }
    }

    /**
     * De-obfuscates JavaScript that has been packed using the popular P,A,C,K,E,R format.
     * This is the exact same proven working implementation from your standalone code.
     *
     * @param p The packed code.
     * @param a The radix (base) for encoding.
     * @param c The number of words in the dictionary.
     * @param kString The string containing the dictionary words, separated by '|'.
     * @return The de-obfuscated JavaScript string.
     */
    private fun unpackJs(p: String, a: Int, c: Int, kString: String): String {
        val k = kString.split('|')
        var count = c
        val dictionary = mutableMapOf<String, String>()

        while (count-- > 0) {
            // In Kotlin, number.toString(radix) is the equivalent for base conversion
            val key = count.toString(a)
            val value = if (count < k.size && k[count].isNotEmpty()) k[count] else key
            dictionary[key] = value
        }

        // This regex finds all "word" characters (alphanumeric + underscore)
        val wordRegex = Regex("\\b\\w+\\b")

        // Use replace with a lambda to look up each word in the dictionary
        return wordRegex.replace(p) { matchResult ->
            dictionary[matchResult.value] ?: matchResult.value
        }
    }
}