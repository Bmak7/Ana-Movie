package com.faselhd.app.network // Or your desired package

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DdosGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Check if DDos-GUARD is on
        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
            return response
        }

        // Close the previous response to free resources
        response.close()

        // Get existing cookies
        val urlString = originalRequest.url.toString()
        val cookies = cookieManager.getCookie(urlString)
        val oldCookie = if (!cookies.isNullOrEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(originalRequest.url, it) }
        } else {
            emptyList()
        }

        // Check if we already have the required cookie
        val ddg2Cookie = oldCookie.firstOrNull { it.name == "__ddg2_" }
        if (!ddg2Cookie?.value.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        // Fetch the new cookie
        val newCookie = getNewCookie(originalRequest.url)
            ?: return chain.proceed(originalRequest) // Proceed without it if fetching fails

        // Combine old and new cookies
        val newCookieHeader = buildString {
            (oldCookie + newCookie).forEachIndexed { index, cookie ->
                if (index > 0) append("; ")
                append(cookie.name).append('=').append(cookie.value)
            }
        }

        // Retry the request with the new cookie
        val newRequest = originalRequest.newBuilder()
            .header("Cookie", newCookieHeader)
            .build()

        return chain.proceed(newRequest)
    }

    private fun getNewCookie(url: HttpUrl): Cookie? {
        // This is the known URL to get the DDOS-Guard check script
        val checkJsUrl = "https://check.ddos-guard.net/check.js"
        val checkRequest = Request.Builder().url(checkJsUrl).build()

        return try {
            // Get the path for the actual check
            val wellKnown = client.newCall(checkRequest).execute().body!!.string()
                .substringAfter("'").substringBefore("'")

            val checkUrl = url.newBuilder().encodedPath(wellKnown).build()

            // Perform the check and get the "set-cookie" header from the response
            val checkResponse = client.newCall(Request.Builder().url(checkUrl).build()).execute()
            checkResponse.header("set-cookie")?.let {
                Cookie.parse(url, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private val ERROR_CODES = listOf(403)
        private val SERVER_CHECK = listOf("ddos-guard")
    }
}