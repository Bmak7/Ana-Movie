package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit


class Asia2TvSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val json: Json by injectLazy()

    private val baseUrl = "https://ww1.asia2tv.pw"

    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidbomExtractor by lazy { VidBomExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    // Initialize all LuluStream extractors
    private val luluVdoExtractor by lazy { LuluVdoExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }
    private val luluStream2Extractor by lazy { LuluStream2Extractor(client) }

    //endregion

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/category/asian-drama/page/$page/"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.postmovie-photo a[title]").map {
            SAnime().apply {
                this.url = it.attr("href")
                this.title = it.attr("title")
                // Thumbnail is not easily accessible on this page, load it in details view
                this.thumbnail_url = ""
                this.source = AnimeSource.ASIA2TV.name
            }
        }

        val hasNextPage = document.selectFirst("div.nav-links a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            // Simplified filter handling for now.
            // TODO: Implement a proper filter selection UI
            "$baseUrl/category/asian-drama/page/$page/"
        }

        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.postmovie-photo a[title]").map {
            SAnime().apply {
                this.url = it.attr("href")
                this.title = it.attr("title")
                this.thumbnail_url = ""
                this.source = AnimeSource.ASIA2TV.name
            }
        }

        val hasNextPage = document.selectFirst("div.nav-links a.next") != null
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            title = document.select("h1 span.title").text()
            thumbnail_url = document.select("div.single-thumb-bg > img").attr("src")
            description = document.select("div.getcontent p").text()
            genre = document.select("div.box-tags a, li:contains(البلد) a").joinToString(", ") { it.text() }
            source = AnimeSource.ASIA2TV.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        document.select("div.loop-episode a").map {
            SEpisode().apply {
                this.url = it.attr("href")
                // Example: .../the-good-bad-mother-episode-1/ -> "1 : الحلقة"
                this.name = it.attr("href").trimEnd('/').substringAfterLast("-") + " : الحلقة"
                this.episode_number = this.name!!.substringBefore(" ").toFloatOrNull() ?: 0f
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting fetchVideoList for URL: $episodeUrl")

        try {
            val initialResponse = client.newCall(Request.Builder().url(episodeUrl).build()).execute()
            println("DEBUG: Initial response code: ${initialResponse.code}")

            val initialDoc = Jsoup.parse(initialResponse.body!!.string())
            println("DEBUG: Initial document parsed successfully")

            val currentEpisodeLink = initialDoc.selectFirst("div.loop-episode a.current")?.attr("href")
            if (currentEpisodeLink == null) {
                println("DEBUG: ERROR - No current episode link found")
                return@withContext emptyList()
            }
            println("DEBUG: Found current episode link: $currentEpisodeLink")

            val finalResponse = client.newCall(Request.Builder().url(currentEpisodeLink).build()).execute()
            println("DEBUG: Final response code: ${finalResponse.code}")

            val finalDoc = Jsoup.parse(finalResponse.body!!.string())
            println("DEBUG: Final document parsed successfully")

            val serverElements = finalDoc.select("ul.server-list-menu li")
            println("DEBUG: Found ${serverElements.size} server elements")

            serverElements.forEachIndexed { index, element ->
                println("DEBUG: Server $index - data: ${element.attr("data-server")}, text: ${element.text()}")
            }

            val videos = serverElements.flatMapIndexed { index, element ->
                val url = element.attr("data-server")
                println("DEBUG: Processing server $index: $url")
                // Pass the episode URL as pageReferer for extractors that need it
                val serverVideos = getVideosFromUrl(url, currentEpisodeLink)
                println("DEBUG: Found ${serverVideos.size} videos from server $index")
                serverVideos.forEach { video ->
                    println("DEBUG: Video from server $index: ${video.quality} - ${video.url}")
                }
                serverVideos
            }

            println("DEBUG: Total videos found: ${videos.size}")
            videos.forEachIndexed { index, video ->
                println("DEBUG: Final video $index: ${video.quality} - ${video.url}")
            }

            videos

        } catch (e: Exception) {
            println("DEBUG: ERROR in fetchVideoList: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private val domainMappings = mapOf(
        "dood.li" to "d-s.io",
        "dood.to" to "d-s.io",
        "dood.ws" to "d-s.io",
        "dood.stream" to "d-s.io",
        "dood.com" to "d-s.io",
        "dood.wf" to "d-s.io",
        "dood.pm" to "d-s.io",
        "dood.re" to "d-s.io",
        "dood.yt" to "d-s.io",
        "dood.sh" to "d-s.io",
        "dood.so" to "d-s.io",
        "dood.cx" to "d-s.io",
        "dood.la" to "d-s.io"
    )

    fun getInstantFinalUrl(originalUrl: String): String {
        var result = originalUrl

        // Check each known domain pattern
        for ((oldDomain, newDomain) in domainMappings) {
            if (originalUrl.contains(oldDomain)) {
                result = originalUrl.replace(oldDomain, newDomain)
                break
            }
        }

        return result
    }


    private fun getVideosFromUrl(url: String, pageReferer: String = ""): List<Video> {
        println("DEBUG: getVideosFromUrl called with: $url, referer: $pageReferer")

        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: ""
        println("DEBUG: Extracted host: $host")
//url.contains("d-s.io" ) || url.contains("dood.li" )->
        return try {
            val videos = when {
//                "dood" in host || "ds2play" in host -> {
//                    println("DEBUG: Using doodExtractor for: $host")
                    url.contains("d-s.io" ) || url.contains("dood" )-> {
                        val doodUrl = getInstantFinalUrl(url)
                        println("doodUrl $doodUrl")
                        doodExtractor.videosFromUrl(doodUrl, "Doodstream")
                    }
//                }
                "streamtape" in host -> {
                    println("DEBUG: Using streamtapeExtractor for: $host")
                    streamtapeExtractor.videosFromUrl(url)
                }
//                STREAM_WISH_DOMAINS.any { host.contains(it) } -> {
//                    println("DEBUG: Using streamwishExtractor for: $host")
//                    streamwishExtractor.videosFromUrl(url)
//                }
                "uqload" in host -> {
                    println("DEBUG: Using uqloadExtractor for: $host")
                    uqloadExtractor.videosFromUrl(url)
                }
//                url.contains("mixdrop" ) || url.contains("mxdrop" ) || url.contains("mx" ) -> mixDropExtractor.videosFromUrl(url)


                // LuluStream extractors with proper pageReferer
//                "luluvid.com" in host || "luluvid" in host||"lulu" in host -> {
//                    println("DEBUG: Using luluVdoExtractor for: $host")
//                    luluVdoExtractor.videosFromUrl(url, pageReferer)
//                }
                "lulustream.com" in host || "lulustream" in host -> {
                    println("DEBUG: Using luluStream1Extractor for: $host")
                    luluStream1Extractor.videosFromUrl(url, pageReferer)
                }
                "kinoger.pw" in host || "kinoger" in host -> {
                    println("DEBUG: Using luluStream2Extractor for: $host")
                    luluStream2Extractor.videosFromUrl(url, pageReferer)
                }



                VID_BOM_DOMAINS.any { host.contains(it) } -> {
                    println("DEBUG: Using vidbomExtractor for: $host")
                    vidbomExtractor.videosFromUrl(url)
                }
                "youdbox" in host || "yodbox" in host -> {
                    println("DEBUG: Using custom extractor for Yodbox: $host")
                    try {
                        val response = client.newCall(Request.Builder().url(url).build()).execute()
                        println("DEBUG: Yodbox response code: ${response.code}")
                        val doc = Jsoup.parse(response.body!!.string())
                        val videoUrl = doc.selectFirst("source")?.attr("abs:src")
                        if (videoUrl != null) {
                            println("DEBUG: Found Yodbox video URL: $videoUrl")
                            listOf(Video(videoUrl, "Yodbox", videoUrl))
                        } else {
                            println("DEBUG: No video source found in Yodbox page")
                            emptyList()
                        }
                    } catch (e: Exception) {
                        println("DEBUG: ERROR processing Yodbox: ${e.message}")
                        emptyList()
                    }
                }
                "drive.google.com" in host -> {
                    println("DEBUG: Using Google Drive extractor for: $host")
                    extractGoogleDriveVideo(url)
                }
                else -> {
                    println("DEBUG: No extractor found for host: $host")
                    emptyList()
                }
            }

            println("DEBUG: getVideosFromUrl returning ${videos.size} videos for $url")
            videos

        } catch (e: Exception) {
            println("DEBUG: ERROR in getVideosFromUrl: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // Also add the Google Drive extractor function if not already present
    private fun extractGoogleDriveVideo(googleDriveUrl: String): List<Video> {
        println("DEBUG: extractGoogleDriveVideo called with: $googleDriveUrl")
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

            if (fileId != null) {
                println("DEBUG: Extracted Google Drive file ID: $fileId")
                val directUrl = "https://drive.google.com/uc?export=download&id=$fileId"

                // Add different quality options
                videos.add(Video(directUrl, "Google Drive: High Quality", directUrl))
                videos.add(Video(directUrl, "Google Drive: Medium Quality", directUrl))
                videos.add(Video(directUrl, "Google Drive: Standard", directUrl))

                println("DEBUG: Created ${videos.size} Google Drive video options")
            } else {
                println("DEBUG: Could not extract file ID from Google Drive URL")
            }

            videos

        } catch (e: Exception) {
            println("DEBUG: ERROR in extractGoogleDriveVideo: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // Stubs for unused functions
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            // Fetch the homepage content
            val request = Request.Builder().url(baseUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            // Select items from the main slider or the first few items on the page
            // The selector "div.postmovie-photo a" is a reliable choice for this site's theme
            val sliderItems = document.select("div.postmovie-photo a").map {
                SAnime().apply {
                    this.url = it.attr("href")
                    this.title = it.attr("title")
                    // The thumbnail is in an <img> tag within the link
                    this.thumbnail_url = it.selectFirst("img")?.attr("src") ?: ""
                    this.source = AnimeSource.ASIA2TV.name
                }
            }.take(10) // Limit to the first 10 items for a clean slider

            sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList() // Return an empty list on error
        }
    }
    fun getFilterList() = AnimeFilterList(emptyList())

    companion object {
        private val STREAM_WISH_DOMAINS = listOf("wishfast", "fviplions", "filelions", "streamwish", "dwish")
        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")
    }
}