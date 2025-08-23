// In file: app/src/main/java/com/faselhd/app/utils/WebViewResolver.kt
package com.faselhd.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class WebViewResolverNetf(private val context: Context) {
    private val TAG = "WebViewResolver"
    private val handler = Handler(Looper.getMainLooper())

    suspend fun getUrl(url: String, headers: Map<String, String>): String {
        return withContext(Dispatchers.Main) { // Ensure WebView operations are on the main thread
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null // Hold a reference to destroy it

                val timeoutRunnable = Runnable {
                    Log.d(TAG, "Timeout reached for URL: $url")
                    if (continuation.isActive) {
                        continuation.resume("") // Resume with empty string on timeout
                    }
                    webView?.destroy()
                    Log.d(TAG, "WebView destroyed due to timeout")
                }

                val resolutionContinuation = object : CancellableContinuation<String> by continuation {
                    override fun resume(value: String, onCancellation: ((cause: Throwable) -> Unit)?) {
                        handler.removeCallbacks(timeoutRunnable)
                        webView?.destroy()
                        Log.d(TAG, "WebView destroyed after resolution")
                        if (continuation.isActive) {
                            continuation.resume(value, onCancellation)
                        }
                    }

                    override fun resumeWith(result: Result<String>) {
                        handler.removeCallbacks(timeoutRunnable)
                        webView?.destroy()
                        Log.d(TAG, "WebView destroyed after resolution")
                        if (continuation.isActive) {
                            continuation.resumeWith(result)
                        }
                    }
                }

                webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                // =================================================================
                //  THE FIX: Add a standard mobile browser User-Agent
                // =================================================================
                val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36"
                webView.settings.userAgentString = userAgent
                // =================================================================

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "Page finished loading: $url")
                        // Optional: a short delay for JS to execute
                        handler.postDelayed({
                            if (resolutionContinuation.isActive) {
                                // If we don't find the link, we rely on the timeout
                                Log.d(TAG, "JavaScript extraction failed. Waiting for intercept or timeout.")
                            }
                        }, 2000)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val interceptedUrl = request.url.toString()
                        // This is a common pattern for HLS streams
                        if (interceptedUrl.contains(".m3u8")) {
                            Log.d(TAG, "Intercepted HLS URL: $interceptedUrl")
                            if (resolutionContinuation.isActive) {
                                resolutionContinuation.resume(interceptedUrl)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Coroutine cancelled for URL: $url")
                    handler.removeCallbacks(timeoutRunnable)
                    webView?.destroy()
                    Log.d(TAG, "WebView destroyed due to cancellation")
                }

                handler.postDelayed(timeoutRunnable, 20000L) // 20-second timeout
                webView.loadUrl(url, headers)
            }
        }
    }
}