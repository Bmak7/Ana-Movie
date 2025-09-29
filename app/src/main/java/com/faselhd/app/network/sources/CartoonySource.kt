package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import com.faselhd.app.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CartoonySource(private val context: Context) {
    companion object {
        const val NAME = "كرتوني"
        const val BASE_URL = "https://cartoony.net"
        private const val API_URL = "https://api.cartoony.net/v2"
        const val LANG = "ar"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun parseShowJson(json: JSONObject): SAnime {
        val showType = if (json.optString("type", "show") == "movie") "فيلم" else "مسلسل"
        val year = json.optString("year", null)
        val details = listOfNotNull(showType, year).joinToString(" • ")

        return SAnime().apply {
            url = "$BASE_URL/watch/${json.getString("id")}"
            title = json.getString("title")
            thumbnail_url = json.optString("poster_url", null)
            description = json.optString("story", null)
        }
    }

    // ============================== Popular ==============================
    suspend fun fetchPopularSeries(page: Int): MangaPage {
        if (page > 1) return MangaPage(emptyList(), false)
        return fetchHomePageSections()
    }

    // ============================== Latest Updates ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage {
        if (page > 1) return MangaPage(emptyList(), false)
        return fetchHomePageSections(sectionToGet = "newly_added_shows")
    }

    private suspend fun fetchHomePageSections(sectionToGet: String = "most_watched_shows"): MangaPage =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$API_URL/home").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext MangaPage(emptyList(), false)

                    val jsonResponse = JSONObject(response.body!!.string())
                    val sections = jsonResponse.getJSONArray("sections")

                    var animeList = emptyList<SAnime>()
                    for (i in 0 until sections.length()) {
                        val section = sections.getJSONObject(i)
                        if (section.getString("id") == sectionToGet) {
                            val showsArray = section.getJSONArray("shows")
                            animeList = List(showsArray.length()) { j ->
                                parseShowJson(showsArray.getJSONObject(j))
                            }
                            break
                        }
                    }
                    return@withContext MangaPage(animeList, false)
                }
            } catch (e: Exception) {
                Log.e("CartoonySource", "Failed to fetch home page", e)
                return@withContext MangaPage(emptyList(), false)
            }
        }

    // ============================== Details & Episodes ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime =
        withContext(Dispatchers.IO) {
            val showId = animeUrl.substringAfterLast('/')
            val request = Request.Builder().url("$API_URL/shows/$showId").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext SAnime()

                val json = JSONObject(response.body!!.string())
                return@withContext parseShowJson(json)
            }
        }

    private fun parseEpisodeList(episodesJson: JSONArray?): List<SEpisode> {
        if (episodesJson == null) return emptyList()
        return List(episodesJson.length()) { i ->
            val episodeJson = episodesJson.getJSONObject(i)
            SEpisode().apply {
                url = "$BASE_URL/watch/${episodeJson.getString("show_id")}/${episodeJson.getString("id")}"
                name = episodeJson.getString("title")
                episode_number = episodeJson.optInt("episode_number", i + 1).toFloat()
            }
        }.sortedBy { it.episode_number }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> =
        withContext(Dispatchers.IO) {
            val showId = animeUrl.substringAfterLast('/')
            val request = Request.Builder().url("$API_URL/shows/$showId").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body!!.string())
                return@withContext parseEpisodeList(json.optJSONArray("episodes"))
            }
        }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> =
        withContext(Dispatchers.IO) {
            try {
                val showId = episodeUrl.split("/").getOrNull(4) ?: return@withContext emptyList()
                val episodeId = episodeUrl.split("/").getOrNull(5) ?: showId

                val request = Request.Builder()
                    .url("$API_URL/episodes/$episodeId?show_id=$showId")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val json = JSONObject(response.body!!.string())

                    // Sometimes multiple sources might exist
                    val sources = mutableListOf<Video>()
                    if (json.has("video_url")) {
                        sources += Video(
                            url = json.getString("video_url"),
                            quality = "Default",
                            videoUrl = json.getString("video_url"),
                            headers = mapOf("Referer" to BASE_URL)
                        )
                    }
                    if (json.has("sources")) {
                        val arr = json.getJSONArray("sources")
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            sources += Video(
                                url = obj.getString("file"),
                                quality = obj.optString("label", "Unknown"),
                                videoUrl = obj.getString("file"),
                                headers = mapOf("Referer" to BASE_URL)
                            )
                        }
                    }
                    return@withContext sources
                }
            } catch (e: Exception) {
                Log.e("CartoonySource", "Failed to fetch video list", e)
                return@withContext emptyList()
            }
        }

    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext MangaPage(emptyList(), false)
            try {
                val request = Request.Builder().url("$API_URL/search?q=$query").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext MangaPage(emptyList(), false)

                    val showsArray = JSONArray(response.body!!.string())
                    val animeList = List(showsArray.length()) { i ->
                        parseShowJson(showsArray.getJSONObject(i))
                    }
                    return@withContext MangaPage(animeList, false)
                }
            } catch (e: Exception) {
                Log.e("CartoonySource", "Search failed", e)
                return@withContext MangaPage(emptyList(), false)
            }
        }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}
