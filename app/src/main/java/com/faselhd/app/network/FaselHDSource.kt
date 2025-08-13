package com.faselhd.app.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.faselhd.app.models.*
import com.faselhd.app.utils.PlaylistUtils
import com.faselhd.app.utils.Tls12SocketFactory
import com.faselhd.app.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.internal.userAgent
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class FaselHDSource(private val context: Context)  {
    companion object {
        const val name = "فاصل اعلاني"

        private const val PREFS_NAME = "FaselHD_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val DEFAULT_BASE_URL = "https://www.faselhds.life"
//        "https://www.faselhd.pro"


        fun getBaseUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        }

        fun setBaseUrl(context: Context, newUrl: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BASE_URL, newUrl).apply()
        }

        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

    private val baseUrl: String
        get() = getBaseUrl(context)

    private val client: OkHttpClient by lazy {
        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", USER_AGENT)
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

    // ============================== Popular ===============================
    private fun popularSeriesSelector(): String = "div#postList div.col-xl-2 a"

    private fun popularSeriesRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/series/page/$page")
            .build()
    }

    private fun popularSeriesFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    private fun popularSeriesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = popularSeriesRequest(page)
        val response = client.newCall(request).execute()
        popularSeriesParse(response)
    }

    private fun popularSeriesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(popularSeriesSelector())
        val animeList = animeElements.map { popularSeriesFromElement(it) }
        val hasNextPage = document.selectFirst(popularSeriesNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }

    // --- NEW FUNCTION TO GET THE MAIN SLIDER ITEMS ---
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        // The slider is on the base URL
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        val slideElements = document.select("div#homeSlide div.swiper-slide")

        return slideElements.map { element ->
            val anime = SAnime()
            val linkElement = element.select("div.h1 a")
            anime.setUrlWithoutDomain(linkElement.attr("href"))
            anime.title = linkElement.text()

            // The background image is in a style attribute, which is harder to parse reliably.
            // Let's use the poster image for consistency.
            anime.thumbnail_url = element.select("div.poster img").attr("src")

            // We can also extract the description
            anime.description = element.select("p").first()?.text()

            anime
        }
    }

    // --- NEW FUNCTION TO GET LATEST EPISODES FROM HOME PAGE ---
    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        homePageLatestEpisodesParse(response)
    }

    private fun homePageLatestEpisodesParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        val episodeElements = document.select("section#blockList.blockAlt .epDivHome")

        return episodeElements.map { element ->
            val anime = SAnime()
            val linkElement = element.select("a.epHomeImg")
            anime.setUrlWithoutDomain(linkElement.attr("href"))
            anime.title = element.select("div.h4").text()
            anime.thumbnail_url = element.select("img").attr("data-src")
            anime
        }
    }

    // --- NEW FUNCTION TO GET ONLY SEASONS ---
    suspend fun fetchSeasonList(animeUrl: String): List<SSeason> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        seasonListParse(response)
    }

    private fun seasonListParse(response: Response): List<SSeason> {
        val document = Jsoup.parse(response.body!!.string())
        val seasonElements = document.select("div#seasonList div.col-xl-2")

        // If there's only one season, it might not have the selector.
        // In that case, we create a "default" season using the main page.
        if (seasonElements.isEmpty()) {
            return listOf(SSeason(
                name = "Season 1",
                url = response.request.url.toString() // The URL itself is the season page
            ))
        }

        return seasonElements.map { element ->
            val seasonDiv = element.select("div.seasonDiv")
            val onclickAttr = seasonDiv.attr("onclick")
            // Example: postA('12345') -> we need to extract 12345
            val seasonId = onclickAttr.substringAfter("('").substringBefore("')")

            SSeason(
                name = seasonDiv.select("div.title").text(),
                // The URL is now a request to the server's AJAX endpoint
                url = "$baseUrl/?p=$seasonId"
            )
        }
    }


    // ============================== Episodes ==============================
    private fun episodeListSelector() = "div.epAll a"

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div#seasonList div.col-xl-2:nth-child($seasonNumber)"


    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        episodeListParse(response)
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1

        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "مشاهدة"
            return episode
        }

        fun addEpisodes(document: Document) {
            val episodeElements = document.select(episodeListSelector())
            if (episodeElements.isEmpty()) {
                document.select("div.shortLink").forEach { episodes.add(episodeExtract(it)) }
            } else {
                episodeElements.forEach { episodes.add(episodeFromElement(it)) }
                document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    val onclickAttr = it.select("div.seasonDiv").attr("onclick")
                    val seasonId = onclickAttr.substringAfterLast("=").substringBeforeLast("'")

                    try {
                        val seasonRequest = Request.Builder()
                            .url("$baseUrl/?p=$seasonId")
                            .build()
                        val seasonResponse = client.newCall(seasonRequest).execute()
                        addEpisodes(Jsoup.parse(seasonResponse.body!!.string()))
                    } catch (e: Exception) {
                        // Handle error silently for now
                    }
                }
            }
        }

        val document = Jsoup.parse(response.body!!.string())
        addEpisodes(document)
        return episodes
    }

    private fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        val seasonTitle = element.ownerDocument()?.select("div.seasonDiv.active > div.title")?.text() ?: ""
        episode.name = "$seasonTitle : ${element.text()}"
        episode.episode_number = element.text().replace("الحلقة ", "").toFloatOrNull() ?: -1f
        return episode
    }

