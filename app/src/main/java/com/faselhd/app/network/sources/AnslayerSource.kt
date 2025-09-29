package com.anslayer.app.network.sources

import android.content.Context
import android.util.Base64 // Import Base64 for encoding
import android.util.Log
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

//region DTOs for Anslayer API (Should be correct now)
@Serializable
data class AnslayerApiResponse<T>(
    val data: T
)

@Serializable
data class AnslayerAnimeInfo(
    @SerialName("anime_id") val id: Long,
    @SerialName("anime_name") val name: String,
    @SerialName("anime_cover_image_url") val coverUrl: String,
)

@Serializable
data class AnslayerAnimeDetails(
    @SerialName("anime_id") val id: Long,
    @SerialName("anime_name") val name: String,
    @SerialName("anime_description") val description: String,
    @SerialName("anime_cover_image_url") val coverUrl: String,
    @SerialName("anime_status") val status: String,
    @SerialName("anime_genres") val genres: String? = null,
)

@Serializable
data class AnslayerEpisode(
    @SerialName("episode_id") val id: Long,
    @SerialName("episode_number") val number: Int,
    @SerialName("episode_name") val name: String,
)

@Serializable
data class AnslayerVideoSource(
    val file: String,
    val label: String,
)
//endregion

class AnslayerSource(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://anslayer.com/anime/public"

    // =================================================================================
    //  FINAL STEP: Find these two values in the app's source code using `grep`
    //  for "client_id" and "client_secret" and paste them here.
    // =================================================================================
    private val CLIENT_ID = "PASTE_YOUR_CAPTURED_CLIENT_ID_HERE"
    private val CLIENT_SECRET = "PASTE_YOUR_CAPTURED_CLIENT_SECRET_HERE"

    private val client: OkHttpClient by lazy {
        // Create the Basic Auth credential string
        val basicAuth = "Basic " + Base64.encodeToString(
            "$CLIENT_ID:$CLIENT_SECRET".toByteArray(),
            Base64.NO_WRAP
        )

        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequestBuilder = originalRequest.newBuilder()
                    .header("User-Agent", "okhttp/4.9.1")
                    // This is the correct authentication method for a Client ID and Secret
                    .header("Authorization", basicAuth)

                chain.proceed(newRequestBuilder.build())
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // =============================== Latest / Search ===============================
    private suspend fun fetchAnimeList(page: Int, query: String = ""): MangaPage = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/animes/get-published-animes".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext MangaPage(emptyList(), false)

        urlBuilder.addQueryParameter("page", page.toString())
        urlBuilder.addQueryParameter("search_text", query)
        urlBuilder.addQueryParameter("sorted_by", "anime_rating_desc")

        val request = Request.Builder().url(urlBuilder.build()).get().build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("AnslayerSource", "fetchAnimeList failed with code: ${response.code} for URL: ${request.url}")
                return@withContext MangaPage(emptyList(), false)
            }

            val responseBody = response.body!!.string()
            val responseJson = json.decodeFromString<AnslayerApiResponse<List<AnslayerAnimeInfo>>>(responseBody)

            val animeList = responseJson.data.map {
                SAnime().apply {
                    this.url = it.id.toString()
                    this.title = it.name
                    this.thumbnail_url = it.coverUrl
                    this.source = AnimeSource.ANSLAYER.name
                }
            }
            return@withContext MangaPage(animeList, animeList.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = fetchAnimeList(page)
    suspend fun fetchSearchAnime(page: Int, query: String): MangaPage = fetchAnimeList(page, query)

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeId: String): SAnime = withContext(Dispatchers.IO) {
        val requestUrl = "$baseUrl/anime/get-anime-details"
        val formBody = FormBody.Builder().add("anime_id", animeId).build()
        val request = Request.Builder().url(requestUrl).post(formBody).build()

        try {
            val response = client.newCall(request).execute()
            val details = json.decodeFromString<AnslayerApiResponse<AnslayerAnimeDetails>>(response.body!!.string()).data
            return@withContext SAnime().apply {
                url = details.id.toString()
                title = details.name
                status = if (details.status == "مستمر") SAnime.ONGOING else SAnime.COMPLETED
                genre = details.genres
                description = details.description
                thumbnail_url = details.coverUrl
                source = AnimeSource.ANSLAYER.name
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext SAnime()
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeId: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val requestUrl = "$baseUrl/episodes/get-episodes-new"
        val formBody = FormBody.Builder().add("anime_id", animeId).build()
        val request = Request.Builder().url(requestUrl).post(formBody).build()

        try {
            val response = client.newCall(request).execute()
            val responseJson = json.decodeFromString<AnslayerApiResponse<List<AnslayerEpisode>>>(response.body!!.string())
            return@withContext responseJson.data.map {
                SEpisode().apply {
                    name = it.name
                    url = it.id.toString()
                    episode_number = it.number.toFloat()
                }
            }.sortedBy { it.episode_number }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeId: String): List<Video> = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/google.php".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext emptyList<Video>()
        urlBuilder.addQueryParameter("episode_id", episodeId)
        val request = Request.Builder().url(urlBuilder.build()).get().build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body!!.string()
            val sourcesData = json.decodeFromString<AnslayerApiResponse<List<AnslayerVideoSource>>>(responseBody).data
            return@withContext sourcesData.map { Video(it.file, it.label, it.file) }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList<Video>()
        }
    }
}