package com.faselhd.app.network.sources

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ZimabadkSource(private val context: Context) {

    // SSL and OkHttpClient setup remains the same as it's robust for handling various sites.
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

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        val cookieJar = object : CookieJar {
            private val cookieStore = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        }

        OkHttpClient.Builder()
            .cookieJar(cookieJar) // This automatically handles cookies
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .build()
                chain.proceed(request)
            }
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

    private val baseUrl = "https://www.zimabadk.com"

    // --- Extractors for video servers ---
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
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val fourSharedExtractor by lazy { FourSharedExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }


    private val megaMaxExtractor by lazy { MegaMaxExtractor(client, doodExtractor, voeExtractor, mixDropExtractor, streamWishExtractor, streamTapeExtractor, mp4uploadExtractor, vidTubeExtractor= vidTubeExtractor,mivalyoExtractor = mivalyoExtractor, luluStream1Extractor = luluStream1Extractor, filemoonExtractor = filemoonExtractor) }

    // ============================== Popular & Latest ===============================

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false) // Slider is only on the first page

        val url = "$baseUrl/home"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.slider .owl-item:not(.cloned) .blockSlider").map {
            popularFromElement(it)
        }
        MangaPage(animeList, false) // Slider is not paginated
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // The "latest episodes" section seems to be under this category page.
        val url = "$baseUrl/category/قائمة-الانمي/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())

        val episodeList = document.select("div.post-container article.post").map { element ->
            SAnime().apply {
                val linkElement = element.selectFirst("div.postBlockOne a")
                this.url = linkElement?.attr("href") ?: ""
                this.title = element.selectFirst("h3.title")?.text() ?: "No Title"
                val imageElement = element.selectFirst("div.poster img.imgLoaded")
                this.thumbnail_url = imageElement?.attr("data-img") ?: imageElement?.attr("src")
                this.source = AnimeSource.ZIMABADK.name
            }
        }
        // Zimabadk uses a "Load More" button, so we check if a certain number of items were returned.
        val hasNextPage = episodeList.size >= 12
        MangaPage(episodeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        return SAnime().apply {
            url = element.selectFirst(".buttons a.btn-green")?.attr("href") ?: ""
            val image = element.selectFirst(".poster img.imgLoaded")
            thumbnail_url = image?.attr("data-img") ?: image?.attr("src")
            title = element.selectFirst(".blockSliderInfo h3")?.text() ?: "No Title"
            source = AnimeSource.ZIMABADK.name
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        // Search URL structure for zimabadk
        val searchUrl = "$baseUrl/?s=${query.replace(" ", "+")}&type=anime"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(searchUrl).build()).execute().body!!.string())

        val animes = document.select("div.post-container article").mapNotNull { container ->
            val linkElement = container.selectFirst("a.anime")
            val titleElement = container.selectFirst("h3.title")
            val imageElement = container.selectFirst("img.imgLoaded")

            if (linkElement != null && titleElement != null) {
                SAnime().apply {
                    url = linkElement.attr("href")
                    title = titleElement.text()
                    thumbnail_url = imageElement?.attr("data-img") ?: imageElement?.attr("src")
                    source = AnimeSource.ZIMABADK.name
                    status = parseStatus(container.selectFirst("span.status")?.text())
                }
            } else {
                null
            }
        }
        // Search results are not paginated
        return@withContext MangaPage(animes, hasNextPage = false)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        var currentUrl = animeUrl
        var doc = Jsoup.parse(client.newCall(Request.Builder().url(currentUrl).build()).execute().body!!.string())

        // Handle case where the URL is an episode link by finding the canonical anime link from breadcrumbs
        if (!currentUrl.contains("/anime/")) {
            val animeLink = doc.selectFirst("#breadcrumbs a[href*='/anime/']")
            if (animeLink != null) {
                currentUrl = animeLink.attr("href")
                doc = Jsoup.parse(client.newCall(Request.Builder().url(currentUrl).build()).execute().body!!.string())
            }
        }

        return@withContext SAnime().apply {
            this.url = currentUrl
            this.source = AnimeSource.ZIMABADK.name

            val content = doc.selectFirst("div.singleContent") ?: doc
            this.title = content.selectFirst("h1.title")?.text() ?: ""
            this.thumbnail_url = content.selectFirst("div.singleThumb img")?.attr("src")
            this.description = content.selectFirst("div.story p")?.text()
            this.genre = content.select("ul.tax li:has(span:contains(الانواع)) a").joinToString(", ") { it.text() }

            val statusText = content.selectFirst("ul.tax li:has(span:contains(حالة الانمي)) strong")?.text()
            this.status = parseStatus(statusText)
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())

        val animeTitle = document.selectFirst("h1.title")?.text() ?: ""

        // All episodes for a season are on the same page
        val episodeElements = document.select("ul#episodesList li")

        val episodes = episodeElements.map { element ->
            SEpisode().apply {
                val link = element.selectFirst("a")
                url = link!!.attr("href")
                name = "$animeTitle : ${link.attr("title")}" // e.g., "Kimetsu no Yaiba : الحلقة 1"

                val epNumStr = element.selectFirst("em")?.text()?.trim()
                episode_number = epNumStr?.toFloatOrNull() ?: 0f

                date_upload = System.currentTimeMillis() // Site does not provide upload dates
            }
        }

        return@withContext episodes.reversed() // Reverse to have episode 1 first
    }

    // ============================ Video Links =============================
    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val watchUrl = if (episodeUrl.endsWith("/watch/")) episodeUrl else "$episodeUrl/watch/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(watchUrl).build()).execute().body!!.string())

        // The default server is loaded in an iframe directly
        val embedUrl = document.selectFirst("div.postEmbed iframe")?.attr("src")

        return@withContext if (embedUrl != null) {
            extractVideosFromUrl(normalizeUrl(embedUrl))
        } else {
            emptyList()
        }
        // Note: Scraping other servers would require reverse-engineering their JS onClick functions,
        // which is complex. We are extracting the default server which is the most reliable.
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        return when {
//            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
//            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url)
//            "dood" in url || "d-s.io" in url -> doodExtractor.videosFromUrl(url)
//            "streamtape" in url -> streamTapeExtractor.videosFromUrl(url)
//            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
//            "4shared" in url -> fourSharedExtractor.videosFromUrl(url)
            "megamax" in url -> megaMaxExtractor.videosFromUrl(url)
//            "yourupload" in url -> yourUploadExtractor.videosFromUrl(url)
//            "voe.sx" in url -> voeExtractor.videosFromUrl(url)
//            "wish" in url || "videas" in url -> streamWishExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when {
            statusString?.contains("يعرض الآن") == true || statusString?.contains("يُعرض الآن") == true -> SAnime.ONGOING
            statusString?.contains("مكتمل") == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on site
}