//    // ============================ Video Links =============================
//    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
//        val request = Request.Builder()
//            .url(if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl")
//            .build()
//        val response = client.newCall(request).execute()
//        videoListParse(response)
//    }
//
//    private fun videoListParse(response: Response): List<Video> {
//        val document = Jsoup.parse(response.body!!.string())
//        val iframe = document.selectFirst("iframe")?.attr("src")?.substringBefore("&img")
//
//        return if (!iframe.isNullOrBlank()) {
//            // For now, return the iframe URL as a video source
//            // In a real implementation, you'd need to resolve this further
//            listOf(Video(iframe, "1080p", iframe))
//        } else {
//            emptyList()
//        }
//    }

    // +++ NEW COMPONENTS +++
    private val webViewResolver by lazy { WebViewResolver(context) }
    private val playlistUtils by lazy { PlaylistUtils(client) }


    // ============================ Video Links (FINAL VERSION) =============================

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val absoluteUrl = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
        val request = Request.Builder().url(absoluteUrl).build()
        val response = client.newCall(request).execute()

        videoListParse(response)
    }

    private suspend fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        val iframeUrl = document.selectFirst("iframe")?.attr("src")?.substringBefore("&img")
        println("iframeUrl fasel : $iframeUrl")
        if (iframeUrl.isNullOrBlank()) {
            return emptyList()
        }

        // Pass empty headers for now, can be customized later if needed
        val hlsUrl = webViewResolver.getUrl(iframeUrl, emptyMap())
        println("videos323344dcw : $hlsUrl")
        return if (hlsUrl.isNotBlank()) {
            playlistUtils.extractFromHls(hlsUrl)
        } else {
            emptyList()
        }
    }

    // ++ ADD THIS NEW HELPER FUNCTION ++
    suspend fun getHlsUrlFromEpisode(episodeUrl: String): String? = withContext(Dispatchers.IO) {
        val absoluteUrl = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl"
        val request = Request.Builder().url(absoluteUrl).build()
        val response = client.newCall(request).execute()

        val document = Jsoup.parse(response.body!!.string())
        val iframeUrl = document.selectFirst("iframe")?.attr("src")?.substringBefore("&img")

        if (iframeUrl.isNullOrBlank()) {
            return@withContext null
        }


        // Return the HLS URL directly
        webViewResolver.getUrl(iframeUrl, emptyMap())
    }

    // =============================== Search ===============================
    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img, img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img, img").attr("data-src")
        return anime
    }

    private fun searchAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"
    private fun searchAnimeSelector(): String = "div#postList div.col-xl-2 a"

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sectionFilter = filters.find<SectionFilter>()
        val categoryFilter = filters.find<CategoryFilter>()
        val genreFilter = filters.find<GenreFilter>()

        return if (query.isNotBlank()) {
            Request.Builder()
                .url("$baseUrl/page/$page?s=$query")
                .build()
        } else {
            val urlBuilder = StringBuilder(baseUrl)

            when {
                sectionFilter != null && sectionFilter.state != 0 -> {
                    urlBuilder.append("/${sectionFilter.toUriPart()}")
                }
                categoryFilter != null && categoryFilter.state != 0 -> {
                    urlBuilder.append("/${categoryFilter.toUriPart()}")
                    if (genreFilter != null) {
                        urlBuilder.append("/${genreFilter.toUriPart().lowercase()}")
                    }
                }
                else -> throw Exception("من فضلك اختر قسم او نوع")
            }

            urlBuilder.append("/page/$page")
            Request.Builder()
                .url(urlBuilder.toString())
                .build()
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val request = searchAnimeRequest(page, query, filters)
        val response = client.newCall(request).execute()
        searchAnimeParse(response)
    }

    private fun searchAnimeParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(searchAnimeSelector())
        val animeList = animeElements.map { searchAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response)

    }

    private fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string())
        val anime = SAnime()

        anime.title = document.select("meta[itemprop=name]").attr("content")
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a")
            .joinToString(", ") { it.text() }

        val cover = document.select("div.posterImg img.poster").attr("src")
        anime.thumbnail_url = if (cover.isNullOrEmpty()) {
            document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        } else {
            cover
        }

        anime.description = document.select("meta[itemprop=description]").attr("content")
        anime.status = parseStatus(
            document.select("span:contains(حالة)").text()
                .replace("حالة ", "")
                .replace("المسلسل : ", "")
        )

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "مستمر" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // =============================== Latest ===============================
    private fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    private fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    private fun latestUpdatesRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/most_recent/page/$page")
            .build()
    }

    private fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(latestUpdatesSelector())
        val animeList = animeElements.map { latestUpdatesFromElement(it) }
        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }

    // ============================ Filters =============================
    fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            listOf(
                AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارع"),
                SectionFilter(),
                AnimeFilter.Separator(),
                AnimeFilter.Header("الفلتره تعمل فقط لو كان اقسام الموقع على 'اختر'"),
                CategoryFilter(),
                GenreFilter()
            )
        )
    }

    // =========================== Latest Added Movies ============================
    private fun latestAddedMoviesSelector(): String = "div#postList div.col-xl-2 a"

    private fun latestAddedMoviesRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/movies/page/$page")
            .build()
    }

    private fun latestAddedMoviesFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    private fun latestAddedMoviesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    suspend fun fetchLatestAddedMovies(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = latestAddedMoviesRequest(page)
        val response = client.newCall(request).execute()
        latestAddedMoviesParse(response)
    }

    private fun latestAddedMoviesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(latestAddedMoviesSelector())
        val animeList = animeElements.map { latestAddedMoviesFromElement(it) }
        val hasNextPage = document.selectFirst(latestAddedMoviesNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }





    // =========================== Best Series this Month ============================
    private fun bestSeriesThisMonthSelector(): String = "div#postList div.col-xl-2 a"

    private fun bestSeriesThisMonthRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/series/page/$page")
            .build()
    }

    private fun bestSeriesThisMonthFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    private fun bestSeriesThisMonthNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    suspend fun fetchBestSeriesThisMonth(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = bestSeriesThisMonthRequest(page)
        val response = client.newCall(request).execute()
        bestSeriesThisMonthParse(response)
    }

    private fun bestSeriesThisMonthParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(bestSeriesThisMonthSelector())
        val animeList = animeElements.map { bestSeriesThisMonthFromElement(it) }
        val hasNextPage = document.selectFirst(bestSeriesThisMonthNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }

}





