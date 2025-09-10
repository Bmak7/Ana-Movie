package com.faselhd.app.network.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.AnimeFilterList
import com.faselhd.app.models.MangaPage
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class OkAnimeSource(private val context: Context) {

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
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }


    companion object {
        // TODO: You will need to create a settings screen to change these preferences.
        private const val PREF_QUALITY_KEY = "okanime_preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")

        private const val PREF_HOSTER_SELECTION_KEY = "okanime_hoster_selection"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Voe", "Mp4upload", "Dood", "VidBom", "Okru")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_ENTRIES.toSet() }
    }
    // We need the Json instance for the VoeExtractor
    private val json: Json by injectLazy()

    private val baseUrl = "https://www.okanime.xyz"

    // --- Extractors ---
    // You already have Mp4upload and Voe. You will need to add the others later.
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val fourSharedExtractor by lazy { FourSharedExtractor(client) }
    val megaMaxExtractor = MegaMaxExtractor(
        client = client,
        doodExtractor = doodExtractor,
        voeExtractor = voeExtractor,
        mixDropExtractor = mixDropExtractor,
        streamWishExtractor = streamWishExtractor,
        streamTapeExtractor = streamTapeExtractor,
        mp4uploadExtractor = mp4uploadExtractor,
        vidTubeExtractor = vidTubeExtractor,
        mivalyoExtractor = mivalyoExtractor,
        // ... pass others here
    )


    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        popularAnimeParse(document)
    }

    private fun popularAnimeParse(document: Document): MangaPage {
        val animeList = document.select("div.container > div.section:last-child div.anime-card").map {
            popularAnimeFromElement(it)
        }
        // Okanime main page has no next page for popular
        return MangaPage(animeList, hasNextPage = false)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime().apply {
            element.selectFirst("div.anime-title > h4 > a")!!.also {
                url = it.attr("href").substringAfter(baseUrl)
                title = it.text()
            }
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            source = AnimeSource.OKANIME.name
        }
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int):  List<SAnime> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/espisode-list?page=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), url)

        val animeList = document.select("div.container > div.section:last-child div.anime-card").map {
            popularAnimeFromElement(it)
        }
        val hasNextPage = document.select("ul.pagination > li:last-child:not(.disabled)").isNotEmpty()
        animeList
    }

    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        val episodeCards = document.select("div.small.owl-episode-card")

        return episodeCards.map { card ->
            val anime = SAnime()

            // Extract episode URL
            val episodeLink = card.select("div.episode-image a").attr("href")
            anime.setUrlWithoutDomain(episodeLink)

            // Extract anime title
            anime.title = card.select("div.anime-title h4 a").text()

            // Extract episode title/number
            val episodeTitle = card.select("div.anime-title h5 a").text()
            anime.description = episodeTitle // Using description field for episode info

            // Extract thumbnail URL
            anime.thumbnail_url = card.select("div.episode-image img").attr("src")

            anime
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search/?s=$query" + if (page > 1) "&page=$page" else ""
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), url)

        val animeList = document.select("div.container > div.section:last-child div.anime-card").map {
            popularAnimeFromElement(it)
        }
        val hasNextPage = document.select("ul.pagination > li:last-child:not(.disabled)").isNotEmpty()
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url( animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        SAnime().apply {
            url = animeUrl
            title = document.selectFirst("div.author-info-title > h1")!!.text()
            genre = document.select("div.review-author-info a").eachText().joinToString(", ")
            source = AnimeSource.OKANIME.name

            val infosdiv = document.selectFirst("div.text-right")!!
            thumbnail_url = infosdiv.selectFirst("img")!!.attr("src")
            status = when (infosdiv.selectFirst("div.full-list-info:contains(حالة الأنمي) a")?.text()) {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                document.selectFirst("div.review-content")?.text()?.let { append("$it\n\n") }
                infosdiv.select("div.full-list-info").forEach { info ->
                    val infoText = info.select("small").eachText().joinToString(": ")
                    append("$infoText\n")
                }
            }

            println("[ok anime] title : $title url : $url thumbnail_url $thumbnail_url $description")
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        // ========= MODIFICATION START =========

        // Extract the main anime title to use as the "season" name for grouping.
        val animeNameAsSeason = document.selectFirst("div.author-info-title > h1")?.text() ?: "الموسم 1"

        return@withContext document.select("div.row div.episode-card div.anime-title a").map {
            SEpisode().apply {
                url = it.attr("href").substringAfter(baseUrl)
                // Format the name consistently: "Anime Title : Episode Title"
                name = "$animeNameAsSeason : ${it.text()}"
                episode_number = it.text().substringAfterLast(" ").toFloatOrNull() ?: 0f
            }
        } // The site lists oldest first, so we reverse to show the latest on top.

        // ========= MODIFICATION END =========
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), episodeUrl)

        val hosterSelection = getSelectedHosts()

        document.select("a.ep-link").flatMap { element ->
            val url = element.attr("data-src")
            extractVideosFromUrl(url, hosterSelection)
        }.let { sortVideos(it) }
    }

    private fun extractVideosFromUrl(url: String, selection: Set<String>): List<Video> {
        return when {

            "mp4upload" in url && selection.contains("Mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(url, prefix = "Okanime")
            }
            "voe.sx" in url ||  "voe" in url -> {
                voeExtractor.videosFromUrl(url)
            }
            // UNCOMMENT THE BLOCKS BELOW
            "https://doo" in url || "https://d" in url  || selection.contains("Dood") -> {
                doodExtractor.videosFromUrl(url, "Doodstream")
            }
            "ok.ru" in url && selection.contains("Okru") -> {
                okruExtractor.videosFromUrl(url, prefix = "Okanime:")
            }
            "uqload" in url  -> {
                uqloadExtractor.videosFromUrl(url, prefix = "Okanime:")
            }
            "4shared" in url -> fourSharedExtractor.videosFromUrl(url)
            VID_BOM_DOMAINS.any(url::contains) && selection.contains("VidBom") -> {
                vidBomExtractor.videosFromUrl(url)
            }
            "megamax" in url -> megaMaxExtractor.videosFromUrl(url)

            else -> emptyList()
        }
    }


    private fun sortVideos(videos: List<Video>): List<Video> {
        val quality = getPreferredQuality()
        return videos.sortedWith(
            compareByDescending { it.quality.contains(quality) }
        )
    }

    // ============================== Filters ===============================
    // Okanime doesn't have a filter system on its website, so we return an empty list.
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())

    // ============================== Settings ==============================
    // We use SharedPreferences to store settings for this source.
    private fun getPreferences() = context.getSharedPreferences("okanime_prefs", Context.MODE_PRIVATE)

    fun getPreferredQuality(): String {
        return getPreferences().getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
    }

    fun getSelectedHosts(): Set<String> {
        return getPreferences().getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT) ?: PREF_HOSTER_SELECTION_DEFAULT
    }


}