// In file: app/src/main/java/com/faselhd/app/network/extractors/StreamWishExtractor.kt
package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import dev.datlag.jsunpacker.JsUnpacker
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class StreamWishExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = "StreamWish"): List<Video> {
        return try {
            val embedUrl = if (url.contains("/f/")) {
                "https://streamwish.com/e/" + url.substringAfterLast("/f/")
            } else {
                url
            }

            val doc = Jsoup.parse(client.newCall(Request.Builder().url(embedUrl).build()).execute().body!!.string())

            val scriptBody = doc.selectFirst("script:containsData(m3u8)")?.data()?.let { script ->
                if (script.contains("eval(function(p,a,c")) {
                    JsUnpacker.unpackAndCombine(script)
                } else {
                    script
                }
            } ?: return emptyList()

            val masterUrl = M3U8_REGEX.matcher(scriptBody).let {
                if (it.find()) it.group(1) else null
            } ?: return emptyList()

            // We return the master HLS playlist directly.
            // Your video player (like ExoPlayer) should handle HLS playlists natively.
            val videoHeaders = mapOf("Referer" to "https://${embedUrl.toHttpUrl().host}/")
            listOf(Video(masterUrl, prefix, masterUrl, headers = videoHeaders))
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    companion object {
        private val M3U8_REGEX = Pattern.compile("""(https[^"]*m3u8[^"]*)""")
    }
}