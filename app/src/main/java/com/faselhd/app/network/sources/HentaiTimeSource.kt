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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HentaiTimeSource(private val context: Context) {

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

    private val baseUrl = "https://hentai-time.com"

    // ============================== Popular & Latest ===============================

    suspend fun fetchPopular(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/?filter=popular&paged=$page"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("article.thumb-block").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/page/$page/" else baseUrl
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("section:has(h2:contains(اخر الاضافات)) article.thumb-block").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("data-src")
            title = element.selectFirst("header.entry-header span")?.text() ?: "No Title"
            source = AnimeSource.HENTAI_TIME.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/page/$page/?s=$query"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())
        val animes = document.select("article.thumb-block").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        return@withContext MangaPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        return@withContext SAnime().apply {
            this.url = animeUrl
            this.source = AnimeSource.HENTAI_TIME.name
            this.title = document.selectFirst("h1.entry-title")?.text() ?: ""
            this.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = document.selectFirst("div.desc")?.text()
            this.genre = document.select("div.tags-list a.label").joinToString(", ") { it.text() }
            this.status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodeList = mutableListOf<SEpisode>()

        // Check for related videos which act as episodes for a series
        val relatedVideos = document.select("div.under-video-block article.thumb-block")
        if (relatedVideos.isNotEmpty()) {
            relatedVideos.forEachIndexed { index, element ->
                val linkElement = element.selectFirst("a")
                episodeList.add(
                    SEpisode().apply {
                        url = linkElement?.attr("href") ?: ""
                        name = linkElement?.attr("title") ?: "Episode ${index + 1}"
                        episode_number = (index + 1).toFloat()
                        date_upload = System.currentTimeMillis()
                    }
                )
            }
        } else {
            // If no related videos, it's a single video post
            episodeList.add(
                SEpisode().apply {
                    url = animeUrl
                    name = document.selectFirst("h1.entry-title")?.text() ?: "Episode 1"
                    episode_number = 1f
                    date_upload = System.currentTimeMillis()
                }
            )
        }
        return@withContext episodeList.reversed()
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
        val videoList = mutableListOf<Video>()

        // Prioritize the direct download link as it's more stable
        val downloadLink = document.selectFirst("a#tracking-url")?.attr("href")
        if (!downloadLink.isNullOrEmpty()) {
            videoList.add(Video(downloadLink, "Download", downloadLink))
        }

        // Extract the streaming source and add the necessary Referer header to bypass hotlink protection
        val videoSource = document.selectFirst("div.video-player video source")?.attr("src")
        if (!videoSource.isNullOrEmpty()) {
            val headers = mapOf(
                "Referer" to episodeUrl
            )
            videoList.add(Video(videoSource, "Stream", videoSource, headers = headers))
        }

        return@withContext videoList
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on the site
}