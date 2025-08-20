package com.faselhd.app.network.extractors // Or wherever you want to place it

import com.faselhd.app.models.Video // Make sure this points to YOUR Video model class
import dev.datlag.jsunpacker.JsUnpacker
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class Mp4uploadExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val headers = Headers.Builder()
            .add("Referer", REFERER)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()

        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string())

        // Find the packed JavaScript code
        val scriptElement = document.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")

        val script = if (scriptElement != null) {
            // If found, unpack it
            JsUnpacker.unpackAndCombine(scriptElement.data())
        } else {
            // Otherwise, look for the script that directly contains the video source
            document.selectFirst("script:containsData(player.src)")?.data()
        }

        // If no usable script is found, return an empty list
        if (script.isNullOrEmpty()) {
            return emptyList()
        }

        // Extract the video URL from the script content
        val videoUrl = script.substringAfter(".src(").substringBefore(")")
            .substringAfter("src:").substringAfter('"').substringBefore('"')

        // If the URL is empty, something went wrong
        if (videoUrl.isBlank()) {
            return emptyList()
        }

        // Try to find the resolution from the script
        val resolution = QUALITY_REGEX.find(script)?.groupValues?.get(1)?.let { "${it}p" } ?: "HD"
        val quality = if (prefix.isNotEmpty()) "$prefix - $resolution" else "Mp4upload - $resolution"
        val newHeader = mapOf("referer" to "https://mp4upload.com/")
        // Use your app's existing Video model
        // Note: The original code passed headers to the Video object. You can add that
        // if your video player needs special headers to play the content.
        return listOf(Video(url = videoUrl, quality = quality, videoUrl = videoUrl, headers = newHeader))
    }

    companion object {
        private val QUALITY_REGEX by lazy { """\WHEIGHT=(\d+)""".toRegex() }
        private const val REFERER = "https://mp4upload.com/"
    }
}

