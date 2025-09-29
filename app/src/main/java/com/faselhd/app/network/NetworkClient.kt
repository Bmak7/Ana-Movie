package com.faselhd.app.network // Or your preferred package

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

// A singleton object to hold our shared OkHttpClient instance.
object NetworkClient {

    // Lazy initialization ensures the client is created only once when first accessed.
    val client: OkHttpClient by lazy {
        // This CookieJar will be shared by the extractor and the player.
        val cookieJar = object : CookieJar {
            private val cookieStore = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        }

        OkHttpClient.Builder()
            .cookieJar(cookieJar) // The most important part!
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}