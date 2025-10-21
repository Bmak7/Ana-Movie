package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CimaLightSource(private val context: Context) {
    companion object {
        const val name = "Cima Light"
        const val BASE_URL = "https://w.cimalight.co"
        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
        private val urlRegex = Regex("""url\('?(.*?)'?\)""")
        private const val TAG = "CimaLightSource" // Tag for logging
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

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val bigWarpExtractor by lazy { BigWarpExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client) } // New Extractor


//    private val anafastsExtractor by lazy { AnafastsExtractor(client) }
//    private val vidspeedsExtractor by lazy { VidspeedsExtractor(client) }
//    private val vidrobaExtractor by lazy { VidrobaExtractor(client) }


    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL/main10").build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val sliderItems = document.select(".sSlide.feat .pm-ul-carousel-videos .block-post")
        return sliderItems.mapNotNull { element ->
            try {
                SAnime().apply {
                    val link = element.selectFirst("a")!!
                    setUrlWithoutDomain(link.attr("href"))
                    title = element.selectFirst(".title")?.text() ?: "No Title"
                    val style = element.selectFirst(".imgSer")?.attr("style")
                    thumbnail_url = style?.let { urlRegex.find(it)?.groupValues?.get(1) }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ============================== Popular / Latest ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/episodes.php?page=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/topvideos.php?page=$page"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                latestUpdatesParse(response)
            } catch (e: Exception) {
                MangaPage(emptyList(), false)
            }
        }
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("ul.pm-ul-browse-videos > li")
        val animeList = animeElements.mapNotNull { element ->
            try {
                animeFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination a:contains(»)") != null
        return MangaPage(animeList, hasNextPage)
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
            title = document.selectFirst("h1[itemprop=name]")?.text() ?: "No Title"
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("div[itemprop=description] p")?.text()

            val details = mutableMapOf<String, String>()
            document.select("dl.dl-horizontal dt").forEach { dt ->
                val key = dt.text().trim()
                val value = dt.nextElementSibling()?.text()?.trim()
                if (value != null) {
                    details[key] = value
                }
            }
            genre = details["الاقسام"]
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

        val seasonsTabs = document.select("div.SeasonsBoxUL button.tablinks")
        if (seasonsTabs.isNotEmpty()) {
            // It's a series
            seasonsTabs.forEach { seasonButton ->
                val seasonName = seasonButton.text()
                val seasonId = seasonButton.attr("onclick").substringAfter("'").substringBefore("'")
                document.select("div#$seasonId ul a").forEach { episodeLink ->
                    episodes.add(episodeFromElement(episodeLink, seasonName, document))
                }
            }
        } else {
            // It's a movie
            if (document.selectFirst("div#player") != null) {
                val movieEpisode = SEpisode().apply {
                    url = document.location()
                    name = "مشاهدة الفيلم"
                    episode_number = 1.0f
                    thumbnailUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
                }
                episodes.add(movieEpisode)
            }
        }
        return episodes.reversed()
    }

    private fun episodeFromElement(element: Element, seasonName: String, document: Document): SEpisode {
        return SEpisode().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = "$seasonName: ${element.attr("title")}"
            val epNumStr = element.text().filter { it.isDigit() }
            episode_number = epNumStr.toFloatOrNull() ?: 1.0f
            thumbnailUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📡 Fetching page: $episodeUrl")
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), episodeUrl)

        val adLink = document.selectFirst("a.xtgo")?.attr("href")
        if (adLink != null) {
            Log.d(TAG, "🔗 Following ad link: $adLink")
            val adRequest = Request.Builder().url(adLink).header("Referer", episodeUrl).build()
            val adResponse = client.newCall(adRequest).execute()
            return@withContext videoListParse(adResponse)
        } else {
            Log.w(TAG, "⚠️ No ad link (xtgo) found on page. Trying direct parse.")
            return@withContext videoListParse(response) // Fallback to parsing the original page
        }
    }

    private fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val allVideos = mutableListOf<Video>()
        val servers = document.select("div.embeding ul li")

        Log.d(TAG, "🎬 Found ${servers.size} servers on host page.")

        for (serverElement in servers) {
            val embedUrl = serverElement.attr("data-embed")
            val serverName = serverElement.text()
            if (embedUrl.isNotBlank()) {
                allVideos.addAll(extractVideosFromServer(embedUrl, serverName))
            }
        }
        return allVideos
    }

    private fun extractVideosFromServer(url: String, quality: String): List<Video> {
        Log.d(TAG, "🔎 Extracting from server ($quality): $url")
        return when {
            "dood" in url -> doodExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url, quality)
            "streamtape" in url -> streamtapeExtractor.videosFromUrl(url)
            "vidtube" in url ||"vidspeeds" in url ||"vidroba" in url || "vid" in url -> vidTubeExtractor.videosFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url)
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "streamwish" in url || "wishembed" in url -> streamWishExtractor.videosFromUrl(url)
            "lulustream" in url -> luluStream1Extractor.videosFromUrl(url, url)
            "filemoon" in url -> filemoonExtractor.videosFromUrl(url, "FileMoon")
            "mivalyo" in url -> mivalyoExtractor.videosFromUrl(url)
            "goodstream" in url -> goodStreamExtractor.videosFromUrl(url)
            url.contains("bigwarp") || url.contains("bigwarp.io") -> {
                println("DEBUG: Processing BigWarp URL: $url")
                val result = bigWarpExtractor.videosFromUrl(url)
                println("DEBUG: BigWarp extraction result: ${result.size} videos found")
                result
            }
