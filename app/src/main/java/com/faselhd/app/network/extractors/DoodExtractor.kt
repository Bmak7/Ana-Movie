package com.faselhd.app.network.extractors

import com.faselhd.app.models.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kotlin.random.Random

class DoodExtractor(private val client: OkHttpClient) {

    fun getFinalUrl(originalUrl: String): String {
        // Create trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // Bypass hostname verification
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        return try {
            val request = Request.Builder()
                .url(originalUrl)
                .head()
                .build()

            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            response.close()

            finalUrl
        } catch (e: Exception) {
            e.printStackTrace()
            originalUrl
        }
    }


    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = runCatching {
            val embedUrl = url.replace("/d/", "/e/")
            val response = client.newCall(Request.Builder().url(embedUrl).build()).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body!!.string()
            if (!content.contains("'/pass_md5/")) return@runCatching null

            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            val newQuality = listOfNotNull(
                quality,
                "Doodstream " + (extractedQuality ?: (if (redirect) "mirror" else "")),
            ).joinToString(" - ")

            val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return@runCatching null)
            val token = md5.substringAfterLast("/")
            val randomString = createHashTable()

            val videoUrlStart = client.newCall(
                Request.Builder()
                    .url(md5)
                    .headers(Headers.headersOf("referer", newUrl))
                    .build()
            ).execute().body!!.string()

            val videoUrl = "$videoUrlStart$randomString?token=$token"
            val videoHeaders = mapOf("Referer" to "https://dood.watch/")
            Video(videoUrl, newQuality, videoUrl, resolution = extractedQuality ?: "Doodstream", headers = videoHeaders)
        }.getOrNull()

        return video?.let(::listOf) ?: emptyList()
    }

    private fun createHashTable(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..10)
            .map { alphabet.random() }
            .joinToString("")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}

