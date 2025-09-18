package com.faselhd.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.faselhd.app.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.media3.common.Tracks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages ExoPlayer state and optimizations for better streaming performance
 */
class PlayerStateManager(
    private val context: Context,
    private val player: ExoPlayer,
    private val trackSelector: DefaultTrackSelector
) {

    // Adaptive streaming configuration
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var monitoringJob: Job? = null

    // Network monitoring
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Performance metrics
    private var consecutiveStalls = 0
    private val stallThreshold = 3 // Number of stalls before reporting an issue

    data class NetworkInfo(
        val isConnected: Boolean,
        val type: NetworkType,
        val bandwidth: Long // in kbps
    )

    enum class NetworkType {
        WIFI, MOBILE, ETHERNET, UNKNOWN
    }

    interface StateListener {
        fun onBufferHealthChanged(percentage: Int)
        fun onNetworkChanged(networkInfo: NetworkInfo)
        fun onQualityChanged(height: Int, bitrate: Int)
        fun onPerformanceIssue(issue: PerformanceIssue)
    }

    enum class PerformanceIssue {
        FREQUENT_BUFFERING,
        POOR_NETWORK,
        LOW_BUFFER_HEALTH,
    }

    private val listeners = mutableSetOf<StateListener>()
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                consecutiveStalls++
                if (consecutiveStalls >= stallThreshold) {
                    listeners.forEach { it.onPerformanceIssue(PerformanceIssue.FREQUENT_BUFFERING) }
                    // Reset after reporting to avoid spamming
                    consecutiveStalls = 0
                }
            } else if (playbackState == Player.STATE_READY) {
                consecutiveStalls = 0
            }
        }

        @OptIn(UnstableApi::class)
        override fun onTracksChanged(tracks: Tracks) {
            val videoTrackGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            if (videoTrackGroup != null && videoTrackGroup.isSelected) {
                for (i in 0 until videoTrackGroup.length) {
                    if (videoTrackGroup.isTrackSelected(i)) {
                        val format = videoTrackGroup.getTrackFormat(i)
                        listeners.forEach { it.onQualityChanged(format.height, format.bitrate) }
                        break
                    }
                }
            }
        }
    }

    fun addListener(listener: StateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun startMonitoring() {
        stopMonitoring() // Ensure no previous monitoring is running
        player.addListener(playerListener)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyNetworkChange()
            }
            override fun onLost(network: Network) {
                notifyNetworkChange()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                notifyNetworkChange()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        monitoringJob = scope.launch {
            while (true) {
                // Monitor buffer health
                val bufferedPercentage = player.bufferedPercentage
                listeners.forEach { it.onBufferHealthChanged(bufferedPercentage) }
                if (player.isPlaying && bufferedPercentage < 10) {
                    listeners.forEach { it.onPerformanceIssue(PerformanceIssue.LOW_BUFFER_HEALTH) }
                }
                delay(2000) // Check every 2 seconds
            }
        }
        notifyNetworkChange() // Initial check
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        player.removeListener(playerListener)
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
    }

    /**
     * Optimize player settings for current video and network conditions
     */
    fun optimizeForVideo(video: Video) {
        val networkInfo = getCurrentNetworkInfo()
        optimizeBufferSettings(networkInfo)
        optimizeTrackSelection(networkInfo)
    }

    private fun notifyNetworkChange() {
        val networkInfo = getCurrentNetworkInfo()
        listeners.forEach { it.onNetworkChanged(networkInfo) }
        if (!networkInfo.isConnected || networkInfo.bandwidth < 500) { // less than 500 kbps
            listeners.forEach { it.onPerformanceIssue(PerformanceIssue.POOR_NETWORK) }
        }
    }

    private fun optimizeBufferSettings(networkInfo: NetworkInfo) {
        // This is now handled by ExoPlayer's DefaultLoadControl,
        // but we could override it if specific behavior is needed.
        // For now, we focus on track selection which is more impactful.
    }

    @OptIn(UnstableApi::class)
    private fun optimizeTrackSelection(networkInfo: NetworkInfo) {
        val parametersBuilder = trackSelector.buildUponParameters()
        when (networkInfo.type) {
            NetworkType.WIFI, NetworkType.ETHERNET -> {
                // Allow high quality on unmetered connections
                parametersBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
            }
            NetworkType.MOBILE -> {
                // Be more conservative on mobile data to save usage and avoid buffering
                // Cap at 1080p quality (approx 5 Mbps)
                parametersBuilder.setMaxVideoBitrate(5_000_000)
            }
            NetworkType.UNKNOWN -> {
                // If network is unknown or poor, be very conservative
                // Cap at 480p quality (approx 1.5 Mbps)
                parametersBuilder.setMaxVideoBitrate(1_500_000)
            }
        }
        trackSelector.parameters = parametersBuilder.build()
    }

    private fun getCurrentNetworkInfo(): NetworkInfo {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (activeNetwork == null || capabilities == null) {
            return NetworkInfo(false, NetworkType.UNKNOWN, 0)
        }

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
        // Bandwidth is in Kbps
        val bandwidth = capabilities.linkDownstreamBandwidthKbps.toLong()

        return NetworkInfo(true, type, bandwidth)
    }
}