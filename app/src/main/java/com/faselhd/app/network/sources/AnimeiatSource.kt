package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// REMOVED: uy.kohesive.injekt.injectLazy - We no longer need this

//region DTOs for Animeiat API
@Serializable
data class AnimeiatPopularAnimeResponse(
    val data: List<AnimeiatPopularAnimeList>,
    val meta: AnimeiatMeta,
)

@Serializable
data class AnimeiatLatestAnimeResponse(
    val data: List<AnimeiatEpisode>,
    val meta: AnimeiatMeta,
)

@Serializable
data class AnimeiatPopularAnimeList(
    @SerialName("anime_name") val animeName: String,
    @SerialName("poster_path") val posterPath: String,
    val slug: String,
)

@Serializable
data class AnimeiatMeta(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
data class AnimeiatAnimePageResponse(
    val data: AnimeiatAnimeDetails,
)

@Serializable
data class AnimeiatAnimeDetails(
    @SerialName("anime_name") val animeName: String,
    val genres: List<AnimeiatGenre>,
    @SerialName("poster_path") val posterPath: String,
    val slug: String,
    val status: String,
    val story: String,
    val studios: List<AnimeiatStudio>,
)

@Serializable
data class AnimeiatGenre(val name: String)

@Serializable
data class AnimeiatStudio(val name: String)

@Serializable
data class AnimeiatAnimeEpisodesList(
    val data: List<AnimeiatEpisode>,
    val links: AnimeiatLinks,
)

@Serializable
data class AnimeiatLinks(
    val next: String? = null,
)

@Serializable
data class AnimeiatEpisode(
    val number: Float,
    val slug: String,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null, // Only in latest
)

@Serializable
data class AnimeiatPlayerHashResponse(
    val hash: String,
)

@Serializable
data class AnimeiatPlayerIdPayload( // For robustly parsing the decoded hash
    val id: String,
)

@Serializable
data class AnimeiatStreamLinks(
    val data: AnimeiatVideoInformation,
)

@Serializable
data class AnimeiatVideoInformation(
    val sources: List<AnimeiatSourceInfo>,
)

@Serializable
data class AnimeiatSourceInfo(
    val file: String,
    val label: String,
    val quality: String,
)
//endregion

class AnimeiatSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ==================================================================
    //  FIXED: Changed from injectLazy to direct initialization
    // ==================================================================
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://api.animeiat.co/v1"
    private val storageUrl = "https://api.animeiat.co/storage"

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/anime?page=$page").build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<AnimeiatPopularAnimeResponse>(response.body!!.string())

        val animeList = responseJson.data.map {
            SAnime().apply {
                url = it.slug
                title = it.animeName
                thumbnail_url = "$storageUrl/${it.posterPath}"
                source = AnimeSource.ANIMEIAT.name
            }
        }
        val hasNextPage = responseJson.meta.currentPage < responseJson.meta.lastPage
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/home/sticky-episodes?page=$page").build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<AnimeiatLatestAnimeResponse>(response.body!!.string())

        val animeList = responseJson.data.map {
            SAnime().apply {
                url = it.slug.substringBefore("-episode-")
                title = it.title
                thumbnail_url = "$storageUrl/${it.posterPath}"
                source = AnimeSource.ANIMEIAT.name
            }
        }
        val hasNextPage = responseJson.meta.currentPage < responseJson.meta.lastPage
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchLatestUpdatess(page: Int): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/home/sticky-episodes?page=$page").build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<AnimeiatLatestAnimeResponse>(response.body!!.string())

        val animeList = responseJson.data.map {
            SAnime().apply {
                url = it.slug.substringBefore("-episode-")
                title = it.title
                thumbnail_url = "$storageUrl/${it.posterPath}"
                source = AnimeSource.ANIMEIAT.name
            }
        }
        val hasNextPage = responseJson.meta.currentPage < responseJson.meta.lastPage
        (animeList)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = if (query.isNotBlank()) "$baseUrl/anime?q=$query&page=$page" else "$baseUrl/anime?page=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<AnimeiatPopularAnimeResponse>(response.body!!.string())

        val animeList = responseJson.data.map {
            SAnime().apply {
                this.url = it.slug
                this.title = it.animeName
                thumbnail_url = "$storageUrl/${it.posterPath}"
                source = AnimeSource.ANIMEIAT.name
            }
        }
        val hasNextPage = responseJson.meta.currentPage < responseJson.meta.lastPage
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeSlug: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/anime/$animeSlug").build()
        val response = client.newCall(request).execute()
        val details = json.decodeFromString<AnimeiatAnimePageResponse>(response.body!!.string()).data

        SAnime().apply {
            url = details.slug
            title = details.animeName
            status = when (details.status) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = details.genres.joinToString { it.name }
            description = details.story
            thumbnail_url = "$storageUrl/${details.posterPath}"
            source = AnimeSource.ANIMEIAT.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeSlug: String): List<SEpisode> = withContext(Dispatchers.IO) {
        // ========= MODIFICATION START =========

        // 1. Fetch the anime's details first to get its name. This will serve as the "season" name.
        val animeNameAsSeason = try {
            val detailsRequest = Request.Builder().url("$baseUrl/anime/$animeSlug").build()
            val detailsResponse = client.newCall(detailsRequest).execute()
            json.decodeFromString<AnimeiatAnimePageResponse>(detailsResponse.body!!.string()).data.animeName
        } catch (e: Exception) {
            e.printStackTrace()
            "الموسم 1" // Provide a fallback name in case the details fetch fails
        }

        // 2. Proceed with fetching the paginated list of episodes.
        val episodeList = mutableListOf<SEpisode>()
        var nextUrl: String? = "$baseUrl/anime/$animeSlug/episodes"

        while (nextUrl != null) {
            val request = Request.Builder().url(nextUrl).build()
            val response = client.newCall(request).execute()
            val responseJson = json.decodeFromString<AnimeiatAnimeEpisodesList>(response.body!!.string())

            episodeList.addAll(
                responseJson.data.map {
                    SEpisode().apply {
                        // 3. Format the name consistently: "Anime Title : Episode Title"
                        name = "$animeNameAsSeason : ${it.title}"
                        url = it.slug
                        episode_number = it.number
                    }
                }
            )
            nextUrl = responseJson.links.next
        }
        return@withContext episodeList

        // ========= MODIFICATION END =========
    }


    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeSlug: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
//            val referer = "https://animeiat.co/"

            // 1. Get the player hash from the episode endpoint
            val hashRequest = Request.Builder()
                .url("$baseUrl/episode/$episodeSlug")
                .header("User-Agent", userAgent)
//                .header("Referer", referer)
                .build()
            val hashResponse = client.newCall(hashRequest).execute()
            val playerHash = json.decodeFromString<AnimeiatPlayerHashResponse>(hashResponse.body!!.string()).hash

            // 2. Decode the hash to get the PHP Serialized String
            val decodedPayload = String(Base64.decode(playerHash, Base64.DEFAULT))

            // =========================================================================
            //  THE FIX: Use a Regular Expression to extract the ID from the PHP string
            // =========================================================================
            val uuidPattern = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
            val matcher = uuidPattern.matcher(decodedPayload)

            val playerId = if (matcher.find()) {
                matcher.group(0) // Get the first full match (the ID)
            } else {
                // If we can't find the ID, we cannot proceed.
                println("Could not find player ID in decoded payload: $decodedPayload")
                return@withContext emptyList<Video>()
            }

            // 3. Get the video sources using the extracted player ID
            val sourcesRequest = Request.Builder()
                .url("$baseUrl/video/$playerId")
                .header("User-Agent", userAgent)
//                .header("Referer", referer)
                .build()
            val sourcesResponse = client.newCall(sourcesRequest).execute()
            val sourcesData = json.decodeFromString<AnimeiatStreamLinks>(sourcesResponse.body!!.string()).data

            // 4. Map to your app's Video model
            return@withContext sourcesData.sources.map {
                Video(it.file, "${it.label} ${it.quality}", it.file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList<Video>()
        }
    }
    // Unused functions for this source
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            // Fetch the first page of popular anime, which is perfect for a slider
            val request = Request.Builder().url("$baseUrl/anime?page=1").build()
            val response = client.newCall(request).execute()
            val responseJson = json.decodeFromString<AnimeiatPopularAnimeResponse>(response.body!!.string())

            // Map the data to SAnime objects and limit the count for the slider
            val sliderItems = responseJson.data.map {
                SAnime().apply {
                    url = it.slug
                    title = it.animeName
                    thumbnail_url = "$storageUrl/${it.posterPath}"
                    source = AnimeSource.ANIMEIAT.name
                }
            }.take(10) // Take the top 10 popular items for the slider

            sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // Return an empty list on error
        }
    }
    fun getFilterList() = AnimeFilterList(emptyList())
}