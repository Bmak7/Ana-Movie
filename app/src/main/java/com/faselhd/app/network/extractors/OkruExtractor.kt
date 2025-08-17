// In file: app/src/main/java/com/faselhd/app/network/extractors/OkruExtractor.kt

package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video // CHANGED: Import your app's Video model
import com.faselhd.app.utils.PlaylistUtils // CHANGED: Import your app's PlaylistUtils
import okhttp3.OkHttpClient
import okhttp3.Request // CHANGED: Using standard OkHttp Request
import org.jsoup.Jsoup

class OkruExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return runCatching {
            // CHANGED: Using standard OkHttp and Jsoup instead of custom helpers
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val document = Jsoup.parse(response.body!!.string())

            val videoString = document.selectFirst("div[data-options]")
                ?.attr("data-options")
                ?: return emptyList()

            // CHANGED: Simplified to work with your PlaylistUtils
            when {
                "ondemandHls" in videoString -> {
                    val playlistUrl = videoString.extractLink("ondemandHls")
                    playlistUtils.extractFromHls(playlistUrl)
                }
                else -> videosFromJson(videoString, prefix)
            }
        }.getOrElse { emptyList() }
    }

    private fun String.addPrefix(prefix: String) =
        prefix.takeIf(String::isNotBlank)
            ?.let { "$prefix $this" }
            ?: this

    private fun String.extractLink(attr: String) =
        substringAfter("$attr\\\":\\\"")
            .substringBefore("\\\"")
            .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String, prefix: String = ""): List<Video> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val videoUrl = it.extractLink("url")
            val quality = it.substringBefore("\\\"")
            val videoQuality = "Okru:$quality".addPrefix(prefix)

            if (videoUrl.startsWith("https://")) {
                // CHANGED: Using your app's Video model constructor
                Video(videoUrl, videoQuality, videoUrl, resolution = quality)
            } else {
                null
            }
        }
    }
}