package com.faselhd.app.network.sources

import VoeExtractor
import android.content.Context
import android.util.Base64
import com.faselhd.app.models.AnimeFilter // Make sure this import is correct
import com.faselhd.app.models.AnimeFilterList
import com.faselhd.app.models.MangaPage
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

// DTOs (Data Transfer Objects) for ArabAnime API responses
@Serializable
data class PopularAnimeResponsee(
    val Shows: List<String>,
    val current_page: Int,
    val last_page: Int,
)

@Serializable
data class AnimeItemm(
    val anime_cover_image_url: String,
    val anime_id: String,
    val anime_name: String,
    val info_src: String,
)

// Corrected data classes for show details
@Serializable
data class ShowItemm(
    val EPS: List<EPSs>,
    val show: List<Showw>,
)

@Serializable
data class EPSs(
    val episode_name: String,
    val episode_number: Int,
    @SerialName("info-src")
    val infoSrc: String,
)

@Serializable
data class Showw(
    val drama_id: Int,
    val drama_name: String,
    val drama_synonyms: String?,
    val drama_score: String,
    val drama_country: String,
    val drama_status: String,
    val drama_type: String,
    val drama_release_date: String,
    val drama_description: String,
    val drama_genres: String,
    val drama_cover_image_url: String,
    val wallpapaer: String,
    val drama_slug: String,
    val show_episode_count: Int,
)

@Serializable
data class Episodee(
    val ep_info: List<EpInfo>,
)

@Serializable
data class EpInfoo(
    val stream_servers: List<String>,
)

//================================================================================
// START: FILTER CLASSES (DEFINED AT THE TOP-LEVEL OF THE FILE)
//================================================================================

private val ORDER_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("التقييم", "2"),
    Pair("اخر الانميات المضافة", "1"),
    Pair("الابجدية", "0"),
)

private val TYPE_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("الكل", ""),
    Pair("فيلم", "0"),
    Pair("انمى", "1"),
)

private val STATUS_LIST = arrayOf(
    Pair("اختر", ""),
    Pair("الكل", ""),
    Pair("مستمر", "1"),
    Pair("مكتمل", "0"),
)

// This class now correctly inherits from your project's AnimeFilter.Select
// Generic filter that maps display labels to query values
private open class QueryPartFilterr(
    displayName: String,
    val vals: Array<Pair<String, String>>,
    state: Int = 0,
) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toQueryPart() = vals[state].second
}

private class OrderFilterr : QueryPartFilterr("ترتيب", ORDER_LIST)
private class TypeFilterr : QueryPartFilterr("النوع", TYPE_LIST)
private class StatusFilterr : QueryPartFilterr("الحالة", STATUS_LIST)

//================================================================================
// END: FILTER CLASSES
//================================================================================

class ArabDramaSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json: Json by injectLazy()

    private val baseUrl = "https://www.arab-drama.me"

    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api?page=$page").build()
        val response = client.newCall(request).execute()
        val responseJson = json.decodeFromString<PopularAnimeResponsee>(response.body!!.string())

        val animeList = responseJson.Shows.mapNotNull {
            runCatching {
                val animeJson = json.decodeFromString<AnimeItemm>(it.decodeBase64())
                SAnime().apply {
                    url = animeJson.info_src
                    title = animeJson.anime_name
                    thumbnail_url = animeJson.anime_cover_image_url
                    source = AnimeSource.ARAB_DRAMA.name
                }
            }.getOrNull()
        }
        val hasNextPage = responseJson.current_page < responseJson.last_page
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): List<SAnime> = withContext(Dispatchers.IO) {
        if (page > 1) {
            return@withContext emptyList()
        }
        val request = Request.Builder().url(baseUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), baseUrl)

        val latestEpisodes = document.select("div.as-episode")
        val animeList = latestEpisodes.map {
            SAnime().apply {
                val ahref = it.selectFirst("a.as-info")!!
                title = ahref.text()
                url = ahref.attr("href").replace("watch", "show").substringBeforeLast("/")
                thumbnail_url = it.selectFirst("img")?.absUrl("src")
                source = AnimeSource.ARAB_DRAMA.name
            }
        }
        animeList
    }

    // =============================== Main Slider ===============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).build()
            val response = client.newCall(request).execute()
            val document = Jsoup.parse(response.body!!.string(), baseUrl)

            val latestEpisodes = document.select("div.as-episode")
            val animeList = latestEpisodes.map {
                SAnime().apply {
                    val ahref = it.selectFirst("a.as-info")!!
                    title = ahref.text()
                    // The URL should point to the show, not the episode
                    url = ahref.attr("href").replace("watch", "show").substringBeforeLast("/")
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                    source = AnimeSource.ARAB_DRAMA.name
                }
            }
            // Take the first 5 and shuffle them for a random-looking slider
            animeList.take(5).shuffled()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // Return an empty list on error
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val request = if (query.isNotEmpty()) {
            val body: RequestBody = FormBody.Builder().add("searchq", query).build()
            Request.Builder().url("$baseUrl/searchq").post(body).build()
        } else {
            // This code will now work because OrderFilter is a valid AnimeFilter type.
            val order = filters.find<OrderFilterr>()?.toQueryPart() ?: ""
            val type = filters.find<TypeFilterr>()?.toQueryPart() ?: ""
            val status = filters.find<StatusFilterr>()?.toQueryPart() ?: ""
            Request.Builder().url("$baseUrl/api?order=$order&type=$type&stat=$status&tags=&page=$page").build()
        }

        val response = client.newCall(request).execute()

        if (response.header("Content-Type", "")?.contains("application/json") == true) {
            val responseJson = json.decodeFromString<PopularAnimeResponsee>(response.body!!.string())
            val animeList = responseJson.Shows.mapNotNull {
                runCatching {
                    val animeJson = json.decodeFromString<AnimeItemm>(it.decodeBase64())
                    SAnime().apply {
                        url = animeJson.info_src
                        title = animeJson.anime_name
                        thumbnail_url = animeJson.anime_cover_image_url
                        source = AnimeSource.ARAB_DRAMA.name
                    }
                }.getOrNull()
            }
            val hasNextPage = responseJson.current_page < responseJson.last_page
            MangaPage(animeList, hasNextPage)
        } else {
            val document = Jsoup.parse(response.body!!.string(), baseUrl)
            val searchResult = document.select("div.show")
            val animeList = searchResult.map {
                SAnime().apply {
                    url = it.selectFirst("a")!!.attr("href")
                    title = it.selectFirst("h3")!!.text()
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                    source = AnimeSource.ARAB_DRAMA.name
                }
            }
            MangaPage(animeList, hasNextPage = false)
        }
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        val showData = document.selectFirst("div#data")!!.text().decodeBase64()
        val details = json.decodeFromString<ShowItemm>(showData).show[0]

        SAnime().apply {
            url = response.request.url.toString()
            title = details.drama_name
            status = when (details.drama_status.lowercase()) {
                "ongoing", "مستمر" -> SAnime.ONGOING
                "completed", "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = details.drama_genres
            description = details.drama_description
            thumbnail_url = "$baseUrl${details.drama_cover_image_url}"
            source = AnimeSource.ARAB_DRAMA.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        val showData = document.selectFirst("div#data")?.text()?.decodeBase64() ?: return@withContext emptyList()
        val episodesJson = json.decodeFromString<ShowItemm>(showData)

        episodesJson.EPS.map {
            SEpisode().apply {
                name = it.episode_name
                episode_number = it.episode_number.toFloat()
                url = it.infoSrc
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting fetchVideoList for URL: $episodeUrl")

        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), episodeUrl)

        val watchData = document.selectFirst("div#datawatch")?.text()?.decodeBase64() ?: return@withContext emptyList()
        val serversJson = json.decodeFromString<Episode>(watchData)

        if (serversJson.ep_info.isEmpty() || serversJson.ep_info[0].stream_servers.isEmpty()) {
            println("DEBUG: No servers found")
            return@withContext emptyList()
        }

        val videos = mutableListOf<Video>()
        val processedUrls = mutableSetOf<String>() // Prevent duplicates

        // Process all available servers
        for ((serverIndex, serverEncoded) in serversJson.ep_info[0].stream_servers.withIndex()) {
            try {
                val serverUrl = serverEncoded.decodeBase64()
                println("DEBUG: Processing server $serverIndex: $serverUrl")

                if (!serverUrl.startsWith("http") || processedUrls.contains(serverUrl)) {
                    continue
                }
                processedUrls.add(serverUrl)

                when {
                    // Arab Drama embed server (priority - contains direct MP4 links)
                    serverUrl.contains("arab-drama.me/embed") -> {
                        val embedVideos = extractFromArabDramaEmbed(serverUrl)
                        videos.addAll(embedVideos)
                        println("DEBUG: Found ${embedVideos.size} videos from Arab Drama embed")
                    }

                    // External extractors
                    "mp4upload" in serverUrl -> {
                        val extractedVideos = mp4uploadExtractor.videosFromUrl(serverUrl, prefix = "Arab Drama")
                        videos.addAll(extractedVideos)
                        println("DEBUG: Found ${extractedVideos.size} videos from Mp4upload")
                    }

                    "voe.sx" in serverUrl || "voe" in serverUrl -> {
                        val extractedVideos = voeExtractor.videosFromUrl(serverUrl)
                        videos.addAll(extractedVideos)
                        println("DEBUG: Found ${extractedVideos.size} videos from Voe")
                    }

                    "doo" in serverUrl && "/e/" in serverUrl -> {
                        val extractedVideos = doodExtractor.videosFromUrl(serverUrl, "Doodstream")
                        videos.addAll(extractedVideos)
                        println("DEBUG: Found ${extractedVideos.size} videos from Doodstream")
                    }

                    "ok.ru" in serverUrl -> {
                        val extractedVideos = okruExtractor.videosFromUrl(serverUrl, prefix = "Arab Drama:")
                        videos.addAll(extractedVideos)
                        println("DEBUG: Found ${extractedVideos.size} videos from Okru")
                    }

                    VID_BOM_DOMAINS.any(serverUrl::contains) -> {
                        val extractedVideos = vidBomExtractor.videosFromUrl(serverUrl)
                        videos.addAll(extractedVideos)
                        println("DEBUG: Found ${extractedVideos.size} videos from VidBom")
                    }

                    // Mega.nz
                    "mega.nz" in serverUrl -> {
                        // Mega links are usually not directly playable, skip for now
                        println("DEBUG: Skipping Mega.nz link: $serverUrl")
                    }

                    // Google Drive
                    "drive.google.com" in serverUrl -> {
                        val driveVideos = extractGoogleDriveVideo(serverUrl)
                        videos.addAll(driveVideos)
                        println("DEBUG: Found ${driveVideos.size} videos from Google Drive")
                    }

                    // Direct video links
                    serverUrl.endsWith(".mp4") || serverUrl.endsWith(".m3u8") -> {
                        videos.add(Video(serverUrl, "Direct Link", serverUrl))
                        println("DEBUG: Added direct video link")
                    }

                    else -> {
                        println("DEBUG: Unhandled server URL: $serverUrl")
                        // Try to extract as generic embed
                        val genericVideos = extractFromGenericEmbed(serverUrl)
                        videos.addAll(genericVideos)
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: Error processing server $serverIndex: ${e.message}")
            }
        }

        println("DEBUG: Total videos found: ${videos.size}")
        return@withContext sortVideos(videos.distinctBy { it.url })
    }

    // Extract videos from Arab Drama's own embed server
    private suspend fun extractFromArabDramaEmbed(embedUrl: String): List<Video> {
        return try {
            val response = client.newCall(Request.Builder().url(embedUrl).build()).execute()
            val embedDoc = Jsoup.parse(response.body!!.string(), embedUrl)
            val videos = mutableListOf<Video>()

            // Method 1: Look for direct source elements
            embedDoc.select("source").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    val quality = source.attr("label").takeIf { it.isNotBlank() } ?: "Unknown"
                    videos.add(Video(videoUrl, "Arab Drama: $quality", videoUrl))
                }
            }

            // Method 2: Look for video elements with src
            embedDoc.select("video").forEach { video ->
                val videoUrl = video.attr("src")
                if (videoUrl.isNotBlank()) {
                    videos.add(Video(videoUrl, "Arab Drama: Video", videoUrl))
                }
            }

            // Method 3: Look in JavaScript for video URLs
            val scripts = embedDoc.select("script")
            val videoUrlPattern = """(https?://[^\s"']*\.(?:mp4|m3u8)[^\s"']*)""".toRegex()

            scripts.forEach { script ->
                val scriptContent = script.html()
                videoUrlPattern.findAll(scriptContent).forEach { match ->
                    val videoUrl = match.value.replace("\\", "")
                    if (videoUrl.contains("drslayer.com") || videoUrl.contains("arab-drama.me")) {
                        videos.add(Video(videoUrl, "Arab Drama: Extracted", videoUrl))
                    }
                }
            }

            // Method 4: Look for specific patterns in the page
            val pageContent = embedDoc.html()
            if (pageContent.contains("sources:") || pageContent.contains("source:")) {
                // Look for Plyr.js or similar player configurations
                val sourcePattern = """sources?:\s*\[?\s*['"](https?://[^'"]*(?:mp4|m3u8))['"]\s*\]?""".toRegex()
                sourcePattern.findAll(pageContent).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    videos.add(Video(videoUrl, "Arab Drama: Player", videoUrl))
                }
            }

            println("DEBUG: Arab Drama embed extraction found ${videos.size} videos")
            videos
        } catch (e: Exception) {
            println("DEBUG: Error extracting from Arab Drama embed: ${e.message}")
            emptyList()
        }
    }

    // Extract from Google Drive preview links
    private fun extractGoogleDriveVideo(googleDriveUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        return try {
            // Extract file ID from various Google Drive URL formats
            val fileId = when {
                googleDriveUrl.contains("id=") -> {
                    val pattern = """id=([^&]+)""".toRegex()
                    pattern.find(googleDriveUrl)?.groupValues?.get(1)
                }
                googleDriveUrl.contains("/file/d/") -> {
                    val pattern = """/file/d/([^/]+)/""".toRegex()
                    pattern.find(googleDriveUrl)?.groupValues?.get(1)
                }
                googleDriveUrl.contains("uc?id=") -> {
                    val pattern = """uc\?id=([^&]+)""".toRegex()
                    pattern.find(googleDriveUrl)?.groupValues?.get(1)
                }
                else -> null
            }

            fileId?.let {
                // Create multiple video options with different quality labels
                val directUrl = "https://drive.google.com/uc?export=download&id=$it"

                // Add different quality options (even if they're the same URL, different labels help)
                videos.add(Video(directUrl, "Google Drive: High Quality", directUrl))
                videos.add(Video(directUrl, "Google Drive: Medium Quality", directUrl))
                videos.add(Video(directUrl, "Google Drive: Standard", directUrl))

                println("DEBUG: Found Google Drive video with ID: $it")
            }

            videos

        } catch (e: Exception) {
            println("DEBUG: Error processing Google Drive URL: ${e.message}")
            emptyList()
        }
    }

    // Generic embed extractor for unknown sources
    private suspend fun extractFromGenericEmbed(embedUrl: String): List<Video> {
        return try {
            val response = client.newCall(Request.Builder().url(embedUrl).build()).execute()
            val doc = Jsoup.parse(response.body!!.string(), embedUrl)
            val videos = mutableListOf<Video>()

            // Look for common video elements
            doc.select("source, video[src]").forEach { element ->
                val videoUrl = element.attr("src")
                if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                    val quality = element.attr("label").takeIf { it.isNotBlank() } ?: "Generic"
                    videos.add(Video(videoUrl, quality, videoUrl))
                }
            }

            // Look for iframe sources
            doc.select("iframe").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank() && (iframeSrc.contains(".mp4") || iframeSrc.contains(".m3u8"))) {
                    videos.add(Video(iframeSrc, "Generic iframe", iframeSrc))
                }
            }

            videos
        } catch (e: Exception) {
            println("DEBUG: Error in generic extraction: ${e.message}")
            emptyList()
        }
    }

    private fun sortVideos(videos: List<Video>): List<Video> {
        val quality = getPreferredQuality()
        return videos.sortedWith(
            compareByDescending { it.quality.contains(quality) }
        )
    }


    private fun extractVideosFromEmbedPage(document: Document, serverIndex: Int, videos: MutableList<Video>) {
        // Method 1: Extract from <source> elements (most common)
        document.select("source").forEach { source ->
            val videoUrl = source.attr("src")
            val label = source.attr("label")
            val res = source.attr("res")

            if (videoUrl.isNotBlank() && (videoUrl.endsWith(".m3u8") || videoUrl.endsWith(".mp4"))) {
                val quality = when {
                    label.isNotBlank() -> label
                    res.isNotBlank() -> "${res}p"
                    else -> "Unknown"
                }
                val videoName = "Server $serverIndex: $quality"
                val video = Video(videoUrl, videoName, videoUrl)

                if (!videos.any { it.url == videoUrl }) {
                    videos.add(video)
                    println("DEBUG: Found video from source: $videoName - $videoUrl")
                }
            }
        }

        // Method 2: Extract from <video> element
        document.select("video").forEach { videoElement ->
            val videoUrl = videoElement.attr("src")
            if (videoUrl.isNotBlank() && (videoUrl.endsWith(".m3u8") || videoUrl.endsWith(".mp4"))) {
                val videoName = "Server $serverIndex: Direct"
                val video = Video(videoUrl, videoName, videoUrl)

                if (!videos.any { it.url == videoUrl }) {
                    videos.add(video)
                    println("DEBUG: Found video from video element: $videoName - $videoUrl")
                }
            }
        }

        // Method 3: Extract from JavaScript variables (common in video players)
        document.select("script:not([src])").forEach { script ->
            val scriptContent = script.html()
            if (scriptContent.contains("mp4") || scriptContent.contains("m3u8")) {
                // Look for video URLs in JavaScript - more specific patterns
                val patterns = listOf(
                    """src["']?\s*[=:]\s*["']([^"']*\.(mp4|m3u8)[^"']*)["']""",
                    """file["']?\s*[=:]\s*["']([^"']*\.(mp4|m3u8)[^"']*)["']""",
                    """video["']?\s*[=:]\s*["']([^"']*\.(mp4|m3u8)[^"']*)["']""",
                    """(https?://[^\s"']*\.(mp4|m3u8)[^\s"']*)"""
                )

                patterns.forEach { pattern ->
                    val urlPattern = pattern.toRegex(RegexOption.IGNORE_CASE)
                    val matches = urlPattern.findAll(scriptContent)

                    matches.forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && (videoUrl.startsWith("http"))) {
                            val videoName = "Server $serverIndex: JS"
                            val video = Video(videoUrl, videoName, videoUrl)

                            if (!videos.any { it.url == videoUrl }) {
                                videos.add(video)
                                println("DEBUG: Found video from script: $videoName - $videoUrl")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractVideosFromMainPage(document: Document, videos: MutableList<Video>) {
        // Also check the main page for video elements
        document.select("source").forEach { source ->
            val videoUrl = source.attr("src")
            val label = source.attr("label")

            if (videoUrl.isNotBlank() && (videoUrl.endsWith(".m3u8") || videoUrl.endsWith(".mp4"))) {
                val quality = if (label.isNotBlank()) label else "Main Page"
                val video = Video(videoUrl, quality, videoUrl)

                if (!videos.any { it.url == videoUrl }) {
                    videos.add(video)
                    println("DEBUG: Found video on main page: $quality - $videoUrl")
                }
            }
        }

        document.select("video").forEach { videoElement ->
            val videoUrl = videoElement.attr("src")
            if (videoUrl.isNotBlank() && (videoUrl.endsWith(".m3u8") || videoUrl.endsWith(".mp4"))) {
                val video = Video(videoUrl, "Main Page: Direct", videoUrl)

                if (!videos.any { it.url == videoUrl }) {
                    videos.add(video)
                    println("DEBUG: Found video element on main page: $videoUrl")
                }
            }
        }
    }
    private fun extractVideosFromJsonData(jsonData: String, videos: MutableList<Video>) {
        try {
            // Try to find video URLs directly in the JSON data
            val urlPattern = """(https?://[^\s"']*\.(mp4|m3u8)[^\s"']*)""".toRegex()
            val matches = urlPattern.findAll(jsonData)

            matches.forEach { match ->
                val videoUrl = match.value
                if (videoUrl.isNotBlank()) {
                    val video = Video(videoUrl, "JSON: Direct", videoUrl)
                    if (!videos.any { it.url == videoUrl }) {
                        videos.add(video)
                        println("DEBUG: Found video in JSON data: $videoUrl")
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Error extracting from JSON data: ${e.message}")
        }
    }

    // ============================== Filters ===============================
    fun getFilterList() = AnimeFilterList(
        listOf(
            AnimeFilter.Header("فلترة الموقع (يعمل فقط عند ترك البحث فارغ)"),
            OrderFilterr(),
            TypeFilterr(),
            StatusFilterr(),
        )
    )

    // =============================== Preferences ===============================
    private fun getPreferences() = context.getSharedPreferences("arabdrama_prefs", Context.MODE_PRIVATE)

    fun getPreferredQuality(): String {
        return getPreferences().getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
    }

    private fun String.decodeBase64() = String(Base64.decode(this, Base64.DEFAULT))

    companion object {
        private const val PREF_QUALITY_KEY = "arabdrama_preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")
    }
}

// Extension function to find filters by type
//inline fun <reified T> AnimeFilterList.find(): T? {
//    return this.filterIsInstance<T>().firstOrNull()
//}