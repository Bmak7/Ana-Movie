package com.faselhd.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebViewResolver(private val context: Context) {
    private val TAG = "WebViewResolver"
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    companion object {
        private const val TIMEOUT_MS: Long = 30000
        private val VIDEO_REGEX by lazy {
            Regex("""\.(mp4|m3u8|mpd|mkv|avi|mov|flv|wmv|webm)""", RegexOption.IGNORE_CASE)
        }
        private val STREAM_REGEX by lazy {
            Regex("""(https?://[^\s'"]+\.(m3u8|mp4)[^\s'"]*)""", RegexOption.IGNORE_CASE)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getUrl(requestUrl: String, headers: Map<String, String>): String {
        return suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var resolved = false
            val mainHandler = Handler(Looper.getMainLooper())

            val timeoutRunnable = Runnable {
                if (!resolved) {
                    resolved = true
                    continuation.resume("")
                    webView?.destroy()
                }
            }

            mainHandler.post {
                val wv = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = this@WebViewResolver.userAgent
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (VIDEO_REGEX.containsMatchIn(url)) {
                                if (!resolved) {
                                    resolved = true
                                    mainHandler.removeCallbacks(timeoutRunnable)
                                    continuation.resume(url)
                                    view.destroy()
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!resolved && view != null) {
                                view.evaluateJavascript("(function() { return document.body.innerHTML; })();") { html ->
                                    if (resolved) return@evaluateJavascript

                                    try {
                                        val matches = STREAM_REGEX.findAll(html ?: "")
                                        matches.firstOrNull()?.groupValues?.get(1)?.let { videoUrl ->
                                            if (!resolved) {
                                                resolved = true
                                                mainHandler.removeCallbacks(timeoutRunnable)
                                                continuation.resume(videoUrl)
                                                view.destroy()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Log error if needed
                                    }
                                }
                            }
                        }
                    }
                }
                webView = wv

                continuation.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    mainHandler.post {
                        if (!resolved) {
                            resolved = true
                            webView?.stopLoading()
                            webView?.destroy()
                        }
                    }
                }

                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                wv.loadUrl(requestUrl, headers)
            }
        }
    }
}
