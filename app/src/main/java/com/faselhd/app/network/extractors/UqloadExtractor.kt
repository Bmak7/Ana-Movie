// In file: app/src/main/java/com/faselhd/app/network/extractors/UqloadExtractor.kt

package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video // CHANGED: Import your app's Video model
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request // CHANGED: Using standard OkHttp Request
import org.jsoup.Jsoup

class UqloadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return runCatching {
            // CHANGED: Using standard OkHttp and Jsoup instead of custom helpers
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val doc = Jsoup.parse(response.body!!.string())

            val script = doc.selectFirst("script:containsData(sources:)")?.data()
                ?: return@runCatching emptyList()

            val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
                .takeIf(String::isNotBlank)
                ?.takeIf { it.startsWith("http") }
                ?: return@runCatching emptyList()

            val quality = if (prefix.isNotBlank()) "$prefix Uqload" else "Uqload"
//            val videoHeaders = Headers.headersOf("Referer", "https://uqload.net/")
            val videoHeaders = mapOf("Referer" to "https://uqload.net/")
            // CHANGED: Using your app's Video model constructor
            val video = Video(videoUrl, quality, videoUrl, resolution = "Uqload", headers = videoHeaders)
            listOf(video)
        }.getOrElse { emptyList() }
    }
}