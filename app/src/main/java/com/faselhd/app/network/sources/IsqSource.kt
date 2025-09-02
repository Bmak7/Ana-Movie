package com.faselhd.app.network.sources

import VidmolyExtractor
import VoeExtractor
import android.content.Context
import android.os.Build
import android.util.Log
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class IsqSource(private val context: Context) {
    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

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
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(request)
            }

        if (Build.VERSION.SDK_INT in 16..21) {
            try {
                val sc = SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, null)
                val trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
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

    private val baseUrl = "https://3isq.cam"

    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val vidbomExtractor by lazy { VidBomExtractor(client) }
    //endregion

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.Small--Box a.recent--block").mapNotNull {
            // Filter out episode links, only keep series links
            if (it.parent()?.hasClass("series") == true || it.attr("href").contains("/series/")) {
                SAnime().apply {
                    this.url = it.attr("href")
                    this.title = it.select("div.title").text()
                    this.thumbnail_url = it.select("img.imgInit").let { img ->
                        img.attr("data-src").ifEmpty { img.attr("src") }
                    }
                    this.source = AnimeSource.ISQ.name
                }
            } else {
                null
            }
        }

        val hasNextPage = document.selectFirst("link[rel=next]") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) "$baseUrl/?s=$query&page=$page" else "$baseUrl/?s=$query"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.Small--Box a.recent--block").mapNotNull {
            SAnime().apply {
                this.url = it.attr("href")
                this.title = it.select("div.title").text()
                this.thumbnail_url = it.select("img").let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }
                // Extract episode number from the title or the number element
                val episodeNum = it.select("div.number em").text()
                if (episodeNum.isNotEmpty()) {
                    this.title = "الحلقة $episodeNum - ${this.title}"
                }
                this.source = AnimeSource.ISQ.name
            }
        }

        // Check if there's a next page based on pagination
        val hasNextPage = document.select("a.next.page-numbers").isNotEmpty()

        MangaPage(animeList, hasNextPage = hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            title = document.selectFirst("div.info h1 a")?.text() ?: "N/A"
            val backgroundUrl = document.selectFirst("div.cover div.img")?.attr("style")
            thumbnail_url = backgroundUrl?.substringAfter("url(")?.substringBefore(");") ?: ""
            description = document.selectFirst("div.story")?.ownText() ?: ""
            genre = document.select("div.tax:contains(التصنيفات) a, div.tax:contains(الأنواع) a").joinToString(", ") { it.text() }
            status = 1 // Default status, can be updated if available
            source = AnimeSource.ISQ.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        // First, get the anime name/season name from the main page
        val animeNameAsSeason = document.selectFirst("h1.title, h1.anime-title, div.anime-info h1, h1")?.text() ?: "الموسم 1"

        document.select("section.allepcont div.Small--Box.series a.recent--block").map {
            SEpisode().apply {
                this.url = it.attr("href")

                // Format the name to be "Anime Name : Episode Name" like function 1
                val episodeName = it.select("div.title").text()
                this.name = "$animeNameAsSeason : $episodeName"

                this.episode_number = it.select("div.number em").text().toFloatOrNull() ?: 0f
            }
        }.reversed() // Episodes are usually listed newest first
    }

    private  val TAG = "VideoFetcher"
    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
    }


    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d(TAG, "➡️ fetchVideoList() called with episodeUrl=$episodeUrl")

        // Step 1: Navigate to the "see" page which contains the video servers
        val watchPageUrl = if (episodeUrl.endsWith("/see/")) episodeUrl else "$episodeUrl/see/"
        Log.d(TAG, "📺 Watch page URL resolved: $watchPageUrl")

        val request = Request.Builder().url(watchPageUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        Log.d(TAG, "🌐 Response received. Length=${body?.length ?: 0}")
        val document = Jsoup.parse(body)

        // Step 2: Extract server links from the server list
        val serverElements = document.select("ul#watch li")
        Log.d(TAG, "🔗 Found ${serverElements.size} server elements")

        serverElements.flatMap { element ->
            var embedUrl = element.attr("data-watch")
            val serverName = element.select("em").text()
            embedUrl = normalizeUrl(embedUrl)
            Log.d(TAG, "🖥️ Server detected: name=$serverName, embedUrl=$embedUrl")
            val videos = getVideosFromUrl(embedUrl, serverName, watchPageUrl)
            Log.d(TAG, "✅ Extracted ${videos.size} videos from $serverName")
            videos
        }
    }

    fun extractVoeId(url: String): String? {
        // Normalize scheme-less URLs
        val normalized = if (url.startsWith("//")) "https:$url" else url

        val regex = Regex(
            pattern = """^https?://(?:www\.)?voe\.sx/e/([A-Za-z0-9]+)(?:[/?#]|$)""",
            option = RegexOption.IGNORE_CASE
        )
        return regex.find(normalized)?.groupValues?.get(1)
    }
    private suspend fun getVideosFromUrl(url: String, quality: String, referer: String): List<Video> {
        Log.d(TAG, "➡️ getVideosFromUrl() called with url=$url, quality=$quality, referer=$referer")
        return try {
            val videos = when {
                "dood" in url || "d-s" in url || "vide0" in url -> {
                    Log.d(TAG, "🎯 Using doodExtractor")
                    doodExtractor.videosFromUrl(url, quality)
                }
                "uqload" in url -> {
                    Log.d(TAG, "🎯 Using uqloadExtractor")
                    uqloadExtractor.videosFromUrl(url)
                }
                "voe" in url -> {
                    Log.d(TAG, "🎯 Using voeExtractor r https://jilliandescribecompany.com/e/${extractVoeId(url)}")
                    voeExtractor.videosFromUrl("https://jilliandescribecompany.com/e/${extractVoeId(url)}")
                }
                // "vidmoly" in url -> vidmolyExtractor.videosFromUrl(url)
                "streamtape" in url -> {
                    Log.d(TAG, "🎯 Using streamtapeExtractor")
                    streamtapeExtractor.videosFromUrl(url)
                }
                "streamwish" in url || "filelions" in url || "videa" in url -> {
                    Log.d(TAG, "🎯 Using streamwishExtractor")
                    streamwishExtractor.videosFromUrl(url)
                }
                "vidbom" in url -> {
                    Log.d(TAG, "🎯 Using vidbomExtractor")
                    vidbomExtractor.videosFromUrl(url)
                }
                "vidmoly" in url || "vidmoly.net" in url -> {
                    println("DEBUG: Using vidmolyExtractor for: $url")
                    vidmolyExtractor.videosFromUrl(url)
                }
                else -> {
                    Log.d(TAG, "⚠️ No extractor found for url=$url")
                    emptyList()
                }
            }
            Log.d(TAG, "📦 Extractor returned ${videos.size} videos for url=$url")
            videos
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in getVideosFromUrl: ${e.message}", e)
            emptyList()
        }
    }

    // ============================ Main Slider =============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        // The "المثبتات" (Pinned) section serves as the slider
        val pinnedSection = document.select("h3.themesXcom_title:contains(المثبتات)").first()?.parent()?.parent()
        pinnedSection?.select("div.Small--Box a.recent--block")?.map {
            SAnime().apply {
                this.url = it.attr("href")
                this.title = it.select("div.title").text()
                this.thumbnail_url = it.select("img.imgInit").let { img ->
                    img.attr("src").ifEmpty { img.attr("data-src") }
                }
                this.source = AnimeSource.ISQ.name
            }
        } ?: emptyList()
    }

    fun getFilterList() = AnimeFilterList(emptyList()) // No filters implemented for this source
}