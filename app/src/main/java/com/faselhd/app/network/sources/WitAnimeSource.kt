package com.faselhd.app.network.sources

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.*
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.CloudflareInterceptor
import com.faselhd.app.network.extractors.*
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.CookieManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.text.Regex
import java.util.Base64
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


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


    private val baseUrl = "https://witanime.red"

    // Data class for server information
    //region Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidmolyExtractor by lazy { VidmolyExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }
    private val vidbomExtractor by lazy { VidBomExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mivalyoExtractor by lazy { MivalyoExtractor(client) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client) }
    private val fourSharedExtractor by lazy { FourSharedExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
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
            val responseHtml = client.newCall(request).execute().body!!.string()
            val document = Jsoup.parse(responseHtml)

            // Get the anime title, which can be found in different places on each page type
            val animeTitle = document.selectFirst(".anime-page-link a")?.text() // From episode page
                ?: document.selectFirst("h1.anime-details-title")?.text() // From main anime page
                ?: "Unknown Anime"

            // --- METHOD 1: Check for encrypted data on the main anime page ---
            val scriptRegex = """var processedEpisodeData = '(.+?)';""".toRegex()
            val matchResult = scriptRegex.find(responseHtml)

            if (matchResult != null) {
                val encodedString = matchResult.groups[1]?.value
                if (encodedString != null && encodedString.contains(".")) {
                    val parts = encodedString.split('.')
                    if (parts.size == 2) {
                        try {
                            val encodedData = parts[0]
                            val encodedKey = parts[1]
                            val dataBytes = Base64.getDecoder().decode(encodedData)
                            val keyBytes = Base64.getDecoder().decode(encodedKey)

                            val decryptedBytes = ByteArray(dataBytes.size)
                            for (i in dataBytes.indices) {
                                decryptedBytes[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
                            }

                            val decodedHtml = String(decryptedBytes, StandardCharsets.UTF_8)
                            val episodesDocument = Jsoup.parse(decodedHtml, animeUrl)

                            return@withContext episodesDocument.select("a").map { element ->
                                createEpisode(element, animeTitle)
                            }.sortedBy { it.episode_number }
                        } catch (e: Exception) {
                            Log.e("WitAnimeSource", "Failed to decrypt episode data. Falling back to other method.", e)
                        }
                    }
                }
            }

            // --- METHOD 2: Fallback for episode watch pages (Base64 in onclick) ---
            val episodeLinks = document.select("ul#ULEpisodesList li a")
            if (episodeLinks.isNotEmpty()) {
                val episodes = mutableListOf<SEpisode>()
                val onclickRegex = """openEpisode\('([^']+)'\)""".toRegex()

                episodeLinks.forEach { element ->
                    val onclickAttr = element.attr("onclick")
                    val b64Match = onclickRegex.find(onclickAttr)
                    if (b64Match != null) {
                        val b64Url = b64Match.groupValues[1]
                        try {
                            val decodedUrl = String(Base64.getDecoder().decode(b64Url), StandardCharsets.UTF_8)
                            episodes.add(createEpisode(element, animeTitle, episodeUrl = decodedUrl))
                        } catch (e: IllegalArgumentException) {
                            Log.e("WitAnimeSource", "Failed to decode Base64 URL from onclick: $b64Url")
                        }
                    }
                }
                if (episodes.isNotEmpty()) {
                    return@withContext episodes.sortedBy { it.episode_number }
                }
            }

            // If neither method found episodes, return an empty list
            Log.w("WitAnimeSource", "No episodes found on page: $animeUrl")
            return@withContext emptyList()

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Helper function to create an SEpisode object. It can now take an optional
     * explicit URL for cases where the element's href is not the real link.
     */
    private fun createEpisode(element: Element, seriesName: String, episodeUrl: String = ""): SEpisode {
        val episodeText = element.text()
        // Use the provided episodeUrl if it's not empty, otherwise get it from the element's href
        val finalUrl = episodeUrl.ifEmpty { element.attr("abs:href") }
        println("final episode url: $finalUrl")
        return SEpisode().apply {
            url = finalUrl
            name = "$seriesName: $episodeText"
            episode_number = Regex("""الحلقة\s+(\d+)""").find(episodeText)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""(\d+)""").find(episodeText)?.value?.toFloatOrNull()
                        ?: 0f
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

    // The DecryptionParams data class remains the same
    // The DecryptionParams data class remains the same
    // Data class for the main decryption parameters from _zH
    // The 'd' and 'x' arrays are no longer needed.
    data class DecryptionParams(
        val k: String,
        val v: String
    )

    // Data class to parse the salt from the _m variable
    data class SaltParams(
        val r: String
    )

    /**
     * FINAL CORRECTED FUNCTION
     * This version correctly extracts the necessary data and applies the RC4
     * decryption directly to the encrypted URL data.
     */
    suspend fun fetchVideoList(episodeUrl: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            Log.d("WitAnime", "Starting video fetch for URL: $episodeUrl")
            val request = Request.Builder().url(episodeUrl).build()
            val responseHtml = client.newCall(request).execute().body!!.string()
            val document = Jsoup.parse(responseHtml)
            val videos = mutableListOf<Video>()

            // 1. Find and extract the primary data variables (_zG, _zH, _m)
            val zGRegex = """var _zG\s*=\s*"([^"]+)";""".toRegex()
            val zHRegex = """var _zH\s*=\s*"([^"]+)";""".toRegex()
            val mRegex = """var _m\s*=\s*(\{.+\});""".toRegex()

            val zGMatch = zGRegex.find(responseHtml)
            val zHMatch = zHRegex.find(responseHtml)
            val mMatch = mRegex.find(responseHtml)

            if (zGMatch == null || zHMatch == null || mMatch == null) {
                Log.e("WitAnime", "Failed to find one or more required JS variables (_zG, _zH, _m).")
                return@withContext emptyList()
            }
            Log.d("WitAnime", "Successfully found all required JS variables.")

            // 2. Base64 decode the data to get JSON strings
            val jsonZG = String(Base64.getDecoder().decode(zGMatch.groupValues[1]), StandardCharsets.UTF_8)
            val jsonZH = String(Base64.getDecoder().decode(zHMatch.groupValues[1]), StandardCharsets.UTF_8)

            // 3. Parse the JSON strings into Kotlin objects
            val gson = Gson()
            val urlListType = object : TypeToken<List<String>>() {}.type
            val paramsListType = object : TypeToken<List<DecryptionParams>>() {}.type
            val saltType = object : TypeToken<SaltParams>() {}.type

            val encryptedUrls: List<String> = gson.fromJson(jsonZG, urlListType)
            val decryptionParams: List<DecryptionParams> = gson.fromJson(jsonZH, paramsListType)
            val saltParams: SaltParams = gson.fromJson(mMatch.groupValues[1], saltType)
            val salt = String(Base64.getDecoder().decode(saltParams.r), StandardCharsets.UTF_8)

            Log.d("WitAnime", "Parsed ${encryptedUrls.size} encrypted URLs and ${decryptionParams.size} parameter sets.")
            Log.d("WitAnime", "Extracted salt: $salt")

            // 4. Iterate through servers and decrypt the URLs
            document.select("ul#episode-servers a.server-link").forEach { serverElement ->
                val serverId = serverElement.attr("data-server-id").toIntOrNull()
                val serverName = serverElement.text().trim()

                if (serverId != null && serverId < encryptedUrls.size && serverId < decryptionParams.size) {
                    val encryptedUrlData = encryptedUrls[serverId]
                    val params = decryptionParams[serverId]

                    Log.d("WitAnime", "Processing Server: '$serverName' (ID: $serverId)")

                    // --- DECRYPT THE URL DIRECTLY ---
                    val decryptedUrl = decryptUrl(encryptedUrlData, params, salt)

                    if (decryptedUrl.isNotEmpty() && (decryptedUrl.startsWith("http") || decryptedUrl.startsWith("//"))) {
                        Log.i("WitAnime", " -> SUCCESS: Decrypted URL: $decryptedUrl")
                        videos.add(
                            Video(
                                url = decryptedUrl,
                                quality = serverName,
                                videoUrl = decryptedUrl,
                                headers = mapOf("Referer" to episodeUrl)
                            )
                        )
                    } else {
                        Log.w("WitAnime", " -> FAILED: Decryption returned invalid URL for server '$serverName'. Result: '$decryptedUrl'")
                    }
                }
            }

            Log.i("WitAnime", "Finished processing. Found ${videos.size} unique video links.")
            return@withContext videos.distinctBy { it.url }

        } catch (e: Exception) {
            Log.e("WitAnime", "A critical error occurred while fetching the video list.", e)
            return@withContext emptyList()
        }
    }

    /**
     * UNCHANGED
     * This function correctly implements the RC4 cipher. The key was to provide it
     * with the correctly encoded ciphertext.
     */
    private fun decryptUrl(encryptedUrl: String, params: DecryptionParams, salt: String): String {
        try {
            // Step 1: Get raw bytes from the encrypted string using ISO_8859_1 to prevent corruption.
            val encryptedBytes = encryptedUrl.toByteArray(StandardCharsets.ISO_8859_1)

            // Step 2: Construct the full RC4 key.
            val keyPart1 = String(Base64.getDecoder().decode(params.v), StandardCharsets.UTF_8)
            val keyPart2 = String(Base64.getDecoder().decode(params.k), StandardCharsets.UTF_8)
            val key = (keyPart1 + keyPart2 + salt).toByteArray(StandardCharsets.UTF_8)

            // Step 3: RC4 Key-Scheduling Algorithm (KSA).
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0..255) {
                val keyByte = key[i % key.size].toInt() and 0xFF
                j = (j + s[i] + keyByte) % 256
                s[i] = s[j].also { s[j] = s[i] } // Swap
            }

            // Step 4: RC4 Pseudo-Random Generation Algorithm (PRGA) and XORing.
            var i = 0
            j = 0
            val decryptedBytes = ByteArray(encryptedBytes.size)
            for (byteIndex in encryptedBytes.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                s[i] = s[j].also { s[j] = s[i] } // Swap
                val keystreamByte = s[(s[i] + s[j]) % 256]
                decryptedBytes[byteIndex] = (encryptedBytes[byteIndex].toInt() xor keystreamByte).toByte()
            }

            // The final result is a readable UTF-8 string (the URL).
            return String(decryptedBytes, StandardCharsets.UTF_8)

        } catch (e: Exception) {
            Log.e("WitAnimeDecrypt", "Failed to decrypt URL.", e)
            return ""
        }
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

