package com.faselhd.app.network.sources

import StreamGHExtractor
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder

class CimaClubSource(private val context: Context) {
    companion object {
        const val name = "Cima Club"
        const val BASE_URL = "https://ciimaclub.club"
        const val lang = "ar"
        const val supportsLatest = true
    }

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

    // Extractors for video hosts
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }

    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    // A generic extractor for hosts like peytonepre, iplayerhls, etc.
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }

    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val sliderItems = document.select(".Slides--Main .Slides--Item")
        return sliderItems.mapNotNull { element ->
            try {
                SAnime().apply {
                    val link = element.selectFirst("a")!!
                    setUrlWithoutDomain(link.attr("href"))
                    title = link.attr("title")
                    thumbnail_url = link.selectFirst("img")?.attr("data-src") ?:link.selectFirst("img")?.attr("src")
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ============================== Popular / Latest ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) BASE_URL else "$BASE_URL/page/$page/"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    // Using "Most Watched" or other AJAX-based filters would require more complex network inspection.
    // For simplicity, we can point popular to latest, as the main page content is the most relevant.
    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return fetchLatestUpdates(page)
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("div.BlocksHolder div.Small--Box")
        val animeList = animeElements.map(::animeFromElement)
        val hasNextPage = document.select("a.next.page-numbers").isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime {
        return SAnime().apply {
            val link = element.selectFirst("a.recent--block")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.selectFirst("inner--title h2")?.text() ?: link.attr("title")
            thumbnail_url = link.selectFirst("div.Poster img")?.attr("data-src") ?: link.selectFirst("div.Poster img")?.attr("src")
        }
    }

    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response)
    }

    private fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        return SAnime().apply {
            url = document.location()
            title = document.selectFirst("h1.PostTitle")?.text() ?: "N/A"
            thumbnail_url = document.selectFirst("div.left div.image img")?.absUrl("src")
            val storyElement = document.selectFirst("div.StoryArea p")
            storyElement?.selectFirst("em")?.remove() // Remove "قصة العرض"
            description = storyElement?.text()

            val details = mutableMapOf<String, String>()
            document.select("ul.RightTaxContent ul.half-tags").forEach { ul ->
                val key = ul.selectFirst("span")?.text()?.trim()
                val value = ul.select("a").joinToString(", ") { it.text() }
                if (!key.isNullOrEmpty() && value.isNotEmpty()) {
                    details[key] = value
                }
            }

            genre = details["الانواع"] ?: details["التصنيف"]
            description += "\n\n" + details.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        episodeListParse(response)
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val episodes = mutableListOf<SEpisode>()

        val seasons = document.select("section.allseasonss .Small--Box a")
        if (seasons.isNotEmpty()) {
            // It's a series with multiple seasons
            seasons.reversed().forEach { seasonLink ->
                try {
                    val seasonUrl = seasonLink.absUrl("href")
                    val seasonDoc = Jsoup.connect(seasonUrl).get()
                    val seasonName = seasonLink.selectFirst(".epnum")?.text() ?: "الموسم"
                    seasonDoc.select("section.allepcont .row a").forEach { episodeLink ->
                        episodes.add(episodeFromElement(episodeLink, seasonName))
                    }
                } catch (e: Exception) {
                    // Ignore season if it fails to load
                }
            }
        } else {
            // It's a single season series or a movie
            val episodeElements = document.select("section.allepcont .row a")
            if (episodeElements.isNotEmpty()) {
                val seasonName = document.selectFirst(".allseasonss .epnum")?.text() ?: "الموسم 1"
                episodeElements.forEach { episodes.add(episodeFromElement(it, seasonName)) }
            } else {
                // It's a movie, create a single "watch" episode
                val watchLink = document.selectFirst(".BTNSDownWatch a.watch")?.absUrl("href")
                val thumbnailUrll = document.selectFirst("div.left div.image img")?.absUrl("data-src") ?:document.selectFirst("div.left div.image img")?.absUrl("src")
                if (watchLink != null) {
                    val movieEpisode = SEpisode().apply {
                        url = watchLink
                        name = "مشاهدة الفيلم"
                        episode_number = 1.0f
                        thumbnailUrl = thumbnailUrll
                    }
                    episodes.add(movieEpisode)
                }
            }
        }
        return episodes.reversed() // Reverse to show oldest first
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = "$seasonName: ${element.selectFirst(".ep-info h2")?.text() ?: "حلقة"}"
            val epNumStr = element.selectFirst(".epnum")?.text()?.replace(Regex("[^0-9.]"), "")
            episode_number = epNumStr?.toFloatOrNull() ?: 1.0f
            thumbnailUrl = element.selectFirst("img")?.absUrl("data-src") ?: element.selectFirst("img")?.absUrl("src")
        }
    }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val watchUrl = if (episodeUrl.endsWith("/watch/")) episodeUrl else "$episodeUrl/watch/"
        val request = Request.Builder().url(watchUrl).build()
        val response = client.newCall(request).execute()
        videoListParse(response)
    }

    private fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val allVideos = mutableListOf<Video>()
        val servers = document.select("ul#watch li")

        servers.forEach { serverElement ->
            val iframeUrl = serverElement.attr("data-watch")
            val serverName = serverElement.text()
            if (iframeUrl.isNotEmpty()) {
                allVideos.addAll(extractVideosFromServer(iframeUrl, serverName))
            }
        }
        return allVideos
    }

    fun extractHglinkId(url: String): String? {
        return try {
            Uri.parse(url).lastPathSegment
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVideosFromServer(url: String, quality: String): List<Video> {
        Log.d("VideoFetcher", "🔎 Extracting from server ($quality): $url")
        return when {
            "uqload" in url -> uqloadExtractor.videosFromUrl(url, quality)
            "lulu" in url -> luluStream1Extractor.videosFromUrl(url, url)
            "dood" in url || "dooodster" in url -> doodExtractor.videosFromUrl(url)
            "streamtape" in url -> {
                println("DEBUG: Using streamtapeExtractor for: $url")
                streamtapeExtractor.videosFromUrl(url)
            }
            "wish" in url -> {
                println("DEBUG: Using streamtapeExtractor for: $url")
                streamwishExtractor.videosFromUrl(url)
            }
            "filemoon" in url -> filemoonExtractor.videosFromUrl(url, "FileMoon")
            url.contains("hglink") || url.contains("dumbalag") -> {
                println("DEBUG: Processing Hglink URL: $url")
                val extractedId = extractHglinkId(url)
                println("DEBUG: Extracted Hglink ID: $extractedId")
                val haxloppdUrl = "https://haxloppd.com/$extractedId"
                println("DEBUG: Haxloppd URL: $haxloppdUrl")
                val result = haxloppdExtractor.videosFromUrl(haxloppdUrl)
                println("DEBUG: Haxloppd extraction result: ${result.size} videos found")
                result
            }
            // Generic extractors for other potential servers
            "peytonepre" in url || "iplayerhls" in url || "vudeo" in url || "mivalyo" in url || "mivalyo" in url  -> mivalyoExtractor.videosFromUrl(url)
            else -> {
                Log.w("VideoFetcher", "⚠️ No specific extractor for: $url")
                emptyList()
            }
        }
    }

    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page > 1) {
            "$BASE_URL/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$BASE_URL/?s=${URLEncoder.encode(query, "UTF-8")}"
        }

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response)
    }

    private fun searchParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("div.BlocksHolder div.Small--Box")
        val animeList = animeElements.map(::animeFromElement)
        val hasNextPage = document.select("a.next.page-numbers").isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Filters ==============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // Site filters are complex and require JS.
}