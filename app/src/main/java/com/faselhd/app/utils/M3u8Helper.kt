package com.faselhd.app.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * M3u8Helper - Handles HLS (HTTP Live Streaming) playlist parsing and downloading
 * Supports AES-128 encryption and custom headers for authentication
 */
object M3u8Helper {
    private const val TAG = "M3u8Helper"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val BASE_URL = "https://www.faselhds.xyz"

    // Regex patterns for parsing M3U8 playlists
    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")
    private val RESOLUTION_REGEX = Regex("#EXT-X-STREAM-INF:.*RESOLUTION=(\\d+x\\d+)")

    /**
     * Create OkHttpClient with custom headers and proper configuration
     */
    private fun createClient(customHeaders: Map<String, String> = emptyMap()): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .header("User-Agent", USER_AGENT)

                // Add custom headers (e.g., Authorization, Origin, etc.)
                customHeaders.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                // Add default Referer if not present in custom headers
                if (!customHeaders.containsKey("Referer") && !customHeaders.containsKey("referer")) {
                    requestBuilder.header("Referer", BASE_URL)
                }

                val newRequest = requestBuilder.build()

                Log.d(TAG, "Request URL: ${newRequest.url}")
                Log.d(TAG, "Request Headers: ${newRequest.headers}")

                chain.proceed(newRequest)
            }
            .build()
    }

    /**
     * Get parent URL from a given URI (removes last path segment)
     */
    private fun getParentLink(uri: String): String {
        val split = uri.split("/").toMutableList()
        if (split.isEmpty()) return ""
        split.removeAt(split.lastIndex)
        return split.joinToString("/")
    }

    /**
     * Check if URL is relative (doesn't start with http:// or https://)
     */
    private fun isNotCompleteUrl(url: String): Boolean {
        return !url.startsWith("https://") && !url.startsWith("http://")
    }

    /**
     * Convert ULong to 16-byte array (Big Endian) for IV generation
     */
    private fun toBytes16Big(n: ULong): ByteArray {
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
        }
        return bytes
    }

    /**
     * Generate default IV from sequence number
     */
    private fun defaultIv(sequence: Int): ByteArray {
        return toBytes16Big(sequence.toULong())
    }

    /**
     * Decrypt AES-128 encrypted segment data
     */
    private fun getDecrypted(
        secretKey: ByteArray,
        data: ByteArray,
        iv: ByteArray,
        sequence: Int
    ): ByteArray {
        val ivKey = if (iv.isEmpty()) defaultIv(sequence) else iv
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val skSpec = SecretKeySpec(secretKey, "AES")
        val ivSpec = IvParameterSpec(ivKey)
        cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
        return cipher.doFinal(data)
    }

    /**
     * Data class to hold HLS download information
     */
    data class HlsDownloadData(
        val encryptionKey: ByteArray?,
        val encryptionIv: ByteArray?,
        val tsLinks: List<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HlsDownloadData

            if (encryptionKey != null) {
                if (other.encryptionKey == null) return false
                if (!encryptionKey.contentEquals(other.encryptionKey)) return false
            } else if (other.encryptionKey != null) return false
            if (encryptionIv != null) {
                if (other.encryptionIv == null) return false
                if (!encryptionIv.contentEquals(other.encryptionIv)) return false
            } else if (other.encryptionIv != null) return false
            if (tsLinks != other.tsLinks) return false

            return true
        }

        override fun hashCode(): Int {
            var result = encryptionKey?.contentHashCode() ?: 0
            result = 31 * result + (encryptionIv?.contentHashCode() ?: 0)
            result = 31 * result + tsLinks.hashCode()
            return result
        }
    }

    /**
     * Parse M3U8 playlist and extract segment URLs and encryption info
     *
     * @param playlistUrl URL of the M3U8 playlist
     * @param customHeaders Optional custom headers (auth tokens, referer, etc.)
     * @return HlsDownloadData containing encryption info and segment URLs
     * @throws IOException if playlist cannot be fetched or parsed
     */
    @Throws(IOException::class)
    fun HlsDownloadData(
        playlistUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ): HlsDownloadData {
        Log.d(TAG, "Parsing M3U8 playlist: $playlistUrl")

        val client = createClient(customHeaders)
        val request = Request.Builder().url(playlistUrl).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch playlist (HTTP ${response.code}: ${response.message})")
        }

        val playlistText = response.body?.string()
            ?: throw IOException("Playlist response body is null")

        Log.d(TAG, "Playlist fetched, size: ${playlistText.length} chars")

        val parentUrl = getParentLink(playlistUrl)
        Log.d(TAG, "Parent URL: $parentUrl")

        var encryptionKey: ByteArray? = null
        var encryptionIv: ByteArray? = null

        // Check for encryption key
        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let { groups ->
            val method = groups[1]
            var keyUri = groups[2]
            val ivHex = groups.getOrNull(3)

            Log.d(TAG, "Encryption detected - Method: $method, Key URI: $keyUri")

            // Convert relative URL to absolute
            if (isNotCompleteUrl(keyUri)) {
                keyUri = "$parentUrl/$keyUri"
                Log.d(TAG, "Converted key URI to: $keyUri")
            }

            // Fetch encryption key
            try {
                val keyRequest = Request.Builder().url(keyUri).build()
                val keyResponse = client.newCall(keyRequest).execute()
                if (keyResponse.isSuccessful) {
                    encryptionKey = keyResponse.body?.bytes()
                    Log.d(TAG, "Encryption key fetched successfully, size: ${encryptionKey?.size} bytes")
                } else {
                    Log.w(TAG, "Failed to fetch encryption key: ${keyResponse.code}")
                }
                keyResponse.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching encryption key", e)
            }

            // Parse IV if present
            if (!ivHex.isNullOrBlank()) {
                try {
                    // Remove "0x" prefix if present
                    val hexString = ivHex.removePrefix("0x").removePrefix("0X")
                    encryptionIv = hexString.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                    Log.d(TAG, "IV parsed successfully, size: ${encryptionIv?.size} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing IV", e)
                }
            }
        }

        // Extract segment URLs
        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
            var tsUrl = match.groupValues[1].trim()

            // Convert relative URL to absolute
            if (isNotCompleteUrl(tsUrl)) {
                tsUrl = "$parentUrl/$tsUrl"
            }

            tsUrl
        }.toList()

        if (tsLinks.isEmpty()) {
            throw IOException("No .ts segments found in playlist")
        }

        Log.d(TAG, "Found ${tsLinks.size} segments")
        Log.d(TAG, "First segment: ${tsLinks.firstOrNull()}")
        Log.d(TAG, "Last segment: ${tsLinks.lastOrNull()}")

        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
    }

    /**
     * Download a single segment (.ts file)
     *
     * @param segmentUrl URL of the segment to download
     * @param encryptionData Encryption data from HlsDownloadData (can be null)
     * @param sequence Segment sequence number (used for IV generation)
     * @param customHeaders Optional custom headers
     * @return Decrypted segment data as ByteArray
     * @throws IOException if segment cannot be downloaded
     */
    @Throws(IOException::class)
    fun downloadSegment(
        segmentUrl: String,
        encryptionData: HlsDownloadData?,
        sequence: Int,
        customHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        val client = createClient(customHeaders)
        val request = Request.Builder().url(segmentUrl).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to download segment $segmentUrl (HTTP ${response.code}: ${response.message})")
        }

        val tsData = response.body?.bytes()
            ?: throw IOException("Segment response body is null")

        if (tsData.isEmpty()) {
            throw IOException("Segment is empty")
        }

        response.close()

        // Decrypt if encrypted
        if (encryptionData?.encryptionKey != null) {
            try {
                return getDecrypted(
                    encryptionData.encryptionKey,
                    tsData,
                    encryptionData.encryptionIv ?: byteArrayOf(),
                    sequence
                )
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed for segment $sequence", e)
                throw IOException("Failed to decrypt segment", e)
            }
        }

        return tsData
    }

    /**
     * Check if a URL is likely an M3U8 playlist
     */
    fun isM3u8Url(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/hls/", ignoreCase = true) ||
                url.contains("playlist", ignoreCase = true) ||
                url.contains("master", ignoreCase = true)
    }

    /**
     * Parse master playlist to get available qualities
     * Returns map of quality label to playlist URL
     */
    @Throws(IOException::class)
    fun parseMasterPlaylist(
        masterUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        Log.d(TAG, "Parsing master playlist: $masterUrl")

        val client = createClient(customHeaders)
        val request = Request.Builder().url(masterUrl).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch master playlist (HTTP ${response.code})")
        }

        val playlistText = response.body?.string()
            ?: throw IOException("Master playlist response body is null")

        val parentUrl = getParentLink(masterUrl)
        val qualityMap = mutableMapOf<String, String>()

        val lines = playlistText.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // Extract resolution
                val resolution = RESOLUTION_REGEX.find(line)?.groupValues?.get(1) ?: "Unknown"

                // Next line should be the playlist URL
                if (i + 1 < lines.size) {
                    var playlistUrl = lines[i + 1].trim()
                    if (isNotCompleteUrl(playlistUrl)) {
                        playlistUrl = "$parentUrl/$playlistUrl"
                    }
                    qualityMap[resolution] = playlistUrl
                }
            }
        }

        Log.d(TAG, "Found ${qualityMap.size} quality options")
        return qualityMap
    }
}


