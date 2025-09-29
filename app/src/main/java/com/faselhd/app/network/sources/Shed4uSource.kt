package com.faselhd.app.network.sources

import StreamGHExtractor
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.NetworkClient
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern

class Shed4uSource(private val context: Context) {

    private val baseUrl = "https://shed4u.cam"

    // =========================================================================
    //  Client Setup - Reusing the robust client from the example
    // =========================================================================
    private val client = NetworkClient.client

//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .followRedirects(true)
//            .followSslRedirects(true)
//            .addInterceptor { chain ->
//                val original = chain.request()
//                val request = original.newBuilder()
//                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
//                    .header("Referer", baseUrl)
//                    .build()
//                chain.proceed(request)
//            }
//            .build()
//    }

    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val bigWarpExtractor by lazy { BigWarpExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val luluStream1Extractor by lazy { LuluStream1Extractor(client) }

    //endregion

    // ============================== Main Slider ===============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        document.select("ul.glide__slides li.glide__slide a.show-card").mapNotNull {
            val style = it.attr("style")
            val matcher = Pattern.compile("url\\((.*?)\\)").matcher(style)
            val thumbnailUrl = if (matcher.find()) matcher.group(1) else return@mapNotNull null

            SAnime().apply {
                url = it.attr("href")
                title = it.select("p.title").text()
                thumbnail_url = thumbnailUrl
                source = "SHED4U" // Assuming SHED4U is a valid source name
            }
        }
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/?page=$page").build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.shows-container div.mb-3 a.show-card").mapNotNull {
            val style = it.attr("style")
            val matcher = Pattern.compile("url\\((.*?)\\)").matcher(style)
            val thumbnailUrl = if (matcher.find()) matcher.group(1) else return@mapNotNull null

            SAnime().apply {
                url = it.attr("href")
                title = it.select("p.title").text()
                thumbnail_url = thumbnailUrl
                source = "SHED4U"
            }
        }

        val hasNextPage = document.selectFirst("ul.pagination button[onclick*=\"updateQuery('page', ${page + 1})\"]") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search?s=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        println("DEBUG: Fetching URL: $url")

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body!!.string()

        println("DEBUG: Response code: ${response.code}")
        println("DEBUG: Response length: ${responseBody.length} characters")

        val document = Jsoup.parse(responseBody)

        // Debug: Check if we found the main container
        val showsContainer = document.select("div.shows-container")
        println("DEBUG: Found shows-container: ${showsContainer.size}")

        val animeElements = document.select("div.shows-container div.col-6.col-md-4.col-lg-20ps.mb-3 a.show-card")
        println("DEBUG: Found ${animeElements.size} anime elements")

        val animeList = animeElements.mapIndexedNotNull { index, element ->
            try {
                println("DEBUG: Processing element $index")

                // Extract URL
                val animeUrl = element.attr("href")
                println("DEBUG: Element $index - URL: $animeUrl")

                // Extract title
                val title = element.select("p.title").text()
                println("DEBUG: Element $index - Title: $title")

                // Extract thumbnail URL from style attribute
                val style = element.attr("style")
                println("DEBUG: Element $index - Style: $style")

                val thumbnailUrl = if (style.contains("url(")) {
                    val urlFromStyle = style.substringAfter("url(").substringBefore(")").trim()
                    println("DEBUG: Element $index - Thumbnail from style: $urlFromStyle")
                    urlFromStyle
                } else {
                    // Fallback: try to find image in child elements
                    val imgElement = element.select("img").first()
                    val imgUrl = imgElement?.attr("src") ?: ""
                    println("DEBUG: Element $index - Thumbnail from img tag: $imgUrl")
                    imgUrl
                }

                // Extract episode number if available
                val episodeText = element.select("span.ep").text()
                println("DEBUG: Element $index - Episode: $episodeText")

                SAnime().apply {
                    this.url = animeUrl
                    this.title = title
                    this.thumbnail_url = thumbnailUrl
                    this.source = "SHED4U"
                }.also {
                    println("DEBUG: Element $index - Successfully created SAnime object")
                }
            } catch (e: Exception) {
                println("DEBUG: Element $index - Error: ${e.message}")
                e.printStackTrace()
                null
            }
        }

        println("DEBUG: Successfully processed ${animeList.size} anime items")

        // Check for next page - looking for pagination buttons
        val paginationElements = document.select("ul.pagination button")
        println("DEBUG: Found ${paginationElements.size} pagination buttons")

        paginationElements.forEachIndexed { index, button ->
            val buttonText = button.text()
            val onClick = button.attr("onclick")
            println("DEBUG: Pagination button $index - Text: '$buttonText', OnClick: '$onClick'")
        }

        val hasNextPage = paginationElements.any { button ->
            val buttonText = button.text()
            val onClick = button.attr("onclick")
            val isNextPage = buttonText.toIntOrNull() == page + 1 ||
                    onClick.contains("updateQuery('page', ${page + 1})") ||
                    buttonText.contains("التالي") || // Next in Arabic
                    buttonText.contains("Next")

            if (isNextPage) {
                println("DEBUG: Found next page button: $buttonText")
            }
            isNextPage
        }

        println("DEBUG: Has next page: $hasNextPage")

        MangaPage(animeList, hasNextPage).also {
            println("DEBUG: Returning MangaPage with ${it.manga.size} items, hasNextPage: ${it.hasNextPage}")
        }
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            title = document.select("div.info-side span.title").text()
            val style = document.select("div.poster").attr("style")
            val matcher = Pattern.compile("url\\((.*?)\\)").matcher(style)
            if (matcher.find()) {
                thumbnail_url = matcher.group(1)
            }
            description = document.select("span.description").text()
            genre = document.select("div.info-side a[href*='/genre/']").joinToString(", ") { it.text() }
            status = if (document.select("div.items a.epss[href*='/episode/']").isNotEmpty()) SAnime.ONGOING else SAnime.COMPLETED
            source = "SHED4U"
        }
    }


    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        // This main function makes the initial network request.
        val initialRequest = Request.Builder().url(animeUrl).build()
        val response = client.newCall(initialRequest).execute()

        // THE FIX:
        // We must provide the original animeUrl as the "baseUri" (the second argument).
        // This allows document.location() to work correctly inside the parsing function.
        val document = Jsoup.parse(response.body!!.string(), animeUrl)

        // Now, we pass the correctly-parsed document to the parser.
        return@withContext episodeListParse(document)
    }

    /**
     * Parses the document to intelligently find all episodes, correctly grouped by season.
     * For multi-season shows, it will make new network requests for each season.
     */
    private fun episodeListParse(document: org.jsoup.nodes.Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val thumbnailUrl = document.select("div.poster").attr("style")
            .substringAfter("url(").substringBefore(")")

        val currentUrl = document.location()

        // 1. Explicitly check if the URL indicates it's a movie or single post.
        // This is a much more reliable way to detect non-series content.
        if (currentUrl.contains("/film/") || currentUrl.contains("/post/")) {
            // --- MOVIES / SINGLE POSTS ---
            episodes.add(
                SEpisode().apply {
                    url = currentUrl // The URL of the movie details page itself.
                    name = document.select("div.info-side span.title").text() // Just the movie title
                    episode_number = 1f
                    this.thumbnailUrl = thumbnailUrl
                }
            )
            return episodes // Return immediately as movies have only one "episode".
        }

        // 2. If it's not a movie, check for a list of seasons on the page for a series.
        val seasonElements = document.select("div.items a.epss[href*='/season/']")

        if (seasonElements.isNotEmpty()) {
            // --- MULTI-SEASON SHOWS ---
            seasonElements.reversed().forEach { seasonElement ->
                val seasonName = seasonElement.text().trim()
                val seasonUrl = seasonElement.attr("abs:href")
                try {
                    val seasonDoc = Jsoup.parse(client.newCall(Request.Builder().url(seasonUrl).build()).execute().body!!.string())
                    seasonDoc.select("div.items a.epss[href*='/episode/']").reversed().forEach { episodeElement ->
                        episodes.add(episodeFromElement(episodeElement, seasonName, thumbnailUrl))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else if (document.select("div.items a.epss[href*='/episode/']").isNotEmpty()) {
            // --- SINGLE-SEASON SHOW ---
            val seriesTitleAsSeason = document.select("span.title").text().substringBefore("الحلقة").trim()
            document.select("div.items a.epss[href*='/episode/']").reversed().forEach { episodeElement ->
                episodes.add(episodeFromElement(episodeElement, seriesTitleAsSeason, thumbnailUrl))
            }
        }

        // If, after all checks, the list is empty (e.g., a brand new series with no episodes listed yet),
        // we return the empty list instead of incorrectly treating it as a movie.
        return episodes
    }

    /**
     * Helper function to create an SEpisode from an HTML element (<a> tag).
     * This function ensures the name is formatted as "Season Name : Episode Name".
     */
    private fun episodeFromElement(element: org.jsoup.nodes.Element, seasonName: String, thumbnailUrl: String): SEpisode {
        val episodeTitle = element.text().trim() // This will be "الحلقة 1", "الحلقة 2", etc.

        return SEpisode().apply {
            url = element.attr("abs:href")
            // This is the crucial formatting for your UI's seasonal grouping.
            name = "$seasonName : $episodeTitle"
            episode_number = element.select("span.fs-2").text().toFloatOrNull() ?: 0f
            this.thumbnailUrl = thumbnailUrl
        }
    }

    // Define a data class for parsing server information from JSON
    data class Server(val url: String)

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val watchUrl = episodeUrl
            .replace("/episode/", "/watch/")
            .replace("/film/", "/watch/")
            .replace("/post/", "/watch/")
        println("watchurlllol : $episodeUrl $watchUrl")
        val request = Request.Builder().url(watchUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val scriptContent = document.select("script:containsData(let servers = JSON.parse)").first()?.data()
            ?: return@withContext emptyList()

        // Extract the JSON string from the script tag
        val jsonRegex = Regex("let servers = JSON\\.parse\\('(\\[.*?\\])'\\);")
        val matchResult = jsonRegex.find(scriptContent)
        val jsonString = matchResult?.groups?.get(1)?.value?.let {
            // The JSON string inside javascript is escaped, we need to unescape it
            it.replace("\\\"", "\"").replace("\\/", "/")
        } ?: return@withContext emptyList()

        val serverList: List<Server> = try {
            val serverType = object : TypeToken<List<Server>>() {}.type
            Gson().fromJson(jsonString, serverType)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        serverList.flatMap { server ->

            extractVideosFromUrl(normalizeUrl(server.url))
        }
    }

    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
    }
    fun getFinalDoodUrl(originalUrl: String): String {
        val client = OkHttpClient.Builder()
            .followRedirects(true) // Enable following redirects
            .followSslRedirects(true) // Enable SSL redirects
            .build()

        return try {
            val request = Request.Builder()
                .url(originalUrl)
                .head() // Use HEAD request to avoid downloading the whole body
                .build()

            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()

            finalUrl
        } catch (e: IOException) {
            e.printStackTrace()
            originalUrl // Return original URL if there's an error
        }
    }

    fun extractHglinkId(url: String): String? {
        // Normalize scheme-less URLs
        val normalized = if (url.startsWith("//")) "https:$url" else url

        val regex = Regex(
            pattern = """^https?://(?:www\.)?hglink\.to/e/([A-Za-z0-9]+)(?:[/?#]|$)""",
            option = RegexOption.IGNORE_CASE
        )
        return regex.find(normalized)?.groupValues?.get(1)
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        return when {
            //            DOOD_REGEX.matcher(url).find() -> doodExtractor.videosFromUrl(url, "Dood")
            "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url -> {

                val result = doodExtractor.videosFromUrl(url, "Doodstream")
                println("DEBUG: Doodstream extraction result: ${result.size} videos found")
                result
            }
            url.contains("mixdrop") || url.contains("mxdrop") || url.contains("mx") -> {
                println("DEBUG: Processing MixDrop URL: $url")
                val result = mixDropExtractor.videosFromUrl(url)
                println("DEBUG: MixDrop extraction result: ${result.size} videos found")
                result
            }
            "streamtape" in url -> {
                println("DEBUG: Using streamtapeExtractor for: $url")
                streamTapeExtractor.videosFromUrl(url)
            }

            "vidtube" in url || "zjet7" in url || "vidshar" in url -> {
                Log.d("VideoFetcher", "🔎 Extracting from vidtube server ($): $url")
                vidTubeExtractor.videosFromUrl(url)
            }
            "filemoon" in url || "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, "FileMoon") // Assuming you have a Filemoon extractor
            url.contains("wish") || url.contains("videa") || url.contains("mx") -> {
                println("DEBUG: Processing StreamWish URL: $url")
                val result = streamWishExtractor.videosFromUrl(url)
                println("DEBUG: StreamWish extraction result: ${result.size} videos found")
                result
            }
            url.contains("uqload") || url.contains("upload") -> {
                println("DEBUG: Processing Uqload URL: $url")
                val result = uqloadExtractor.videosFromUrl(url)
                println("DEBUG: Uqload extraction result: ${result.size} videos found")
                result
            }
//            url.contains("bigwarp") || url.contains("bigwarp.io") -> {
//                println("DEBUG: Processing BigWarp URL: $url")
//                val result = bigWarpExtractor.videosFromUrl(url)
//                println("DEBUG: BigWarp extraction result: ${result.size} videos found")
//                result
//            }
            url.contains("fdewsdc") || url.contains("mivalyo") || url.contains("mivalyo.com") -> {
                println("DEBUG: Processing Mivalyo URL: $url")
                val result = mivalyoExtractor.videosFromUrl(url)
                println("DEBUG: Mivalyo extraction result: ${result.size} videos found")
                result
            }
            url.contains("fsdcmo") ||url.contains("hglink") || url.contains("hglink.to") -> {
                println("DEBUG: Processing Hglink URL: $url")
                val extractedId = extractHglinkId(url)
                println("DEBUG: Extracted Hglink ID: $extractedId")
                val haxloppdUrl = "https://haxloppd.com/$extractedId"
                println("DEBUG: Haxloppd URL: $haxloppdUrl")
                val result = haxloppdExtractor.videosFromUrl(url)
                println("DEBUG: Haxloppd extraction result: ${result.size} videos found")
                result
            }

            "lulu" in url || "lulustream"  in url -> {
                println("DEBUG: Using luluStream1Extractor for: $url")
                luluStream1Extractor.videosFromUrl(url, url)
            }
            url.contains("ahvsh") || url.contains("fanakishtuna") -> {
                println("DEBUG: Processing AHVSH/Fanakishtuna URL: $url")
                try {
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
                    println("DEBUG: HTTP Response code: ${response.code}")
                    val htmlBody = response.body!!.string()
                    println("DEBUG: HTML body length: ${htmlBody.length}")

                    val doc = Jsoup.parse(htmlBody)
                    val script = doc.selectFirst("script:containsData(sources)")?.data() ?: ""
                    println("DEBUG: Script found: ${script.isNotEmpty()}, length: ${script.length}")

                    val videoUrl = Regex("""file:\s*["']([^"']+)""").find(script)?.groupValues?.get(1)
                    println("DEBUG: Extracted video URL: $videoUrl")

                    val result = if (videoUrl != null) {
                        val videos = listOf(Video(videoUrl, "Mirror", videoUrl))
                        println("DEBUG: AHVSH extraction successful: ${videos.size} videos found")
                        videos
                    } else {
                        println("DEBUG: AHVSH extraction failed - no video URL found")
                        emptyList()
                    }
                    result
                } catch (e: Exception) {
                    println("DEBUG: AHVSH extraction error: ${e.message}")
                    e.printStackTrace()
                    emptyList()
                }
            }
            else -> {

                println("DEBUG: No matching extractor found for URL: $url")
                emptyList()
            }
        }
    }

    fun getFilterList() = AnimeFilterList(emptyList())
}