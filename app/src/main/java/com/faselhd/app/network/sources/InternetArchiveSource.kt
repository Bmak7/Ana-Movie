package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.helper.HttpConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class InternetArchiveSource(private val context: Context) {

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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val mapper by lazy {
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private val baseUrl = "https://archive.org"

    // ... (fetchPopularSeries and fetchSearchAnime remain the same) ...
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=50&page=$page&output=json"
            val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
            val result = mapper.readValue<InternetArchiveSearchResult>(responseText)
            val animeList = result.response.docs.map { searchEntryToSAnime(it) }
            MangaPage(animeList, hasNextPage = animeList.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/advancedsearch.php?q=$query+mediatype:(movies OR audio)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=50&output=json"
            val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
            val result = mapper.readValue<InternetArchiveSearchResult>(responseText)
            val animeList = result.response.docs.map { searchEntryToSAnime(it) }
            MangaPage(animeList, hasNextPage = false)
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val identifier = animeUrl.substringAfterLast("/")
        val url = "$baseUrl/metadata/$identifier"
        val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
        val result = mapper.readValue<MetadataResult>(responseText)
        val metadata = result.metadata

        SAnime().apply {
            this.url = animeUrl
            title = metadata.title ?: identifier
            thumbnail_url = "$baseUrl/services/img/$identifier"
            description = metadata.description?.let { Jsoup.parse(it).text() }
            genre = (metadata.subject?.getOrNull(0)?.split(";") ?: metadata.subject)?.joinToString(", ")

            // ========= MODIFICATION: Set status based on mediatype =========
            status = if (metadata.mediatype == "audio") SAnime.AUDIOBOOK else SAnime.COMPLETED
            source = AnimeSource.INTERNET_ARCHIVE.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val identifier = animeUrl.substringAfterLast("/")
        val url = "$baseUrl/metadata/$identifier"
        val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
        val result = mapper.readValue<MetadataResult>(responseText)

        // ========= MODIFICATION START: Handle both Audio and Video =========
        val playableFiles = if (result.metadata.mediatype == "audio") {
            // Filter for common audio formats
            result.files.filter {
                it.format.contains("MP3", ignoreCase = true) ||
                        it.format.contains("Flac", ignoreCase = true) ||
                        it.format.contains("Ogg Vorbis", ignoreCase = true)
            }
        } else {
            // Filter for common video formats
            result.files.filter {
                (it.format.contains("MPEG", true) || it.format.startsWith("H.264", true)) &&
                        it.lengthInSeconds >= 10.0 // Filter out short clips
            }
        }

        if (playableFiles.isEmpty()) return@withContext emptyList()

        val animeNameAsSeason = result.metadata.title ?: identifier

        // If it's a single file (common for movies or single-track audiobooks), create one episode
        if (playableFiles.size == 1) {
            val file = playableFiles.first()
            val videoLinkData = listOf(
                VideoLinkData("https://${result.server}${result.dir}/${file.name}", file.format)
            )
            return@withContext listOf(SEpisode().apply {
                name = "$animeNameAsSeason : Play" // Simple name for single items
                episode_number = 1f
                this.url = mapper.writeValueAsString(videoLinkData)
            })
        }

        // If there are multiple files, treat them as a playlist or album (episodes)
        return@withContext playableFiles.mapIndexed { index, file ->
            val cleanName = file.title ?: file.name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
            val videoLinkData = listOf(
                VideoLinkData("https://${result.server}${result.dir}/${file.name}", file.format)
            )
            SEpisode().apply {
                name = "$animeNameAsSeason : $cleanName"
                episode_number = (index + 1).toFloat()
                this.url = mapper.writeValueAsString(videoLinkData)
            }
        }
        // ========= MODIFICATION END =========
    }

    // ... (fetchVideoList remains the same, as it just deserializes the URL) ...
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val videoLinks = mapper.readValue<List<VideoLinkData>>(episodeUrl)
            return@withContext videoLinks.map {
                Video(
                    url = it.url,
                    quality = it.quality, // For audio, this will be "MP3", "Flac", etc.
                    videoUrl = it.url
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun searchEntryToSAnime(entry: SearchEntry): SAnime {
        return SAnime().apply {
            title = entry.title ?: entry.identifier
            url = "$baseUrl/details/${entry.identifier}"
            thumbnail_url = "$baseUrl/services/img/${entry.identifier}"
            source = AnimeSource.INTERNET_ARCHIVE.name
        }
    }

    // ... (stubs for fetchMainSlider and getFilterList remain the same) ...
    // ============================== Main Slider ===============================
    /**
     * Fetches the top 10 most downloaded movies to be used as featured content.
     */
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=mediatype&sort[]=downloads%20desc&rows=10&output=json")
                .build()
            val response = client.newCall(request).execute()
            return@withContext mainSliderParse(response)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        if (!response.isSuccessful) return emptyList()
        val responseText = response.body!!.string()
        val result = mapper.readValue<InternetArchiveSearchResult>(responseText)
        return result.response.docs.map { searchEntryToSAnime(it) }
    }

    fun getFilterList() = AnimeFilterList(emptyList())
}

//package com.faselhd.app.network.sources
//import android.content.Context
//import com.faselhd.app.models.*
//import com.faselhd.app.network.AnimeSource
//import com.fasterxml.jackson.databind.DeserializationFeature
//import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
//import com.fasterxml.jackson.module.kotlin.readValue
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import org.jsoup.Jsoup
//import java.util.concurrent.TimeUnit
//import java.util.regex.Pattern
//import kotlin.math.roundToInt
//class InternetArchiveSource(private val context: Context) {
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }
//
//    private val mapper by lazy {
//        jacksonObjectMapper().apply {
//            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
//            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
//        }
//    }
//
//    private val baseUrl = "https://archive.org"
//
//    // Fetches featured content for the main page
//    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=50&page=$page&output=json"
//            val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
//            val result = mapper.readValue<InternetArchiveSearchResult>(responseText)
//            val animeList = result.response.docs.map { searchEntryToSAnime(it) }
//            // API does not provide a clear "hasNextPage" boolean, so we assume true if we got results.
//            MangaPage(animeList, hasNextPage = animeList.isNotEmpty())
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // Searches for content (movies and audio)
//    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/advancedsearch.php?q=$query+mediatype:(movies OR audio)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=50&output=json"
//            val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
//            val result = mapper.readValue<InternetArchiveSearchResult>(responseText)
//            val animeList = result.response.docs.map { searchEntryToSAnime(it) }
//            MangaPage(animeList, hasNextPage = false) // Search is not paginated
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // Fetches detailed information for a specific item
//    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
//        val identifier = animeUrl.substringAfterLast("/")
//        val url = "$baseUrl/metadata/$identifier"
//        val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
//        val result = mapper.readValue<MetadataResult>(responseText)
//        val metadata = result.metadata
//
//        SAnime().apply {
//            this.url = animeUrl
//            title = metadata.title ?: identifier
//            thumbnail_url = "$baseUrl/services/img/$identifier"
//            description = metadata.description?.let { Jsoup.parse(it).text() }
//            genre = (metadata.subject?.getOrNull(0)?.split(";") ?: metadata.subject)?.joinToString(", ")
//            status = SAnime.COMPLETED // Most archive content is complete
//            source = AnimeSource.INTERNET_ARCHIVE.name // Assuming you add this to your enum
//        }
//    }
//
//    // Fetches the list of playable files, treating them as episodes
//    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        val identifier = animeUrl.substringAfterLast("/")
//        val url = "$baseUrl/metadata/$identifier"
//        val responseText = client.newCall(Request.Builder().url(url).build()).execute().body!!.string()
//        val result = mapper.readValue<MetadataResult>(responseText)
//
//        val videoFiles = result.files.asSequence()
//            .filter { it.format.contains("MPEG", true) || it.format.startsWith("H.264", true) }
//            .filter { it.lengthInSeconds >= 10.0 } // Filter out short clips
//            .toList()
//
//        if (videoFiles.isEmpty()) return@withContext emptyList()
//
//        // If there's only one video file, it's a movie
//        if (videoFiles.size == 1) {
//            return@withContext listOf(SEpisode().apply {
//                name = result.metadata.title ?: identifier
//                episode_number = 1f
//                // We'll pass all video links in the URL, encoded as JSON
//                this.url = mapper.writeValueAsString(videoFiles.map { VideoLinkData("https://${result.server}${result.dir}/${it.name}", it.format) })
//            })
//        }
//
//        // It's a playlist (acting as a series)
//        val animeNameAsSeason = result.metadata.title ?: identifier
//        return@withContext videoFiles.mapIndexed { index, file ->
//            SEpisode().apply {
//                val cleanName = file.title ?: file.name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
//                name = "$animeNameAsSeason : $cleanName"
//                episode_number = (index + 1).toFloat()
//                this.url = mapper.writeValueAsString(listOf(VideoLinkData("https://${result.server}${result.dir}/${file.name}", file.format)))
//            }
//        }
//    }
//
//    // Extracts the direct video link from the episode data
//    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
//        try {
//            // The episodeUrl is actually a JSON string of VideoLinkData
//            val videoLinks = mapper.readValue<List<VideoLinkData>>(episodeUrl)
//            return@withContext videoLinks.map {
//                Video(
//                    url = it.url,
//                    quality = it.quality,
//                    videoUrl = it.url
//                )
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            return@withContext emptyList()
//        }
//    }
//
//    // Helper function to convert a search entry to your SAnime model
//    private fun searchEntryToSAnime(entry: SearchEntry): SAnime {
//        return SAnime().apply {
//            title = entry.title ?: entry.identifier
//            url = "$baseUrl/details/${entry.identifier}"
//            thumbnail_url = "$baseUrl/services/img/${entry.identifier}"
//            source = AnimeSource.INTERNET_ARCHIVE.name
//        }
//    }
//
//    // Stubs for features not supported by this source
//    suspend fun fetchMainSlider(): List<SAnime> = emptyList()
//    fun getFilterList() = AnimeFilterList(emptyList())
//}