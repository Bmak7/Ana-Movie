package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.DaddyLiveChannel
import com.faselhd.app.models.SLiveTv
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.myapplication.R
import com.faselhd.app.utils.*
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import java.io.File
import java.net.URL

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

    private val baseUrl = "https://thedaddy.top"
    private var cachedChannels: List<DaddyLiveChannel> = emptyList()
    private val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val countries = listOf(
        "Andorra", "UAE", "Afghanistan", "Antigua and Barbuda", "Anguilla", "Albania", "Armenia", "Angola",
        "Antarctica", "Argentina", "American Samoa", "Austria", "Australia", "Aruba", "Aland", "Azerbaijan",
        "Bosnia and Herzegovina", "Barbados", "Bangladesh", "Belgium", "Burkina Faso", "Bulgaria", "Bahrain",
        "Burundi", "Benin", "Saint Barthelemy", "Bermuda", "Brunei", "Bolivia", "Bonaire Sint Eustatius and Saba",
        "Brasil", "Bahamas", "Bhutan", "Bouvet Island", "Botswana", "Belarus", "Belize", "Canada",
        "Cocos Keeling Islands", "DR Congo", "Central African Republic", "Congo Republic", "Switzerland",
        "Ivory Coast", "Cook Islands", "Chile", "Cameroon", "China", "Colombia", "Costa Rica", "Cuba",
        "Cabo Verde", "Curacao", "Christmas Island", "Cyprus", "Czechia", "Germany", "Djibouti", "Denmark",
        "Dominica", "Dominican Republic", "Algeria", "Ecuador", "Estonia", "Egypt", "Western Sahara", "Eritrea",
        "Spain", "Ethiopia", "Finland", "Fiji", "Falkland Islands", "Micronesia", "Faroe Islands", "France",
        "Gabon", "UK", "Grenada", "Georgia", "French Guiana", "Guernsey", "Ghana", "Gibraltar", "Greenland",
        "The Gambia", "Guinea", "Guadeloupe", "Equatorial Guinea", "Greece", "South Georgia and South Sandwich Islands",
        "Guatemala", "Guam", "Guinea-Bissau", "Guyana", "Hong Kong", "Heard and McDonald Islands", "Honduras",
        "Croatia", "Haiti", "Hungary", "Indonesia", "Ireland", "Israel", "Isle of Man", "India",
        "British Indian Ocean Territory", "Iraq", "Iran", "Iceland", "Italy", "Jersey", "Jamaica", "Jordan",
        "Japan", "Kenya", "Kyrgyzstan", "Cambodia", "Kiribati", "Comoros", "St Kitts and Nevis", "North Korea",
        "South Korea", "Kuwait", "Cayman Islands", "Kazakhstan", "Laos", "Lebanon", "Saint Lucia", "Liechtenstein",
        "Sri Lanka", "Liberia", "Lesotho", "Lithuania", "Luxembourg", "Latvia", "Libya", "Morocco", "Monaco",
        "Moldova", "Montenegro", "Saint Martin", "Madagascar", "Marshall Islands", "North Macedonia", "Mali",
        "Myanmar", "Mongolia", "Macao", "Northern Mariana Islands", "Martinique", "Mauritania", "Montserrat",
        "Malta", "Mauritius", "Maldives", "Malawi", "Mexico", "Malaysia", "Mozambique", "Namibia", "New Caledonia",
        "Niger", "Norfolk Island", "Nigeria", "Nicaragua", "The Netherlands", "Norway", "Nepal", "Nauru", "Niue",
        "New Zealand", "Oman", "Panama", "Peru", "French Polynesia", "Papua New Guinea", "Philippines", "Pakistan",
        "Poland", "Saint Pierre and Miquelon", "Pitcairn Islands", "Puerto Rico", "Palestine", "Portugal", "Palau",
        "Paraguay", "Qatar", "Reunion", "Romania", "Serbia", "Russia", "Rwanda", "Saudi Arabia", "Solomon Islands",
        "Seychelles", "Sudan", "Sweden", "Singapore", "Saint Helena", "Slovenia", "Svalbard and Jan Mayen",
        "Slovakia", "Sierra Leone", "San Marino", "Senegal", "Somalia", "Suriname", "South Sudan",
        "Sao Tome and Principe", "El Salvador", "Sint Maarten", "Syria", "Eswatini", "Turks and Caicos Islands",
        "Chad", "French Southern Territories", "Togo", "Thailand", "Tajikistan", "Tokelau", "Timor-Leste",
        "Turkmenistan", "Tunisia", "Tonga", "Turkey", "Trinidad and Tobago", "Tuvalu", "Taiwan", "Tanzania",
        "Ukraine", "Uganda", "U.S. Outlying Islands", "USA", "Uruguay", "Uzbekistan", "Vatican City",
        "St Vincent and Grenadines", "Venezuela", "British Virgin Islands", "U.S. Virgin Islands", "Vietnam",
        "Vanuatu", "Wallis and Futuna", "Samoa", "Kosovo", "Yemen", "Mayotte", "South Africa", "Zambia", "Zimbabwe"
    )

    // Fetches and caches all channels from DaddyLive
    private suspend fun getAllChannels(): List<DaddyLiveChannel> {
        if (cachedChannels.isNotEmpty()) {
            return cachedChannels
        }

        return try {
            val channelsUrl = "$baseUrl/24-7-channels.php"
            val requestBody = FormBody.Builder().build()
            val request = Request.Builder()
                .url(channelsUrl)
                .post(requestBody)
                .addHeader("Referer", baseUrl)
                .addHeader("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: run {
                Log.e("DDL", "Empty response body from channels page")
                return emptyList()
            }

            // Parse the HTML response using regex patterns
            val chBlockPattern = Pattern.compile("<center><h1(.+?)tab-2", Pattern.DOTALL or Pattern.MULTILINE)
            val chBlockMatcher = chBlockPattern.matcher(responseBody)
            val chBlock = if (chBlockMatcher.find()) chBlockMatcher.group(1) else ""

            val chanDataPattern = Pattern.compile("href=\"(.*?)\"\\s+target.*?><strong>(.*?)</strong>")
            val chanDataMatcher = chanDataPattern.matcher(chBlock)
            val channels = mutableListOf<DaddyLiveChannel>()

            while (chanDataMatcher.find()) {
                val href = chanDataMatcher.group(1) ?: continue
                val strongText = chanDataMatcher.group(2) ?: continue
                val country = determineCountry(strongText)

                channels.add(
                    DaddyLiveChannel(
                        name = strongText,
                        url = href,
                        country = country
                    )
                )
            }

            cachedChannels = channels
            channels
        } catch (e: Exception) {
            Log.e("DDL", "Error fetching channels: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private fun determineCountry(channelName: String): String {
        val lastPart = channelName.substringAfterLast(" ").replace(")", "").trim()
        return countries.find { country ->
            country.lowercase() in lastPart.lowercase()
        } ?: "International"
    }

    suspend fun fetchAllChannelsByCountry(): Map<String, List<SLiveTv>> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .map { channelToSLiveTv(it) }
            .groupBy { it.country ?: "International" }
            .toSortedMap()
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
            country = channel.country
            source = AnimeSource.DADDY_LIVE.name
        }
    }

    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
        try {
            Log.d("DDL", "fetchLiveStreamLink started for: ${channelJson.take(100)}...")
            val channel = json.decodeFromString(DaddyLiveChannel.serializer(), channelJson)

            // Extract the stream ID from the original URL (e.g., "/stream/stream-91.php" -> "91")
            val streamIdPattern = Pattern.compile("stream-(\\d+)\\.php")
            val streamIdMatcher = streamIdPattern.matcher(channel.url)
            val streamId = if (streamIdMatcher.find()) {
                streamIdMatcher.group(1) ?: run {
                    Log.e("DDL", "Could not extract stream ID from URL: ${channel.url}")
                    return@withContext null
                }
            } else {
                Log.e("DDL", "Invalid URL format: ${channel.url}")
                return@withContext null
            }

            Log.d("DDL", "Extracted stream ID: $streamId")

            // List of player patterns to try
            val playerPatterns = listOf(
                "/stream/stream-$streamId.php",
                "/cast/stream-$streamId.php",
                "/hls/stream-$streamId.php",
                "/player/stream-$streamId.php"
            )

            // Try each player pattern until one works
            for (playerPattern in playerPatterns) {
                Log.d("DDL", "Trying player pattern: $playerPattern")
                val channelUrl = "$baseUrl$playerPattern"

                val video = tryFetchFromPlayer(channelUrl, channel.name, streamId)
                if (video != null) {
                    Log.d("DDL", "Successfully got stream from player: $playerPattern")
                    return@withContext video
                } else {
                    Log.d("DDL", "Failed to get stream from player: $playerPattern")
                }
            }

            Log.e("DDL", "All players failed for channel: ${channel.name}")
            return@withContext null

        } catch (e: Exception) {
            Log.e("DDL", "Exception in fetchLiveStreamLink: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun tryFetchFromPlayer(channelUrl: String, channelName: String, streamId: String): Video? {
        try {
            Log.d("DDL", "Trying to fetch from URL: $channelUrl")

            // Step 1: Fetch the stream page and extract iframe src
            val request = Request.Builder()
                .url(channelUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", baseUrl)
                .addHeader("Origin", baseUrl)
                .build()

            val response = client.newCall(request).execute()
            val pageContent = response.body?.string() ?: run {
                Log.e("DDL", "Empty response body from channel page: $channelUrl")
                response.close()
                return null
            }
            response.close()

            Log.d("DDL", "Page content length: ${pageContent.length}")

            // Look for iframe src - try multiple patterns
            val iframePatterns = listOf(
                Pattern.compile("<iframe[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
                Pattern.compile("<iframe[^>]*src='([^']+)'", Pattern.CASE_INSENSITIVE),
                Pattern.compile("src=\"([^\"]*(?:premiumtv|player|stream)[^\"]*?)\"", Pattern.CASE_INSENSITIVE)
            )

            var iframeSrc: String? = null
            for (pattern in iframePatterns) {
                val matcher = pattern.matcher(pageContent)
                if (matcher.find()) {
                    iframeSrc = matcher.group(1)
                    Log.d("DDL", "Found iframe with pattern: $pattern, src: $iframeSrc")
                    break
                }
            }

            if (iframeSrc == null) {
                Log.e("DDL", "No iframe found in page content for: $channelUrl")
                // Log a snippet of the content for debugging
                Log.d("DDL", "Page content snippet: ${pageContent.take(500)}")
                return null
            }

            Log.d("DDL", "Iframe URL: $iframeSrc")

            // Extract final .m3u8 URL
            val finalUrl = extractFinalUrl(iframeSrc, channelUrl) ?: run {
                Log.e("DDL", "extractFinalUrl returned null for iframe: $iframeSrc")
                return null
            }

            val parsedUrl = URL(iframeSrc)
            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"

            return Video(
                url = finalUrl,
                quality = "Live",
                videoUrl = finalUrl,
                headers = mapOf(
                    "Referer" to "$refererBase/",
                    "User-Agent" to userAgent,
                    "Origin" to refererBase
                )
            )

        } catch (e: Exception) {
            Log.e("DDL", "Exception in tryFetchFromPlayer for $channelUrl: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // Enhanced extractFinalUrl method with better error handling and multiple iframe formats
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

            // Log a snippet of the script content for debugging
            Log.d("DDL", "Script content snippet: ${scriptContent.take(200)}")

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
                Log.d("DDL", "Full script content for debugging: $scriptContent")
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
//    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
//        try {
//            Log.d("DDL", "fetchLiveStreamLink started for: ${channelJson.take(100)}...")
//            val channel = json.decodeFromString(DaddyLiveChannel.serializer(), channelJson)
//            val channelUrl = "$baseUrl${channel.url}"
//            Log.d("DDL", "Parsed channel - name: ${channel.name}, url: $channelUrl")
//
//            // Step 1: Fetch the stream page and extract iframe src
//            val request = Request.Builder()
//                .url(channelUrl)
//                .addHeader("User-Agent", userAgent)
//                .addHeader("Referer", baseUrl)
//                .addHeader("Origin", baseUrl)
//                .build()
//            val response = client.newCall(request).execute()
//            val pageContent = response.body?.string() ?: run {
//                Log.e("DDL", "Empty response body from channel page")
//                return@withContext null
//            }
//            response.close()
//
//            val iframePattern = Pattern.compile("<iframe[^>]*src=\"([^\"]+)\"")
//            val iframeMatcher = iframePattern.matcher(pageContent)
//            if (!iframeMatcher.find()) {
//                Log.e("DDL", "No iframe found in page content")
//                return@withContext null
//            }
//            val iframeSrc = iframeMatcher.group(1) ?: run {
//                Log.e("DDL", "Iframe src is null")
//                return@withContext null
//            }
//            Log.d("DDL", "Iframe URL: $iframeSrc")
//
//            // Extract final .m3u8 URL
//            val finalUrl = extractFinalUrl(iframeSrc, channelUrl) ?: run {
//                Log.e("DDL", "extractFinalUrl returned null")
//                return@withContext null
//            }
//
//            val parsedUrl = URL(iframeSrc)
//            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
//
//            return@withContext Video(
//                url = finalUrl,
//                quality = "Live",
//                videoUrl = finalUrl,
//                headers = mapOf(
//                    "Referer" to "$refererBase/",
//                    "User-Agent" to userAgent,
//                    "Origin" to refererBase
//                )
//            )
//        } catch (e: Exception) {
//            Log.e("DDL", "Exception in fetchLiveStreamLink: ${e.message}")
//            e.printStackTrace()
//            return@withContext null
//        }
//    }
//
//    private suspend fun extractFinalUrl(iframeSrc: String, referer: String): String? {
//        try {
//            Log.d("DDL", "extractFinalUrl started with iframeSrc: $iframeSrc, referer: $referer")
//
//            // Step 1: Fetch the iframe page content
//            val iframeRequest = Request.Builder()
//                .url(iframeSrc)
//                .addHeader("User-Agent", userAgent)
//                .addHeader("Referer", referer)
//                .addHeader("Origin", referer.substringBeforeLast("/"))
//                .build()
//            val iframeResponse = client.newCall(iframeRequest).execute()
//            val scriptContent = iframeResponse.body?.string() ?: run {
//                Log.e("DDL", "Empty response body from iframe")
//                return null
//            }
//            iframeResponse.close()
//            Log.d("DDL", "Iframe response code: ${iframeResponse.code}, content length: ${scriptContent.length}")
//
//            val serverUrl = iframeSrc.substringBefore("/premiumtv")
//            Log.d("DDL", "Extracted server URL: $serverUrl")
//
//            // Step 2: Extract BUNDLE (or alternative, e.g., XJZ) and CHANNEL_KEY
//            val bundlePattern = Pattern.compile("""const\s+\w+\s*=\s*"([^"]+)"""")
//            val bundleMatcher = bundlePattern.matcher(scriptContent)
//            var bundle: String? = null
//            if (bundleMatcher.find()) {
//                bundle = bundleMatcher.group(1)
//                Log.d("DDL", "Bundle found: ${bundle.take(50)}...")
//            }
//
//            val channelKeyPattern = Pattern.compile("""const CHANNEL_KEY\s*=\s*"([^"]+)"""")
//            val channelKeyMatcher = channelKeyPattern.matcher(scriptContent)
//            var channelKey: String? = null
//            if (channelKeyMatcher.find()) {
//                channelKey = channelKeyMatcher.group(1)
//                Log.d("DDL", "Channel Key: $channelKey")
//            }
//
//            // Fallback for channelKey from iframe URL
//            if (channelKey == null) {
//                val channelIdPattern = Pattern.compile("""id=(\d+)""")
//                val channelIdMatcher = channelIdPattern.matcher(iframeSrc)
//                if (channelIdMatcher.find()) {
//                    channelKey = channelIdMatcher.group(1)
//                    Log.d("DDL", "Fallback Channel Key from URL: $channelKey")
//                }
//            }
//
//            if (bundle == null || channelKey == null) {
//                Log.e("DDL", "Missing BUNDLE or CHANNEL_KEY in script content")
//                return null
//            }
//
//            // Step 3: Decode the bundle
//            val bundleJson = try {
//                String(Base64.decode(bundle, Base64.DEFAULT))
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to decode bundle: ${e.message}")
//                return null
//            }
//            Log.d("DDL", "Decoded bundle JSON: $bundleJson")
//
//            val bundleObj = try {
//                json.decodeFromString<Bundle>(bundleJson)
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to parse bundle JSON: ${e.message}")
//                return null
//            }
//
//            val authTs = try {
//                String(Base64.decode(bundleObj.bTs, Base64.DEFAULT))
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to decode b_ts: ${e.message}")
//                return null
//            }
//            val authRnd = try {
//                String(Base64.decode(bundleObj.bRnd, Base64.DEFAULT))
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to decode b_rnd: ${e.message}")
//                return null
//            }
//            val authSig = try {
//                String(Base64.decode(bundleObj.bSig, Base64.DEFAULT))
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to decode b_sig: ${e.message}")
//                return null
//            }
//            Log.d("DDL", "Decoded auth params - ts: $authTs, rnd: $authRnd, sig: ${authSig.take(20)}...")
//
//            // Step 4: Make the authentication request
//            val authUrl = "https://top2new.newkso.ru/auth.php?channel_id=$channelKey&ts=$authTs&rnd=$authRnd&sig=${URLEncoder.encode(authSig, "UTF-8")}"
//            Log.d("DDL", "Auth URL: $authUrl")
//            val authRequest = Request.Builder()
//                .url(authUrl)
//                .addHeader("User-Agent", userAgent)
//                .addHeader("Referer", "$serverUrl/")
//                .addHeader("Origin", serverUrl)
//                .build()
//            val authResponse = client.newCall(authRequest).execute()
//            Log.d("DDL", "Auth response code: ${authResponse.code}")
//            if (authResponse.code == 403) {
//                Log.e("DDL", "Auth request failed with 403 Forbidden")
//                authResponse.close()
//                return null
//            }
//            authResponse.close()
//
//            // Step 5: Make the server lookup request
//            val serverLookupUrl = "$serverUrl/server_lookup.php?channel_id=$channelKey"
//            Log.d("DDL", "Server lookup URL: $serverLookupUrl")
//            val serverKeyRequest = Request.Builder()
//                .url(serverLookupUrl)
//                .addHeader("User-Agent", userAgent)
//                .addHeader("Referer", "$serverUrl/")
//                .addHeader("Origin", serverUrl)
//                .build()
//            val serverKeyResponse = client.newCall(serverKeyRequest).execute()
//            val serverKeyJson = serverKeyResponse.body?.string() ?: run {
//                Log.e("DDL", "Empty response from server lookup")
//                serverKeyResponse.close()
//                return null
//            }
//            Log.d("DDL", "Server lookup response code: ${serverKeyResponse.code}, content: $serverKeyJson")
//            serverKeyResponse.close()
//
//            val serverData = try {
//                json.decodeFromString<ServerKeyResponse>(serverKeyJson)
//            } catch (e: Exception) {
//                Log.e("DDL", "Failed to parse server key JSON: ${e.message}")
//                return null
//            }
//            val serverKey = serverData.serverKey
//            Log.d("DDL", "Extracted server key: $serverKey")
//
//            // Step 6: Construct the final M3U8 URL
//            val finalUrl = if (serverKey == "top1/cdn") {
//                "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
//            } else {
//                "https://$serverKey.newkso.ru/$serverKey/$channelKey/mono.m3u8"
//            }
//            Log.d("DDL", "Final URL constructed: $finalUrl")
//            return finalUrl
//        } catch (e: Exception) {
//            Log.e("DDL", "Exception in extractFinalUrl: ${e.message}")
//            e.printStackTrace()
//            return null
//        }
//    }

    private fun base64Decode(encoded: String): String {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e("DDL", "Base64 decode failed: ${e.message}")
            ""
        }
    }
}

//package com.faselhd.app.network.sources
//
//import android.content.Context
//import com.faselhd.app.models.DaddyLiveChannel
//import com.faselhd.app.models.SLiveTv
//import com.faselhd.app.models.Video
//import com.faselhd.app.network.AnimeSource
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.json.Json
//import okhttp3.FormBody
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import java.net.URLEncoder
//import java.util.concurrent.TimeUnit
//import java.util.regex.Pattern
//import android.util.Base64
//import java.security.SecureRandom
//import java.security.cert.X509Certificate
//import javax.net.ssl.SSLContext
//import javax.net.ssl.TrustManager
//import javax.net.ssl.X509TrustManager
//import android.webkit.* // Required for WebView
//import kotlinx.coroutines.suspendCancellableCoroutine
//import kotlin.coroutines.resume
//
//import kotlinx.coroutines.withContext
//import kotlinx.serialization.SerialName
//import kotlinx.serialization.Serializable
//
//import java.net.URL
//
//
//@Serializable
//data class ServerKeyResponse(
//    @SerialName("server_key") val serverKey: String
//)
//
//// Data class for parsing the Base64-encoded BUNDLE
//@Serializable
//data class Bundle(
//    @SerialName("b_host") val bHost: String,
//    @SerialName("b_rnd") val bRnd: String,
//    @SerialName("b_script") val bScript: String,
//    @SerialName("b_sig") val bSig: String,
//    @SerialName("b_ts") val bTs: String
//)
//
//class DaddyLiveSource(private val context: Context) {
//
//    val trustAllCerts = arrayOf<TrustManager>(
//        object : X509TrustManager {
//            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
//            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
//            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
//        }
//    )
//
//    val sslContext = SSLContext.getInstance("SSL").apply {
//        init(null, trustAllCerts, SecureRandom())
//    }
//
//    private val client: OkHttpClient by lazy {
//        OkHttpClient.Builder()
//            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
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
//    private val baseUrl = "https://daddylive.dad"
//    private var cachedChannels: List<DaddyLiveChannel> = emptyList()
//    private val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
//
//    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
//
//    private val countries = listOf(
//        "Andorra", "UAE", "Afghanistan", "Antigua and Barbuda", "Anguilla", "Albania", "Armenia", "Angola",
//        "Antarctica", "Argentina", "American Samoa", "Austria", "Australia", "Aruba", "Aland", "Azerbaijan",
//        "Bosnia and Herzegovina", "Barbados", "Bangladesh", "Belgium", "Burkina Faso", "Bulgaria", "Bahrain",
//        "Burundi", "Benin", "Saint Barthelemy", "Bermuda", "Brunei", "Bolivia", "Bonaire Sint Eustatius and Saba",
//        "Brasil", "Bahamas", "Bhutan", "Bouvet Island", "Botswana", "Belarus", "Belize", "Canada",
//        "Cocos Keeling Islands", "DR Congo", "Central African Republic", "Congo Republic", "Switzerland",
//        "Ivory Coast", "Cook Islands", "Chile", "Cameroon", "China", "Colombia", "Costa Rica", "Cuba",
//        "Cabo Verde", "Curacao", "Christmas Island", "Cyprus", "Czechia", "Germany", "Djibouti", "Denmark",
//        "Dominica", "Dominican Republic", "Algeria", "Ecuador", "Estonia", "Egypt", "Western Sahara", "Eritrea",
//        "Spain", "Ethiopia", "Finland", "Fiji", "Falkland Islands", "Micronesia", "Faroe Islands", "France",
//        "Gabon", "UK", "Grenada", "Georgia", "French Guiana", "Guernsey", "Ghana", "Gibraltar", "Greenland",
//        "The Gambia", "Guinea", "Guadeloupe", "Equatorial Guinea", "Greece", "South Georgia and South Sandwich Islands",
//        "Guatemala", "Guam", "Guinea-Bissau", "Guyana", "Hong Kong", "Heard and McDonald Islands", "Honduras",
//        "Croatia", "Haiti", "Hungary", "Indonesia", "Ireland", "Israel", "Isle of Man", "India",
//        "British Indian Ocean Territory", "Iraq", "Iran", "Iceland", "Italy", "Jersey", "Jamaica", "Jordan",
//        "Japan", "Kenya", "Kyrgyzstan", "Cambodia", "Kiribati", "Comoros", "St Kitts and Nevis", "North Korea",
//        "South Korea", "Kuwait", "Cayman Islands", "Kazakhstan", "Laos", "Lebanon", "Saint Lucia", "Liechtenstein",
//        "Sri Lanka", "Liberia", "Lesotho", "Lithuania", "Luxembourg", "Latvia", "Libya", "Morocco", "Monaco",
//        "Moldova", "Montenegro", "Saint Martin", "Madagascar", "Marshall Islands", "North Macedonia", "Mali",
//        "Myanmar", "Mongolia", "Macao", "Northern Mariana Islands", "Martinique", "Mauritania", "Montserrat",
//        "Malta", "Mauritius", "Maldives", "Malawi", "Mexico", "Malaysia", "Mozambique", "Namibia", "New Caledonia",
//        "Niger", "Norfolk Island", "Nigeria", "Nicaragua", "The Netherlands", "Norway", "Nepal", "Nauru", "Niue",
//        "New Zealand", "Oman", "Panama", "Peru", "French Polynesia", "Papua New Guinea", "Philippines", "Pakistan",
//        "Poland", "Saint Pierre and Miquelon", "Pitcairn Islands", "Puerto Rico", "Palestine", "Portugal", "Palau",
//        "Paraguay", "Qatar", "Reunion", "Romania", "Serbia", "Russia", "Rwanda", "Saudi Arabia", "Solomon Islands",
//        "Seychelles", "Sudan", "Sweden", "Singapore", "Saint Helena", "Slovenia", "Svalbard and Jan Mayen",
//        "Slovakia", "Sierra Leone", "San Marino", "Senegal", "Somalia", "Suriname", "South Sudan",
//        "Sao Tome and Principe", "El Salvador", "Sint Maarten", "Syria", "Eswatini", "Turks and Caicos Islands",
//        "Chad", "French Southern Territories", "Togo", "Thailand", "Tajikistan", "Tokelau", "Timor-Leste",
//        "Turkmenistan", "Tunisia", "Tonga", "Turkey", "Trinidad and Tobago", "Tuvalu", "Taiwan", "Tanzania",
//        "Ukraine", "Uganda", "U.S. Outlying Islands", "USA", "Uruguay", "Uzbekistan", "Vatican City",
//        "St Vincent and Grenadines", "Venezuela", "British Virgin Islands", "U.S. Virgin Islands", "Vietnam",
//        "Vanuatu", "Wallis and Futuna", "Samoa", "Kosovo", "Yemen", "Mayotte", "South Africa", "Zambia", "Zimbabwe"
//    )
//
//    // Fetches and caches all channels from DaddyLive
//    private suspend fun getAllChannels(): List<DaddyLiveChannel> {
//        if (cachedChannels.isNotEmpty()) {
//            return cachedChannels
//        }
//
//        return try {
//            val channelsUrl = "$baseUrl/24-7-channels.php"
//            val requestBody = FormBody.Builder().build()
//            val request = Request.Builder()
//                .url(channelsUrl)
//                .post(requestBody)
//                .addHeader("Referer", baseUrl)
//                .addHeader("User-Agent", userAgent)
//                .build()
//
//            val response = client.newCall(request).execute()
//            val responseBody = response.body!!.string()
//
//            // Parse the HTML response using regex patterns similar to the CloudStream implementation
//            val chBlockPattern = Pattern.compile("<center><h1(.+?)tab-2", Pattern.DOTALL or Pattern.MULTILINE)
//            val chBlockMatcher = chBlockPattern.matcher(responseBody)
//            val chBlock = if (chBlockMatcher.find()) chBlockMatcher.group(1) else ""
//
//            val chanDataPattern = Pattern.compile("href=\"(.*)\" target(.*)<strong>(.*)</strong>")
//            val chanDataMatcher = chanDataPattern.matcher(chBlock)
//            val channels = mutableListOf<DaddyLiveChannel>()
//
//            while (chanDataMatcher.find()) {
//                val href = chanDataMatcher.group(1) ?: continue
//                val strongText = chanDataMatcher.group(3) ?: continue
//
//                // Determine country from channel name
//                val country = determineCountry(strongText)
//
//                channels.add(
//                    DaddyLiveChannel(
//                        name = strongText,
//                        url = href,
//                        country = country
//                    )
//                )
//            }
//
//            cachedChannels = channels
//            channels
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//    }
//
//    private fun determineCountry(channelName: String): String {
//        val lastPart = channelName.substringAfterLast(" ").replace(")", "").trim()
//        return countries.find { country ->
//            country.lowercase() in lastPart.lowercase()
//        } ?: "International"
//    }
//
//    // Fetches all channels and groups them by country for the main screen
//    suspend fun fetchAllChannelsByCountry(): Map<String, List<SLiveTv>> = withContext(Dispatchers.IO) {
//        val channels = getAllChannels()
//        return@withContext channels
//            .map { channelToSLiveTv(it) }
//            .groupBy { it.country ?: "International" }
//            .toSortedMap() // Sort countries alphabetically
//    }
//
//    // Searches for channels based on a query
//    suspend fun search(query: String): List<SLiveTv> = withContext(Dispatchers.IO) {
//        val channels = getAllChannels()
//        return@withContext channels
//            .filter { it.name.contains(query, ignoreCase = true) }
//            .map { channelToSLiveTv(it) }
//    }
//
//    // Converts the DaddyLive Channel model to your app's UI model
//    private fun channelToSLiveTv(channel: DaddyLiveChannel): SLiveTv {
//        return SLiveTv().apply {
//            title = channel.name
//            // Store the channel data as JSON in the URL for easy access later
//            url = json.encodeToString(DaddyLiveChannel.serializer(), channel)
//            posterUrl = this@DaddyLiveSource.posterUrl
//            country = channel.country
//            source = AnimeSource.DADDY_LIVE.name
//        }
//    }
//
//    // Gets the final M3U8 link for a selected channel
//    // Add these as class-level properties to manage the timeout safely on the Main thread
//    // Add these as class-level properties to manage the timeout safely on the Main thread
////    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
////    private var timeoutRunnable: Runnable? = null
////
////    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.Main) {
////        // WebView must be created and used on the Main thread in Android.
////        println("DEBUG: fetchLiveStreamLink started for: ${channelJson.take(100)}...")
////        try {
////            val channel = json.decodeFromString<DaddyLiveChannel>(channelJson)
////            val channelUrl = "$baseUrl${channel.url}"
////            println("DEBUG: Parsed channel - name: ${channel.name}, url: $channelUrl")
////
////            return@withContext suspendCancellableCoroutine<Video?> { continuation ->
////                val webView = WebView(context)
////
////                // Clean up any previous timeout handlers to be safe
////                timeoutRunnable?.let { handler.removeCallbacks(it) }
////
////                webView.settings.javaScriptEnabled = true
////                webView.settings.domStorageEnabled = true
////                webView.settings.userAgentString = userAgent
////
////                webView.webViewClient = object : WebViewClient() {
////                    private var found = false
////                    private var currentIframeUrl: String? = null
////
////                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
////                        val requestUrl = request?.url?.toString() ?: ""
////
////                        if (!found && requestUrl.contains(".m3u8")) {
////                            found = true // Set flag immediately
////                            println("✅ SUCCESS: Found M3U8 URL -> $requestUrl")
////
////                            // *** OPTIMIZATION 1: Immediately cancel the timeout ***
////                            timeoutRunnable?.let { handler.removeCallbacks(it) }
////
////                            val iframeUrl = currentIframeUrl ?: requestUrl
////                            val parsedUrl = URL(iframeUrl)
////                            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
////
////                            val video = Video(
////                                url = requestUrl,
////                                quality = "Live",
////                                videoUrl = requestUrl,
////                                headers = mapOf(
////                                    "Referer" to "$refererBase/",
////                                    "User-Agent" to userAgent,
////                                    "Origin" to "refererBase"
////                                )
////                            )
////
////                            // *** OPTIMIZATION 2: Return the result and destroy the WebView immediately ***
////                            if (continuation.isActive) {
////                                continuation.resume(video)
////                            }
////                            destroyWebView(webView) // Clean up right away
////                        }
////                        return super.shouldInterceptRequest(view, request)
////                    }
////
////                    override fun onPageFinished(view: WebView?, url: String?) {
////                        super.onPageFinished(view, url)
////                        println("DEBUG: WebView finished loading page: $url")
////
////                        if (url != null && url.contains("jxoxkplay.xyz")) {
////                            currentIframeUrl = url
////                        }
////
////                        // Set up the timeout runnable as a safety net
////                        timeoutRunnable = Runnable {
////                            if (!found && continuation.isActive) {
////                                println("DEBUG: Timeout reached. M3U8 URL not found.")
////                                continuation.resume(null)
////                                destroyWebView(view)
////                            }
////                        }
////                        handler.postDelayed(timeoutRunnable!!, 20000) // 20-second safety timeout
////                    }
////                }
////
////                println("DEBUG: Loading URL into WebView: $channelUrl")
////                webView.loadUrl(channelUrl)
////
////                continuation.invokeOnCancellation {
////                    destroyWebView(webView)
////                }
////            }
////        } catch (e: Exception) {
////            println("DEBUG: Exception in fetchLiveStreamLink: ${e.message}")
////            e.printStackTrace()
////            return@withContext null
////        }
////    }
////
////    private fun destroyWebView(webView: WebView?) {
////        // Ensure all WebView operations are on the Main thread
////        handler.post {
////            println("DEBUG: Destroying WebView.")
////            timeoutRunnable?.let { handler.removeCallbacks(it) }
////            timeoutRunnable = null
////
////            webView?.stopLoading()
////            webView?.parent?.let { parent ->
////                (parent as android.view.ViewGroup).removeView(webView)
////            }
////            webView?.destroy()
////        }
////    }
//    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
//    private var timeoutRunnable: Runnable? = null
//
//    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.Main) {
//        // WebView must be created and used on the Main thread in Android.
//        println("DEBUG: fetchLiveStreamLink started for: ${channelJson.take(100)}...")
//        try {
//            val channel = json.decodeFromString<DaddyLiveChannel>(channelJson)
//            val channelUrl = "$baseUrl${channel.url}"
//            println("DEBUG: Parsed channel - name: ${channel.name}, url: $channelUrl")
//
//            return@withContext suspendCancellableCoroutine<Video?> { continuation ->
//                val webView = WebView(context)
//
//                webView.settings.javaScriptEnabled = true
//                webView.settings.domStorageEnabled = true
//                webView.settings.userAgentString = userAgent
//
//                webView.webViewClient = object : WebViewClient() {
//                    private var found = false
//                    private var currentIframeUrl: String? = null
//
//                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
//                        val requestUrl = request?.url?.toString() ?: ""
//
//                        if (!found && requestUrl.contains(".m3u8")) {
//                            found = true // Set flag immediately to prevent race conditions
//                            println("✅ SUCCESS: Found M3U8 URL -> $requestUrl")
//
//                            // Cancel the timeout immediately since we have succeeded
//                            timeoutRunnable?.let { handler.removeCallbacks(it) }
//
//                            val iframeUrl = currentIframeUrl ?: requestUrl // Fallback if onPageFinished hasn't fired
//                            val parsedUrl = URL(iframeUrl)
//                            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
//
//                            val video = Video(
//                                url = requestUrl,
//                                quality = "Live",
//                                videoUrl = requestUrl,
//                                headers = mapOf(
//                                    "Referer" to "$refererBase/",
//                                    "User-Agent" to userAgent,
//                                    "Origin" to refererBase
//                                )
//                            )
//
//                            if (continuation.isActive) {
//                                continuation.resume(video)
//                            }
//                            // Clean up immediately
//                            destroyWebView(webView)
//                        }
//                        return super.shouldInterceptRequest(view, request)
//                    }
//
//                    override fun onPageFinished(view: WebView?, url: String?) {
//                        super.onPageFinished(view, url)
//                        println("DEBUG: WebView finished loading page: $url")
//
//                        if (url != null && url.contains("jxoxkplay.xyz")) {
//                            currentIframeUrl = url
//                        }
//
//                        // Only set the timeout if we haven't already found the URL.
//                        if (!found) {
//                            timeoutRunnable = Runnable {
//                                if (continuation.isActive) {
//                                    println("DEBUG: Timeout reached. M3U8 URL not found.")
//                                    continuation.resume(null)
//                                    destroyWebView(view)
//                                }
//                            }
//                            handler.postDelayed(timeoutRunnable!!, 15000) // 15-second timeout
//                        }
//                    }
//                }
//
//                println("DEBUG: Loading URL into WebView: $channelUrl")
//                webView.loadUrl(channelUrl)
//
//                continuation.invokeOnCancellation {
//                    destroyWebView(webView)
//                }
//            }
//        } catch (e: Exception) {
//            println("DEBUG: Exception in fetchLiveStreamLink: ${e.message}")
//            e.printStackTrace()
//            return@withContext null
//        }
//    }
//
//    private fun destroyWebView(webView: WebView?) {
//        // Ensure all WebView operations are on the Main thread
//        handler.post {
//            println("DEBUG: Destroying WebView.")
//            timeoutRunnable?.let { handler.removeCallbacks(it) }
//            timeoutRunnable = null
//
//            webView?.stopLoading()
//            webView?.parent?.let { parent ->
//                (parent as android.view.ViewGroup).removeView(webView)
//            }
//            webView?.destroy()
//        }
//    }
//
//
//
////    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
////        println("DEBUG: fetchLiveStreamLink started with channelJson: ${channelJson.take(100)}...")
////
////        try {
////            val channel = json.decodeFromString<DaddyLiveChannel>(channelJson)
////            val channelUrl = "$baseUrl${channel.url}"
////            println("DEBUG: Parsed channel - name: ${channel.name}, url: $channelUrl")
////
////            val request = Request.Builder().url(channelUrl).header("User-Agent", userAgent).build()
////            println("DEBUG: Making request to: $channelUrl")
////
////            val response = client.newCall(request).execute()
////            val pageContent = response.body?.string() ?: run {
////                println("DEBUG: Empty response body from channel page")
////                return@withContext null
////            }
////
////            println("DEBUG: Channel page response code: ${response.code}, content length: ${pageContent.length}")
////
////            val iframePattern = Pattern.compile("<iframe[^>]*src=\"([^\"]+)\"")
////            val iframeMatcher = iframePattern.matcher(pageContent)
////            if (!iframeMatcher.find()) {
////                println("DEBUG: No iframe found in page content")
////                return@withContext null
////            }
////
////            val iframeSrc = iframeMatcher.group(1) ?: run {
////                println("DEBUG: Iframe src is null")
////                return@withContext null
////            }
////            println("DEBUG: Found iframe src: $iframeSrc")
////
////            val finalUrl = extractFinalUrl(iframeSrc, channelUrl) ?: run {
////                println("DEBUG: extractFinalUrl returned null")
////                return@withContext null
////            }
////            println("DEBUG: Extracted final URL: $finalUrl")
////
////            val parsedUrl = URL(iframeSrc)
////            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
////
////            println("DEBUG: Successfully created Video object with referer: $refererBase")
////            return@withContext Video(
////                url = finalUrl,
////                quality = "Live",
////                videoUrl = finalUrl,
////                headers = mapOf(
////                    "Referer" to "$refererBase/",
////                    "User-Agent" to userAgent,
////                    "Origin" to refererBase
////                )
////            )
////        } catch (e: Exception) {
////            println("DEBUG: Exception in fetchLiveStreamLink: ${e.message}")
////            e.printStackTrace()
////            return@withContext null
////        }
////    }
//
//    // ### CORRECTED EXTRACTION LOGIC ###
//    private suspend fun extractFinalUrl(iframeSrc: String, referer: String): String? {
//        println("DEBUG: extractFinalUrl started with iframeSrc: $iframeSrc, referer: $referer")
//
//        try {
//            // Step 1: Get the iframe page content
//            println("DEBUG: Step 1 - Fetching iframe content from: $iframeSrc")
//            val iframeRequest = Request.Builder()
//                .url(iframeSrc)
//                .header("User-Agent", userAgent)
//                .header("Referer", referer)
//                .build()
//
//            val iframeResponse = client.newCall(iframeRequest).execute()
//            val scriptContent = iframeResponse.body?.string() ?: run {
//                println("DEBUG: Empty response body from iframe")
//                return null
//            }
//
//            println("DEBUG: Iframe response code: ${iframeResponse.code}, content length: ${scriptContent.length}")
//
//            val serverUrl = iframeSrc.substringBefore("/premiumtv")
//            println("DEBUG: Extracted server URL: $serverUrl")
//
//            // Step 2: Extract BUNDLE and CHANNEL_KEY from the script
//            println("DEBUG: Step 2 - Extracting BUNDLE and CHANNEL_KEY")
//            val bundle = Regex("""const BUNDLE = "([^"]+)""").find(scriptContent)?.groupValues?.get(1)
//            val channelKey = Regex("""const CHANNEL_KEY = "([^"]+)""").find(scriptContent)?.groupValues?.get(1)
//
//            println("DEBUG: BUNDLE found: ${bundle != null}, CHANNEL_KEY found: ${channelKey != null}")
//            if (bundle == null || channelKey == null) {
//                println("DEBUG: Missing BUNDLE or CHANNEL_KEY in script content")
//                return null
//            }
//
//            println("DEBUG: CHANNEL_KEY: $channelKey")
//            println("DEBUG: BUNDLE (first 50 chars): ${bundle.take(50)}...")
//
//            // Step 3: Decode the bundle
//            println("DEBUG: Step 3 - Decoding bundle")
//            val bundleJson = String(Base64.decode(bundle, Base64.DEFAULT))
//            println("DEBUG: Decoded bundle JSON: $bundleJson")
//
//            val bundleObj = json.decodeFromString<Bundle>(bundleJson)
//
//            val authTs = String(Base64.decode(bundleObj.bTs, Base64.DEFAULT))
//            val authRnd = String(Base64.decode(bundleObj.bRnd, Base64.DEFAULT))
//            val authSig = String(Base64.decode(bundleObj.bSig, Base64.DEFAULT))
//
//            println("DEBUG: Decoded auth params - ts: $authTs, rnd: $authRnd, sig: ${authSig.take(20)}...")
//
//            // Step 4: Make the authentication request
//            println("DEBUG: Step 4 - Making authentication request")
//            val authUrl = "https://top2new.newkso.ru/auth.php?channel_id=$channelKey&ts=$authTs&rnd=$authRnd&sig=${URLEncoder.encode(authSig, "UTF-8")}"
//            println("DEBUG: Auth URL: $authUrl")
//
//            val authRequest = Request.Builder()
//                .url(authUrl)
//                .header("User-Agent", userAgent)
//                .header("Referer", "$serverUrl/")
//                .header("Origin", serverUrl)
//                .build()
//
//            val authResponse = client.newCall(authRequest).execute()
//            println("DEBUG: Auth response code: ${authResponse.code}")
//            authResponse.close() // We don't need the response, just to execute it
//
//            // Step 5: Make the server lookup request
//            println("DEBUG: Step 5 - Making server lookup request")
//            val serverLookupUrl = "$serverUrl/server_lookup.php?channel_id=$channelKey"
//            println("DEBUG: Server lookup URL: $serverLookupUrl")
//
//            val serverKeyRequest = Request.Builder().url(serverLookupUrl).build()
//            val serverKeyResponse = client.newCall(serverKeyRequest).execute()
//            val serverKeyJson = serverKeyResponse.body?.string() ?: run {
//                println("DEBUG: Empty response from server lookup")
//                return null
//            }
//
//            println("DEBUG: Server lookup response code: ${serverKeyResponse.code}, content: $serverKeyJson")
//
//            val serverData = json.decodeFromString<ServerKeyResponse>(serverKeyJson)
//            val serverKey = serverData.serverKey
//            println("DEBUG: Extracted server key: $serverKey")
//
//            // Step 6: Construct the final M3U8 URL
//            println("DEBUG: Step 6 - Constructing final M3U8 URL")
//            val finalUrl = when (serverKey) {
//                "top1/cdn" -> "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
//                else -> "https://${serverKey}new.newkso.ru/$serverKey/$channelKey/mono.m3u8"
//            }
//
//            println("DEBUG: Final URL constructed: $finalUrl")
//            return finalUrl
//
//        } catch (e: Exception) {
//            println("DEBUG: Exception in extractFinalUrl: ${e.message}")
//            e.printStackTrace()
//            return null
//        }
//    }
//
//
//
//    private fun extractServerKey(json: String): String {
//        // Simple JSON parsing for serverKey
//        return try {
//            val startIndex = json.indexOf("\"serverKey\":\"") + 13
//            val endIndex = json.indexOf("\"", startIndex)
//            json.substring(startIndex, endIndex)
//        } catch (e: Exception) {
//            "top1/cdn" // fallback
//        }
//    }
//}