package recloudstream // Use your actual package name

import android.content.Context
import com.faselhd.app.models.* // Assuming this is where your SAnime, MangaPage, etc. are
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.net.URLEncoder

// --- Data Classes for API Parsing ---

// For Search and Popular lists
data class DailymotionSearchResponse(
    @SerializedName("list") val list: List<DailymotionVideoItem>,
    @SerializedName("has_more") val hasMore: Boolean
)

data class DailymotionVideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("thumbnail_360_url") val thumbnail360Url: String
)

// For Video Details page
data class DailymotionDetailResponse(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnail_720_url") val thumbnail720Url: String
)

// NEW: For the video metadata endpoint used to get stream links
data class DailymotionMetadataResponse(
    @SerializedName("qualities") val qualities: Map<String, List<DailymotionStream>>?
)

data class DailymotionStream(
    @SerializedName("type") val type: String,
    @SerializedName("url") val url: String
)


class DailymotionSource(private val context: Context) {
    companion object {
        const val name = "Dailymotion"
        const val BASE_URL = "https://api.dailymotion.com"
        const val lang = "en"
        const val supportsLatest = true
    }

    private val gson = Gson()

    // --- OKHTTP CLIENT SETUP ---
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(request)
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
            .build()
    }

    // ============================== Popular ==============================
    suspend fun fetchPopular(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        val data = gson.fromJson(responseBody, DailymotionSearchResponse::class.java)
        val animeList = data.list.map { it.toSAnime() }
        MangaPage(animeList, data.hasMore)
    }


    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String): MangaPage = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&search=$encodedQuery"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        val data = gson.fromJson(responseBody, DailymotionSearchResponse::class.java)
        val animeList = data.list.map { it.toSAnime() }
        MangaPage(animeList, data.hasMore)
    }

    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val videoId = Regex("""dailymotion\.com/video/(\w+)""").find(animeUrl)?.groupValues?.get(1)
            ?: throw Exception("Invalid Dailymotion URL")

        val url = "$BASE_URL/video/$videoId?fields=id,title,description,thumbnail_720_url"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        val data = gson.fromJson(responseBody, DailymotionDetailResponse::class.java)
        return@withContext data.toSAnime()
    }

    // ============================== Episodes ==============================
    // For Dailymotion, a "video" is treated as a single episode.
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val videoId = Regex("""dailymotion\.com/video/(\w+)""").find(animeUrl)?.groupValues?.get(1)
            ?: return@withContext emptyList()

        return@withContext listOf(
            SEpisode().apply {
                url = videoId // Pass the ID to fetchVideoList
                name = "Watch Video"
                episode_number = 1f
            }
        )
    }

    // ============================== Video Links (CORRECTED) ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        // In this source, `episodeUrl` is the video ID passed from fetchEpisodeList
        val videoId = episodeUrl
        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val videos = mutableListOf<Video>()

        try {
            val request = Request.Builder()
                .url(metadataUrl)
                // Dailymotion doesn't seem to require a referer for this endpoint, but it's good practice
                .header("Referer", "https://www.dailymotion.com/")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val metadata = gson.fromJson(responseBody, DailymotionMetadataResponse::class.java)

                // Iterate through all available qualities (e.g., "240", "360", "auto")
                metadata.qualities?.forEach { (quality, streams) ->
                    // Find the HLS (m3u8) stream for the current quality
                    val hlsStream = streams.firstOrNull { it.type == "application/x-mpegURL" }
                    if (hlsStream != null) {
                        videos.add(
                            Video(
                                url = hlsStream.url,
                                // Use the quality key ("240", "360", etc.) as the label
                                quality = if (quality == "auto") "Auto" else "${quality}p",
                                videoUrl = hlsStream.url,
                                headers = mapOf("Referer" to "https://www.dailymotion.com/")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Log the error if something goes wrong
            e.printStackTrace()
        }

        // Return a distinct list sorted by quality (highest first)
        return@withContext videos
            .distinctBy { it.quality }
            .sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
    }


    // --- Helper Functions to map API responses to SAnime ---
    private fun DailymotionVideoItem.toSAnime(): SAnime {
        return SAnime().apply {
            title = this@toSAnime.title
            url = "https://www.dailymotion.com/video/${this@toSAnime.id}"
            thumbnail_url = this@toSAnime.thumbnail360Url
        }
    }

    private fun DailymotionDetailResponse.toSAnime(): SAnime {
        return SAnime().apply {
            title = this@toSAnime.title
            url = "https://www.dailymotion.com/video/${this@toSAnime.id}"
            thumbnail_url = this@toSAnime.thumbnail720Url
            description = this@toSAnime.description ?: ""
            status = SAnime.COMPLETED // Single videos are always "completed"
        }
    }
}