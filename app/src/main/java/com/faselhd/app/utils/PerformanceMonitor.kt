package com.faselhd.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class PerformanceMonitor(private val context: Context) {

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val MONITORING_INTERVAL_MS = 1000L // 1 second
        private const val METRICS_RETENTION_HOURS = 24
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var player: ExoPlayer? = null

    // Performance metrics storage
    private val performanceMetrics = ConcurrentHashMap<String, MutableList<PerformanceMetric>>()
    private val networkMetrics = ConcurrentHashMap<String, NetworkMetric>()
    private val playbackMetrics = PlaybackMetrics()

    // Monitoring runnable
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                collectMetrics()
                handler.postDelayed(this, MONITORING_INTERVAL_MS)
            }
        }
    }

    fun startMonitoring(exoPlayer: ExoPlayer) {
        this.player = exoPlayer
        isMonitoring = true
        playbackMetrics.sessionStartTime = System.currentTimeMillis()
        handler.post(monitoringRunnable)
        Log.d(TAG, "Performance monitoring started")
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(monitoringRunnable)
        playbackMetrics.sessionEndTime = System.currentTimeMillis()
        Log.d(TAG, "Performance monitoring stopped")

        // Generate session report
        generateSessionReport()
    }

    private fun collectMetrics() {
        val currentTime = System.currentTimeMillis()

        // Collect playback metrics
        player?.let { player ->
            collectPlaybackMetrics(player, currentTime)
            collectBufferMetrics(player, currentTime)
            collectQualityMetrics(player, currentTime)
        }

        // Collect system metrics
        collectMemoryMetrics(currentTime)
        collectBatteryMetrics(currentTime)
        collectNetworkMetrics(currentTime)
    }

    private fun collectPlaybackMetrics(player: ExoPlayer, timestamp: Long) {
        val isPlaying = player.isPlaying
        val currentPosition = player.currentPosition
        val duration = player.duration
        val playbackState = player.playbackState

        // Track playback state changes
        if (playbackMetrics.lastPlaybackState != playbackState) {
            playbackMetrics.stateChanges++
            playbackMetrics.lastPlaybackState = playbackState

            when (playbackState) {
                ExoPlayer.STATE_BUFFERING -> {
                    playbackMetrics.bufferingStartTime = timestamp
                    playbackMetrics.bufferingEvents++
                }
                ExoPlayer.STATE_READY -> {
                    if (playbackMetrics.bufferingStartTime > 0) {
                        val bufferingDuration = timestamp - playbackMetrics.bufferingStartTime
                        playbackMetrics.totalBufferingTime += bufferingDuration
                        playbackMetrics.bufferingStartTime = 0
                    }
                }
            }
        }

        // Track playback progress
        if (isPlaying) {
            playbackMetrics.totalPlayTime += MONITORING_INTERVAL_MS
        }

        // Calculate playback efficiency
        if (duration > 0) {
            playbackMetrics.playbackProgress = (currentPosition.toFloat() / duration.toFloat()) * 100f
        }

        addMetric("playback_position", PerformanceMetric(timestamp, currentPosition.toFloat()))
        addMetric("playback_state", PerformanceMetric(timestamp, playbackState.toFloat()))
    }

    private fun collectBufferMetrics(player: ExoPlayer, timestamp: Long) {
        val bufferedPosition = player.bufferedPosition
        val currentPosition = player.currentPosition
        val bufferHealth = if (currentPosition > 0) {
            ((bufferedPosition - currentPosition).toFloat() / 1000f) // Buffer in seconds
        } else {
            0f
        }

        playbackMetrics.averageBufferHealth =
            (playbackMetrics.averageBufferHealth + bufferHealth) / 2f

        addMetric("buffer_health", PerformanceMetric(timestamp, bufferHealth))
        addMetric("buffered_position", PerformanceMetric(timestamp, bufferedPosition.toFloat()))
    }

    @OptIn(UnstableApi::class)
    private fun collectQualityMetrics(player: ExoPlayer, timestamp: Long) {
        val videoFormat = player.videoFormat
        videoFormat?.let { format ->
            val resolution = format.width * format.height
            val bitrate = format.bitrate.toFloat()
            val frameRate = format.frameRate

            playbackMetrics.currentResolution = "${format.width}x${format.height}"
            playbackMetrics.currentBitrate = bitrate.toInt()

            addMetric("video_resolution", PerformanceMetric(timestamp, resolution.toFloat()))
            addMetric("video_bitrate", PerformanceMetric(timestamp, bitrate))
            addMetric("video_framerate", PerformanceMetric(timestamp, frameRate))
        }
    }

    private fun collectMemoryMetrics(timestamp: Long) {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsagePercent = (usedMemory.toFloat() / maxMemory.toFloat()) * 100f

        addMetric("memory_usage", PerformanceMetric(timestamp, memoryUsagePercent))
        addMetric("memory_used_mb", PerformanceMetric(timestamp, usedMemory.toFloat() / (1024 * 1024)))
    }

    private fun collectBatteryMetrics(timestamp: Long) {
        // Battery metrics would require additional permissions and implementation
        // This is a placeholder for battery usage tracking
        addMetric("battery_usage", PerformanceMetric(timestamp, 0f))
    }

    private fun collectNetworkMetrics(timestamp: Long) {
        // Network metrics collection would integrate with network monitoring
        // This is a placeholder for network performance tracking
        val networkMetric = NetworkMetric(
            timestamp = timestamp,
            downloadSpeed = 0f, // Would be calculated from actual network data
            uploadSpeed = 0f,
            latency = 0f,
            packetLoss = 0f,
            connectionType = "unknown"
        )

        networkMetrics["current"] = networkMetric
    }

    private fun addMetric(key: String, metric: PerformanceMetric) {
        val metrics = performanceMetrics.getOrPut(key) { mutableListOf() }
        metrics.add(metric)

        // Clean up old metrics (keep only last 24 hours)
        val cutoffTime = System.currentTimeMillis() - (METRICS_RETENTION_HOURS * 60 * 60 * 1000)
        metrics.removeAll { it.timestamp < cutoffTime }
    }

    fun getPerformanceReport(): PerformanceReport {
        val currentTime = System.currentTimeMillis()
        val sessionDuration = currentTime - playbackMetrics.sessionStartTime

        return PerformanceReport(
            sessionDuration = sessionDuration,
            totalPlayTime = playbackMetrics.totalPlayTime,
            totalBufferingTime = playbackMetrics.totalBufferingTime,
            bufferingEvents = playbackMetrics.bufferingEvents,
            averageBufferHealth = playbackMetrics.averageBufferHealth,
            playbackProgress = playbackMetrics.playbackProgress,
            currentResolution = playbackMetrics.currentResolution,
            currentBitrate = playbackMetrics.currentBitrate,
            stateChanges = playbackMetrics.stateChanges,
            memoryUsage = getAverageMetric("memory_usage"),
            networkQuality = calculateNetworkQuality(),
            qualityAdaptations = calculateQualityAdaptations(),
            userExperienceScore = calculateUserExperienceScore()
        )
    }

    private fun getAverageMetric(key: String): Float {
        val metrics = performanceMetrics[key] ?: return 0f
        return if (metrics.isNotEmpty()) {
            metrics.map { it.value }.average().toFloat()
        } else {
            0f
        }
    }

    private fun calculateNetworkQuality(): String {
        val avgBitrate = getAverageMetric("video_bitrate")
        val bufferHealth = playbackMetrics.averageBufferHealth

        return when {
            avgBitrate > 5000000 && bufferHealth > 10 -> "Excellent"
            avgBitrate > 2000000 && bufferHealth > 5 -> "Good"
            avgBitrate > 1000000 && bufferHealth > 2 -> "Fair"
            else -> "Poor"
        }
    }

    private fun calculateQualityAdaptations(): Int {
        val bitrateMetrics = performanceMetrics["video_bitrate"] ?: return 0
        var adaptations = 0
        var lastBitrate = 0f

        for (metric in bitrateMetrics) {
            if (lastBitrate > 0 && kotlin.math.abs(metric.value - lastBitrate) > 500000) {
                adaptations++
            }
            lastBitrate = metric.value
        }

        return adaptations
    }

    private fun calculateUserExperienceScore(): Float {
        val bufferingRatio = if (playbackMetrics.totalPlayTime > 0) {
            playbackMetrics.totalBufferingTime.toFloat() / playbackMetrics.totalPlayTime.toFloat()
        } else {
            0f
        }

        val bufferHealthScore = minOf(playbackMetrics.averageBufferHealth / 10f, 1f)
        val qualityStabilityScore = maxOf(0f, 1f - (calculateQualityAdaptations() / 10f))
        val playbackStabilityScore = maxOf(0f, 1f - bufferingRatio)

        return ((bufferHealthScore + qualityStabilityScore + playbackStabilityScore) / 3f * 100f).roundToInt().toFloat()
    }

    private fun generateSessionReport() {
        CoroutineScope(Dispatchers.IO).launch {
            val report = getPerformanceReport()
            Log.d(TAG, "Session Performance Report:")
            Log.d(TAG, "Duration: ${report.sessionDuration / 1000}s")
            Log.d(TAG, "Play Time: ${report.totalPlayTime / 1000}s")
            Log.d(TAG, "Buffering Time: ${report.totalBufferingTime / 1000}s")
            Log.d(TAG, "Buffering Events: ${report.bufferingEvents}")
            Log.d(TAG, "Average Buffer Health: ${report.averageBufferHealth}s")
            Log.d(TAG, "Network Quality: ${report.networkQuality}")
            Log.d(TAG, "User Experience Score: ${report.userExperienceScore}")

            // Save report to local storage or send to analytics service
            saveReportToStorage(report)
        }
    }

    private fun saveReportToStorage(report: PerformanceReport) {
        // Implementation to save performance report to local storage
        // This could be used for offline analysis or sent to analytics service
    }

    // Data classes for metrics
    data class PerformanceMetric(
        val timestamp: Long,
        val value: Float
    )

    data class NetworkMetric(
        val timestamp: Long,
        val downloadSpeed: Float,
        val uploadSpeed: Float,
        val latency: Float,
        val packetLoss: Float,
        val connectionType: String
    )

    data class PlaybackMetrics(
        var sessionStartTime: Long = 0,
        var sessionEndTime: Long = 0,
        var totalPlayTime: Long = 0,
        var totalBufferingTime: Long = 0,
        var bufferingEvents: Int = 0,
        var bufferingStartTime: Long = 0,
        var averageBufferHealth: Float = 0f,
        var playbackProgress: Float = 0f,
        var currentResolution: String = "",
        var currentBitrate: Int = 0,
        var stateChanges: Int = 0,
        var lastPlaybackState: Int = -1
    )

    data class PerformanceReport(
        val sessionDuration: Long,
        val totalPlayTime: Long,
        val totalBufferingTime: Long,
        val bufferingEvents: Int,
        val averageBufferHealth: Float,
        val playbackProgress: Float,
        val currentResolution: String,
        val currentBitrate: Int,
        val stateChanges: Int,
        val memoryUsage: Float,
        val networkQuality: String,
        val qualityAdaptations: Int,
        val userExperienceScore: Float
    ) {
        val bufferingRatio: Float
            get() = if (totalPlayTime > 0) {
                totalBufferingTime.toFloat() / totalPlayTime.toFloat()
            } else {
                0f
            }

        val playbackEfficiency: Float
            get() = if (sessionDuration > 0) {
                totalPlayTime.toFloat() / sessionDuration.toFloat()
            } else {
                0f
            }
    }

    /**
     * Real-time performance alerts
     */

    fun setPerformanceThresholds(
        maxBufferingRatio: Float = 0.1f,
        minBufferHealth: Float = 2f,
        maxMemoryUsage: Float = 80f
    ) {
        // Set thresholds for performance alerts
    }

    fun enablePerformanceAlerts(enabled: Boolean) {
        // Enable/disable real-time performance alerts
    }

    /**
     * Performance optimization suggestions
     */

    fun getOptimizationSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        val report = getPerformanceReport()

        if (report.bufferingRatio > 0.1f) {
            suggestions.add("Consider lowering video quality to reduce buffering")
        }

        if (report.averageBufferHealth < 2f) {
            suggestions.add("Check network connection for better streaming performance")
        }

        if (report.memoryUsage > 80f) {
            suggestions.add("Close other apps to free up memory")
        }

        if (report.qualityAdaptations > 10) {
            suggestions.add("Network connection is unstable, consider using WiFi")
        }

        return suggestions
    }
}

