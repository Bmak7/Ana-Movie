package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI

class DriveseedExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<Video> {
        try {
            val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
            val quality = document.selectFirst("li.list-group-item:contains(Name)")?.text() ?: "Default"

            // "Instant Download" is usually the most reliable link
            val instantUrl = document.selectFirst("a:contains(Instant Download)")?.attr("href")
            if (instantUrl != null) {
                val finalLink = instantLink(instantUrl)
                if (finalLink != null) {
                    return listOf(Video(finalLink, quality, finalLink))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    private fun instantLink(url: String): String? {
        return try {
            val host = URI(url).host ?: "video-seed.pro"
            val token = url.substringAfter("url=")
            val response = client.newCall(
                Request.Builder()
                    .url("https://$host/api")
                    .post(okhttp3.FormBody.Builder().add("keys", token).build())
                    .header("x-token", host)
                    .header("Referer", url)
                    .build()
            ).execute().body!!.string()

            response.substringAfter("url\":\"").substringBefore("\"").replace("\\/", "/")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}