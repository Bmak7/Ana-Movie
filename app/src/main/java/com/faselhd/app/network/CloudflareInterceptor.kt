package com.faselhd.app.network // Or a utils package

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(private val context: Context, private val cookieJar: CookieJar) : Interceptor {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Attempt the request directly first
        val originalResponse = chain.proceed(originalRequest)

        // Check if Cloudflare blocked the request
        if (originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK) {
            originalResponse.close() // Close the failed response
            try {
                // Solve the challenge with a WebView
                val newRequest = resolveWithWebView(originalRequest)
                // Retry the request with the new cookies
                return chain.proceed(newRequest)
            } catch (e: Exception) {
                throw IOException(e)
            }
        }

        // Not blocked, return the original response
        return originalResponse
    }

    private class CloudflareJSI(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() = latch.countDown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request {
        val latch = CountDownLatch(1)
        val jsInterface = CloudflareJSI(latch)
        var webView: WebView? = null
        val url = request.url.toString()
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = request.header("User-Agent")
            }
            webview.addJavascriptInterface(jsInterface, "CloudflareJSI")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }
            }
            webview.loadUrl(url, headers)
        }

        // Wait for the challenge to be solved
        latch.await(30, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        // Get cookies from WebView's CookieManager
        val cookies = CookieManager.getInstance().getCookie(url)
            ?.split(";")
            ?.mapNotNull { Cookie.parse(url.toHttpUrl(), it) }
            ?: emptyList()

        // Add the retrieved cookies to our OkHttp client's cookie jar
        cookieJar.saveFromResponse(url.toHttpUrl(), cookies)

        return request
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = setOf("cloudflare-nginx", "cloudflare")

        private val CHECK_SCRIPT by lazy {
            """
            (function() {
                // Wait for the Cloudflare challenge to be solved
                const interval = setInterval(() => {
                    if (document.querySelector("#challenge-form") == null) {
                        // Challenge form is gone, we likely passed
                        clearInterval(interval);
                        CloudflareJSI.leave();
                    }
                    // Optional: You can add logic here to auto-click certain challenges if needed
                    // For example, clicking a simple "Verify you are human" button:
                    // document.querySelector("#challenge-stage > div > input[type='button']")?.click();
                }, 2000); // Check every 2 seconds

                // Set a timeout to prevent waiting forever
                setTimeout(() => {
                    clearInterval(interval);
                    CloudflareJSI.leave(); // Give up after 25 seconds
                }, 25000);
            })();
            """.trimIndent()
        }
    }
}