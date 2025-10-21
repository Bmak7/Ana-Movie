package com.faselhd.app.network.extractors

import android.util.Log
import com.faselhd.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request

class GoodStreamExtractor(private val client: OkHttpClient) {

    private val TAG = "GoodStreamExtractor"
    // Regex to find the 'sources' array and capture the file URL within it
    private val sourceRegex = Regex("""sources:\s*\[\{file:"([^"]+)"\}\]""")

    fun videosFromUrl(url: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://goodstream.one/") // Good practice to include a referer
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (responseBody != null) {
                val match = sourceRegex.find(responseBody)
                val m3u8Url = match?.groupValues?.get(1)

                if (m3u8Url != null) {
                    Log.d(TAG, "Extracted M3U8 URL: $m3u8Url")
                    // The extracted URL is a master M3U8 playlist which allows for adaptive quality.
                    videos.add(
                        Video(
                            url = m3u8Url,
                            quality = "GoodStream (Auto)", // Quality is auto/adaptive
                            videoUrl = m3u8Url,
                            headers = mapOf("Referer" to "https://goodstream.one/")
                        )
                    )
                } else {
                    Log.w(TAG, "Could not find M3U8 source in response from $url")
                }
            } else {
                Log.w(TAG, "Response body was null for $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from GoodStream URL: $url", e)
        }
        return videos
    }
}