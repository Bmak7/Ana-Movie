// In file: com/faselhd/app/CloudflareActivity.kt

package com.faselhd.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import com.faselhd.app.network.CloudFlareProtectedException
import com.faselhd.app.network.SharedCookieManager
import java.net.URL
import com.example.myapplication.R

class CloudflareActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var targetUrl: String
    private var isChallengeSolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloudflare)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        supportActionBar?.hide()

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        targetUrl = url

        initWebView()

        // Pass the base domain as a Referer. This is often required.
        val headers = mutableMapOf<String, String>()
        val referer = URL(targetUrl).protocol + "://" + URL(targetUrl).host
        headers["Referer"] = referer

        // Start loading the protected URL with headers
        webView.loadUrl(targetUrl, headers)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings

        // --- CRITICAL SETTINGS FOR STUBBORN SITES ---
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true // Enable the database storage API.
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
        settings.cacheMode = WebSettings.LOAD_NO_CACHE // Don't use a cache.

        // --- THE FIX FOR STUBBORN SITES ---
        // This allows the WebView to load resources from HTTP on an HTTPS page, which can be
        // necessary for some of Cloudflare's or reCAPTCHA's scripts to work.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = CloudflareWebViewClient(this, targetUrl)
    }

    // This is our callback from the WebViewClient
    fun onChallengeSolved() {
        if (!isChallengeSolved) {
            isChallengeSolved = true
            Toast.makeText(this, "Challenge solved!", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            // Use a small delay to ensure all operations are complete before finishing
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 500)
        }
    }


    private class CloudflareWebViewClient(
        private val activity: CloudflareActivity,
        private val requestUrl: String
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            activity.progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            activity.progressBar.visibility = View.GONE

            // --- FORCE COOKIE SYNC AND CHECK ---
            // This is the most important part. We force the WebView's cookie store
            // to be written to the persistent storage that our OkHttp client uses.
            CookieManager.getInstance().flush()

            val cookie = SharedCookieManager.getClearanceCookie(requestUrl)

            if (cookie != null) {
                // Success! We got the clearance cookie.
                activity.onChallengeSolved()
            }
        }
    }

    // The ActivityResultContract remains the same
    class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
        override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
            return Intent(context, CloudflareActivity::class.java).apply {
                putExtra(EXTRA_URL, input.url)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == Activity.RESULT_OK
        }
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
    }
}