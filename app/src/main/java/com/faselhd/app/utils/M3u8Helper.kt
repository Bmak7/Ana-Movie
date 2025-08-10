package com.faselhd.app.utils


import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object M3u8Helper {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    private const val BASE_URL = "https://www.faselhds.xyz"

    // *** THIS IS THE CRITICAL FIX ***
    // Configure the client to automatically add the required headers to every request
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Referer", BASE_URL)
                .build()
            chain.proceed(newRequest)
        }
        .build()

    // The rest of the helper code remains the same, but we will simplify the function calls
    // because headers are now handled automatically.

    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")

    private fun getParentLink(uri: String): String {
        val split = uri.split("/").toMutableList()
        if (split.isEmpty()) return ""
        split.removeAt(split.lastIndex)
        return split.joinToString("/")
    }

    private fun isNotCompleteUrl(url: String): Boolean {
        return !url.startsWith("https://") && !url.startsWith("http://")
    }

    private fun toBytes16Big(n: ULong): ByteArray {
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
        }
        return bytes
    }

    private fun defaultIv(sequence: Int): ByteArray {
        return toBytes16Big(sequence.toULong())
    }

    private fun getDecrypted(secretKey: ByteArray, data: ByteArray, iv: ByteArray, sequence: Int): ByteArray {
        val ivKey = if (iv.isEmpty()) defaultIv(sequence) else iv
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val skSpec = SecretKeySpec(secretKey, "AES")
        val ivSpec = IvParameterSpec(ivKey)
        cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
        return cipher.doFinal(data)
    }

    data class HlsDownloadData(
        val encryptionKey: ByteArray?,
        val encryptionIv: ByteArray?,
        val tsLinks: List<String>
    )

    // Main function to parse the M3U8 playlist. NO LONGER NEEDS headers parameter.
    @Throws(IOException::class)
    fun HlsDownloadData(playlistUrl: String): HlsDownloadData {
        val request = Request.Builder().url(playlistUrl).build() // The interceptor will add headers

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to fetch playlist (Code: ${response.code})")

        val playlistText = response.body!!.string()
        val parentUrl = getParentLink(playlistUrl)

        var encryptionKey: ByteArray? = null
        var encryptionIv: ByteArray? = null

        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let {
            var keyUri = it[2]
            if (isNotCompleteUrl(keyUri)) {
                keyUri = "$parentUrl/$keyUri"
            }
            val keyRequest = Request.Builder().url(keyUri).build()
            val keyResponse = client.newCall(keyRequest).execute()
            if (keyResponse.isSuccessful) {
                encryptionKey = keyResponse.body!!.bytes()
            }
            encryptionIv = it.getOrNull(3)?.toByteArray()
        }

        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
            var tsUrl = match.groupValues[1].trim()
            if (isNotCompleteUrl(tsUrl)) {
                tsUrl = "$parentUrl/$tsUrl"
            }
            tsUrl
        }.toList()

        if (tsLinks.isEmpty()) throw IOException("No .ts segments found in playlist")

        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
    }

    // Function to download a single .ts segment. NO LONGER NEEDS headers parameter.
    @Throws(IOException::class)
    fun downloadSegment(
        segmentUrl: String,
        encryptionData: HlsDownloadData?,
        sequence: Int
    ): ByteArray {
        val request = Request.Builder().url(segmentUrl).build() // The interceptor will add headers
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to download segment $segmentUrl (Code: ${response.code})")

        val tsData = response.body!!.bytes()
        if (tsData.isEmpty()) throw IOException("Segment is empty")

        if (encryptionData?.encryptionKey != null) {
            return getDecrypted(
                encryptionData.encryptionKey,
                tsData,
                encryptionData.encryptionIv ?: byteArrayOf(),
                sequence
            )
        }
        return tsData
    }
}