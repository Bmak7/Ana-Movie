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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.regex.Pattern

class AnimePhoenixSource(private val context: Context) {

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

    private val baseUrl = "https://anime-phoenix.com"

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
    suspend fun fetchPopularSeries(page: Int): MangaPage = fetchLatestUpdates(page) // Main page is a mix, suitable for both.

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("article a.FJ-Home-Phoenix-item-card").map { popularFromElement(it) }
        // The site uses infinite scroll, so we assume there is always a next page for simplicity
        val hasNextPage = true

        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("href")
            thumbnail_url = element.selectFirst("img.FJ-Home-Phoenix-item-card-img")?.attr("src")
            title = element.selectFirst("h3.FJ-Home-Phoenix-item-card-title")?.text()
            source = AnimeSource.ANIME_PHOENIX.name // Add ANIMEPHOENIX to your AnimeSource enum
        }
    }


    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/?s=$query&ajax_search=true"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val animeList = document.select("div.common_card").map { searchFromElement(it) }
        val hasNextPage = document.select("div.common_card").isNotEmpty() // If results exist, assume more might exist.

        MangaPage(animeList, hasNextPage)
    }

    private fun searchFromElement(element: Element): SAnime {
        val link = element.selectFirst("a")
        return SAnime().apply {
            url = link?.attr("href") ?: ""
            thumbnail_url = link?.selectFirst("img")?.attr("src")
            title = link?.selectFirst("h6")?.text()
            source = AnimeSource.ANIME_PHOENIX.name
        }
    }


    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            title = document.selectFirst("h3.texture-text, h3.FJ-anime-title")?.text()
            thumbnail_url = document.selectFirst("img.video-banner-image")?.attr("src")
            description = document.selectFirst("p.tvshow-description span.readmore-text, .FJ-anime-description p")?.text()
            genre = document.select("ul.tvshow-geners li a, ul.FJ-category-list li a").eachText().joinToString(", ")

            // Status is tricky as it's not always on the same spot.
            status = SAnime.UNKNOWN
            document.select(".FJ-meta-list li, .FJ-info-list li").forEach {
                if (it.text().contains("الحاله")) {
                    status = if (it.text().contains("منتهي")) SAnime.COMPLETED else if (it.text().contains("مستمر")) SAnime.ONGOING else SAnime.UNKNOWN
                }
            }
            source = AnimeSource.ANIME_PHOENIX.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        // Fetch the anime main page
        val document = Jsoup.parse(
            client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string()
        )

        // Get anime/season title (same logic as version 1)
        val animeNameAsSeason = document.selectFirst("h3.texture-text, h3.FJ-anime-title")?.text()
            ?: document.selectFirst("h1.PostTitle")?.text()?.substringBefore("الحلقة")?.trim()
            ?: "Season 1"

        // Check if there’s a “Full Episodes” page (for series)
        val episodesListUrl = document.selectFirst("a.FJ-full-episodes-button")?.attr("href")
        if (episodesListUrl.isNullOrBlank()) {
            // 🎬 Handle movies / single-episode content
            return@withContext listOf(
                SEpisode().apply {
                    url = animeUrl
                    name = animeNameAsSeason // Movie title
                    episode_number = 1f
                    date_upload = System.currentTimeMillis()
                }
            )
        }

        // 📄 Fetch the dedicated episodes list page
        val episodesDocument = Jsoup.parse(
            client.newCall(Request.Builder().url(episodesListUrl).build()).execute().body!!.string()
        )

        // Extract embedded JSON data
        val scriptContent = episodesDocument.select("script#fj-episodes-script-js-extra").html()
        val pattern = Pattern.compile("var fjPageData = (\\{.*?\\});", Pattern.DOTALL)
        val matcher = pattern.matcher(scriptContent)

        if (matcher.find()) {
            val jsonData = matcher.group(1)
            val jsonObject = JSONObject(jsonData)
            val episodesArray = jsonObject.getJSONArray("episodes")

            // 🧾 Map JSON episodes to SEpisode list
            return@withContext (0 until episodesArray.length()).map { i ->
                val episodeObject = episodesArray.getJSONObject(i)
                SEpisode().apply {
                    url = episodeObject.getString("permalink")
                    val episodeTitle = episodeObject.getString("title")
                    name = "Season : $episodeTitle" // ✅ Add anime title prefix
                    val episodeNumberMatch = Regex("الحلقة\\s*(\\d+)").find(episodeTitle)
                    episode_number = episodeNumberMatch?.groupValues?.get(1)?.toFloatOrNull()
                        ?: episodeObject.optInt("id", 0).toFloat()
                    date_upload = System.currentTimeMillis()
                }
            }.reversed() // reverse for chronological order
        }

        return@withContext emptyList()
    }



    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        // If it's a movie, the watch URL is different
        val finalUrl = if (episodeUrl.contains("/movies/")) {
            episodeUrl.removeSuffix("/") + "/watch/"
        } else {
            episodeUrl
        }

        val document = Jsoup.parse(client.newCall(Request.Builder().url(finalUrl).build()).execute().body!!.string())

        // Find the video source in the main player (direct link)
        val mainPlayerSource = document.selectFirst("video#streamit_player > source")?.attr("src")

        val videos = mutableListOf<Video>()
        if (!mainPlayerSource.isNullOrBlank()) {
            Log.d("AnimePhoenix", "Found direct source: $mainPlayerSource")
            videos.add(Video(mainPlayerSource, "Sanka Server - Direct", mainPlayerSource))
        }

        // Find alternative servers from the modal
        val serverElements = document.select("a.FJ-watch-link")
        Log.d("AnimePhoenix", "Found ${serverElements.size} alternative server elements.")

        serverElements.forEach { element ->
            try {
                val serverDataJson = element.attr("data-server")
                val serverData = JSONObject(serverDataJson)
                val serverName = serverData.getString("name")
                val link = serverData.getString("link")

                when (serverData.getString("type")) {
                    "direct" -> {
                        videos.add(Video(link, serverName, link))
                    }
                    "iframe" -> {
                        val iframeUrl = Jsoup.parse(link).selectFirst("iframe")?.attr("src") ?: ""
                        if (iframeUrl.isNotBlank()) {
                            videos.addAll(extractVideosFromUrl(iframeUrl, serverName))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimePhoenix", "Failed to parse server data", e)
            }
        }

        return@withContext videos
    }

    private fun extractVideosFromUrl(url: String, quality: String): List<Video> {
        return try {
            when {
                "ok.ru" in url -> okruExtractor.videosFromUrl(url, quality)
                "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, quality)
                "dood" in url || "d-s.io" in url -> doodExtractor.videosFromUrl(url, "DoodStream")
                "streamtape" in url -> streamTapeExtractor.videosFromUrl(url, "StreamTape")
                "uqload" in url -> uqloadExtractor.videosFromUrl(url, quality)
                "fembed" in url || "femax20" in url -> mp4uploadExtractor.videosFromUrl(url, quality) // Fembed can often be extracted by Mp4upload
                VIDBOM_DOMAINS.any { url.contains(it) } -> vidBomExtractor.videosFromUrl(url)
                url.contains("wish", ignoreCase = true) -> streamWishExtractor.videosFromUrl(url)
                else -> {
                    Log.w("AnimePhoenix", "No specific extractor for: $url")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("AnimePhoenix", "Extractor failed for URL: $url", e)
            emptyList()
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())

    companion object {
        private val VIDBOM_DOMAINS = listOf("vidbom", "vidbem", "vidbm", "vedpom", "vadbom", "myviid")
    }
}