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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class AnimeLekSource(private val context: Context) {

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

                // Pass our custom Tls12SocketFactory
                clientBuilder.sslSocketFactory(Tls12SocketFactory(sc.socketFactory), trustManager)

                // Optional: Force a connection spec that includes modern cipher suites
                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()
                clientBuilder.connectionSpecs(Collections.singletonList(cs))
            } catch (e: Exception) {
                // Could not enable TLSv1.2, older devices might still fail.
                // Log the error for debugging.
                e.printStackTrace()
            }
        }

        clientBuilder.build()
    }

    private val baseUrl = "https://animelek.live"

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
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }


    val megaMaxExtractor = MegaMaxExtractor(
        client = client,
        doodExtractor = doodExtractor,
        voeExtractor = voeExtractor,
        mixDropExtractor = mixDropExtractor,
        streamWishExtractor = streamWishExtractor,
        streamTapeExtractor = streamTapeExtractor,
        mp4uploadExtractor = mp4uploadExtractor,
        vidTubeExtractor = vidTubeExtractor,
        mivalyoExtractor = mivalyoExtractor,
        luluStream1Extractor =  luluStream1Extractor,
        filemoonExtractor = filemoonExtractor

    )

    // ============================== Popular & Latest ===============================

    // Uses "Pinned Animes" section, which is not paginated.
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false) // Only page 1 has content

        val url = baseUrl
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.main-widget:has(h2:contains(المثبتة)) div.anime-card-container").map {
            popularFromElement(it)
        }
        MangaPage(animeList, false) // No pagination for this section
    }

    // Uses "Latest Added Episodes" section
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/episode/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("div.episodes-list-content div.episodes-card-container").mapNotNull { element ->
            val animeLink = element.selectFirst("div.ep-card-anime-title > h3 > a")
            if (animeLink != null) {
                SAnime().apply {
                    this.url = animeLink.attr("href")
                    this.title = animeLink.text()
                    this.thumbnail_url = element.selectFirst("div.episodes-card img")?.attr("src")
                    this.source = AnimeSource.ANIMELEK.name
                }
            } else {
                null
            }
        }
        val hasNextPage = document.selectFirst("a.next_page") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a.overlay")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("src")
            title = element.selectFirst("div.anime-card-title h3 a")?.text() ?: "No Title"
            source = AnimeSource.ANIMELEK.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/search/?s=${query.replace(" ", "+")}"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())

        val animes = document.select("div.anime-card-container").mapNotNull { container ->
            val overlayLink = container.selectFirst("a.overlay")
            val titleElement = container.selectFirst("div.anime-card-title h3 a")

            if (overlayLink != null && titleElement != null) {
                SAnime().apply {
                    url = overlayLink.attr("href")
                    title = titleElement.text()
                    thumbnail_url = container.selectFirst("img.img-responsive")?.attr("src")
                    source = AnimeSource.ANIMELEK.name
                    description = container.selectFirst("div.anime-card-title")?.attr("data-content")

                    status = when (container.selectFirst("div.anime-card-status a")?.text()) {
                        "يعرض الان" -> SAnime.ONGOING
                        "مكتمل" -> SAnime.COMPLETED
                        else -> SAnime.UNKNOWN
                    }
                }
            } else {
                null
            }
        }
        val hasNextPage = document.selectFirst("a.next") != null
        return@withContext MangaPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        return@withContext SAnime().apply {
            this.url = animeUrl
            this.source = AnimeSource.ANIMELEK.name
            this.title = document.selectFirst("h1.anime-details-title")?.text() ?: ""
            this.thumbnail_url = document.selectFirst("div.anime-thumbnail-pic img.thumbnail")?.attr("data-src")?:document.selectFirst("div.anime-thumbnail-pic img.thumbnail")?.attr("src")
            this.description = document.selectFirst("p.anime-story")?.text()
            this.genre = document.select("ul.anime-genres li a").joinToString(", ") { it.text() }

            val statusText = document.select("div.full-list-info:has(small:contains(حالة الأنمي)) small a")?.text()
            this.status = when (statusText) {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val animeNameAsSeason = document.selectFirst("h1.anime-details-title")?.text() ?: "Season 1"

        return@withContext document.select("div#episodes div.DivEpisodeContainer").mapNotNull { container ->
            val linkElement = container.selectFirst("a")
            val titleElement = container.selectFirst("div.ep-card-anime-title-detail h3 a")

            if (linkElement != null && titleElement != null) {
                SEpisode().apply {
                    url = linkElement.attr("href")
                    val episodeName = titleElement.text()
                    name = "$animeNameAsSeason : $episodeName"

                    val episodeNumberMatch = Regex("""(?:الحلقة|الأونا)\s*(\d+)""").find(episodeName)
                    episode_number = episodeNumberMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

                    date_upload = System.currentTimeMillis()
                }
            } else {
                null
            }
        }
    }

    // ============================ Video Links =============================
    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting fetchVideoList for URL: $episodeUrl")

        val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
        println("DEBUG: Successfully fetched and parsed document")

        val serverElements = document.select("ul#episode-servers li.watch a")
        println("DEBUG: Found ${serverElements.size} server elements")

        // Use coroutineScope to manage the lifecycle of concurrent tasks
        return@withContext coroutineScope {
            serverElements.map { element ->
                // Launch each extraction in a separate coroutine
                async {
                    println("DEBUG: Processing server element: ${element.text()}")
                    var embedUrl = element.attr("data-ep-url")
                    println("DEBUG: Raw embed URL: $embedUrl")

                    // Immediately skip if the URL is blank
                    if (embedUrl.isBlank()) {
                        return@async emptyList<Video>()
                    }

                    embedUrl = normalizeUrl(embedUrl)
                    println("DEBUG: Normalized embed URL: $embedUrl")

                    val videos = extractVideosFromUrl(embedUrl)
                    println("DEBUG: Extracted ${videos.size} videos from URL: $embedUrl")

                    videos
                }
            }.awaitAll().flatten().also {
                println("DEBUG: Total videos extracted: ${it.size}")
            }
        }
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        println("DEBUG: extractVideosFromUrl called with URL: $url")

        return when {
            "mp4upload" in url -> {
                println("DEBUG: Using mp4upload extractor")
                mp4uploadExtractor.videosFromUrl(url)
            }
            "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url -> {
                println("DEBUG: Using dood extractor")
                doodExtractor.videosFromUrl(url)
            }
            "streamtape" in url -> {
                println("DEBUG: Using streamtape extractor")
                streamTapeExtractor.videosFromUrl(url)
            }
            "uqload" in url -> {
                println("DEBUG: Using uqload extractor")
                uqloadExtractor.videosFromUrl(url)
            }
            "4shared" in url -> {
                println("DEBUG: Using 4shared extractor")
                fourSharedExtractor.videosFromUrl(url)
            }
            "megamax" in url -> {
                println("DEBUG: Using megamax extractor")
                megaMaxExtractor.videosFromUrl(url)
            }
            "yourupload" in url -> {
                println("DEBUG: Using yourupload extractor")
                yourUploadExtractor.videosFromUrl(url)
            }
            "vidmoly" in url -> {
                println("DEBUG: Using vidmoly extractor")
                vidmolyExtractor.videosFromUrl(url)
            }
            "voe.sx" in url -> {
                println("DEBUG: Using voe extractor")
                voeExtractor.videosFromUrl(url)
            }
            "wish" in url || "videas" in url -> {
                println("DEBUG: Using streamwish extractor")
                streamWishExtractor.videosFromUrl(url)
            }
            else -> {
                println("DEBUG: No matching extractor found for URL: $url")
                emptyList()
            }
        }.also { videos ->
            println("DEBUG: Extracted ${videos.size} videos from $url")
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on site
}