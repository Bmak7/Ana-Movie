import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
// Import Gson library components
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VoeExtractor(private val client: OkHttpClient) {

    /**
     * Fetches a VOE (jilliandescribecompany) URL and extracts the video link.
     * @param url The embed URL (e.g., "https://jilliandescribecompany.com/e/2zgeenou7utp").
     * @return A list of Video objects containing the decoded HLS stream.
     */
    fun videosFromUrl(url: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", "https://jilliandescribecompany.com/")
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

                val videoUrl = extractVideoUrlFromHtml(htmlContent) ?: return emptyList()

                return listOf(
                    Video(
                        url = videoUrl,
                        quality = "HLS",
                        videoUrl = videoUrl,
                        resolution = "Auto",
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
     * Finds the obfuscated JSON data in the HTML and runs the decoding process.
     */
    private fun extractVideoUrlFromHtml(htmlCode: String): String? {
        val obfuscatedJsonRegex = Regex("""<script type="application/json">(\[".+?"\])</script>""", RegexOption.DOT_MATCHES_ALL)
        val match = obfuscatedJsonRegex.find(htmlCode)

        if (match != null && match.groupValues.size > 1) {
            try {
                // The regex captures the full JSON array string '["..."]'
                val jsonArrayString = match.groupValues[1]

                // --- Use Gson to parse the initial array ---
                val listType = object : TypeToken<List<String>>() {}.type
                val obfuscatedList: List<String> = Gson().fromJson(jsonArrayString, listType)
                val obfuscatedString = obfuscatedList.firstOrNull() ?: return null

                println("2. Found and extracted obfuscated data string.")
                println("3. Decoding the data to find the video source...")

                // This now returns a Map<String, Any?>
                val decodedData = decodeVoeSource(obfuscatedString)
                // Safely access the 'source' key from the map
                return decodedData["source"] as? String
            } catch (e: Exception) {
                println("Error parsing or decoding the obfuscated JSON: ${e.message}")
                return null
            }
        }

        println("Error: Could not find the obfuscated JSON data block.")
        return null
    }

    /**
     * A Kotlin port of the VOE multi-step decoding algorithm, now using Gson.
     * Returns a Map instead of a JSONObject.
     */
    private fun decodeVoeSource(obfuscatedString: String): Map<String, Any?> {
        try {
            // Step 1: ROT13 decode
            var text = obfuscatedString.map { char ->
                when {
                    char in 'a'..'z' -> 'a' + (char - 'a' + 13) % 26
                    char in 'A'..'Z' -> 'A' + (char - 'A' + 13) % 26
                    else -> char
                }
            }.joinToString("")

            // Step 2-7 remain the same...
            val symbols = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
            symbols.forEach { symbol -> text = text.replace(symbol, "_") }
            text = text.replace("_", "")
            text = String(Base64.getDecoder().decode(text), Charsets.UTF_8)
            text = text.map { chr -> chr - 3 }.joinToString("")
            text = text.reversed()
            text = String(Base64.getDecoder().decode(text), Charsets.UTF_8)

            // --- Step 8: Parse the final JSON string using Gson ---
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            return Gson().fromJson(text, mapType)
        } catch (e: Exception) {
            println("Decoding failed: ${e.message}")
            return emptyMap()
        }
    }
}



