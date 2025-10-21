package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class E3skSource(private val context: Context) {
    companion object {
        const val name = "قصة عشق"
        const val BASE_URL = "https://e3sk.com"
        const val lang = "ar"
        const val supportsLatest = true
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
        // Define a consistent log tag
        private const val TAG = "E3skSource"
    }

    // --- START: OKHTTP CLIENT SETUP (Identical to ArabSeedSource) ---
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
    private val client = NetworkClient.client

//    private val client: OkHttpClient by lazy {
//        val cookieJar = object : CookieJar {
//            private val cookieStore = HashMap<String, List<Cookie>>()
//            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
//                cookieStore[url.host] = cookies
//            }
//            override fun loadForRequest(url: HttpUrl): List<Cookie> {
//                return cookieStore[url.host] ?: ArrayList()
//            }
//        }
//
//        OkHttpClient.Builder()
//            .cookieJar(cookieJar)
//            .addInterceptor { chain ->
//                val request = chain.request().newBuilder()
//                    .header("User-Agent", USER_AGENT)
//                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
//                    .build()
//                chain.proceed(request)
//            }
//            .followRedirects(true)
//            .followSslRedirects(true)
//            .ignoreAllSSLErrors()
//            .cache(
//                Cache(
//                    directory = File(context.cacheDir, "http_cache"),
//                    maxSize = 50L * 1024L * 1024L // 50 MiB
//                )
//            ).apply {
//                when (dns) {
//                    1 -> addGoogleDns()
//                    2 -> addCloudFlareDns()
//                    4 -> addAdGuardDns()
//                    5 -> addDNSWatchDns()
//                    6 -> addQuad9Dns()
//                    7 -> addDnsSbDns()
//                    8 -> addCanadianShieldDns()
//                }
//            }
//            .build()
//    }
    // --- END: OKHTTP CLIENT SETUP ---


    // --- Video Extractors ---
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) } // General purpose
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val okRuExtractor by lazy { OkruExtractor(client) }
    private val dailyMotionExtractor by lazy { DailyMotionExtractor(client) }
    private val miraVdExtractor by lazy { MiraVDExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }



    // ============================== Helper Functions ==============================
    private fun getBackgroundImageUrl(style: String): String? {
        val regex = "url\\((.*?)\\)".toRegex()
        return regex.find(style)?.groupValues?.get(1)
    }

    /**
     * E3sk listing pages use a redirector link (arbandroid.com).
     * The real URL is Base64 encoded in the 'url' parameter.
     */
    /**
     * E3sk listing pages use a redirector link (arbandroid.com).
     * The real URL is a URL-Encoded, Base64 encoded string in the 'url' parameter.
     * This function now correctly handles the decoding process.
     */
    private fun decodeRedirectorUrl(url: String): String {
        Log.d(TAG, "[decodeRedirectorUrl] Input URL: $url")
        try {
            // Check if the URL is a redirector link before attempting to decode
            if (!url.contains("?url=")) {
                Log.d(TAG, "[decodeRedirectorUrl] Not a redirector link. Returning original.")
                return url
            }
            val encodedPart = url.substringAfter("?url=")

            // --- START FIX ---
            // STEP 1: URL-decode the string first to handle characters like %3D -> =
            val urlDecodedString = URLDecoder.decode(encodedPart, "UTF-8")

            // STEP 2: Now, Base64-decode the result.
            val decodedBytes = Base64.decode(urlDecodedString, Base64.DEFAULT)
            // --- END FIX ---

            val finalUrl = String(decodedBytes, StandardCharsets.UTF_8)
            Log.d(TAG, "[decodeRedirectorUrl] Successfully Decoded URL: $finalUrl")
            return finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "[decodeRedirectorUrl] Failed to decode URL: $url", e)
            return url // Return original URL if decoding fails
        }
    }

    /**
     * Helper to log large HTML content to Logcat without truncation.
     */
    private fun logHtmlContent(tag: String, title: String, html: String) {
        Log.d(tag, "---- START HTML: $title ----")
        html.chunked(4000).forEach { chunk ->
            Log.d(tag, chunk)
        }
        Log.d(tag, "---- END HTML: $title ----")
    }

    // ============================== Latest Updates ==============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) BASE_URL else "$BASE_URL/page/$page/"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        latestUpdatesParse(response)
    }

    private fun latestUpdatesParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)
        val animeElements = document.select("div#load-post article.post")
        val animeList = animeElements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            SAnime().apply {
                url = decodeRedirectorUrl(linkElement.attr("abs:href"))
                title = linkElement.selectFirst(".title")?.text()?.trim() ?: "No Title"
                val style = linkElement.selectFirst(".imgBg")?.attr("style")
                if (style != null) {
                    thumbnail_url = getBackgroundImageUrl(style)
                }
            }
        }
        val hasNextPage = document.select("div.pagination a.next, div.navigation a:contains(›)").isNotEmpty()
        return MangaPage(animeList, hasNextPage)
    }

    // ============================== Details ==============================
    // ============================== Details ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        Log.d(TAG, "[fetchAnimeDetails] Received URL: $animeUrl")

        // --- START FIX ---
        // Check if the incoming URL is a redirector link and decode it if necessary.
        val correctedUrl = if (animeUrl.contains("arbandroid.com") || animeUrl.contains("syara.net")) {
            decodeRedirectorUrl(animeUrl)
        } else {
            animeUrl
        }
        Log.d(TAG, "[fetchAnimeDetails] Corrected URL for fetching: $correctedUrl")
        // --- END FIX ---

        // Now, use the correctedUrl for the network request and parsing
        val request = Request.Builder().url(correctedUrl).build()
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body!!.string(), correctedUrl)

        return@withContext SAnime().apply {
            val seriesInfoBlock = document.selectFirst("div.singleSeries")
            if (seriesInfoBlock != null) {
                // Use the correctedUrl in the final object
                url = seriesInfoBlock.selectFirst("div.info h1 a")?.attr("abs:href") ?: correctedUrl
                thumbnail_url = getBackgroundImageUrl(seriesInfoBlock.selectFirst("div.cover .img")?.attr("style") ?: "")
                title = seriesInfoBlock.selectFirst("div.info h1 a")?.text() ?: "No Title"
                description = seriesInfoBlock.selectFirst("div.story")?.text() ?: ""
                genre = seriesInfoBlock.select("div.tax a").joinToString { it.text() }
                status = if (document.select("span.ribbon:contains(الحلقة الأخيرة)").isNotEmpty()) {
                    SAnime.COMPLETED
                } else {
                    SAnime.ONGOING
                }
            } else {
                // Fallback for pages without the series block
                url = correctedUrl
                title = document.selectFirst("h1")?.text() ?: "No Title"
                description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            }
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val tempDetails = fetchAnimeDetails(animeUrl)
        val seriesUrl = tempDetails.url
        val request = Request.Builder().url(seriesUrl!!).build()
        val response = client.newCall(request).execute()
        episodeListParse(response)
    }

    private fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        val episodeElements = document.select("article.postEp")

        return if (episodeElements.isNotEmpty()) {
            episodeElements.map { element ->
                val link = element.selectFirst("a")
                SEpisode().apply {
                    url = decodeRedirectorUrl(link?.attr("abs:href") ?: "")
                    name = "Season : ${ link?.selectFirst(".title")?.text()?.trim() ?: "Episode" }"
                    episode_number = link?.selectFirst(".episodeNum span:nth-of-type(2)")?.text()?.toFloatOrNull() ?: 1f
                }
            }.reversed()
        } else {
            listOf(
                SEpisode().apply {
                    url = response.request.url.toString()
                    name = "مشاهدة الفيلم"
                    episode_number = 1f
                }
            )
        }
    }
    private val playlistUtils by lazy { PlaylistUtils(client) }


    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[fetchVideoList] Starting fetch for episode URL: $episodeUrl")
        try {
            // Step 1: Fetch the initial episode page to find the redirector link
            val episodeRequest = Request.Builder().url(episodeUrl).build()
            val episodeResponse = client.newCall(episodeRequest).execute()
            val episodeDoc = Jsoup.parse(episodeResponse.body!!.string(), episodeUrl)

            val redirectorLink = episodeDoc.selectFirst("a.fullscreen-clickable")?.attr("abs:href")
            if (redirectorLink.isNullOrEmpty()) {
                Log.e(TAG, "[fetchVideoList] Step 1 FAILED: Could not find the intermediate redirector link.")
                return@withContext emptyList()
            }
            Log.d(TAG, "[fetchVideoList] Step 1 Success: Found intermediate redirector link: $redirectorLink")

            // --- START: CORRECTED LOGIC ---

            // Step 2: Extract the 'post' parameter which contains Base64 encoded data
            val postDataEncoded = URI(redirectorLink).query.substringAfter("post=")
            if (postDataEncoded.isBlank()) {
                Log.e(TAG, "[fetchVideoList] Step 2 FAILED: 'post' parameter is missing or empty.")
                return@withContext emptyList()
            }

            // Step 3: Decode the Base64 string to get the JSON data
            val decodedJsonString = String(Base64.decode(postDataEncoded, Base64.DEFAULT), StandardCharsets.UTF_8)
            Log.d(TAG, "[fetchVideoList] Step 3 Success: Decoded JSON: $decodedJsonString")
            val jsonObject = JSONObject(decodedJsonString)
            val serversArray = jsonObject.getJSONArray("servers")

            // Step 4: Loop through the servers, construct embed URLs, and extract videos
            val videos = mutableListOf<Video>()
            Log.d(TAG, "[fetchVideoList] Step 4: Found ${serversArray.length()} servers to process.")

            for (i in 0 until serversArray.length()) {
                val serverObject = serversArray.getJSONObject(i)
                val serverName = serverObject.optString("name", "Unknown")
                val serverId = serverObject.optString("id")
                val qualityLabel = "$name - $serverName"
                var embedUrl: String? = null

                // Handle different server types based on the decoded JSON
                if (serverName.equals("dailymotion", ignoreCase = true)) {
                    // Dailymotion might have a different structure, but if it has an ID, we can build a link
                    // For this source, the direct link is often inside the intermediate page's HTML
                    // We will fall back to the generic turkvearab link if a specific one isn't found.
                    embedUrl = "https://www.dailymotion.com/video/${serverId}"
                } else if (serverId.isNotBlank()) {
                    // For most other servers, the embed URL is constructed like this
                    embedUrl = "https://v.turkvearab.com/embed-$serverId.html"
                }

                if (!embedUrl.isNullOrEmpty()) {
                    Log.d(TAG, "[fetchVideoList] Processing server '$serverName' with URL: $embedUrl")
                    videos.addAll(extractVideosFromUrl(embedUrl, qualityLabel, redirectorLink))
                } else {
                    Log.w(TAG, "[fetchVideoList] Could not construct a valid embed URL for server: $serverObject")
                }
            }

            // --- END: CORRECTED LOGIC ---

            val distinctVideos = videos.distinctBy { it.url }
            Log.d(TAG, "[fetchVideoList] Finished. Found ${distinctVideos.size} distinct video links.")
            return@withContext distinctVideos

        } catch (e: Exception) {
            Log.e(TAG, "[fetchVideoList] A critical error occurred during video fetching", e)
            return@withContext emptyList()
        }
    }

    /**
     * Dispatcher function to route embed URLs to the correct extractor.
     */
    private suspend fun extractVideosFromUrl(url: String, qualityLabel: String, episodeUrl: String): List<Video> {
        return try {
            when {
                "daily" in url -> {
                    dailyMotionExtractor.videosFromUrl(url)
                }
                "ok.ru" in url -> okRuExtractor.videosFromUrl(url)
                "turkvearab" in url  -> extractFromTurkVeArab(url, qualityLabel,episodeUrl)
                "voe.sx" in url -> voeExtractor.videosFromUrl(url)
                "https://doo" in url || "https://d" in url ||"d000" in url || "dood" in url || "d-s.io" in url || "vide0" in url -> doodExtractor.videosFromUrl(url, qualityLabel)
                else -> {
                    Log.w(TAG, "No specific extractor available for URL: $url")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extractor failed for URL: $url", e)
            emptyList()
        }
    }

    /**
     * Custom extractor for the main server (v.turkvearab.com)
     */
    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
    private suspend fun extractFromTurkVeArab(url: String, qualityLabel: String, episodeUrl: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }

            val htmlContent = response.body?.string() ?: return emptyList()
            response.close()

            val videoUrls = extractVideoUrlsFromHtml(htmlContent)
            val allVideos = mutableListOf<Video>()

            videoUrls.forEachIndexed { index, videoUrl ->
                if (videoUrl.contains(".m3u8")) {
                    // Extract multiple qualities from M3U8 playlist
                    val m3u8Videos = extractQualitiesFromM3U8(videoUrl, url)
                    allVideos.addAll(m3u8Videos.reversed())
                } else {
                    // Handle regular MP4 files
                    val quality = determineQuality(videoUrl, index)
                    allVideos.add(Video(
                        url = videoUrl,
                        quality = quality,
                        videoUrl = videoUrl,
                        resolution = quality,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to url
                        ),
                        subtitles = null
                    ))
                }
            }

            return allVideos

        } catch (e: IOException) {
            println("Error fetching video: ${e.message}")
            return emptyList()
        }
    }

    private suspend fun extractQualitiesFromM3U8(m3u8Url: String, refererUrl: String): List<Video> {
        try {
            val request = Request.Builder()
                .url(m3u8Url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", refererUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }

            val m3u8Content = response.body?.string() ?: return emptyList()
            response.close()

            return parseM3U8Playlist(m3u8Content, m3u8Url, refererUrl)

        } catch (e: Exception) {
            println("Error fetching M3U8 playlist: ${e.message}")
            // Return the original M3U8 URL as fallback
            return listOf(Video(
                url = m3u8Url,
                quality = "HLS",
                videoUrl = m3u8Url,
                resolution = "HLS",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to refererUrl
                ),
                subtitles = null
            ))
        }
    }

    private fun parseM3U8Playlist(m3u8Content: String, baseUrl: String, refererUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val lines = m3u8Content.split("\n")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // Look for quality information in EXT-X-STREAM-INF tags
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // Extract quality information from the stream info line
                val bandwidth = extractAttribute(line, "BANDWIDTH")
                val resolution = extractAttribute(line, "RESOLUTION")
                val quality = determineQualityFromAttributes(bandwidth, resolution)

                // The next line should contain the stream URL
                if (i + 1 < lines.size) {
                    var streamUrl = lines[i + 1].trim()

                    // Convert relative URLs to absolute URLs
                    if (!streamUrl.startsWith("http")) {
                        streamUrl = resolveUrl(baseUrl, streamUrl)
                    }

                    videos.add(Video(
                        url = streamUrl,
                        quality = quality,
                        videoUrl = streamUrl,
                        resolution = quality,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to refererUrl
                        ),
                        subtitles = null
                    ))
                }
            }
            i++
        }

        // If no stream info found, treat as single stream
        if (videos.isEmpty()) {
            videos.add(Video(
                url = baseUrl,
                quality = "HLS",
                videoUrl = baseUrl,
                resolution = "HLS",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to refererUrl
                ),
                subtitles = null
            ))
        }

        return videos
    }

    private fun extractAttribute(line: String, attribute: String): String? {
        val pattern = "$attribute=([^,\\s]+)"
        val regex = Regex(pattern)
        val matchResult = regex.find(line)
        return matchResult?.groupValues?.get(1)?.replace("\"", "")
    }

    private fun determineQualityFromAttributes(bandwidth: String?, resolution: String?): String {
        // First try to determine quality from resolution
        resolution?.let { res ->
            when {
                res.contains("1920x1080") || res.contains("1080") -> return "1080p"
                res.contains("1280x720") || res.contains("720") -> return "720p"
                res.contains("854x480") || res.contains("480") -> return "480p"
                res.contains("640x360") || res.contains("360") -> return "360p"
                else -> "Hls"
            }
        }

        // If no resolution, try to determine from bandwidth
        bandwidth?.let { bw ->
            val bandwidthInt = bw.toIntOrNull() ?: 0
            return when {
                bandwidthInt >= 5000000 -> "1080p" // 5+ Mbps
                bandwidthInt >= 2500000 -> "720p"  // 2.5+ Mbps
                bandwidthInt >= 1000000 -> "480p"  // 1+ Mbps
                bandwidthInt >= 500000 -> "360p"   // 500+ Kbps
                else -> "Unknown"
            }
        }

        return "HLS"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            if (relativeUrl.startsWith("http")) {
                relativeUrl
            } else {
                // Extract base URL without filename
                val baseUrlParts = baseUrl.split("/")
                val basePath = baseUrlParts.dropLast(1).joinToString("/")
                "$basePath/$relativeUrl"
            }
        } catch (e: Exception) {
            relativeUrl
        }
    }



    private fun extractVideoUrlsFromHtml(htmlCode: String): List<String> {
        val evalStart = htmlCode.indexOf("eval(function(p,a,c,k,e,d)")

        if (evalStart == -1) {
            println("Could not find the packed JavaScript in the HTML.")
            return emptyList()
        }

        // Find the end of this eval statement
        var bracketCount = 0
        var evalEnd = evalStart

        for (i in evalStart until htmlCode.length) {
            when (htmlCode[i]) {
                '(' -> bracketCount++
                ')' -> {
                    bracketCount--
                    if (bracketCount == 0) {
                        evalEnd = i + 1
                        break
                    }
                }
            }
        }

        if (evalEnd <= evalStart) {
            println("Could not find end of eval statement")
            return emptyList()
        }

        val packedScript = htmlCode.substring(evalStart, evalEnd)
        val deobfuscatedCode = deobfuscatePackedJs(packedScript)

        if (deobfuscatedCode.isEmpty()) {
            println("Deobfuscation failed.")
            return emptyList()
        }

        return findAllUrls(deobfuscatedCode)
    }

    private fun findAllUrls(text: String, prefix: String = "file:\"", suffix: String = "\""): List<String> {
        val urls = mutableListOf<String>()
        var startPos = 0

        while (true) {
            val pos = text.indexOf(prefix, startPos)
            if (pos == -1) break

            val urlStart = pos + prefix.length
            val urlEnd = text.indexOf(suffix, urlStart)
            if (urlEnd == -1) break

            val url = text.substring(urlStart, urlEnd)

            if (url.startsWith("http") && (url.contains(".mp4") || url.contains(".m3u8"))) {
                urls.add(url)
            }

            startPos = urlEnd + 1
        }

        return urls
    }

    private fun extractPackedComponents(packedJs: String): PackedComponents? {
        // Find all occurrences of "}('"
        val patternPositions = mutableListOf<Int>()
        var searchStart = 0

        while (true) {
            val pos = packedJs.indexOf("}('", searchStart)
            if (pos == -1) break
            patternPositions.add(pos)
            searchStart = pos + 1
        }

        if (patternPositions.isEmpty()) {
            return null
        }

        // Try the last occurrence
        val patternStart = patternPositions.last()
        val startPos = patternStart + 3

        // Find the first string (p parameter)
        var quoteCount = 0
        var pEnd = -1
        var i = startPos

        while (i < packedJs.length) {
            when {
                packedJs[i] == '\\' -> i += 2 // Skip escaped character
                packedJs[i] == '\'' -> {
                    quoteCount++
                    if (quoteCount == 1 && i + 1 < packedJs.length && packedJs[i + 1] == ',') {
                        pEnd = i
                        break
                    }
                    i++
                }
                else -> i++
            }
        }

        if (pEnd == -1) return null

        val p = packedJs.substring(startPos, pEnd)

        // Find the first number (a parameter)
        var numStart = pEnd + 2 // Skip ','
        while (numStart < packedJs.length && packedJs[numStart].isWhitespace()) {
            numStart++
        }

        var numEnd = numStart
        while (numEnd < packedJs.length && packedJs[numEnd].isDigit()) {
            numEnd++
        }

        if (numEnd == numStart) return null

        val a = try {
            packedJs.substring(numStart, numEnd).toInt()
        } catch (e: NumberFormatException) {
            return null
        }

        // Find the second number (c parameter)
        val commaPos = packedJs.indexOf(',', numEnd)
        if (commaPos == -1) return null

        var num2Start = commaPos + 1
        while (num2Start < packedJs.length && packedJs[num2Start].isWhitespace()) {
            num2Start++
        }

        var num2End = num2Start
        while (num2End < packedJs.length && packedJs[num2End].isDigit()) {
            num2End++
        }

        if (num2End == num2Start) return null

        val c = try {
            packedJs.substring(num2Start, num2End).toInt()
        } catch (e: NumberFormatException) {
            return null
        }

        // Find the keyword string (k parameter)
        val kQuoteStart = packedJs.indexOf('\'', num2End)
        if (kQuoteStart == -1) return null

        val kStart = kQuoteStart + 1

        // Find the end of the quoted string
        var kEnd = -1
        i = kStart
        while (i < packedJs.length) {
            when {
                packedJs[i] == '\\' -> i += 2 // Skip escaped character
                packedJs[i] == '\'' -> {
                    if (packedJs.substring(i).startsWith("'.split")) {
                        kEnd = i
                        break
                    }
                    i++
                }
                else -> i++
            }
        }

        if (kEnd == -1) return null

        val kString = packedJs.substring(kStart, kEnd)
        return PackedComponents(p, a, c, kString)
    }

    private fun getBaseAString(num: Int, base: Int): String {
        return if (num < base) {
            if (num < 10) num.toString() else (('a'.code + num - 10).toChar()).toString()
        } else {
            getBaseAString(num / base, base) + getBaseAString(num % base, base)
        }
    }

    private fun replaceWords(text: String, lookup: Map<String, String>): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            if (char.isLetterOrDigit() || char == '_') {
                val wordStart = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) {
                    i++
                }

                val word = text.substring(wordStart, i)
                result.append(lookup[word] ?: word)
            } else {
                result.append(char)
                i++
            }
        }

        return result.toString()
    }

    private fun deobfuscatePackedJs(packedJs: String): String {
        return try {
            val components = extractPackedComponents(packedJs)
            if (components == null) {
                return ""
            }

            val (p, a, c, kString) = components

            // Handle escaped quotes and split by |
            val k = kString.replace("\\'", "'").split("|")

            // Build the dictionary for replacements
            val lookup = mutableMapOf<String, String>()
            for (i in c - 1 downTo 0) {
                val key = getBaseAString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                lookup[key] = value
            }

            // Replace keywords manually by tokenizing
            replaceWords(p, lookup)

        } catch (e: Exception) {
            ""
        }
    }

    private fun determineQuality(videoUrl: String,index: Int): String {
        return when {
            videoUrl.contains("1080") || index == 0-> "1080p"
            videoUrl.contains("720") || index == 1-> "720p"
            videoUrl.contains("480") || index == 2-> "480p"
            videoUrl.contains("360") || index == 3-> "360p"
            videoUrl.contains(".m3u8") -> "HLS"
            else -> "Unknown"
        }
    }


    // ============================== Search ==============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        // --- START: CORRECTED URL LOGIC ---
        // The search URL structure is /search/query/
        // The site does not seem to support pagination for search results, so the 'page' parameter is ignored.
        val url = "$BASE_URL/search/$query/"
        // --- END: CORRECTED URL LOGIC ---

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        searchParse(response) // Call the new dedicated search parser
    }

    // --- NEW DEDICATED PARSER FOR SEARCH RESULTS ---
    private fun searchParse(response: Response): MangaPage {
        val document = Jsoup.parse(response.body!!.string(), BASE_URL)

        // The search results are in article.post elements directly inside the main row.
        val animeElements = document.select("article.post")

        val animeList = animeElements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            SAnime().apply {
                // Links on the search page are direct, no decoding needed.
                url = linkElement.attr("abs:href")
                title = linkElement.attr("title").replace(" - قصة عشق", "").trim()
                val style = linkElement.selectFirst(".imgBg")?.attr("style")
                if (style != null) {
                    thumbnail_url = getBackgroundImageUrl(style)
                }
            }
        }

        // The search results page does not have a "next page" button.
        return MangaPage(animeList, hasNextPage = false)
    }

}


