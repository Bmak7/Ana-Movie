package com.faselhd.app.network.sources

import android.content.Context
import com.faselhd.app.models.DaddyLiveChannel
import com.faselhd.app.models.SLiveTv
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import android.util.Base64

class DaddyLiveSource(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl = "https://daddylive.dad"
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
            val responseBody = response.body!!.string()

            // Parse the HTML response using regex patterns similar to the CloudStream implementation
            val chBlockPattern = Pattern.compile("<center><h1(.+?)tab-2", Pattern.DOTALL or Pattern.MULTILINE)
            val chBlockMatcher = chBlockPattern.matcher(responseBody)
            val chBlock = if (chBlockMatcher.find()) chBlockMatcher.group(1) else ""

            val chanDataPattern = Pattern.compile("href=\"(.*)\" target(.*)<strong>(.*)</strong>")
            val chanDataMatcher = chanDataPattern.matcher(chBlock)
            val channels = mutableListOf<DaddyLiveChannel>()

            while (chanDataMatcher.find()) {
                val href = chanDataMatcher.group(1) ?: continue
                val strongText = chanDataMatcher.group(3) ?: continue

                // Determine country from channel name
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

    // Fetches all channels and groups them by country for the main screen
    suspend fun fetchAllChannelsByCountry(): Map<String, List<SLiveTv>> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .map { channelToSLiveTv(it) }
            .groupBy { it.country ?: "International" }
            .toSortedMap() // Sort countries alphabetically
    }

    // Searches for channels based on a query
    suspend fun search(query: String): List<SLiveTv> = withContext(Dispatchers.IO) {
        val channels = getAllChannels()
        return@withContext channels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { channelToSLiveTv(it) }
    }

    // Converts the DaddyLive Channel model to your app's UI model
    private fun channelToSLiveTv(channel: DaddyLiveChannel): SLiveTv {
        return SLiveTv().apply {
            title = channel.name
            // Store the channel data as JSON in the URL for easy access later
            url = json.encodeToString(DaddyLiveChannel.serializer(), channel)
            posterUrl = this@DaddyLiveSource.posterUrl
            country = channel.country
            source = AnimeSource.DADDY_LIVE.name
        }
    }

    // Gets the final M3U8 link for a selected channel
    suspend fun fetchLiveStreamLink(channelJson: String): Video? = withContext(Dispatchers.IO) {
        try {
            val channel = json.decodeFromString<DaddyLiveChannel>(channelJson)
            val channelUrl = "$baseUrl${channel.url}"

            // Get the channel page
            val request = Request.Builder()
                .url(channelUrl)
                .addHeader("Referer", baseUrl)
                .addHeader("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            val pageContent = response.body!!.string()

            // Extract iframe source
            val iframePattern = Pattern.compile("src=\"([^\"]+)\"")
            val iframeMatcher = iframePattern.matcher(pageContent)

            if (!iframeMatcher.find()) return@withContext null

            val iframeSrc = iframeMatcher.group(1) ?: return@withContext null

            // Extract the final M3U8 URL
            val finalUrl = extractFinalUrl(iframeSrc) ?: return@withContext null
            println("channelKey: djkd f: $finalUrl")
            return@withContext Video(
                url = finalUrl,
                quality = "Live",
                videoUrl = finalUrl,
                headers = mapOf(
                    "Referer" to "$baseUrl/",
                    "User-Agent" to userAgent,
                    "Origin" to baseUrl
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun extractFinalUrl(iframeSrc: String): String? {
        try {
            // Get the iframe page
            val request = Request.Builder()
                .url(iframeSrc)
                .addHeader("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            val page = response.body!!.string()

            // Extract security values using regex patterns
            val channelKeyRegex = "(?<=var channelKey = \").*(?=\")".toRegex()
            val authTsRegex = "(?<=var __c = atob\\.\").*(?=\")".toRegex()
            val authRndRegex = "(?<=var __d = atob\\.\").*(?=\")".toRegex()
            val authSigRegex = "(?<=var __e = atob\\.\").*(?=\")".toRegex()

            val channelKey = channelKeyRegex.find(page)?.value ?: return null
            val authTs = String(Base64.decode(authTsRegex.find(page)?.value ?: return null, Base64.DEFAULT))
            val authRnd = String(Base64.decode(authRndRegex.find(page)?.value ?: return null, Base64.DEFAULT))
            val authSig = String(Base64.decode(authSigRegex.find(page)?.value ?: return null, Base64.DEFAULT))

            // Get server URL from iframe
            val serverUrl = iframeSrc.substringBefore("/stream")

            // Authenticate
            val authUrl = "https://top2new.newkso.ru/auth.php?channel_id=$channelKey" +
                    "&ts=$authTs" +
                    "&rnd=$authRnd" +
                    "&sig=${URLEncoder.encode(authSig, "UTF-8")}"

            val authRequest = Request.Builder()
                .url(authUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", "$serverUrl/")
                .addHeader("Origin", serverUrl)
                .build()

            client.newCall(authRequest).execute()

            // Get server key
            val serverKeyRequest = Request.Builder()
                .url("$serverUrl/server_lookup.php?channel_id=$channelKey")
                .build()

            val serverKeyResponse = client.newCall(serverKeyRequest).execute()
            val serverKeyJson = serverKeyResponse.body!!.string()

            // Parse server key (you might need to create a data class for this)
            val serverKey = extractServerKey(serverKeyJson)

            // Build M3U8 URL https://top1.newkso.ru/top1/cdn/${CHANNEL_KEY}/mono.m3u8`
            val m3u8 = when (serverKey) {
                "top1/cdn" -> "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                else -> "https://${serverKey}new.newkso.ru/$serverKey/$channelKey/mono.m3u8"
            }

            return m3u8
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun extractServerKey(json: String): String {
        // Simple JSON parsing for serverKey
        return try {
            val startIndex = json.indexOf("\"serverKey\":\"") + 13
            val endIndex = json.indexOf("\"", startIndex)
            json.substring(startIndex, endIndex)
        } catch (e: Exception) {
            "top1/cdn" // fallback
        }
    }
}