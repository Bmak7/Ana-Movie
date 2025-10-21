package com.faselhd.app.network.sources

import StreamGHExtractor
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
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NxxhentaiSource(private val context: Context) {

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

        if (Build.VERSION.SDK_INT in 16..21) {
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

    private val baseUrl = "https://nxxhentai.com"

    // --- Add necessary extractors here ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val fourSharedExtractor by lazy { FourSharedExtractor(client) }
    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }


    // ============================== Popular & Latest ===============================

    suspend fun fetchPopular(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/trending/page/$page/" else "$baseUrl/trending/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("article.item.tvshows").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("a.arrow_pag") != null
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/episode/page/$page/" else baseUrl
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("article.item.episodes").map { latestFromElement(it) }
        val hasNextPage = document.selectFirst("a.arrow_pag") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("data-src")
            title = element.selectFirst("div.data h3 a")?.text() ?: "No Title"
            source = AnimeSource.NXXHENTAI.name
        }
    }

    private fun latestFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("div.season_m a")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("data-src")
            title = element.selectFirst("div.data h3 a")?.text() ?: "No Title"
            source = AnimeSource.NXXHENTAI.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/page/$page/?s=$query"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())
        val animes = document.select("div.result-item article").mapNotNull { element ->
            val linkElement = element.selectFirst("div.thumbnail a") ?: return@mapNotNull null
            SAnime().apply {
                url = linkElement.attr("href")
                thumbnail_url = element.selectFirst("img")?.attr("src")
                title = element.selectFirst("div.title a")?.text() ?: "No Title"
                source = AnimeSource.NXXHENTAI.name
            }
        }
        val hasNextPage = document.selectFirst("a.arrow_pag") != null
        return@withContext MangaPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        return@withContext SAnime().apply {
            this.url = animeUrl
            this.source = AnimeSource.NXXHENTAI.name
            this.title = document.selectFirst("div.sheader h1")?.text() ?: ""
            this.thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
            this.description = document.selectFirst("div.wp-content > p")?.text()
            this.genre = document.select("div.sgeneros a").joinToString(", ") { it.text() }
            this.status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodeList = mutableListOf<SEpisode>()
        val episodeElements = document.select("ul.episodios li")

        episodeElements.forEach { element ->
            val linkElement = element.selectFirst("a")
            episodeList.add(
                SEpisode().apply {
                    url = linkElement?.attr("href") ?: ""
                    name = linkElement?.selectFirst(".episodiotitle")?.text() ?: ""
                    // Simple regex to extract number from "الحلقة 01" or similar patterns
                    episode_number = element.className().substringAfter("mark-", "").toFloatOrNull() ?: 0f
                    date_upload = System.currentTimeMillis()
                }
            )
        }
        return@withContext episodeList
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
        val videoList = mutableListOf<Video>()

        // Extract streaming servers
        document.select("div.source-box iframe.metaframe").forEach { iframe ->
            val embedUrl = iframe.attr("src")
            println("embddd yrl : $embedUrl")
            if (embedUrl.isNotBlank()) {
                videoList.addAll(extractVideosFromUrl(embedUrl))
            }
        }

        return@withContext videoList
    }



    fun extractHglinkId(url: String): String? {
        // Normalize scheme-less URLs
        val normalized = if (url.startsWith("//")) "https:$url" else url

        val regex = Regex(
            pattern = """^https?://(?:www\.)?hglink\.to/e/([A-Za-z0-9]+)(?:[/?#]|$)""",
            option = RegexOption.IGNORE_CASE
        )
        return regex.find(normalized)?.groupValues?.get(1)
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        return when {
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url)
            "ds2play." in url || "dood" in url || "d0000d" in url -> doodExtractor.videosFromUrl(url) // d0000d is a dood mirror
            "streamtape" in url -> streamTapeExtractor.videosFromUrl(url)

            url.contains("hlswish") || url.contains("hglink") || url.contains("hglink.to") -> {
                println("DEBUG: Processing Hglink URL: $url")
                val extractedId = extractHglinkId(url)
                println("DEBUG: Extracted Hglink ID: $extractedId")
                val haxloppdUrl = "https://haxloppd.com/$extractedId"
                println("DEBUG: Haxloppd URL: $haxloppdUrl")
                val result = haxloppdExtractor.videosFromUrl(url)
                println("DEBUG: Haxloppd extraction result: ${result.size} videos found")
                result
            }
            else -> emptyList()
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on the site
}