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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

import kotlinx.coroutines.withContext
import okhttp3.Cache
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AnimercoSource(private val context: Context) {

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

    private val baseUrl = "https://got.animerco.org"

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
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }


    // GdrivePlayerExtractor and YourUploadExtractor can be added if you have them
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
        luluStream1Extractor = luluStream1Extractor,
        filemoonExtractor = filemoonExtractor
        // ... pass others here
    )

    // ============================== Popular & Latest ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/trending/page/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.media-block > div > a.image").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child a:has(svg)") != null
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/?s="
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.media-block > div > a.image").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child a:has(svg)") != null
        MangaPage(animeList, hasNextPage)
    }

    private fun popularFromElement(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("href")
            thumbnail_url = element.attr("data-src")
            title = element.attr("title")
            source = AnimeSource.ANIMERCO.name // Assuming you add ANIMERCO to your enum
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("s", query)
//        filters.forEach { filter ->
//            when (filter) {
//                is GenreFilter -> if (filter.toUriPart().isNotBlank()) urlBuilder.addQueryParameter("genres", filter.toUriPart())
//                is YearFilter -> if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("dtyear", filter.state)
//            }
//        }
        val url = urlBuilder.build().toString()
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("div.media-block > div > a.image").map { popularFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child a:has(svg)") != null
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        SAnime().apply {
            this.url = animeUrl
            thumbnail_url = document
                .selectFirst("div.banner")  // find <div class="banner ...">
                ?.attr("data-src")

            title = document.selectFirst("div.media-title h1")!!.text()
            genre = document.select("nav.Nvgnrs a, ul.media-info li:contains(النوع) a").eachText().joinToString(", ")
            description = document.selectFirst("div.media-story p")?.text()
            status = SAnime.UNKNOWN // Status is complex to determine accurately, default to UNKNOWN
            source = AnimeSource.ANIMERCO.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodes = mutableListOf<SEpisode>()

        // Handle movies separately by checking the URL structure
        if (animeUrl.contains("/movies/")) {
            return@withContext listOf(SEpisode().apply {
                this.url = animeUrl
                this.name = (document.selectFirst("div.media-title h1")?.text() ?: "Movie") + " : فيلم"
                this.episode_number = 1f
            })
        }

        // --- TV Series Logic ---
        // 1. Check for a dedicated seasons list on the main anime page.
        val seasonElements = document.select("div.media-seasons ul.episodes-lists li a.title")

        if (seasonElements.isNotEmpty()) {
            // --- MULTI-SEASON SHOW ---
            // The site lists seasons in order (1, 2...), so no reversal is needed here.
            seasonElements.forEach { seasonElement ->
                val seasonName = seasonElement.selectFirst("h3")?.ownText()?.trim() ?: "Season" // Extracts "الموسم 1"
                val seasonUrl = seasonElement.attr("href")
                try {
                    val seasonDoc = Jsoup.parse(client.newCall(Request.Builder().url(seasonUrl).build()).execute().body!!.string())
                    // Episodes on the season page are listed chronologically, so we don't reverse here.
                    seasonDoc.select("div.media-episodes ul.episodes-lists li").forEach { episodeElement ->
                        episodes.add(episodeFromElement(episodeElement, seasonName))
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Log error and continue if a season fails
                }
            }
        } else {
            // --- SINGLE-SEASON SHOW ---
            // If no season list exists, the episodes are on the main page itself.
            val animeTitleAsSeason = document.selectFirst("div.media-title h1")?.text() ?: "الموسم 1"
            document.select("div.media-episodes ul.episodes-lists li").forEach { episodeElement ->
                episodes.add(episodeFromElement(episodeElement, animeTitleAsSeason))
            }
        }

        return@withContext episodes
    }

    // Helper function to create an SEpisode from a list item element.
    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        val linkElement = element.selectFirst("a.title")
        val episodeTitle = linkElement?.selectFirst("h3")?.ownText()?.trim() ?: "Episode"

        return SEpisode().apply {
            url = linkElement?.attr("href") ?: ""
            // This is the crucial formatting for your UI's seasonal grouping.
            name = "$seasonName : $episodeTitle"
            // The episode number is stored in the 'data-number' attribute of the parent <li>
            episode_number = element.attr("data-number").toFloatOrNull() ?: 0f
        }
    }

    // ============================ Video Links (Corrected) =============================

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d("VideoDebug", "fetchVideoList called with URL: $episodeUrl")

        try {
            // 1. Make the initial request to get the page content and cookies
            val initialRequest = Request.Builder().url(episodeUrl).build()
            val initialResponse = client.newCall(initialRequest).execute()

            val responseBody = initialResponse.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.e("VideoDebug", "Initial response body was null or empty.")
                return@withContext emptyList()
            }

            // 2. Extract cookies to maintain the session
            val cookies = initialResponse.headers.values("Set-Cookie").joinToString(separator = "; ") {
                it.substringBefore(";")
            }

            // 3. --- KEY CHANGE: Extract the entire dtAjax JSON object ---
            val ajaxDataPattern = Pattern.compile("""var dtAjax\s*=\s*(\{.*?\});""")
            val ajaxDataMatcher = ajaxDataPattern.matcher(responseBody)

            val (ajaxUrl, nonce) = if (ajaxDataMatcher.find()) {
                try {
                    val jsonData = JSONObject(ajaxDataMatcher.group(1))
                    val relativeUrl = jsonData.getString("url")
                    // Construct the full URL from the base and the relative path
                    val fullUrl = episodeUrl.toHttpUrl().scheme + "://" + episodeUrl.toHttpUrl().host + relativeUrl
                    val extractedNonce = jsonData.getString("nonce")
                    Log.d("VideoDebug", "Successfully extracted AJAX URL: $fullUrl and Nonce: $extractedNonce")
                    Pair(fullUrl, extractedNonce)
                } catch (e: Exception) {
                    Log.e("VideoDebug", "Failed to parse dtAjax JSON object.", e)
                    return@withContext emptyList()
                }
            } else {
                Log.e("VideoDebug", "Error: Could not find the dtAjax JavaScript object.")
                return@withContext emptyList()
            }

            if (ajaxUrl.isBlank() || nonce.isBlank()) {
                Log.e("VideoDebug", "Extracted AJAX URL or Nonce is blank.")
                return@withContext emptyList()
            }

            // 4. Find all server elements
            val document = Jsoup.parse(responseBody, episodeUrl)
            val serverElements = document.select("ul.server-list > li > a.option")
            Log.d("VideoDebug", "Found ${serverElements.size} server elements.")


            // 5. Use flatMap to concurrently and safely fetch video links
            val videos = serverElements.flatMap { playerElement ->
                val serverName = playerElement.selectFirst("span.server")?.text() ?: "Unknown Server"
                val postData = playerElement.attr("data-post")
                val numeData = playerElement.attr("data-nume")
                val typeData = playerElement.attr("data-type")

                if (postData.isBlank() || numeData.isBlank() || typeData.isBlank()) {
                    return@flatMap emptyList<Video>()
                }

                // 6. Build the FormBody WITH the dynamically extracted nonce
                val formBody = FormBody.Builder()
                    .add("action", "player_ajax")
                    .add("post", postData)
                    .add("nume", numeData)
                    .add("type", typeData)
                    .add("nonce", nonce) // Nonce is now included
                    .build()

                // 7. Build the request WITH required headers and the dynamic AJAX URL
                val ajaxRequest = Request.Builder()
                    .url(ajaxUrl) // Use the dynamic URL
                    .post(formBody)
                    .header("Referer", episodeUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", cookies)
                    .build()

                try {
                    val ajaxResponse = client.newCall(ajaxRequest).execute()
                    val ajaxBody = ajaxResponse.body?.string()

                    if (!ajaxResponse.isSuccessful || ajaxBody.isNullOrEmpty()) {
                        Log.w("VideoDebug", "AJAX request for server '$serverName' failed or returned empty body.")
                        emptyList<Video>()
                    } else {
                        // 8. Robustly parse the JSON to get the embed_url
                        val embedUrl = try {
                            JSONObject(ajaxBody).getString("embed_url").replace("\\", "")
                        } catch (e: Exception) {
                            Log.e("VideoDebug", "Failed to parse embed_url from AJAX response for server '$serverName'.", e)
                            ""
                        }

                        if (embedUrl.isNotBlank()) {
                            Log.d("VideoDebug", "Got embed URL for '$serverName': $embedUrl")
                            extractVideosFromUrl(embedUrl) // Call your existing extractor
                        } else {
                            emptyList<Video>()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VideoDebug", "Exception during AJAX call for server '$serverName'.", e)
                    emptyList<Video>()
                }
            }

            Log.i("VideoDebug", "Completed fetching. Total videos found: ${videos.size}")
            return@withContext videos

        } catch (e: Exception) {
            Log.e("VideoDebug", "A critical error occurred in fetchVideoList.", e)
            return@withContext emptyList()
        }
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        Log.d("VideoDebug", "extractVideosFromUrl called with URL: $url")

        val result = when {
            "ok.ru" in url -> {
                Log.d("VideoDebug", "Detected OK.ru URL")
                okruExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "OK.ru extracted ${it.size} videos")
                }
            }
            "mp4upload" in url -> {
                Log.d("VideoDebug", "Detected MP4Upload URL")
                mp4uploadExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "MP4Upload extracted ${it.size} videos")
                }
            }
            "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url-> {
                Log.d("VideoDebug", "Detected Dood URL")
                doodExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "Dood extracted ${it.size} videos")
                }
            }
            "streamtape" in url -> {
                Log.d("VideoDebug", "Detected StreamTape URL")
                streamTapeExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "StreamTape extracted ${it.size} videos")
                }
            }
            "uqload" in url -> {
                Log.d("VideoDebug", "Detected Uqload URL")
                uqloadExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "Uqload extracted ${it.size} videos")
                }
            }
            "megamax" in url -> megaMaxExtractor.videosFromUrl(url)
            "yourupload" in url -> {
                Log.d("VideoDebug", "Detected YourUpload URL")
                yourUploadExtractor.videosFromUrl(url)
            }
            VIDBOM_DOMAINS.any { url.contains(it) } -> {
                Log.d("VideoDebug", "Detected VidBom URL")
                vidBomExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "VidBom extracted ${it.size} videos")
                }
            }
            url.contains("wish", ignoreCase = true) ||url.contains("videas", ignoreCase = true)  -> {
                Log.d("VideoDebug", "Detected StreamWish URL")
                streamWishExtractor.videosFromUrl(url).also {
                    Log.d("VideoDebug", "StreamWish extracted ${it.size} videos")
                }
            }
            else -> {
                println("DEBUG: No extractor found for host: $url")
//                emptyList()
                Log.d("VideoDebug", "Unknown URL type, trying generic extractors")
                // Try all extractors as fallback
                val allVideos = mutableListOf<Video>()

//                listOf(
////                    { okruExtractor.videosFromUrl(url) },
////                    { mp4uploadExtractor.videosFromUrl(url) },
////                    { doodExtractor.videosFromUrl(url) },
////                    { streamTapeExtractor.videosFromUrl(url) },
////                    { uqloadExtractor.videosFromUrl(url) },
////                    { vidBomExtractor.videosFromUrl(url) },
//                    { streamWishExtractor.videosFromUrl(url) }
//                ).forEach { extractor ->
//                    try {
//                        val videos = extractor()
//                        if (videos.isNotEmpty()) {
//                            Log.d("VideoDebug", "Fallback extractor found ${videos.size} videos")
//                            allVideos.addAll(videos)
//                        }
//                    } catch (e: Exception) {
//                        Log.d("VideoDebug", "Fallback extractor failed: ${e.message}")
//                    }
//                }

                allVideos.also {
                    Log.d("VideoDebug", "Fallback extraction found ${it.size} total videos")
                }
            }
        }

        Log.d("VideoDebug", "Final result: ${result.size} videos extracted")
        result.forEachIndexed { index, video ->
            Log.d("VideoDebug", "  Video $index: ${video.quality} - ${video.url.take(100)}...")
        }

        return result
    }




    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
