package com.faselhd.app.network.sources

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.*
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
import java.util.regex.Pattern
import java.net.URLEncoder
import com.google.gson.Gson
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import java.io.File

//region DTOs for HiAnime API
@Serializable
data class HiAnimeResponse(
    val status: Boolean,
    val html: String
)

@Serializable
data class HiAnimeSyncData(
    @SerialName("mal_id") val malId: String? = null,
    @SerialName("anilist_id") val aniListId: String? = null,
)

@Serializable
data class HiAnimeEpisodeServers(
    val type: String = "",
    val link: String = "",
    val server: Long = 0
)

@Serializable
data class HiAnimeVideoSources(
    val sources: List<HiAnimeSourcee> = emptyList(),
    val tracks: List<HiAnimeTrack> = emptyList(),
    val encrypted: Boolean = false
)

@Serializable
data class HiAnimeSourcee(
    val file: String = "",
    val type: String = "",
    val label: String? = null
)

@Serializable
data class HiAnimeTrack(
    val file: String = "",
    val label: String = "",
    val kind: String = "",
    val default: Boolean? = null
)

// Megacloud extractor DTOs
data class MegacloudResponse(
    val sources: List<MegacloudSource>,
    val tracks: List<MegacloudTrack>,
    val encrypted: Boolean,
    val intro: MegacloudIntro?,
    val outro: MegacloudOutro?,
    val server: Long
)

data class MegacloudSource(
    val file: String,
    val type: String
)

data class MegacloudTrack(
    val file: String,
    val label: String,
    val kind: String,
    val default: Boolean? = null
)

data class MegacloudIntro(val start: Long, val end: Long)
data class MegacloudOutro(val start: Long, val end: Long)
data class MegacloudKey(val rabbit: String, val mega: String)
//endregion

