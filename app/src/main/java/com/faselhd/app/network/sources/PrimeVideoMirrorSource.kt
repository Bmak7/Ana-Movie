package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

// This source uses the same DTOs and Cookie Storage as NetflixMirrorSource because the API structure is identical.

class PrimeVideoMirrorSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Direct initialization of Json, removing the need for injectLazy
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://net2025.cc"
    private var cookieValue: String = ""
    private val cookieMutex = Mutex()

    // Re-using the same bypass and storage logic from NetflixMirror as they share the same backend
    private suspend fun bypassCookies(): String {
        return try {
            println("Starting bypass process for PrimeVideoMirror...")
            var attempts = 0
            val maxAttempts = 10

            while (attempts < maxAttempts) {
                attempts++
                val request = Request.Builder()
                    .url("$baseUrl/tv/p.php")
                    .post(okhttp3.RequestBody.create(null, ""))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (responseBody.contains("\"r\":\"n\"")) {
                    val setCookieHeaders = response.headers("Set-Cookie")
                    val tHashCookie = setCookieHeaders
                        .firstOrNull { it.contains("t_hash_t") }
                        ?.substringAfter("t_hash_t=")
                        ?.substringBefore(";")
                    response.close()
                    if (!tHashCookie.isNullOrEmpty()) {
                        return tHashCookie
                    }
                }
                response.close()
                if (attempts < maxAttempts) {
                    delay(1000)
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
            if (cookieValue.isEmpty()) {
                val (cachedCookie, cachedTimestamp) = NetflixMirrorStorage.getCookie()
                if (!cachedCookie.isNullOrEmpty() && System.currentTimeMillis() - cachedTimestamp < 54_000_000) {
                    cookieValue = cachedCookie
                    return cookieValue
                }
                try {
                    cookieValue = bypassCookies()
                    if (cookieValue.isNotEmpty()) {
                        NetflixMirrorStorage.saveCookie(cookieValue)
                    } else {
                        NetflixMirrorStorage.clearCookie()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    cookieValue = ""
                    NetflixMirrorStorage.clearCookie()
                }
            }
            return cookieValue
        }
    }

    private fun getCookies(): Map<String, String> {
        return mapOf(
            "t_hash_t" to cookieValue,
            "ott" to "pv", // The only key change: "pv" for Prime Video
            "hd" to "on"
        )
    }

    // =============================== Popular/Homepage ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        try {
            getCookieValue()
            val request = Request.Builder().url("$baseUrl/tv/home")
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val popularItems = document.select(".lolomoRow").firstOrNull()
                ?.select("img.lazy")
                ?.mapNotNull {
                    val dataSrc = it.attr("data-src")
                    if (dataSrc.isNotEmpty()) {
                        val id = dataSrc.substringAfterLast("/").substringBefore(".")
                        SAnime().apply {
                            title = it.attr("alt").takeIf { it.isNotEmpty() } ?: "Unknown Title"
                            url = id
                            thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                            source = AnimeSource.PRIME_VIDEO_MIRROR.name
                        }
                    } else null
                } ?: emptyList()

            MangaPage(popularItems, false)
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

            val responseBody = client.newCall(request).execute().body!!.string()
            if (responseBody.isBlank() || !responseBody.trim().startsWith("{")) {
                return@withContext MangaPage(emptyList(), false)
            }
            val data = json.decodeFromString<NetflixSearchData>(responseBody)

            val animeList = data.searchResult.map {
                SAnime().apply {
                    url = it.id
                    title = it.t
                    thumbnail_url = "https://imgcdn.media/poster/v/${it.id}.jpg"
                    source = AnimeSource.PRIME_VIDEO_MIRROR.name
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
            val requestBuilder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/tv/home")
            if (cookieValue.isNotEmpty()) {
                requestBuilder.header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
            }
            val responseBody = client.newCall(requestBuilder.build()).execute().body!!.string()
            if (responseBody.length < 50) null else json.decodeFromString<NetflixPostData>(responseBody)
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
            source = AnimeSource.PRIME_VIDEO_MIRROR.name
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
                SEpisode().apply {
                    name = it.t
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
                val request = Request.Builder().url(urll)
                    .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                val responseBody = client.newCall(request).execute().body!!.string()
                val data = json.decodeFromString<NetflixEpisodesPage>(responseBody)

                data.episodes?.mapTo(episodes) {
                    SEpisode().apply {
                        name = it.t
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
            val url = "$baseUrl/tv/playlist.php?id=$episodeId&t=video&tm=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url)
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/tv/home")
                .build()
            val responseBody = client.newCall(request).execute().body!!.string()
            val playlistData = json.decodeFromString<List<NetflixPlaylist>>(responseBody)

            val subtitles = mutableListOf<Subtitle>()
            playlistData.forEach { playlist ->
                playlist.tracks?.forEach { track ->
                    if (!track.file.isNullOrEmpty() && !track.label.isNullOrEmpty()) {
                        val subtitleUrl = when {
                            track.file.startsWith("//") -> "https:${track.file}"
                            track.file.startsWith("/") -> "$baseUrl${track.file}"
                            else -> track.file
                        }
                        subtitles.add(Subtitle(url = subtitleUrl, lang = track.label))
                    }
                }
            }

            val videos = mutableListOf<Video>()
            playlistData.forEach { playlist ->
                playlist.sources.forEach { source ->
                    val videoUrl = if (source.file.startsWith("/")) "$baseUrl${source.file}" else source.file
                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = source.label,
                            videoUrl = videoUrl,
                            headers = mapOf(
                                "Cookie" to "hd=on",
                                "Referer" to "$baseUrl/tv/home"
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

    // =============================== Main Slider ===============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            getCookieValue()
            val request = Request.Builder().url("$baseUrl/tv/home")
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val sliderItems = document.select(".lolomoRow").firstOrNull()
                ?.select("img.lazy")
                ?.mapNotNull {
                    val dataSrc = it.attr("data-src")
                    if (dataSrc.isNotEmpty()) {
                        val id = dataSrc.substringAfterLast("/").substringBefore(".")
                        SAnime().apply {
                            title = it.attr("alt").takeIf { it.isNotEmpty() } ?: "Unknown Title"
                            url = id
                            thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                            source = AnimeSource.PRIME_VIDEO_MIRROR.name
                        }
                    } else null
                } ?: emptyList()

            sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}