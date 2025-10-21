package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.NetworkUtils
import okhttp3.ConnectionSpec
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Anime4upSource(private val context: Context) {

    // A trust manager that does not validate certificate chains
    private val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    // A modern, simplified OkHttpClient configuration
    private val client = NetworkUtils.getUnsafeOkHttpClient()



    private val baseUrl = "https://ww.anime4up.rest"

    // --- Extractors ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val fourSharedExtractor by lazy { FourSharedExtractor(client) }
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
        luluStream1Extractor = luluStream1Extractor,
        filemoonExtractor = filemoonExtractor
    )

    // ============================== Popular & Latest ===============================

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        val url = baseUrl
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.main-widget:has(h3:contains(المثبتة)) div.anime-card-container").map {
            popularFromElement(it)
        }
        MangaPage(animeList, false)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/episode/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("div.episodes-list-content div.episodes-card-container").mapNotNull { element ->
            val animeLink = element.selectFirst("div.ep-card-anime-title > h3 > a")
            if (animeLink != null) {
                SAnime().apply {
                    this.url = animeLink.attr("href")
                    this.title = animeLink.text()
                    this.thumbnail_url = element.selectFirst("div.episodes-card img")?.attr("src") ?: element.selectFirst("div.episodes-card img")?.attr("data-src")
                    this.source = AnimeSource.ANIME4UP.name
                }
            } else {
                null
            }
        }
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a.overlay")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src")
            title = element.selectFirst("div.anime-card-title h3 a")?.text() ?: "No Title"
            source = AnimeSource.ANIME4UP.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/?search_param=animes&s=${query.replace(" ", "+")}"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())

        val animes = document.select("div.anime-card-container").mapNotNull { container ->
            val overlayLink = container.selectFirst("a.overlay")
            val titleElement = container.selectFirst("div.anime-card-title h3 a")
            if (overlayLink != null && titleElement != null) {
                SAnime().apply {
                    url = overlayLink.attr("href")
                    title = titleElement.text()
                    thumbnail_url = container.selectFirst("img.img-responsive")?.attr("src")
                    source = AnimeSource.ANIME4UP.name
                    status = when (container.selectFirst("div.anime-card-status a")?.text()) {
                        "يعرض الان" -> SAnime.ONGOING
                        "مكتمل" -> SAnime.COMPLETED
                        else -> SAnime.UNKNOWN
                    }
                    description = container.selectFirst("[data-content]")?.attr("data-content")
                }
            } else {
                null
            }
        }
        return@withContext MangaPage(animes, hasNextPage = animes.size >= 20)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        return@withContext SAnime().apply {
            url = animeUrl
            source = AnimeSource.ANIME4UP.name
            title = document.selectFirst("h1.anime-details-title")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.anime-thumbnail img.thumbnail")?.attr("src")
            description = document.selectFirst("p.anime-story")?.text()
            genre = document.select("ul.anime-genres li a").joinToString(", ") { it.text() }
            val statusText = document.select("div.anime-info").find { it.text().contains("حالة الأنمي:") }
                ?.selectFirst("a")?.text()
            status = when (statusText) {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val allEpisodes = mutableListOf<SEpisode>()
        var currentPageUrl = animeUrl
        val mainDocument = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val animeNameAsSeason = mainDocument.selectFirst("h1.anime-details-title")?.text() ?: "Season 1"

        while (true) {
            val document = Jsoup.parse(client.newCall(Request.Builder().url(currentPageUrl).build()).execute().body!!.string())
            val episodeElements = document.select("div.episodes-list-content .themexblock")
            val episodesOnPage = episodeElements.mapNotNull { container ->
                val linkElement = container.selectFirst(".info a")
                val titleElement = container.selectFirst("a.badge span")
                if (linkElement != null && titleElement != null) {
                    SEpisode().apply {
                        url = linkElement.attr("href")
                        val episodeName = titleElement.text()
                        name = "$animeNameAsSeason : $episodeName"
                        val episodeNumberMatch = Regex("الحلقة\\s*(\\d+)").find(episodeName)
                        episode_number = episodeNumberMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                        date_upload = System.currentTimeMillis()
                    }
                } else {
                    null
                }
            }
            allEpisodes.addAll(episodesOnPage)
            val nextPageElement = document.selectFirst("nav.pagination a.next")
            if (nextPageElement != null) {
                currentPageUrl = nextPageElement.attr("href")
            } else {
                break
            }
        }
        return@withContext allEpisodes.reversed()
    }

    // ============================ Video Links =============================
    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting fetchVideoList for URL: $episodeUrl")
        val document = try {
            val response = client.newCall(Request.Builder().url(episodeUrl).build()).execute()
            if (!response.isSuccessful) {
                println("ERROR: Failed to fetch episode page. Code: ${response.code}")
                return@withContext emptyList()
            }
            Jsoup.parse(response.body!!.string())
        } catch (e: Exception) {
            println("ERROR: Exception while fetching episode page: ${e.message}")
            return@withContext emptyList()
        }

        // --- STRATEGY 1: New layout with 'data-watch' attributes ---
        val watchServerElements = document.select("ul#episode-servers li[data-watch]")
        if (watchServerElements.isNotEmpty()) {
            println("INFO: Found 'data-watch' attributes. Using Strategy 1.")
            return@withContext watchServerElements.flatMap { element ->
                val embedUrl = element.attr("data-watch")
                val serverName = element.selectFirst("a")?.text()?.trim() ?: "Unknown Server"
                println("\nDEBUG: --- Processing server: $serverName ---")
                println("DEBUG: Found direct embed URL: $embedUrl")
                if (embedUrl.isNotBlank()) {
                    val normalizedUrl = normalizeUrl(embedUrl)
                    val videos = extractVideosFromUrl(normalizedUrl)
                    println("DEBUG: Extractor found ${videos.size} video(s) for this server.")
                    videos
                } else {
                    emptyList()
                }
            }
        }

        // --- STRATEGY 2: Fallback to download links table ---
        println("WARN: No 'data-watch' attributes found. Falling back to Strategy 2 (Download Links).")
        val downloadRows = document.select("div#download tbody tr")
        if (downloadRows.isEmpty()) {
            println("WARN: Both strategies failed. No 'data-watch' or 'download' links found.")
            return@withContext emptyList()
        }

        println("INFO: Found ${downloadRows.size} download links.")
        return@withContext downloadRows.flatMap { row ->
            val downloadUrl = row.selectFirst("a.btn")?.attr("href")
            val serverName = row.selectFirst(".server-name")?.text() ?: "Unknown"

            if (downloadUrl.isNullOrBlank()) return@flatMap emptyList<Video>()

            println("\nDEBUG: --- Processing server: $serverName ---")
            println("DEBUG: Original Download URL: $downloadUrl")

            val embedUrl = when {
                "megamax.me" in downloadUrl -> downloadUrl.replace("/download/", "/iframe/")
                "filelions.to" in downloadUrl || "filelions.live" in downloadUrl -> downloadUrl.replace("/f/", "/v/")
                "ok.ru" in downloadUrl -> downloadUrl.replace("/video/", "/videoembed/")
                "streamwish.to" in downloadUrl -> downloadUrl.replace("/f/", "/e/")
                "doodstream.com" in downloadUrl || "dsvplay.com" in downloadUrl -> downloadUrl.replace("/d/", "/e/")
                "uqload.com" in downloadUrl -> downloadUrl.replace(".com/", ".com/embed-") + ".html"
                "mp4upload.com" in downloadUrl -> downloadUrl.replace("/d/", "/embed-") + ".html"
                "vidmoly" in downloadUrl -> downloadUrl.replace("/d/", "/embed-")
                "voe.sx" in downloadUrl -> downloadUrl // No transformation needed
                else -> {
                    println("WARN: No transformation rule for URL: $downloadUrl")
                    null
                }
            }

            if (embedUrl != null) {
                println("INFO: Transformed to Embed URL: $embedUrl")
                val normalizedUrl = normalizeUrl(embedUrl)
                val videos = extractVideosFromUrl(normalizedUrl)
                println("DEBUG: Extractor found ${videos.size} video(s) for this server.")
                videos
            } else {
                emptyList()
            }
        }
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        println("DEBUG: Extracting from URL: $url")
        return when {
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url)
            "dood" in url || "dsvplay" in url -> doodExtractor.videosFromUrl(url)
            "streamtape" in url -> streamTapeExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "4shared" in url -> fourSharedExtractor.videosFromUrl(url)
            "megamax" in url -> megaMaxExtractor.videosFromUrl(url)
            "filelions" in url -> megaMaxExtractor.videosFromUrl(url) // Assuming it uses a compatible extractor
            "yourupload" in url -> yourUploadExtractor.videosFromUrl(url)
            "vidmoly" in url -> vidmolyExtractor.videosFromUrl(url)
            "voe.sx" in url -> voeExtractor.videosFromUrl(url)
            "streamwish" in url || "videas" in url -> streamWishExtractor.videosFromUrl(url)
            else -> {
                println("WARN: No extractor found for URL: $url")
                emptyList()
            }
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}