class HiAnimeSource(private val context: Context) {

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                // Note that you need to add a ResponseInterceptor to make this 100% active.
                // The server response dictates if and when stuff should be cached.
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            // Needs to be build as otherwise the other builders will change this object
            .build()
    }
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val gson = Gson()
    private val baseUrl = "https://hianimez.is"

    private fun Element.toSearchResult(): SAnime {
        val href = this.select("a").attr("href").let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }
        val title = this.select("h3.film-name").text().ifEmpty {
            this.select(".film-name").text()
        }
        val subCount = this.selectFirst(".film-poster > .tick.ltr > .tick-sub")?.text()?.toIntOrNull()
        val dubCount = this.selectFirst(".film-poster > .tick.ltr > .tick-dub")?.text()?.toIntOrNull()

        val posterUrl = this.select("img").attr("data-src").let { src ->
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "$baseUrl$src"
                src.isEmpty() -> this.select("img").attr("src")
                else -> src
            }
        }

        return SAnime().apply {
            this.title = title.ifEmpty { "Unknown Title" }
            this.url = href
            this.thumbnail_url = posterUrl
            this.source = AnimeSource.HIANIME.name
        }
    }

    private fun getType(typeText: String): String {
        return when {
            typeText.contains("OVA", ignoreCase = true) ||
                    typeText.contains("Special", ignoreCase = true) -> "OVA"
            typeText.contains("Movie", ignoreCase = true) -> "Movie"
            else -> "TV"
        }
    }

    private fun getStatus(statusText: String): Int {
        return when (statusText) {
            "Finished Airing" -> SAnime.COMPLETED
            "Currently Airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // =============================== Megacloud Extractor ===============================
    private suspend fun extractFromMegacloud(embedUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val videos = mutableListOf<Video>()

            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://megacloud.blog/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            )

            // Get the embed page
            val embedResponse = client.newCall(
                Request.Builder()
                    .url(embedUrl)
                    .headers(headers.toHeaders())
                    .build()
            ).execute()

            val embedContent = embedResponse.body?.string() ?: return@withContext emptyList()

            // Extract ID and nonce
            val id = embedUrl.substringAfterLast("/").substringBefore("?")

            // Extract nonce using regex patterns
            val match1 = Regex("""\b[a-zA-Z0-9]{48}\b""").find(embedContent)
            val match2 = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""").find(embedContent)
            val nonce = match1?.value ?: match2?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }

            if (nonce == null) {
                return@withContext emptyList()
            }

            // Get sources from API
            val apiUrl = "https://megacloud.blog/embed-2/v3/e-1/getSources?id=$id&_k=$nonce"
            val apiResponse = client.newCall(
                Request.Builder()
                    .url(apiUrl)
                    .headers(headers.toHeaders())
                    .build()
            ).execute()

            val apiContent = apiResponse.body?.string() ?: return@withContext emptyList()
            val megacloudResponse = try {
                gson.fromJson(apiContent, MegacloudResponse::class.java)
            } catch (e: Exception) {
                return@withContext emptyList()
            }

            val encoded = megacloudResponse.sources.firstOrNull()?.file ?: return@withContext emptyList()

            // Check if it's already a direct m3u8 URL
            val m3u8Url = if (encoded.contains(".m3u8")) {
                encoded
            } else {
                // Try to decrypt the encoded URL
                try {
                    val key = getMegacloudKey()
                    if (key != null) {
                        decryptMegacloudUrl(encoded, nonce, key)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }

            if (m3u8Url != null) {
                // Parse M3U8 playlist to extract different quality streams
                val m3u8Response = client.newCall(
                    Request.Builder()
                        .url(m3u8Url)
                        .header("Referer", "https://megacloud.blog/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                        .build()
                ).execute()

                val m3u8Content = m3u8Response.body?.string() ?: ""
                val qualities = parseM3U8Qualities(m3u8Content, m3u8Url)

                qualities.forEach { (quality, url) ->
                    videos.add(
                        Video(
                            url = url,
                            quality = quality,
                            videoUrl = url,
                            headers = mapOf(
                                "Referer" to "https://megacloud.blog/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                            )
                        )
                    )
                }
            }

            val subtitles = megacloudResponse.tracks
                .filter { it.kind == "captions" || it.kind == "subtitles" }
                .map { Subtitle(it.file, it.label) }

            if (subtitles.isNotEmpty()) {
                videos.forEach { video ->
                    video.subtitles = subtitles
                }
            }

            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun getMegacloudKey(): String? {
        return try {
            val keyResponse = client.newCall(
                Request.Builder()
                    .url("https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json")
                    .build()
            ).execute()

            val keyContent = keyResponse.body?.string() ?: return null
            val megaKey = gson.fromJson(keyContent, MegacloudKey::class.java)
            megaKey.mega
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun decryptMegacloudUrl(encoded: String, nonce: String, key: String): String? {
        return try {
            val decodeUrl = "https://script.google.com/macros/s/AKfycbxHbYHbrGMXYD2-bC-C43D3njIbU-wGiYQuJL61H4vyy6YVXkybMNNEPJNPPuZrD1gRVA/exec"
            val fullUrl = "$decodeUrl?encrypted_data=${URLEncoder.encode(encoded, "UTF-8")}" +
                    "&nonce=${URLEncoder.encode(nonce, "UTF-8")}" +
                    "&secret=${URLEncoder.encode(key, "UTF-8")}"

            val decryptResponse = client.newCall(
                Request.Builder()
                    .url(fullUrl)
                    .build()
            ).execute()

            val decryptContent = decryptResponse.body?.string() ?: return null
            Regex("\"file\":\"(.*?)\"").find(decryptContent)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseM3U8Qualities(m3u8Content: String, baseUrl: String): Map<String, String> {
        val qualities = mutableMapOf<String, String>()
        val lines = m3u8Content.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // Extract resolution and bandwidth info
                val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1)
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()

                // Get the URL from next line
                if (i + 1 < lines.size) {
                    val streamUrl = lines[i + 1]
                    val fullUrl = if (streamUrl.startsWith("http")) {
                        streamUrl
                    } else {
                        // Relative URL, construct full URL
                        val baseUrlWithoutFile = baseUrl.substringBeforeLast("/")
                        "$baseUrlWithoutFile/$streamUrl"
                    }

                    val qualityLabel = when {
                        resolution?.contains("1920") == true -> "1080p"
                        resolution?.contains("1280") == true -> "720p"
                        resolution?.contains("854") == true || resolution?.contains("640") == true -> "480p"
                        resolution?.contains("426") == true -> "360p"
                        bandwidth != null && bandwidth > 2000000 -> "HD"
                        bandwidth != null && bandwidth > 1000000 -> "SD"
                        else -> "Auto"
                    }

                    qualities[qualityLabel] = fullUrl
                }
            }
            i++
        }

        // If no qualities found, return the original URL
        if (qualities.isEmpty()) {
            qualities["Auto"] = baseUrl
        }

        return qualities
    }

    private fun Map<String, String>.toHeaders(): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        this.forEach { (key, value) ->
            builder.add(key, value)
        }
        return builder.build()
    }

    // =============================== Popular/Homepage ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/most-popular?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active").isNotEmpty() &&
                        document.select(".pagination .page-item").size > 1

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "$baseUrl/search?keyword=${query.replace(" ", "+")}&page=$page"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    // =========================== Details & Episodes ============================
    suspend fun fetchAnimeDetails(url: String): SAnime? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")

                val title = document.selectFirst(".anisc-detail > .film-name")?.text() ?: ""
                val poster = document.selectFirst(".anisc-poster img")?.attr("src") ?: ""

                SAnime().apply {
                    this.url = url
                    this.title = title
                    this.thumbnail_url = when {
                        poster.startsWith("//") -> "https:$poster"
                        poster.startsWith("/") -> "$baseUrl$poster"
                        else -> poster
                    }

                    // Parse additional info
                    document.select(".anisc-info > .item").forEach { info ->
                        val infoType = info.select("span.item-head").text().removeSuffix(":")
                        when (infoType) {
                            "Overview" -> this.description = info.selectFirst(".text")?.text()
                            "Japanese" -> {} // Could store alternative title if needed
                            "Premiered" -> {
                                val yearText = info.selectFirst(".name")?.text()?.substringAfter(" ")
                                // Could parse year if your model supports it
                            }
                            "Status" -> this.status = getStatus(info.selectFirst(".name")?.text() ?: "")
                            "Genres" -> this.genre = info.select("a").joinToString(", ") { it.text() }
                            else -> {}
                        }
                    }

                    this.source = AnimeSource.HIANIME.name
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchEpisodeList(urll: String): List<SEpisode> = withContext(Dispatchers.IO) {
        try {
            val animeId = urll.split("-").lastOrNull()?.split("?")?.firstOrNull() ?: return@withContext emptyList()

            val episodeListUrl = "$baseUrl/ajax/v2/episode/list/$animeId"
            val request = Request.Builder()
                .url(episodeListUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val hiAnimeResponse = json.decodeFromString<HiAnimeResponse>(responseBody)

                if (!hiAnimeResponse.status) return@withContext emptyList()

                val document = Jsoup.parse(hiAnimeResponse.html)
                val episodes = mutableListOf<SEpisode>()

                document.select(".ss-list > a[href].ssl-item.ep-item").forEach { ep ->
                    val href = ep.attr("href")
                    val episodeTitle = ep.attr("title")
                    val episodeNumber = ep.selectFirst(".ssli-order")?.text()?.toFloatOrNull() ?: 0f

                    // Create both sub and dub versions if available
                    val subEpisode = SEpisode().apply {
                        name = episodeTitle
                        url = "sub|$href"
                        episode_number = episodeNumber
                    }
                    episodes.add(subEpisode)

                    // Check if dub is available (you might need to adjust this logic)
                    val dubEpisode = SEpisode().apply {
                        name = "$episodeTitle (Dub)"
                        url = "dub|$href"
                        episode_number = episodeNumber
                    }
                    episodes.add(dubEpisode)
                }

                episodes.sortedBy { it.episode_number }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Enhanced Video Links =============================
    suspend fun fetchVideoList(episodeData: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val dubType = episodeData.substringBefore("|")
            val hrefPart = episodeData.substringAfter("|")
            val epId = hrefPart.substringAfter("ep=")

            // Get servers
            val serversUrl = "$baseUrl/ajax/v2/episode/servers?episodeId=$epId"
            val serversRequest = Request.Builder()
                .url(serversUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val videos = mutableListOf<Video>()

            client.newCall(serversRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val serversResponse = json.decodeFromString<HiAnimeResponse>(responseBody)

                if (!serversResponse.status) return@withContext emptyList()

                val document = Jsoup.parse(serversResponse.html)
                val servers = document.select(".server-item[data-type=$dubType][data-id], .server-item[data-type=raw][data-id]")

                servers.forEach { serverElement ->
                    val serverId = serverElement.attr("data-id")
                    val serverLabel = serverElement.selectFirst("a.btn")?.text()?.trim() ?: "Unknown"

                    if (serverId.isNotEmpty()) {
                        try {
                            // Get source URL
                            val sourceUrl = "$baseUrl/ajax/v2/episode/sources?id=$serverId"
                            val sourceRequest = Request.Builder()
                                .url(sourceUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .build()

                            client.newCall(sourceRequest).execute().use { sourceResponse ->
                                val sourceBody = sourceResponse.body?.string() ?: ""
                                val episodeServers = json.decodeFromString<HiAnimeEpisodeServers>(sourceBody)

                                if (episodeServers.link.isNotEmpty()) {
                                    // Check if it's a Megacloud link and extract actual video URLs
                                    if (episodeServers.link.contains("megacloud", ignoreCase = true)) {
                                        val extractedVideos = extractFromMegacloud(episodeServers.link)
                                        extractedVideos.forEach { video ->
                                            videos.add(
                                                video.copy(quality = "$serverLabel - ${video.quality}")
                                            )
                                        }
                                    } else {
                                        // For other servers, keep the original behavior
                                        videos.add(
                                            Video(
                                                url = episodeServers.link,
                                                quality = serverLabel,
                                                videoUrl = episodeServers.link,
                                                headers = mapOf(
                                                    "Referer" to baseUrl,
                                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Extra Main Page Functions =============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/home")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")

                // Look for slider or featured content
                val sliderItems = document.select(".swiper-wrapper .swiper-slide, .trending-list .flw-item, .banner .flw-item")
                    .take(10) // Limit to first 10 items
                    .map { it.toSearchResult() }

                sliderItems.ifEmpty {
                    // Fallback to regular items if no slider found
                    document.select("div.flw-item").take(10).map { it.toSearchResult() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================= Additional Functions =============================
    suspend fun fetchRecentlyUpdated(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/recently-updated?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchTopAiring(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/top-airing?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchMostFavorite(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/most-favorite?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    suspend fun fetchCompletedAnime(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/completed?page=$page"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val document = Jsoup.parse(response.body?.string() ?: "")
                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()

                MangaPage(animeList, hasNextPage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MangaPage(emptyList(), false)
        }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}

//package com.faselhd.app.network.sources
//
//import android.content.Context
//import com.faselhd.app.models.*
//import com.faselhd.app.network.AnimeSource
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.SerialName
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import org.jsoup.Jsoup
//import org.jsoup.nodes.Document
//import org.jsoup.nodes.Element
//import java.util.concurrent.TimeUnit
//import java.util.regex.Pattern
//
////region DTOs for HiAnime API
//@Serializable
//data class HiAnimeResponse(
//    val status: Boolean,
//    val html: String
//)
//
//@Serializable
//data class HiAnimeSyncData(
//    @SerialName("mal_id") val malId: String? = null,
//    @SerialName("anilist_id") val aniListId: String? = null,
//)
//
//@Serializable
//data class HiAnimeEpisodeServers(
//    val type: String = "",
//    val link: String = "",
//    val server: Long = 0
//)
//
//@Serializable
//data class HiAnimeVideoSources(
//    val sources: List<HiAnimeSourcee> = emptyList(),
//    val tracks: List<HiAnimeTrack> = emptyList(),
//    val encrypted: Boolean = false
//)
//
//@Serializable
//data class HiAnimeSourcee(
//    val file: String = "",
//    val type: String = "",
//    val label: String? = null
//)
//
//@Serializable
//data class HiAnimeTrack(
//    val file: String = "",
//    val label: String = "",
//    val kind: String = "",
//    val default: Boolean? = null
//)
////endregion
//
//class HiAnimeSource(private val context: Context) {
//
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }
//
//    private val json = Json {
//        ignoreUnknownKeys = true
//        isLenient = true
//    }
//
//    private val baseUrl = "https://hianimez.is"
//
//    private fun Element.toSearchResult(): SAnime {
//        val href = this.select("a").attr("href").let {
//            if (it.startsWith("/")) "$baseUrl$it" else it
//        }
//        val title = this.select("h3.film-name").text().ifEmpty {
//            this.select(".film-name").text()
//        }
//        val subCount = this.selectFirst(".film-poster > .tick.ltr > .tick-sub")?.text()?.toIntOrNull()
//        val dubCount = this.selectFirst(".film-poster > .tick.ltr > .tick-dub")?.text()?.toIntOrNull()
//
//        val posterUrl = this.select("img").attr("data-src").let { src ->
//            when {
//                src.startsWith("//") -> "https:$src"
//                src.startsWith("/") -> "$baseUrl$src"
//                src.isEmpty() -> this.select("img").attr("src")
//                else -> src
//            }
//        }
//
//        return SAnime().apply {
//            this.title = title.ifEmpty { "Unknown Title" }
//            this.url = href
//            this.thumbnail_url = posterUrl
//            this.source = AnimeSource.HIANIME.name
//        }
//    }
//
//    private fun getType(typeText: String): String {
//        return when {
//            typeText.contains("OVA", ignoreCase = true) ||
//                    typeText.contains("Special", ignoreCase = true) -> "OVA"
//            typeText.contains("Movie", ignoreCase = true) -> "Movie"
//            else -> "TV"
//        }
//    }
//
//    private fun getStatus(statusText: String): Int {
//        return when (statusText) {
//            "Finished Airing" -> SAnime.COMPLETED
//            "Currently Airing" -> SAnime.ONGOING
//            else -> SAnime.UNKNOWN
//        }
//    }
//
//    // =============================== Popular/Homepage ===============================
//    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/most-popular?page=$page"
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active").isNotEmpty() &&
//                        document.select(".pagination .page-item").size > 1
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // =============================== Search ===============================
//    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val searchUrl = "$baseUrl/search?keyword=${query.replace(" ", "+")}&page=$page"
//            val request = Request.Builder()
//                .url(searchUrl)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // =========================== Details & Episodes ============================
//    suspend fun fetchAnimeDetails(url: String): SAnime? = withContext(Dispatchers.IO) {
//        try {
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//
//                val title = document.selectFirst(".anisc-detail > .film-name")?.text() ?: ""
//                val poster = document.selectFirst(".anisc-poster img")?.attr("src") ?: ""
//
//                SAnime().apply {
//                    this.url = url
//                    this.title = title
//                    this.thumbnail_url = when {
//                        poster.startsWith("//") -> "https:$poster"
//                        poster.startsWith("/") -> "$baseUrl$poster"
//                        else -> poster
//                    }
//
//                    // Parse additional info
//                    document.select(".anisc-info > .item").forEach { info ->
//                        val infoType = info.select("span.item-head").text().removeSuffix(":")
//                        when (infoType) {
//                            "Overview" -> this.description = info.selectFirst(".text")?.text()
//                            "Japanese" -> {} // Could store alternative title if needed
//                            "Premiered" -> {
//                                val yearText = info.selectFirst(".name")?.text()?.substringAfter(" ")
//                                // Could parse year if your model supports it
//                            }
//                            "Status" -> this.status = getStatus(info.selectFirst(".name")?.text() ?: "")
//                            "Genres" -> this.genre = info.select("a").joinToString(", ") { it.text() }
//                            else -> {}
//                        }
//                    }
//
//                    this.source = AnimeSource.HIANIME.name
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//
//    suspend fun fetchEpisodeList(urll: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        try {
//            val animeId = urll.split("-").lastOrNull()?.split("?")?.firstOrNull() ?: return@withContext emptyList()
//
//            val episodeListUrl = "$baseUrl/ajax/v2/episode/list/$animeId"
//            val request = Request.Builder()
//                .url(episodeListUrl)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .header("X-Requested-With", "XMLHttpRequest")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val responseBody = response.body?.string() ?: ""
//                val hiAnimeResponse = json.decodeFromString<HiAnimeResponse>(responseBody)
//
//                if (!hiAnimeResponse.status) return@withContext emptyList()
//
//                val document = Jsoup.parse(hiAnimeResponse.html)
//                val episodes = mutableListOf<SEpisode>()
//
//                document.select(".ss-list > a[href].ssl-item.ep-item").forEach { ep ->
//                    val href = ep.attr("href")
//                    val episodeTitle = ep.attr("title")
//                    val episodeNumber = ep.selectFirst(".ssli-order")?.text()?.toFloatOrNull() ?: 0f
//
//                    // Create both sub and dub versions if available
//                    val subEpisode = SEpisode().apply {
//                        name = episodeTitle
//                        url = "sub|$href"
//                        episode_number = episodeNumber
//                    }
//                    episodes.add(subEpisode)
//
//                    // Check if dub is available (you might need to adjust this logic)
//                    val dubEpisode = SEpisode().apply {
//                        name = "$episodeTitle (Dub)"
//                        url = "dub|$href"
//                        episode_number = episodeNumber
//                    }
//                    episodes.add(dubEpisode)
//                }
//
//                episodes.sortedBy { it.episode_number }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//    // ============================ Video Links =============================
//    suspend fun fetchVideoList(episodeData: String): List<Video> = withContext(Dispatchers.IO) {
//        try {
//            val dubType = episodeData.substringBefore("|")
//            val hrefPart = episodeData.substringAfter("|")
//            val epId = hrefPart.substringAfter("ep=")
//
//            // Get servers
//            val serversUrl = "$baseUrl/ajax/v2/episode/servers?episodeId=$epId"
//            val serversRequest = Request.Builder()
//                .url(serversUrl)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .header("X-Requested-With", "XMLHttpRequest")
//                .build()
//
//            val videos = mutableListOf<Video>()
//
//            client.newCall(serversRequest).execute().use { response ->
//                val responseBody = response.body?.string() ?: ""
//                val serversResponse = json.decodeFromString<HiAnimeResponse>(responseBody)
//
//                if (!serversResponse.status) return@withContext emptyList()
//
//                val document = Jsoup.parse(serversResponse.html)
//                val servers = document.select(".server-item[data-type=$dubType][data-id], .server-item[data-type=raw][data-id]")
//
//                servers.forEach { serverElement ->
//                    val serverId = serverElement.attr("data-id")
//                    val serverLabel = serverElement.selectFirst("a.btn")?.text()?.trim() ?: "Unknown"
//
//                    if (serverId.isNotEmpty()) {
//                        try {
//                            // Get source URL
//                            val sourceUrl = "$baseUrl/ajax/v2/episode/sources?id=$serverId"
//                            val sourceRequest = Request.Builder()
//                                .url(sourceUrl)
//                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                                .header("X-Requested-With", "XMLHttpRequest")
//                                .build()
//
//                            client.newCall(sourceRequest).execute().use { sourceResponse ->
//                                val sourceBody = sourceResponse.body?.string() ?: ""
//                                val episodeServers = json.decodeFromString<HiAnimeEpisodeServers>(sourceBody)
//
//                                if (episodeServers.link.isNotEmpty()) {
//                                    // Check if it's a Megacloud link
//                                    if (episodeServers.link.contains("megacloud")) {
//                                        val extractedVideos = extractMegacloudVideo(episodeServers.link, serverLabel)
//                                        videos.addAll(extractedVideos)
//                                    } else {
//                                        // For other servers, add the embed link directly
//                                        videos.add(
//                                            Video(
//                                                url = episodeServers.link,
//                                                quality = serverLabel,
//                                                videoUrl = episodeServers.link,
//                                                headers = mapOf(
//                                                    "Referer" to baseUrl,
//                                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
//                                                )
//                                            )
//                                        )
//                                    }
//                                }
//                            }
//                        } catch (e: Exception) {
//                            e.printStackTrace()
//                        }
//                    }
//                }
//            }
//
//            videos
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//    // ============================ Megacloud Extractor =============================
//    private suspend fun extractMegacloudVideo(embedUrl: String, serverLabel: String): List<Video> = withContext(Dispatchers.IO) {
//        val videos = mutableListOf<Video>()
//
//        try {
//            val mainheaders = mapOf(
//                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
//                "Accept" to "*/*",
//                "Accept-Language" to "en-US,en;q=0.5",
//                "Accept-Encoding" to "gzip, deflate, br, zstd",
//                "Origin" to "https://megacloud.blog",
//                "Referer" to "https://megacloud.blog/",
//                "Connection" to "keep-alive"
//            )
//
//            val headers = mapOf(
//                "Accept" to "*/*",
//                "X-Requested-With" to "XMLHttpRequest",
//                "Referer" to "https://megacloud.blog"
//            )
//
//            // Extract ID from embed URL
//            val id = embedUrl.substringAfterLast("/").substringBefore("?")
//
//            // Get the nonce from the embed page
//            val embedRequest = Request.Builder()
//                .url(embedUrl)
//                .headers(headers.toHeaders())
//                .build()
//
//            val embedResponse = client.newCall(embedRequest).execute()
//            val embedHtml = embedResponse.body?.string() ?: ""
//
//            // Extract nonce using regex patterns
//            val match1 = Regex("""\b[a-zA-Z0-9]{48}\b""").find(embedHtml)
//            val match2 = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""").find(embedHtml)
//            val nonce = match1?.value ?: match2?.let {
//                it.groupValues[1] + it.groupValues[2] + it.groupValues[3]
//            } ?: return@withContext emptyList()
//
//            // Get sources from API
//            val apiUrl = "https://megacloud.blog/embed-2/v3/e-1/getSources?id=$id&_k=$nonce"
//            val apiRequest = Request.Builder()
//                .url(apiUrl)
//                .headers(headers.toHeaders())
//                .build()
//
//            val apiResponse = client.newCall(apiRequest).execute()
//            val apiBody = apiResponse.body?.string() ?: ""
//
//            val megacloudResponse = try {
//                json.decodeFromString<MegacloudApiResponse>(apiBody)
//            } catch (e: Exception) {
//                null
//            } ?: return@withContext emptyList()
//
//            val encoded = megacloudResponse.sources.firstOrNull()?.file ?: return@withContext emptyList()
//
//            // Check if already decoded (contains .m3u8)
//            val videoUrl = if (encoded.contains(".m3u8")) {
//                encoded
//            } else {
//                // Try to get decryption key
//                val key = try {
//                    val keyRequest = Request.Builder()
//                        .url("https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json")
//                        .build()
//                    val keyResponse = client.newCall(keyRequest).execute()
//                    val keyJson = keyResponse.body?.string() ?: ""
//                    val keyData = json.decodeFromString<MegakeyResponse>(keyJson)
//                    keyData.mega
//                } catch (e: Exception) {
//                    null
//                }
//
//                if (key != null) {
//                    // Decrypt using external service
//                    val decodeUrl = "https://script.google.com/macros/s/AKfycbxHbYHbrGMXYD2-bC-C43D3njIbU-wGiYQuJL61H4vyy6YVXkybMNNEPJNPPuZrD1gRVA/exec"
//                    val decryptRequest = Request.Builder()
//                        .url("$decodeUrl?encrypted_data=${java.net.URLEncoder.encode(encoded, "UTF-8")}&nonce=${java.net.URLEncoder.encode(nonce, "UTF-8")}&secret=${java.net.URLEncoder.encode(key, "UTF-8")}")
//                        .build()
//
//                    val decryptResponse = client.newCall(decryptRequest).execute()
//                    val decryptedBody = decryptResponse.body?.string() ?: ""
//
//                    // Extract video URL from decrypted response
//                    Regex("\"file\":\"(.*?)\"").find(decryptedBody)?.groupValues?.get(1) ?: encoded
//                } else {
//                    encoded
//                }
//            }
//
//            // Add the main video
//            videos.add(
//                Video(
//                    url = videoUrl,
//                    quality = serverLabel,
//                    videoUrl = videoUrl,
//                    headers = mainheaders
//                )
//            )
//
//            // Add subtitles if available
//            val subtitles = megacloudResponse.tracks
//                .filter { it.kind == "captions" || it.kind == "subtitles" }
//                .map { Subtitle(it.file, it.label) }
//
//            if (subtitles.isNotEmpty()) {
//                videos.forEach { video ->
//                    video.subtitles = subtitles
//                }
//            }
//
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
//
//        return@withContext videos
//    }
//
//    // Helper function to convert Map to Headers
//    private fun Map<String, String>.toHeaders(): okhttp3.Headers {
//        val builder = okhttp3.Headers.Builder()
//        this.forEach { (key, value) ->
//            builder.add(key, value)
//        }
//        return builder.build()
//    }
//
//    // Additional data classes for Megacloud API
//    @Serializable
//    data class MegacloudApiResponse(
//        val sources: List<MegacloudSource> = emptyList(),
//        val tracks: List<MegacloudTrack> = emptyList(),
//        val encrypted: Boolean = false,
//        val server: Long = 0
//    )
//
//    @Serializable
//    data class MegacloudSource(
//        val file: String = "",
//        val type: String = ""
//    )
//
//    @Serializable
//    data class MegacloudTrack(
//        val file: String = "",
//        val label: String = "",
//        val kind: String = "",
//        val default: Boolean? = null
//    )
//
//    @Serializable
//    data class MegakeyResponse(
//        val rabbit: String = "",
//        val mega: String = ""
//    )
//
//    // ============================ Extra Main Page Functions =============================
//    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
//        try {
//            val request = Request.Builder()
//                .url("$baseUrl/home")
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//
//                // Look for slider or featured content
//                val sliderItems = document.select(".swiper-wrapper .swiper-slide, .trending-list .flw-item, .banner .flw-item")
//                    .take(10) // Limit to first 10 items
//                    .map { it.toSearchResult() }
//
//                sliderItems.ifEmpty {
//                    // Fallback to regular items if no slider found
//                    document.select("div.flw-item").take(10).map { it.toSearchResult() }
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//    // ============================= Additional Functions =============================
//    suspend fun fetchRecentlyUpdated(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/recently-updated?page=$page"
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    suspend fun fetchTopAiring(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/top-airing?page=$page"
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    suspend fun fetchMostFavorite(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/most-favorite?page=$page"
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    suspend fun fetchCompletedAnime(page: Int = 1): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/completed?page=$page"
//            val request = Request.Builder()
//                .url(url)
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body?.string() ?: "")
//                val animeList = document.select("div.flw-item").map { it.toSearchResult() }
//                val hasNextPage = document.select(".pagination .page-item.active + .page-item").isNotEmpty()
//
//                MangaPage(animeList, hasNextPage)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
//}

//package com.faselhd.app.network.sources
//
//import android.content.Context
//import com.faselhd.app.models.*
//import com.faselhd.app.network.AnimeSource
//import com.google.gson.Gson
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import org.jsoup.Jsoup
//import org.jsoup.nodes.Document
//import java.util.concurrent.TimeUnit
//
////region DTOs for HiAnime API
//@Serializable
//data class HiAnimeSearchResponse(
//    val html: String
//)
//
//data class HiAnimeEpisodeServersResponse(
//    val status: Boolean,
//    val html: String
//) {
//    fun getDocument(): Document {
//        return Jsoup.parse(html)
//    }
//}
//
//data class HiAnimeEpisodeSourcesResponse(
//    val type: String,
//    val link: String,
//    val server: Long,
//    val sources: List<Any?>,
//    val tracks: List<Any?>,
//)
////endregion
//
//class HiAnimeSource(private val context: Context) {
//
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build()
//    }
//
//    private val json = Json {
//        ignoreUnknownKeys = true
//        isLenient = true
//    }
//
//    private val gson = Gson() // For parsing JSON with different structure
//
//    private val baseUrl = "https://hianimez.is"
//
//    // =============================== Popular/Homepage ===============================
//    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
//        if (page > 10) return@withContext MangaPage(emptyList(), false) // HiAnime has many pages
//        try {
//            val url = "$baseUrl/most-popular?page=$page"
//            val request = Request.Builder().url(url).build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body!!.string())
//                val popularItems = document.select("div.flw-item").map {
//                    val title = it.select("h3.film-name a").attr("title")
//                    val href = it.select("a").attr("href")
//                    val posterUrl = it.select("img.film-poster-img").attr("data-src")
//                    val animeId = href.substringAfterLast('-')
//
//                    SAnime().apply {
//                        this.title = title
//                        this.url = animeId
//                        this.thumbnail_url = posterUrl
//                        this.source = AnimeSource.HIANIME.name // Assuming you add HIANIME to your enum
//                    }
//                }
//                MangaPage(popularItems, popularItems.isNotEmpty())
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // =============================== Search ===============================
//    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
//        try {
//            val url = "$baseUrl/search?keyword=$query&page=$page"
//            val request = Request.Builder().url(url).build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body!!.string())
//                val animeList = document.select("div.flw-item").map {
//                    val title = it.select("h3.film-name a").attr("title")
//                    val href = it.select("a").attr("href")
//                    val posterUrl = it.select("img.film-poster-img").attr("data-src")
//                    val animeId = href.substringAfterLast('-')
//
//                    SAnime().apply {
//                        this.url = animeId
//                        this.title = title
//                        this.thumbnail_url = posterUrl
//                        this.source = AnimeSource.HIANIME.name
//                    }
//                }
//                MangaPage(animeList, animeList.isNotEmpty())
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            MangaPage(emptyList(), false)
//        }
//    }
//
//    // =========================== Details & Episodes ============================
//    suspend fun fetchAnimeDetails(animeId: String): SAnime? = withContext(Dispatchers.IO) {
//        try {
//            // HiAnime uses full URLs with slugs for details, but we only have the ID.
//            // A search might be needed to get the full URL, or we can construct a likely one.
//            // For simplicity, we assume we need to fetch the full URL first via a search.
//            // This is a placeholder; a more robust solution would be needed.
//            // Let's assume the ID is sufficient for API calls, as seen in the original source.
//            val detailsUrl = "$baseUrl/watch/placeholder-slug-$animeId" // This URL is not directly used for data fetching
//            val request = Request.Builder().url(detailsUrl).build()
//
//            client.newCall(request).execute().use { response ->
//                val document = Jsoup.parse(response.body!!.string())
//                SAnime().apply {
//                    url = animeId
//                    title = document.selectFirst(".anisc-detail .film-name")?.text() ?: "Unknown Title"
//                    thumbnail_url = document.selectFirst(".anisc-poster img")?.attr("src")
//                    description = document.select(".anisc-info .item-head:contains(Overview) + .text").text()
//                    genre = document.select(".anisc-info .item-head:contains(Genres) + .name a").joinToString { it.text() }
//                    status = SAnime.UNKNOWN
//                    source = AnimeSource.HIANIME.name
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//
//
//    suspend fun fetchEpisodeList(animeId: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        try {
//            val urll = "$baseUrl/ajax/v2/episode/list/$animeId"
//            val request = Request.Builder().url(urll).build()
//
//            client.newCall(request).execute().use { response ->
//                val responseBody = response.body!!.string()
//                val epResponse = gson.fromJson(responseBody, HiAnimeEpisodeServersResponse::class.java)
//                val document = epResponse.getDocument()
//
//                document.select(".ss-list > a.ssl-item.ep-item").map { epElement ->
//                    SEpisode().apply {
//                        name = epElement.attr("title")
//                        // URL will contain the episode ID needed for fetching sources
//                        url = epElement.attr("href").substringAfter("?ep=")
//                        episode_number = epElement.selectFirst(".ssli-order")?.text()?.toFloatOrNull() ?: 0f
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//
//    // ============================ Video Links =============================
//    suspend fun fetchVideoList(episodeId: String): List<Video> = withContext(Dispatchers.IO) {
//        val videos = mutableListOf<Video>()
//        try {
//            // 1. Get available servers for the episode
//            val serversUrl = "$baseUrl/ajax/v2/episode/servers?episodeId=$episodeId"
//            val serversRequest = Request.Builder().url(serversUrl).build()
//            val serversResponseJson = client.newCall(serversRequest).execute().body!!.string()
//            val serversDoc = gson.fromJson(serversResponseJson, HiAnimeEpisodeServersResponse::class.java).getDocument()
//
//            val serverIds = serversDoc.select(".server-item[data-id]").map {
//                it.attr("data-id") to it.selectFirst("a")?.text()
//            }
//
//            // 2. For each server, get the video source link
//            serverIds.forEach { (id, name) ->
//                try {
//                    val sourcesUrl = "$baseUrl/ajax/v2/episode/sources?id=$id"
//                    val sourcesRequest = Request.Builder().url(sourcesUrl).build()
//                    val sourcesResponseJson = client.newCall(sourcesRequest).execute().body!!.string()
//                    val sourcesResponse = gson.fromJson(sourcesResponseJson, HiAnimeEpisodeSourcesResponse::class.java)
//
//                    // The link is often to another extractor like Megacloud or Vidstreaming
//                    // Here we directly add the link. If it's an m3u8, it might work directly.
//                    // If it's a link to an extractor page, more complex logic is needed.
//                    val videoUrl = sourcesResponse.link
//                    if (videoUrl.isNotEmpty()) {
//                        // A more advanced implementation would call a specific extractor
//                        // based on the URL (e.g., if "megacloud" in videoUrl).
//                        // For now, we add it directly.
//                        videos.add(
//                            Video(
//                                url = videoUrl,
//                                quality = name ?: "Default",
//                                videoUrl = videoUrl,
//                                headers = mapOf("Referer" to baseUrl) // Generic referer
//                            )
//                        )
//                    }
//                } catch (e: Exception) {
//                    // Ignore errors for a single server and continue
//                    e.printStackTrace()
//                }
//            }
//            videos
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//}