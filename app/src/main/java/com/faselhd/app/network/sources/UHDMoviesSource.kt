package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

//region DTOs for UHDMovies API
@Serializable
data class DomainsParserr(
    @SerialName("moviesdrive") val moviesdrive: String = "",
    @SerialName("HDHUB4u") val hdhub4u: String = "",
    @SerialName("4khdhub") val n4khdhub: String = "",
    @SerialName("MultiMovies") val multiMovies: String = "",
    @SerialName("bollyflix") val bollyflix: String = "",
    @SerialName("UHDMovies") val uhdmovies: String = "",
    @SerialName("moviesmod") val moviesmod: String = "",
    @SerialName("topMovies") val topMovies: String = "",
    @SerialName("hdmovie2") val hdmovie2: String = "",
    @SerialName("vegamovies") val vegamovies: String = "",
    @SerialName("rogmovies") val rogmovies: String = "",
    @SerialName("luxmovies") val luxmovies: String = ""
)

@Serializable
data class UHDLinks(
    @SerialName("sourceName") val sourceName: String = "",
    @SerialName("sourceLink") val sourceLink: String = ""
)
//endregion

class UHDMoviesSource(private val context: Context) {

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
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var baseUrl = "https://uhdmovies.tube"
    private val domainsUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private var cachedDomains: DomainsParserr? = null

