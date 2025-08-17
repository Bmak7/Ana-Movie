// In file: app/src/main/java/com/faselhd/app/network/extractors/VidBomExtractor.kt

package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video // CHANGED: Import your app's Video model
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request // CHANGED: Using standard OkHttp Request
import org.jsoup.Jsoup

class VidBomExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers? = null): List<Video> {
        return runCatching {
            val requestBuilder = Request.Builder().url(url)
            if (headers != null) {
                requestBuilder.headers(headers)
            }
            // CHANGED: Using standard OkHttp and Jsoup instead of custom helpers
            val response = client.newCall(requestBuilder.build()).execute()
            val doc = Jsoup.parse(response.body!!.string())

            val script = doc.selectFirst("script:containsData(sources)")?.data() ?: return@runCatching emptyList()
            val data = script.substringAfter("sources: [").substringBefore("],")

            data.split("file:\"").drop(1).map { source ->
                val src = source.substringBefore("\"")
                val qualityLabel = source.substringAfter("label:\"").substringBefore("\"")
                val quality = "Vidbom: $qualityLabel"

                // CHANGED: Using your app's Video model constructor
                Video(src, quality, src, resolution = qualityLabel)
            }
        }.getOrElse { emptyList() }
    }
}