package com.faselhd.app.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.faselhd.app.models.*
import com.faselhd.app.network.extractors.UqloadExtractor
import com.faselhd.app.network.extractors.VidBomExtractor
import com.faselhd.app.utils.PlaylistUtils
import com.faselhd.app.utils.Tls12SocketFactory
import com.faselhd.app.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class MyCimaSource(private val context: Context) {
    companion object {
        const val name = "MY Cima"

        private const val PREFS_NAME = "MyCima_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val DEFAULT_BASE_URL = "https://wecima.show"

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
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
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

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    // ============================== Popular ==============================
    private fun popularAnimeSelector(): String =
        "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    private fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    private fun popularAnimeRequest(page: Int): Request =
        Request.Builder()
            .url("$baseUrl/seriestv/top/?page_number=$page")
            .build()

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url =
            element.select("a > span.BG--GridItem")
                .attr("data-lazy-style")
                .substringAfter("-image:url(")
                .substringBefore(");")
        return anime
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = popularAnimeRequest(page)
        val response = client.newCall(request).execute()
        popularAnimeParse(response)
    }

    private fun popularAnimeParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(popularAnimeSelector())
        val animeList = animeElements.map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
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
        val gridItems = document.select("div.GridItem")

        return gridItems.map { element ->
            val anime = SAnime()
            val linkElement = element.select("div.Thumb--GridItem a")
            anime.setUrlWithoutDomain(linkElement.attr("href"))
            anime.title = linkElement.select("strong.hasyear").text()


            val style = linkElement.select("span.BG--GridItem").attr("data-lazy-style")
            val thumbnailUrl = style.substringAfter("--image:url(").substringBefore(");")
            anime.thumbnail_url = thumbnailUrl



            anime
        }
    }


    // ============================== Episodes ==============================
    private fun episodeListSelector() = "div.Episodes--Seasons--Episodes a"

    private fun seasonsListSelector() = "div.List--Seasons--Episodes a"

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        episodeListParse(response)
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        return if (document.select(episodeListSelector()).isNullOrEmpty()) {
            val movieSeries =
                document.select("singlerelated.hasdivider:contains(سلسلة) div.Thumb--GridItem a")
            if (movieSeries.isNotEmpty()) {
                movieSeries.sortedByDescending {
                    it.selectFirst(".year")!!.text().let(::getNumberFromEpsString)
                }.map(::mSeriesEpisode)
            } else {
                document.selectFirst("div.Poster--Single-begin > a")!!.let(::movieEpisode)
            }
        } else {
            val seasonsList = document.select(seasonsListSelector())
            if (seasonsList.isNullOrEmpty()) {
                document.select(episodeListSelector()).map(::newEpisodeFromElement)
            } else {
                seasonsList.reversed().flatMap { season ->
                    val seNum = season.text().let(::getNumberFromEpsString)
                    if (season.hasClass("selected")) {
                        document.select(episodeListSelector())
                            .map { newEpisodeFromElement(it, seNum) }
                    } else {
                        val seasonDoc =
                            client.newCall(Request.Builder().url(season.absUrl("href")).build())
                                .execute().let { Jsoup.parse(it.body!!.string()) }
                        seasonDoc.select(episodeListSelector())
                            .map { newEpisodeFromElement(it, seNum) }
                    }
                }
            }
        }
    }

    private fun movieEpisode(element: Element): List<SEpisode> =
        newEpisodeFromElement(element, type = "movie").let(::listOf)

    private fun mSeriesEpisode(element: Element): SEpisode {
        val episode = newEpisodeFromElement(element, type = "mSeries")
        // Additional thumbnail handling if needed
        if (episode.thumbnailUrl.isNullOrEmpty()) {
            episode.thumbnailUrl = element.selectFirst("span.BG--GridItem")?.attr("data-lazy-style")
                ?.substringAfter("--image:url(")?.substringBefore(");") ?: ""
        }
        return episode
    }

    private fun newEpisodeFromElement(
        element: Element,
        seNum: String = "1",
        type: String = "series",
    ): SEpisode {
        val episode = SEpisode()
        episode.setUrlWithoutDomain(
            when (type) {
                "series" -> element.select("a").attr("href")
                else -> element.absUrl("href")
            },
        )
        episode.name = when (type) {
            "series" -> "الموسم $seNum : ${element.text()}"
            "mSeries" -> element.text().replace("مشاهدة فيلم ", "").substringBefore("مترجم")
            else -> "مشاهدة"
        }
        episode.episode_number = when (type) {
            "series" -> "$seNum.${element.text().let(::getNumberFromEpsString)}".toFloat()
            else -> 1F
        }

        // Add thumbnail URL
        episode.thumbnailUrl = when (type) {
            "series" -> element.ownerDocument()?.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            "mSeries" -> element.selectFirst("span.BG--GridItem")?.attr("data-lazy-style")
                ?.substringAfter("--image:url(")?.substringBefore(");") ?: ""
            else -> element.ownerDocument()?.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        }

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl$episodeUrl")
            .build()
        val response = client.newCall(request).execute()
        videoListParse(response)
    }

    private fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select(videoListSelector())
            .mapNotNull(::extractVideos)
            .flatten()
    }

    private fun extractVideos(element: Element): List<Video>? {
        val iframeUrl = element.selectFirst("btn")?.absUrl("data-url") ?: return null
//        val newHeader = Headers.Builder().add("referer", "$baseUrl/").build()
        val newHeader = mapOf("referer" to "https://wecinma.video/")
        val iframeTxt = element.text().lowercase()

        return when {
            element.hasClass("MyCimaServer") && "/run/" in iframeUrl -> {
                val mp4Url = iframeUrl.replace("?Key", "/?Key") + "&auto=true"
                listOf(Video(mp4Url, "Default (may take a while)", mp4Url, "?x?", newHeader))
            }
            "govid" in iframeTxt || "vidbom" in iframeTxt || "vidshare" in iframeTxt -> {
                // Use GoVadExtractor for these
                extractGoVadVideos(iframeUrl)
            }
            "uqload" in iframeTxt -> {
                uqloadExtractor.videosFromUrl(iframeUrl)
            }

            "vidbom" in iframeTxt -> {
                vidBomExtractor.videosFromUrl(iframeUrl)
            }



            else -> emptyList()
        }
    }

    private fun extractGoVadVideos(url: String): List<Video> {
        return try {
            val doc = client.newCall(Request.Builder().url(url).build()).execute()
                .let { Jsoup.parse(it.body!!.string()) }
            val script = doc.selectFirst("script:containsData(sources)")
            if (script != null) {
                val data = script.data().substringAfter("sources: [").substringBefore("],")
                data.split("file:\"").drop(1).map { source ->
                    val src = source.substringBefore("\"")
                    var quality = source.substringAfter("label:\"").substringBefore("\"")
                    if (quality.length > 15) {
                        quality = "720p"
                    }
                    Video(src, "MyCima: $quality", src, "?x?")
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun videoListSelector() = "ul.WatchServersList li"

    // ============================== Search ==============================
    private fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    private fun searchAnimeSelector(): String = popularAnimeSelector()

    suspend fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
        type: String = "movie"
    ): MangaPage = withContext(Dispatchers.IO) {
        val request = searchAnimeRequest(page, query, filters, type)
        val response = client.newCall(request).execute()
        searchAnimeParse(response)
    }

    private fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
        type: String
    ): Request {
        val url = baseUrl + when (type) {
            "movie" -> "/search/$query/?page_number=$page"
            "series" -> "/search/$query/list/series/?page_number=$page"
            "anime" -> "/search/$query/list/anime/?page_number=$page"
            else -> "/search/$query/?page_number=$page" // Default to movie search
        }
        return Request.Builder().url(url).build()
    }

    private fun searchAnimeParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(searchAnimeSelector())
        val animeList = animeElements.map { searchAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Details ==============================
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

        anime.url = response.request.url.toString()
        anime.title = when {
            document.selectFirst("li:contains(المسلسل) p") != null -> {
                document.select("li:contains(المسلسل) p").text()
            }
            document.selectFirst("singlerelated.hasdivider:contains(سلسلة) a") != null -> {
                document.selectFirst("singlerelated.hasdivider:contains(سلسلة) a")!!.text()
            }
            else -> {
                document.select("div.Title--Content--Single-begin > h1").text()
                    .substringBefore(" (").replace("مشاهدة فيلم ", "").substringBefore("مترجم")
            }
        }

        anime.thumbnail_url =
            document.selectFirst("meta[property=og:image]")?.attr("content")

        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent").text()
//        anime.author = document.select("li:contains(شركات الإنتاج) > p > a").joinToString(", ") { it.text() }

        // add alternative name to anime description
        document.select("li:contains( بالعربي) > p, li:contains(معروف) > p").text().let {
            if (it.isNotEmpty()) {
                anime.description += when {
                    anime.description!!.isEmpty() -> "Alternative Name: $it"
                    else -> "\n\nAlternative Name: $it"
                }
            }
        }

        return anime
    }

    // ============================== Latest ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    private fun latestUpdatesRequest(page: Int): Request =
        Request.Builder().url("$baseUrl/page/$page").build()

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(popularAnimeSelector())
        val animeList = animeElements.map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return MangaPage(animeList, hasNextPage)
    }



}







