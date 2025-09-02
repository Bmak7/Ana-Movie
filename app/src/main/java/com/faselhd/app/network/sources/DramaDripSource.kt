package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.DriveseedExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DramaDripSource(private val context: Context) {

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mapper by lazy { jacksonObjectMapper() }
    private var baseUrl = "https://dramadrip.com"

    // --- Extractors ---
    private val driveseedExtractor by lazy { DriveseedExtractor(client) }

    private suspend fun updateBaseUrl() {
        if (baseUrl != "https://dramadrip.com") return // Already updated
        try {
            val domainsJson = client.newCall(Request.Builder().url(DOMAINS_URL).build()).execute().body!!.string()
            val domains = mapper.readValue<DomainsParser>(domainsJson)
            if (domains.dramadrip.isNotBlank()) {
                baseUrl = domains.dramadrip
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ============================== Popular & Latest ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val url = "$baseUrl/drama/ongoing/page/$page"
        fetchMangaPage(url)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val url = "$baseUrl/latest/page/$page"
        fetchMangaPage(url)
    }

    private fun fetchMangaPage(url: String): MangaPage {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val animeList = document.select("article").mapNotNull { it.toSAnime() }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val url = "$baseUrl/page/$page/?s=$query"
        fetchMangaPage(url)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val title = document.selectFirst("div.wp-block-column > h2.wp-block-heading")?.text()?.substringBefore("(")?.trim() ?: "Unknown"

        SAnime().apply {
            this.url = animeUrl
            this.title = title
            this.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            this.genre = document.select("div.mt-2 span.badge").joinToString(", ") { it.text() }
            this.description = document.selectFirst("div.content-section p.mt-4")?.text()?.trim()
            this.status = SAnime.ONGOING // Most content is TV Series, default to ongoing
            this.source = AnimeSource.DRAMADRIP.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val document = Jsoup.parse(client.newCall(Request.Builder().url(animeUrl).build()).execute().body!!.string())
        val episodes = mutableListOf<SEpisode>()

        // Find all season headers
        val seasonHeaders = document.select("div.su-accordion h2.su-spoiler-title")
        if (seasonHeaders.isEmpty()) {
            // It's a movie, create a single episode
            val movieTitle = document.selectFirst("div.wp-block-column > h2.wp-block-heading")?.text() ?: "Movie"
            return@withContext listOf(SEpisode().apply {
                this.url = animeUrl // The links are on the details page itself
                this.name = "$movieTitle : مشاهدة"
                this.episode_number = 1f
            })
        }

        // It's a series
        seasonHeaders.forEach { seasonHeader ->
            val seasonName = seasonHeader.text().substringBefore(" ZIP").trim()
            if (seasonName.contains("zip", true)) return@forEach // Skip download zips

            // Find the container with episode links for this season
            val linksContainer = seasonHeader.nextElementSibling()

            linksContainer?.select("div.wp-block-button a")?.forEach { qualityLink ->
                try {
                    val episodePageDoc = client.newCall(
                        Request.Builder().url(qualityLink.attr("href")).build()
                    ).execute().use { response ->
                        Jsoup.parse(response.body!!.string())
                    }

                    val episodeLinks = episodePageDoc.select("a").filter { element ->
                        Regex("""(?i)(Episode|Ep|E)?\s*0*\d+""").matches(element.text())
                    }

                    episodeLinks.forEach { epLink ->
                        episodes.add(SEpisode().apply {
                            url = epLink.attr("href")
                            name = "$seasonName : ${epLink.text().trim()}"
                            episode_number = epLink.text()
                                .filter { it.isDigit() }
                                .toFloatOrNull() ?: 0f
                        })
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Group episodes by their URL to consolidate links from different qualities (480p, 720p, etc.)
        return@withContext episodes.groupBy { it.name }.map { (name, epList) ->
            epList.first().apply {
                // We store all possible links for an episode as a JSON array in its URL field
                this.url = mapper.writeValueAsString(epList.map { it.url }.distinct())
            }
        }.sortedBy { it.episode_number }
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeData: String): List<Video> = withContext(Dispatchers.IO) {
        updateBaseUrl()
        val links = try { mapper.readValue<List<String>>(episodeData) } catch (e: Exception) { listOf(episodeData) }

        links.map { async { resolveLink(it) } }.awaitAll().flatten()
    }

    private suspend fun resolveLink(url: String): List<Video> {
        return try {
            val resolvedUrl = when {
                "href.li" in url -> bypassHrefli(url)
                else -> url
            }
            if (resolvedUrl != null) {
                driveseedExtractor.videosFromUrl(resolvedUrl)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ============================ Helper Functions =============================

    private fun Element.toSAnime(): SAnime? {
        val title = this.selectFirst("h2.entry-title")?.text()?.substringAfter("Download")?.trim() ?: return null
        val href = this.selectFirst("h2.entry-title > a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.first()
            ?: this.selectFirst("img")?.attr("src")

        return SAnime().apply {
            this.title = title
            this.url = href
            this.thumbnail_url = posterUrl
            this.source = AnimeSource.DRAMADRIP.name
        }
    }

    private suspend fun bypassHrefli(url: String): String? {
        // This is a complex, multi-step link shortener bypass
        return try {
            var currentUrl = url
            // It can sometimes take up to 3 form submissions
            for (i in 1..3) {
                val doc = Jsoup.parse(client.newCall(Request.Builder().url(currentUrl).build()).execute().body!!.string())
                val form = doc.selectFirst("form#landing")
                if (form != null) {
                    val action = form.attr("action")
                    val inputs = form.select("input").associate { it.attr("name") to it.attr("value") }
                    val formBody = FormBody.Builder().apply { inputs.forEach { (k, v) -> add(k, v) } }.build()
                    val response = client.newCall(Request.Builder().url(action).post(formBody).build()).execute()
                    currentUrl = response.request.url.toString() // Update URL for next loop
                } else {
                    // Check for meta refresh or final script redirect
                    val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=")
                    if (metaRefresh != null) return metaRefresh

                    val scriptRedirect = doc.select("script:containsData(window.location.replace)").firstOrNull()?.data()
                    val finalUrl = scriptRedirect?.substringAfter("replace(\"")?.substringBefore("\")")
                    return finalUrl ?: currentUrl
                }
            }
            null // Failed after multiple attempts
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Stubs
    fun getFilterList() = AnimeFilterList(emptyList())
    suspend fun fetchMainSlider(): List<SAnime> = emptyList()

    companion object {
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/main/domains.json"
    }
}