// In file: com/faselhd/app/network/CloudflareBypass.kt

package com.faselhd.app.network

import android.webkit.CookieManager
import okhttp3.*
import org.jsoup.Jsoup
import java.io.IOException
import java.net.CookiePolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


// A custom exception to signal that a Cloudflare challenge is required.
class CloudFlareProtectedException(
    message: String,
    val url: String // The URL that is protected by Cloudflare
) : IOException(message)

// Singleton object to hold our shared cookie jar.
// This is the glue that makes the bypass work. Both OkHttp and the WebView will use this.
object SharedCookieManager {
    val cookieJar: CookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
            syncCookiesToWebView(cookies, url)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }

        private fun syncCookiesToWebView(cookies: List<Cookie>, url: HttpUrl) {
            val cookieManager = CookieManager.getInstance()
            cookies.forEach { cookie ->
                val cookieString = "${cookie.name}=${cookie.value}; domain=${cookie.domain}; path=${cookie.path}"
                cookieManager.setCookie(url.host, cookieString)
            }
            CookieManager.getInstance().flush()
        }
    }

    fun getClearanceCookie(url: String): Cookie? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return cookieJar.loadForRequest(httpUrl)
            .firstOrNull { it.name == "cf_clearance" }
    }
}

// The Interceptor that inspects responses from the server.
class CloudFlareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // We check if the response is a Cloudflare challenge page.
        // Cloudflare uses status codes 503 or 403 for its challenge pages.
        if (response.code == 503 || response.code == 403) {
            val responseBody = response.body?.string() ?: ""
            // The presence of "js-challenge" or "challenge-platform" is a strong indicator.
            if ("js-challenge" in responseBody || "challenge-platform" in responseBody) {
                // Close the original response and throw our custom exception.
                response.close()
                throw CloudFlareProtectedException(
                    "Cloudflare protection detected",
                    request.url.toString()
                )
            }
            // We must put the body back for other parsing to work if it's not a challenge
            val newBody = ResponseBody.create(response.body?.contentType(), responseBody)
            return response.newBuilder().body(newBody).build()
        }

        return response
    }
}