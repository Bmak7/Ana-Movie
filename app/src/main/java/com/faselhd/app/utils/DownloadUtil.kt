package com.faselhd.app.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

object DownloadUtil {

    private const val TAG = "EnhancedDownloadUtil"
    private const val CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500MB default cache size
    private const val MAX_CACHE_SIZE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB max cache size

    @Volatile
    private var downloadManager: DownloadManager? = null

    @Volatile
    private var downloadCache: Cache? = null

    @Volatile
    private var databaseProvider: DatabaseProvider? = null

    @Volatile
    private var cacheSize: Long = CACHE_SIZE_BYTES

    @OptIn(UnstableApi::class)
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        return databaseProvider ?: synchronized(this) {
            StandaloneDatabaseProvider(context).also {
                databaseProvider = it
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun getDownloadCache(context: Context): Cache {
        return downloadCache ?: synchronized(this) {
            val appName = "Ana Movie"
            val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val cacheDir = File(downloadDirectory, appName)

            // Ensure the directory exists
            if (!cacheDir.exists()) {
                val created = cacheDir.mkdirs()
                Log.d(TAG, "Cache directory created: $created at ${cacheDir.absolutePath}")
            }

            // Check available space and adjust cache size if necessary
            val availableSpace = cacheDir.freeSpace
            val adjustedCacheSize = minOf(cacheSize, availableSpace / 2) // Use at most half of available space

            Log.d(TAG, "Cache directory: ${cacheDir.absolutePath}")
            Log.d(TAG, "Available space: ${availableSpace / (1024 * 1024)}MB")
            Log.d(TAG, "Cache size: ${adjustedCacheSize / (1024 * 1024)}MB")

            val dbProvider = getDatabaseProvider(context)

            // Use LeastRecentlyUsedCacheEvictor for better cache management
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(adjustedCacheSize)

            SimpleCache(cacheDir, cacheEvictor, dbProvider).also {
                downloadCache = it
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun getCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val upstreamFactory = createEnhancedHttpDataSourceFactory()
        val cache = getDownloadCache(context)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null) // Use default
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @OptIn(UnstableApi::class)
    fun getReadOnlyCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val cache = getDownloadCache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null) // No network access for offline mode
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @OptIn(UnstableApi::class)
    fun getOfflineDataSourceFactory(context: Context): CacheDataSource.Factory {
        val cache = getDownloadCache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null) // Strictly offline
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @OptIn(UnstableApi::class)
    private fun createEnhancedHttpDataSourceFactory(): DataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setUserAgent("Ana Movie Player/1.0")
            .setConnectTimeoutMs(30000) // 30 seconds
            .setReadTimeoutMs(30000) // 30 seconds
            .setAllowCrossProtocolRedirects(true)
    }

    @OptIn(UnstableApi::class)
    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            val dbProvider = getDatabaseProvider(context)
            val downloadIndex = DefaultDownloadIndex(dbProvider)

            // Enhanced data source factory for downloads
            val dataSourceFactory = DefaultDataSource.Factory(
                context,
                createEnhancedHttpDataSourceFactory()
            )

            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(getDownloadCache(context))
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val downloaderFactory = DefaultDownloaderFactory(
                cacheDataSourceFactory,
                Executors.newFixedThreadPool(3) // Allow 3 concurrent downloads
            )

            DownloadManager(
                context,
                downloadIndex,
                downloaderFactory
            ).apply {
                // Set maximum parallel downloads
                maxParallelDownloads = 3
                // Set minimum retry count
                minRetryCount = 3
            }.also {
                downloadManager = it
            }
        }
    }

    /**
     * Enhanced cache management functions
     */

    @OptIn(UnstableApi::class)
    fun getCacheSize(context: Context): Long {
        return try {
            getDownloadCache(context).cacheSpace
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache size", e)
            0L
        }
    }

