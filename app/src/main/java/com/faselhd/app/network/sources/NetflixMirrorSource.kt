package com.faselhd.app.network.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Simple cookie storage
object NetflixMirrorStorage {
    private var savedCookie: String? = null
    private var savedTimestamp: Long = 0

    fun getCookie(): Pair<String?, Long> = Pair(savedCookie, savedTimestamp)

    fun saveCookie(cookie: String) {
        savedCookie = cookie
        savedTimestamp = System.currentTimeMillis()
    }

    fun clearCookie() {
        savedCookie = null
        savedTimestamp = 0
    }
}

//region DTOs for Netflix Mirror API
@Serializable
data class NetflixSearchData(
    val head: String = "",
    val type: Int = 0,
    @SerialName("searchResult") val searchResult: List<NetflixSearchResult> = emptyList(),
)
@Serializable
data class NetflixSearchResult(
    val id: String = "",
    val t: String = "",
)

@Serializable
data class NetflixPostData(
    val title: String? = null,
    val desc: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val cast: String? = null,
    @SerialName("match") val rating: String? = null,
    val ua: String? = null,
    val suggest: List<NetflixSuggest>? = null,
    val episodes: List<NetflixEpisodeInfo?>? = null,
    val season: List<NetflixSeasonInfo>? = null,
    val nextPageShow: Int? = 0,
    val nextPageSeason: String? = null,
)

@Serializable
data class NetflixSuggest(
    val id: String,
)

@Serializable
data class NetflixEpisodeInfo(
    val id: String,
    val t: String,
    val ep: String,
    val s: String,
    val time: String,
)

@Serializable
data class NetflixSeasonInfo(
    val id: String
)

@Serializable
data class NetflixEpisodesPage(
    val episodes: List<NetflixEpisodeInfo>? = null,
    val nextPageShow: Int = 0,
)

@Serializable
data class NetflixPlaylist(
    val sources: List<NetflixSource>,
    val tracks: List<NetflixTrack>? = null,
)

@Serializable
data class NetflixSource(
    val file: String,
    val label: String,
)

@Serializable
data class NetflixTrack(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
)
//endregion

class NetflixMirrorSource(private val context: Context) {

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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://net2025.cc"
    private var cookieValue: String = ""
    private val cookieMutex = Mutex()

