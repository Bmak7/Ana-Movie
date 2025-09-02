package com.arabictoons.app.network.sources

import android.content.Context
import android.os.Build
import android.util.Log
import com.faselhd.app.models.* // Assuming these models are generic enough to be reused
import com.faselhd.app.utils.PlaylistUtils
import com.faselhd.app.utils.Tls12SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.regex.Pattern
import javax.net.ssl.TrustManager

class ArabicToonsSource(private val context: Context) {
    companion object {
        const val name = "Arabic-Toons"
        private const val PREFS_NAME = "ArabicToons_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val DEFAULT_BASE_URL = "https://www.arabic-toons.com"

        fun getBaseUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        }

        fun setBaseUrl(context: Context, newUrl: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BASE_URL, newUrl).apply()
        }

        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

    private val baseUrl: String
        get() = getBaseUrl(context)


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
        val clientBuilder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(request)
            }

        if (Build.VERSION.SDK_INT in 16..21) {
            try {
                val sc = SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, null)
                val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as java.security.KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                    throw IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers))
                }
                val trustManager = trustManagers[0] as X509TrustManager
                clientBuilder.sslSocketFactory(Tls12SocketFactory(sc.socketFactory), trustManager)
                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()
                clientBuilder.connectionSpecs(Collections.singletonList(cs))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        clientBuilder.build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    // ============================== Popular ===============================
    private fun popularSeriesSelector(): String = "div.moviesBlocks div.movie.movie-mb"

    private fun popularSeriesRequest(page: Int): Request {
        // The site doesn't seem to have pagination for the main series list in a simple page/X URL.
        // We will target the main cartoon page. Pagination might need a different approach if it exists.
        return Request.Builder()
            .url("$baseUrl/cartoon.php") // Assuming this is the main series page
            .build()
    }

    private fun popularSeriesFromElement(element: Element): SAnime {
        val anime = SAnime()
        val a = element.select("a.thumbnail")
        anime.setUrlWithoutDomain(a.attr("href"))
        anime.title = a.attr("title")
        anime.thumbnail_url = "$baseUrl/" +a.select("img").attr("src")
        return anime
    }

    // Note: arabic-toons.com doesn't show clear pagination on its main series page.
    private fun popularSeriesNextPageSelector(): String? = null

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        // Ignoring 'page' for now as pagination is not apparent.
        val request = popularSeriesRequest(page)
        val response = client.newCall(request).execute()
        popularSeriesParse(response)
    }

    private fun popularSeriesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(popularSeriesSelector())
        val animeList = animeElements.map { popularSeriesFromElement(it) }
        val hasNextPage = popularSeriesNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Latest ===============================

    private fun latestUpdatesSelector(): String = "div.row.well h3:contains(آخر الحلقات المضافة) + div.moviesBlocks div.movie"

    private fun latestUpdatesRequest(page: Int): Request {
        // Latest episodes are on the homepage. The site doesn't appear to have pagination for this section.
        return Request.Builder().url(baseUrl).build()
    }

    private fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime()
        val a = element.selectFirst("a.thumbnail")
        anime.setUrlWithoutDomain(a!!.attr("href"))
        // Title combines the series name and the episode number.
        val seriesName = element.select("div.badge-overd").text()
        val episodeNumber = element.select("div.badge-overlay").text()
        anime.title = "$seriesName - ?????? $episodeNumber"
        anime.thumbnail_url = "$baseUrl/" + a.select("img").attr("src")
        return anime
    }

    private fun latestUpdatesNextPageSelector(): String? = null

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    suspend fun fetchLatestUpdatess(page: Int): List<SAnime> = withContext(Dispatchers.IO) {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).execute()
        latestUpdatesParsee(response)
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(latestUpdatesSelector())
        val animeList = animeElements.map { latestUpdatesFromElement(it) }
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangaPage(animeList, hasNextPage)
    }

    private fun latestUpdatesParsee(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        val animeElements = document.select(latestUpdatesSelector())
        val animeList = animeElements.map { latestUpdatesFromElement(it) }
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) } != null
        return (animeList)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl/$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        animeDetailsParse(response)
    }

    private fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string())
        val anime = SAnime()
        anime.url = response.request.url.toString()
        anime.title = document.selectFirst("div.col-md-2 h1.text-center")?.text() ?: ""

        // Correct description selector - it's in the div with class "text-right" that contains the story
        anime.description = document.selectFirst("div.text-right > div.text-right")?.text() ?: ""

        anime.thumbnail_url = "$baseUrl/" + document.selectFirst("div.col-md-2 img")?.attr("src")

        // Extract audio URL and put it in genre field
        val audioUrl = document.selectFirst("audio#audioPlayer source")?.attr("src")
        anime.genre = if (!audioUrl.isNullOrEmpty()) "$baseUrl/$audioUrl" else ""
        println("audio ss : $audioUrl")
        anime.status = SAnime.UNKNOWN

        return anime
    }

    // ============================== Episodes ==============================
    private fun episodeListSelector() = "div.moviesBlocks div.movie"

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl/$animeUrl")
            .build()
        val response = client.newCall(request).execute()
        episodeListParse(response).reversed()
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        val episodeListContainer = document.selectFirst("h1:contains(قائمة الحلقات) + div.moviesBlocks")

        // Check if it's a series/season page with an episode list
        if (episodeListContainer != null) {
            // It's a series page, so parse the list of episodes
            val episodeElements = episodeListContainer.select(episodeListSelector())
            return episodeElements.map { episodeFromElement(it) }.reversed()
        } else {
            // It's a movie page, so treat the movie itself as a single episode
            val episode = SEpisode()
            episode.setUrlWithoutDomain(response.request.url.toString())
            episode.name = document.selectFirst("div.col-md-2 h1.text-center")?.text() ?: "Movie"
            episode.episode_number = 1f // Movies are considered episode 1
            episode.thumbnailUrl = "$baseUrl/" + document.selectFirst("div.col-md-2 img")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$baseUrl/$src"
            }
            return listOf(episode)
        }
    }

    private fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode()
        val a = element.selectFirst("a.thumbnail")
        a?.let {
            episode.setUrlWithoutDomain(it.attr("href"))
            val episodeNumberText = element.selectFirst("div.badge-overd.badge-light")?.text()?.replace("??????", "")?.trim()
            episode.name = "$baseUrl/" + episodeNumberText// Use title attribute for full name
            episode.thumbnailUrl = it.selectFirst("img")?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$baseUrl/$src"
            }
        }
        // Extract episode number from the badge
        val episodeNumberText = element.selectFirst("div.badge-overd")?.text()
            ?.replace("الحلقة", "")?.trim()
        episode.episode_number = episodeNumberText?.toFloatOrNull() ?: 0f
        return episode
    }
