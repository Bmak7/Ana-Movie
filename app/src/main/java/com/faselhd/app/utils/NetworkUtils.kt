package com.faselhd.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkUtils {

    /**
     * WARNING: This returns a client that trusts all SSL certificates.
     * This is insecure and should ONLY be used for specific connections
     * to servers with known certificate issues, like some video CDNs.
     * Do NOT use this for general networking.
     */
    fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
//            val sslSocketFactory = sslContext.socketFactory
            val cookieJar = object : CookieJar {
                private val cookieStore = HashMap<String, List<Cookie>>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: ArrayList()
                }
            }
            return OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .ignoreAllSSLErrors()
//                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true } // Also ignore hostname verification
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            return when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "UNKNOWN"
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return when (activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "WIFI"
                ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                else -> "UNKNOWN"
            }
        }
    }

    fun getConnectionSpeed(context: Context): Double {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            return when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                    // For WiFi, we can estimate based on link speed
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiInfo = wifiManager.connectionInfo
                    wifiInfo.linkSpeed.toDouble() // Returns speed in Mbps
                }
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                    // For mobile, estimate based on network subtype
                    estimateMobileSpeed(context)
                }
                else -> 1.0 // Default low speed for unknown connections
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return when (activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiInfo = wifiManager.connectionInfo
                    wifiInfo.linkSpeed.toDouble()
                }
                ConnectivityManager.TYPE_MOBILE -> estimateMobileSpeed(context)
                else -> 1.0
            }
        }
    }

    private fun estimateMobileSpeed(context: Context): Double {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        return when (telephonyManager.networkType) {

            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_IDEN -> 0.1 // 2G speeds

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> 2.0 // 3G speeds

            TelephonyManager.NETWORK_TYPE_LTE -> 10.0 // 4G LTE speeds

            TelephonyManager.NETWORK_TYPE_NR -> 50.0 // 5G speeds

            else -> 1.0 // Unknown, assume slow
        }
    }
}