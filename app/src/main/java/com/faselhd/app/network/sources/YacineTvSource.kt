package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
import android.util.Log
import com.faselhd.app.models.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_8

class YacineTvSource(private val context: Context) {
    companion object {
        const val NAME = "Yacine TV"
        private const val API_URL = "http://ver3.yacinelive.com"
        private const val KEY = "c!xZj+N9&G@Ev@vw"

        // Custom URL schemes to pass IDs between functions
        private const val CATEGORY_URL_PREFIX = "yacine_category::"
        private const val CHANNEL_URL_PREFIX = "yacine_channel::"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Decrypts the API response using a repeating XOR cipher.
     * This is a direct Kotlin port of the Python decryption function.
     */
    private fun decrypt(encryptedBase64: String, key: String): String {
        // Step 1: Base64 decode the input string.
        val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val decodedString = String(decodedBytes, UTF_8)

        // Step 2: Apply the XOR cipher.
        val result = StringBuilder()
        for (i in decodedString.indices) {
            val decryptedChar = decodedString[i].code xor key[i % key.length].code
            result.append(decryptedChar.toChar())
        }
        return result.toString()
    }

    /**
     * Performs a request to the YacineTV API, handles decryption, and returns the JSON string.
     */
    private fun makeApiRequest(path: String): String? {
        try {
            val request = Request.Builder().url(API_URL + path).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("YacineTvSource", "API request failed for path $path: ${response.code}")
                return null
            }

            // The timestamp from the 't' header is crucial for the decryption key.
            val timestamp = response.header("t") ?: (System.currentTimeMillis() / 1000).toString()
            val responseBody = response.body?.string() ?: return null

            // The final key is the static key plus the timestamp.
            val decryptionKey = KEY + timestamp

            return decrypt(responseBody, decryptionKey)
        } catch (e: Exception) {
            Log.e("YacineTvSource", "Exception in makeApiRequest for path $path", e)
            return null
        }
    }

    /**
     * Fetches the list of categories and presents them as "shows".
     */
    suspend fun fetchPopularSeries(page: Int): MangaPage {
        if (page > 1) return MangaPage(emptyList(), false) // API is not paginated

        val jsonString = makeApiRequest("/api/categories") ?: return MangaPage(emptyList(), false)

        val categoriesArray = JSONArray(jsonString)
        val animeList = mutableListOf<SAnime>()

        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            animeList.add(
                SAnime().apply {
                    title = category.getString("name")
                    // We create a custom URL to hold the category ID for the next step.
                    url = "$CATEGORY_URL_PREFIX${category.getInt("id")}"
                    thumbnail_url = category.getString("image_url")
                }
            )
        }
        return MangaPage(animeList, hasNextPage = false)
    }

    /**
     * Fetches the channels for a given category and presents them as "episodes".
     */
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> {
        val categoryId = animeUrl.removePrefix(CATEGORY_URL_PREFIX)
        val jsonString = makeApiRequest("/api/categories/$categoryId/channels") ?: return emptyList()

        val responseJson = JSONObject(jsonString)
        val channelsArray = responseJson.getJSONArray("channels")
        val episodeList = mutableListOf<SEpisode>()

        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            episodeList.add(
                SEpisode().apply {
                    name = channel.getString("name")
                    // Create a custom URL to hold the channel ID for fetching the video links.
                    url = "$CHANNEL_URL_PREFIX${channel.getInt("id")}"
                    episode_number = (i + 1).toFloat() // Use index as episode number
                }
            )
        }
        return episodeList
    }

    /**
     * Fetches the final stream links for a given channel.
     */
    suspend fun fetchVideoList(episodeUrl: String): List<Video> {
        val channelId = episodeUrl.removePrefix(CHANNEL_URL_PREFIX)
        val jsonString = makeApiRequest("/api/channel/$channelId") ?: return emptyList()

        val channelJson = JSONObject(jsonString)
        val videoList = mutableListOf<Video>()

        // The API might provide multiple direct links (e.g., for different qualities)
        val directLinks = channelJson.optJSONArray("direct_links")
        if (directLinks != null) {
            for (i in 0 until directLinks.length()) {
                val link = directLinks.getJSONObject(i)
                videoList.add(
                    Video(
                        url = link.getString("url"),
                        quality = link.getString("quality"),
                        videoUrl = link.getString("url")
                        // Add headers if needed, e.g., user-agent
                        // headers = mapOf("User-Agent" to "...")
                    )
                )
            }
        }
        // As a fallback, check for a single embed URL
        else if (channelJson.has("embed_url")) {
            videoList.add(
                Video(
                    url = channelJson.getString("embed_url"),
                    quality = "HD", // Default quality
                    videoUrl = channelJson.getString("embed_url")
                )
            )
        }

        return videoList
    }

    // These functions are part of the standard interface but are not applicable to YacineTV.
    suspend fun fetchLatestUpdates(page: Int): MangaPage = MangaPage(emptyList(), false)
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = SAnime() // Details are simple, no need for a separate call
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = MangaPage(emptyList(), false)
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}