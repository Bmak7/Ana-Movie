package com.faselhd.app.network.sources

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.models.DaddyLiveChannel
import com.faselhd.app.models.SLiveTv
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Helper data classes for JSON parsing within the source
@Serializable
data class ServerKeyResponse(
    @SerialName("server_key") val serverKey: String
)

@Serializable
data class Bundle(
    @SerialName("b_host") val bHost: String,
    @SerialName("b_rnd") val bRnd: String,
    @SerialName("b_script") val bScript: String,
    @SerialName("b_sig") val bSig: String,
    @SerialName("b_ts") val bTs: String
)

class DaddyLiveSource(private val context: Context) {

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

    private val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    private val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .ignoreAllSSLErrors()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )
            ).apply {
                when (dns) {
                    1 -> addGoogleDns()
                    2 -> addCloudFlareDns()
                    4 -> addAdGuardDns()
                    5 -> addDNSWatchDns()
                    6 -> addQuad9Dns()
                    7 -> addDnsSbDns()
                    8 -> addCanadianShieldDns()
                }
            }
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://dlhd.dad"
    private var cachedChannels: List<DaddyLiveChannel> = emptyList()
    private val posterUrl = "https://seo-michael.co.uk/content/images/2025/04/dlslogo.png"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private suspend fun getAllChannels(): List<DaddyLiveChannel> {
        if (cachedChannels.isNotEmpty()) {
            return cachedChannels
        }
        return try {
            val request = Request.Builder().url("$baseUrl/24-7-channels.php").build()
            val response = client.newCall(request).execute().body!!.string()
            val document = Jsoup.parse(response)
            cachedChannels = document.select("a.card").mapNotNull {
                val title = it.select(".card__title").text()
                val id = it.attr("href").substringAfter("id=", "")
                if (id.isNotEmpty()) DaddyLiveChannel(id, title) else null
            }
            cachedChannels
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun fetchAllChannelsByCountry(): Map<String, List<SLiveTv>> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext mapOf("Live TV & Sports" to channels.map { channelToSLiveTv(it) })
    }

    suspend fun search(query: String): List<SLiveTv> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { channelToSLiveTv(it) }
    }

    private fun channelToSLiveTv(channel: DaddyLiveChannel): SLiveTv {
        return SLiveTv().apply {
            title = channel.name
            url = json.encodeToString(DaddyLiveChannel.serializer(), channel)
            posterUrl = this@DaddyLiveSource.posterUrl
            country = "Live"
            source = AnimeSource.DADDY_LIVE.name
        }
    }

    // Helper function to log long strings to Logcat without truncation
    fun logLongString(tag: String, message: String) {
        val maxLogSize = 4000
        for (i in 0..message.length / maxLogSize) {
            val start = i * maxLogSize
            var end = (i + 1) * maxLogSize
            end = if (end > message.length) message.length else end
            Log.d(tag, message.substring(start, end))
        }
    }

    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
        try {
            Log.d("DDL", "fetchLiveStreamLink started for: ${channelJson.take(100)}...")
            val channel = json.decodeFromString(DaddyLiveChannel.serializer(), channelJson)

            // Extract the stream ID from the URL
            val streamIdPattern = Pattern.compile("(?:stream-|id=)(\\d+)")
            val streamIdMatcher = streamIdPattern.matcher("https://dlhd.dad/watch.php?id=${channel.id}")
            val streamId = if (streamIdMatcher.find()) {
                streamIdMatcher.group(1) ?: run {
                    Log.e("DDL", "Could not extract stream ID from URL: ${"https://dlhd.dad/watch.php?id=${channel.id}"}")
                    return@withContext null
                }
            } else {
                Log.e("DDL", "Invalid URL format: ${"https://dlhd.dad/watch.php?id=${channel.id}"}")
                return@withContext null
            }

            Log.d("DDL", "Extracted stream ID: $streamId")

            // Try direct watch page first
            val watchUrl = "$baseUrl/watch.php?id=$streamId"
            val watchVideo = tryExtractFromWatchPage(watchUrl, streamId)
            if (watchVideo != null) {
                Log.d("DDL", "Successfully got stream from watch page")
                return@withContext watchVideo
            }

            // List of player endpoints to try
            val playerEndpoints = listOf(
                "/stream/stream-$streamId.php",
                "/cast/stream-$streamId.php",
                "/watch/stream-$streamId.php",
                "/plus/stream-$streamId.php",
                "/casting/stream-$streamId.php",
                "/player/stream-$streamId.php"
            )

            // Try each player endpoint
            for (endpoint in playerEndpoints) {
                Log.d("DDL", "Trying player endpoint: $endpoint")
                val playerUrl = "$baseUrl$endpoint"

                val video = tryFetchFromPlayerEndpoint(playerUrl, channel.name, streamId)
                if (video != null) {
                    Log.d("DDL", "Successfully got stream from endpoint: $endpoint")
                    return@withContext video
                }
            }

            Log.e("DDL", "All endpoints failed for channel: ${channel.name}")
            return@withContext null

        } catch (e: Exception) {
            Log.e("DDL", "Exception in fetchLiveStreamLink: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun tryExtractFromWatchPage(watchUrl: String, streamId: String): Video? {
        try {
            Log.d("DDL", "Trying watch page: $watchUrl")

            val request = Request.Builder()
                .url(watchUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", baseUrl)
                .build()

            val response = client.newCall(request).execute()
            val content = response.body?.string() ?: return null
            response.close()

            // Log the full HTML content of the watch page
            logLongString("DDL_HTML", "Watch Page HTML:\n$content")


            // Look for player button URLs and try them
            val playerButtonPattern = Pattern.compile("data-url=[\"']([^\"']+)[\"']")
            val matcher = playerButtonPattern.matcher(content)

            while (matcher.find()) {
                val playerPath = matcher.group(1)
                val fullPlayerUrl = if (playerPath.startsWith("http")) {
                    playerPath
                } else {
                    "$baseUrl$playerPath"
                }

                Log.d("DDL", "Trying player URL from watch page: $fullPlayerUrl")
                val video = tryFetchFromPlayerEndpoint(fullPlayerUrl, "", streamId)
                if (video != null) {
                    return video
                }
            }

            return null
        } catch (e: Exception) {
            Log.e("DDL", "Exception in tryExtractFromWatchPage: ${e.message}")
            return null
        }
    }

    private suspend fun tryFetchFromPlayerEndpoint(playerUrl: String, channelName: String, streamId: String): Video? {
        try {
            Log.d("DDL", "Trying to fetch from URL: $playerUrl")

            val request = Request.Builder()
                .url(playerUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", baseUrl)
                .addHeader("Origin", baseUrl)
                .build()

            val response = client.newCall(request).execute()
            val pageContent = response.body?.string() ?: run {
                Log.e("DDL", "Empty response body from player page: $playerUrl")
                response.close()
                return null
            }
            response.close()

            Log.d("DDL", "Page content length: ${pageContent.length}")
            // Log the full HTML content of the player page
            logLongString("DDL_HTML", "Player Page HTML from $playerUrl:\n$pageContent")


            // First try to find iframe sources
            val iframePatterns = listOf(
                Pattern.compile("src=\"([^\"]*(?:stream|player|cast)[^\"]*?\\.php[^\"]*?)\"", Pattern.CASE_INSENSITIVE),
                Pattern.compile("<iframe[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            )

            for (pattern in iframePatterns) {
                val matcher = pattern.matcher(pageContent)
                if (matcher.find()) {
                    val iframeSrc = matcher.group(1)
                    Log.d("DDL", "Found iframe src: $iframeSrc")

                    val fullIframeUrl = if (iframeSrc.startsWith("http")) {
                        iframeSrc
                    } else if (iframeSrc.startsWith("//")) {
                        "https:$iframeSrc"
                    } else if (iframeSrc.startsWith("/")) {
                        "$baseUrl$iframeSrc"
                    } else {
                        val baseUrlWithoutPath = baseUrl.substringBeforeLast("/")
                        "$baseUrlWithoutPath/$iframeSrc"
                    }

                    val streamUrl = extractFinalUrl(fullIframeUrl, playerUrl)
                    if (streamUrl != null) {
                        val parsedUrl = URL(fullIframeUrl)
                        val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"

                        return Video(
                            url = streamUrl,
                            quality = "Live",
                            videoUrl = streamUrl,
                            headers = mapOf(
                                "Referer" to "$refererBase/",
                                "User-Agent" to userAgent,
                                "Origin" to refererBase
                            )
                        )
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Log.e("DDL", "Exception in tryFetchFromPlayerEndpoint: ${e.message}")
            return null
        }
    }

    private suspend fun extractFinalUrl(iframeSrc: String, referer: String): String? {
        try {
            Log.d("DDL", "extractFinalUrl started with iframeSrc: $iframeSrc, referer: $referer")

            // Step 1: Fetch the iframe page content
            val iframeRequest = Request.Builder()
                .url(iframeSrc)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", referer)
                .addHeader("Origin", referer.substringBeforeLast("/"))
                .build()

            val iframeResponse = client.newCall(iframeRequest).execute()
            val scriptContent = iframeResponse.body?.string() ?: run {
                Log.e("DDL", "Empty response body from iframe")
                iframeResponse.close()
                return null
            }
            iframeResponse.close()

            Log.d("DDL", "Iframe response code: ${iframeResponse.code}, content length: ${scriptContent.length}")

            // Log the full HTML/JS content from the iframe
            logLongString("DDL_JS", "Iframe content from $iframeSrc:\n$scriptContent")

            // Extract and log external JavaScript files
            val scriptPattern = Pattern.compile("<script[^>]*src=[\"']([^\"']+\\.js)[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
            val scriptMatcher = scriptPattern.matcher(scriptContent)
            while (scriptMatcher.find()) {
                val scriptUrl = scriptMatcher.group(1)
                val fullScriptUrl = if (scriptUrl.startsWith("http")) {
                    scriptUrl
                } else if (scriptUrl.startsWith("//")) {
                    "https:$scriptUrl"
                } else if (scriptUrl.startsWith("/")) {
                    val iframeUrl = URL(iframeSrc)
                    "${iframeUrl.protocol}://${iframeUrl.host}$scriptUrl"
                } else {
                    val baseUrlWithoutPath = iframeSrc.substringBeforeLast("/")
                    "$baseUrlWithoutPath/$scriptUrl"
                }
                Log.d("DDL", "Found external JS: $fullScriptUrl")
                try {
                    val jsRequest = Request.Builder().url(fullScriptUrl).addHeader("Referer", iframeSrc).build()
                    val jsResponse = client.newCall(jsRequest).execute()
                    val jsCode = jsResponse.body?.string()
                    if (jsCode != null) {
                        logLongString("DDL_JS", "JavaScript from $fullScriptUrl:\n$jsCode")
                    }
                    jsResponse.close()
                } catch (e: Exception) {
                    Log.e("DDL", "Failed to fetch external JS: $fullScriptUrl", e)
                }
            }


            // Extract server URL from iframe URL
            val serverUrl = when {
                iframeSrc.contains("/premiumtv") -> iframeSrc.substringBefore("/premiumtv")
                iframeSrc.contains("/player") -> iframeSrc.substringBefore("/player")
                iframeSrc.contains("/daddy") -> iframeSrc.substringBefore("/daddy")
                else -> {
                    val parsedUrl = URL(iframeSrc)
                    "${parsedUrl.protocol}://${parsedUrl.host}"
                }
            }
            Log.d("DDL", "Extracted server URL: $serverUrl")

            // Step 2: Extract BUNDLE/XJZ and CHANNEL_KEY with multiple patterns
            var bundle: String? = null
            val bundlePatterns = listOf(
                Pattern.compile("""const\s+BUNDLE\s*=\s*["']([^"']+)["']"""),
                Pattern.compile("""const\s+XJZ\s*=\s*["']([^"']+)["']"""),
                Pattern.compile("""const\s+\w+\s*=\s*["']([^"']+)["']"""),
                Pattern.compile("""var\s+\w+\s*=\s*["']([^"']+)["']""")
            )

            for (pattern in bundlePatterns) {
                val matcher = pattern.matcher(scriptContent)
                if (matcher.find()) {
                    bundle = matcher.group(1)
                    Log.d("DDL", "Bundle found with pattern: $pattern, value: ${bundle.take(50)}...")
                    break
                }
            }

            var channelKey: String? = null
            val channelKeyPatterns = listOf(
                Pattern.compile("""const\s+CHANNEL_KEY\s*=\s*["']([^"']+)["']"""),
                Pattern.compile("""const\s+CHANNEL_ID\s*=\s*["']([^"']+)["']"""),
                Pattern.compile("""channel_id["']\s*:\s*["']([^"']+)["']"""),
                Pattern.compile("""id["']\s*:\s*["']([^"']+)["']""")
            )

            for (pattern in channelKeyPatterns) {
                val matcher = pattern.matcher(scriptContent)
                if (matcher.find()) {
                    channelKey = matcher.group(1)
                    Log.d("DDL", "Channel Key found with pattern: $pattern, value: $channelKey")
                    break
                }
            }

            // Fallback: Extract channel key from iframe URL parameters
            if (channelKey == null) {
                val urlPatterns = listOf(
                    Pattern.compile("""[?&]id=(\d+)"""),
                    Pattern.compile("""[?&]a=(\d+)"""),
                    Pattern.compile("""[?&]channel=(\d+)""")
                )

                for (pattern in urlPatterns) {
                    val matcher = pattern.matcher(iframeSrc)
                    if (matcher.find()) {
                        channelKey = matcher.group(1)
                        Log.d("DDL", "Fallback Channel Key from URL with pattern: $pattern, value: $channelKey")
                        break
                    }
                }
            }

            if (bundle == null || channelKey == null) {
                Log.e("DDL", "Missing BUNDLE ($bundle) or CHANNEL_KEY ($channelKey) in script content")
                logLongString("DDL_JS_FAILED", "Full script content for debugging: $scriptContent")
                return null
            }

            // Step 3: Decode the bundle
            val bundleJson = try {
                String(Base64.decode(bundle, Base64.DEFAULT))
            } catch (e: Exception) {
                Log.e("DDL", "Failed to decode bundle: ${e.message}")
                return null
            }
            Log.d("DDL", "Decoded bundle JSON: $bundleJson")

            val bundleObj = try {
                json.decodeFromString<Bundle>(bundleJson)
            } catch (e: Exception) {
                Log.e("DDL", "Failed to parse bundle JSON: ${e.message}")
                return null
            }

            // Step 4: Decode auth parameters
            val authTs = base64Decode(bundleObj.bTs)
            val authRnd = base64Decode(bundleObj.bRnd)
            val authSig = base64Decode(bundleObj.bSig)

            if (authTs.isEmpty() || authRnd.isEmpty() || authSig.isEmpty()) {
                Log.e("DDL", "Failed to decode auth parameters")
                return null
            }

            Log.d("DDL", "Decoded auth params - ts: $authTs, rnd: $authRnd, sig: ${authSig.take(20)}...")

            // Step 5: Make the authentication request
            val authUrl = "https://top2new.newkso.ru/auth.php?channel_id=$channelKey&ts=$authTs&rnd=$authRnd&sig=${URLEncoder.encode(authSig, "UTF-8")}"
            Log.d("DDL", "Auth URL: $authUrl")

            val authRequest = Request.Builder()
                .url(authUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", "$serverUrl/")
                .addHeader("Origin", serverUrl)
                .build()

            val authResponse = client.newCall(authRequest).execute()
            Log.d("DDL", "Auth response code: ${authResponse.code}")

            if (authResponse.code == 403) {
                Log.e("DDL", "Auth request failed with 403 Forbidden")
                authResponse.close()
                return null
            }
            authResponse.close()

            // Step 6: Make the server lookup request
            val serverLookupUrl = "$serverUrl/server_lookup.php?channel_id=$channelKey"
            Log.d("DDL", "Server lookup URL: $serverLookupUrl")

            val serverKeyRequest = Request.Builder()
                .url(serverLookupUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", "$serverUrl/")
                .addHeader("Origin", serverUrl)
                .build()

            val serverKeyResponse = client.newCall(serverKeyRequest).execute()
            val serverKeyJson = serverKeyResponse.body?.string() ?: run {
                Log.e("DDL", "Empty response from server lookup")
                serverKeyResponse.close()
                return null
            }
            Log.d("DDL", "Server lookup response code: ${serverKeyResponse.code}, content: $serverKeyJson")
            serverKeyResponse.close()

            val serverData = try {
                json.decodeFromString<ServerKeyResponse>(serverKeyJson)
            } catch (e: Exception) {
                Log.e("DDL", "Failed to parse server key JSON: ${e.message}")
                return null
            }

            val serverKey = serverData.serverKey
            Log.d("DDL", "Extracted server key: $serverKey")

            // Step 7: Construct the final M3U8 URL
            val finalUrl = if (serverKey == "top1/cdn") {
                "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
            } else {
                "https://$serverKey.newkso.ru/$serverKey/$channelKey/mono.m3u8"
            }

            Log.d("DDL", "Final URL constructed: $finalUrl")
            return finalUrl

        } catch (e: Exception) {
            Log.e("DDL", "Exception in extractFinalUrl: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun base64Decode(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e("DDL", "Base64 decode failed: ${e.message}")
            ""
        }
    }
    private fun isValidStreamUrl(url: String?): Boolean {
        if (url == null || url.length < 10) return false

        return url.startsWith("http") &&
                url.contains(".m3u8") &&
                !url.contains("\${") &&
                !url.contains("{CHANNEL_KEY}") &&
                !url.contains("undefined") &&
                !url.contains("null")
    }
}
