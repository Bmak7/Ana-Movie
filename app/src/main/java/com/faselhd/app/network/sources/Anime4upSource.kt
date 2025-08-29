package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class Anime4upSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl = "https://ww.anime4up.rest"

    // --- Add necessary extractors here ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    // VoeExtractor and others can be added if you have them.

    // ============================== Popular & Latest ===============================

    // Uses "Pinned Animes" section, which is not paginated.
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false) // Only page 1 has content

        val url = baseUrl
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.main-widget:has(h3:contains(المثبتة)) div.anime-card-container").map {
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
                    // Use the episode's image as the anime thumbnail
                    this.thumbnail_url = element.selectFirst("div.episodes-card img")?.attr("data-image")
                    this.source = AnimeSource.ANIME4UP.name
                }
            } else {
                null
            }
        }
        // Check for a "next page" link to determine if there's a next page
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a.overlay")
        return SAnime().apply {
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("src")
            title = element.selectFirst("div.anime-card-title h3 a")?.text() ?: "No Title"
            source = AnimeSource.ANIME4UP.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val searchUrl = "https://ww.anime4up.rest/?search_param=animes&s=${query.replace(" ", "+")}"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())

        val animes = document.select("div.anime-card-container").mapNotNull { container ->
            val overlayLink = container.selectFirst("a.overlay")
            val titleElement = container.selectFirst("div.anime-card-title h3 a")
            val imageElement = container.selectFirst("img.img-responsive")
            val statusElement = container.selectFirst("div.anime-card-status a")
            val typeElement = container.selectFirst("div.anime-card-type a")

            if (overlayLink != null && titleElement != null) {
                SAnime().apply {
                    url = overlayLink.attr("href")
                    title = titleElement.text()
                    thumbnail_url = imageElement?.attr("src")
                    source = AnimeSource.ANIME4UP.name

                    // Extract status
                    status = when (statusElement?.text()) {
                        "يعرض الان" -> SAnime.ONGOING
                        "مكتمل" -> SAnime.COMPLETED
                        else -> SAnime.UNKNOWN
                    }

                    // Extract type

                    // Extract description from popover data-content attribute
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
            this.url = animeUrl
            this.source = AnimeSource.ANIME4UP.name

            // Extract title from h1.anime-details-title
            this.title = document.selectFirst("h1.anime-details-title")?.text() ?: ""

            // Extract thumbnail from anime-thumbnail img
            this.thumbnail_url = document.selectFirst("div.anime-thumbnail img.thumbnail")?.attr("src")

            // Extract description from anime-story paragraph
            this.description = document.selectFirst("p.anime-story")?.text()

            // Extract genres from anime-genres list
            this.genre = document.select("ul.anime-genres li a").joinToString(", ") { it.text() }

            // Extract additional info from anime-info divs
            val infoElements = document.select("div.anime-info")



            // Extract status
            val statusText = infoElements.find { it.text().contains("حالة الأنمي:") }
                ?.selectFirst("a")?.text()
            this.status = when (statusText) {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            // Extract year from "بداية العرض"


        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        // Select episode containers from the episodes list
        val episodeElements = document.select("div.DivEpisodeContainer")

        return@withContext episodeElements.mapNotNull { container ->
            val linkElement = container.selectFirst("a")
            val titleElement = container.selectFirst("h3 a")

            if (linkElement != null && titleElement != null) {
                SEpisode().apply {
                    url = linkElement.attr("href")
                    name = titleElement.text()

                    // Extract episode number from Arabic text like "الحلقة 1"
                    val episodeText = name
                    val episodeNumberMatch = Regex("الحلقة\\s*(\\d+)").find(episodeText!!)
                    episode_number = episodeNumberMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

                    date_upload = System.currentTimeMillis()
                }
            } else {
                null
            }
        }.reversed() // Reverse to get episode 1 first
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
        val serverElements = document.select("ul#episode-servers li a")

        return@withContext serverElements.flatMap { element ->
            val embedUrl = element.attr("data-ep-url")
            extractVideosFromUrl(embedUrl)
        }
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        return when {
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url)
            "dood" in url || "d-s.io" in url -> doodExtractor.videosFromUrl(url)
            "streamtape" in url -> streamTapeExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "yourupload" in url -> yourUploadExtractor.videosFromUrl(url)
            "vidmoly" in url -> vidBomExtractor.videosFromUrl(url) // Vidmoly might work with Vidbom
            "voe.sx" in url -> {
                // Placeholder for VoeExtractor if you have one.
                emptyList()
            }
            "wish" in url || "videas" in url -> streamWishExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on site
}