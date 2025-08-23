package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class StreamTapeExtractor(private val client: OkHttpClient) {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

    fun videosFromUrl(url: String, quality: String = "Streamtape"): List<Video> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://streamtape.com/")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val document = Jsoup.parse(body)

            // Method 1: Try to extract from hidden divs first
            val directUrl = extractFromHiddenDivs(document)
            if (directUrl.isNotEmpty()) {
                val finalUrl = getFinalVideoUrl(directUrl)
                if (finalUrl.isNotEmpty()) {
                    return listOf(Video(finalUrl, quality, finalUrl))
                }
            }

            // Method 2: Try to extract from JavaScript manipulation
            val jsUrl = extractFromJavaScript(document)
            if (jsUrl.isNotEmpty()) {
                val finalUrl = getFinalVideoUrl(jsUrl)
                if (finalUrl.isNotEmpty()) {
                    return listOf(Video(finalUrl, quality, finalUrl))
                }
            }

            // Method 3: Try regex patterns for common obfuscation
            val regexUrl = extractWithRegex(body)
            if (regexUrl.isNotEmpty()) {
                val finalUrl = getFinalVideoUrl(regexUrl)
                if (finalUrl.isNotEmpty()) {
                    return listOf(Video(finalUrl, quality, finalUrl))
                }
            }

            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractFromHiddenDivs(document: org.jsoup.nodes.Document): String {
        // Check for hidden divs that might contain the URL
        val hiddenDivs = listOf("ideoolink", "botlink", "robotlink")

        for (divId in hiddenDivs) {
            val element = document.getElementById(divId)
            if (element != null) {
                val urlText = element.text()
                if (urlText.contains("get_video")) {
                    return if (urlText.startsWith("/")) {
                        "https:$urlText"
                    } else {
                        urlText
                    }
                }
            }
        }
        return ""
    }

    private fun extractFromJavaScript(document: org.jsoup.nodes.Document): String {
        val scripts = document.select("script")

        for (script in scripts) {
            val scriptData = script.data()

            // Look for the pattern where URL is being constructed
            if (scriptData.contains("getElementById") && scriptData.contains("innerHTML")) {

                // Handle complex patterns like:
                // innerHTML = '//stream'+ ('xcdtape.com/get_video?...').substring(2).substring(1);
                val complexPattern = Pattern.compile("innerHTML = '([^']*)'\\s*\\+\\s*\\('([^']*)'\\)\\.substring\\((\\d+)\\)(?:\\.substring\\((\\d+)\\))?")
                val complexMatcher = complexPattern.matcher(scriptData)

                if (complexMatcher.find()) {
                    val firstPart = complexMatcher.group(1) ?: ""
                    val secondPart = complexMatcher.group(2) ?: ""
                    val substring1 = complexMatcher.group(3)?.toIntOrNull() ?: 0
                    val substring2 = complexMatcher.group(4)?.toIntOrNull() ?: 0

                    var processedSecondPart = secondPart
                    if (substring1 > 0 && substring1 < processedSecondPart.length) {
                        processedSecondPart = processedSecondPart.substring(substring1)
                    }
                    if (substring2 > 0 && substring2 < processedSecondPart.length) {
                        processedSecondPart = processedSecondPart.substring(substring2)
                    }

                    val url = "https:$firstPart$processedSecondPart"
                    if (url.contains("get_video")) {
                        return url
                    }
                }

                // Handle simpler patterns like:
                // innerHTML = '//streamtape.com/get_video?...'
                val simplePattern = Pattern.compile("innerHTML = '([^']*get_video[^']*)'")
                val simpleMatcher = simplePattern.matcher(scriptData)

                if (simpleMatcher.find()) {
                    val url = simpleMatcher.group(1) ?: ""
                    return if (url.startsWith("//")) {
                        "https:$url"
                    } else url
                }
            }
        }
        return ""
    }

    private fun extractWithRegex(body: String): String {
        // Try to find URLs in various formats
        val patterns = listOf(
            // Direct streamtape URLs with get_video
            Pattern.compile("(https://[^\\s\"'<>]*streamtape[^\\s\"'<>]*get_video[^\\s\"'<>]*)"),
            Pattern.compile("\"(//[^\"]*streamtape[^\"]*get_video[^\"]*?)\""),
            Pattern.compile("'(//[^']*streamtape[^']*get_video[^']*?)'"),

            // Look for the get_video parameters directly
            Pattern.compile("(/streamtape\\.com/get_video\\?[^\\s\"'<>]*)"),
            Pattern.compile("(get_video\\?id=[a-zA-Z0-9]+&expires=[0-9]+&ip=[a-zA-Z0-9]+&token=[a-zA-Z0-9]+)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(body)
            if (matcher.find()) {
                val url = matcher.group(1) ?: continue

                val finalUrl = when {
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/streamtape.com") -> "https:$url"
                    url.startsWith("get_video") -> "https://streamtape.com/$url"
                    url.startsWith("http") -> url
                    else -> continue
                }

                if (finalUrl.contains("get_video")) {
                    return finalUrl
                }
            }
        }

        return ""
    }

    private fun getFinalVideoUrl(streamtapeUrl: String): String {
        return try {
            // Fix the URL if it's missing colon after https
            val correctedUrl = if (streamtapeUrl.startsWith("https/")) {
                streamtapeUrl.replace("https/", "https://")
            } else streamtapeUrl

            println("DEBUG: Attempting to get final URL from: $correctedUrl")

            val request = Request.Builder()
                .url(correctedUrl)
                .header("User-Agent", userAgent)
                .header("Referer", "https://streamtape.com/")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            val response = client.newCall(request).execute()

            // Check if we got redirected
            val finalUrl = response.request.url.toString()

            if (finalUrl != correctedUrl && finalUrl.contains(".mp4")) {
                println("DEBUG: Got redirected to final video URL: $finalUrl")
                return finalUrl
            }

            // If no redirect, check the response body for direct links
            val responseBody = response.body?.string() ?: ""

            // Look for direct video URLs in the response
            val directUrlPatterns = listOf(
                Pattern.compile("(https://[^\\s\"'<>]*\\.tapecontent\\.net/[^\\s\"'<>]*\\.mp4[^\\s\"'<>]*)"),
                Pattern.compile("\"(https://[^\"]*\\.mp4[^\"]*)\""),
                Pattern.compile("'(https://[^']*\\.mp4[^']*)'"),
                Pattern.compile("src=\"([^\"]*\\.mp4[^\"]*)\""),
                Pattern.compile("url:\"([^\"]*\\.mp4[^\"]*)\"")
            )

            for (pattern in directUrlPatterns) {
                val matcher = pattern.matcher(responseBody)
                if (matcher.find()) {
                    val videoUrl = matcher.group(1) ?: continue
                    if (videoUrl.contains(".mp4")) {
                        println("DEBUG: Found direct video URL in response: $videoUrl")
                        return videoUrl
                    }
                }
            }

            println("DEBUG: No final video URL found")
            return ""

        } catch (e: Exception) {
            println("ERROR getting final video URL: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }
}