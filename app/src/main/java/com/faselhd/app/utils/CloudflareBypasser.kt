package com.faselhd.app.network.sources

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Data class to hold the results of the bypass
data class CloudflareClearance(val userAgent: String, val cookies: String)

object CloudflareBypasser {

    private var isBypassing = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getCloudflareClearance(context: Context, url: String): CloudflareClearance? {
        // Prevent multiple bypass attempts at the same time
        if (isBypassing || Looper.myLooper() != Looper.getMainLooper()) {
            // If we are not on the main thread, we cannot create a WebView.
            // We can re-call this on the main thread if needed. For now, we return null.
            return null
        }
        isBypassing = true

        val deferred = CompletableDeferred<CloudflareClearance?>()

        // WebView operations must be done on the main thread
        withContext(Dispatchers.Main) {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    // We check for the "cf_clearance" cookie which indicates success
                    if (cookies != null && cookies.contains("cf_clearance")) {
                        val userAgent = view?.settings?.userAgentString
                        if (userAgent != null) {
                            deferred.complete(CloudflareClearance(userAgent, cookies))
                        } else {
                            deferred.complete(null)
                        }
                    }
                    // If we don't find the cookie, the deferred will eventually time out and return null.
                }
            }
            webView.loadUrl(url)
        }

        // Wait for a maximum of 30 seconds for the challenge to be solved.
        val result = withTimeoutOrNull(30_000) {
            deferred.await()
        }

        isBypassing = false
        return result
    }
}