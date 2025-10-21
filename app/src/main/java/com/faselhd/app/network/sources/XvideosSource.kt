package com.faselhd.app.network.sources

import android.content.Context
import android.os.Build
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.Tls12SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class XvideosSource(private val context: Context) {

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
        val clientBuilder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", FaselHDSource.USER_AGENT)
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(request)
            }

        if (Build.VERSION.SDK_INT in 16..21) { // Apply for Jelly Bean up to Lollipop
            try {
                val sc = SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, null)
                val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as java.security.KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                    throw IllegalStateException("Unexpected default trust managers:" + java.util.Arrays.toString(trustManagers))
                }
                val trustManager = trustManagers[0] as X509TrustManager
                clientBuilder.sslSocketFactory(Tls12SocketFactory(sc.socketFactory), trustManager)
                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()
                clientBuilder.connectionSpecs(Collections.singletonList(cs))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        clientBuilder.build()
    }

    private val baseUrl = "https://www.xvideos.com"

    // ============================== Popular & Latest ===============================

    suspend fun fetchPopular(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/best/$page" else "$baseUrl/best"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.thumb-block").mapNotNull { videoFromElement(it) }
        val hasNextPage = document.selectFirst("a.next-page") != null
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/new/$page" else "$baseUrl/new"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.thumb-block").mapNotNull { videoFromElement(it) }
        val hasNextPage = document.selectFirst("a.next-page") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun videoFromElement(element: Element): SAnime? {
        val linkElement = element.selectFirst("p.title a") ?: return null
        return SAnime().apply {
            url = baseUrl + linkElement.attr("href")
            thumbnail_url = element.selectFirst("img")?.attr("data-src")
            title = linkElement.attr("title")
            source = AnimeSource.XVIDEOS.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        // Xvideos uses p=0 for page 1, p=1 for page 2, etc.
        val pageQuery = if (page > 1) "&p=${page - 1}" else ""
        val searchUrl = "$baseUrl/?k=$query$pageQuery"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())
        val animes = document.select("div.thumb-block").mapNotNull { videoFromElement(it) }
        val hasNextPage = document.selectFirst("a.next-page") != null
        return@withContext MangaPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchVideoDetails(video: SAnime): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(video.url!!).build()).execute().body!!.string())
        return@withContext video.apply {
            title = document.selectFirst("h2.page-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[name=description]")?.attr("content")
            genre = document.select("div.video-tags-list a").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED // Single videos are always 'completed'
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        // A single video is treated as a single episode
        return@withContext listOf(
            SEpisode().apply {
                url = anime.url
                name = anime.title
                episode_number = 1f
                date_upload = System.currentTimeMillis()
            }
        )
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(episode.url!!).build()).execute().body!!.string())
        val videoList = mutableListOf<Video>()
        val scriptContent = document.select("script").html()

        // Regex to find different quality video URLs
        val hlsPattern = Pattern.compile("html5player\\.setVideoHLS\\('(.*?)'\\);")
        val lowQualityPattern = Pattern.compile("html5player\\.setVideoUrlLow\\('(.*?)'\\);")
        val highQualityPattern = Pattern.compile("html5player\\.setVideoUrlHigh\\('(.*?)'\\);")

        var matcher = hlsPattern.matcher(scriptContent)
        if (matcher.find()) {
            val url = matcher.group(1)
            videoList.add(Video(url, "HLS (Auto)", url))
        }

        matcher = highQualityPattern.matcher(scriptContent)
        if (matcher.find()) {
            val url = matcher.group(1)
            videoList.add(Video(url, "High Quality", url))
        }

        matcher = lowQualityPattern.matcher(scriptContent)
        if (matcher.find()) {
            val url = matcher.group(1)
            videoList.add(Video(url, "Low Quality", url))
        }

        return@withContext videoList
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}