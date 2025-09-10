package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.AnimeFilter // Make sure this import is correct
import com.faselhd.app.models.AnimeFilterList
import com.faselhd.app.models.MangaPage
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// DTOs (Data Transfer Objects) for ArabAnime API responses
@Serializable
data class PopularAnimeResponse(
    val Shows: List<String>,
    val current_page: Int,
    val last_page: Int,
)

@Serializable
data class AnimeItem(
    val anime_cover_image_url: String,
    val anime_id: String,
    val anime_name: String,
    val info_src: String,
)

@Serializable
data class ShowItem(
    val EPS: List<EPS>,
    val show: List<Show>,
)

@Serializable
data class EPS(
    val episode_name: String,
    val episode_number: Int,
    @SerialName("info-src")
    val `info-src`: String,
)

@Serializable
data class Show(
    val anime_cover_image_url: String,
    val anime_description: String,
    val anime_genres: String,
    val anime_id: Int,
    val anime_name: String,
    val anime_slug: String,
    val anime_status: String,
)

@Serializable
data class Episode(
    val ep_info: List<EpInfo>,
)

@Serializable
data class EpInfo(
    val stream_servers: List<String>,
)

//================================================================================
// START: FILTER CLASSES (DEFINED AT THE TOP-LEVEL OF THE FILE)
//================================================================================

private val ORDER_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("التقييم", "2"),
    Pair("اخر الانميات المضافة", "1"),
    Pair("الابجدية", "0"),
)

private val TYPE_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("الكل", ""),
    Pair("فيلم", "0"),
    Pair("انمى", "1"),
)

private val STATUS_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("الكل", ""),
    Pair("مستمر", "1"),
    Pair("مكتمل", "0"),
)

// This class now correctly inherits from your project's AnimeFilter.Select
// Generic filter that maps display labels to query values
private open class QueryPartFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
    state: Int = 0,
) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toQueryPart() = vals[state].second
}


private class OrderFilter : QueryPartFilter("ترتيب", ORDER_LIST)
private class TypeFilter : QueryPartFilter("النوع", TYPE_LIST)
private class StatusFilter : QueryPartFilter("الحالة", STATUS_LIST)

//================================================================================
// END: FILTER CLASSES
//================================================================================


class ArabAnimeSource(private val context: Context) {

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

    private val json: Json by injectLazy()

    private val baseUrl = "https://www.arabanime.net"

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api?page=$page").build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<PopularAnimeResponse>(response.body!!.string())

