package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
// Make sure to import the new extractor
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.regex.Pattern

class EskSource(private val context: Context) {
    companion object {
        const val name = "قصة عشق"
        const val BASE_URL = "https://3esk.onl"
        const val lang = "ar"
        const val supportsLatest = true
    }

    // --- OKHTTP CLIENT (No changes) ---
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                private val cookieStore = HashMap<String, List<Cookie>>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: ArrayList()
                }
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
            .apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
                    4 -> addAdGuardDns()
                }
            }
            .build()
    }


    // --- VIDEO EXTRACTORS ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val filemoonExtractor by lazy { FileMoonExtractor(client) }
    // ADD THE NEW EXTRACTOR
    private val miraVdExtractor by lazy { MiraVDExtractor(client) }

    // --- All other functions (fetchLatestUpdates, fetchVideoList, etc.) remain the same ---
    // ... (keep all other functions as they are correct) ...
    // ============================== Latest Updates / Main Page ==============================
    // ============================== Latest Updates / Main Page ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)
        val url = BASE_URL
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("ul.items-latest-eps li.type_item_box a.type_item")
        val animeList = animeElements.mapNotNull { element ->
            // Get the encoded URL from the 'data-clse' attribute
            val encodedUrl = element.attr("data-clse")
            val decodedUrl = if (encodedUrl.isNotEmpty()) {
                // Decode the Base64 string to get the real URL
                String(Base64.decode(encodedUrl, Base64.DEFAULT))
            } else {
                // Fallback to href if data-clse is missing for some reason
                element.attr("abs:href")
            }

            SAnime().apply {
                this.url = decodedUrl // Use the decoded URL
                this.title = element.selectFirst("div.item_title")?.text() ?: "No Title"
                this.thumbnail_url = element.selectFirst("img.item_img")?.attr("data-image")
            }
        }
        return MangaPage(animeList, hasNextPage = false)
    }

    // ============================== Details ==============================
    // ============================== Details ==============================
    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        Log.d("EskSource", "fetchAnimeDetails started for URL: $animeUrl")
        try {
            val request = Request.Builder().url(animeUrl).build()
            val response = client.newCall(request).execute()

            Log.d("EskSource", "Response for $animeUrl - Code: ${response.code}")

            if (!response.isSuccessful) {
                Log.e("EskSource", "Failed to fetch details from $animeUrl. Aborting.")
                // Returning an empty SAnime object or handle the error as appropriate
                return@withContext SAnime()
            }

            // Read the body once and pass it to the parsing function.
            val responseBody = response.body?.string()

            if (responseBody.isNullOrEmpty()) {
                Log.e("EskSource", "Response body is null or empty for $animeUrl.")
                return@withContext SAnime()
            }

            // Print the entire HTML body to Logcat for inspection.
            // Be aware this can be very long.
            Log.d("EskSource", "--- Start of Response Body for $animeUrl ---")
            // Logcat has a limit, so we print in chunks if necessary
            responseBody.chunked(4000).forEach { chunk ->
                Log.d("EskSource", chunk)
            }
            Log.d("EskSource", "--- End of Response Body for $animeUrl ---")

            // Call the parsing function with the response body string
            return@withContext animeDetailsParse(responseBody, animeUrl)

        } catch (e: Exception) {
            Log.e("EskSource", "Exception in fetchAnimeDetails for $animeUrl", e)
            return@withContext SAnime() // Return empty object on error
        }
    }

    // Modified to accept the response body as a String
    private fun animeDetailsParse(responseBody: String, animeUrl: String): SAnime {
        Log.d("EskSource", "animeDetailsParse started for URL: $animeUrl")
        val document = Jsoup.parse(responseBody, animeUrl)

        return SAnime().apply {
            this.url = animeUrl
            Log.d("EskSource", "Parsing URL: $url")

            // 1. Parse Thumbnail URL
            val thumbSelector = "div.poster img"
            Log.d("EskSource", "Attempting to select thumbnail with: '$thumbSelector'")
            this.thumbnail_url = document.selectFirst(thumbSelector)?.attr("src")
            Log.d("EskSource", "Parsed thumbnail_url: ${this.thumbnail_url}")

            // 2. Parse Title
            val titleSelector = "div.single_info h1.title"
            Log.d("EskSource", "Attempting to select title with: '$titleSelector'")
            this.title = document.selectFirst(titleSelector)?.text()?.trim() ?: "No Title"
            Log.d("EskSource", "Parsed title: ${this.title}")

            // 3. Parse Genre
            val genreSelector = "div.categories a"
            Log.d("EskSource", "Attempting to select genre with: '$genreSelector'")
            this.genre = document.select(genreSelector).joinToString { it.text() }
            Log.d("EskSource", "Parsed genre: ${this.genre}")

            // 4. Parse Description
            val descSelector = "div.description span[data-nosnippet]"
            Log.d("EskSource", "Attempting to select description with: '$descSelector'")
            this.description = document.selectFirst(descSelector)?.text() ?: ""
            Log.d("EskSource", "Parsed description: ${this.description}")

            // 5. Determine Status
            val statusSelector = "div.items_list a.ep-num"
            Log.d("EskSource", "Attempting to select episodes for status check with: '$statusSelector'")
            val lastEpisodeText = document.select(statusSelector).first()?.attr("title").orEmpty()
            Log.d("EskSource", "Text found for status check: '$lastEpisodeText'")
            this.status = if (lastEpisodeText.contains("والاخيرة", true) || lastEpisodeText.contains("final", true)) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }
            Log.d("EskSource", "Parsed status: ${this.status}")

            Log.d("EskSource", "Finished parsing. Final SAnime object: $this")
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val response = client.newCall(request).execute()
        episodeListParse(response)
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        val episodeElements = document.select("div.items_list a.ep-num")

        return if (episodeElements.isNotEmpty()) {
            episodeElements.map { element ->
                // Get the encoded URL from the 'data-clse' attribute for the episode
                val encodedUrl = element.attr("data-clse")
                val decodedUrl = if (encodedUrl.isNotEmpty()) {
                    // Decode the Base64 string to get the real URL
                    String(Base64.decode(encodedUrl, Base64.DEFAULT))
                } else {
                    // Fallback to href if data-clse is missing
                    element.attr("abs:href")
                }

                SEpisode().apply {
                    this.url = decodedUrl // Use the CORRECT decoded URL
                    this.name =
                        "Season : ${ element.attr("title").ifEmpty { "الحلقة " + element.selectFirst("b")?.text()?.trim() } }"
                    this.episode_number = element.selectFirst("b")?.text()?.toFloatOrNull() ?: 1f
                }
            }.reversed()
        } else {
            // This part is for movies, which is correct and remains unchanged.
            listOf(
                SEpisode().apply {
                    url = response.request.url.toString()
                    name = "مشاهدة الفيلم"
                    episode_number = 1f
                }
            )
        }
    }


    // ============================== Video Links (No changes here) ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            Log.d("EskSource", "1. Fetching Episode Page: $episodeUrl")
            val episodeRequest = Request.Builder().url(episodeUrl).build()
            val episodeResponse = client.newCall(episodeRequest).execute()
            if (!episodeResponse.isSuccessful) {
                Log.e("EskSource", "Failed to fetch episode page: ${episodeResponse.code}")
                return@withContext emptyList()
            }
            val episodeDoc = Jsoup.parse(episodeResponse.body!!.string(), episodeUrl)
            val firstPostForm = episodeDoc.selectFirst("form[action*=aa.3isk.icu]")
            val firstPostUrl = firstPostForm?.attr("action")
            val newsValue = firstPostForm?.selectFirst("input[name=news]")?.attr("value")
            if (firstPostUrl.isNullOrEmpty() || newsValue.isNullOrEmpty()) {
                Log.e("EskSource", "Could not find the first POST form or 'news' value.")
                return@withContext emptyList()
            }
            Log.d("EskSource", "2. POSTing to intermediate URL: $firstPostUrl")
            val firstPostBody = FormBody.Builder().add("news", newsValue).build()
            val firstPostRequest = Request.Builder().url(firstPostUrl)
                .post(firstPostBody)
                .header("Referer", episodeUrl)
                .build()
            val intermediateResponse = client.newCall(firstPostRequest).execute()
            if (!intermediateResponse.isSuccessful) {
                Log.e("EskSource", "First POST failed: ${intermediateResponse.code}")
                return@withContext emptyList()
            }
            val intermediateHtml = intermediateResponse.body!!.string()
            Log.d("EskSource", "3. Parsing intermediate script for final POST data.")
            val urlRegex = """var myUrl = "([^"]+)";""".toRegex()
            val finalNewsRegex = """myInput\.value = "([^"]+)";""".toRegex()
            val secondPostUrl = urlRegex.find(intermediateHtml)?.groups?.get(1)?.value
            val finalNewsValue = finalNewsRegex.find(intermediateHtml)?.groups?.get(1)?.value
            if (secondPostUrl.isNullOrEmpty() || finalNewsValue.isNullOrEmpty()) {
                Log.e("EskSource", "Could not extract final POST URL or news value from script.")
                return@withContext emptyList()
            }
            Log.d("EskSource", "4. POSTing to final player URL: $secondPostUrl")
            val secondPostBody = FormBody.Builder().add("news", finalNewsValue).build()
            val secondPostRequest = Request.Builder().url(secondPostUrl)
                .post(secondPostBody)
                .header("Referer", firstPostUrl)
                .build()
            val finalPlayerResponse = client.newCall(secondPostRequest).execute()
            if (!finalPlayerResponse.isSuccessful) {
                Log.e("EskSource", "Second POST failed: ${finalPlayerResponse.code}")
                return@withContext emptyList()
            }
            val finalPlayerDoc = Jsoup.parse(finalPlayerResponse.body!!.string(), secondPostUrl)
            val iframeSrc = finalPlayerDoc.selectFirst("iframe[src]")?.attr("abs:src")
            if (iframeSrc.isNullOrEmpty()) {
                Log.e("EskSource", "Could not find iframe on final player page.")
                return@withContext emptyList()
            }
            Log.d("EskSource", "5. Found iframe, dispatching to extractor: $iframeSrc")
            return@withContext extractVideosFromUrl(iframeSrc, "Default Quality")

        } catch (e: Exception) {
            Log.e("EskSource", "A critical error occurred in fetchVideoList", e)
            return@withContext emptyList()
        }
    }


    // ============================== UPDATED EXTRACTOR DISPATCHER ==============================
    private suspend fun extractVideosFromUrl(url: String, qualityLabel: String): List<Video> {
        return try {
            when {
                // This is now a "resolver" that finds the *real* video host URL
                "3esk.onl/embed" in url -> {
                    Log.d("EskSource", "Resolving 3esk embed page: $url")
                    val request = Request.Builder().url(url).header("Referer", BASE_URL).build()
                    val response = client.newCall(request).execute()
                    val doc = Jsoup.parse(response.body?.string() ?: "")
                    val iframeSrc = doc.selectFirst("iframe[src]")?.attr("abs:src")
                    if (!iframeSrc.isNullOrEmpty()) {
                        Log.d("EskSource", "Found nested iframe: $iframeSrc. Re-dispatching...")
                        // Call this function again with the new URL from the iframe
                        extractVideosFromUrl(iframeSrc, qualityLabel)
                    } else {
                        Log.e("EskSource", "No nested iframe found on 3esk embed page.")
                        emptyList()
                    }
                }

                // Add the new extractor for miravd.com
                "mwdy" in url || "miravd" in url -> miraVdExtractor.videosFromUrl(url)

                // Existing extractors
                "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url -> doodExtractor.videosFromUrl(url, qualityLabel)
                "voe.sx" in url -> voeExtractor.videosFromUrl(url)
                "vidmoly" in url -> vidmolyExtractor.videosFromUrl(url)
                "streamwish" in url || "streamsss" in url -> streamwishExtractor.videosFromUrl(url, qualityLabel)
                "filemoon" in url || "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, qualityLabel)

                else -> {
                    Log.w("EskSource", "No specific extractor for URL: $url.")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("EskSource", "Extractor failed for URL: $url", e)
            emptyList()
        }
    }


    // ============================== Search (No changes) ==============================
    suspend fun fetchSearchAnime(page: Int, query: String): MangaPage = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext MangaPage(emptyList(), false)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/$encodedQuery/"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response)
    }

    private fun searchParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), response.request.url.toString())
        val animeElements = document.select("ul.search-page li.type_item_box a.type_item")
        val animeList = animeElements.map { element ->
            // Get the encoded URL from the 'data-clse' attribute
            val encodedUrl = element.attr("data-clse")
            val decodedUrl = if (encodedUrl.isNotEmpty()) {
                // Decode the Base64 string to get the real URL
                String(Base64.decode(encodedUrl, Base64.DEFAULT))
            } else {
                // Fallback to href if data-clse is missing
                element.attr("abs:href")
            }

            SAnime().apply {
                this.url = decodedUrl // Use the decoded URL
                this.title = element.selectFirst("div.item_title")?.text() ?: "Search Result"
                this.thumbnail_url = element.selectFirst("img.item_img")?.attr("data-image")
            }
        }
        return MangaPage(animeList, hasNextPage = false)
    }
}