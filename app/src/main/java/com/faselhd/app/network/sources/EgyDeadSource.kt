package com.faselhd.app.network.sources

import MivalyoExtractor
import StreamGHExtractor
import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class EgyDeadSource(private val context: Context) {

    // =========================================================================
    //  THE FIX: Add an Interceptor to the client to automatically add a User-Agent header
    // =========================================================================
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0")
                    .build()
                chain.proceed(newRequest)
            }
            .build()
    }

    private val baseUrl = "https://tv2.egydead.live/"

    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val bigWarpExtractor by lazy { BigWarpExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }
    //endregion

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        val request = Request.Builder().url(baseUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.pin-posts-list li.movieItem").map {
            SAnime().apply {
                url = it.select("a").attr("href")
                title = it.select("h1.BottomTitle").text()
                thumbnail_url = it.select("a img").attr("src")
                source = AnimeSource.EGYDEAD.name
            }
        }
        MangaPage(animeList, false)
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/page/$page/").build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("section.main-section li.movieItem").map {
            SAnime().apply {
                url = it.select("a").attr("href")
                title = it.select("h1.BottomTitle").text()
                thumbnail_url = it.select("a img").attr("src")
                source = AnimeSource.EGYDEAD.name
            }
        }

        val hasNextPage = document.selectFirst("div.pagination ul.page-numbers li a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/?s=$query"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.catHolder li.movieItem").map {
            SAnime().apply {
                this.url = it.select("a").attr("href")
                this.title = it.select("h1.BottomTitle").text()
                this.thumbnail_url = it.select("a img").attr("src")
                this.source = AnimeSource.EGYDEAD.name
            }
        }

        val hasNextPage = document.selectFirst("div.pagination-two a:contains(›)") != null
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            title = document.select("div.infoBox div.singleTitle").text()
            thumbnail_url = document.select("div.single-thumbnail img").attr("src")
            description = document.select("div.infoBox div.extra-content p").text()
            genre = document.select("div.LeftBox li:contains(النوع) a, div.LeftBox li:contains(السنه) a").joinToString(", ") { it.text() }
            status = if (title!!.contains("كامل") || title!!.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
            source = AnimeSource.EGYDEAD.name
        }
    }


    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodes = mutableListOf<SEpisode>()

        val seasonElements = document.select("div.seasons-list li.movieItem a")
        if (seasonElements.isNotEmpty()) {
            // --- MULTI-SEASON SHOWS ---
            seasonElements.forEach { seasonElement ->
                val seasonDoc = Jsoup.parse(client.newCall(Request.Builder().url(seasonElement.attr("href")).build()).execute().body!!.string())
                // Get season name (e.g., "مسلسل The Walking Dead الموسم الحادي عشر")
                val seasonName = seasonDoc.select("div.infoBox div.singleTitle").text()

                val thumbinalEpi = document.select("div.infoBox div.single-thumbnail img").attr("src")
                // Get episodes and reverse their order
                val seasonEpisodes = seasonDoc.select("div.EpsList li a").map {
                    SEpisode().apply {
                        url = it.attr("href")
                        name = "$seasonName : ${it.text()}"
                        episode_number = it.text().filter { c -> c.isDigit() }.toFloatOrNull() ?: 0f
                        thumbnailUrl = thumbinalEpi
                    }
                }.reversed() // Reverse the order of episodes within this season

                episodes.addAll(seasonEpisodes)
            }
        } else if (document.select("div.EpsList li a").isNotEmpty()) {
            // --- SINGLE-SEASON SHOWS ---
            // Get the title of the current page, which serves as the season name
            val seasonName = document.select("div.infoBox div.singleTitle").text()
            val thumbinalEpi = document.select("div.infoBox div.single-thumbnail img").attr("src")
            // Get episodes and reverse their order
            val seasonEpisodes = document.select("div.EpsList li a").map {
                SEpisode().apply {
                    url = it.attr("href")
                    name = "$seasonName : ${it.text()}"
                    episode_number = it.text().filter { c -> c.isDigit() }.toFloatOrNull() ?: 0f
                    thumbnailUrl = thumbinalEpi
                }
            }.reversed() // Reverse the order of episodes within this season

            episodes.addAll(seasonEpisodes)
        } else {
            // --- MOVIES ---
            episodes.add(
                SEpisode().apply {
                    url = animeUrl
                    name = document.select("div.infoBox div.singleTitle").text() // Use movie title as name
                    episode_number = 1f
                    thumbnailUrl  = document.select("div.infoBox div.single-thumbnail img").attr("src")
                }
            )
        }

        return@withContext episodes
    }


    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder().add("View", "1").build()
        val request = Request.Builder().url(episodeUrl).post(formBody).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        document.select("ul.serversList li").flatMap {
            val url = it.attr("data-link")
            extractVideosFromUrl(url)
        }
    }

    fun getFinalDoodUrl(originalUrl: String): String {
        val client = OkHttpClient.Builder()
            .followRedirects(true) // Enable following redirects
            .followSslRedirects(true) // Enable SSL redirects
            .build()

        return try {
            val request = Request.Builder()
                .url(originalUrl)
                .head() // Use HEAD request to avoid downloading the whole body
                .build()

            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()

            finalUrl
        } catch (e: IOException) {
            e.printStackTrace()
            originalUrl // Return original URL if there's an error
        }
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
//            DOOD_REGEX.matcher(url).find() -> doodExtractor.videosFromUrl(url, "Dood")
            url.contains("d-s.io" ) || url.contains("dood" )-> {
                val doodUrl = getFinalDoodUrl(url)
                doodExtractor.videosFromUrl(doodUrl, "Doodstream")
            }
            url.contains("mixdrop" ) || url.contains("mxdrop" ) || url.contains("mx" ) -> mixDropExtractor.videosFromUrl(url)
            STREAMWISH_REGEX.matcher(url).find() -> streamWishExtractor.videosFromUrl(url)
            url.contains("uqload") || url.contains("upload") -> uqloadExtractor.videosFromUrl(url)
            url.contains("bigwarp") || url.contains("bigwarp.io") -> bigWarpExtractor.videosFromUrl(url)
            url.contains("mivalyo") || url.contains("mivalyo.com") -> mivalyoExtractor.videosFromUrl(url)
            url.contains("hglink") || url.contains("hglink.to") -> haxloppdExtractor.videosFromUrl("https://haxloppd.com/${extractHglinkId(url)}")
            url.contains("ahvsh") || url.contains("fanakishtuna") -> {
                try {
                    val doc = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
                    val script = doc.selectFirst("script:containsData(sources)")?.data() ?: ""
                    val videoUrl = Regex("""file:\s*["']([^"']+)""").find(script)?.groupValues?.get(1)
                    if (videoUrl != null) listOf(Video(videoUrl, "Mirror", videoUrl)) else emptyList()
                } catch (e: Exception) { emptyList() }
            }
            else -> emptyList()
        }
    }

    // Stubs for unused functions
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            // Fetch the homepage content
            val request = Request.Builder().url(baseUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            // The "pinned posts" section is the ideal source for the main slider
            val sliderItems = document.select("div.pin-posts-list li.movieItem").map {
                SAnime().apply {
                    url = it.select("a").attr("href")
                    title = it.select("h1.BottomTitle").text()
                    thumbnail_url = it.select("a img").attr("src")
                    source = AnimeSource.EGYDEAD.name
                }
            }
            sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // Return an empty list on error
        }
    }
    fun getFilterList() = AnimeFilterList(emptyList())

    companion object {
        private val DOOD_REGEX = Pattern.compile("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))")
        private val STREAMWISH_REGEX = Pattern.compile("ajmidyad|alhayabambi|atabknh[ks]|sbs")
    }
}