    // Get updated domain from GitHub
    private suspend fun getDomains(forceRefresh: Boolean = false): DomainsParserr? {
        if (cachedDomains == null || forceRefresh) {
            try {
                val request = Request.Builder().url(domainsUrl).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return null
                response.close()

                cachedDomains = json.decodeFromString<DomainsParserr>(responseBody)
                // Update base URL if available
                cachedDomains?.uhdmovies?.let {
                    if (it.isNotEmpty()) baseUrl = it
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
        return cachedDomains
    }

    private suspend fun makeRequest(url: String): Document? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            val document = Jsoup.parse(response.body?.string() ?: "")
            response.close()

            // Check if Cloudflare protection is active
            if (document.select("title").text() == "Just a moment...") {
                // For now, return null - in a real implementation you'd handle CF bypass
                null
            } else {
                document
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun makeRequestRaw(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()
            responseBody
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun makePostRequest(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val formBody = okhttp3.FormBody.Builder()
            formData.forEach { (key, value) ->
                formBody.add(key, value)
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .post(formBody.build())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()
            response.close()
            responseBody
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun makeRequestWithCookies(url: String, cookies: Map<String, String>): String? {
        return try {
            val cookieString = cookies.map { "${it.key}=${it.value}" }.joinToString("; ")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Cookie", cookieString)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()
            responseBody
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url.substringBefore("/", url)
        }
    }

    private fun fixUrl(path: String, domain: String): String {
        return when {
            path.startsWith("http") -> path
            path.isEmpty() -> ""
            path.startsWith("//") -> "https:$path"
            path.startsWith('/') -> domain + path
            else -> "$domain/$path"
        }
    }

    private fun Element.toSearchResult(): SAnime? {
        return try {
            val titleRaw = this.select("h1.sanket").text().trim().removePrefix("Download ")
            val titleRegex = Regex("(^.*\\)\\d*)")
            val title = titleRegex.find(titleRaw)?.groups?.get(1)?.value ?: titleRaw
            val href = this.select("div.entry-image > a").attr("href")
            val posterUrl = this.select("div.entry-image > a > img").attr("src")

            if (href.isEmpty()) return null

            SAnime().apply {
                this.title = title
                this.url = href
                this.thumbnail_url = if (posterUrl.startsWith("http")) posterUrl else "$baseUrl$posterUrl"
                this.description = if (titleRaw.contains("season|S0", true) || titleRaw.contains("episode", true)) "TV Series" else "Movie"
                this.source = "UHDMOVIES"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // =============================== Popular/Homepage ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            // Update domains first
            getDomains()

            val url = if (page == 1) {
                "$baseUrl/"
            } else {
                "$baseUrl/page/$page/"
            }

            val document = makeRequest(url) ?: return@withContext MangaPage(emptyList(), false)

            val movies = document.select("article.gridlove-post").mapNotNull {
                it.toSearchResult()
            }

            MangaPage(movies, movies.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =============================== Latest Updates ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            getDomains()

            val url = if (page == 1) {
                "$baseUrl/movies/"
            } else {
                "$baseUrl/movies/page/$page/"
            }

            val document = makeRequest(url) ?: return@withContext MangaPage(emptyList(), false)

            val movies = document.select("article.gridlove-post").mapNotNull {
                it.toSearchResult()
            }

            MangaPage(movies, movies.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        try {
            getDomains()

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl?s=$encodedQuery"

            val document = makeRequest(url) ?: return@withContext MangaPage(emptyList(), false)

            val searchResults = document.select("article.gridlove-post").mapNotNull {
                it.toSearchResult()
            }

            MangaPage(searchResults, false)
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =========================== Details & Episodes ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        try {
            val document = makeRequest(animeUrl) ?: return@withContext SAnime()

            val titleRaw = document.select("div.gridlove-content div.entry-header h1.entry-title").text().trim().removePrefix("Download ")
            val titleRegex = Regex("(^.*\\)\\d*)")
            val title = titleRegex.find(titleRaw)?.groups?.get(1)?.value ?: titleRaw
            val poster = document.selectFirst("div.entry-content p img")?.attr("src")
            val yearRegex = Regex("(?<=\\()[\\d(\\]]+(?!=\\))")
            val year = yearRegex.find(title)?.value?.toIntOrNull()
            val tags = document.select("div.entry-category > a.gridlove-cat").map { it.text() }
            val description = document.select("div.entry-content p").firstOrNull()?.text() ?: ""

            SAnime().apply {
                this.title = title
                this.url = animeUrl
                this.thumbnail_url = if (poster?.startsWith("http") == true) poster else poster?.let { "$baseUrl$it" } ?: ""
                this.description = description
                this.genre = tags.joinToString(", ")
                this.status = SAnime.COMPLETED
                this.source = "UHDMOVIES"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            SAnime().apply {
                url = animeUrl
                source = "UHDMOVIES"
            }
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        try {
            val document = makeRequest(animeUrl) ?: return@withContext emptyList()
            val episodes = mutableListOf<SEpisode>()

            val tvTags = document.selectFirst("h1.entry-title")?.text() ?: ""
            val isTvSeries = tvTags.contains("Season") || tvTags.contains("S0")

            if (isTvSeries) {
                // Handle TV Series with episodes
                var pTags = document.select("p:has(a:contains(Episode))")
                if (pTags.isEmpty()) {
                    pTags = document.select("div:has(a:contains(Episode))")
                }

                var season = 1
                pTags.forEachIndexed { seasonIndex, pTag ->
                    val prevPtag = pTag.previousElementSibling()
                    val details = prevPtag?.text() ?: ""
                    val realSeason = Regex("""(?:Season |S0)(\d+)""").find(details)?.groupValues?.get(1) ?: season.toString()

                    val aTags = pTag.select("a:contains(Episode)")
                    aTags.forEachIndexed { episodeIndex, aTag ->
                        val episodeName = aTag.text()
                        val episodeUrl = aTag.attr("href")

                        if (episodeUrl.isNotEmpty()) {
                            episodes.add(SEpisode().apply {
                                name = "الموسم $realSeason : $episodeName"
                                url = episodeUrl
                                episode_number = (episodeIndex + 1).toFloat()
                            })
                        }
                    }
                    season++
                }
            } else {
                // Handle Movie (single episode)
                val title = document.select("h1.entry-title").text().removePrefix("Download ")
                episodes.add(SEpisode().apply {
                    name = title
                    url = animeUrl
                    episode_number = 1f
                })
            }

            episodes
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Bypass Functions =============================
    private suspend fun bypassHrefli(url: String): String? {
        return try {
            val host = getBaseUrl(url)
            var response = makeRequestRaw(url) ?: return null
            var document = Jsoup.parse(response)

            // Get form data
            val formUrl = document.select("form#landing").attr("action")
            val formData = document.select("form#landing input").associate {
                it.attr("name") to it.attr("value")
            }

            if (formUrl.isEmpty()) return null

            // First form submission
            response = makePostRequest(formUrl, formData) ?: return null
            document = Jsoup.parse(response)

            val formUrl2 = document.select("form#landing").attr("action")
            val formData2 = document.select("form#landing input").associate {
                it.attr("name") to it.attr("value")
            }

            if (formUrl2.isEmpty()) return null

            // Second form submission
            response = makePostRequest(formUrl2, formData2) ?: return null
            document = Jsoup.parse(response)

            val skToken = document.selectFirst("script:containsData(?go=)")?.data()
                ?.substringAfter("?go=")?.substringBefore("\"") ?: return null

            val cookies = mapOf(skToken to (formData2["_wp_http2"] ?: ""))
            val redirectResponse = makeRequestWithCookies("$host?go=$skToken", cookies) ?: return null
            val redirectDocument = Jsoup.parse(redirectResponse)

            val driveUrl = redirectDocument.selectFirst("meta[http-equiv=refresh]")
                ?.attr("content")?.substringAfter("url=") ?: return null

            val pathResponse = makeRequestRaw(driveUrl) ?: return null
            val path = pathResponse.substringAfter("replace(\"").substringBefore("\")")

            if (path == "/404") return null

            fixUrl(path, getBaseUrl(driveUrl))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractDriveLink(url: String): String? {
        return try {
            when {
                url.contains("driveseed") || url.contains("driveleech") -> {
                    extractDriveSeedLink(url)
                }
                url.contains("unblockedgames") -> {
                    bypassHrefli(url)
                }
                url.contains("workers.dev") -> {
                    url // Direct workers.dev link
                }
                else -> url
            }
        } catch (e: Exception) {
            e.printStackTrace()
            url
        }
    }

    private suspend fun extractDriveSeedLink(url: String): String? {
        return try {
            val document = makeRequest(url) ?: return null

            // Look for instant download link
            val instantLink = document.selectFirst("a:contains(Instant Download)")?.attr("href")
            if (!instantLink.isNullOrEmpty()) {
                return extractInstantLink(instantLink)
            }

            // Look for resume bot link
            val resumeBotLink = document.selectFirst("a:contains(Resume Worker Bot)")?.attr("href")
            if (!resumeBotLink.isNullOrEmpty()) {
                return extractResumeBotLink(resumeBotLink)
            }

            // Look for direct links
            val directLink = document.selectFirst("a.btn-success")?.attr("href")
            if (!directLink.isNullOrEmpty() && (directLink.endsWith(".mp4") || directLink.endsWith(".mkv"))) {
                return directLink
            }

            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractInstantLink(url: String): String? {
        return try {
            val host = getBaseUrl(url)
            val token = url.substringAfter("url=")

            val formData = mapOf("keys" to token)
            val response = makePostRequest("$host/api", formData, mapOf(
                "x-token" to java.net.URI(url).host,
                "Referer" to url
            ))

            if (response != null) {
                val jsonResponse = json.decodeFromString<Map<String, String>>(response)
                jsonResponse["url"]?.replace("\\/", "/")
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractResumeBotLink(url: String): String? {
        return try {
            val response = makeRequestRaw(url) ?: return null
            val token = Regex("formData\\.append\\('token', '([a-f0-9]+)'\\)")
                .find(response)?.groupValues?.get(1) ?: return null
            val path = Regex("""fetch\('/download\?id=([a-zA-Z0-9+/]+)'\)""")

                .find(response)?.groupValues?.get(1) ?: return null

            val baseUrl = url.split("/download")[0]
            val formData = mapOf("token" to token)

            val jsonResponse = makePostRequest("$baseUrl/download?id=$path", formData, mapOf(
                "Accept" to "*/*",
                "Origin" to baseUrl,
                "Sec-Fetch-Site" to "same-origin",
                "Referer" to url
            )) ?: return null

            val responseMap = json.decodeFromString<Map<String, String>>(jsonResponse)
            responseMap["url"]
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val document = makeRequest(episodeUrl) ?: return@withContext emptyList()
            val videos = mutableListOf<Video>()

            // Extract all download links from the page
            val downloadLinks = mutableListOf<Pair<String, String>>() // Pair of (quality, url)

            // Method 1: Look for direct download buttons
            val downloadButtons = document.select("a.maxbutton-1, a[class*=maxbutton], a.btn, a[href*='driveleech'], a[href*='driveseed']")
            downloadButtons.forEach { button ->
                val linkUrl = button.attr("href")
                val linkText = button.text().trim()

                if (linkUrl.isNotEmpty() && linkUrl.startsWith("http")) {
                    // Extract quality info from the surrounding context
                    var qualityInfo = linkText

                    // Try to get quality from parent or previous sibling elements
                    val parentText = button.parent()?.text() ?: ""
                    val prevSibling = button.previousElementSibling()?.text() ?: ""

                    // Look for quality indicators in the context
                    val contextText = "$parentText $prevSibling $linkText".trim()
                    val qualityMatch = Regex("(\\d{3,4}[pP]|[0-9.]+\\s*GB|HEVC|x264|x265|HDR|WEB-DL|BluRay|1080p|720p|480p|4K|2160p)").findAll(contextText).joinToString(" ") { it.value }

                    if (qualityMatch.isNotEmpty()) {
                        qualityInfo = qualityMatch
                    }

                    downloadLinks.add(Pair(qualityInfo.ifEmpty { "Download" }, linkUrl))
                }
            }

            // Method 2: Look for links in paragraph text (common pattern in UHDMovies)
            val paragraphs = document.select("div.entry-content p, div.post-content p")
            paragraphs.forEach { paragraph ->
                val paragraphText = paragraph.text()

                // Look for quality indicators in paragraph text
                val hasQualityInfo = Regex("(\\d{3,4}[pP]|[0-9.]+\\s*GB|HEVC|x264|x265|HDR|WEB-DL|BluRay)").containsMatchIn(paragraphText)

                if (hasQualityInfo) {
                    // Find download links in next few siblings
                    var nextElement = paragraph.nextElementSibling()
                    var searchDepth = 0

                    while (nextElement != null && searchDepth < 3) {
                        val links = nextElement.select("a[href*='driveleech'], a[href*='driveseed'], a.maxbutton-1, a[class*=maxbutton]")
                        links.forEach { link ->
                            val linkUrl = link.attr("href")
                            if (linkUrl.isNotEmpty() && linkUrl.startsWith("http")) {
                                downloadLinks.add(Pair(paragraphText.trim(), linkUrl))
                            }
                        }
                        nextElement = nextElement.nextElementSibling()
                        searchDepth++
                    }
                }
            }

            // Method 3: Look for structured download sections
            val downloadSections = document.select("div:has(a[href*='driveleech']), div:has(a[href*='driveseed']), div:has(a.maxbutton)")
            downloadSections.forEach { section ->
                val sectionText = section.ownText().trim()
                val links = section.select("a[href*='driveleech'], a[href*='driveseed'], a.maxbutton-1, a[class*=maxbutton]")

                links.forEach { link ->
                    val linkUrl = link.attr("href")
                    val linkText = link.text().trim()

                    if (linkUrl.isNotEmpty() && linkUrl.startsWith("http")) {
                        val qualityInfo = if (sectionText.isNotEmpty()) sectionText else linkText
                        downloadLinks.add(Pair(qualityInfo, linkUrl))
                    }
                }
            }

            // Process all found download links
            downloadLinks.distinctBy { it.second }.forEach { (quality, url) ->
                try {
                    // Extract the actual video URL using bypass functions
                    val actualVideoUrl = extractDriveLink(url) ?: url

                    // Clean up quality text
                    val cleanQuality = quality.replace("Download", "").trim().ifEmpty { "Unknown Quality" }

                    videos.add(Video(
                        url = actualVideoUrl,
                        quality = cleanQuality,
                        videoUrl = actualVideoUrl,
                        headers = mapOf(
                            "Referer" to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Add the original URL as fallback
                    videos.add(Video(
                        url = url,
                        quality = quality.replace("Download", "").trim().ifEmpty { "Link" },
                        videoUrl = url,
                        headers = mapOf(
                            "Referer" to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    ))
                }
            }

            // If no videos found with above methods, try generic link extraction
            if (videos.isEmpty()) {
                val genericLinks = document.select("a[href]").filter { element ->
                    val href = element.attr("href")
                    href.contains("driveleech") || href.contains("driveseed") ||
                            href.contains("drive.google.com") || href.contains("mega.nz") ||
                            element.hasClass("maxbutton-1") || element.hasClass("btn")
                }

                genericLinks.forEach { link ->
                    val linkUrl = link.attr("href")
                    val linkText = link.text().trim()

                    if (linkUrl.isNotEmpty() && linkUrl.startsWith("http")) {
                        videos.add(Video(
                            url = linkUrl,
                            quality = linkText.ifEmpty { "Download Link" },
                            videoUrl = linkUrl,
                            headers = mapOf(
                                "Referer" to baseUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        ))
                    }
                }
            }

            // If still no videos found, create a placeholder
            if (videos.isEmpty()) {
                videos.add(Video(
                    url = episodeUrl,
                    quality = "Visit Page",
                    videoUrl = episodeUrl,
                    headers = mapOf(
                        "Referer" to baseUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ))
            }

            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }


    // ============================ Main Slider =============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            getDomains()
            val document = makeRequest(baseUrl) ?: return@withContext emptyList()

            // Get featured/slider content
            val sliderItems = document.select("article.gridlove-post").take(5).mapNotNull {
                it.toSearchResult()
            }

            sliderItems
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Filters =============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}


