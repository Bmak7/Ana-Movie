package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Create Json instance directly instead of using dependency injection
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://net2025.cc"
    private var cookieValue: String = ""
    private val cookieMutex = Mutex()

    // Alternative bypass method like in CloudStream
    private suspend fun bypassCookies(): String {
        return try {
            println("Starting bypass process...")
            var attempts = 0
            val maxAttempts = 10

            while (attempts < maxAttempts) {
                attempts++
                println("Bypass attempt $attempts")

                val request = Request.Builder()
                    .url("$baseUrl/tv/p.php")
                    .post(okhttp3.RequestBody.create(null, ""))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                println("Bypass response: $responseBody")

                if (responseBody.contains("\"r\":\"n\"")) {
                    println("Bypass successful, extracting cookie...")

                    // Extract cookie from Set-Cookie headers
                    val setCookieHeaders = response.headers("Set-Cookie")
                    val tHashCookie = setCookieHeaders
                        .firstOrNull { it.contains("t_hash_t") }
                        ?.substringAfter("t_hash_t=")
                        ?.substringBefore(";")

                    response.close()

                    if (!tHashCookie.isNullOrEmpty()) {
                        println("Cookie extracted successfully: $tHashCookie")
                        return tHashCookie
                    }
                }

                response.close()

                if (attempts < maxAttempts) {
                    kotlinx.coroutines.delay(1000) // Wait 1 second between attempts
                }
            }

            println("Bypass failed after $maxAttempts attempts")
            ""
        } catch (e: Exception) {
            println("Bypass error: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    // Helper to get and store the essential cookie
    private suspend fun getCookieValue(): String {
        cookieMutex.withLock {
            if (cookieValue.isEmpty()) {
                // Check cached cookie first
                val (cachedCookie, cachedTimestamp) = NetflixMirrorStorage.getCookie()

                // Return cached cookie if valid (≤15 hours old)
                if (!cachedCookie.isNullOrEmpty() && System.currentTimeMillis() - cachedTimestamp < 54_000_000) {
                    println("Using cached cookie")
                    cookieValue = cachedCookie
                    return cookieValue
                }

                try {
                    println("Getting new cookie using bypass method...")
                    cookieValue = bypassCookies()

                    // Save the new cookie to cache
                    if (cookieValue.isNotEmpty()) {
                        NetflixMirrorStorage.saveCookie(cookieValue)
                    } else {
                        NetflixMirrorStorage.clearCookie()
                    }

                    println("Cookie result: ${if (cookieValue.isNotEmpty()) "SUCCESS" else "FAILED"}, value: $cookieValue")
                } catch (e: Exception) {
                    println("Cookie fetch error: ${e.message}")
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
            "ott" to "nf",
            "hd" to "on"
        )
    }

    // =============================== Popular/Homepage ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false) // Homepage is single page

        try {
            getCookieValue()

            val request = Request.Builder().url("$baseUrl/tv/home")
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .build()

            val response = client.newCall(request).execute()
            val document = Jsoup.parse(response.body!!.string())
            response.close()

            val popularItems = document.select(".lolomoRow").firstOrNull()
                ?.select("img.lazy")
                ?.mapNotNull {
                    val dataSrc = it.attr("data-src")
                    if (dataSrc.isNotEmpty()) {
                        val id = dataSrc.substringAfterLast("/").substringBefore(".")
                        SAnime().apply {
                            title = it.attr("alt").takeIf { it.isNotEmpty() } ?: "Unknown Title"
                            url = id // We only need the ID
                            thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                            source = AnimeSource.NETFLIX_MIRROR.name
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

    // Private helper to avoid duplicate API calls
    private suspend fun fetchPostData(animeId: String): NetflixPostData? {
        return try {
            println("Fetching post data for anime: $animeId")
            getCookieValue()

            // If we still don't have a cookie, try a different approach
            if (cookieValue.isEmpty()) {
                println("No cookie available, attempting request without cookie")
            }

            val url = "$baseUrl/post.php?id=$animeId&t=${System.currentTimeMillis()}"
            println("Making post data request to: $url")

            val requestBuilder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/tv/home")

            if (cookieValue.isNotEmpty()) {
                requestBuilder.header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            val responseBody = response.body!!.string()

            println("Post data response status: ${response.code}, length: ${responseBody.length}")
            println("Post data response: ${responseBody.take(200)}${if (responseBody.length > 200) "..." else ""}")

            response.close()

            if (responseBody.length < 50) {
                println("Response too short, likely an error")
                return null
            }

            val data = json.decodeFromString<NetflixPostData>(responseBody)
            println("Post data parsed successfully: title=${data.title}, episodes=${data.episodes?.size}, seasons=${data.season?.size}")

            data
        } catch (e: Exception) {
            println("Post data fetch error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchAnimeDetails(animeId: String): SAnime? = withContext(Dispatchers.IO) {
        val data = fetchPostData(animeId) ?: return@withContext null

        SAnime().apply {
            url = animeId // Keep the ID as the URL
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
            println("Fetching episode list for: $animeId")
            val data = fetchPostData(animeId) ?: return@withContext emptyList()
            val episodes = mutableListOf<SEpisode>()

            println("Netflix Anime Details: $data")

            // Handle movies (single episode)
            if (data.episodes?.firstOrNull() == null) {
                println("No episodes found, treating as movie")
                episodes.add(SEpisode().apply {
                    name = data.title ?: "Movie"
                    url = animeId
                    episode_number = 1f
                })
                println("Returning single episode for movie")
                return@withContext episodes
            }

            println("Found ${data.episodes.size} episodes in first batch")
            // Add first page of episodes
            data.episodes.filterNotNull().mapTo(episodes) {
                // ========= MODIFICATION START =========
                val seasonNumber = it.s.replace("S", "")
                SEpisode().apply {
                    // Format the name to be "الموسم [Number] : [Title]"
                    name = "الموسم $seasonNumber : ${it.t}"
                    url = it.id // Store episode ID in URL
                    episode_number = it.ep.replace("E", "").toFloatOrNull() ?: 0f
                }
                // ========= MODIFICATION END =========
            }

            // Handle pagination for current season
            if (data.nextPageShow == 1 && !data.nextPageSeason.isNullOrEmpty()) {
                println("Fetching additional pages for season: ${data.nextPageSeason}")
                episodes.addAll(getPaginatedEpisodes(animeId, data.nextPageSeason, 2))
            }

            // Fetch remaining seasons if they exist
            data.season?.dropLast(1)?.forEach { seasonInfo ->
                println("Fetching episodes for season: ${seasonInfo.id}")
                episodes.addAll(getPaginatedEpisodes(animeId, seasonInfo.id, 1))
            }

            println("Total episodes found: ${episodes.size}")
            episodes
        } catch (e: Exception) {
            println("Episode list fetch error: ${e.message}")
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

                val response = client.newCall(request).execute()
                val responseBody = response.body!!.string()
                response.close()

                val data = json.decodeFromString<NetflixEpisodesPage>(responseBody)

                data.episodes?.mapTo(episodes) {
                    // ========= MODIFICATION START =========
                    val seasonNumber = it.s.replace("S", "")
                    SEpisode().apply {
                        // Format the name to be "الموسم [Number] : [Title]"
                        name = "الموسم $seasonNumber : ${it.t}"
                        url = it.id
                        episode_number = it.ep.replace("E", "").toFloatOrNull() ?: 0f
                    }
                    // ========= MODIFICATION END =========
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

            val response = client.newCall(request).execute()
            val responseBody = response.body!!.string()
            response.close()

            println("Playlist response: $responseBody") // Debug log

            val playlistData = json.decodeFromString<List<NetflixPlaylist>>(responseBody)
            // ========= MODIFICATION START =========

            val subtitles = mutableListOf<Subtitle>()
            playlistData.forEach { playlist ->
                playlist.tracks?.forEach { track ->
                    if (!track.file.isNullOrEmpty() && !track.label.isNullOrEmpty()) {
                        // Handle different URL formats (absolute, protocol-relative, relative)
                        val subtitleUrl = when {
                            track.file.startsWith("//") -> "https:${track.file}" // Handle protocol-relative URLs
                            track.file.startsWith("/") -> "$baseUrl${track.file}" // Handle absolute path URLs
                            else -> track.file // Assume full URL
                        }
                        subtitles.add(
                            Subtitle(
                                url = subtitleUrl,
                                lang = track.label
                            )
                        )
                    }
                }
            }

            val videos = mutableListOf<Video>()
            playlistData.forEach { playlist ->
                playlist.sources.forEach { source ->
                    val videoUrl = if (source.file.startsWith("/")) {
                        "$baseUrl${source.file}"
                    } else {
                        source.file
                    }

                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = source.label,
                            videoUrl = videoUrl,
                            headers = mapOf(
                                "Cookie" to "hd=on",
                                "Referer" to "$baseUrl/tv/home"
                            ),
                            subtitles = subtitles // Attach the corrected subtitle list
                        )
                    )
                }
            }
            println("Videos found: ${videos.size}, Subtitles found: ${subtitles.size}")
            videos


            // ========= MODIFICATION END =========
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Stubs for other functions
    // ========= MODIFICATION START =========
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            getCookieValue()

            val request = Request.Builder().url("$baseUrl/tv/home")
                .header("Cookie", getCookies().map { "${it.key}=${it.value}" }.joinToString("; "))
                .build()

            val response = client.newCall(request).execute()
            val document = Jsoup.parse(response.body!!.string())
            response.close()

            // Scrape the first row of items, which is typically the featured/slider content
            val sliderItems = document.select(".lolomoRow").firstOrNull()
                ?.select("img.lazy")
                ?.mapNotNull {
                    val dataSrc = it.attr("data-src")
                    if (dataSrc.isNotEmpty()) {
                        val id = dataSrc.substringAfterLast("/").substringBefore(".")
                        SAnime().apply {
                            title = it.attr("alt").takeIf { it.isNotEmpty() } ?: "Unknown Title"
                            url = id // We only need the ID for navigation
                            // Use a different image URL format if the slider has larger "backdrop" images
                            // For this source, the poster format seems to be the only one available.
                            thumbnail_url = "https://imgcdn.media/poster/v/$id.jpg"
                            source = AnimeSource.NETFLIX_MIRROR.name
                        }
                    } else null
                } ?: emptyList()

            return@withContext sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
    // ========= MODIFICATION END =========
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}

