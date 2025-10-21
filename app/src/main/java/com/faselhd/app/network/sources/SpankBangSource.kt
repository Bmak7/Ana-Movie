package com.faselhd.app.network.sources

import android.content.Context
import android.os.Build
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.Tls12SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class SpankBangSource(private val context: Context) {

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
                    .header("User-Agent", FaselHDSource.USER_AGENT)
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(request)
            }

        if (Build.VERSION.SDK_INT in 16..21) { // Apply for Jelly Bean up to Lollipop
            try {
                val sc = SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, null)
                val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as java.security.KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                    throw IllegalStateException("Unexpected default trust managers:" + java.util.Arrays.toString(trustManagers))
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

    private val baseUrl = "https://spankbang.com"

    // ============================== Popular & Latest ===============================

    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) "$baseUrl/trending_videos/" else "$baseUrl/trending_videos/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val videoList = document.select("div.video-item").map { videoFromElement(it) }
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        MangaPage(videoList, hasNextPage)
    }

    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) "$baseUrl/new_videos/" else "$baseUrl/new_videos/$page/"
        val document = Jsoup.parse(client.newCall(Request.Builder().url(url).build()).execute().body!!.string())
        val videoList = document.select("div.video-item").map { videoFromElement(it) }
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        MangaPage(videoList, hasNextPage)
    }

    private fun videoFromElement(element: Element): SAnime {
        val linkElement = element.selectFirst("a.thumb")
        return SAnime().apply {
            url = baseUrl + (linkElement?.attr("href") ?: "")
            thumbnail_url = element.selectFirst("img.cover")?.attr("data-src")
            title = linkElement?.attr("title") ?: "No Title"
            source = "SPANKBANG" // Using string literal as Enum cannot be modified here
        }
    }

    // ============================= Search ==============================
    suspend fun fetchSearch(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        println("DEBUG: Starting fetchSearch - page: $page, query: '$query', filters: $filters")

        val searchUrl = "$baseUrl/s/$query/$page/"
        println("DEBUG: Search URL: $searchUrl")

        val response = client.newCall(Request.Builder().url(searchUrl).build()).execute()
        println("DEBUG: HTTP response code: ${response.code}")

        val document = Jsoup.parse(response.body!!.string())
        println("DEBUG: Successfully parsed search document")

        val videoElements = document.select("div.video-item")
        println("DEBUG: Found ${videoElements.size} video items")

        val videos = videoElements.map { element ->
            println("DEBUG: Processing video element")
            videoFromElement(element)
        }
        println("DEBUG: Successfully processed ${videos.size} videos")

        val hasNextPage = document.selectFirst("div.pagination a.next") != null
        println("DEBUG: Has next page: $hasNextPage")

        return@withContext MangaPage(videos, hasNextPage).also {
            println("DEBUG: Search completed - returning ${videos.size} results")
        }
    }

    // =========================== Video Details ============================
    suspend fun fetchVideoDetails(video: SAnime): SAnime = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(video.url!!).build()).execute().body!!.string())
        return@withContext video.apply {
            title = document.selectFirst("h1.main_content_title")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("div.cat > p")?.text()
            genre = document.select("div.searches a").joinToString(", ") { it.text() }
            status = SAnime.UNKNOWN // Not applicable
        }
    }

    // ============================== Episodes ==============================
    // SpankBang has individual videos, not episodes. This function returns the video as a single "episode".
    suspend fun fetchEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        return@withContext listOf(
            SEpisode().apply {
                url = anime.url
                name = anime.title
                episode_number = 1f
                date_upload = System.currentTimeMillis()
            }
        )
    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val document = Jsoup.parse(client.newCall(Request.Builder().url(episode.url!!).build()).execute().body!!.string())
        val videoList = mutableListOf<Video>()

        val pattern = Pattern.compile("""stream_data = (\{.*?\});""")
        val matcher = pattern.matcher(document.html())

        if (matcher.find()) {
            val jsonString = matcher.group(1)
            val json = JSONObject(jsonString)
            val qualities = listOf("4k", "1080p", "720p", "480p", "240p")

            qualities.forEach { quality ->
                if (json.has(quality)) {
                    val urls = json.getJSONArray(quality)
                    if (urls.length() > 0) {
                        val url = urls.getString(0)
                        videoList.add(Video(url, quality, url))
                    }
                }
            }

            if (json.has("m3u8")) {
                val urls = json.getJSONArray("m3u8")
                if (urls.length() > 0) {
                    val url = urls.getString(0)
                    videoList.add(Video(url, "HLS Stream", url))
                }
            }
        }
        return@withContext videoList
    }

    // ============================== Filters ===============================
    fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList()) // No complex filters observed on site
}