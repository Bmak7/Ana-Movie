package com.faselhd.app.utils // Or a suitable package like `network`

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.myapplication.R // Adjust to your R file path
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    private var client: OkHttpClient? = null
    var app: Requests? = null

    fun getClient(context: Context): OkHttpClient {
        if (client == null) {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
            val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)

            val cookieJar = object : CookieJar {
                private val cookieStore = mutableMapOf<String, List<okhttp3.Cookie>>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            }

            client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.5")
                        .build()
                    chain.proceed(request)
                }
                .ignoreAllSSLErrors()
                .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
                .connectTimeout(30, TimeUnit.SECONDS) // Add timeouts
                .readTimeout(30, TimeUnit.SECONDS)
                .apply {
                    // This is the crucial DNS fix
                    when (dns) {
                        1 -> addGoogleDns()
                        2 -> addCloudFlareDns()
                        4 -> addAdGuardDns()
                        5 -> addDNSWatchDns()
                        6 -> addQuad9Dns()
                        7 -> addDnsSbDns()
                        8 -> addCanadianShieldDns()
                        else -> addCloudFlareDns() // Default to Cloudflare for reliability
                    }
                }
                .build()

            app = Requests(client!!)
        }
        return client!!
    }
}