//    private fun episodeListSelector() = "div.moviesBlocks div.movie"
//
//    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        val request = Request.Builder()
//            .url(if (animeUrl.startsWith("http")) animeUrl else "$baseUrl/$animeUrl")
//            .build()
//        val response = client.newCall(request).execute()
//        episodeListParse(response)
//    }
//
//    private fun episodeListParse(response: Response): List<SEpisode> {
//        val document = Jsoup.parse(response.body!!.string())
//        val episodeElements = document.select("h1:contains(قائمة الحلقات) + div.moviesBlocks").select(episodeListSelector())
//        return episodeElements.map { episodeFromElement(it) }.reversed() // Typically episodes are listed newest first.
//    }
//
//    private fun episodeFromElement(element: Element): SEpisode {
//        val episode = SEpisode()
//        val a = element.selectFirst("a.thumbnail")
//        episode.setUrlWithoutDomain(a!!.attr("href"))
//        val episodeNumberText = element.selectFirst("div.badge-overd.badge-light")?.text()?.replace("??????", "")?.trim()
//        episode.name = episodeNumberText ?: "??"
////        val episodeNumberText = element.selectFirst("div.badge-overd.badge-light")?.text()?.replace("??????", "")?.trim()
//        episode.episode_number = episodeNumberText?.toFloatOrNull() ?: 0f
//        episode.thumbnailUrl = "$baseUrl/" + element.selectFirst("img")?.attr("src")
//        return episode
//    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val absoluteUrl = if (episodeUrl.startsWith("http")) episodeUrl else "$baseUrl/$episodeUrl"
        try {
            val request = Request.Builder().url(absoluteUrl).build()
            val response = client.newCall(request).execute()
            videoListParse(response)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList<Video>()
        }
    }

    /**
     * Parses the episode page response to find video URLs.
     * It tries to extract from JavaScript first, then falls back to checking iframes.
     */
    private suspend fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        val htmlContent = document.html()
        val pageUrl = response.request.url.toString()

        // Method 1: Look for HLS URL in JavaScript (most common case for this site)
        var videos = extractFromJavascript(htmlContent, pageUrl)
        if (videos.isNotEmpty()) {
            return videos
        }

        // Method 2: Look for iframe sources if JS extraction fails
        videos = extractFromIframes(document, pageUrl)
        if (videos.isNotEmpty()) {
            return videos
        }

        return emptyList()
    }

    /**
     * Extracts video URLs by parsing JavaScript content within the HTML.
     * It specifically looks for the pattern used by arabic-toons.com to construct HLS URLs.
     */
    private fun extractFromJavascript(htmlContent: String, pageUrl: String): List<Video> {
        // Pattern to find the JavaScript object containing parts of the URL.
        val x9zfqv3Pattern = Regex("""const\s+x9zFqV3\s*=\s*\{([^}]+)\}""")
        val match = x9zfqv3Pattern.find(htmlContent)

        if (match != null) {
            val objContent = match.groupValues[1]
            val variables = mutableMapOf<String, String>()
            // Pattern to parse key-value pairs from the JS object.
            val propPattern = Regex("""(\w+):\s*["']([^"']+)["']""")
            propPattern.findAll(objContent).forEach {
                variables[it.groupValues[1]] = it.groupValues[2]
            }

            // After parsing the object, look for the yB0hQ variable assignment which constructs the final URL.
            val yb0hqPattern = Regex("""yB0hQ\s*=\s*`\$\{x9zFqV3\.jC1kO\}:\/\/\$\{x9zFqV3\.hF3nV\}\/\$\{x9zFqV3\.iA5pX\}\?\$\{x9zFqV3\.tN4qY\}`""")
            if (yb0hqPattern.containsMatchIn(htmlContent)) {
                val protocol = variables["jC1kO"]
                val domain = variables["hF3nV"]
                val path = variables["iA5pX"]
                val params = variables["tN4qY"]

                if (protocol != null && domain != null && path != null && params != null) {
                    val resolvedUrl = "$protocol://$domain/$path?$params"
                    if (isValidVideoUrl(resolvedUrl)) {
                        // Create a single Video object with the HLS master playlist URL.
                        // The player will handle quality selection.
                        val headers = mapOf(
                            "Referer" to baseUrl,
//                            "User-Agent" to "Mozilla/5.0" // optional, but helps avoid 403
                        )
                        val video = Video(resolvedUrl, "Auto Quality", resolvedUrl, "??",headers)

                        return listOf(video)
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Finds all iframes on the page and recursively tries to extract video URLs from them.
     */
    private suspend fun extractFromIframes(document: Document, pageUrl: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val iframes = document.select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val iframeUrl = if (src.startsWith("http")) src else "$baseUrl/$src"
                try {
                    val request = Request.Builder().url(iframeUrl).build()
                    val response = client.newCall(request).execute()
                    // Recursively call the JavaScript extractor on the iframe's content.
                    val videosFromIframe = extractFromJavascript(response.body!!.string(), iframeUrl)
                    if (videosFromIframe.isNotEmpty()) {
                        videoList.addAll(videosFromIframe)
                    }
                } catch (e: Exception) {
                    // Silently ignore if an iframe fails to load.
                    println("Error processing iframe $iframeUrl: ${e.message}")
                }
            }
        }
        return videoList
    }

    /**
     * Helper function to quickly validate if a URL seems like a video stream.
     */
    private fun isValidVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url.startsWith("data:")) {
            return false
        }
        val lowerUrl = url.lowercase(Locale.ROOT)
        val videoExtensions = listOf(".mp4", ".webm", ".mkv", ".m3u8")
        return videoExtensions.any { lowerUrl.contains(it) }
    }

    // =============================== Search ===============================
    // NOTE: arabic-toons.com does not appear to have a standard search function.
    // This is a placeholder implementation.
    // Selector for the search results from livesearch.php
    private fun searchAnimeSelector(): String = "div.list-group a.list-group-item"

    // Parser for a single search result element (the <a> tag)
    private suspend fun searchResultFromElement(element: Element): SAnime {

        Log.e("Search Arab:", element.toString())
        val anime = SAnime()
        var animeThunbinal = SAnime()
        animeThunbinal = fetchAnimeDetails("$baseUrl/" + element.attr("href"))
        anime.setUrlWithoutDomain(element.attr("href"))
        // The title is the text of the link, remove the badge span content
        anime.title = element.ownText().trim()
        // The search result does not contain a thumbnail

        anime.thumbnail_url = animeThunbinal.thumbnail_url
        return anime
    }

    // A dedicated parser for the HTML response from livesearch.php
    private suspend fun searchAnimeParse(responseBody: String): List<SAnime> {
        val document = Jsoup.parse(responseBody)
        val animeElements = document.select(searchAnimeSelector())
        return animeElements.map { searchResultFromElement(it) }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext MangaPage(emptyList(), false)
        }

        // Create two separate requests for cartoons and movies
        val cartoonRequest = Request.Builder().url("$baseUrl/livesearch.php?q=$query").build()
        val movieRequest = Request.Builder().url("$baseUrl/livesearch.php?m&q=$query").build()

        // Use async to run requests in parallel
        val cartoonDeferred = async { client.newCall(cartoonRequest).execute() }
        val movieDeferred = async { client.newCall(movieRequest).execute() }

        // Await both responses
        val cartoonResponse = cartoonDeferred.await()
        val movieResponse = movieDeferred.await()

        val allResults = mutableListOf<SAnime>()
        val seenUrls = mutableSetOf<String>()

        // Process cartoon results using the new parser
        if (cartoonResponse.isSuccessful) {
            val responseBody = cartoonResponse.body!!.string()
            searchAnimeParse(responseBody).forEach { anime ->
                if (anime.url!!.isNotBlank() && seenUrls.add(anime.url!!)) {
                    allResults.add(anime)
                }
            }
        }

        // Process movie results using the new parser
        if (movieResponse.isSuccessful) {
            val responseBody = movieResponse.body!!.string()
            searchAnimeParse(responseBody).forEach { anime ->
                if (anime.url!!.isNotBlank() && seenUrls.add(anime.url!!)) {
                    allResults.add(anime)
                }
            }
        }

        // livesearch.php does not support pagination, so hasNextPage is always false.
        MangaPage(allResults, false)
    }
}