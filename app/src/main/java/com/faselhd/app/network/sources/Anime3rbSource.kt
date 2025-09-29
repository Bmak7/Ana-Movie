package com.faselhd.app.network.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.CloudflareInterceptor
import com.faselhd.app.network.NetworkClient
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.CookieManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.text.Regex

class Anime3rbSource(private val context: Context) {

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

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client = NetworkClient.client
//    private val client: OkHttpClient by lazy {
//        val cookieManager = CookieManager()
//        val cookieJar: CookieJar = JavaNetCookieJar(cookieManager)
//
//        OkHttpClient.Builder()
//            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
//            .cookieJar(cookieJar)
//            .addInterceptor(CloudflareInterceptor(context, cookieJar))
//            .addInterceptor { chain ->
//                val originalRequest = chain.request()
//                val newRequest = originalRequest.newBuilder()
//                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//                    .header("Referer", baseUrl)
//                    .build()
//                chain.proceed(newRequest)
//            }
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }

    private val baseUrl = "https://anime3rb.com"

    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
            // FIX: The original selector was targeting "Latest Episodes".
            // This now correctly targets the main slider ("الأنميات المثبتة").
            document.select("section.relative div.glide li.glide__slide a.video-card").mapNotNull {
                toEpisodeAnime(it) // Use a helper for episode cards
            }.take(10)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun toEpisodeAnime(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("abs:href")
            title = element.selectFirst("h3.title-name")?.text() ?: "Unknown"
            thumbnail_url = element.selectFirst("div.poster img")?.attr("src")
            source = AnimeSource.ANIME3RB.name
        }
    }
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext MangaPage(emptyList(), false)
        }

        try {
            // Step 1: Get initial page and cookies
            val mainPageRequest = Request.Builder().url(baseUrl).build()
            val mainPageResponse = client.newCall(mainPageRequest).execute()
            if (!mainPageResponse.isSuccessful) return@withContext MangaPage(emptyList(), false)

            val document = Jsoup.parse(mainPageResponse.body!!.string(), baseUrl)

            // Step 2: Extract CSRF token and snapshot with more robust selectors
            val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

            // MODIFIED SELECTOR: This is more generic and should find the search component's snapshot.
            val snapshotJsonString = document.selectFirst("form[wire\\:snapshot]")?.attr("wire:snapshot")

            if (csrfToken.isNullOrEmpty() || snapshotJsonString.isNullOrEmpty()) {
                println("Failed to extract CSRF token or Live wire snapshot. Check HTML structure.")
                return@withContext MangaPage(emptyList(), false)
            }

            // Step 3: Build the JSON payload using JSONObject to ensure correct formatting
            val snapshotObject = JSONObject(snapshotJsonString)
            val updatesObject = JSONObject().put("query", query)
            val callsArray = JSONArray()

            val componentObject = JSONObject()
            componentObject.put("snapshot", snapshotObject.toString()) // Ensure snapshot is a string
            componentObject.put("updates", updatesObject)
            componentObject.put("calls", callsArray)

            val componentsArray = JSONArray().put(componentObject)

            val rootPayload = JSONObject()
            rootPayload.put("_token", csrfToken)
            rootPayload.put("components", componentsArray)

            val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), rootPayload.toString())

            // Step 4: Make the POST request
            val livewireUrl = "$baseUrl/livewire/update"
            val searchRequest = Request.Builder()
                .url(livewireUrl)
                .post(requestBody)
                .header("X-CSRF-TOKEN", csrfToken)
                .header("X-Livewire", "true")
                .header("Accept", "application/json")
                .header("Origin", baseUrl)
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            if (!searchResponse.isSuccessful) return@withContext MangaPage(emptyList(), false)

            // Step 5: Parse the response and extract results
            val responseBody = searchResponse.body!!.string()
            val jsonResponse = JSONObject(responseBody)

            // The HTML is nested inside the 'effects' of the first component
            val htmlContent = jsonResponse.getJSONArray("components").getJSONObject(0)
                .getJSONObject("effects").getString("html")

            val searchResultsDocument = Jsoup.parse(htmlContent)
            val animeList = searchResultsDocument.select("a.simple-title-card").mapNotNull {
                toLiveSearchAnime(it)
            }

            return@withContext MangaPage(animeList, false)

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext MangaPage(emptyList(), false)
        }
    }

    /**
     * Helper function specifically for parsing anime from the live search result's HTML structure.
     */
    private fun toLiveSearchAnime(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("abs:href")
            title = element.selectFirst("h4.text-lg")?.text()?.trim() ?: "Unknown Title"
            thumbnail_url = element.selectFirst("img")?.attr("src")
            source = AnimeSource.ANIME3RB.name
            // Other details like description are not in the live search snippet.
            // They will be loaded when the user selects the item and fetchAnimeDetails is called.
        }
    }

    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            url = animeUrl
            // Get title from page title or heading
            title = document.selectFirst("h1, .title-name, title")?.text()
                ?.replace("مترجم أون لاين.*".toRegex(), "")
                ?.replace("- Anime3rb.*".toRegex(), "")
                ?.trim() ?: "Unknown Title"

            // Get thumbnail from meta tags or images
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.poster, .title-poster img, img[alt*=بوستر]")?.attr("src")

            // Look for description in meta tags
            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?.replace("مشاهدة و تحميل.*".toRegex(), "")
                ?.trim()

            // Extract genres from the sidebar or page content
            genre = document.select("a[href*=/genre/]").joinToString(", ") {
                it.text().trim()
            }

            // Determine status based on available information
            status = SAnime.UNKNOWN // Default since status info isn't clearly available
            source = AnimeSource.ANIME3RB.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val allEpisodes = mutableListOf<SEpisode>()

        // MODERN APPROACH: Look for distinct season containers first.
        // A common pattern is a div for each season with a title and the episode list.
        val seasonContainers = document.select("div.season-list > div.entry-content")

        if (seasonContainers.isNotEmpty()) {
            // Case A: Found distinct season containers
            seasonContainers.forEach { container ->
                // The season name is usually a title right before the list of episodes.
                val seasonName = container.selectFirst("h3.title, h4.widget-title")?.text()?.trim() ?: "Season"

                container.select("a[href*=/episode/]").forEach { episodeElement ->
                    allEpisodes.add(createEpisode(episodeElement, seasonName)) // Use the season name
                }
            }
        } else {
            // Case B: No season containers found, fallback to the old method
            val animeTitle = document.selectFirst("h1, .title-name, title")?.text()
                ?.replace("مترجم أون لاين.*".toRegex(), "")
                ?.replace("- Anime3rb.*".toRegex(), "")
                ?.trim() ?: "Unknown"

            // Scrape all episode links from the entire page
            document.select("div.videos-list a, .episode-list a, a[href*=/episode/]").forEach { episodeElement ->
                allEpisodes.add(createEpisode(episodeElement, animeTitle)) // Use the main anime title
            }
        }

        // Sort all collected episodes by their number
        return@withContext allEpisodes.sortedBy { it.episode_number }
    }


    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            // The latest episodes are on the homepage. Pagination is handled by the "?page=" query parameter.
            val requestUrl = if (page > 1) "$baseUrl/?page=$page" else baseUrl
            val request = Request.Builder().url(requestUrl).build()

            // Fetch and parse the HTML content of the page.
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string(), baseUrl)

            // Select each episode card. Based on the HTML, they are `a.video-card` elements
            // within the main `div#videos` container.
            val animeList = document.select("div#videos a.video-card").map { element ->
                SAnime().apply {
                    // The URL in the card points directly to the episode. We use this as the primary URL.
                    // The app's subsequent logic (e.g., in fetchAnimeDetails) might need to handle this.
                    url = element.attr("abs:href")

                    // The title is in the `h3` tag with the class "title-name".
                    title = element.selectFirst("h3.title-name")?.text()?.trim() ?: "Unknown Title"

                    // The thumbnail URL is the `src` of the `img` tag inside the poster div.
                    thumbnail_url = element.selectFirst("div.poster img")?.attr("src")

                    // Set the source identifier.
                    source = AnimeSource.ANIME3RB.name
                }
            }

            // Determine if there is a next page.
            // A simple and effective way is to check if the current page returned any results.
            // If the list is not empty, it's possible there are more pages.
            val hasNextPage = animeList.isNotEmpty()

            // Return the results wrapped in a MangaPage object.
            MangaPage(animeList, hasNextPage)

        } catch (e: Exception) {
            e.printStackTrace()
            // In case of any errors (e.g., network issues, parsing failures), return an empty result.
            MangaPage(emptyList(), false)
        }
    }


    suspend fun fetchLatestUpdatess(page: Int): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            // The latest episodes are on the homepage. Pagination is handled by the "?page=" query parameter.
            val requestUrl = if (page > 1) "$baseUrl/?page=$page" else baseUrl
            val request = Request.Builder().url(requestUrl).build()

            // Fetch and parse the HTML content of the page.
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string(), baseUrl)

            // Select each episode card. Based on the HTML, they are `a.video-card` elements
            // within the main `div#videos` container.
            val animeList = document.select("div#videos a.video-card").map { element ->
                SAnime().apply {
                    // The URL in the card points directly to the episode. We use this as the primary URL.
                    // The app's subsequent logic (e.g., in fetchAnimeDetails) might need to handle this.
                    url = element.attr("abs:href")

                    // The title is in the `h3` tag with the class "title-name".
                    title = element.selectFirst("h3.title-name")?.text()?.trim() ?: "Unknown Title"

                    // The thumbnail URL is the `src` of the `img` tag inside the poster div.
                    thumbnail_url = element.selectFirst("div.poster img")?.attr("src")

                    // Set the source identifier.
                    source = AnimeSource.ANIME3RB.name
                }
            }

            // Determine if there is a next page.
            // A simple and effective way is to check if the current page returned any results.
            // If the list is not empty, it's possible there are more pages.
            val hasNextPage = animeList.isNotEmpty()

            // Return the results wrapped in a MangaPage object.
            (animeList)

        } catch (e: Exception) {
            e.printStackTrace()
            // In case of any errors (e.g., network issues, parsing failures), return an empty result.
            (emptyList())
        }
    }

    suspend fun fetchHomePageLatestAnimes(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            // The target page is the main directory for anime titles.
            val requestUrl = "$baseUrl/titles/list"
            val request = Request.Builder().url(requestUrl).build()

            // Execute the request and parse the HTML response.
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string(), baseUrl)

            // Select all `div.title-card` elements and map them to SAnime objects.
            val animeList = document.select("div.titles-list div.title-card").mapNotNull { cardElement ->
                // The primary link contains the poster, title, and URL.
                val linkElement = cardElement.selectFirst("a") ?: return@mapNotNull null

                SAnime().apply {
                    // Extract the absolute URL to the anime's detail page.
                    url = linkElement.attr("abs:href")

                    // Extract the title from the h2 element.
                    title = linkElement.selectFirst("h2.title-name")?.text()?.trim() ?: "Unknown Title"

                    // Extract the thumbnail URL from the img element's src attribute.
                    thumbnail_url = linkElement.selectFirst("img")?.attr("src")

                    // Set the source identifier.
                    source = AnimeSource.ANIME3RB.name
                }
            }

            return@withContext animeList

        } catch (e: Exception) {
            e.printStackTrace()
            // If any error occurs (e.g., network failure, parsing error), return an empty list.
            emptyList()
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
            val videos = mutableListOf<Video>()

            // Method 1: Extract iframe player URLs and fetch direct .mp4 from them
            val iframes = document.select("iframe[src*=player], iframe[src*=vid3rb]")
            iframes.forEach { iframe ->
                val playerUrl = iframe.attr("abs:src")
                if (playerUrl.isNotEmpty()) {
                    try {
                        // Fetch the iframe content
                        val playerDocument = Jsoup.parse(
                            client.newCall(Request.Builder().url(playerUrl).build())
                                .execute().body!!.string()
                        )

                        // Extract direct .mp4 URLs from video elements
                        playerDocument.select("video[src*=.mp4], video source[src*=.mp4]").forEach { videoElement ->
                            val mp4Url = videoElement.attr("src").ifEmpty { videoElement.attr("abs:src") }
                            if (mp4Url.contains(".mp4")) {
                                val quality = extractQualityFromUrl(mp4Url) ?: "Default"
                                videos.add(Video(
                                    url = mp4Url,
                                    quality = quality,
                                    videoUrl = mp4Url
                                ))
                            }
                        }

                        // Extract from JavaScript in the player page
                        val scriptContent = playerDocument.select("script:not([src])").joinToString("\n") { it.html() }
                        extractMp4FromJavaScript(scriptContent, videos)

                    } catch (e: Exception) {
                        println("Failed to fetch player iframe: ${e.message}")
                        // Keep the player URL as fallback
                        videos.add(Video(
                            url = playerUrl,
                            quality = "Player",
                            videoUrl = playerUrl
                        ))
                    }
                }
            }

            // Method 2: Extract from x-data and then fetch the player
            document.selectFirst("section[x-data*='videoUrl:'], div[x-data*='videoUrl:']")?.let { section ->
                val xData = section.attr("x-data")
                val videoUrlRegex = Regex("""videoUrl:\s*'([^']+)'""")
                videoUrlRegex.find(xData)?.let { match ->
                    val videoPlayerUrl = match.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")

                    if (videoPlayerUrl.isNotEmpty()) {
                        try {
                            // Fetch the player page to get direct .mp4
                            val playerDocument = Jsoup.parse(
                                client.newCall(Request.Builder().url(videoPlayerUrl).build())
                                    .execute().body!!.string()
                            )

                            // Look for direct .mp4 URLs in video elements
                            playerDocument.select("video[src*=.mp4], video source[src*=.mp4]").forEach { videoElement ->
                                val mp4Url = videoElement.attr("src").ifEmpty { videoElement.attr("abs:src") }
                                if (mp4Url.contains(".mp4")) {
                                    val quality = extractQualityFromUrl(mp4Url) ?: "Default"
                                    videos.add(Video(
                                        url = mp4Url,
                                        quality = quality,
                                        videoUrl = mp4Url
                                    ))
                                }
                            }

                            // Extract from JavaScript variables in player
                            val scriptContent = playerDocument.select("script:not([src])").joinToString("\n") { it.html() }
                            extractMp4FromJavaScript(scriptContent, videos)

                        } catch (e: Exception) {
                            println("Failed to fetch video player: ${e.message}")
                            // Keep the player URL as fallback
                            videos.add(Video(
                                url = videoPlayerUrl,
                                quality = "Default",
                                videoUrl = videoPlayerUrl
                            ))
                        }
                    }
                }
            }

            // Method 3: Direct download links (keep existing functionality)
            document.select("a[href*=download], a[href*=.mp4]").forEach { downloadLink ->
                val href = downloadLink.attr("abs:href")
                val linkText = downloadLink.text().trim()

                val quality = extractQualityFromText(linkText) ?: "Download"

                if (href.isNotEmpty()) {
                    videos.add(Video(
                        url = href,
                        quality = quality,
                        videoUrl = href
                    ))
                }
            }

            // Remove duplicates and prioritize .mp4 files
            return@withContext videos
                .distinctBy { it.url }
                .filter { it.url.isNotEmpty() }
                .sortedWith(compareBy<Video> {
                    if (it.url.contains(".mp4")) 0 else 1 // .mp4 files first
                }.thenByDescending {
                    when (it.quality) {
                        "1080p" -> 1080
                        "720p" -> 720
                        "480p" -> 480
                        "360p" -> 360
                        else -> 0
                    }
                })

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun extractMp4FromJavaScript(scriptContent: String, videos: MutableList<Video>) {
        // Pattern 1: Look for direct .mp4 URLs in src attributes
        val mp4UrlRegex = Regex("""['"`](https://[^'"`]*\.mp4[^'"`]*)['"`]""")
        mp4UrlRegex.findAll(scriptContent).forEach { match ->
            val mp4Url = match.groupValues[1]
            val quality = extractQualityFromUrl(mp4Url) ?: "JavaScript"
            videos.add(Video(
                url = mp4Url,
                quality = quality,
                videoUrl = mp4Url
            ))
        }

        // Pattern 2: Look for video configuration objects
        val videoConfigRegex = Regex("""src:\s*['"`]([^'"`]*\.mp4[^'"`]*)['"`]""")
        videoConfigRegex.findAll(scriptContent).forEach { match ->
            val mp4Url = match.groupValues[1]
            val quality = extractQualityFromUrl(mp4Url) ?: "Config"
            videos.add(Video(
                url = mp4Url,
                quality = quality,
                videoUrl = mp4Url
            ))
        }

        // Pattern 3: Look for files array with .mp4 entries
        val filesArrayRegex = Regex("""files.*?:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        filesArrayRegex.find(scriptContent)?.let { match ->
            val filesContent = match.groupValues[1]
            val fileUrlRegex = Regex("""['"`](https://[^'"`]*\.mp4[^'"`]*)['"`]""")
            fileUrlRegex.findAll(filesContent).forEach { urlMatch ->
                val mp4Url = urlMatch.groupValues[1]
                val quality = extractQualityFromUrl(mp4Url) ?: "Files Array"
                videos.add(Video(
                    url = mp4Url,
                    quality = quality,
                    videoUrl = mp4Url
                ))
            }
        }

        // Pattern 4: VideoJS source configuration
        val videojsRegex = Regex("""(?:source|sources).*?src:\s*['"`]([^'"`]*\.mp4[^'"`]*)['"`]""")
        videojsRegex.findAll(scriptContent).forEach { match ->
            val mp4Url = match.groupValues[1]
            val quality = extractQualityFromUrl(mp4Url) ?: "VideoJS"
            videos.add(Video(
                url = mp4Url,
                quality = quality,
                videoUrl = mp4Url
            ))
        }
    }

    private fun extractQualityFromUrl(url: String): String? {
        return when {
            url.contains("1080p", ignoreCase = true) -> "1080p"
            url.contains("720p", ignoreCase = true) -> "720p"
            url.contains("480p", ignoreCase = true) -> "480p"
            url.contains("360p", ignoreCase = true) -> "360p"
            url.contains("/1080/", ignoreCase = true) -> "1080p"
            url.contains("/720/", ignoreCase = true) -> "720p"
            url.contains("/480/", ignoreCase = true) -> "480p"
            url.contains("/360/", ignoreCase = true) -> "360p"
            else -> Regex("""(\d{3,4})p\.\w+""") // supports .mp4, .mkv, .ts, etc.
                .find(url)
                ?.groupValues?.get(1)
                ?.plus("p")
        }
    }


    private fun extractQualityFromText(text: String): String? {
        return when {
            text.contains("1080", ignoreCase = true) -> "1080p"
            text.contains("720", ignoreCase = true) -> "720p"
            text.contains("480", ignoreCase = true) -> "480p"
            text.contains("360", ignoreCase = true) -> "360p"
            text.contains("عالية", ignoreCase = true) -> "High Quality"
            text.contains("منخفضة", ignoreCase = true) -> "Low Quality"
            text.contains("متوسطة", ignoreCase = true) -> "Medium Quality"
            else -> {
                // Extract file size and estimate quality
                val sizeRegex = Regex("""(\d+(?:\.\d+)?)\s*(?:ميغابايت|MB)""", RegexOption.IGNORE_CASE)
                val sizeMb = sizeRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

                when {
                    sizeMb == null -> null
                    sizeMb > 300 -> "1080p"
                    sizeMb > 150 -> "720p"
                    sizeMb > 80 -> "480p"
                    else -> "360p"
                }
            }
        }
    }

    private fun toAnime(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("abs:href")

            // Get title from various possible locations
            title = element.selectFirst("h2, h3, .title-name, .video-data span")?.text()
                ?: element.attr("title")
                        ?: "Unknown"

            // Get thumbnail from img tag or data-src attribute
            thumbnail_url = element.selectFirst("img")?.let { img ->
                img.attr("src").ifEmpty {
                    img.attr("data-src")
                }
            }

            source = AnimeSource.ANIME3RB.name
        }
    }

    private fun createEpisode(element: Element, seriesName: String): SEpisode {
        val episodeData = element.selectFirst("div.video-data")
        val episodeTitle = episodeData?.selectFirst("span")?.text() ?: element.text()
        val episodeSubtitle = episodeData?.selectFirst("p")?.text() ?: ""

        return SEpisode().apply {
            url = element.attr("abs:href")

            // Create episode name, prepending the series/season name for context
            name = when {
                episodeTitle.contains("الحلقة") -> "$seriesName: $episodeTitle"
                episodeTitle.isNotEmpty() -> "$seriesName - $episodeTitle"
                else -> element.text().ifEmpty { "$seriesName - Episode" }
            }

            // Extract episode number from text like "الحلقة 1" or from the URL
            episode_number = Regex("""(\d+)""").find(episodeTitle)?.value?.toFloatOrNull()
                ?: Regex("""/episode/.*?-(\d+)""").find(url!!)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: 0f

            // Add subtitle info if available
            if (episodeSubtitle.isNotEmpty()) {
                name += " ($episodeSubtitle)"
            }
        }
    }

    private fun getStatus(statusString: String): Int {
        return when {
            statusString.contains("منتهي", ignoreCase = true) ||
                    statusString.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
            statusString.contains("يعرض الان", ignoreCase = true) ||
                    statusString.contains("قيد البث", ignoreCase = true) ||
                    statusString.contains("مستمر", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    fun getFilterList() = AnimeFilterList(emptyList())
}

//package com.faselhd.app.network.sources
//
//import android.content.Context
//import com.faselhd.app.models.*
//import com.faselhd.app.network.AnimeSource
//import com.faselhd.app.network.CloudflareInterceptor
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import okhttp3.CookieJar
//import okhttp3.JavaNetCookieJar
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import org.jsoup.Jsoup
//import org.jsoup.nodes.Element
//import java.net.CookieManager
//import java.util.concurrent.TimeUnit
//import kotlin.text.Regex
//
//class Anime3rbSource(private val context: Context) {
//
//    private val client: OkHttpClient by lazy {
//        val cookieManager = CookieManager()
//        val cookieJar: CookieJar = JavaNetCookieJar(cookieManager)
//
//        OkHttpClient.Builder()
//            .cookieJar(cookieJar)
//            .addInterceptor(CloudflareInterceptor(context, cookieJar))
//            .addInterceptor { chain ->
//                val originalRequest = chain.request()
//                val newRequest = originalRequest.newBuilder()
//                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//                    .header("Referer", baseUrl)
//                    .build()
//                chain.proceed(newRequest)
//            }
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }
//
//    private val baseUrl = "https://anime3rb.com"
//
//    // ============================== Main Page Sections ===============================
//
//    // FIX: Corrected the paths and titles to reflect what's on the site's sidebar for clarity.
//    private val mainPageSections = listOf(
//        Pair("قائمة مسلسلات الأنمي", "/titles/list/tv?page="),
//        Pair("قائمة أفلام الأنمي", "/titles/list/movie?page="),
//        Pair("قائمة الأوفا", "/titles/list/ova?page="),
//        Pair("قائمة الأونا", "/titles/list/ona?page="),
//        Pair("حلقات الأنمي الخاصة", "/titles/list/special?page="),
//    )
//
//    // FIX: Changed function name to be more accurate (fetching a list, not just "popular").
//    suspend fun fetchAnimeList(page: Int): MangaPage = withContext(Dispatchers.IO) {
//        val popularUrl = baseUrl + mainPageSections.first().second + page
//        val request = Request.Builder().url(popularUrl).build()
//        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
//
//        // FIX: The selector was incorrect. List pages use `div.title-card` to hold each anime entry.
//        val animeList = document.select("div.title-card").mapNotNull {
//            toAnime(it)
//        }
//
//        // FIX: Pagination logic seems to be dynamic now. This selector is a guess for server-side pagination if it exists.
//        // The "Load More" button on the homepage is client-side.
//        val hasNextPage = document.selectFirst("a:contains(تحميل المزيد)") != null
//        MangaPage(animeList, hasNextPage)
//    }
//
//    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
//        try {
//            val request = Request.Builder().url(baseUrl).build()
//            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
//            // FIX: The original selector was targeting "Latest Episodes".
//            // This now correctly targets the main slider ("الأنميات المثبتة").
//            document.select("section.relative div.glide li.glide__slide a.video-card").mapNotNull {
//                toEpisodeAnime(it) // Use a helper for episode cards
//            }.take(10)
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
//        // Note: Search is dynamic. This static URL might not provide full results.
//        val url = "$baseUrl/search?q=${query.replace(" ", "+")}"
//        val request = Request.Builder().url(url).build()
//        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
//
//        // FIX: Search results will likely be full anime cards, not episode cards.
//        val animeList = document.select("div.title-card").mapNotNull {
//            toAnime(it)
//        }
//        MangaPage(animeList, hasNextPage = false)
//    }
//
//    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
//        // This function fetches details from the main anime page (e.g., /titles/anne-shirley)
//        val request = Request.Builder().url(animeUrl).build()
//        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
//
//        SAnime().apply {
//            url = animeUrl
//            // FIX: Corrected selectors based on the `title-card` structure, which is likely similar on the details page.
//            // These need to be verified with the actual details page HTML.
//            title = document.selectFirst("h2.title-name")?.text() ?: "Unknown Title"
//            thumbnail_url = document.selectFirst("div.title-card img")?.attr("src")
//            description = document.selectFirst("p.synopsis")?.text()?.trim()
//            genre = document.select("div.genres span").joinToString(", ") { it.text() }
//            // FIX: The original status selector was a good guess but needs verification.
//            status = getStatus(document.select("div.title-card span.badge:contains(موسم)").text())
//            source = AnimeSource.ANIME3RB.name
//        }
//    }
//
//    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        // This function should be called with the main anime page URL, not an episode URL.
//        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
//        val episodes = mutableListOf<SEpisode>()
//
//        // FIX: The site doesn't seem to use a `ul.season-list`. The episodes are listed directly.
//        // FIX: The episode container is `div.videos-list`.
//        val seasonName = document.selectFirst("h2.title-name")?.text() ?: "الموسم 1"
//        document.select("div.videos-list a").forEach { episodeElement ->
//            episodes.add(createEpisode(episodeElement, seasonName))
//        }
//
//        return@withContext episodes.reversed() // Typically, sites list newest first, so we reverse it.
//    }
//
//    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
//        try {
//            val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())
//
//            // FIX: The `x-data` attribute is on the `section` tag.
//            // FIX: The property name is `videoUrl`, not `videoSource`.
//            val section = document.selectFirst("section[x-data*='videoUrl:']")
//            val xData = section?.attr("x-data") ?: return@withContext emptyList()
//            val videoPlayerUrl = Regex("videoUrl:\\s*'([^']+)'").find(xData)?.groupValues?.get(1)
//                ?.replace("\\/", "/") // Unescape slashes
//                ?.replace("\\u0026", "&") ?: return@withContext emptyList() // Unescape ampersands
//
//            // The rest of the logic to parse the player page is highly specific and cannot be
//            // verified without its HTML, but this is a common and plausible pattern.
//            val downloadPage = client.newCall(Request.Builder().url(videoPlayerUrl).build()).execute().body!!.string()
//
//            // FIX: Updated Regex to be more robust for finding download links from the download page.
//            val videoRegex = Regex("""(https://[\w./-]+\.m3u8)[\s\S]+?(\d{3,4})p""")
//            return@withContext videoRegex.findAll(downloadPage).map { match ->
//                val (src, res) = match.destructured
//                Video(url = src, quality = "${res}p", videoUrl = src)
//            }.toList()
//
//        } catch (e: Exception) {
//            e.printStackTrace()
//            return@withContext emptyList()
//        }
//    }
//
//    // FIX: Renamed to `toAnime` and corrected selectors for a `div.title-card` element.
//    private fun toAnime(element: Element): SAnime {
//        val anchor = element.selectFirst("a") ?: return SAnime()
//        return SAnime().apply {
//            url = anchor.attr("abs:href")
//            title = element.selectFirst("h2.title-name")?.text() ?: "Unknown"
//            thumbnail_url = element.selectFirst("img")?.attr("src")
//            source = AnimeSource.ANIME3RB.name
//        }
//    }
//
//    // FIX: Created a new helper for parsing episode cards (`a.video-card`).
//    private fun toEpisodeAnime(element: Element): SAnime {
//        return SAnime().apply {
//            url = element.attr("abs:href")
//            title = element.selectFirst("h3.title-name")?.text() ?: "Unknown"
//            thumbnail_url = element.selectFirst("div.poster img")?.attr("src")
//            source = AnimeSource.ANIME3RB.name
//        }
//    }
//
//    private fun createEpisode(element: Element, seasonName: String): SEpisode {
//        // FIX: The container class is `video-data`, not `video-metadata`.
//        val episodeTitle = element.selectFirst("div.video-data p")?.text()?.ifEmpty {
//            element.selectFirst("div.video-data span")?.text()
//        } ?: "Episode"
//        val episodeNumberText = element.selectFirst("div.video-data span")?.text() ?: ""
//        return SEpisode().apply {
//            url = element.attr("abs:href")
//            name = "$seasonName: $episodeTitle"
//            // FIX: The episode number is inside text like "الحلقة 19". We need to extract the digits.
//            episode_number = Regex("""\d+""").find(episodeNumberText)?.value?.toFloatOrNull() ?: 0f
//        }
//    }
//
//    private fun getStatus(statusString: String): Int {
//        // FIX: Status text is different on the site. Updated cases.
//        return when {
//            statusString.contains("منتهي", ignoreCase = true) -> SAnime.COMPLETED
//            statusString.contains("يعرض الان", ignoreCase = true) || statusString.contains("قيد البث", ignoreCase = true) -> SAnime.ONGOING
//            else -> SAnime.UNKNOWN
//        }
//    }
//
//    fun getFilterList() = AnimeFilterList(emptyList())
//}