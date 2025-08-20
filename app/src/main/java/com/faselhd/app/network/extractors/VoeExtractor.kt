package com.faselhd.app.network.extractors // Or your desired package

import android.util.Base64
import android.util.Log
import com.faselhd.app.models.Video // <-- IMPORTANT: Point this to your Video model
import com.faselhd.app.network.DdosGuardInterceptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class VoeExtractor(private val client: OkHttpClient, private val json: Json) {

    // This client will have the DDOS-Guard bypasser
    private val ddosGuardClient by lazy {
        client.newBuilder().addInterceptor(DdosGuardInterceptor(client)).build()
    }

    private val redirectRegex = Regex("""window\.location\.href\s*=\s*'([^']+)';""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        try {
            var document = ddosGuardClient.newCall(Request.Builder().url(url).build())
                .execute().let { Jsoup.parse(it.body!!.string(), url) }

            // Handle potential redirects found in scripts
            val scriptData = document.selectFirst("script")?.data()
            val redirectMatch = scriptData?.let { redirectRegex.find(it) }

            if (redirectMatch != null) {
                val redirectUrl = redirectMatch.groupValues[1]
                document = ddosGuardClient.newCall(Request.Builder().url(redirectUrl).build())
                    .execute().let { Jsoup.parse(it.body!!.string(), redirectUrl) }
            }

            // Find the encrypted JSON string
            val encodedString = document.selectFirst("script[type=application/json]")?.data()
                ?.trim()?.substringAfter("[\"")?.substringBeforeLast("\"]")
                ?: return emptyList()

            // Decrypt the string to get the real JSON content
            val decryptedJson = decryptF7(encodedString) ?: return emptyList()

            val m3u8Url = decryptedJson["source"]?.jsonPrimitive?.content
            val mp4Url = decryptedJson["direct_access_url"]?.jsonPrimitive?.content

            // Add the HLS (m3u8) stream if available. Modern players prefer this.
            if (m3u8Url != null) {
                videoList.add(
                    Video(url = m3u8Url, quality = "${prefix}Voe: Auto (HLS)", videoUrl = m3u8Url)
                )
            }

            // Add the direct MP4 link if available as a fallback
            if (mp4Url != null) {
                videoList.add(
                    Video(url = mp4Url, quality = "${prefix}Voe: MP4", videoUrl = mp4Url)
                )
            }
        } catch (e: Exception) {
            Log.e("VoeExtractor", "Failed to extract Voe links: ${e.message}")
            e.printStackTrace()
        }

        return videoList
    }

    private fun decryptF7(p8: String): JsonObject? {
        return try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = base64Decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = reverse(vF5)
            val vAtob = base64Decode(vF6)
            json.decodeFromString<JsonObject>(vAtob)
        } catch (e: Exception) {
            Log.e("VoeExtractor", "Decryption error: ${e.message}")
            null
        }
    }

    // --- All decryption helper functions are self-contained and need no changes ---

    private fun rot13(input: String): String {
        return input.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private val patternsRegex = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").joinToString("|") { Regex.escape(it) }.toRegex()
    private fun replacePatterns(input: String): String = input.replace(patternsRegex, "_")
    private fun removeUnderscores(input: String): String = input.replace("_", "")
    private fun charShift(input: String, shift: Int): String = input.map { (it.code - shift).toChar() }.joinToString("")
    private fun reverse(input: String): String = input.reversed()
    private fun base64Decode(input: String): String {
        val decodedBytes = Base64.decode(input, Base64.DEFAULT)
        return String(decodedBytes, Charsets.UTF_8) // Changed to UTF-8 for better compatibility
    }
}