//    fun getFilterList(): AnimeFilterList = AnimeFilterList(
//        GenreFilter(GenresList),
////        YearFilter(),
//    )

    companion object {
        private val VIDBOM_DOMAINS = listOf("vidbom", "vidbem", "vidbm", "vedpom", "vadbom", "myviid")
        private val GenresList = arrayOf(
            "التصنيفات" to "", "أكشن" to "action", "أوفا" to "ova", "إثارة" to "thriller", "إيتشي" to "ecchi",
            "السفر عبر الزمن" to "time-travel", "بوليسي" to "police", "تاريخي" to "historical", "تحقيقات" to "detective",
            "تشويق" to "suspense", "جريمة" to "crime", "جنون" to "dementia", "جوسي" to "josei", "حريم" to "harem",
            "حياة العمل" to "work-life", "خارق للطبيعة" to "supernatural", "خيال" to "fantasy", "خيال علمي" to "science-fiction",
            "خيال علمي وفانتازيا" to "sci-fi-fantasy", "دراما" to "drama", "دموي" to "gore", "ذواق" to "gourmet",
            "رعب" to "horror", "رومانسي" to "romance", "رياضي" to "sports", "ساخر" to "parody", "ساموراي" to "samurai",
            "سباق" to "racing", "سحر" to "magic", "سينين" to "seinen", "شريحة من الحياة" to "slice-of-life",
            "شوجو" to "shoujo", "شونين" to "shounen", "شونين آي" to "shounen-ai", "شياطين" to "demons",
            "طبي" to "medical", "طليعية" to "avant-garde", "عسكري" to "military", "غموض" to "mystery",
            "فضاء" to "space", "فنون تعبيرية" to "performing-arts", "فنون تمثيلية" to "performing-arts-2",
            "فنون قتالية" to "martial-arts", "قوة خارقة" to "super-power", "كوميدي" to "comedy", "لعبة" to "game",
            "لعبة استراتيجية" to "strategy-game", "مدرسي" to "school", "مصاصي دماء" to "vampire",
            "مغامرة" to "adventure", "موسيقي" to "music", "ميثولوجيا" to "mythology", "ميكا" to "mecha",
            "نفسي" to "psychological"
        )
    }
}

