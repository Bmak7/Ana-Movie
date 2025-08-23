package com.faselhd.app.network.extractors

import android.util.Log
import com.faselhd.app.models.Video
import dev.datlag.jsunpacker.JsUnpacker
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class MixDropExtractor(private val client: OkHttpClient) {

    private val TAG = "MixDropExtractor"

    fun videosFromUrl(url: String, prefix: String = "MixDrop"): List<Video> {
        Log.d(TAG, "videosFromUrl called with url: $url, prefix: $prefix")
        return try {
            Log.d(TAG, "Making request to MixDrop URL")
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", "https://mixdrop.co/")
                .build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response code: ${response.code}")

            val html = response.body!!.string()
            Log.v(TAG, "HTML response length: ${html.length} chars")

            val document = Jsoup.parse(html)
            Log.d(TAG, "Document parsed successfully")

            // Look for packed JavaScript - try multiple common patterns
            var script = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
            if (script == null) {
                script = document.selectFirst("script:containsData(eval(function(p,a,c,k,e,r)))")?.data()
            }
            if (script == null) {
                script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            }

            if (script == null) {
                Log.d(TAG, "No packed JavaScript found in the document")
                // Try to find unpacked script with wurl pattern
                val allScripts = document.select("script")
                for (scriptElement in allScripts) {
                    val scriptContent = scriptElement.data()
                    if (scriptContent.contains("wurl") || scriptContent.contains("MDCore")) {
                        Log.d(TAG, "Found script with wurl/MDCore pattern")
                        return extractFromUnpackedScript(scriptContent, prefix, url)
                    }
                }
                return emptyList()
            }

            Log.d(TAG, "Found packed JavaScript, length: ${script.length} chars")
            Log.v(TAG, "Packed script snippet: ${script.take(100)}...")

            val unpackedScript = JsUnpacker.unpackAndCombine(script)
            if (unpackedScript == null) {
                Log.d(TAG, "Failed to unpack JavaScript")
                return emptyList()
            }

            Log.d(TAG, "JavaScript unpacked successfully, length: ${unpackedScript.length} chars")
            Log.v(TAG, "Unpacked script snippet: ${unpackedScript.take(200)}...")

            return extractFromUnpackedScript(unpackedScript, prefix, url)

        } catch (e: Exception) {
            Log.e(TAG, "Error in videosFromUrl: ${e.message}", e)
            emptyList()
        }
    }

    private fun extractFromUnpackedScript(script: String, prefix: String, originalUrl: String): List<Video> {
        Log.d(TAG, "Attempting to extract video URL from unpacked script")

        // Try the CloudStream3 pattern first (most reliable)
        val srcMatcher = SRC_REGEX.matcher(script)
        if (srcMatcher.find()) {
            val videoUrl = srcMatcher.group(1)
            if (videoUrl != null) {
                Log.d(TAG, "Found video URL with SRC_REGEX: $videoUrl")
                return createVideoList(videoUrl, prefix, originalUrl)
            }
        }

        // Try the original MDCore pattern
        val mdMatcher = MD_URL_REGEX.matcher(script)
        if (mdMatcher.find()) {
            Log.d(TAG, "URL pattern matched with MD_URL_REGEX")
            val group1 = mdMatcher.group(1)
            val group2 = mdMatcher.group(2)
            Log.d(TAG, "Regex groups - group1: '$group1', group2: '$group2'")

            if (group2 != null) {
                val videoUrl = (group1 ?: "https") + ":" + group2
                Log.d(TAG, "Constructed video URL: $videoUrl")
                return createVideoList(videoUrl, prefix, originalUrl)
            }
        }

        // Try alternative patterns
        val altPatterns = listOf(
            Pattern.compile("""wurl\s*=\s*["`']([^"`']+)["`']"""),
            Pattern.compile("""wurl\s*\+=\s*["`']([^"`']+)["`']"""),
            Pattern.compile("""MDCore\.wurl\s*=\s*["`']([^"`']+)["`']"""),
            Pattern.compile("""src\s*[:=]\s*["`']([^"`']+\.mp4[^"`']*)["`']"""),
            Pattern.compile("""file\s*[:=]\s*["`']([^"`']+\.mp4[^"`']*)["`']""")
        )

        for (pattern in altPatterns) {
            val matcher = pattern.matcher(script)
            if (matcher.find()) {
                val videoUrl = matcher.group(1)
                if (videoUrl != null && (videoUrl.startsWith("http") || videoUrl.startsWith("//"))) {
                    Log.d(TAG, "Found video URL with alternative pattern: $videoUrl")
                    return createVideoList(videoUrl, prefix, originalUrl)
                }
            }
        }

        Log.d(TAG, "No URL match found with any pattern")
        Log.v(TAG, "Script content for debugging:\n${script.take(1000)}...")
        return emptyList()
    }

    private fun createVideoList(videoUrl: String, prefix: String, originalUrl: String): List<Video> {
        val finalUrl = if (videoUrl.startsWith("//")) {
            "https:$videoUrl"
        } else if (!videoUrl.startsWith("http")) {
            "https:$videoUrl"
        } else {
            videoUrl
        }

        val quality = when {
            finalUrl.contains("1080") -> "1080p"
            finalUrl.contains("720") -> "720p"
            finalUrl.contains("480") -> "480p"
            finalUrl.contains("360") -> "360p"
            else -> "HD"
        }

        Log.d(TAG, "Final video URL: $finalUrl")
        Log.d(TAG, "Detected quality: $quality")

        val referer = try {
            val parsedUri = java.net.URI(originalUrl)
            "${parsedUri.scheme}://${parsedUri.host}/"
        } catch (e: Exception) {
            "https://mixdrop.co/" // Fallback to the most common one
        }

        // Enhanced headers for better compatibility
        val videoHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to referer,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "identity", // Important: avoid compression for video streams
            "Range" to "bytes=0-", // Enable range requests for better streaming
            "Connection" to "keep-alive"
        )
        Log.d(TAG, "Created video headers: $videoHeaders")

        val video = Video(finalUrl, "$prefix $quality", finalUrl, headers = videoHeaders)
        Log.d(TAG, "Created video: ${video.quality}")

        return listOf(video)
    }

    companion object {
        // Primary regex based on CloudStream3 (most reliable)
        private val SRC_REGEX = Pattern.compile("""wurl.*?=.*?"(.*?)";""")

        // Original MDCore regex as fallback
        private val MD_URL_REGEX = Pattern.compile("""MDCore\.wurl="([^"]+)";.*?MDCore\.wurl="\+?"([^"]+)"""")
    }
}