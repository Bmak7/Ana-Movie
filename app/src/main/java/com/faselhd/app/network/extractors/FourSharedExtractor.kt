// In file: app/src/main/java/com/faselhd/app/network/extractors/FourSharedExtractor.kt

package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class FourSharedExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val doc = Jsoup.parse(response.body!!.string())

            // Find the video source URL from the <source> tag
            val videoUrl = doc.selectFirst("video source[src]")?.attr("src")
                ?.takeIf(String::isNotBlank)
                ?.takeIf { it.startsWith("http") }
                ?: return@runCatching emptyList()

            // Extract quality/title from social overlay plugin data or use default
            val title = doc.selectFirst("script:containsData(socialOverlay)")?.data()
                ?.let { script ->
                    script.substringAfter("title: \"").substringBefore('"')
                        .takeIf(String::isNotBlank)
                } ?: "4shared"

            val quality = if (prefix.isNotBlank()) "$prefix $title" else title
            val videoHeaders = mapOf("Referer" to "https://www.4shared.com/")

            val video = Video(videoUrl, quality, videoUrl, resolution = "4shared", headers = videoHeaders)
            listOf(video)
        }.getOrElse { emptyList() }
    }
}