package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.CloudflareInterceptor // <-- IMPORT THE NEW INTERCEPTOR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.util.concurrent.TimeUnit
import kotlin.text.Regex

class Anime3rbSource(private val context: Context) {

    // ========= MODIFICATION START =========
    private val client: OkHttpClient by lazy {
        // 1. Create a persistent cookie jar
        val cookieManager = CookieManager()
        val cookieJar: CookieJar = JavaNetCookieJar(cookieManager)

        // 2. Build the client with the CloudflareInterceptor
        OkHttpClient.Builder()
            .cookieJar(cookieJar) // Important: Share the cookie jar
            .addInterceptor(CloudflareInterceptor(context, cookieJar)) // Add the Cloudflare interceptor first
            .addInterceptor { chain ->
                // Your original interceptor for headers
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(newRequest)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    // ========= MODIFICATION END =========

    private val baseUrl = "https://anime3rb.com"

    // ... The rest of your file remains exactly the same ...
    // ============================== Main Page Sections ===============================

    private val mainPageSections = listOf(
        Pair("قائمة مسلسلات الأنمي", "/titles/list/tv?page="),
        Pair("قائمة أفلام الأنمي", "/titles/list/movie?page="),
        Pair("قائمة الأوفا", "/titles/list/ova?page="),
        Pair("قائمة الأونا", "/titles/list/ona?page="),
        Pair("قائمة الحلقات الخاصة", "/titles/list/special?page="),
    )

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val popularUrl = baseUrl + mainPageSections.first().second + page
        val request = Request.Builder().url(popularUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.flex-wrap div.my-2 a").mapNotNull {
            toSearchResponse(it)
        }

        val hasNextPage = document.selectFirst("ul.pagination a[rel=next]") != null
        MangaPage(animeList, hasNextPage)
    }

    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).build()
            val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
            document.select("div.flex-wrap div.my-2 a").mapNotNull {
                toSearchResponse(it)
            }.take(10)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search?q=${query.replace(" ", "+")}"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.flex-wrap div.my-2 a").mapNotNull {
            toSearchResponse(it)
        }
        MangaPage(animeList, hasNextPage = false)
    }

    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            url = animeUrl
            title = document.selectFirst("div.items-baseline h1 span")?.text() ?: "Unknown Title"
            thumbnail_url = document.selectFirst("div.w-full.flex.flex-col.gap-3 img")?.attr("src")
            description = document.selectFirst("p.sm\\:text-\\[1\\.05rem\\]")?.text()?.trim()
            genre = document.select("div.sm\\:text-\\[\\.93rem\\] a").joinToString(", ") { it.text() }
            status = getStatus(document.select("tr.border-b:contains(الحالة:) td:last-child").text())
            source = AnimeSource.ANIME3RB.name
        }
    }

    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodes = mutableListOf<SEpisode>()

        val seasonElements = document.select("ul.season-list li a")

        if (seasonElements.isNotEmpty()) {
            seasonElements.forEach { seasonElement ->
                val seasonName = seasonElement.text()
                val seasonUrl = seasonElement.attr("abs:href")
                val seasonDoc = Jsoup.parse(client.newCall(Request.Builder().url(seasonUrl).build()).execute().body!!.string())

                seasonDoc.select("div.videos-container a").forEach { episodeElement ->
                    episodes.add(createEpisode(episodeElement, seasonName))
                }
            }
        } else {
            val seasonName = document.selectFirst("div.items-baseline h1 span")?.text() ?: "الموسم 1"
            document.select("div.videos-container a").forEach { episodeElement ->
                episodes.add(createEpisode(episodeElement, seasonName))
            }
        }

        return@withContext episodes
    }

    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            val document = Jsoup.parse(client.newCall(Request.Builder().url(episodeUrl).build()).execute().body!!.string())

            val xData = document.selectFirst("section#player-section")?.attr("x-data") ?: return@withContext emptyList()
            val videoSourceUrl = Regex("videoSource:\\s*'([^']+)'").find(xData)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&") ?: return@withContext emptyList()

            val videoPageDoc = Jsoup.parse(client.newCall(Request.Builder().url(videoSourceUrl).build()).execute().body!!.string())
            val scriptElement = videoPageDoc.select("script").firstOrNull { it.html().contains("const sources") }
            val scriptContent = scriptElement?.html() ?: return@withContext emptyList()

            val videoRegex = Regex("""\{\s*src:\s*'([^']+)',\s*type:\s*'video/mp4',\s*label:\s*'[^']+',\s*res:\s*'(\d+)'""")
            return@withContext videoRegex.findAll(scriptContent).map { match ->
                val (src, res) = match.destructured
                Video(url = src, quality = "${res}p", videoUrl = src)
            }.toList()

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun toSearchResponse(element: Element): SAnime {
        return SAnime().apply {
            url = element.attr("abs:href")
            title = element.selectFirst("h2")?.text() ?: "Unknown"
            thumbnail_url = element.selectFirst("img")?.attr("src")
            source = AnimeSource.ANIME3RB.name
        }
    }

    private fun createEpisode(element: Element, seasonName: String): SEpisode {
        val episodeTitle = element.selectFirst("div.video-metadata p")?.text() ?: "Episode"
        return SEpisode().apply {
            url = element.attr("abs:href")
            name = "$seasonName : $episodeTitle"
            episode_number = element.selectFirst("div.video-metadata span")?.text()?.toFloatOrNull() ?: 0f
        }
    }

    private fun getStatus(statusString: String): Int {
        return when (statusString.trim()) {
            "منتهي" -> SAnime.COMPLETED
            "قيد البث" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    fun getFilterList() = AnimeFilterList(emptyList())
}