package com.faselhd.app.sources // Use your project's package name

import StreamGHExtractor
import android.content.Context
import android.net.Uri
import android.util.Log
import com.faselhd.app.models.*
import com.google.gson.Gson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.nodes.Element
import java.io.File
import java.util.Base64


data class OkRuFlashvars(
    val metadata: String // This is a JSON string within the JSON
)

data class OkRuMetadata(
    val videos: List<OkRuVideoLink>
)

data class OkRuVideoLink(
    val name: String, // e.g., "mobile", "lowest", "low", "sd", "hd"
    val url: String
)

class FullReplaysSource(private val context: Context) {
    companion object {
        const val name = "FullReplays"
        const val BASE_URL = "https://www.fullreplays.com"
        const val lang = "en"
        const val supportsLatest = true
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

    // --- OKHTTP CLIENT SETUP (CORRECTED & UPGRADED) ---
    private val client: OkHttpClient by lazy {
        // A simple in-memory cookie jar to handle cookie-based sessions and redirects
        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        OkHttpClient.Builder()
            .cookieJar(cookieJar) // <-- FIX #1: Add a CookieJar
            .addInterceptor { chain -> // <-- FIX #2: Add an interceptor to set User-Agent on all requests
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .ignoreAllSSLErrors()
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
            .build()
    }
    private val app = Requests(client)
    private val gson = Gson()

    private val haxloppdExtractor by lazy { StreamGHExtractor(client) }


    // ============================== Categories & Main Page ==============================
    suspend fun fetchCategory(page: Int, categoryPath: String): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL$categoryPath/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.vlog-lay-g").mapNotNull { toSAnime(it) }
        MangaPage(items, items.isNotEmpty())
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage {
        return fetchCategory(page, "/")
    }

    // ============================== Search ==============================
    suspend fun fetchSearch(query: String): MangaPage = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/?s=$query"
        val document = app.get(url).document
        val items = document.select("article.vlog-lay-g").mapNotNull { toSAnime(it) }
        MangaPage(items, false)
    }

    // ============================== Details & Episodes ==============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val document = app.get(animeUrl).document
        return@withContext SAnime().apply {
            url = animeUrl
            title = document.selectFirst("h1.entry-title")?.text()
            thumbnail_url = document.selectFirst(".vlog-cover > img:nth-child(1)")?.attr("src")
            description = document.selectFirst(".frc_first_para_match_dt")?.html()
            status = SAnime.ONGOING
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = app.get(animeUrl).document
        val episodeLinksMap = mutableMapOf<String, StringBuilder>()

        document.select("ul.frc-vid-sources-list").forEach { element ->
            element.select("li > span").forEach { link ->
                val episodeName = link.text().trim()
                val dataContent = link.attr("data-sc")
                if (episodeName.isNotEmpty() && dataContent.isNotEmpty()) {
                    episodeLinksMap.getOrPut(episodeName) { StringBuilder() }
                        .append(dataContent)
                        .append(" ; ")
                }
            }
        }

        return@withContext episodeLinksMap.entries.mapIndexed { index, (name, links) ->
            SEpisode().apply {
                this.name = name
                this.url = links.toString().removeSuffix(" ; ")
                this.episode_number = (index + 1).toFloat()
            }
        }
    }

    // ============================== Video Links ==============================
    suspend fun fetchVideoList(episodeData: String): List<Video> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<Video>()
        episodeData.split(" ; ").forEach { data ->
            if (data.isNotBlank()) {
                var iframeUrl: String? = null
                try {
                    val decodedBytes = Base64.getDecoder().decode(data)
                    iframeUrl = String(decodedBytes, Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    if (data.startsWith("http")) iframeUrl = data
                } catch (e: Exception) {
                    Log.e("FullReplays", "Failed to process data: $data", e)
                }

                if (iframeUrl != null) {
                    videoList.addAll(extractVideosFromUrl(iframeUrl, name))
                }
            }
        }
        return@withContext videoList.distinctBy { it.url }
    }

    // ============================== Helper Functions ==============================
    private fun toSAnime(element: Element): SAnime? {
        val linkElement = element.selectFirst(".entry-title > a") ?: return null
        return SAnime().apply {
            title = linkElement.text()
            url = linkElement.attr("href")
            thumbnail_url = element.selectFirst(".entry-image img")?.attr("src")
        }
    }

    fun extractHglinkId(url: String): String? {
        return try {
            Uri.parse(url).lastPathSegment
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractVideosFromUrl(url: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            when {
                "vkvideo.ru" in url || "vk" in url -> {
                    // Pass the main site URL as the Referer to look like a legitimate request
                    val document = app.get(url, referer = BASE_URL).document

                    val script = document.select("script:containsData(playerParams)").firstOrNull()?.data()
                    if (script != null) {
                        val jsonRegex = Regex("""playerParams\s*=\s*\{.*?"params":\s*(\[.*?\])\}""")
                        val paramsJson = jsonRegex.find(script)?.groupValues?.get(1)

                        if (paramsJson != null) {
                            val urlRegex = Regex(""""(url(\d+)|hls)":\s*"([^"]+)"""")
                            urlRegex.findAll(paramsJson).forEach { match ->
                                val quality = match.groupValues[2].ifEmpty { "HLS" } + "p"
                                var videoUrl = match.groupValues[3].replace("\\/", "/")
                                videos.add(Video(videoUrl, quality, videoUrl, headers = mapOf("Referer" to url)))
                            }
                        }
                    }
                }

                "ok.ru" in url -> {
                    // <-- FIX #3: Add Referer header to the request
                    val document = app.get(url, referer = BASE_URL).document
                    val optionsJson = document.selectFirst("[data-options]")?.attr("data-options")
                    if (optionsJson != null) {
                        val flashvars = gson.fromJson(optionsJson, OkRuFlashvars::class.java)
                        val metadataJson = java.net.URLDecoder.decode(flashvars.metadata, "UTF-8")
                        val metadata = gson.fromJson(metadataJson, OkRuMetadata::class.java)
                        metadata.videos.forEach { videoLink ->
                            val quality = when (videoLink.name) {
                                "mobile" -> "144p"; "lowest" -> "240p"; "low" -> "360p"
                                "sd" -> "480p"; "hd" -> "720p"; "full" -> "1080p"
                                else -> "OK.ru"
                            }
                            videos.add(Video(videoLink.url, quality, videoUrl = videoLink.url, headers = mapOf("Referer" to BASE_URL)))
                        }
                    }
                }


                url.contains("hglink") || url.contains("dumbalag") -> {
                    println("DEBUG: Processing Hglink URL: $url")
                    val extractedId = extractHglinkId(url)
                    println("DEBUG: Extracted Hglink ID: $extractedId")
                    val haxloppdUrl = "https://haxloppd.com/$extractedId"
                    println("DEBUG: Haxloppd URL: $haxloppdUrl")
                    val result = haxloppdExtractor.videosFromUrl(haxloppdUrl)
                    println("DEBUG: Haxloppd extraction result: ${result.size} videos found")
                    videos.addAll(result)
                }
                // Add other extractors here
                else -> {
                    // Fallback for unknown but direct links
                    emptyList<Video>()
                }
            }
        } catch (e: Exception) {
            Log.e("FullReplays", "Error extracting from URL: $url", e)
        }
        return videos
    }
}