    // This bypass function is a custom implementation. The reference provider's implementation is not shown.
    // This implementation is assumed to be functional.
    private suspend fun bypassCookies(): String {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/tv/p.php")
                .post(okhttp3.RequestBody.create(null, ""))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.body?.string()?.contains("\"r\":\"n\"") == true) {
                    val setCookieHeaders = response.headers("Set-Cookie")
                    val tHashCookie = setCookieHeaders
                        .firstOrNull { it.contains("t_hash_t") }
                        ?.substringAfter("t_hash_t=")
                        ?.substringBefore(";")
                    return tHashCookie ?: ""
                }
            }
            ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private suspend fun getCookieValue(): String {
        cookieMutex.withLock {
            if (cookieValue.isNotEmpty()) return@withLock cookieValue

            val (cachedCookie, cachedTimestamp) = NetflixMirrorStorage.getCookie()
            if (!cachedCookie.isNullOrEmpty() && System.currentTimeMillis() - cachedTimestamp < 54_000_000) { // 15 hours
                cookieValue = cachedCookie
                return@withLock cookieValue
            }

            cookieValue = bypassCookies()
            if (cookieValue.isNotEmpty()) {
                NetflixMirrorStorage.saveCookie(cookieValue)
            } else {
                NetflixMirrorStorage.clearCookie()
            }
            return@withLock cookieValue
        }
        return ""
    }

    private fun getCookies(): Map<String, String> {
        return mapOf(
            "t_hash_t" to cookieValue,
            "user_token" to "233123f803cf02184bf6c67e149cdd50",
            "ott" to "nf",
            "hd" to "on"
        )
    }

    private fun buildCookieHeader(): String {
        return getCookies().map { "${it.key}=${it.value}" }.joinToString("; ")
    }

    private suspend fun makeApiRequest(url: String, referer: String? = null): String {
        val requestBuilder = Request.Builder().url(url)
            .header("Cookie", buildCookieHeader())
            .header("X-Requested-With", "XMLHttpRequest")

        referer?.let { requestBuilder.header("Referer", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            // Ensure the body is not null before calling string() to avoid potential NullPointerException
            return response.body?.string() ?: ""
        }
    }


    // =============================== Popular/Homepage ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        try {
            getCookieValue()
            val request = Request.Builder().url("$baseUrl/home")
                .header("Cookie", buildCookieHeader())
                .header("Referer", "$baseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body!!.string())
                val popularItems = document.select(".lolomoRow").flatMap { row ->
                    row.select("img.lazy").mapNotNull {
                        val dataSrc = it.attr("data-src")
                        if (dataSrc.isNotEmpty()) {
                            val id = dataSrc.substringAfterLast("/").substringBefore(".")
                            SAnime().apply {
                                title = it.attr("alt").ifEmpty { "Unknown Title" }
                                url = id
                                thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                                source = AnimeSource.NETFLIX_MIRROR.name
                            }
                        } else null
                    }
                }
                MangaPage(popularItems, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        try {
            getCookieValue()
            val urll = "$baseUrl/search.php?s=$query&t=${System.currentTimeMillis()}"

            val request = Request.Builder().url(urll)
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/tv/home")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body!!.string()
            response.close()

            println("Search response: $responseBody") // Debug log

            // Check if response is valid JSON and contains expected data
            if (responseBody.isBlank() || !responseBody.trim().startsWith("{")) {
                println("Invalid search response format")
                return@withContext MangaPage(emptyList(), false)
            }
            val data = try {
                json.decodeFromString<NetflixSearchData>(responseBody)
            } catch (e: Exception) {
                println("Failed to parse search response: ${e.message}")
                // Try to parse as a different structure or return empty
                return@withContext MangaPage(emptyList(), false)
            }
            println("Search data: ${data.toString()}")




            val animeList = data.searchResult.map {
                SAnime().apply {
                    url = it.id
                    title = it.t
                    thumbnail_url = "https://imgcdn.media/poster/v/${it.id}.jpg"
                    source = AnimeSource.NETFLIX_MIRROR.name
                }
            }
            MangaPage(animeList, false)
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =========================== Details & Episodes ============================
    private suspend fun fetchPostData(animeId: String): NetflixPostData? {
        return try {
            getCookieValue()
            val url = "$baseUrl/post.php?id=$animeId&t=${System.currentTimeMillis()}"
            val responseBody = makeApiRequest(url, referer = "$baseUrl/tv/home")

            if (responseBody.length < 50) return null

            json.decodeFromString<NetflixPostData>(responseBody)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchAnimeDetails(animeId: String): SAnime? = withContext(Dispatchers.IO) {
        val data = fetchPostData(animeId) ?: return@withContext null

        SAnime().apply {
            url = animeId
            title = data.title ?: ""
            thumbnail_url = "https://imgcdn.media/poster/v/$animeId.jpg"
            description = data.desc
            genre = data.genre
            status = SAnime.UNKNOWN
            source = AnimeSource.NETFLIX_MIRROR.name
        }
    }

    suspend fun fetchEpisodeList(animeId: String): List<SEpisode> = withContext(Dispatchers.IO) {
        try {
            val data = fetchPostData(animeId) ?: return@withContext emptyList()
            val episodes = mutableListOf<SEpisode>()

            if (data.episodes?.firstOrNull() == null) {
                episodes.add(SEpisode().apply {
                    name = data.title ?: "Movie"
                    url = animeId
                    episode_number = 1f
                })
                return@withContext episodes
            }

            data.episodes.filterNotNull().mapTo(episodes) {
                val seasonNumber = it.s.replace("S", "")
                SEpisode().apply {
                    name = "الموسم $seasonNumber : ${it.t}"
                    url = it.id
                    episode_number = it.ep.replace("E", "").toFloatOrNull() ?: 0f
                }
            }

            if (data.nextPageShow == 1 && !data.nextPageSeason.isNullOrEmpty()) {
                episodes.addAll(getPaginatedEpisodes(animeId, data.nextPageSeason, 2))
            }

            data.season?.dropLast(1)?.forEach { seasonInfo ->
                episodes.addAll(getPaginatedEpisodes(animeId, seasonInfo.id, 1))
            }

            episodes
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun getPaginatedEpisodes(seriesId: String, seasonId: String, startPage: Int = 1): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var page = startPage
        try {
            while (true) {
                val urll = "$baseUrl/episodes.php?s=$seasonId&series=$seriesId&t=${System.currentTimeMillis()}&page=$page"
                val responseBody = makeApiRequest(urll, referer = "$baseUrl/tv/home")
                val data = json.decodeFromString<NetflixEpisodesPage>(responseBody)

                data.episodes?.mapTo(episodes) {
                    val seasonNumber = it.s.replace("S", "")
                    SEpisode().apply {
                        name = "الموسم $seasonNumber : ${it.t}"
                        url = it.id
                        episode_number = it.ep.replace("E", "").toFloatOrNull() ?: 0f
                    }
                }
                if (data.nextPageShow == 0) break
                page++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return episodes
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeId: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            getCookieValue()
            // The reference provider uses the title here, but it may not be strictly necessary.
            // Using the episode name as a fallback.
//            val title = episode.name
            val url = "$baseUrl/tv/playlist.php?id=$episodeId&t=video&tm=${System.currentTimeMillis()}"
//            val url = "$baseUrl/tv/playlist.php?id=${episode.url}&t=$title&tm=${System.currentTimeMillis()}"
            val responseBody = makeApiRequest(url, referer = "$baseUrl/tv/home")


            val playlistData = json.decodeFromString<List<NetflixPlaylist>>(responseBody)
            val videos = mutableListOf<Video>()

            playlistData.forEach { playlist ->
                val subtitles = playlist.tracks
                    ?.filter { it.kind == "captions" && !it.file.isNullOrEmpty() && !it.label.isNullOrEmpty() }
                    ?.map { track ->
                        val subtitleUrl = when {
                            track.file!!.startsWith("//") -> "https:${track.file}"
                            track.file.startsWith("/") -> "$baseUrl${track.file}"
                            else -> track.file
                        }
                        Subtitle(url = subtitleUrl, lang = track.label!!)
                    } ?: emptyList()

                playlist.sources.forEach { source ->
                    // Corrected: Video files are on a different domain.
                    val videoUrl = "https://net50.cc${source.file.replace("/tv/", "/")}"

                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = source.label,
                            videoUrl = videoUrl,
                            // Corrected: Referer must match the video host.
                            headers = mapOf(
                                "Cookie" to "hd=on",
                                "Referer" to "https://net50.cc/"
                            ),
                            subtitles = subtitles
                        )
                    )
                }
            }
            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Extra Main Page Functions =============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            getCookieValue()
            val request = Request.Builder().url("$baseUrl/home")
                .header("Cookie", buildCookieHeader())
                .header("Referer", "$baseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body!!.string())

                val sliderItems = document.select(".lolomoRow").firstOrNull()
                    ?.select("img.lazy")
                    ?.mapNotNull {

                        val dataSrc = it.attr("data-src")
                        println("dataSrcc = ${it.toString()} $dataSrc")
                        if (dataSrc.isNotEmpty()) {
                            val id = dataSrc.substringAfterLast("/").substringBefore(".")
                            SAnime().apply {
                                title = it.attr("alt").ifEmpty { "Unknown Title" }
                                url = id
                                thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                                source = AnimeSource.NETFLIX_MIRROR.name
                            }
                        } else null
                    } ?: emptyList()
                return@withContext sliderItems
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}