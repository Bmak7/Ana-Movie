package com.faselhd.app.network.sources


import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ArabDrama2Source(private val context: Context) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    }

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
//        val clientBuilder = OkHttpClient.Builder()
//            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
//            .addInterceptor { chain ->
//                val original = chain.request()
//                val request = original.newBuilder()
//                    .header("User-Agent", USER_AGENT)
//                    .header("Referer", baseUrl)
//                    .build()
//                chain.proceed(request)
//            }
//
//        if (Build.VERSION.SDK_INT in 16..21) {
//            try {
//                val sc = SSLContext.getInstance("TLSv1.2")
//                sc.init(null, null, null)
//                val trustManagerFactory =
//                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
//                trustManagerFactory.init(null as java.security.KeyStore?)
//                val trustManagers = trustManagerFactory.trustManagers
//                if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
//                    throw IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers))
//                }
//                val trustManager = trustManagers[0] as X509TrustManager
//
//                clientBuilder.sslSocketFactory(Tls12SocketFactory(sc.socketFactory), trustManager)
//
//                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
//                    .tlsVersions(TlsVersion.TLS_1_2)
//                    .build()
//                clientBuilder.connectionSpecs(Collections.singletonList(cs))
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//
//        clientBuilder.build()
//    }

    private val baseUrl = "https://aradramatv.cc"

    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val vidbomExtractor by lazy { VidBomExtractor(client) }
    //endregion

    // ============================== Popular ===============================
    suspend fun fetchPopularSeries(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) "$baseUrl/category/serie/" else "$baseUrl/category/serie/page/$page/"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.item.wgr").mapNotNull {
            SAnime().apply {
                this.url = it.select("a.first_A").attr("href")
                this.title = it.select("h3").text()
                this.thumbnail_url = it.select("img").attr("src")
                this.source = AnimeSource.ARABDRAMA2.name
            }
        }

        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    suspend fun fetchLatestUpdates(page: Int): MangaPage = withContext(Dispatchers.IO) {
        val url = if (page == 1) "$baseUrl/category/episodes/new/" else "$baseUrl/category/episodes/new/page/$page/"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.item_small.T_post").mapNotNull {
            SAnime().apply {
                this.url = it.select("a").attr("href")
                this.title = it.select("h3 a").text()
                this.thumbnail_url = it.select("img").attr("src")
                this.source = AnimeSource.ARABDRAMA2.name
            }
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        MangaPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    suspend fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): MangaPage = withContext(Dispatchers.IO) {
        val url = "$baseUrl/page/$page/?s=$query"
        val request = Request.Builder().url(url).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        val animeList = document.select("div.item.wgr").map {
            SAnime().apply {
                this.url = it.select("a.first_A").attr("href")
                this.title = it.select("h3").text()
                this.thumbnail_url = it.select("img").attr("src")
                this.source = AnimeSource.ARABDRAMA2.name
            }
        }

        val hasNextPage = document.select("a.next.page-numbers").isNotEmpty()
        MangaPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    suspend fun fetchAnimeDetails(animeUrl: String): SAnime = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        SAnime().apply {
            this.url = animeUrl
            val infoBox = document.selectFirst("div.b_block.s-desc")
            title = infoBox?.select("p:contains(الاسم العربي) ")?.text()?.substringAfter(":")?.trim() ?: "N/A"
            thumbnail_url = document.selectFirst("img.vc_single_image-img")?.attr("src") ?: ""
            description = document.select("div.b_block.s-desc > p:last-of-type").text()
            genre = infoBox?.select("p:contains(النوع) ")?.text()?.substringAfter(":")?.trim()
            status = if (document.select("a[href*='/stat/completed']").isNotEmpty()) 0 else 1 // Completed or Ongoing
            source = AnimeSource.ARABDRAMA2.name
        }
    }

    // ============================== Episodes ==============================
    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(animeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        // First, get the anime name/season name from the main page
        val animeNameAsSeason = document.selectFirst("h1.title")?.text() ?: "الموسم 1"

        val episodesLink = document.selectFirst("a.vc_general.vc_btn3:contains(مشاهدة حلقات المسلسل)")?.attr("href")
        if (episodesLink.isNullOrEmpty()) {
            return@withContext emptyList()
        }

        val episodesRequest = Request.Builder().url(episodesLink).build()
        val episodesDocument = Jsoup.parse(client.newCall(episodesRequest).execute().body!!.string())

        episodesDocument.select("div.eps article").map {
            SEpisode().apply {
                this.url = it.select("a.first_A").attr("href")

                // Format the name to be "Anime Name : Episode Name" like function 1
                val episodeName = it.select("h3.post-title a").text()
                this.name = "$animeNameAsSeason : $episodeName"

                this.episode_number = episodeName.substringAfter("الحلقة").trim().split(" ")[0].toFloatOrNull() ?: 0f
            }
        }.reversed()
    }

