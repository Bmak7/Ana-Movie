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

    private val handler = Handler(Looper.getMainLooper())
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    companion object {
        private const val TIMEOUT_MS: Long = 20000 // 20 seconds
        private val VIDEO_REGEX by lazy { Regex("\\.(mp4|m3u8)") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getUrl(requestUrl: String, headers: Map<String, String>): String {
        Log.d(TAG, "Starting URL resolution for: $requestUrl")
        return suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var resolved = false

            val timeoutRunnable = Runnable {
                if (!resolved) {
                    Log.d(TAG, "Timeout reached for URL: $requestUrl")
                    resolved = true
                    // Ensure coroutine is resumed on the correct thread if needed, though "" is safe
                    if (continuation.isActive) {
                        continuation.resume("") // Return empty string on timeout
                    }
                    webView?.destroy()
                    Log.d(TAG, "WebView destroyed due to timeout")
                }
            }

            // Centralized cleanup function
            fun cleanup(result: String, from: String) {
                if (!resolved) {
                    resolved = true
                    handler.removeCallbacks(timeoutRunnable)
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                    // Always destroy WebView on the main thread
                    handler.post {
                        webView?.destroy()
                        Log.d(TAG, "WebView destroyed after success from $from")
                    }
                }
            }

            handler.post {
                Log.d(TAG, "Creating WebView on main thread")
                val wv = WebView(context)
                webView = wv
                with(wv.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false // Important for auto-playing media
                    userAgentString = this@WebViewResolver.userAgent
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (VIDEO_REGEX.containsMatchIn(url)) {
                            Log.d(TAG, "Found video URL match via intercept: $url")
                            cleanup(url, "shouldInterceptRequest")
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    // *** THIS IS THE CORRECTED LOGIC ***
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "Page finished loading: $url")

                        // Script to find the HLS source from the JWPlayer object
                        val jsScript = """
                            (function() {
                                try {
                                    if (typeof jwplayer !== 'undefined') {
                                        var sources = jwplayer().getPlaylist()[0].sources;
                                        for (var i = 0; i < sources.length; i++) {
                                            if (sources[i].file.includes('.m3u8')) {
                                                return sources[i].file;
                                            }
                                        }
                                        // Fallback if no specific m3u8 is found
                                        return jwplayer().getPlaylist()[0].file;
                                    }
                                } catch (e) { /* Player not ready or different API */ }
                                return null;
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(jsScript) { result ->
                            // result is a JSON-encoded string, so remove quotes
                            val extractedUrl = result?.takeIf { it != "null" }?.removeSurrounding("\"")

                            if (!extractedUrl.isNullOrBlank()) {
                                Log.d(TAG, "Successfully extracted URL via JavaScript: $extractedUrl")
                                cleanup(extractedUrl, "onPageFinished JS")
                            } else {
                                Log.d(TAG, "JavaScript extraction failed. Waiting for intercept or timeout.")
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        Log.e(TAG, "WebView error: $errorCode - $description - $failingUrl")
                    }
                }

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Coroutine cancelled for URL: $requestUrl")
                    handler.removeCallbacks(timeoutRunnable)
                    handler.post {
                        webView?.stopLoading()
                        webView?.destroy()
                        Log.d(TAG, "WebView destroyed due to cancellation")
                    }
                }

                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                wv.loadUrl(requestUrl, headers)
            }
        }
    }
}
//package com.faselhd.app.utils
//
//import android.annotation.SuppressLint
//import android.content.Context
//import android.os.Handler
//import android.os.Looper
//import android.webkit.WebResourceRequest
//import android.webkit.WebResourceResponse
//import android.webkit.WebView
//import android.webkit.WebViewClient
//import kotlinx.coroutines.suspendCancellableCoroutine
//import kotlin.coroutines.resume
//
//class WebViewResolver(private val context: Context) {
//
//    private val handler = Handler(Looper.getMainLooper())
//    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
//
//    companion object {
//        private const val TIMEOUT_MS: Long = 20000 // 20 seconds
//        private val VIDEO_REGEX by lazy { Regex("\\.(mp4|m3u8)") }
//    }
//
//    @SuppressLint("SetJavaScriptEnabled")
//    suspend fun getUrl(requestUrl: String, headers: Map<String, String>): String {
//        return suspendCancellableCoroutine { continuation ->
//            var webView: WebView? = null
//            var resolved = false
//
//            val timeoutRunnable = Runnable {
//                if (!resolved) {
//                    resolved = true
//                    continuation.resume("") // Return empty string on timeout
//                    webView?.destroy()
//                }
//            }
//
//            handler.post {
//                val wv = WebView(context)
//                webView = wv
//                with(wv.settings) {
//                    javaScriptEnabled = true
//                    domStorageEnabled = true
//                    databaseEnabled = true
//                    useWideViewPort = false
//                    loadWithOverviewMode = false
//                    userAgentString = this@WebViewResolver.userAgent
//                }
//
//                wv.webViewClient = object : WebViewClient() {
//                    override fun shouldInterceptRequest(
//                        view: WebView,
//                        request: WebResourceRequest,
//                    ): WebResourceResponse? {
//                        val url = request.url.toString()
//                        if (VIDEO_REGEX.containsMatchIn(url) && !resolved) {
//                            resolved = true
//                            // *** THE FIX IS HERE ***
//                            // We are on a background thread. Post the work to the main thread.
//                            handler.post {
//                                handler.removeCallbacks(timeoutRunnable) // Clean up the timeout
//                                continuation.resume(url) // Resume the coroutine with the result
//                                view.destroy() // Safely destroy the WebView on the main thread
//                            }
//                        }
//                        return super.shouldInterceptRequest(view, request)
//                    }
//                }
//
//                continuation.invokeOnCancellation {
//                    handler.removeCallbacks(timeoutRunnable)
//                    handler.post {
//                        webView?.stopLoading()
//                        webView?.destroy()
//                    }
//                }
//
//                // Start timeout
//                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
//                wv.loadUrl(requestUrl, headers)
//            }
//        }
//    }
//}
//
