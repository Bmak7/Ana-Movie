package com.faselhd.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebViewResolver(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    companion object {
        private const val TIMEOUT_MS: Long = 20000 // 20 seconds
        private val VIDEO_REGEX by lazy { Regex("\\.(mp4|m3u8)") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getUrl(requestUrl: String, headers: Map<String, String>): String {
        return suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var resolved = false

            val timeoutRunnable = Runnable {
                if (!resolved) {
                    resolved = true
                    continuation.resume("") // Return empty string on timeout
                    webView?.destroy()
                }
            }

            handler.post {
                val wv = WebView(context)
                webView = wv
                with(wv.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    userAgentString = this@WebViewResolver.userAgent
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (VIDEO_REGEX.containsMatchIn(url) && !resolved) {
                            resolved = true
                            // *** THE FIX IS HERE ***
                            // We are on a background thread. Post the work to the main thread.
                            handler.post {
                                handler.removeCallbacks(timeoutRunnable) // Clean up the timeout
                                continuation.resume(url) // Resume the coroutine with the result
                                view.destroy() // Safely destroy the WebView on the main thread
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                continuation.invokeOnCancellation {
                    handler.removeCallbacks(timeoutRunnable)
                    handler.post {
                        webView?.stopLoading()
                        webView?.destroy()
                    }
                }

                // Start timeout
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                wv.loadUrl(requestUrl, headers)
            }
        }
    }
}