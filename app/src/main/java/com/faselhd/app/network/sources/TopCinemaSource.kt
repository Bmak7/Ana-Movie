package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.extractors.StreamTapeExtractor
import com.faselhd.app.network.extractors.UqloadExtractor
import com.faselhd.app.network.extractors.VidTubeExtractor
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TopCinemaSource(private val context: Context) {
    companion object {
        const val name = "Top Cinema"
        const val BASE_URL = "https://web6.topcinema.cam"
        const val lang = "ar"
        const val supportsLatest = true
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

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                // Note that you need to add a ResponseInterceptor to make this 100% active.
                // The server response dictates if and when stuff should be cached.
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            // Needs to be build as otherwise the other builders will change this object
            .build()
    }
//    private val client: OkHttpClient = OkHttpClient.Builder()
//        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
//        .connectTimeout(30, TimeUnit.SECONDS)
//        .readTimeout(30, TimeUnit.SECONDS)
//        .build()


    // These would be implemented to extract video links from specific hosts
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }

    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val sliderItems = document.select(".Slides--Item")
        return sliderItems.map { element ->
            SAnime().apply {
                val link = element.selectFirst("a")!!
                setUrlWithoutDomain(link.attr("href"))
                title = link.attr("title")
                thumbnail_url = link.selectFirst("img")?.attr("data-src")?:link.selectFirst("img")?.attr("src")
            }
        }
    }

    // ============================== Popular / Latest ==============================
    // The site's main listings are "latest", which we'll use for both popular and latest.
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) "$BASE_URL/recent/" else "$BASE_URL/recent/page/$page/"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(BASE_URL).build()
                val response = client.newCall(request).execute()
                val document = Jsoup.parse(response.body!!.string(), BASE_URL)
                // Find the "أفضل مسلسلات هذا الشهر" section
                val popularSection = document.select("div.Wide--Contents.Reverse.OneBox")
                    .firstOrNull { it ->
                        it.select("h3").any { h3 ->
                            h3.text().contains("أفضل مسلسلات هذا الشهر")
                        }
                    }

                val animeList = popularSection?.select("div.Small--Box")
                    ?.mapNotNull { element ->
                        try {
                            animeFromElement(element)
                        } catch (e: Exception) {
                            null
                        }
                    } ?: emptyList()

                MangaPage(animeList,   false)
            } catch (e: Exception) {
                MangaPage(emptyList(),   false)
            }
        }
    }

    private fun animeFromElement(element: Element): SAnime {
        return SAnime().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.selectFirst("h3.title")?.text() ?: link.attr("title")
            thumbnail_url =link.selectFirst("img")?.attr("data-src") ?: link.selectFirst("img")?.attr("src")

            // Extract additional information if available
            val liList = element.select("ul.liList li")
            val genres = liList.filterNot { it.hasClass("imdbRating") }
                .joinToString { it.text() }

            // Get IMDB rating if available
            val ratingElement = element.selectFirst("li.imdbRating")
            val rating = ratingElement?.text()?.substringAfter(" ") ?: ""

            // Add additional info to description or other fields
            description = buildString {
                if (genres.isNotEmpty()) {
                    append("التصنيفات: $genres\n")
                }
                if (rating.isNotEmpty()) {
                    append("التقييم: $rating")
                }
            }
        }
    }
    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("div.Wide--Contents .Posts--List .Small--Box")
        val animeList = animeElements.map(::animeFromElement)
        // TopCinema uses endless scroll, so we assume there's always a next page.
        // A more robust implementation might check if the returned list is empty.
        val hasNextPage = animeList.isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }




    // ============================== Home Page Latest Episodes ==============================
    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val episodeElements = document.select("div.Title--Box:has(h3:contains(آخر الحلقات المضافة)) + div.Box--Contents .Small--Box")
        episodeElements.map(::animeFromElement)
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
            title = document.selectFirst("h1.post-title")!!.text()
            thumbnail_url =document.selectFirst("div.MainSingle div.image img")?.absUrl("src")
            description = document.selectFirst("div.story > p")?.text()

            val details = mutableMapOf<String, String>()
            document.select("ul.RightTaxContent li").forEach { li ->
                val key = li.selectFirst("span")?.text()?.trim()
                val value = li.select("a").joinToString(", ") { it.text() }.ifEmpty { li.selectFirst("strong")?.text() }
                if (key != null && value != null) {
                    details[key] = value
                }
            }
            genre = details["نوع المسلسل :"] ?: details["نوع الفيلم :"]
            // Append other details to description for more context
            description += "\n\n" +
                    "القسم: ${details["قسم المسلسل :"] ?: details["قسم الفيلم :"] ?: "N/A"}\n" +
                    "الجودة: ${details["جودة المسلسل :"] ?: details["جودة الفيلم :"] ?: "N/A"}\n" +
                    "تاريخ الإصدار: ${details["موعد الصدور :"] ?: "N/A"}\n" +
                    "الدولة: ${details["دولة المسلسل :"] ?: "N/A"}\n"
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

        val seasons = document.select("section.allseasonss .Small--Box.Season a")
        if (seasons.isNotEmpty()) {
            // It's a series with multiple seasons
            seasons.forEach { seasonLink ->
                val seasonUrl = seasonLink.absUrl("href")
                val seasonDoc = Jsoup.connect(seasonUrl).get()
                val seasonName = seasonLink.selectFirst(".epnum")?.text() ?: "الموسم"
                seasonDoc.select("section.allepcont .row a").forEach { episodeLink ->
                    episodes.add(episodeFromElement(episodeLink, seasonName))
                }
            }
        } else {
            // It's a single season series or a movie
            val episodeElements = document.select("section.allepcont .row a")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { episodes.add(episodeFromElement(it, "الموسم 1")) }
            } else {
                // It's a movie, create a single "watch" episode
                val watchLink = document.selectFirst(".BTNSDownWatch a.watch")?.absUrl("href")
                val thumbnail_url = document.selectFirst("img")?.absUrl("data-src") ?: document.selectFirst("img")?.absUrl("src")
                if(watchLink != null) {
                    val movieEpisode = SEpisode().apply {
                        url = watchLink
                        name = "مشاهدة الفيلم"
                        episode_number = 1.0f
                        thumbnailUrl = thumbnail_url
                    }
                    episodes.add(movieEpisode)
                }
            }
        }
        return episodes.reversed() // Typically, sites list newest first, so we reverse
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = "$seasonName: ${element.selectFirst(".ep-info h2")?.text() ?: "حلقة"}"
            val epNumStr = element.selectFirst(".epnum")?.text()?.filter { it.isDigit() }
            episode_number = epNumStr?.toFloatOrNull() ?: 1.0f
            thumbnailUrl =element.selectFirst("img")?.absUrl("data-src") ?: element.selectFirst("img")?.absUrl("src")
        }
    }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        // Ensure episode URL has /watch/
        val watchUrl = if (episodeUrl.endsWith("/")) "${episodeUrl}watch/" else "$episodeUrl/watch/"
        Log.d("VideoFetcher", "📡 Fetching watch page: $watchUrl")

        val request = Request.Builder().url(watchUrl).build()
        val response = client.newCall(request).execute()

        val videos = videoListParse(response)
        Log.d("VideoFetcher", "✅ Extracted ${videos.size} videos from $watchUrl")

        return@withContext videos
    }

    private fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val videos = mutableListOf<Video>()
        val servers = document.select("div.watch--servers--list li.server--item")

        // Get the initially loaded iframe (default server)
        val initialIframe = document.selectFirst("div.player--iframe iframe")?.attr("src")
        Log.d("VideoFetcher", "🎬 Found servers: ${servers.eachText()}")
        Log.d("VideoFetcher", "🎬 Initial iframe: $initialIframe")

        if (initialIframe != null) {
            val serverName = servers.firstOrNull()?.text() ?: "Default Server"
            videos.addAll(extractVideosFromServer(initialIframe, serverName))
        }

        return videos
    }

    private fun extractVideosFromServer(url: String, quality: String): List<Video> {
        Log.d("VideoFetcher", "🔎 Extracting from server ($quality): $url")

        return when {
            "uqload" in url -> uqloadExtractor.videosFromUrl(url, quality)
            "streamtape" in url -> streamtapeExtractor.videosFromUrl(url)

            // vidtube extractor (iframe inside the watch page)
            "vidtube" in url || "vidbam" in url || "vidshar" in url -> {
                Log.d("VideoFetcher", "🔎 Extracting from vidtube server ($quality): $url")
                vidTubeExtractor.videosFromUrl(url)
            }

            else -> {
                Log.w("VideoFetcher", "⚠️ No extractor for: $url")
                emptyList()
            }
        }
    }

    /**
     * Fetch Vidtube iframe and extract direct video URLs
     */
    private fun fetchAndExtractVidtube(url: String): List<Video> {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://web6.topcinema.cam/") // required by most hosts
            .build()

        client.newCall(request).execute().use { response ->
            val html = response.body!!.string()
            Log.d("VideoFetcher", "📄 Vidtube iframe response length=${html.length}")

            // Vidtube usually hides sources in a <script> like: sources: [{file:"https://...m3u8"}]
            val regex = Regex("""file["']\s*:\s*["'](https?://[^"']+)["']""")
            val matches = regex.findAll(html)

            val videos = matches.map { match ->
                val videoUrl = match.groupValues[1]
                Log.d("VideoFetcher", "🎯 Extracted Vidtube URL: $videoUrl")
                Video(
                    url = videoUrl,
                    quality = "Auto",
                    videoUrl = videoUrl,
                    headers = mapOf("Referer" to url)
                )
            }.toList()

            return videos
        }
    }


    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) {
            "$BASE_URL/search/?query=${URLEncoder.encode(query, "UTF-8")}&type=all"
        } else {
            "$BASE_URL/search/?query=${URLEncoder.encode(query, "UTF-8")}&type=all&offset=$page"
        }

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response, page)
    }

    private fun searchParse(response: Response, page: Int): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("ul.Posts--List.SixInRow div.Small--Box")
        val animeList = animeElements.map(::animeFromElementt)

        // Check if there's a next page by looking for pagination elements
        val pagination = document.select("div.paginate ul.page-numbers")
        val hasNextPage = pagination.select("a.next.page-numbers").isNotEmpty() ||
                pagination.select("a.page-numbers[href*=\"offset=${page + 1}\"]").isNotEmpty()

        return MangaPage(animeList, hasNextPage)
    }

    // Helper function to parse anime from search result element
    private fun animeFromElementt(element: Element): SAnime {
        val link = element.select("a.recent--block").first()!!
        val url = link.attr("href")
        val title = link.select("h3.title").text()
        val thumbnail = link.select("div.Poster img").attr("data-src")?:link.select("div.Poster img").attr("src")

        return SAnime().apply {
            this.url = url
            this.title = title
            this.thumbnail_url = thumbnail
        }
    }


    // ============================== Filters ==============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // Site doesn't have easily accessible filters
}