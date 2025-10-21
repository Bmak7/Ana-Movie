package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

class AnimetakSource(private val context: Context) {

    // --- Start: Boilerplate - Can be copied from your existing source ---
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            .build()
    }

    private val baseUrl = "https://rf.animetak.top"

    // --- Extractors ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }

    val megaMaxExtractor = MegaMaxExtractor(
        client = client,
        doodExtractor = doodExtractor,
        voeExtractor = voeExtractor,
        mixDropExtractor = mixDropExtractor,
        streamWishExtractor = streamWishExtractor,
        streamTapeExtractor = streamTapeExtractor,
        mp4uploadExtractor = mp4uploadExtractor,
        filemoonExtractor = filemoonExtractor,
        luluStream1Extractor = luluStream1Extractor,
        mivalyoExtractor = mivalyoExtractor,
        vidTubeExtractor = vidTubeExtractor
    )
    // --- End: Boilerplate ---


    // ============================== Popular & Latest ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = fetchLatestUpdates(page) // Animetak main page is latest, so we use it for popular as well.

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("div.BlocksHolder div.Small--Box > a.recent--block").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("div.pagination a.next.page-numbers") != null

        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("href") // Note: This is an EPISODE url, details page will resolve the series url
            thumbnail_url = element.selectFirst("div.Poster img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            title = element.selectFirst("h3.title")?.text()
            source = AnimeSource.ANIMETAK.name
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/?s=$query"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("div.BlocksHolder div.Small--Box > a.recent--block").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("div.pagination a.next.page-numbers") != null

        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        // animeUrl is an episode URL from the listing. We parse details from it
        // but set the SAnime url to the main series page for episode loading.
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        SAnime().apply {
            // The canonical URL for the show, used to fetch the episode list
            url = document.selectFirst("ul.single-info a[href*=/series/]")?.attr("href") ?: animeUrl

            title = document.selectFirst("ul.single-info a[href*=/series/]")?.text()
                ?: document.selectFirst("h1.PostTitle")?.text()?.substringBefore("الحلقة")?.trim()

            thumbnail_url = document.selectFirst("div.MainSingle div.image img")?.attr("src")
            description = document.selectFirst("h3.story")?.text()
            genre = document.select("ul.single-info:contains(النوع) a").eachText().joinToString(", ")
            status = SAnime.UNKNOWN // Status is not clearly available, default to UNKNOWN
            source = AnimeSource.ANIMETAK.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        // Fetch the main page
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        // Try to get the title from either the series link or the page title (like version 1)
        val animeNameAsSeason = document.selectFirst("ul.single-info a[href*=/series/]")?.text()
            ?: document.selectFirst("h1.PostTitle")?.text()?.substringBefore("الحلقة")?.trim()
            ?: "Season 1"

        // Select all episode links
        val episodeElements = document.select("div.EpisodesList > a")

        if (episodeElements.isEmpty()) {
            // Handle movies or single-episode shows
            return@withContext listOf(SEpisode().apply {
                url = animeUrl
                name = animeNameAsSeason
                episode_number = 1f
            })
        }

        // Map elements to SEpisode objects and reverse for chronological order
        return@withContext episodeElements.map { episodeFromElement(it, animeNameAsSeason) }.reversed()
    }

    private fun episodeFromElement(element: Element, animeNameAsSeason: String): SEpisode {
        return SEpisode().apply {
            url = element.attr("href")
            val episodeName = element.text().trim()
            name = "Season  : $episodeName" // ✅ include anime title before episode name
            // Extract number from <em> or from Arabic text
            val episodeNumberMatch = Regex("الحلقة\\s*(\\d+)").find(episodeName)
            episode_number = episodeNumberMatch?.groupValues?.get(1)?.toFloatOrNull()
                ?: element.selectFirst("em")?.text()?.toFloatOrNull()
                        ?: 0f
            date_upload = System.currentTimeMillis()
        }
    }


    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        // 1. Get the main episode page to find the "watch" link
        val episodeDocument = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
        val watchUrl = episodeDocument.selectFirst("a.btn-servView[href*=watch]")?.attr("href")

        if (watchUrl.isNullOrBlank()) {
            Log.e("Animetak", "Could not find watch URL on page: $episodeUrl")
            return@withContext emptyList()
        }

        // 2. Get the watch page content
        val document = Jsoup.parse(client.newCall(Request.Builder().url(watchUrl).build()).execute().body!!.string())

        // 3. Find all server list items and extract the 'data-watch' URL
        val serverElements = document.select("ul#watch > li")

        // 4. Use flatMap to process each URL and collect the results
        val videos = serverElements.flatMap { serverElement ->
            val embedUrl = serverElement.attr("data-watch")
            val quality = serverElement.text() // e.g. "HD - megamax"
            Log.d("Animetak", "Found server: $quality -> $embedUrl")

            if (embedUrl.isNotBlank()) {
                extractVideosFromUrl(embedUrl, quality)
            } else {
                emptyList()
            }
        }
        return@withContext videos
    }

    private fun extractVideosFromUrl(url: String, quality: String): List<Video> {
        val result = when {
            "ok.ru" in url -> okruExtractor.videosFromUrl(url, quality)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, quality)
            "dood" in url || "d-s.io" in url -> doodExtractor.videosFromUrl(url, "DoodStream")
            "streamtape" in url -> streamTapeExtractor.videosFromUrl(url, "StreamTape")
            "uqload" in url -> uqloadExtractor.videosFromUrl(url, quality)
            "megamax" in url -> megaMaxExtractor.videosFromUrl(url)
            "yourupload" in url -> yourUploadExtractor.videosFromUrl(url)
            VIDBOM_DOMAINS.any { url.contains(it) } -> vidBomExtractor.videosFromUrl(url)
            url.contains("wish", ignoreCase = true) -> streamWishExtractor.videosFromUrl(url)
            else -> {
                Log.w("Animetak", "No specific extractor found for: $url")
                emptyList()
            }
        }
        return result
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // Filters are not implemented for this source.

    companion object {
        private val VIDBOM_DOMAINS = listOf("vidbom", "vidbem", "vidbm", "vedpom", "vadbom", "myviid")
    }
}