//package com.faselhd.app.utils
//
//
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import java.io.IOException
//import javax.crypto.Cipher
//import javax.crypto.spec.IvParameterSpec
//import javax.crypto.spec.SecretKeySpec
//import kotlin.math.pow
//
//object M3u8Helper {
//    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
//    private const val BASE_URL = "https://www.faselhds.xyz"
//
//    // *** THIS IS THE CRITICAL FIX ***
//    // Configure the client to automatically add the required headers to every request
//    private val client = OkHttpClient.Builder()
//        .addInterceptor { chain ->
//            val originalRequest = chain.request()
//            val newRequest = originalRequest.newBuilder()
//                .header("User-Agent", USER_AGENT)
//                .header("Referer", BASE_URL)
//                .build()
//            chain.proceed(newRequest)
//        }
//        .build()
//
//    // The rest of the helper code remains the same, but we will simplify the function calls
//    // because headers are now handled automatically.
//
//    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
//    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")
//
//    private fun getParentLink(uri: String): String {
//        val split = uri.split("/").toMutableList()
//        if (split.isEmpty()) return ""
//        split.removeAt(split.lastIndex)
//        return split.joinToString("/")
//    }
//
//    private fun isNotCompleteUrl(url: String): Boolean {
//        return !url.startsWith("https://") && !url.startsWith("http://")
//    }
//
//    private fun toBytes16Big(n: ULong): ByteArray {
//        val bytes = ByteArray(16)
//        for (i in 0 until 16) {
//            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
//        }
//        return bytes
//    }
//
//    private fun defaultIv(sequence: Int): ByteArray {
//        return toBytes16Big(sequence.toULong())
//    }
//
//    private fun getDecrypted(secretKey: ByteArray, data: ByteArray, iv: ByteArray, sequence: Int): ByteArray {
//        val ivKey = if (iv.isEmpty()) defaultIv(sequence) else iv
//        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
//        val skSpec = SecretKeySpec(secretKey, "AES")
//        val ivSpec = IvParameterSpec(ivKey)
//        cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
//        return cipher.doFinal(data)
//    }
//
//    data class HlsDownloadData(
//        val encryptionKey: ByteArray?,
//        val encryptionIv: ByteArray?,
//        val tsLinks: List<String>
//    )
//
//    // Main function to parse the M3U8 playlist. NO LONGER NEEDS headers parameter.
//    @Throws(IOException::class)
//    fun HlsDownloadData(playlistUrl: String): HlsDownloadData {
//        val request = Request.Builder().url(playlistUrl).build() // The interceptor will add headers
//
//        val response = client.newCall(request).execute()
//        if (!response.isSuccessful) throw IOException("Failed to fetch playlist (Code: ${response.code})")
//
//        val playlistText = response.body!!.string()
//        val parentUrl = getParentLink(playlistUrl)
//
//        var encryptionKey: ByteArray? = null
//        var encryptionIv: ByteArray? = null
//
//        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let {
//            var keyUri = it[2]
//            if (isNotCompleteUrl(keyUri)) {
//                keyUri = "$parentUrl/$keyUri"
//            }
//            val keyRequest = Request.Builder().url(keyUri).build()
//            val keyResponse = client.newCall(keyRequest).execute()
//            if (keyResponse.isSuccessful) {
//                encryptionKey = keyResponse.body!!.bytes()
//            }
//            encryptionIv = it.getOrNull(3)?.toByteArray()
//        }
//
//        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
//            var tsUrl = match.groupValues[1].trim()
//            if (isNotCompleteUrl(tsUrl)) {
//                tsUrl = "$parentUrl/$tsUrl"
//            }
//            tsUrl
//        }.toList()
//
//        if (tsLinks.isEmpty()) throw IOException("No .ts segments found in playlist")
//
//        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
//    }
//
//    // Function to download a single .ts segment. NO LONGER NEEDS headers parameter.
//    @Throws(IOException::class)
//    fun downloadSegment(
//        segmentUrl: String,
//        encryptionData: HlsDownloadData?,
//        sequence: Int
//    ): ByteArray {
//        val request = Request.Builder().url(segmentUrl).build() // The interceptor will add headers
//        val response = client.newCall(request).execute()
//        if (!response.isSuccessful) throw IOException("Failed to download segment $segmentUrl (Code: ${response.code})")
//
//        val tsData = response.body!!.bytes()
//        if (tsData.isEmpty()) throw IOException("Segment is empty")
//
//        if (encryptionData?.encryptionKey != null) {
//            return getDecrypted(
//                encryptionData.encryptionKey,
//                tsData,
//                encryptionData.encryptionIv ?: byteArrayOf(),
//                sequence
//            )
//        }
//        return tsData
//    }
//}
//
////package com.faselhd.app.utils
////
////import com.faselhd.app.models.Video
////import okhttp3.Headers.Companion.toHeaders
////import okhttp3.OkHttpClient
////import okhttp3.Request
////import java.io.FileOutputStream
////import java.io.IOException
////import java.io.InputStream
////import java.net.URI
////import javax.crypto.Cipher
////import javax.crypto.CipherInputStream
////import javax.crypto.spec.IvParameterSpec
////import javax.crypto.spec.SecretKeySpec
////
////object M3u8Helper {
////
////    // A single, clean client with no interceptors. Headers will be added manually.
////    private val client = OkHttpClient()
////
////    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
////    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")
////
////    private fun getParentLink(uri: String): String {
////        return uri.substringBeforeLast('/')
////    }
////
////    private fun isNotCompleteUrl(url: String): Boolean {
////        return !url.startsWith("https://") && !url.startsWith("http://")
////    }
////
////    private fun toBytes16Big(n: ULong): ByteArray {
////        val bytes = ByteArray(16)
////        for (i in 0 until 16) {
////            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
////        }
////        return bytes
////    }
////
////    private fun defaultIv(sequence: Int): ByteArray {
////        return toBytes16Big(sequence.toULong())
////    }
////
////    data class HlsDownloadData(
////        val encryptionKey: ByteArray?,
////        val encryptionIv: ByteArray?,
////        val tsLinks: List<String>
////    )
////
////    // MODIFIED: This function now requires a headers map.
////    @Throws(IOException::class)
////    fun parseHlsData(playlistUrl: String, headers: Map<String, String>): HlsDownloadData {
////        // Build the request WITH the provided headers
////        val request = Request.Builder().url(playlistUrl).headers(headers.toHeaders()).build()
////        val response = client.newCall(request).execute()
////
////        if (!response.isSuccessful) {
////            // Close the body to fix the resource leak warning
////            response.body.close()
////            throw IOException("Failed to fetch playlist (Code: ${response.code})")
////        }
////
////        // Use the response body in a 'use' block to ensure it's always closed
////        val playlistText = response.body.use { it.string() }
////        val parentUrl = getParentLink(playlistUrl)
////
////        var encryptionKey: ByteArray? = null
////        var encryptionIv: ByteArray? = null
////
////        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let {
////            var keyUri = it[2]
////            if (isNotCompleteUrl(keyUri)) {
////                keyUri = "$parentUrl/$keyUri"
////            }
////            // Fetch the key using the same headers
////            val keyRequest = Request.Builder().url(keyUri).headers(headers.toHeaders()).build()
////            val keyResponse = client.newCall(keyRequest).execute()
////            if (keyResponse.isSuccessful) {
////                encryptionKey = keyResponse.body.use { body -> body.bytes() }
////            } else {
////                keyResponse.body.close() // Ensure close on failure
////            }
////            encryptionIv = it.getOrNull(3)?.toByteArray()
////        }
////
////        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
////            var tsUrl = match.groupValues[1].trim()
////            if (isNotCompleteUrl(tsUrl)) {
////                tsUrl = "$parentUrl/$tsUrl"
////            }
////            tsUrl
////        }.toList()
////
////        if (tsLinks.isEmpty()) throw IOException("No .ts segments found in playlist")
////
////        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
////    }
////
////    // MODIFIED: This function now requires a headers map.
////    @Throws(IOException::class)
////    fun downloadSegment(
////        segmentUrl: String,
////        encryptionData: HlsDownloadData?,
////        sequence: Int,
////        headers: Map<String, String>, // <-- Added headers parameter
////        outputStream: FileOutputStream
////    ) {
////        val request = Request.Builder().url(segmentUrl).headers(headers.toHeaders()).build()
////        val response = client.newCall(request).execute()
////
////        if (!response.isSuccessful) {
////            response.body.close()
////            throw IOException("Failed to download segment $segmentUrl (Code: ${response.code})")
////        }
////
////        // Use a 'use' block for the InputStream to ensure it's closed
////        response.body.byteStream().use { inputStream ->
////            if (encryptionData?.encryptionKey != null) {
////                val ivKey = if (encryptionData.encryptionIv == null || encryptionData.encryptionIv.isEmpty()) {
////                    defaultIv(sequence)
////                } else {
////                    encryptionData.encryptionIv
////                }
////                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
////                val skSpec = SecretKeySpec(encryptionData.encryptionKey, "AES")
////                val ivSpec = IvParameterSpec(ivKey)
////                cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
////
////                CipherInputStream(inputStream, cipher).use { cipherInputStream ->
////                    cipherInputStream.copyTo(outputStream)
////                }
////            } else {
////                inputStream.copyTo(outputStream)
////            }
////        }
////    }
////
////    // This function was already correct as it accepted headers. No changes needed.
////
////
////    fun extractVideoQualities(m3u8Url: String, headers: Map<String, String>): List<Video> {
////        // ... (This function is for online playback and is likely fine as is,
////        // since master playlists are usually very small)
////        return try {
////            val request = Request.Builder().url(m3u8Url).headers(headers.toHeaders()).build()
////            val response = client.newCall(request).execute()
////            if (!response.isSuccessful) return emptyList()
////
////            // Stream the master playlist to be safe
////            val masterPlaylist = response.body!!.source().use { it.readUtf8() }
////            val masterBaseUrl = m3u8Url.substringBeforeLast("/")
////            val videoList = mutableListOf<Video>()
////
////            val streamRegex = Regex("""#EXT-X-STREAM-INF:(?:.*?RESOLUTION=(\d+x\d+))?.*?\n(.*?)\s""")
////            streamRegex.findAll(masterPlaylist).forEach { match ->
////                val resolution = match.groups[1]?.value?.substringAfter('x')
////                val qualityLabel = if (resolution != null) "${resolution}p" else "Auto"
////                val streamUrl = match.groups[2]?.value?.trim()
////
////                if (streamUrl != null) {
////                    val fullUrl = if (streamUrl.startsWith("http")) streamUrl else "$masterBaseUrl/$streamUrl"
////                    videoList.add(
////                        Video(
////                            url = fullUrl,
////                            quality = qualityLabel,
////                            videoUrl = fullUrl,
////                            headers = headers
////                        )
////                    )
////                }
////            }
////
////            if (videoList.isEmpty() && masterPlaylist.contains("#EXTINF")) {
////                videoList.add(Video(m3u8Url, "Default", m3u8Url, headers = headers))
////            }
////
////            videoList.sortedByDescending { it.quality.filter { q -> q.isDigit() }.toIntOrNull() ?: 0 }
////        } catch (e: Exception) {
////            e.printStackTrace()
////            emptyList()
////        }
////    }
////}
////
//////package com.faselhd.app.utils
//////
//////
//////import com.faselhd.app.models.Video
//////import okhttp3.Headers.Companion.toHeaders
//////import okhttp3.OkHttpClient
//////import okhttp3.Request
//////import java.io.IOException
//////import javax.crypto.Cipher
//////import javax.crypto.spec.IvParameterSpec
//////import javax.crypto.spec.SecretKeySpec
//////import kotlin.math.pow
//////
//////object M3u8Helper {
//////    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
//////    private const val BASE_URL = "https://www.faselhds.xyz"
//////
//////    // *** THIS IS THE CRITICAL FIX ***
//////    // Configure the client to automatically add the required headers to every request
//////    private val client = OkHttpClient.Builder()
//////        .addInterceptor { chain ->
//////            val originalRequest = chain.request()
//////            val newRequest = originalRequest.newBuilder()
//////                .header("User-Agent", USER_AGENT)
//////                .header("Referer", BASE_URL)
//////                .build()
//////            chain.proceed(newRequest)
//////        }
//////        .build()
//////
//////    // The rest of the helper code remains the same, but we will simplify the function calls
//////    // because headers are now handled automatically.
//////
//////    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
//////    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")
//////
//////    private fun getParentLink(uri: String): String {
//////        val split = uri.split("/").toMutableList()
//////        if (split.isEmpty()) return ""
//////        split.removeAt(split.lastIndex)
//////        return split.joinToString("/")
//////    }
//////
//////    private fun isNotCompleteUrl(url: String): Boolean {
//////        return !url.startsWith("https://") && !url.startsWith("http://")
//////    }
//////
//////    private fun toBytes16Big(n: ULong): ByteArray {
//////        val bytes = ByteArray(16)
//////        for (i in 0 until 16) {
//////            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
//////        }
//////        return bytes
//////    }
//////
//////    private fun defaultIv(sequence: Int): ByteArray {
//////        return toBytes16Big(sequence.toULong())
//////    }
//////
//////    private fun getDecrypted(secretKey: ByteArray, data: ByteArray, iv: ByteArray, sequence: Int): ByteArray {
//////        val ivKey = if (iv.isEmpty()) defaultIv(sequence) else iv
//////        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
//////        val skSpec = SecretKeySpec(secretKey, "AES")
//////        val ivSpec = IvParameterSpec(ivKey)
//////        cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
//////        return cipher.doFinal(data)
//////    }
//////
//////    data class HlsDownloadData(
//////        val encryptionKey: ByteArray?,
//////        val encryptionIv: ByteArray?,
//////        val tsLinks: List<String>
//////    )
//////
//////    // Main function to parse the M3U8 playlist. NO LONGER NEEDS headers parameter.
//////    @Throws(IOException::class)
//////    fun HlsDownloadData(playlistUrl: String): HlsDownloadData {
//////        val request = Request.Builder().url(playlistUrl).build() // The interceptor will add headers
//////
//////        val response = client.newCall(request).execute()
//////        if (!response.isSuccessful) throw IOException("Failed to fetch playlist (Code: ${response.code})")
//////
//////        val playlistText = response.body!!.string()
//////        val parentUrl = getParentLink(playlistUrl)
//////
//////        var encryptionKey: ByteArray? = null
//////        var encryptionIv: ByteArray? = null
//////
//////        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let {
//////            var keyUri = it[2]
//////            if (isNotCompleteUrl(keyUri)) {
//////                keyUri = "$parentUrl/$keyUri"
//////            }
//////            val keyRequest = Request.Builder().url(keyUri).build()
//////            val keyResponse = client.newCall(keyRequest).execute()
//////            if (keyResponse.isSuccessful) {
//////                encryptionKey = keyResponse.body!!.bytes()
//////            }
//////            encryptionIv = it.getOrNull(3)?.toByteArray()
//////        }
//////
//////        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
//////            var tsUrl = match.groupValues[1].trim()
//////            if (isNotCompleteUrl(tsUrl)) {
//////                tsUrl = "$parentUrl/$tsUrl"
//////            }
//////            tsUrl
//////        }.toList()
//////
//////        if (tsLinks.isEmpty()) throw IOException("No .ts segments found in playlist")
//////
//////        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
//////    }
//////
//////    // Function to download a single .ts segment. NO LONGER NEEDS headers parameter.
//////    @Throws(IOException::class)
//////    fun downloadSegment(
//////        segmentUrl: String,
//////        encryptionData: HlsDownloadData?,
//////        sequence: Int
//////    ): ByteArray {
//////        val request = Request.Builder().url(segmentUrl).build() // The interceptor will add headers
//////        val response = client.newCall(request).execute()
//////        if (!response.isSuccessful) throw IOException("Failed to download segment $segmentUrl (Code: ${response.code})")
//////
//////        val tsData = response.body!!.bytes()
//////        if (tsData.isEmpty()) throw IOException("Segment is empty")
//////
//////        if (encryptionData?.encryptionKey != null) {
//////            return getDecrypted(
//////                encryptionData.encryptionKey,
//////                tsData,
//////                encryptionData.encryptionIv ?: byteArrayOf(),
//////                sequence
//////            )
//////        }
//////        return tsData
//////    }
//////
//////    /**
//////     * High-level function for online playback. Parses a master M3U8 playlist and returns
//////     * a list of Video objects, one for each quality stream found.
//////     *
//////     * @param m3u8Url The URL of the master M3U8 playlist.
//////     * @param headers A map of headers (like Referer) required to access the playlist.
//////     * @return A list of Video objects sorted by quality.
//////     */
//////    fun extractVideoQualities(m3u8Url: String, headers: Map<String, String>): List<Video> {
//////        try {
//////            val request = Request.Builder().url(m3u8Url).headers(headers.toHeaders()).build()
//////            val response = client.newCall(request).execute()
//////            if (!response.isSuccessful) return emptyList()
//////
//////            val masterPlaylist = response.body!!.string()
//////            val masterBaseUrl = m3u8Url.substringBeforeLast("/")
//////            val videoList = mutableListOf<Video>()
//////
//////            // Regex to find stream info (resolution) and its corresponding URL
//////            val streamRegex = Regex("""#EXT-X-STREAM-INF:(?:.*?RESOLUTION=(\d+x\d+))?.*?\n(.*?)\s""")
//////            streamRegex.findAll(masterPlaylist).forEach { match ->
//////                val resolution = match.groups[1]?.value?.substringAfter('x') // e.g., "1920x1080" -> "1080"
//////                val qualityLabel = if (resolution != null) "${resolution}p" else "Auto"
//////                val streamUrl = match.groups[2]?.value?.trim()
//////
//////                if (streamUrl != null) {
//////                    val fullUrl = if (streamUrl.startsWith("http")) streamUrl else "$masterBaseUrl/$streamUrl"
//////                    videoList.add(
//////                        Video(
//////                            url = fullUrl,
//////                            quality = qualityLabel,
//////                            videoUrl = fullUrl,
//////                            headers = headers // Pass original headers to the player
//////                        )
//////                    )
//////                }
//////            }
//////
//////            // If no streams were found, it might be a direct media playlist.
//////            if (videoList.isEmpty() && masterPlaylist.contains("#EXTINF")) {
//////                videoList.add(Video(m3u8Url, "Default", m3u8Url, headers = headers))
//////            }
//////
//////            // Sort from highest quality to lowest
//////            return videoList.sortedByDescending { it.quality.filter { q -> q.isDigit() }.toIntOrNull() ?: 0 }
//////        } catch (e: Exception) {
//////            e.printStackTrace()
//////            return emptyList()
//////        }
//////    }
//////}