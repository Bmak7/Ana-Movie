package com.faselhd.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Utility class for managing video cache settings and operations
 */
object VideoCacheManager {

    private const val PREFS_NAME = "video_cache_prefs"
    private const val KEY_CACHE_ENABLED = "cache_enabled"
    private const val KEY_CACHE_SIZE_MB = "cache_size_mb"
    private const val KEY_CACHE_WIFI_ONLY = "cache_wifi_only"
    private const val KEY_AUTO_CACHE_NEXT_EPISODE = "auto_cache_next_episode"

    // Default cache settings
    private const val DEFAULT_CACHE_SIZE_MB = 500L
    private const val DEFAULT_CACHE_ENABLED = true
    private const val DEFAULT_WIFI_ONLY = true
    private const val DEFAULT_AUTO_CACHE_NEXT = true

    private var simpleCache: SimpleCache? = null

    /**
     * Initialize cache with current settings
     */
    @OptIn(UnstableApi::class)
    fun initializeCache(context: Context): SimpleCache? {
        if (simpleCache != null) return simpleCache

        val prefs = getPreferences(context)
        if (!prefs.getBoolean(KEY_CACHE_ENABLED, DEFAULT_CACHE_ENABLED)) {
            return null
        }

        return try {
            val cacheDir = File(context.cacheDir, "video_cache")
            val cacheSizeBytes = prefs.getLong(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB) * 1024 * 1024
            val evictor = LeastRecentlyUsedCacheEvictor(cacheSizeBytes)

            simpleCache = SimpleCache(cacheDir, evictor)
            Log.d("VideoCacheManager", "Cache initialized with size: ${cacheSizeBytes / (1024 * 1024)}MB")
            simpleCache
        } catch (e: Exception) {
            Log.e("VideoCacheManager", "Failed to initialize cache", e)
            null
        }
    }

    /**
     * Get current cache instance
     */
    fun getCache(): SimpleCache? = simpleCache

    /**
     * Check if caching is enabled
     */
    fun isCacheEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CACHE_ENABLED, DEFAULT_CACHE_ENABLED)
    }

    /**
     * Enable or disable caching
     */
    fun setCacheEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CACHE_ENABLED, enabled)
            .apply()

        if (!enabled) {
            clearCache(context)
        } else {
            initializeCache(context)
        }
    }

    /**
     * Get cache size in MB
     */
    fun getCacheSizeMB(context: Context): Long {
        return getPreferences(context).getLong(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB)
    }

    /**
     * Set cache size in MB
     */
    fun setCacheSizeMB(context: Context, sizeMB: Long) {
        getPreferences(context).edit()
            .putLong(KEY_CACHE_SIZE_MB, sizeMB)
            .apply()

        // Reinitialize cache with new size
        clearCache(context)
        initializeCache(context)
    }

    /**
     * Check if caching should only work on WiFi
     */
    fun isCacheWifiOnly(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CACHE_WIFI_ONLY, DEFAULT_WIFI_ONLY)
    }

    /**
     * Set WiFi-only caching preference
     */
    fun setCacheWifiOnly(context: Context, wifiOnly: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CACHE_WIFI_ONLY, wifiOnly)
            .apply()
    }

    /**
     * Check if next episode should be auto-cached
     */
    fun isAutoCacheNextEpisode(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_CACHE_NEXT_EPISODE, DEFAULT_AUTO_CACHE_NEXT)
    }

    /**
     * Set auto-cache next episode preference
     */
    fun setAutoCacheNextEpisode(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_CACHE_NEXT_EPISODE, enabled)
            .apply()
    }

    /**
     * Clear all cached data
     */
    @OptIn(UnstableApi::class)
    fun clearCache(context: Context) {
        try {
            simpleCache?.release()
            simpleCache = null

            val cacheDir = File(context.cacheDir, "video_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }

            Log.d("VideoCacheManager", "Cache cleared successfully")
        } catch (e: Exception) {
            Log.e("VideoCacheManager", "Error clearing cache", e)
        }
    }

    /**
     * Get current cache size on disk in bytes
     */
    fun getCurrentCacheSizeBytes(context: Context): Long {
        return try {
            val cacheDir = File(context.cacheDir, "video_cache")
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e("VideoCacheManager", "Error getting cache size", e)
            0L
        }
    }

    /**
     * Get formatted cache size string
     */
    fun getFormattedCacheSize(context: Context): String {
        val sizeBytes = getCurrentCacheSizeBytes(context)
        val sizeMB = sizeBytes / (1024 * 1024)
        val maxSizeMB = getCacheSizeMB(context)

        return "$sizeMB MB / $maxSizeMB MB"
    }

    /**
     * Get cache usage percentage
     */
    fun getCacheUsagePercentage(context: Context): Int {
        val currentSize = getCurrentCacheSizeBytes(context)
        val maxSize = getCacheSizeMB(context) * 1024 * 1024

        return if (maxSize > 0) {
            ((currentSize * 100) / maxSize).toInt()
        } else {
            0
        }
    }

    /**
     * Check if cache directory exists and is writable
     */
    fun isCacheDirectoryHealthy(context: Context): Boolean {
        return try {
            val cacheDir = File(context.cacheDir, "video_cache")
            cacheDir.mkdirs()
            cacheDir.exists() && cacheDir.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Optimize cache by removing oldest entries if needed
     */
    fun optimizeCache(context: Context) {
        try {
            val currentSize = getCurrentCacheSizeBytes(context)
            val maxSize = getCacheSizeMB(context) * 1024 * 1024

            if (currentSize > maxSize * 0.9) { // If cache is 90% full
                // Force eviction of some entries
                simpleCache?.let { cache ->
                    val cacheDir = File(context.cacheDir, "video_cache")
                    val files = cacheDir.listFiles()?.sortedBy { it.lastModified() }

                    var freedSpace = 0L
                    val targetFreeSpace = maxSize * 0.2 // Free up 20% of cache

                    files?.forEach { file ->
                        if (freedSpace < targetFreeSpace) {
                            freedSpace += file.length()
                            file.delete()
                        }
                    }

                    Log.d("VideoCacheManager", "Cache optimized, freed ${freedSpace / (1024 * 1024)}MB")

                }

            }
        } catch (e: Exception) {
            Log.e("VideoCacheManager", "Error optimizing cache", e)
        }
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Release cache resources (call this when app is destroyed)
     */
    @OptIn(UnstableApi::class)
    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
        } catch (e: Exception) {
            Log.e("VideoCacheManager", "Error releasing cache", e)
        }
    }
}