//    // ============================== Episodes ==============================
//    suspend fun fetchEpisodeList(animeUrl: String): List<SEpisode> = withContext(Dispatchers.IO) {
//        val request = Request.Builder().url(animeUrl).build()
//        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
//
//        val episodesLink = document.selectFirst("a.vc_general.vc_btn3:contains(مشاهدة حلقات المسلسل)")?.attr("href")
//        if (episodesLink.isNullOrEmpty()) {
//            return@withContext emptyList()
//        }
//
//        val episodesRequest = Request.Builder().url(episodesLink).build()
//        val episodesDocument = Jsoup.parse(client.newCall(episodesRequest).execute().body!!.string())
//
//        episodesDocument.select("div.eps article").map {
//            SEpisode().apply {
//                this.url = it.select("a.first_A").attr("href")
//                this.name = it.select("h3.post-title a").text()
//                this.episode_number = this.name!!.substringAfter("الحلقة").trim().split(" ")[0].toFloatOrNull() ?: 0f
//            }
//        }.reversed()
//    }

    // ============================ Video Links =============================
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(episodeUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
        val serverElements = document.select("ul.links-server li.server")

        serverElements.flatMap { element ->
            val embedUrl = element.attr("data-url")
            val serverName = element.select("a").text()
            getVideosFromUrl(embedUrl, serverName, episodeUrl)
        }
    }

    private suspend fun getVideosFromUrl(url: String, quality: String, referer: String): List<Video> {
        return try {
            when {
                "dood" in url || "d-s" in url -> doodExtractor.videosFromUrl(url, quality)
                "uqload" in url -> uqloadExtractor.videosFromUrl(url)
                "voe" in url -> voeExtractor.videosFromUrl(url)
                "vidmoly" in url -> vidmolyExtractor.videosFromUrl(url)
                "streamtape" in url -> streamtapeExtractor.videosFromUrl(url)
                "streamwish" in url || "filelions" in url || "wishembed" in url || "iplayerhls" in url -> streamwishExtractor.videosFromUrl(url)
                "vidbom" in url || "vbn2" in url -> vidbomExtractor.videosFromUrl(url)
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================ Main Slider =============================
    suspend fun fetchMainSlider(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())

        document.select("div#big_carousel div.owl-item div.item").map {
            SAnime().apply {
                this.url = it.select("a.first_A").attr("href")
                this.title = it.select("h3").text()
                this.thumbnail_url = it.select("img").attr("src")
                this.source = AnimeSource.ARABDRAMA2.name
            }
        }
    }

    // ======================== Home Latest Episodes ========================
    suspend fun fetchHomePageLatestEpisodes(): List<SAnime> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl).build()
        val document = Jsoup.parse(client.newCall(request).execute().body!!.string())
        document.select("div.righter div.b_block div.item_small").map {
            SAnime().apply {
                this.url = it.select("a").attr("href")
                this.title = it.select("h3 a").text()
                this.thumbnail_url = it.select("img").attr("src")
                this.source = AnimeSource.ARABDRAMA2.name
            }
        }
    }

    fun getFilterList() = AnimeFilterList(emptyList()) // No filters implemented for this source
}
