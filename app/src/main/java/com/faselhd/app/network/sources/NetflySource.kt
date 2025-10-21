package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest

// --- Finalized Data Classes Based on Real API Responses ---

data class NetflyBaseResponse<T>(
    @SerializedName("code") val code: Int?,
    @SerializedName("msg") val msg: String?,
    @SerializedName("data") val data: T?
)

data class NetflyListData<T>(
    @SerializedName("count") val count: Int?,
    @SerializedName("page") val page: Int?,
    @SerializedName("page_size") val pageSize: Int?,
    @SerializedName("list") val items: List<T>?
)

data class NetflyAnimeItem(
    @SerializedName("id") val id: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("video_pic") val posterUrl: String?,
    @SerializedName("source_type") val sourceType: Int? // 2 for movie, 3 for series
)

data class NetflyAnimeDetails(
    @SerializedName("id") val id: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("video_pic") val posterUrl: String?,
    @SerializedName("description") val overview: String?,
    @SerializedName("tags") val tags: List<NetflyTag>?,
    @SerializedName("source_type") val sourceType: Int?,
    @SerializedName("seasons") val seasons: List<Int>?
)

data class NetflyTag(
    @SerializedName("title") val title: String?
)

data class NetflyEpisodeItem(
    @SerializedName("video_id") val id: Int?,
    @SerializedName("name") val title: String?,
    @SerializedName("episode_number") val episodeNumber: Int?
)

data class NetflyVideoSource(
    @SerializedName("video_source") val turboUrl: String?,
    @SerializedName("source_quality") val quality: String?
)


