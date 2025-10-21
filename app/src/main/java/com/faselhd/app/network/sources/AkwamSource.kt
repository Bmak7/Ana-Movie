package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AkwamSource(private val context: Context) {
    companion object {
        const val name = "Akwam"
        const val BASE_URL = "https://ak.sv"
        const val lang = "ar"
        const val supportsLatest = true
        private const val TAG = "AkwamSource"
    }

    // --- Standard Client Setup ---
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                val dns = PreferenceManager.getDefaultSharedPreferences(context).getInt(context.getString(R.string.dns_pref), 0)
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

    // --- Extractors ---
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client) }
    // Add other extractors if needed

    // ============================== Main Slider ==============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL/main").build()
        val response = client.newCall(request).execute()
        mainSliderParse(response)
    }

    private fun mainSliderParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val sliderItems = document.select(".widget-3 .swiper-slide .entry-box-1")
        return sliderItems.mapNotNull { element ->
            try {
                animeFromElement(element)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing slider item", e)
                null
            }
        }
    }

    // ============================== Popular / Latest ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/recent?page=$page"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    suspend fun fetchPopularSeries(page: Int): MangaPage {
        if (page > 1) return MangaPage(emptyList(), false)
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$BASE_URL/main").build()
                val response = client.newCall(request).execute()
                MangaPage(mainSliderParse(response), false)
            } catch (e: Exception) {
                MangaPage(emptyList(), false)
            }
        }
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select(".widget[data-grid] .entry-box-1")
        val animeList = animeElements.mapNotNull { element ->
            try {
                animeFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = document.select("ul.pagination a[rel=next]").isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        Log.d(TAG, "[Search] Fetching URL: $url")
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response)
    }

    private fun searchParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select(".widget[data-grid] .entry-box-1")
        Log.d(TAG, "[Search] Found ${animeElements.size} items with selector '.widget[data-grid] .entry-box-1'")
        val animeList = animeElements.mapNotNull { element ->
            try {
                animeFromElement(element)
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = document.select("ul.pagination a[rel=next]").isNotEmpty()
        Log.d(TAG, "[Search] Has next page: $hasNextPage")
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response)
    }

    private fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        return SAnime().apply {
            url = document.location()
            title = document.selectFirst("h1.entry-title")?.text() ?: "No Title"
            thumbnail_url = document.selectFirst(".col-lg-3.col-md-4 picture img")?.attr("src")
            description = document.selectFirst(".widget-body .text-white")?.text()

            val details = mutableMapOf<String, String>()
            document.select(".hero__content .font-size-16.text-white").forEach { element ->
                val text = element.text()
                if (text.contains(" : ")) {
                    val parts = text.split(" : ", limit = 2)
                    details[parts[0].trim()] = parts[1].trim()
                }
            }
            genre = document.select(".font-size-16 a.badge-light").joinToString(", ") { it.text() }
            description += "\n\n" + details.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        println("DEBUG: fetchEpisodeList started for URL: $animeUrl")

        val request = Request.Builder().url(animeUrl).build()
        println("DEBUG: Request created: ${request.url}")

        val response = client.newCall(request).execute()
        println("DEBUG: Response received - Code: ${response.code}, URL: ${response.request.url}")

        val episodes = episodeListParse(response)
        println("DEBUG: fetchEpisodeList completed - Found ${episodes.size} episodes")
        episodes.forEachIndexed { index, episode ->
            println("DEBUG: Episode ${index + 1}: ${episode.name} (Number: ${episode.episode_number}, URL: ${episode.url})")
        }

        episodes
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        println("DEBUG: episodeListParse started")

        val htmlBody = response.body!!.string()
        println("DEBUG: HTML body length: ${htmlBody.length} characters")

        val document = Jsoup.parse(htmlBody, response.request.url.toString())
        println("DEBUG: Document parsed - Title: ${document.title()}, URL: ${document.location()}")

        val episodes = mutableListOf<SEpisode>()

        // Check for series episode list
        val episodeElements = document.select("#series-episodes .widget-body > .row > div")
        println("DEBUG: Found ${episodeElements.size} episode elements using series selector")

        if (episodeElements.isNotEmpty()) {
            println("DEBUG: Processing as series with multiple episodes")
            episodeElements.forEachIndexed { index, element ->
                println("DEBUG: Processing episode element $index")
                episodes.add(episodeFromElement(element, index))
            }
        } else {
            println("DEBUG: No series episodes found, treating as movie")
            // Assume it's a movie and create a single "Watch Movie" episode
            val movieEpisode = SEpisode().apply {
                url = document.location() // The URL is the movie page itself
                name = "مشاهدة الفيلم"
                episode_number = 1.0f
                thumbnailUrl = document.selectFirst(".col-lg-3.col-md-4 picture img")?.attr("src")
            }
            println("DEBUG: Created movie episode - URL: ${movieEpisode.url}, Thumbnail: ${movieEpisode.thumbnailUrl}")
            episodes.add(movieEpisode)
        }

        val reversedEpisodes = episodes
        println("DEBUG: episodeListParse completed - Returning ${reversedEpisodes.size} episodes (reversed)")

        return reversedEpisodes
    }

    private fun episodeFromElement(element: Element, index: Int): SEpisode {
        println("DEBUG: episodeFromElement started")

        val link = element.selectFirst("a")
        println("DEBUG: Link element found: ${link != null}")

        if (link == null) {
            println("WARNING: No link element found in episode element")
        }

        return SEpisode().apply {
            val href = link?.attr("href") ?: ""
            setUrlWithoutDomain(href)
            println("DEBUG: Episode URL (raw): $href, (processed): $url")

            var nm = link?.selectFirst("h2.entry-title")?.text()
                ?: link?.selectFirst(".entry-title")?.text()
                ?: link?.selectFirst("h2")?.text()
                ?: link?.selectFirst("h3")?.text()
                ?: link?.selectFirst(".title")?.text()
                ?: "حلقة"
            // Try different selectors for the episode name
            name = "Season : $nm ${index + 1}"

            println("DEBUG: Episode name: $name")

            thumbnailUrl = link?.selectFirst("picture img")?.attr("src")
            println("DEBUG: Episode thumbnail: $thumbnailUrl")

            // Extract episode number from URL as fallback since names are not working
            val epNumFromUrl = href.substringAfterLast("/").substringAfter("الحلقة-").filter { it.isDigit() }
            val epNumFromName = name!!.substringAfter("الحلقة").trim().filter { it.isDigit() }

            // Use URL-based extraction as primary fallback
            val epNumStr = if (epNumFromUrl.isNotEmpty()) {
                epNumFromUrl
            } else if (epNumFromName.isNotEmpty()) {
                epNumFromName
            } else {
                ""
            }

            println("DEBUG: Raw episode number from URL: '$epNumFromUrl', from name: '$epNumFromName', final: '$epNumStr'")

            episode_number = epNumStr.toFloatOrNull() ?: 1.0f
            println("DEBUG: Final episode number: $episode_number")

            // If we successfully extracted the number, create a better name
            if (epNumStr.isNotEmpty() && name == "حلقة") {
                name = "الحلقة $epNumStr"
                println("DEBUG: Updated episode name to: $name")
            }

            println("DEBUG: Episode created successfully - Name: $name, Number: $episode_number")
        }
    }

    // ============================== Video Links (CORRECTED LOGIC) ==============================
    suspend fun fetchVideoList(contentUrl: String): List<Video> = withContext(Dispatchers.IO) {
        // Step 1: Get the redirect link page URL from the content (movie/episode) page
        val initialRequest = Request.Builder().url(contentUrl).build()
        val initialResponse = client.newCall(initialRequest).execute()
        val initialDoc = Jsoup.parse(initialResponse.body!!.string(), contentUrl)
        var goLink = initialDoc.selectFirst("a.link-btn.link-show")?.attr("href")
            ?: return@withContext emptyList()
        Log.d(TAG, "Step 1: Found redirect link: $goLink")

        // Step 1.5: Force HTTPS to prevent CLEARTEXT error
        if (goLink.startsWith("http://")) {
            goLink = goLink.replaceFirst("http://", "https://")
            Log.d(TAG, "Step 1.5: Converted link to HTTPS: $goLink")
        }

        // Step 2: Fetch the redirect page to find the final watch URL
        val goRequest = Request.Builder().url(goLink).build()
        val goResponse = client.newCall(goRequest).execute()
        val goDoc = Jsoup.parse(goResponse.body!!.string(), goLink)
        val finalUrl = goDoc.selectFirst("a.download-link")?.attr("href")
            ?: return@withContext emptyList()
        Log.d(TAG, "Step 2: Parsed final watch page URL: $finalUrl")

        // Step 3: Fetch the final watch page and parse for video sources
        val finalRequest = Request.Builder().url(finalUrl).build()
        val finalResponse = client.newCall(finalRequest).execute()
        return@withContext videoListParse(finalResponse)
    }

    private fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val allVideos = mutableListOf<Video>()
        val sources = document.select("video#player source")

        Log.d(TAG, "Step 3: Found ${sources.size} <source> tags on the watch page.")

        sources.forEach { source ->
            val videoUrl = source.attr("src")
            val quality = source.attr("size") + "p"
            if (videoUrl.isNotBlank()) {
                allVideos.add(
                    Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        headers = mapOf("Referer" to BASE_URL)
                    )
                )
                Log.d(TAG, "Extracted direct link: $quality - $videoUrl")
            }
        }
        return allVideos
    }

    // ============================== Helper & Filters ==============================
    private fun animeFromElement(element: Element): SAnime {
        val link = element.selectFirst("a.box")!!
        return SAnime().apply {
            url = link.attr("href")
            title = element.selectFirst("h3.entry-title a")?.text() ?: "No Title"
            thumbnail_url = element.selectFirst(".entry-image img")?.attr("data-src")
                ?: element.selectFirst(".entry-image img")?.attr("src")

            val rating = element.selectFirst(".label.rating")?.text()
            val quality = element.selectFirst(".label.quality")?.text()
            val episode = element.selectFirst(".label.series")?.text()?.filter { it.isDigit() }

            description = buildString {
                if (!rating.isNullOrEmpty()) append("التقييم: $rating\n")
                if (!quality.isNullOrEmpty()) append("الجودة: $quality\n")
                if (!episode.isNullOrEmpty()) append("الحلقات: $episode")
            }
        }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}