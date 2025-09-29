package com.faselhd.app.network.sources

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.NetworkClient
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import com.google.gson.Gson // Make sure you have Gson in your build.gradle (implementation
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URL
import java.util.regex.Pattern
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException

data class WatchServerAjaxResponse(
    val type: String?,
    val html: String?, // This might contain an iframe tag if type is 'html' or 'success' for older formats
    val server: String? // This is the actual embed URL for the player
)
class ArabSeedSource(private val context: Context) {
    companion object {
        const val name = "عرب سيد"
        // Changed to ROOT_URL to distinguish from the dynamic content URL
        const val ROOT_URL = "https://a.asd.homes"
        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

    // --- START: OKHTTP CLIENT SETUP (No changes needed here) ---
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

    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client = NetworkClient.client
    // --- END: OKHTTP CLIENT SETUP ---

    // Video extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val bigWarpExtractor by lazy { BigWarpExtractor(client) }

    // ============================== Dynamic URL Handling ==============================
    // Variable to cache the dynamic URL after it's been resolved.
    @Volatile
    private var dynamicBaseUrl: String? = null

    /**
     * Fetches the root domain, finds the "enter" link, and returns the correct base URL.
     * Caches the result to avoid repeated network calls.
     */
    private suspend fun resolveBaseUrl(): String {
        // Return the cached URL if we already have it.
        dynamicBaseUrl?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                Log.d("ArabSeed", "Resolving dynamic base URL from $ROOT_URL")
                val request = Request.Builder().url(ROOT_URL).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val document = Jsoup.parse(response.body?.string() ?: "", ROOT_URL)
                    // Select the "enter" link to get the main site URL
                    val enterUrl = document.selectFirst("a.enter")?.attr("abs:href")
                    if (!enterUrl.isNullOrBlank() && enterUrl.startsWith("http")) {
                        Log.d("ArabSeed", "Successfully resolved dynamic base URL: $enterUrl")
                        // Cache the result, removing any trailing slash for consistency.
                        dynamicBaseUrl = enterUrl.removeSuffix("/")
                        dynamicBaseUrl!!
                    } else {
                        // Fallback to a previously known working path if the link isn't found
                        Log.w("ArabSeed", "Could not find 'a.enter' link, falling back to default.")
                        dynamicBaseUrl = "$ROOT_URL/main1"
                        dynamicBaseUrl!!
                    }
                } else {
                    // Fallback in case of a network error
                    Log.e("ArabSeed", "Failed to fetch root URL, falling back to default.")
                    "$ROOT_URL/main1"
                }
            } catch (e: Exception) {
                Log.e("ArabSeed", "Could not resolve dynamic base URL due to an exception.", e)
                // Fallback on any other error
                "$ROOT_URL/main1"
            }
        }
    }


    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        // The page number seems to be ignored for the slider, but we keep the structure.
        val request = Request.Builder().url("$baseUrl?page_number=1").build()
        val response = client.newCall(request).execute()
        mainSliderParse(response, baseUrl)
    }

    private fun mainSliderParse(response: Response, baseUrl: String): List<SAnime> {
        val document = Jsoup.parse(response.body?.string() ?: "", baseUrl)
        val sliderContainer = document.selectFirst("div.slider__container")

        if (sliderContainer == null) {
            return emptyList()
        }

        val sliderItems = sliderContainer.select("div.swiper-slide")
        return sliderItems.mapNotNull { slide ->
            val linkElement = slide.selectFirst("a")
            if (linkElement != null) {
                SAnime().apply {
                    url = linkElement.attr("abs:href")
                    title = linkElement.attr("title")
                    thumbnail_url = linkElement.selectFirst("img.images__loader")?.attr("data-src") ?:linkElement.selectFirst("img.images__loader")?.attr("src")
                    // You might also want to extract additional information
                    val category = linkElement.selectFirst("div.post__category")?.text()
                    val rating = linkElement.selectFirst("div.post__ratings")?.text()?.toFloatOrNull()
                }

            } else {
                null
            }
        }
    }

    // ============================== Popular ==============================
    suspend fun fetchPopularSeries(page: Int): MangaPage {
        // The main page acts as the "popular" or "latest" page.
        return fetchLatestUpdates(page)
    }


    // ============================== Latest Episodes ==============================
    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = resolveBaseUrl()
            val request = Request.Builder().url(baseUrl).build()
            println("Sending request to URL: $baseUrl")
            val response = client.newCall(request).execute()
            println("Received response with status: ${response.code}")

            if (!response.isSuccessful) {
                println("Request failed with status: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                println("Response body is null or empty")
                return@withContext emptyList()
            }

            // Log a snippet of the HTML for debugging (first 500 characters)
            println("HTML snippet: ${body.take(500)}")

            latestEpisodesParse(body, baseUrl)
        } catch (e: IOException) {
            println("Network error while fetching latest episodes: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            println("Unexpected error while fetching latest episodes: ${e.message}")
            emptyList()
        }
    }

    private fun latestEpisodesParse(html: String, baseUrl: String): List<SAnime> {
        println("Starting HTML parsing")
        val document = Jsoup.parse(html, baseUrl)

        // Try multiple selectors to find episode items
        val selectors = listOf(
            "div.swiper-wrapper ul.episodes__blocks__holder > a.episode__item",
            "div.swiper-slide ul.episodes__blocks__holder > a.episode__item",
            "a.episode__item" // Fallback to broadest selector
        )

        var episodeItems = emptyList<org.jsoup.nodes.Element>()
        for (selector in selectors) {
            episodeItems = document.select(selector)
            println("Tried selector '$selector': Found ${episodeItems.size} episode items")
            if (episodeItems.isNotEmpty()) break
        }

        if (episodeItems.isEmpty()) {
            println("No episode items found with any selector. HTML may not contain expected structure.")
            // Log the swiper-wrapper content for debugging
            val swiperWrapper = document.selectFirst("div.swiper-wrapper")
            println("Swiper-wrapper content: ${swiperWrapper?.html()?.take(500) ?: "Not found"}")
            return emptyList()
        }

        return episodeItems.mapNotNull { episode ->
            try {
                val url = episode.attr("abs:href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val titleElement = episode.selectFirst("div.episode__title > span")
                val title = titleElement?.text()?.trim()
                    ?: episode.attr("title").takeIf { it.isNotEmpty() }
                    ?: "Unknown Title"
                val thumbnail = episode.selectFirst("img.images__loader")?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: episode.selectFirst("img.images__loader")?.attr("src")?.takeIf { it.isNotEmpty() }

                println("Parsed episode: URL=$url, Title=$title, Thumbnail=$thumbnail")

                SAnime().apply {
                    this.url = url
                    this.title = title
                    this.thumbnail_url = thumbnail
                }
            } catch (e: Exception) {
                println("Error parsing episode item: ${e.message}")
                null
            }
        }.also { parsedEpisodes ->
            println("Successfully parsed ${parsedEpisodes.size} episodes")
        }
    }


    // ============================== Latest Updates ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        val url = "$baseUrl?page_number=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response, baseUrl)
    }

    private fun latestUpdatesParse(response: Response, baseUrl: String): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        val animeElements = document.select("div#ajax__area li .item__contents a")
        val animeList = animeElements.mapNotNull { element ->
            val isEpisode = element.hasClass("is__episode")
            val title = element.attr("title")
            val url = element.attr("abs:href")
            val imageUrl = element.selectFirst("div.post__image img")?.attr("data-src")
            if (title.isNullOrEmpty() || url.isNullOrEmpty() || imageUrl.isNullOrEmpty()) {
                null
            } else {
                SAnime().apply {
                    this.url = url
                    this.title = title.replace("انمي ", "").replace("مسلسل ", "")
                        .replace("فيلم ", "").replace("الحلقة", "E").replace("الموسم", "S")
                    this.thumbnail_url = imageUrl
                }
            }
        }
        val hasNextPage = document.select("a.next.page-numbers").isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Details ==============================
    // No changes needed here as it takes a full URL.
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response, animeUrl)
    }

    private fun animeDetailsParse(response: Response, animeUrl: String): SAnime {
        val document = Jsoup.parse(response.body!!.string(), animeUrl)
        return SAnime().apply {
            url = animeUrl
            thumbnail_url = document.selectFirst("div.poster__side div.poster__single img")?.attr("data-src") ?:document.selectFirst("div.poster__side div.poster__single img")?.attr("src")
            title = document.selectFirst("h1.post__name")?.text()?.substringBefore(" الحلقة") ?: ""
            genre = document.select("ul.info__area__ul li:has(span:contains(نوع العرض)) a").joinToString { it.text() }
            description = document.selectFirst("div.post__story p")?.text() ?: ""
            status = if (animeUrl.contains("/selary/")) SAnime.ONGOING else SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    // No changes needed here as it takes a full URL.
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        episodeListParse(response, animeUrl)
    }

    private fun episodeListParse(response: Response, animeUrl: String): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), animeUrl)
        val episodeElements = document.select("ul.episodes__list li a")
        return if (episodeElements.isNotEmpty()) {
            episodeElements.map { element ->
                SEpisode().apply {
                    url = element.attr("abs:href")
                    name = element.selectFirst("div.epi__num")?.text() ?: "الحلقة"
                    episode_number = element.selectFirst("div.epi__num b")?.text()?.toFloatOrNull() ?: 1f
                    thumbnailUrl = document.selectFirst("div.poster__side div.poster__single img")?.attr("data-src") ?:document.selectFirst("div.poster__side div.poster__single img")?.attr("src")
                }
            }.reversed() // Reverse to show oldest first
        } else {
            // This is for movies, which don't have an episode list
            listOf(
                SEpisode().apply {
                    url = animeUrl
                    name = "مشاهدة الفيلم"
                    episode_number = 1f
                    thumbnailUrl = document.selectFirst("div.poster__side div.poster__single img")?.attr("data-src") ?:document.selectFirst("div.poster__side div.poster__single img")?.attr("src")

                }
            )
        }
    }

    // ============================== Video Links ==============================
    // No changes needed in this section as it operates on full URLs passed to it.
    private fun parseCsrfToken(html: String): String? {
        val regex = Regex("""'csrf__token':\s*"([^"]+)"""")
        return regex.find(html)?.groups?.get(1)?.value
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d("ArabSeed", "Fetching videos for episode: $episodeUrl")
        try {
            val episodeRequest = Request.Builder().url(episodeUrl).build()
            val episodeResponse = client.newCall(episodeRequest).execute()
            if (!episodeResponse.isSuccessful) return@withContext emptyList()
            val episodeDoc = Jsoup.parse(episodeResponse.body!!.string(), episodeUrl)

            val watchUrl = episodeDoc.selectFirst("a.watch__btn")?.attr("abs:href")
            if (watchUrl.isNullOrEmpty()) return@withContext emptyList()
            Log.d("ArabSeed", "Found watch page URL: $watchUrl")

            val watchRequest = Request.Builder().url(watchUrl).header("Referer", episodeUrl).build()
            val watchResponse = client.newCall(watchRequest).execute()
            if (!watchResponse.isSuccessful) return@withContext emptyList()
            val watchPageHtml = watchResponse.body!!.string()
            val watchDoc = Jsoup.parse(watchPageHtml, watchUrl)

            val csrfToken = parseCsrfToken(watchPageHtml)
            val postId = watchDoc.selectFirst(".servers__list li[data-post]")?.attr("data-post")
            val ajaxBaseUrl = URL(watchUrl).let { "${it.protocol}://${it.host}" }

            if (csrfToken == null || postId == null) {
                Log.e("ArabSeed", "CSRF/PostID not found. Cannot perform AJAX calls.")
                return@withContext emptyList()
            }

            val videos = mutableListOf<Video>()
            val qualityElements = watchDoc.select(".qualities__list li[data-quality]")

            for (qualityElement in qualityElements) {
                val quality = qualityElement.attr("data-quality")
                val qualityName = qualityElement.selectFirst("em")?.text() ?: quality
                Log.d("ArabSeed", "--- Processing Quality: $qualityName ---")

                val qualityFormBody = FormBody.Builder().add("post_id", postId).add("quality", quality).add("csrf_token", csrfToken).build()
                val qualityAjaxUrl = "$ajaxBaseUrl/get__quality__servers/"
                val qualityAjaxRequest = Request.Builder().url(qualityAjaxUrl).post(qualityFormBody).header("Referer", watchUrl).header("X-Requested-With", "XMLHttpRequest").build()

                try {
                    val qualityResponse = client.newCall(qualityAjaxRequest).execute()
                    val newServersHtml = JSONObject(qualityResponse.body!!.string()).optString("html")

                    if (newServersHtml.isNotBlank()) {
                        val newServerElements = Jsoup.parse(newServersHtml).select("li")

                        for (serverElement in newServerElements) {
                            val serverId = serverElement.attr("data-server")
                            val serverName = serverElement.selectFirst("span")?.text() ?: "Server"
                            val serverFormBody = FormBody.Builder().add("post_id", postId).add("quality", quality).add("server", serverId).add("csrf_token", csrfToken).build()
                            val serverAjaxUrl = "$ajaxBaseUrl/get__watch__server/"
                            val serverAjaxRequest = Request.Builder().url(serverAjaxUrl).post(serverFormBody).header("Referer", watchUrl).header("X-Requested-With", "XMLHttpRequest").build()

                            val serverResponse = client.newCall(serverAjaxRequest).execute()
                            val embedUrl = JSONObject(serverResponse.body!!.string()).optString("server")

                            if (embedUrl.startsWith("http")) {
                                Log.d("ArabSeed", "Success for $serverName ($qualityName). Got embed URL: $embedUrl")
                                videos.addAll(extractVideosFromUrl(embedUrl, "ArabSeed server - $qualityName").reversed())
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ArabSeed", "AJAX call failed for quality '$qualityName'.", e)
                }
            }
            return@withContext videos.distinctBy { it.url }

        } catch (e: Exception) {
            Log.e("ArabSeed", "A critical error occurred in fetchVideoList", e)
            return@withContext emptyList()
        }
    }

    private suspend fun extractVideosFromUrl(url: String, qualityLabel: String): List<Video> {
        return try {
            when {
                "embed" in url  -> extractVideoFromIframe(url, qualityLabel)
                "vidmoly" in url -> vidmolyExtractor.videosFromUrl(url)
                "voe.sx" in url -> voeExtractor.videosFromUrl(url)
                "dood" in url || "d-s.io" in url-> doodExtractor.videosFromUrl(url, qualityLabel)
                "filemoon" in url || "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, qualityLabel)
                "bigwarp" in url -> {
                    println("DEBUG: Processing BigWarp URL: $url")
                    val result = bigWarpExtractor.videosFromUrl(url)
                    println("DEBUG: BigWarp extraction result: ${result.size} videos found")
                    result
                }
                else -> {
                    Log.w("ArabSeed", "No extractor available for URL: $url")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("ArabSeed", "Extractor failed for URL: $url", e)
            emptyList()
        }
    }

    private suspend fun extractVideoFromIframe(iframeUrl: String, qualityLabel: String): List<Video> {
        try {
            val referer = URL(iframeUrl).let { "${it.protocol}://${it.host}/" }
            val request = Request.Builder().url(iframeUrl).header("Referer", referer).build()
            val response = client.newCall(request).execute()
            val htmlContent = response.body!!.string()
            val iframeDoc = Jsoup.parse(htmlContent)

            val sourceRegex = Pattern.compile("""['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""")
            val matcher = sourceRegex.matcher(htmlContent)

            val videoUrl = iframeDoc.selectFirst("source[src]")?.attr("src")
                ?: if (matcher.find()) matcher.group(1) else null

            return if (!videoUrl.isNullOrEmpty()) {
                Log.d("ArabSeed", "Successfully extracted video URL: $videoUrl")
                listOf(
                    Video(
                        url = videoUrl,
                        quality = qualityLabel,
                        videoUrl = videoUrl,
                        headers = mapOf("Referer" to referer)
                    )
                )
            } else {
                Log.w("ArabSeed", "Could not find video URL pattern in iframe: $iframeUrl")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ArabSeed", "Failed to extract from iframe URL: $iframeUrl", e)
            return emptyList()
        }
    }

    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Search path is usually relative to the root domain, not the dynamic content page
        val url = "$ROOT_URL/find/?word=$encodedQuery"

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response)
    }

    private fun searchParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), ROOT_URL)
        val animeElements = document.select("div.series__list ul.blocks__ul li a")
        val animeList = animeElements.map { element ->
            SAnime().apply {
                this.url = element.attr("abs:href")
                this.title = element.attr("title")
                this.thumbnail_url = element.selectFirst("div.post__image img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
            }
        }
        return MangaPage(animeList, hasNextPage = false)
    }

    // ============================== Filters ==============================
    // No changes needed.
    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        listOf(
            AnimeFilter.Header("الفلاتر تعمل فقط عند ترك البحث فارغاً"),
            TypeFilter()
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TypeFilter : UriPartFilter(
        "التصنيف",
        arrayOf(
            Pair("أختر", ""),
            Pair("افلام اجنبي", "category/foreign-movies-6/"),
            Pair("افلام عربي", "category/arabic-movies-5/"),
            Pair("افلام هندى", "category/indian-movies/"),
            Pair("افلام اسيوية", "category/asian-movies/"),
            Pair("افلام تركية", "category/turkish-movies/"),
            Pair("افلام انيميشن", "category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/"),
            Pair("مسلسلات اجنبي", "category/foreign-series-2/"),
            Pair("مسلسلات عربي", "category/arabic-series-2/"),
            Pair("مسلسلات تركيه", "category/turkish-series-2/"),
            Pair("مسلسلات مصريه", "category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%85%d8%b5%d8%b1%d9%8a%d9%87/"),
            Pair("مسلسلات هندية", "category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d8%a9/"),
            Pair("مسلسلات كرتون", "category/cartoon-series/")
        )
    )
}