class NetflySource(private val context: Context) {
    companion object {
        const val ROOT_URL = "http://as.netflyapp.com/api/v1"
        const val LOCAL_PROXY_URL = "http://127.0.0.1:63000"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13; SM-M325F Build/TP1A.220624.014)"
        private const val TAG = "NetflySource" // For easy Logcat filtering

    }

    private val client = OkHttpClient()
    private val gson = Gson()

    private fun getAuthParams(): Pair<String, String> {
        val timeOffset = (System.currentTimeMillis() / 1000) - 2524620288L
        val wsTime = Integer.toHexString(timeOffset.toInt())
        val stringToHash = "metaedge/video/$wsTime"
        val wsSecret = md5(stringToHash).substring(0, 8)
        return Pair(wsSecret, wsTime)
    }

    private fun md5(input: String): String = MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun buildUrlWithAuth(path: String, params: Map<String, String> = emptyMap()): String {
        val (secret, time) = getAuthParams()
        val builder = (ROOT_URL + path).toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("ws_secret", secret)
            .addQueryParameter("ws_time", time)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    // === OPTIMIZED HELPER FUNCTION (Prevents OutOfMemoryError) ===
    private suspend fun getApiData(path: String, params: Map<String, String> = emptyMap()): JsonElement? {
        val url = buildUrlWithAuth(path, params)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        println("$TAG: Requesting -> $url")
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("$TAG: ERROR - Request failed with code ${response.code} for URL: $url")
                return null
            }
            val responseBody = response.body?.string() ?: return null
            println("$TAG: SUCCESS - Got response for $path (size: ${responseBody.length})")

            val jsonObject = JsonParser.parseString(responseBody).asJsonObject
            if (jsonObject.has("data") && !jsonObject.get("data").isJsonNull) {
                jsonObject.get("data") // Return as JsonElement, not String
            } else {
                println("$TAG: WARN - JSON response for $path has no 'data' field or it is null.")
                null
            }
        } catch (e: Exception) {
            println("$TAG: CRITICAL - Exception in getApiData for $path: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        println("$TAG: fetchLatestUpdates(page=$page)")
        val dataElement = getApiData("/video/list", mapOf("page" to page.toString()))
        if (dataElement == null) {
            println("$TAG: fetchLatestUpdates - getApiData returned null. Aborting.")
            return@withContext MangaPage(emptyList(), false)
        }

        try {
            val dataType = object : TypeToken<NetflyListData<NetflyAnimeItem>>() {}.type
            val data: NetflyListData<NetflyAnimeItem>? = gson.fromJson(dataElement, dataType)

            val animeList = data?.items?.mapNotNull { item ->
                item.id?.let {
                    SAnime().apply {
                        this.url = "$it;sourceType=${item.sourceType ?: 3}"
                        this.title = item.title ?: "No Title"
                        this.thumbnail_url = item.posterUrl
                    }
                }
            } ?: emptyList()

            val hasNextPage = if (data?.page != null && data.pageSize != null && data.count != null) {
                (data.page * data.pageSize) < data.count
            } else false

            println("$TAG: fetchLatestUpdates - Success. Parsed ${animeList.size} items. HasNextPage: $hasNextPage")
            MangaPage(animeList, hasNextPage)
        } catch (e: Exception) {
            println("$TAG: CRITICAL - GSON parsing failed in fetchLatestUpdates: ${e.message}")
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        println("$TAG: fetchPopularSeries(page=$page)")
        val dataElement = getApiData("/popular", mapOf("page" to page.toString()))
        if (dataElement == null) {
            println("$TAG: fetchPopularSeries - getApiData returned null. Aborting.")
            return@withContext MangaPage(emptyList(), false)
        }

        try {
            val dataType = object : TypeToken<NetflyListData<NetflyAnimeItem>>() {}.type
            val data: NetflyListData<NetflyAnimeItem>? = gson.fromJson(dataElement, dataType)

            val animeList = data?.items?.mapNotNull { item ->
                item.id?.let {
                    SAnime().apply {
                        this.url = "$it;sourceType=${item.sourceType ?: 3}"
                        this.title = item.title ?: "No Title"
                        this.thumbnail_url = item.posterUrl
                    }
                }
            } ?: emptyList()

            val hasNextPage = if (data?.page != null && data.pageSize != null && data.count != null) {
                (data.page * data.pageSize) < data.count
            } else false

            println("$TAG: fetchPopularSeries - Success. Parsed ${animeList.size} items. HasNextPage: $hasNextPage")
            MangaPage(animeList, hasNextPage)
        } catch (e: Exception) {
            println("$TAG: CRITICAL - GSON parsing failed in fetchPopularSeries: ${e.message}")
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String): MangaPage = withContext(Dispatchers.IO) {
        val dataString = getApiData("/video/search", mapOf("page" to page.toString(), "q" to query))
        if (dataString == null) {
            return@withContext MangaPage(emptyList(), false)
        }

        val dataType = object : TypeToken<List<NetflyAnimeItem>>() {}.type
        val data: List<NetflyAnimeItem>? = gson.fromJson(dataString, dataType)

        val animeList = data?.mapNotNull { item ->
            item.id?.let {
                SAnime().apply {
                    this.url = "$it;sourceType=${item.sourceType ?: 3}"
                    this.title = item.title ?: "No Title"
                    this.thumbnail_url = item.posterUrl
                }
            }
        } ?: emptyList()

        MangaPage(animeList, hasNextPage = false)
    }

    suspend fun fetchAnimeDetails(url: String): SAnime = withContext(Dispatchers.IO) {
        val parts = url.split(";sourceType=")
        val animeId = parts[0]
        val sourceType = parts.getOrNull(1) ?: "3"

        val dataString = getApiData("/video/info", mapOf("video_id" to animeId, "source_type" to sourceType))
        val details: NetflyAnimeDetails? = dataString?.let { gson.fromJson(it, NetflyAnimeDetails::class.java) }

        return@withContext SAnime().apply {
            this.url = url
            title = details?.title ?: "Unknown Title"
            thumbnail_url = details?.posterUrl
            description = details?.overview
            genre = details?.tags?.mapNotNull { it.title }?.distinct()?.joinToString(", ")
//            seasons = details?.seasons
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val parts = animeUrl.split(";sourceType=")
        val animeId = parts[0]
        val sourceType = parts.getOrNull(1) ?: "3"

        if (sourceType == "2") {
            return@withContext listOf(SEpisode().apply {
                this.url = "$animeId/$animeId"
                this.name = "Watch Movie"
                this.episode_number = 1f
            })
        }

        val detailsDataString = getApiData("/video/info", mapOf("video_id" to animeId, "source_type" to sourceType))
        val details: NetflyAnimeDetails? = detailsDataString?.let { gson.fromJson(it, NetflyAnimeDetails::class.java) }
        val seasons = details?.seasons ?: return@withContext emptyList()

        coroutineScope {
            seasons.map { seasonNumber ->
                async {
                    val episodesDataString = getApiData("/video/episodes/list", mapOf("series_id" to animeId, "season_number" to seasonNumber.toString()))
                    val episodeDataType = object : TypeToken<List<NetflyEpisodeItem>>() {}.type
                    val episodesData: List<NetflyEpisodeItem>? = episodesDataString?.let { gson.fromJson(it, episodeDataType) }

                    episodesData?.mapNotNull { episode ->
                        episode.id?.let {
                            SEpisode().apply {
                                this.url = "$animeId/$it"
                                this.name = "S${seasonNumber} E${episode.episodeNumber}: ${episode.title ?: ""}"
                                this.episode_number = episode.episodeNumber?.toFloat() ?: 1f
                            }
                        }
                    } ?: emptyList()
                }
            }.awaitAll()
                .flatten()
                .sortedBy { it.episode_number }
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val (seriesId, episodeId) = episodeUrl.split("/").let { it[0] to it[1] }

        println("$TAG: Fetching video list for seriesId: $seriesId, episodeId: $episodeId")

        val dataString = getApiData("/video/episodes/sources", mapOf("series_id" to seriesId, "video_id" to episodeId))
        val dataType = object : TypeToken<List<NetflyVideoSource>>() {}.type
        val data: List<NetflyVideoSource>? = dataString?.let { gson.fromJson(it, dataType) }

        if (data == null) {
            println("$TAG: No video source data returned from API.")
            return@withContext emptyList()
        }

        return@withContext data.mapNotNull { source ->
            source.turboUrl?.let { turboUrl ->
                println("$TAG: Processing turbo URL: $turboUrl")

                val parts = turboUrl.replace("turbo://", "").split('/')
                if (parts.size < 2) {
                    println("$TAG: Invalid turbo URL format: $turboUrl")
                    return@mapNotNull null
                }

                val serverPart = parts[0]
                val fileId = parts[1]

                val host = serverPart.substringAfter("default.")
                val videoBaseUrl = "https://$host.b-cdn.net"

                val playableUrl = "$videoBaseUrl/$fileId/playlist.m3u8"

                println("$TAG: Converted to FINAL playable URL: $playableUrl")

                // === THE FINAL FIX IS HERE ===
                // Create a map of headers to pass to the video player.
                // The server is checking BOTH the Referer and the User-Agent.
                val videoHeaders = mapOf(
                    "Referer" to "https://netflyapp.com/",
                    "User-Agent" to USER_AGENT
                )

                Video(
                    url = playableUrl,
                    quality = source.quality ?: "Default",
                    videoUrl = playableUrl,
                    headers = videoHeaders // Pass the complete headers map
                )
            }
        }
    }
}