//            "anafasts" in url -> anafastsExtractor.videosFromUrl(url)
//            "vidspeeds" in url -> vidspeedsExtractor.videosFromUrl(url)
//            "vidroba" in url -> vidrobaExtractor.videosFromUrl(url)
            else -> {
                Log.w(TAG, "⚠️ No extractor for: $url")
                emptyList()
            }
        }
    }


    // ============================== Search (Live Search) ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        // Live search does not support pagination. Only fetch results for the first page.
        if (page > 1) {
            Log.d(TAG, "[Search] Page > 1 requested, returning empty list as live search has no pagination.")
            return@withContext MangaPage(emptyList(), false)
        }

        val url = "$BASE_URL/ajax-search.php"
        Log.d(TAG, "[Search] Using live search endpoint: $url with query: '$query'")

        try {
            val formBody = FormBody.Builder()
                .add("queryString", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .header("Referer", BASE_URL)
                .header("X-Requested-With", "XMLHttpRequest") // This header is often crucial for AJAX endpoints
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "[Search] Live search response code: ${response.code}")

            return@withContext liveSearchParse(response)
        } catch (e: Exception) {
            Log.e(TAG, "[Search] Live search request failed for query: '$query'", e)
            return@withContext MangaPage(emptyList(), false)
        }
    }

    private suspend fun liveSearchParse(response: Response): MangaPage {
        val responseBody = response.body!!.string()
        // Use parseBodyFragment because the response is just a list of `<li>`s, not a full HTML document.
        val document = Jsoup.parseBodyFragment(responseBody, BASE_URL)
        Log.d(TAG, "[Search] Live search raw response snippet: $responseBody")

        val animeElements = document.select("li")
        Log.d(TAG, "[Search] Found ${animeElements.size} items with selector 'li' in live search response.")

        val animeList = animeElements.mapNotNull { element ->
            try {
                val link = element.selectFirst("a") ?: return@mapNotNull null
                SAnime().apply {
                    url = link.attr("href") // The URL from the href is already absolute
                    title = link.text()
                    // NOTE: Live search results do not contain thumbnail URLs.
                    // The app's UI should handle a null thumbnail gracefully (e.g., show a placeholder).
                    var animeThunbinal = SAnime()
                    animeThunbinal = fetchAnimeDetails(link.attr("href"))
                    thumbnail_url = animeThunbinal.thumbnail_url
                    Log.d(TAG, "[Search Parse Item] Title: $title, URL: $url")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Search] Failed to parse one live search item from HTML: $element", e)
                null
            }
        }
        Log.d(TAG, "[Search] Successfully parsed ${animeList.size} items from live search.")

        // Live search does not have pagination
        return MangaPage(animeList, false)
    }

    private fun animeFromElement(element: Element): SAnime {
        return SAnime().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.attr("title")
            thumbnail_url = link.selectFirst("img")?.attr("src")

            val duration = element.selectFirst("span.pm-label-duration")?.text()
            val episode = element.selectFirst("span.ep")?.text()
            description = buildString {
                if (!duration.isNullOrEmpty()) {
                    append("المدة: $duration\n")
                }
                if (!episode.isNullOrEmpty()) {
                    append(episode)
                }
            }
        }
    }


    // ============================== Filters ==============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}