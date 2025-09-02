package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.HuhuChannel
import com.faselhd.app.models.SLiveTv
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HuhuSource(private val context: Context) {

    val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://huhu.to"
    private var cachedChannels: List<HuhuChannel> = emptyList()
    private val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Huhu/tv.png"

    // Fetches and caches all channels from the API
    private suspend fun getAllChannels(): List<HuhuChannel> {
        if (cachedChannels.isNotEmpty()) {
            return cachedChannels
        }
        return try {
            val request = Request.Builder().url("$baseUrl/channels").build()
            val response = client.newCall(request).execute().body!!.string()
            cachedChannels = json.decodeFromString(response)
            cachedChannels
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Fetches all channels and groups them by country for the main screen
    suspend fun fetchAllChannelsByCountry(): Map<String, List<SLiveTv>> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .map { channelToSLiveTv(it) }
            .groupBy { it.country ?: "Uncategorized" }
            .toSortedMap() // Sort countries alphabetically
    }

    // Searches for channels based on a query
    suspend fun search(query: String): List<SLiveTv> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { channelToSLiveTv(it) }
    }

    // Converts the API's Channel model to your app's UI model
    private fun channelToSLiveTv(channel: HuhuChannel): SLiveTv {
        return SLiveTv().apply {
            title = channel.name
            // Serialize the entire channel object into the URL for easy access later
            url = json.encodeToString(HuhuChannel.serializer(), channel)
            posterUrl = this@HuhuSource.posterUrl
            country = channel.country
            source = AnimeSource.HUHU.name // Assuming you add HUHU to your enum
        }
    }

    // Gets the final M3U8 link for a selected channel
    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
        try {
            val channel = json.decodeFromString<HuhuChannel>(channelJson)
            val streamUrl = "$baseUrl/play/${channel.id}/index.m3u8"
            // You can perform a check here to see if the link is valid if needed
            // For now, we assume it's always valid.
            return@withContext Video(
                url = streamUrl,
                quality = "Live",
                videoUrl = streamUrl,
                headers = mapOf("Referer" to "$baseUrl/")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}