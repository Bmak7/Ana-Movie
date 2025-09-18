package com.faselhd.app.network.sources

import StreamGHExtractor
import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R // Assuming this is your R file
import com.faselhd.app.models.*
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.CookieManager
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FiveTvSource(private val context: Context) {
    companion object {
        const val name = "5tv"
        const val BASE_URL = "https://5tv.center"
        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

    // --- START: OKHTTP CLIENT SETUP (Reused from ArabSeedSource) ---
    private val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        val cookieJar = object : CookieJar {
            private val cookieStore = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        }

        OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(CookieManager())) // This automatically handles cookies
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            .build()
    }
    // --- END: OKHTTP CLIENT SETUP ---

    // --- Video Extractors ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }


    // ============================== Main Slider ==============================

    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting main slider fetch from URL: $BASE_URL")

        val request = Request.Builder()
            .url(BASE_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body?.string() ?: "", BASE_URL)
        val animeList = mutableListOf<SAnime>()

        // 1. Find the script tag containing the slider's JSON data
        val scriptElement = document.select("script").find { script ->
            script.data().contains("SR7.JSON['SR7_3_1']")
        }

        if (scriptElement == null) {
            println("DEBUG: Could not find the script tag containing slider JSON data.")
            return emptyList()
        }
        println("DEBUG: Found the slider data script tag.")

        // 2. Extract the JSON string from the script's content using a regular expression
        val scriptContent = scriptElement.data()
        val pattern = Regex("""SR7\.JSON\['SR7_3_1']\s*=\s*(\{.*\});""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = pattern.find(scriptContent)
        val jsonString = matchResult?.groups?.get(1)?.value

        if (jsonString == null) {
            println("DEBUG: Failed to extract JSON string from the script tag.")
            return emptyList()
        }
        println("DEBUG: Successfully extracted JSON string.")

        // 3. Parse the JSON string to access the slider data
        try {
            val rootJson = JSONObject(jsonString)
            val slides = rootJson.getJSONObject("slides")
            val slideKeys = slides.keys()

            println("DEBUG: Found ${slides.length()} slides in the JSON data.")

            // 4. Iterate through each slide in the JSON and extract its details
            for (key in slideKeys) {
                val slideObject = slides.optJSONObject(key) ?: continue
                val slideData = slideObject.optJSONObject("slide") ?: continue

                // Skip non-published or global slides
                if (slideData.optBoolean("global", false) ||
                    slideData.optJSONObject("publish")?.optString("state") != "published") {
                    continue
                }

                // Get the URL from the "actions" array
                val url = slideData.optJSONArray("actions")?.optJSONObject(0)?.optString("link", "") ?: ""

                // Get the thumbnail URL from the "layers" object
                val layers = slideObject.optJSONObject("layers") ?: continue
                var thumbnailUrl = ""

                // Find the image layer to get the background image source
                layers.keys().forEach { layerKey ->
                    val layer = layers.optJSONObject(layerKey)
                    if (layer?.optString("subtype") == "image") {
                        thumbnailUrl = layer.optJSONObject("bg")?.optJSONObject("image")?.optString("src", "") ?: ""
                    }
                }

                if (url.isNotEmpty() && thumbnailUrl.isNotEmpty()) {
                    val anime = SAnime().apply {
                        this.url = url
                        this.thumbnail_url = thumbnailUrl
                        // Create a readable title from the URL
                        this.title = url.trimEnd('/').substringAfterLast('/')
                            .replace("-", " ")
                            .let { URLDecoder.decode(it, "UTF-8") }
                    }
                    animeList.add(anime)
                    println("DEBUG:  -> SUCCESS: Parsed '${anime.title}'")
                } else {
                    println("DEBUG:  -> SKIPPED slide with key '$key': Missing URL or Thumbnail.")
                }
            }
        } catch (e: Exception) {
            println("DEBUG: An error occurred during JSON parsing: ${e.message}")
        }

        println("DEBUG: Successfully parsed ${animeList.size} valid slider items from JSON.")
        return animeList
    }

    // ============================== Generic Parser ==============================
    private fun parseAnimeFromElement(element: Element): SAnime {
        return SAnime().apply {
            val titleElement = element.selectFirst("h2.entry-title")
            url = element.selectFirst("a.lnk-blk")?.attr("href") ?: ""
            title = titleElement?.text() ?: "Unknown Title"
            thumbnail_url = element.selectFirst("figure img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        }
    }


    // ============================== Popular ==============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // 5tv uses the main page for popular content, pagination isn't standard
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body?.string() ?: "", BASE_URL)

        val popularDramas = document.select("#widget_list_movies_series-6 li.series").map { parseAnimeFromElement(it) }
        val popularMovies = document.select("#widget_list_movies_series-4 li.movies").map { parseAnimeFromElement(it) }

        MangaPage(popularDramas + popularMovies, false)
    }


    // ============================== Latest Episodes ==============================
    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body?.string() ?: "", BASE_URL)

        val episodeItems = document.select("#widget_list_episodes-19 li article.episodes")
        return@withContext episodeItems.mapNotNull { item ->
            SAnime().apply {
                url = item.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
                title = item.selectFirst("h2.entry-title")?.text() ?: "Unknown Episode"
                thumbnail_url = item.selectFirst("figure img")?.attr("data-src")
            }
        }
    }


    // ============================== Latest Updates ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // The "new" sections on the homepage are the best source for latest updates.
        // We'll combine movies and series.
        if (page > 1) return@withContext MangaPage(emptyList(), false)

        val request = Request.Builder().url(BASE_URL).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body?.string() ?: "", BASE_URL)

        val newMovies = document.select("#widget_list_movies_series-5 li.movies").map { parseAnimeFromElement(it) }
        val newSeries = document.select("#widget_list_movies_series-7 li.series").map { parseAnimeFromElement(it) }

        MangaPage(newMovies + newSeries, false)
    }

    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response, animeUrl)
    }

    private fun animeDetailsParse(response: Response, animeUrl: String): SAnime {
        val document = Jsoup.parse(response.body!!.string(), animeUrl)
        return SAnime().apply {
            url = animeUrl
            title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("الحلقة")?.trim() ?: ""
            thumbnail_url = document.selectFirst("div.post-thumbnail figure img")?.attr("data-src") ?:document.selectFirst("div.post-thumbnail figure img")?.attr("src")
            description = document.selectFirst("div.description p")?.text() ?: ""
            genre = document.select("span.genres a").joinToString(", ") { it.text() }
            status = if (document.selectFirst("span.5tv-meta:contains(منتهية)") != null) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    // ============================== Episodes ==============================
    /**
     * Fetches the list of episodes for a given anime URL, handling all seasons
     * by simulating the website's AJAX calls with correct parameters and cookies.
     */
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        println("DEBUG: Starting multi-season episode fetch for URL: $animeUrl")

        val mainPageRequest = Request.Builder()
            .url(animeUrl)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
            .build()

        // This first call will now store the necessary cookies in our client's cookie jar
        val mainPageResponse = client.newCall(mainPageRequest).execute()
        fetchAllSeasonsEpisodeParse(mainPageResponse, animeUrl)
    }

    private fun fetchAllSeasonsEpisodeParse(response: Response, originalUrl: String): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val allEpisodes = mutableListOf<SEpisode>()

        val initialSeasonName = document.selectFirst("div.choose-season dt.n_s")?.let { "الموسم ${it.text()}" } ?: "الموسم 1"
        val initialEpisodeElements = document.select("ul#episode_by_temp li article.episodes")
        println("DEBUG: Found ${initialEpisodeElements.size} episodes for the initial season ('$initialSeasonName') on the main page.")
        initialEpisodeElements.forEach { element ->
            allEpisodes.add(episodeFromElement(element, initialSeasonName))
        }

        val seasonElements = document.select("ul.aa-cnt.sub-menu li.sel-temp a")
        println("DEBUG: Found ${seasonElements.size} total season tabs.")

        if (seasonElements.size > 1) {
            seasonElements.drop(1).forEach { seasonElement ->
                val postId = seasonElement.attr("data-post")
                val seasonNum = seasonElement.attr("data-season")
                val seasonName = seasonElement.text()
                println("DEBUG: --- Making AJAX call for Season: '$seasonName' (Post ID: $postId, Season Num: $seasonNum) ---")

                val ajaxUrl = "https://5tv.center/wp-admin/admin-ajax.php"

                val formBody = FormBody.Builder()
                    .add("action", "action_select_season")
                    .add("season", seasonNum)
                    // CORRECTED: Changed parameter name from "id" to "post"
                    .add("post", postId)
                    .build()

                val ajaxRequest = Request.Builder()
                    .url(ajaxUrl)
                    .post(formBody)
                    // Using headers from your Burp Suite log for perfect imitation
                    .addHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                    .addHeader("Accept", "*/*")
                    .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Origin", "https://5tv.center")
                    .addHeader("Referer", originalUrl)
                    .build()

                try {
                    // This call will now automatically include the cookies from the first request
                    val ajaxResponse = client.newCall(ajaxRequest).execute()
                    if (!ajaxResponse.isSuccessful) {
                        println("DEBUG: AJAX call for season $seasonNum failed with code ${ajaxResponse.code}")
                        return@forEach
                    }

                    val seasonHtmlSnippet = ajaxResponse.body!!.string()
                    val seasonDocument = Jsoup.parseBodyFragment(seasonHtmlSnippet)
                    val episodeElements = seasonDocument.select("li article.episodes")
                    println("DEBUG: AJAX response for Season $seasonNum contained ${episodeElements.size} episodes.")

                    episodeElements.forEach { element ->
                        allEpisodes.add(episodeFromElement(element, seasonName))
                    }
                } catch (e: Exception) {
                    println("ERROR: Failed to fetch or parse episodes for season $seasonNum. Error: ${e.message}")
                }
            }
        } else if (allEpisodes.isEmpty()) {
            println("DEBUG: No seasons found and no initial episodes. Assuming it's a movie.")
            allEpisodes.add(
                SEpisode().apply {
                    url = originalUrl
                    name = "مشاهدة الفيلم"
                    episode_number = 1f
                }
            )
        }

        println("DEBUG: Total episodes parsed from all seasons: ${allEpisodes.size}. Reversing list for correct order.")
        return allEpisodes
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode().apply {
            url = element.selectFirst("a.lnk-blk")!!.attr("href")

            val title = element.selectFirst("h2.entry-title")?.text() ?: "حلقة"
            name = "$seasonName: $title"

            val epNumText = element.selectFirst("span.num-epi")?.text() ?: "1"
            episode_number = epNumText.substringAfter('x', epNumText).trim().toFloatOrNull() ?: 1.0f
            thumbnailUrl = element.selectFirst("figure img")?.let { img ->
                // Prioritize data-src, then src
                val dataSrc = img.attr("abs:data-src")
                val src = img.attr("abs:src")
                if (dataSrc.isNotBlank()) dataSrc else src
            } ?: "No Thumbnail Found"
        }
    }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        println("\n[DEBUG] fetchVideoList: Starting video extraction for URL -> $episodeUrl")

        try {
            // Step 1 & 2: Get the first form's data from the initial episode page. (This is already correct)
            println("[DEBUG] Step 1-2: Fetching initial form data...")
            val episodeRequest = Request.Builder().url(episodeUrl).build()
            val episodeResponse = client.newCall(episodeRequest).execute()
            if (!episodeResponse.isSuccessful) return@withContext emptyList()
            val episodeDoc = Jsoup.parse(episodeResponse.body!!.string(), episodeUrl)

            val firstForm = episodeDoc.selectFirst("form[action*=gogo]") ?: return@withContext emptyList()
            val firstActionUrl = firstForm.attr("action")
            val firstServersData = firstForm.selectFirst("textarea[name=servers]")?.`val`()
            if (firstActionUrl.isNullOrBlank() || firstServersData.isNullOrBlank()) return@withContext emptyList()
            println("[DEBUG] Step 1-2 SUCCESS.")

            // Step 3: Perform the FIRST POST to get the intermediate page.
            println("[DEBUG] Step 3: Performing first POST...")
            val firstFormBody = FormBody.Builder().add("servers", firstServersData).build()
            val intermediatePageRequest = Request.Builder()
                .url(firstActionUrl)
                .post(firstFormBody)
                .header("Referer", episodeUrl)
                .build()
            val intermediatePageResponse = client.newCall(intermediatePageRequest).execute()
            if (!intermediatePageResponse.isSuccessful) {
                println("[DEBUG] Step 3 FAILED.")
                return@withContext emptyList()
            }
            println("[DEBUG] Step 3 SUCCESS.")
            val intermediatePageDoc = Jsoup.parse(intermediatePageResponse.body!!.string(), firstActionUrl)

            // --- NEW LOGIC STARTS HERE ---

            // Step 3.5: Extract the data from the SECOND form on the intermediate page.
            println("[DEBUG] Step 3.5: Extracting data from the second form...")
            val secondForm = intermediatePageDoc.selectFirst("form[action]")
            if (secondForm == null) {
                println("[DEBUG] Step 3.5 FAILED: Could not find the second form.")
                return@withContext emptyList()
            }

            // Use abs:href to resolve the action URL correctly relative to the intermediate page
            val secondActionUrl = secondForm.attr("abs:action")
            val secondServersData = secondForm.selectFirst("textarea[name=servers]")?.`val`()

            if (secondActionUrl.isNullOrBlank() || secondServersData.isNullOrBlank()) {
                println("[DEBUG] Step 3.5 FAILED: Data in second form is missing.")
                return@withContext emptyList()
            }
            println("[DEBUG]  -> Second Action URL: $secondActionUrl")
            println("[DEBUG] Step 3.5 SUCCESS.")

            // Step 4: Perform the SECOND POST to get the final page with server links.
            println("[DEBUG] Step 4: Performing second POST to get final server list...")
            val secondFormBody = FormBody.Builder().add("servers", secondServersData).build()
            val finalPageRequest = Request.Builder()
                .url(secondActionUrl)
                .post(secondFormBody)
                // The referer for this request should be the URL of the intermediate page
                .header("Referer", firstActionUrl)
                .build()
            val finalPageResponse = client.newCall(finalPageRequest).execute()

            if (!finalPageResponse.isSuccessful) {
                println("[DEBUG] Step 4 FAILED: Second POST request failed.")
                return@withContext emptyList()
            }
            println("[DEBUG] Step 4 SUCCESS.")
            val finalPageDoc = Jsoup.parse(finalPageResponse.body!!.string(), secondActionUrl)

            // Log the final HTML so we can create the correct selector
            println("[DEBUG] FINAL PAGE HTML:\n${finalPageDoc.html()}")

            // Step 5: Extract the server links from the FINAL page.
            // ======================= YOUR ACTION REQUIRED HERE =======================
            // Now, you must inspect the "FINAL PAGE HTML" to create the correct selector.
            // The old selector is almost certainly wrong.
            // =======================================================================
            println("[DEBUG] Step 5: Extracting server links from final page...")

            // 1. MODIFY THIS SELECTOR
            val selector = "div.server--name[data-url]" // Replace this!
            val serverElements = finalPageDoc.select(selector)

            println("[DEBUG]  -> Found ${serverElements.size} elements with selector '$selector'.")
            if (serverElements.isEmpty()) {
                println("[DEBUG] Step 5 FAILED: Selector found no elements on the final page.")
                return@withContext emptyList()
            }

            // 2. MODIFY THIS ATTRIBUTE
            val embedUrls = serverElements.mapNotNull { it.attr("data-url") /* Replace this! */ }
            println("[DEBUG] Step 5 SUCCESS: Extracted ${embedUrls.size} URLs.")

            // Step 6: Process the extracted URLs (this part remains the same).
            println("[DEBUG] Step 6: Processing embed URLs...")
            val videos = mutableListOf<Video>()
            for (url in embedUrls) {
                videos.addAll(extractVideosFromUrl(url))
            }

            val uniqueVideos = videos.distinctBy { it.url }
            println("[DEBUG] fetchVideoList: Finished. Returning ${uniqueVideos.size} unique videos.")
            return@withContext uniqueVideos

        } catch (e: Exception) {
            println("[DEBUG] fetchVideoList FAILED with exception: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
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
    /**
     * Processes a single embed URL (e.g., from Dood, StreamWish) and returns a list of final, playable video links.
     */
    private suspend fun extractVideosFromUrl(url: String): List<Video> {
        println("[DEBUG]   extractVideosFromUrl: Trying to extract from -> $url")
        val quality = try { URL(url).host } catch (e: Exception) { "Unknown Host" }

        return try {
            when {
                "ultra4vid" in url -> {
                    println("[DEBUG]     -> Using Ultra4Vid extractor.")
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        println("[DEBUG]     -> Ultra4Vid extractor FAILED: HTTP ${response.code}")
                        return emptyList()
                    }
                    val doc = Jsoup.parse(response.body!!.string())
                    // Select the video tag and get the URL from the 'data-link' attribute
                    val videoUrl = doc.selectFirst("video#playbob-video[data-link]")?.attr("data-link")

                    if (videoUrl.isNullOrBlank()) {
                        println("[DEBUG]     -> Ultra4Vid extractor FAILED: Could not find video URL in data-link attribute.")
                        emptyList()
                    } else {
                        println("[DEBUG]     -> Ultra4Vid extractor found source: $videoUrl")
                        listOf(Video(videoUrl, "Ultra4Vid", videoUrl))
                    }
                }

                "vk.com" in url -> {
                    println("[DEBUG]     -> Using VK extractor.")
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
                    if (!response.isSuccessful) {
                        println("[DEBUG]     -> VK extractor FAILED: HTTP ${response.code}")
                        return emptyList()
                    }
                    val html = response.body!!.string()

                    // Regex to find the entire playerParams JSON object
                    val playerParamsRegex = """var playerParams = (\{.*\});""".toRegex()
                    val playerParamsMatch = playerParamsRegex.find(html)
                    val jsonString = playerParamsMatch?.groups?.get(1)?.value

                    if (jsonString.isNullOrBlank()) {
                        println("[DEBUG]     -> VK extractor FAILED: Could not find playerParams JSON.")
                        return emptyList()
                    }

                    val videos = mutableListOf<Video>()
                    // Regex to find all "urlXXX" keys and their values
                    val urlRegex = """"url(\d+)"\s*:\s*"([^"]+)"""".toRegex()

                    urlRegex.findAll(jsonString).forEach { match ->
                        val quality = match.groups[1]?.value
                        var videoUrl = match.groups[2]?.value

                        if (quality != null && !videoUrl.isNullOrBlank()) {
                            // Unescape the URL (e.g., "https:\/\/..." -> "https://...")
                            videoUrl = videoUrl.replace("\\/", "/")
                            println("[DEBUG]     -> VK extractor found source: [${quality}p] $videoUrl")
                            videos.add(Video(videoUrl, "VK ${quality}p", videoUrl))
                        }
                    }

                    // Return the list sorted from highest to lowest quality
                    videos.sortedByDescending { it.quality.filter(Char::isDigit).toIntOrNull() ?: 0 }
                }

                "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url -> {
                    println("[DEBUG]     -> Using Dood extractor.")
                    doodExtractor.videosFromUrl(url, quality)
                }
                "streamwish" in url || "streamvid" in url -> {
                    println("[DEBUG]     -> Using StreamWish extractor.")
                    streamWishExtractor.videosFromUrl(url)
                }
                "voe.sx" in url -> {
                    println("[DEBUG]     -> Using Voe extractor.")
                    voeExtractor.videosFromUrl(url)
                }
                "vidmoly" in url -> {
                    println("[DEBUG]     -> Using Vidmoly extractor.")
                    vidmolyExtractor.videosFromUrl(url)
                }
                url.contains("movearnpre") || url.contains("vidhi") || url.contains("/v/") || url.contains("bingezove") || url.contains("mivalyo") || url.contains("mivalyo.com") -> {
                    println("mivalyo mivalyo url: $url")

                    println("[DEBUG]     -> Using Vidmoly extractor.")
                    mivalyoExtractor.videosFromUrl(url)
                }

                url.contains("hglink") || url.contains("hglink.to") -> {
                    println("DEBUG: Processing Hglink URL: $url")
                    val extractedId = extractHglinkId(url)
                    println("DEBUG: Extracted Hglink ID: $extractedId")
                    val haxloppdUrl = "https://haxloppd.com/$extractedId"
                    println("DEBUG: Haxloppd URL: $haxloppdUrl")
                    val result = haxloppdExtractor.videosFromUrl(haxloppdUrl)
                    println("DEBUG: Haxloppd extraction result: ${result.size} videos found")
                    result
                }

                "filemoon" in url -> {
                    println("[DEBUG]     -> Using Filemoon extractor.")
                    filemoonExtractor.videosFromUrl(url, quality)
                }
                else -> {
                    println("[DEBUG]     -> Using Generic iframe extractor as a fallback.")
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        println("[DEBUG]     -> Generic extractor FAILED: HTTP ${response.code}")
                        return emptyList()
                    }
                    val doc = Jsoup.parse(response.body!!.string())
                    doc.select("source[src]").map {
                        val src = it.attr("src")
                        println("[DEBUG]     -> Generic extractor found source: $src")
                        Video(src, quality, src)
                    }
                }
            }
        } catch (e: Exception) {
            println("[DEBUG]   extractVideosFromUrl FAILED for '$url': An exception occurred -> ${e.message}")
            emptyList()
        }
    }

    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        // Encode the query to be safely used in a URL.
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/page/$page/?s=$encodedQuery"

        println("[DEBUG] fetchSearchAnime: Requesting URL -> $url")

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            println("[DEBUG] fetchSearchAnime: Response Code -> ${response.code}")

            if (!response.isSuccessful) {
                println("[DEBUG] fetchSearchAnime: Request failed or was not successful.")
                return@withContext MangaPage(emptyList(), false) // Return empty result on failure
            }

            // Pass the response body string to the parser
            val responseBody = response.body!!.string()
            // It's crucial to close the response body after reading it to avoid memory leaks.
            // Since we've read it into a string, the response object can be considered done with.

            // Log a snippet of the HTML to verify we got something meaningful
            val bodySnippet = responseBody.take(500)
            println("[DEBUG] fetchSearchAnime: Received HTML snippet -> \n$bodySnippet\n")

            searchParse(responseBody)

        } catch (e: Exception) {
            println("[DEBUG] fetchSearchAnime: An exception occurred -> ${e.message}")
            e.printStackTrace()
            return@withContext MangaPage(emptyList(), false) // Return empty result on error
        }
    }

    /**
     * Parses the main search page to extract a list of anime and pagination info.
     */
    private fun searchParse(responseBody: String): MangaPage {
        // The BASE_URL is passed as the second argument to resolve relative URLs correctly
        val document = Jsoup.parse(responseBody, BASE_URL)
        println("[DEBUG] searchParse: Successfully parsed the HTML document.")

        // --- SOLUTION ---
        // The old selector was 'div#movies-a > ul.post-lst > li.post' which was incorrect.
        // The new selector targets <li> elements where the 'id' attribute starts with "post-".
        val selector = "div#movies-a > ul.post-lst > li[id^='post-']"

        val animeElements = document.select(selector)
        println("[DEBUG] searchParse: Found ${animeElements.size} elements with selector '$selector'.")

        if (animeElements.isEmpty()) {
            println("[DEBUG] searchParse: Selector found no matching elements. The website structure may have changed.")
        }

        val animeList = animeElements.mapNotNull { element ->
            parseAnimeFromElementSearch(element)
        }

        // Check if a "next" page link exists in the pagination navigation.
        val hasNextPage = document.selectFirst("nav.pagination a.next") != null
        println("[DEBUG] searchParse: Does next page exist? -> $hasNextPage")
        println("[DEBUG] searchParse: Successfully parsed ${animeList.size} items.")

        return MangaPage(animeList, hasNextPage)
    }

    /**
     * Parses a single <li> element to extract SAnime details.
     */
    private fun parseAnimeFromElementSearch(element: Element): SAnime? {
        println("-----------------------------------------------------")
        println("[DEBUG] parseAnimeFromElementSearch: Processing element HTML -> \n${element.outerHtml().take(400)}...")

        try {
            return SAnime().apply {
                val article = element.selectFirst("article.post")!!

                // Title is in the h2 element.
                title = article.selectFirst("h2.entry-title")?.text() ?: "No Title Found"
                println("[DEBUG]  -> Parsed Title: '$title'")

                // URL is in the <a> tag that covers the element.
                url = article.selectFirst("a.lnk-blk")?.attr("abs:href") ?: "No URL Found"
                println("[DEBUG]  -> Parsed URL: '$url'")

                // Thumbnail logic
                thumbnail_url = article.selectFirst("figure img")?.let { img ->
                    // Prioritize data-src, then src
                    val dataSrc = img.attr("abs:data-src")
                    val src = img.attr("abs:src")
                    if (dataSrc.isNotBlank()) dataSrc else src
                } ?: "No Thumbnail Found"
                println("[DEBUG]  -> Parsed Thumbnail: '$thumbnail_url'")

                // Status logic
                status = when {
                    element.hasClass("type-series") -> SAnime.ONGOING
                    element.hasClass("type-movies") -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
                println("[DEBUG]  -> Parsed Status: '$status'")
            }
        } catch (e: Exception) {
            println("[DEBUG] parseAnimeFromElementSearch: FAILED to parse an element. Error -> ${e.message}")
            return null // Return null so mapNotNull filters this failed item out
        }
    }


    // ============================== Filters ==============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        emptyList()
    )
}