    @OptIn(UnstableApi::class)
    fun clearCache(context: Context): Boolean {
        return try {
            val cache = getDownloadCache(context)
            val keys = cache.keys
            for (key in keys) {
                cache.removeResource(key)
            }
            Log.d(TAG, "Cache cleared successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
            false
        }
    }

    @OptIn(UnstableApi::class)
    fun getCacheStats(context: Context): CacheStats {
        return try {
            val cache = getDownloadCache(context)
            val cacheSpace = cache.cacheSpace
            val keys = cache.keys
            val fileCount = keys.size

            CacheStats(
                totalSize = cacheSpace,
                fileCount = fileCount,
                maxSize = cacheSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache stats", e)
            CacheStats(0, 0, cacheSize)
        }
    }

    fun setCacheSize(newSize: Long) {
        cacheSize = minOf(newSize, MAX_CACHE_SIZE_BYTES)
        Log.d(TAG, "Cache size set to: ${cacheSize / (1024 * 1024)}MB")
    }

    @OptIn(UnstableApi::class)
    fun optimizeCache(context: Context) {
        try {
            val cache = getDownloadCache(context)
            val currentSize = cache.cacheSpace

            if (currentSize > cacheSize * 0.9) { // If cache is 90% full
                Log.d(TAG, "Cache optimization triggered. Current size: ${currentSize / (1024 * 1024)}MB")

                // The LeastRecentlyUsedCacheEvictor will automatically handle this
                // But we can also manually trigger cleanup if needed
                val keys = cache.keys.toList()
                val sortedKeys = keys.sortedBy { key ->
                    try {
                        cache.getCachedSpans(key).firstOrNull()?.lastTouchTimestamp ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }

                // Remove oldest 20% of files if cache is too full
                val filesToRemove = (sortedKeys.size * 0.2).toInt()
                for (i in 0 until filesToRemove) {
                    cache.removeResource(sortedKeys[i])
                }

                Log.d(TAG, "Cache optimization completed. Removed $filesToRemove files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing cache", e)
        }
    }

    /**
     * Network condition aware data source factory
     */
    @OptIn(UnstableApi::class)
    fun getAdaptiveDataSourceFactory(context: Context, isLowBandwidth: Boolean = false): CacheDataSource.Factory {
        val httpFactory = if (isLowBandwidth) {
            DefaultHttpDataSource.Factory()
                .setUserAgent("Ana Movie Player/1.0")
                .setConnectTimeoutMs(60000) // Longer timeout for slow connections
                .setReadTimeoutMs(60000)
                .setAllowCrossProtocolRedirects(true)
        } else {
            createEnhancedHttpDataSourceFactory()
        }

        val cache = getDownloadCache(context)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(
                if (isLowBandwidth) {
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE
                } else {
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                }
            )
    }

    /**
     * Preload content for better user experience
     */
    @OptIn(UnstableApi::class)
    fun preloadContent(context: Context, url: String, sizeBytes: Long = 5 * 1024 * 1024) {
        try {
            val cache = getDownloadCache(context)
            val dataSourceFactory = getCacheDataSourceFactory(context)

            // This would typically be done in a background service
            Log.d(TAG, "Preloading content: $url")
            // Implementation would depend on specific requirements
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading content", e)
        }
    }

    /**
     * Check if content is available offline
     */
    @OptIn(UnstableApi::class)
    fun isContentCached(context: Context, url: String): Boolean {
        return try {
            val cache = getDownloadCache(context)
            val cachedSpans = cache.getCachedSpans(url)
            cachedSpans.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cached content", e)
            false
        }
    }

    /**
     * Get cached content percentage
     */
    @OptIn(UnstableApi::class)
    fun getCachedPercentage(context: Context, url: String, totalLength: Long): Float {
        return try {
            val cache = getDownloadCache(context)
            val cachedSpans = cache.getCachedSpans(url)

            var cachedBytes = 0L
            for (span in cachedSpans) {
                cachedBytes += span.length
            }

            if (totalLength > 0) {
                (cachedBytes.toFloat() / totalLength.toFloat()) * 100f
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cached percentage", e)
            0f
        }
    }

    data class CacheStats(
        val totalSize: Long,
        val fileCount: Int,
        val maxSize: Long
    ) {
        val usagePercentage: Float
            get() = if (maxSize > 0) (totalSize.toFloat() / maxSize.toFloat()) * 100f else 0f

        val totalSizeMB: Long
            get() = totalSize / (1024 * 1024)

        val maxSizeMB: Long
            get() = maxSize / (1024 * 1024)
    }
}


//package com.faselhd.app.utils
//
//import android.content.Context
//import android.os.Environment
//import androidx.annotation.OptIn
//import androidx.media3.common.util.UnstableApi
//import androidx.media3.database.DatabaseProvider
//import androidx.media3.database.StandaloneDatabaseProvider
//import androidx.media3.datasource.DataSource
//import androidx.media3.datasource.DefaultDataSource
//import androidx.media3.datasource.cache.Cache
//import androidx.media3.datasource.cache.CacheDataSource
//import androidx.media3.datasource.cache.NoOpCacheEvictor
//import androidx.media3.datasource.cache.SimpleCache
//import androidx.media3.exoplayer.offline.DefaultDownloadIndex
//import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
//import androidx.media3.exoplayer.offline.DownloadManager
//import java.io.File
//import java.util.concurrent.Executors
//
//object DownloadUtil {
//
//    @Volatile
//    private var downloadManager: DownloadManager? = null
//
//    @Volatile
//    private var downloadCache: Cache? = null
//
//    @Volatile
//    private var databaseProvider: DatabaseProvider? = null
//
//    @OptIn(UnstableApi::class)
//    private fun getDatabaseProvider(context: Context): DatabaseProvider {
//        return databaseProvider ?: synchronized(this) {
//            StandaloneDatabaseProvider(context).also {
//                databaseProvider = it
//            }
//        }
//    }
//
//    @OptIn(UnstableApi::class)
//    fun getDownloadCache(context: Context): Cache {
//        return downloadCache ?: synchronized(this) {
//
//            // *** THIS IS THE CRITICAL CHANGE ***
//            // 1. Define the app-specific sub-folder name.
//            val appName = "Ana Movie"
//
//            // 2. Get the path to the public "Downloads" directory.
//            val downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
//
//            // 3. Create the full path for your app's downloads.
//            // Resulting path will be /storage/emulated/0/Download/Ana Movie/
//            val cacheDir = File(downloadDirectory, appName)
//
//            // Ensure the directory exists.
//            if (!cacheDir.exists()) {
//                cacheDir.mkdirs()
//            }
//
//            // 4. Use this new path to create the SimpleCache.
//            val dbProvider = getDatabaseProvider(context)
//            SimpleCache(cacheDir, NoOpCacheEvictor(), dbProvider).also {
//                downloadCache = it
//            }
//        }
//    }
//
//    @OptIn(UnstableApi::class)
//    fun getCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
//        val upstreamFactory = DefaultDataSource.Factory(context)
//        val cache = getDownloadCache(context)
//        return CacheDataSource.Factory()
//            .setCache(cache)
//            .setUpstreamDataSourceFactory(upstreamFactory)
//    }
//
//    // ADD THIS NEW HELPER FUNCTION
//    fun getReadOnlyCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
//        val cache = getDownloadCache(context)
//        return CacheDataSource.Factory()
//            .setCache(cache)
//            // By setting the upstream factory to null, we PREVENT any network access.
//            // If the content is not in the cache, playback will fail (which is correct for offline mode).
//            .setUpstreamDataSourceFactory(null)
//    }
//    @OptIn(UnstableApi::class)
//    fun getDownloadManager(context: Context): DownloadManager {
//        return downloadManager ?: synchronized(this) {
//            val dbProvider = getDatabaseProvider(context)
//            val downloadIndex = DefaultDownloadIndex(dbProvider)
//            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
//
//            // The CacheDataSource.Factory now uses the cache pointing to external storage.
//            val cacheDataSourceFactory = CacheDataSource.Factory()
//                .setCache(getDownloadCache(context))
//                .setUpstreamDataSourceFactory(dataSourceFactory)
//
//            val downloaderFactory = DefaultDownloaderFactory(
//                cacheDataSourceFactory,
//                Executors.newFixedThreadPool(3)
//            )
//
//            DownloadManager(
//                context,
//                downloadIndex,
//                downloaderFactory
//            ).also {
//                downloadManager = it
//            }
//        }
//    }
//}