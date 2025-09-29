// In network/extractors/MiraVDExtractor.kt
package com.faselhd.app.network.extractors

import android.util.Log
import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.regex.Pattern

class MiraVDExtractor(private val client: OkHttpClient) {

    /**
     * De-obfuscates the common "eval(function(p,a,c,k,e,d){...})" packer.
     * This version is robust enough to handle the `.split('|')` dictionary format.
     */
    private fun deobfuscatePackedJs(packedJs: String): String? {
        try {
            // Find the entire eval block
            val evalRegex = """eval\(function\(p,a,c,k,e,d\)\{.*?\}\((.*)\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val evalMatch = evalRegex.find(packedJs) ?: run {
                Log.e("MiraVDExtractor", "Could not find the main eval function block.")
                return null
            }
            val argsContent = evalMatch.groupValues[1]

            // THE FIX: This new regex correctly captures arguments when the dictionary uses .split('|')
            val argsRegex = """'((?:\\.|[^'])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'])*)'\.split\('\|'\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val argsMatch = argsRegex.find(argsContent) ?: run {
                Log.e("MiraVDExtractor", "Could not match the arguments for the deobfuscation function. The format may have changed.")
                return null
            }

            var payload = argsMatch.groupValues[1]
            val radix = argsMatch.groupValues[2].toIntOrNull() ?: return null
            var count = argsMatch.groupValues[3].toIntOrNull() ?: return null
            val dictionary = argsMatch.groupValues[4].split('|')

            // The core deobfuscation algorithm remains the same
            while (count-- > 0) {
                // toString(radix) is the JS equivalent of Java/Kotlin's Integer.toString(radix)
                val key = if (radix > 36) Base36.encode(count.toLong()) else count.toString(radix)
                val value = dictionary.getOrNull(count)
                if (!value.isNullOrBlank()) {
                    payload = payload.replace(Regex("\\b$key\\b"), value)
                }
            }
            return payload
        } catch (e: Exception) {
            Log.e("MiraVDExtractor", "An exception occurred during JS deobfuscation", e)
            return null
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
    suspend fun videosFromUrl(url: String): List<Video> {
        try {
            Log.d("MiraVDExtractor", "Extracting from URL: $url")
            val request = Request.Builder().url(url).header("Referer", "https://3esk.onl/").build()
            val response = client.newCall(request).execute()
            val htmlContent = response.body?.string() ?: ""

            // No need to log the full HTML anymore, but you can re-enable it for future debugging
            // Log.d("MiraVD-HTML", "---- START miravd.com HTML ----") ...

            val deobfuscatedJs = deobfuscatePackedJs(htmlContent)
            if (deobfuscatedJs.isNullOrBlank()) {
                Log.e("MiraVDExtractor", "Deobfuscation failed or returned an empty script.")
                return emptyList()
            }

            Log.d("MiraVD-DEOBFUSCATED", "---- Deobfuscated JS ----\n$deobfuscatedJs")

            // This regex remains the same, but it will now run on the clean, deobfuscated script
            val pattern = Pattern.compile("""sources:\s*\[\{\s*file:\s*"(https?://[^"]+\.m3u8[^"]*)""")
            val matcher = pattern.matcher(deobfuscatedJs)

            if (matcher.find()) {
                val videoUrl = matcher.group(1)!!
                Log.i("MiraVDExtractor", "SUCCESS: Found .m3u8 link: $videoUrl")
                return listOf(
                    Video(
                        url = videoUrl,
                        quality = "MiraVD - HD",
                        videoUrl = videoUrl,
                        headers = mapOf("Referer" to getBaseUrl(url))
                    )
                )
            } else {
                Log.e("MiraVDExtractor", "FAILED: Could not find m3u8 link pattern in the deobfuscated script.")
                return emptyList()
            }
        } catch (e: Exception) {
            Log.e("MiraVDExtractor", "A critical error occurred while extracting from miravd.com", e)
            return emptyList()
        }
    }

    // Helper object for encoding to any base, needed for the deobfuscator
    object Base36 {
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
        fun encode(num: Long): String {
            var n = num
            if (n == 0L) return "0"
            val sb = StringBuilder()
            while (n > 0) {
                sb.insert(0, ALPHABET[(n % 36).toInt()])
                n /= 36
            }
            return sb.toString()
        }
    }
}