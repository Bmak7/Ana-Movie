package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.text.Regex

class WitAnimeSource(private val context: Context) {

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
        val cookieManager = CookieManager()
        val cookieJar: CookieJar = JavaNetCookieJar(cookieManager)

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .cookieJar(cookieJar)
            .addInterceptor(CloudflareInterceptor(context, cookieJar))
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
                    .header("Referer", baseUrl)
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .build()
                chain.proceed(newRequest)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl = "https://witanime.red"

    // Data class for server information
    data class ServerInfo(
        val name: String,
        val serverId: String,
    )
        suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            document.select(".owl-carousel-lucodeia-slider .item a.lucodeia-slider-slide-item").mapNotNull {
                SAnime().apply {
                    url = it.attr("abs:href")
                    title = it.attr("title")
                    thumbnail_url = it.attr("style").substringAfter("background-image: url(").substringBefore(")")
                    source = AnimeSource.WITANIME.name
                }
            }.take(10)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/?s=${query.replace(" ", "+")}"
            val request = Request.Builder().url(url).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val animeList = document.select(".anime-card-container, .episodes-card-container").mapNotNull { element ->
                val linkElement = element.selectFirst("a") ?: return@mapNotNull null
                toAnime(linkElement)
            }

            MangaPage(animeList, hasNextPage = animeList.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            url = animeUrl
            title = document.selectFirst("h1, .title-name")?.text() ?: "Unknown Title"
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.poster, .anime-card-poster img")?.attr("src")

            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst(".main-widget-body p")?.text()

            genre = document.select("a[href*=/anime-genre/]").joinToString(", ") { it.text().trim() }

            val statusText = document.selectFirst(".anime-card-status a")?.text() ?: ""
            status = getStatus(statusText)

            source = AnimeSource.WITANIME.name
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(animeUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val animeTitle = document.selectFirst("h1, .title-name")?.text() ?: "Unknown"

            document.select(".all-episodes-list li a").mapNotNull { element ->
                createEpisode(element, animeTitle)
            }.sortedBy { it.episode_number }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            val urll = if (page > 1) "$baseUrl/episode/page/$page/" else "$baseUrl/episode/"
            val request = Request.Builder().url(urll).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val animeList = document.select(".episodes-card-container").map { element ->
                SAnime().apply {
                    url = element.selectFirst("a")?.attr("abs:href") ?: ""
                    title = element.selectFirst(".ep-card-anime-title h3")?.text() ?: "Unknown Title"
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                    source = AnimeSource.WITANIME.name
                }
            }

            val hasNextPage = document.select(".pagination a.next").isNotEmpty()
            MangaPage(animeList, hasNextPage)
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(episodeUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

            val videos = mutableListOf<Video>()

            // Extract server information
            val servers = extractServers(document)
            Log.d("WitAnime", "Found ${servers.size} servers")

            // For each server, try to get the video URL
            for (server in servers) {
                try {
                    val videoUrl = getServerVideoUrl(episodeUrl, server.serverId, server.name)
                    if (videoUrl.isNotEmpty()) {
                        videos.add(Video(
                            url = videoUrl,
                            quality = "${server.serverId} - ${server.name}",
                            videoUrl = videoUrl,
                            headers = mapOf(
                                "Referer" to episodeUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("WitAnime", "Error extracting video from server ${server.name}", e)
                }
            }

            // Also extract download links
            extractDownloadLinks(document).forEach { video ->
                videos.add(video)
            }

            Log.d("WitAnime", "Total videos found: ${videos.size}")
            videos.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e("WitAnime", "Error fetching video list", e)
            emptyList()
        }
    }


    private suspend fun extractServers(document: Document): List<ServerInfo> {
        return document.select("ul#episode-servers a.server-link").map { serverElement ->
            val serverName = serverElement.selectFirst("span.ser")?.text()?.trim() ?: "Unknown"
            val serverId = serverElement.attr("data-server-id")
            val quality = extractQualityFromServerName(serverName)

            ServerInfo(serverName, serverId)
        }
    }

    private suspend fun getServerVideoUrl(episodeUrl: String, serverId: String, serverName: String): String {
        return try {
            // This simulates clicking on a server tab
            // In a real implementation, you might need to make additional requests
            // or use JavaScript execution to get the actual video URL

            when {
                serverName.contains("videa", ignoreCase = true) -> {
                    extractVideaUrl(episodeUrl, serverId)
                }
                serverName.contains("ok.ru", ignoreCase = true) -> {
                    extractOkRuUrl(episodeUrl, serverId)
                }
                serverName.contains("dailymotion", ignoreCase = true) -> {
                    extractDailymotionUrl(episodeUrl, serverId)
                }
                serverName.contains("streamwish", ignoreCase = true) -> {
                    extractStreamwishUrl(episodeUrl, serverId)
                }
                else -> {
                    extractGenericServerUrl(episodeUrl, serverId)
                }
            }
        } catch (e: Exception) {
            Log.e("WitAnime", "Error getting video URL for server $serverName", e)
            ""
        }
    }

    private suspend fun extractVideaUrl(episodeUrl: String, serverId: String): String {
        // Make a request that simulates server selection
        val request = Request.Builder()
            .url(episodeUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        // Look for videa.hu URLs in the response
        val videaRegex = """https://videa\.hu/player\?v=([^"'\s]+)""".toRegex()
        val match = videaRegex.find(html)

        return match?.value ?: ""
    }

    private suspend fun extractOkRuUrl(episodeUrl: String, serverId: String): String {
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        val okruRegex = """https://ok\.ru/videoembed/[^"'\s]+""".toRegex()
        val match = okruRegex.find(html)

        return match?.value ?: ""
    }

    private suspend fun extractDailymotionUrl(episodeUrl: String, serverId: String): String {
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        val dailymotionRegex = """https://www\.dailymotion\.com/embed/[^"'\s]+""".toRegex()
        val match = dailymotionRegex.find(html)

        return match?.value ?: ""
    }

    private suspend fun extractStreamwishUrl(episodeUrl: String, serverId: String): String {
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        val streamwishRegex = """https://[^"'\s]*streamwish[^"'\s]+""".toRegex()
        val match = streamwishRegex.find(html)

        return match?.value ?: ""
    }

    private suspend fun extractGenericServerUrl(episodeUrl: String, serverId: String): String {
        // Generic extraction for unknown servers
        val request = Request.Builder().url(episodeUrl).build()
        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        // Look for common video URL patterns
        val urlPatterns = listOf(
            """https://[^"'\s]+\.mp4[^"'\s]*""".toRegex(),
            """https://[^"'\s]+\.m3u8[^"'\s]*""".toRegex(),
            """https://[^"'\s]+/embed/[^"'\s]+""".toRegex()
        )

        for (pattern in urlPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.value
            }
        }

        return ""
    }

    private fun extractDownloadLinks(document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        document.select(".download-link").forEach { downloadElement ->
            val dataIndex = downloadElement.attr("data-index")
            val providerElement = downloadElement.selectFirst(".notice")
            val provider = providerElement?.text() ?: "Unknown"

            // Find the quality section this download belongs to
            val qualitySection = downloadElement.closest(".col-md-6")
            val qualityHeader = qualitySection?.selectFirst("li")?.text() ?: ""

            val quality = when {
                qualityHeader.contains("FHD") -> "1080p"
                qualityHeader.contains("HD") && !qualityHeader.contains("FHD") -> "720p"
                qualityHeader.contains("SD") -> "480p"
                else -> "Unknown"
            }

            // Note: The actual download URL would need to be resolved
            // This is a placeholder - you'd need to handle the JavaScript
            // that resolves the actual download URLs
            videos.add(Video(
                url = "download_link_$dataIndex", // Placeholder
                quality = "$quality - $provider (Download)",
                videoUrl = "download_link_$dataIndex"
            ))
        }

        return videos
    }

    private fun toAnime(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("abs:href")
            title = element.attr("title").ifEmpty {
                element.selectFirst("h3, h2, .title-name")?.text() ?: "Unknown"
            }
            thumbnail_url = element.selectFirst("img")?.attr("src")?.ifEmpty {
                element.selectFirst("img")?.attr("data-src")
            }
            source = AnimeSource.WITANIME.name
        }
    }

    private fun createEpisode(element: Element, seriesName: String, episodeUrl: String = ""): SEpisode {
        val episodeText = element.text()
        val finalUrl = episodeUrl.ifEmpty { element.attr("abs:href") }

        return SEpisode().apply {
            url = finalUrl
            name = "$seriesName: $episodeText"

            // Extract episode number from Arabic text like "الحلقة 8"
            episode_number = Regex("""الحلقة\s+(\d+)""").find(episodeText)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""(\d+)""").find(episodeText)?.value?.toFloatOrNull()
                        ?: 0f
        }
    }


    // Additional utility functions for better video extraction
    suspend fun getDirectVideoUrl(embedUrl: String): String = withContext(Dispatchers.IO) {
        try {
            when {
                embedUrl.contains("videa.hu") -> {
                    // Extract direct video URL from Videa player
                    extractVideaDirect(embedUrl)
                }
                embedUrl.contains("ok.ru") -> {
                    // Extract direct video URL from OK.ru
                    extractOkRuDirect(embedUrl)
                }
                embedUrl.contains("dailymotion") -> {
                    // Extract direct video URL from Dailymotion
                    extractDailymotionDirect(embedUrl)
                }
                else -> embedUrl
            }
        } catch (e: Exception) {
            Log.e("WitAnime", "Error extracting direct video URL", e)
            embedUrl
        }
    }

    private suspend fun extractVideaDirect(embedUrl: String): String {
        val request = Request.Builder()
            .url(embedUrl)
            .header("Referer", baseUrl)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body!!.string()

        // Look for direct video URL in Videa player
        val videoRegex = """["'](?:video_url|src)["']:\s*["']([^"']+\.mp4[^"']*)["']""".toRegex()
        val match = videoRegex.find(html)

        return match?.groupValues?.get(1) ?: embedUrl
    }

    private suspend fun extractOkRuDirect(embedUrl: String): String {
        // OK.ru direct extraction would require more complex parsing
        // This is a simplified version
        return embedUrl
    }

    private suspend fun extractDailymotionDirect(embedUrl: String): String {
        // Dailymotion direct extraction would require API calls
        // This is a simplified version
        return embedUrl
    }



    private fun createEpisode(element: Element, seriesName: String): SEpisode {
        val episodeText = element.text()

        return SEpisode().apply {
            url = element.attr("abs:href")
            name = "$seriesName: $episodeText"

            // Extract episode number from text like "الحلقة 8"
            episode_number = Regex("""الحلقة\s+(\d+)""").find(episodeText)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""(\d+)""").find(episodeText)?.value?.toFloatOrNull()
                        ?: 0f
        }
    }

    private fun getStatus(statusString: String): Int {
        return when {
            statusString.contains("منتهي", ignoreCase = true) ||
                    statusString.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
            statusString.contains("يعرض الان", ignoreCase = true) ||
                    statusString.contains("قيد البث", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun extractQualityFromServerName(serverName: String): String {
        return when {
            serverName.contains("FHD", ignoreCase = true) -> "1080p"
            serverName.contains("HD", ignoreCase = true) -> "720p"
            serverName.contains("SD", ignoreCase = true) -> "480p"
            else -> "Unknown"
        }
    }

    private fun extractQualityFromText(text: String): String {
        return when {
            text.contains("FHD", ignoreCase = true) -> "1080p"
            text.contains("HD", ignoreCase = true) -> "720p"
            text.contains("SD", ignoreCase = true) -> "480p"
            text.contains("عالية", ignoreCase = true) -> "High Quality"
            text.contains("متوسطة", ignoreCase = true) -> "Medium Quality"
            text.contains("منخفضة", ignoreCase = true) -> "Low Quality"
            else -> "Unknown"
        }
    }

    fun getFilterList() = AnimeFilterList(emptyList())
}

