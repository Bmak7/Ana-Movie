package com.faselhd.app.network.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.SLiveTv
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FreeTVSource(private val context: Context) {

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

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                // Note that you need to add a ResponseInterceptor to make this 100% active.
                // The server response dictates if and when stuff should be cached.
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            // Needs to be build as otherwise the other builders will change this object
            .build()
    }
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    private var cachedChannels: List<FreeTVChannel> = emptyList()
    private val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/FreeTV/freetv.png"

    @Serializable
    data class FreeTVChannel(
        val title: String,
        val url: String,
        val logo: String = "",
        val country: String = "",
        val tvgId: String = ""
    )

    // Fetches and caches all channels from the M3U8 playlist
    private suspend fun getAllChannels(): List<FreeTVChannel> {
        if (cachedChannels.isNotEmpty()) {
            return cachedChannels
        }
        return try {
            val request = Request.Builder().url(baseUrl).build()
            val response = client.newCall(request).execute().body!!.string()
            val playlist = IptvPlaylistParser().parseM3U(response)

            cachedChannels = playlist.items.mapNotNull { item ->
                if (item.url != null && item.title != null) {
                    FreeTVChannel(
                        title = item.title,
                        url = item.url,
                        logo = item.attributes["tvg-logo"] ?: posterUrl,
                        country = item.attributes["group-title"] ?: "Unknown",
                        tvgId = item.attributes["tvg-id"] ?: ""
                    )
                } else null
            }
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
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.tvgId.contains(query, ignoreCase = true) ||
                        it.country.contains(query, ignoreCase = true)
            }
            .map { channelToSLiveTv(it) }
    }

    // Converts the FreeTVChannel to your app's UI model
    private fun channelToSLiveTv(channel: FreeTVChannel): SLiveTv {
        return SLiveTv().apply {
            title = channel.title
            // Serialize the entire channel object into the URL for easy access later
            url = json.encodeToString(FreeTVChannel.serializer(), channel)
            posterUrl = if (channel.logo.isNotEmpty()) channel.logo else this@FreeTVSource.posterUrl
            country = channel.country
            source = AnimeSource.FREE_TV.name
        }
    }

    // Gets the final stream link for a selected channel
    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
        try {
            val channel = json.decodeFromString<FreeTVChannel>(channelJson)
            return@withContext Video(
                url = channel.url,
                quality = "Live",
                videoUrl = channel.url,
                headers = mapOf("Referer" to baseUrl)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // M3U8 Playlist Parser - converted from the CloudStream version
    private data class Playlist(
        val items: List<PlaylistItem> = emptyList(),
    )

    private data class PlaylistItem(
        val title: String? = null,
        val attributes: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val url: String? = null,
        val userAgent: String? = null,
    )

    private class IptvPlaylistParser {

        fun parseM3U(content: String): Playlist {
            return parseM3U(content.byteInputStream())
        }

        fun parseM3U(input: InputStream): Playlist {
            val reader = input.bufferedReader()

            if (!reader.readLine().isExtendedM3u()) {
                throw Exception("Invalid M3U8 header")
            }

            val playlistItems: MutableList<PlaylistItem> = mutableListOf()
            var currentIndex = 0

            var line: String? = reader.readLine()

            while (line != null) {
                if (line.isNotEmpty()) {
                    if (line.startsWith(EXT_INF)) {
                        val title = line.getTitle()
                        val attributes = line.getAttributes()
                        playlistItems.add(PlaylistItem(title, attributes))
                    } else if (line.startsWith(EXT_VLC_OPT)) {
                        val item = playlistItems[currentIndex]
                        val userAgent = line.getTagValue("http-user-agent")
                        val referrer = line.getTagValue("http-referrer")
                        val headers = if (referrer != null) {
                            item.headers + mapOf("referrer" to referrer)
                        } else item.headers
                        playlistItems[currentIndex] =
                            item.copy(userAgent = userAgent, headers = headers)
                    } else {
                        if (!line.startsWith("#")) {
                            val item = playlistItems[currentIndex]
                            val url = line.getUrl()
                            val userAgent = line.getUrlParameter("user-agent")
                            val referrer = line.getUrlParameter("referer")
                            val urlHeaders = if (referrer != null) {
                                item.headers + mapOf("referrer" to referrer)
                            } else item.headers
                            playlistItems[currentIndex] =
                                item.copy(
                                    url = url,
                                    headers = item.headers + urlHeaders,
                                    userAgent = userAgent
                                )
                            currentIndex++
                        }
                    }
                }
                line = reader.readLine()
            }
            return Playlist(playlistItems)
        }

        private fun String.replaceQuotesAndTrim(): String {
            return replace("\"", "").trim()
        }

        private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U)

        private fun String.getTitle(): String? {
            return split(",").lastOrNull()?.replaceQuotesAndTrim()
        }

        private fun String.getUrl(): String? {
            return split("|").firstOrNull()?.replaceQuotesAndTrim()
        }

        private fun String.getUrlParameter(key: String): String? {
            val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
            val keyRegex = Regex("$key=(\\w[^&]*)", RegexOption.IGNORE_CASE)
            val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()
            return keyRegex.find(paramsString)?.groups?.get(1)?.value
        }

        private fun String.getAttributes(): Map<String, String> {
            val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
            val attributesString = replace(extInfRegex, "").replaceQuotesAndTrim().split(",").first()
            return attributesString.split(Regex("\\s")).mapNotNull {
                val pair = it.split("=")
                if (pair.size == 2) pair.first() to pair.last()
                    .replaceQuotesAndTrim() else null
            }.toMap()
        }

        private fun String.getTagValue(key: String): String? {
            val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
            return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
        }

        companion object {
            const val EXT_M3U = "#EXTM3U"
            const val EXT_INF = "#EXTINF"
            const val EXT_VLC_OPT = "#EXTVLCOPT"
        }
    }
}