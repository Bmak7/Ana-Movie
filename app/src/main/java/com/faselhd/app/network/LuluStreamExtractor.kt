package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

open class LuluStreamExtractor(
    private val client: OkHttpClient,
    private val mainUrl: String = "https://luluvid.com"
) {
    companion object {
        private const val NAME = "LuluStream"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"

        // Regex to find: file:"(URL)"
        private val FILE_LINK_REGEX = Pattern.compile("""file\s*:\s*"([^"]+)"""")
    }

    fun videosFromUrl(url: String, pageReferer: String? = null, quality: String = "1080p"): List<Video> {
        return try {
            println("LuluStream Extractor: Target URL -> $url (Page Referer: $pageReferer)")

            // Extract filecode from URL
            val filecode = extractFilecode(url)
            if (filecode.isEmpty()) {
                println("LuluStream Extractor: Could not extract filecode from URL: $url")
                return emptyList()
            }

            println("LuluStream Extractor: Extracted filecode -> $filecode")

            // Make POST request to get video URL
            val videoUrl = extractVideoUrl(filecode, pageReferer ?: url)
            if (videoUrl.isNotEmpty()) {
                listOf(
                    Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        headers = mapOf("Referer" to mainUrl)
                    )
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("LuluStream Extractor: Error during extraction for $url: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractFilecode(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val pathSegments = uri.path.split("/").filter { it.isNotEmpty() }
            pathSegments.lastOrNull() ?: ""
        } catch (e: Exception) {
            println("LuluStream Extractor: Invalid input URL format $url")
            ""
        }
    }

    private fun extractVideoUrl(filecode: String, referer: String): String {
        val postUrl = "$mainUrl/dl"
        println("LuluStream Extractor: POSTing to -> $postUrl")

        try {
            val formBody = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", filecode)
                .add("auto", "1")
                .add("referer", referer)
                .build()

            val request = Request.Builder()
                .url(postUrl)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referer)
                .header("Origin", mainUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(request).execute()
            println("LuluStream Extractor: POST request to $postUrl -> Status: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return ""
                return parseVideoUrlFromResponse(responseBody)
            } else {
                println("LuluStream Extractor: POST request to $postUrl failed. Status: ${response.code}")
            }
        } catch (e: Exception) {
            println("LuluStream Extractor: Error making POST request: ${e.message}")
            e.printStackTrace()
        }

        return ""
    }

    private fun parseVideoUrlFromResponse(responseBody: String): String {
        try {
            val document = Jsoup.parse(responseBody)

            // Find script containing "vplayer" or "file:"
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.data()
                if (scriptContent.contains("vplayer") || scriptContent.contains("file:")) {
                    val matcher = FILE_LINK_REGEX.matcher(scriptContent)
                    if (matcher.find()) {
                        val link = matcher.group(1) ?: continue
                        if (link.isNotEmpty()) {
                            val finalLink = httpsify(link)
                            println("LuluStream Extractor: Extracted video link -> $finalLink")
                            return finalLink
                        } else {
                            println("LuluStream Extractor: 'file:' regex matched but link is empty.")
                        }
                    } else {
                        println("LuluStream Extractor: 'file:' regex found no match in script content.")
                    }
                }
            }

            println("LuluStream Extractor: No script tag containing 'vplayer' or 'file:' found.")
        } catch (e: Exception) {
            println("LuluStream Extractor: Error parsing response: ${e.message}")
            e.printStackTrace()
        }

        return ""
    }

    private fun httpsify(url: String): String {
        return when {
            url.startsWith("//") -> {
                val mainUri = java.net.URI(mainUrl)
                "${mainUri.scheme}://${mainUri.host}$url"
            }
            url.startsWith("http") -> url
            else -> url
        }
    }
}

// Subclasses for different LuluStream domains
class LuluStream1Extractor(client: OkHttpClient) : LuluStreamExtractor(client, "https://lulustream.com") {
    fun videosFromUrl(url: String, pageReferer: String? = null): List<Video> {
        return super.videosFromUrl(url, pageReferer, "LuluStream1")
    }
}

class LuluStream2Extractor(client: OkHttpClient) : LuluStreamExtractor(client, "https://kinoger.pw") {
    fun videosFromUrl(url: String, pageReferer: String? = null): List<Video> {
        return super.videosFromUrl(url, pageReferer, "LuluStream2")
    }
}

class LuluVdoExtractor(client: OkHttpClient) : LuluStreamExtractor(client, "https://luluvdo.com") {
    fun videosFromUrl(url: String, pageReferer: String? = null): List<Video> {
        return super.videosFromUrl(url, pageReferer, "LuluVdo")
    }
}