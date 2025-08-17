// In file: app/src/main/java/com/faselhd/app/network/extractors/DoodExtractor.kt

package com.faselhd.app.network.extractors // CHANGED: Package name

import com.faselhd.app.models.Video // CHANGED: Import your app's Video model
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request // CHANGED: Using standard OkHttp Request
import java.net.URI

class DoodExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = runCatching {
            // CHANGED: Using standard OkHttp call instead of custom GET helper
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body!!.string()
            if (!content.contains("'/pass_md5/")) return@runCatching null

            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            val newQuality = listOfNotNull(
                quality,
                "Doodstream " + (extractedQuality ?: (if (redirect) "mirror" else "")),
            ).joinToString(" - ")

            val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return@runCatching null)
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()

            // CHANGED: Using standard OkHttp call
            val videoUrlStart = client.newCall(
                Request.Builder()
                    .url(md5)
                    .headers(Headers.headersOf("referer", newUrl))
                    .build()
            ).execute().body!!.string()

            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"

            // CHANGED: Using your app's Video model constructor
            Video(videoUrl, newQuality, videoUrl, resolution = extractedQuality ?: "Doodstream")
        }.getOrNull()

        return video?.let(::listOf) ?: emptyList()
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        // CHANGED: Using your app's name for the User-Agent
        add("User-Agent", "Ana Movie")
        add("Referer", "https://$host/")
    }.build()
}