        val animeList = responseJson.Shows.mapNotNull {
            runCatching {
                val animeJson = json.decodeFromString<AnimeItem>(it.decodeBase64())
                SAnime().apply {
                    url = animeJson.info_src
                    title = animeJson.anime_name
                    thumbnail_url = animeJson.anime_cover_image_url
                    source = AnimeSource.ARAB_ANIME.name
                }
            }.getOrNull()
        }
        val hasNextPage = responseJson.current_page < responseJson.last_page
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) {
            return@withContext MangaPage(emptyList(), hasNextPage = false)
        }
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), baseUrl)

        val latestEpisodes = document.select("div.as-episode")
        val animeList = latestEpisodes.map {
            SAnime().apply {
                val ahref = it.selectFirst("a.as-info")!!
                title = ahref.text()
                url = ahref.attr("href").replace("watch", "show").substringBeforeLast("/")
                thumbnail_url = it.selectFirst("img")?.absUrl("src")
                source = AnimeSource.ARAB_ANIME.name
            }
        }
        MangaPage(animeList, hasNextPage = false)
    }

    // =============================== Main Slider ===============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).build()
            val response = client.newCall(request).execute()
            val document = Jsoup.parse(response.body!!.string(), baseUrl)

            val latestEpisodes = document.select("div.as-episode")
            val animeList = latestEpisodes.map {
                SAnime().apply {
                    val ahref = it.selectFirst("a.as-info")!!
                    title = ahref.text()
                    // The URL should point to the show, not the episode
                    url = ahref.attr("href").replace("watch", "show").substringBeforeLast("/")
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                    source = AnimeSource.ARAB_ANIME.name
                }
            }
            // Take the first 5 and shuffle them for a random-looking slider
            animeList.take(5).shuffled()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // Return an empty list on error
        }
    }


    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val request = if (query.isNotEmpty()) {
            val body: RequestBody = FormBody.Builder().add("searchq", query).build()
            Request.Builder().url("$baseUrl/searchq").post(body).build()
        } else {
            // This code will now work because OrderFilter is a valid AnimeFilter type.
            val order = filters.find<OrderFilter>()?.toQueryPart() ?: ""
            val type = filters.find<TypeFilter>()?.toQueryPart() ?: ""
            val status = filters.find<StatusFilter>()?.toQueryPart() ?: ""
            Request.Builder().url("$baseUrl/api?order=$order&type=$type&stat=$status&tags=&page=$page").build()
        }

        val response = client.newCall(request).execute()

        if (response.header("Content-Type", "")?.contains("application/json") == true) {
            val responseJson = json.decodeFromString<PopularAnimeResponse>(response.body!!.string())
            val animeList = responseJson.Shows.mapNotNull {
                runCatching {
                    val animeJson = json.decodeFromString<AnimeItem>(it.decodeBase64())
                    SAnime().apply {
                        url = animeJson.info_src
                        title = animeJson.anime_name
                        thumbnail_url = animeJson.anime_cover_image_url
                        source = AnimeSource.ARAB_ANIME.name
                    }
                }.getOrNull()
            }
            val hasNextPage = responseJson.current_page < responseJson.last_page
            MangaPage(animeList, hasNextPage)
        } else {
            val document = Jsoup.parse(response.body!!.string(), baseUrl)
            val searchResult = document.select("div.show")
            val animeList = searchResult.map {
                SAnime().apply {
                    url = it.selectFirst("a")!!.attr("href")
                    title = it.selectFirst("h3")!!.text()
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                    source = AnimeSource.ARAB_ANIME.name
                }
            }
            MangaPage(animeList, hasNextPage = false)
        }
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        val showData = document.selectFirst("div#data")!!.text().decodeBase64()
        val details = json.decodeFromString<ShowItem>(showData).show[0]

        SAnime().apply {
            url = response.request.url.toString()
            title = details.anime_name
            status = when (details.anime_status) {
                "Ongoing" -> SAnime.ONGOING
                "Completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = details.anime_genres
            description = details.anime_description
            thumbnail_url = details.anime_cover_image_url
            source = AnimeSource.ARAB_ANIME.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        val showData = document.selectFirst("div#data")?.text()?.decodeBase64() ?: return@withContext emptyList()
        val episodesJson = json.decodeFromString<ShowItem>(showData)

        // ========= MODIFICATION START =========

        // Get the main anime name to use as the "season" name. Provide a fallback.
        val animeNameAsSeason = episodesJson.show.firstOrNull()?.anime_name ?: "الموسم 1"

        return@withContext episodesJson.EPS.map {
            SEpisode().apply {
                // Format the name to be "Anime Name : Episode Name"
                // This allows the UI to group all episodes under the anime's title.
                name = "$animeNameAsSeason : ${it.episode_name}"
                episode_number = it.episode_number.toFloat()
                url = it.`info-src`
            }
        }

        // ========= MODIFICATION END =========
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), episodeUrl)

        val watchData = document.selectFirst("div#datawatch")?.text()?.decodeBase64() ?: return@withContext emptyList()
        val serversJson = json.decodeFromString<Episode>(watchData)
        if (serversJson.ep_info.isEmpty() || serversJson.ep_info[0].stream_servers.isEmpty()) {
            return@withContext emptyList()
        }
        println("aaaa")
        val selectServer = serversJson.ep_info[0].stream_servers[0].decodeBase64()

        val watchPageResponse = client.newCall(Request.Builder().url(selectServer).build()).execute()
        val watchPage = Jsoup.parse(watchPageResponse.body!!.string(), selectServer)

        val videos = watchPage.select("option")
            .map { it.text() to it.attr("data-src").decodeBase64() }
            .filter { it.second.contains("$baseUrl/embed") }
            .flatMap { (name, url) ->
                try {
                    val embedResponse = client.newCall(Request.Builder().url(url).build()).execute()
                    Jsoup.parse(embedResponse.body!!.string(), url)
                        .select("source")
                        .mapNotNull { source ->
                            val videoUrl = source.attr("src")
                            if (!videoUrl.contains("static")) {
                                val quality = source.attr("label").let { q ->
                                    if (q.contains("p", ignoreCase = true)) q else "${q}p"
                                }
                                Video(videoUrl, "$name: $quality", videoUrl)
                            } else {
                                null
                            }
                        }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList<Video>()
                }
            }

        sortVideos(videos)
    }

    private fun sortVideos(videos: List<Video>): List<Video> {
        val quality = getPreferredQuality()
        return videos.sortedWith(
            compareByDescending { it.quality.contains(quality) }
        )
    }

    // ============================== Filters ===============================
    fun getFilterList() = AnimeFilterList(
        listOf(
            AnimeFilter.Header("فلترة الموقع (يعمل فقط عند ترك البحث فارغ)"),
            OrderFilter(),
            TypeFilter(),
            StatusFilter(),
        )
    )

    // =============================== Preferences ===============================
    private fun getPreferences() = context.getSharedPreferences("arabanime_prefs", Context.MODE_PRIVATE)

    fun getPreferredQuality(): String {
        return getPreferences().getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
    }

    private fun String.decodeBase64() = String(Base64.decode(this, Base64.DEFAULT))

    companion object {
        private const val PREF_QUALITY_KEY = "arabanime_preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}