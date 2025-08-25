// Enhanced DownloadWorker.kt - Handles both video and audio downloads

package com.faselhd.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.myapplication.R
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.Download
import com.faselhd.app.models.DownloadState
import com.faselhd.app.network.sources.FaselHDSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.utils.M3u8Helper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = AppDatabase.getDatabase(context)
    private val faselHDSource = FaselHDSource(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sourceManager by lazy { SourceManager(applicationContext) }

    // Enhanced HTTP client for direct downloads
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            chain.proceed(newRequest)
        }
        .build()

    companion object {
        const val KEY_EPISODE_URL = "key_episode_url"
        const val KEY_VIDEO_URL = "key_video_url"
        const val KEY_EPISODE_NAME = "key_episode_name"
        const val KEY_ANIME_TITLE = "key_anime_title"
        const val KEY_THUMBNAIL_URL = "key_thumbnail_url"
        const val NOTIFICATION_CHANNEL_ID = "download_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Downloads"
        const val KEY_HEADERS_JSON = "key_headers_json"
        const val KEY_IS_AUDIO = "key_is_audio"
        const val CHUNK_SIZE = 8192 // 8KB chunks for direct downloads
    }

    override suspend fun doWork(): Result {
        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
        var videoUrl = inputData.getString(KEY_VIDEO_URL)
        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        val isAudio = inputData.getBoolean(KEY_IS_AUDIO, false)
        val notificationId = episodeUrl.hashCode()
        val headersJson = inputData.getString(KEY_HEADERS_JSON)
        val headers = headersJson?.let {
            Gson().fromJson(it, Map::class.java) as? Map<String, String>
        } ?: emptyMap()

        // Define the destination file with appropriate extension
        val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val folderName = if (isAudio) "Ana Movie/Audio" else "Ana Movie/Video"
        val animeDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$folderName/$safeAnimeTitle")
        if (!animeDir.exists()) { animeDir.mkdirs() }

        // Determine file extension based on content type
        val fileExtension = if (isAudio) {
            getAudioFileExtension(videoUrl!!, episodeName)
        } else {
            getVideoFileExtension(videoUrl!!, episodeName)
        }

        val destinationFile = File(animeDir, "$safeEpisodeName.$fileExtension")

        return withContext(Dispatchers.IO) {
            try {
                // For audio downloads, use the provided URL directly
                if (isAudio && !videoUrl.isNullOrBlank()) {
                    Log.d("DownloadWorker", "Starting audio download for '$episodeName'")
                } else if (videoUrl.isNullOrBlank()) {
                    Log.d("DownloadWorker", "Video URL for '$episodeName' is missing. Fetching list...")
                    updateNotification(notificationId, episodeName, "Finding video link...", 0, true)

                    val videos = sourceManager.fetchVideoList(episodeUrl)
                    if (videos.isNotEmpty()) {
                        videoUrl = videos.first().url
                    } else {
                        throw IllegalStateException("No video sources found for the episode.")
                    }
                }

                val download = Download(
                    episodeUrl = episodeUrl,
                    animeTitle = animeTitle,
                    episodeName = episodeName,
                    thumbnailUrl = thumbnailUrl,
                    downloadState = DownloadState.QUEUED,
                    mediaUri = videoUrl!!,
                )
                db.downloadDao().upsert(download)

                val foregroundInfo = createForegroundInfo(notificationId, episodeName, "Starting download...")
                setForeground(foregroundInfo)

                // Update state to DOWNLOADING
                db.downloadDao().updateState(episodeUrl, DownloadState.DOWNLOADING)

                // Determine download method based on URL type and content
                val downloadResult = if (isAudio) {
                    downloadAudioFile(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
                } else {
                    when {
                        isM3u8Url(videoUrl!!) -> downloadM3u8Stream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
                        isDirectVideoUrl(videoUrl!!) -> downloadDirectVideo(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
                        else -> downloadGenericStream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
                    }
                }

                if (downloadResult) {
                    Log.d("DownloadWorker", "Download finished successfully for '$episodeName'")
                    db.downloadDao().updateOnSuccess(episodeUrl, destinationFile.absolutePath)
                    showFinalNotification(notificationId, episodeName, "Download complete", true)
                    Result.success()
                } else {
                    throw Exception("Download failed")
                }

            } catch (e: Exception) {
                Log.e("DownloadWorker", "Download failed for '$episodeName'", e)
                destinationFile.delete() // Clean up partially downloaded file
                db.downloadDao().updateState(episodeUrl, DownloadState.FAILED)
                showFinalNotification(notificationId, episodeName, "Download failed: ${e.message}", false)
                Result.failure()
            }
        }
    }

    private fun isM3u8Url(url: String): Boolean {
        return url.contains(".m3u8") || url.contains("hls") || url.contains("playlist")
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") || url.contains(".mov")
    }

    private fun isAudioUrl(url: String): Boolean {
        return url.contains(".mp3") || url.contains(".wav") || url.contains(".aac") ||
                url.contains(".ogg") || url.contains(".m4a") || url.contains(".flac")
    }

    private fun getAudioFileExtension(url: String, episodeName: String): String {
        // Try to extract extension from URL
        val urlExtension = url.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (urlExtension in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac")) {
            return urlExtension!!
        }

        // Try to extract extension from episode name
        val nameExtension = episodeName.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (nameExtension in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac")) {
            return nameExtension!!
        }

        // Default to mp3
        return "mp3"
    }

    private fun getVideoFileExtension(url: String, episodeName: String): String {
        // Try to extract extension from URL
        val urlExtension = url.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (urlExtension in listOf("mp4", "mkv", "avi", "mov", "webm")) {
            return urlExtension!!
        }

        // Try to extract extension from episode name
        val nameExtension = episodeName.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (nameExtension in listOf("mp4", "mkv", "avi", "mov", "webm")) {
            return nameExtension!!
        }

        // Default to mp4
        return "mp4"
    }

    private suspend fun downloadAudioFile(
        audioUrl: String,
        destinationFile: File,
        notificationId: Int,
        episodeName: String,
        episodeUrl: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            Log.d("DownloadWorker", "Starting audio download for $episodeName")

            val requestBuilder = Request.Builder().url(audioUrl)
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = downloadClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val contentLength = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream()
                ?: throw Exception("Failed to get response stream")

            var downloadedBytes = 0L
            var lastProgress = -1
            val buffer = ByteArray(CHUNK_SIZE)

            FileOutputStream(destinationFile).use { outputStream ->
                inputStream.use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                            throw InterruptedException("Download was cancelled/stopped by user")
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (contentLength > 0) {
                            val progress = ((downloadedBytes * 100) / contentLength).toInt()
                            if (progress > lastProgress) {
                                db.downloadDao().updateProgress(episodeUrl, progress)
                                val content = "Downloaded ${formatFileSize(downloadedBytes)} of ${formatFileSize(contentLength)}"
                                updateNotification(notificationId, episodeName, content, progress, false)
                                lastProgress = progress
                            }
                        } else {
                            // Unknown file size - show indeterminate progress
                            val content = "Downloaded ${formatFileSize(downloadedBytes)}"
                            updateNotification(notificationId, episodeName, content, 0, true)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Audio download failed", e)
            false
        }
    }

    private suspend fun downloadM3u8Stream(
        videoUrl: String,
        destinationFile: File,
        notificationId: Int,
        episodeName: String,
        episodeUrl: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            val downloadData = M3u8Helper.HlsDownloadData(videoUrl)
            val totalSegments = downloadData.tsLinks.size
            var downloadedSegments = 0
            var lastProgress = -1

            Log.d("DownloadWorker", "Starting M3U8 download of $totalSegments segments for $episodeName.")

            FileOutputStream(destinationFile).use { outputStream ->
                downloadData.tsLinks.forEachIndexed { index, segmentUrl ->
                    if (isStopped) {
                        db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                        throw InterruptedException("Download was cancelled/stopped by user")
                    }

                    val segmentData = M3u8Helper.downloadSegment(segmentUrl, downloadData, index)
                    outputStream.write(segmentData)
                    downloadedSegments++

                    val progress = (downloadedSegments * 100) / totalSegments
                    if (progress > lastProgress) {
                        db.downloadDao().updateProgress(episodeUrl, progress)
                        val content = "Downloaded $downloadedSegments of $totalSegments segments"
                        updateNotification(notificationId, episodeName, content, progress, false)
                        lastProgress = progress
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("DownloadWorker", "M3U8 download failed", e)
            false
        }
    }

    private suspend fun downloadDirectVideo(
        videoUrl: String,
        destinationFile: File,
        notificationId: Int,
        episodeName: String,
        episodeUrl: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            Log.d("DownloadWorker", "Starting direct video download for $episodeName")

            val requestBuilder = Request.Builder().url(videoUrl)
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val response = downloadClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val contentLength = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream()
                ?: throw Exception("Failed to get response stream")

            var downloadedBytes = 0L
            var lastProgress = -1
            val buffer = ByteArray(CHUNK_SIZE)

            FileOutputStream(destinationFile).use { outputStream ->
                inputStream.use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                            throw InterruptedException("Download was cancelled/stopped by user")
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (contentLength > 0) {
                            val progress = ((downloadedBytes * 100) / contentLength).toInt()
                            if (progress > lastProgress) {
                                db.downloadDao().updateProgress(episodeUrl, progress)
                                val content = "Downloaded ${formatFileSize(downloadedBytes)} of ${formatFileSize(contentLength)}"
                                updateNotification(notificationId, episodeName, content, progress, false)
                                lastProgress = progress
                            }
                        } else {
                            // Unknown file size - show indeterminate progress
                            val content = "Downloaded ${formatFileSize(downloadedBytes)}"
                            updateNotification(notificationId, episodeName, content, 0, true)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Direct video download failed", e)
            false
        }
    }

    private suspend fun downloadGenericStream(
        videoUrl: String,
        destinationFile: File,
        notificationId: Int,
        episodeName: String,
        episodeUrl: String,
        headers: Map<String, String>
    ): Boolean {
        return try {
            Log.d("DownloadWorker", "Starting generic stream download for $episodeName")

            // First try as direct download
            val directResult = downloadDirectVideo(videoUrl, destinationFile, notificationId, episodeName, episodeUrl, headers)
            if (directResult) {
                return true
            }

            // If direct download fails, try as M3U8
            Log.d("DownloadWorker", "Direct download failed, trying M3U8 method")
            downloadM3u8Stream(videoUrl, destinationFile, notificationId, episodeName, episodeUrl, headers)

        } catch (e: Exception) {
            Log.e("DownloadWorker", "Generic stream download failed", e)
            false
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }

    private fun updateNotification(notificationId: Int, title: String, content: String, progress: Int, isIndeterminate: Boolean) {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px)
            .setOngoing(true)
            .setProgress(100, progress, isIndeterminate)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun createForegroundInfo(notificationId: Int, title: String, content: String): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    private fun showFinalNotification(notificationId: Int, title: String, content: String, isSuccess: Boolean) {
        notificationManager.cancel(notificationId)

        val finalNotification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(if (isSuccess) R.drawable.download_done_24px else R.drawable.file_download_off_24px)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 1, finalNotification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}

//// Enhanced DownloadWorker.kt - Handles all video source types
//
//package com.faselhd.app.workers
//
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.content.Context
//import android.os.Build
//import android.os.Environment
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import androidx.work.CoroutineWorker
//import androidx.work.ForegroundInfo
//import androidx.work.WorkerParameters
//import com.example.myapplication.R
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.models.Download
//import com.faselhd.app.models.DownloadState
//import com.faselhd.app.network.sources.FaselHDSource
//import com.faselhd.app.network.SourceManager
//import com.faselhd.app.utils.M3u8Helper
//import com.google.gson.Gson
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import java.io.File
//import java.io.FileOutputStream
//import java.io.InputStream
//import java.lang.IllegalStateException
//import java.net.URL
//import java.util.concurrent.TimeUnit
//
//class DownloadWorker(
//    private val context: Context,
//    workerParams: WorkerParameters
//) : CoroutineWorker(context, workerParams) {
//
//    private val db = AppDatabase.getDatabase(context)
//    private val faselHDSource = FaselHDSource(context)
//    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//    private val sourceManager by lazy { SourceManager(applicationContext) }
//
//
//    // Enhanced HTTP client for direct downloads
//    private val downloadClient = OkHttpClient.Builder()
//        .connectTimeout(30, TimeUnit.SECONDS)
//        .readTimeout(60, TimeUnit.SECONDS)
//        .writeTimeout(60, TimeUnit.SECONDS)
//        .addInterceptor { chain ->
//            val originalRequest = chain.request()
//            val newRequest = originalRequest.newBuilder()
//                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
//                .build()
//            chain.proceed(newRequest)
//        }
//        .build()
//
//    companion object {
//        const val KEY_EPISODE_URL = "key_episode_url"
//        const val KEY_VIDEO_URL = "key_video_url"
//        const val KEY_EPISODE_NAME = "key_episode_name"
//        const val KEY_ANIME_TITLE = "key_anime_title"
//        const val KEY_THUMBNAIL_URL = "key_thumbnail_url"
//        const val NOTIFICATION_CHANNEL_ID = "download_channel"
//        const val NOTIFICATION_CHANNEL_NAME = "Downloads"
//        const val KEY_HEADERS_JSON = "key_headers_json"
//        const val CHUNK_SIZE = 8192 // 8KB chunks for direct downloads
//        // Add this constant
//        const val KEY_IS_AUDIO = "is_audio"
//
//        // In doWork() method, add audio handling:
//    }
//
//    override suspend fun doWork(): Result {
//        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
//        var videoUrl = inputData.getString(KEY_VIDEO_URL)
//        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
//        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"
//        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
//        val notificationId = episodeUrl.hashCode()
//        val headersJson = inputData.getString(KEY_HEADERS_JSON)
//        val headers = headersJson?.let {
//            Gson().fromJson(it, Map::class.java) as? Map<String, String>
//        } ?: emptyMap()
//
//        // Define the destination file
//        val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
//        val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
//        val animeDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ana Movie/$safeAnimeTitle")
//        if (!animeDir.exists()) { animeDir.mkdirs() }
//        val destinationFile = File(animeDir, "$safeEpisodeName.mp4")
//
//        return withContext(Dispatchers.IO) {
//            try {
//                // Fetch video URL if missing
//                if (videoUrl.isNullOrBlank()) {
//                    Log.d("DownloadWorker", "Video URL for '$episodeName' is missing. Fetching list...")
//                    updateNotification(notificationId, episodeName, "Finding video link...", 0, true)
//
//                    val videos = sourceManager.fetchVideoList(episodeUrl)
//                    if (videos.isNotEmpty()) {
//                        videoUrl = videos.first().url
//                    } else {
//                        throw IllegalStateException("No video sources found for the episode.")
//                    }
//                }
//
//                val download = Download(
//                    episodeUrl = episodeUrl,
//                    animeTitle = animeTitle,
//                    episodeName = episodeName,
//                    thumbnailUrl = thumbnailUrl,
//                    downloadState = DownloadState.QUEUED,
//                    mediaUri = videoUrl!!
//                )
//                db.downloadDao().upsert(download)
//
//                val foregroundInfo = createForegroundInfo(notificationId, episodeName, "Starting download...")
//                setForeground(foregroundInfo)
//
//                // Update state to DOWNLOADING
//                db.downloadDao().updateState(episodeUrl, DownloadState.DOWNLOADING)
//
//                // Determine download method based on URL type
//                val downloadResult = when {
//                    isM3u8Url(videoUrl!!) -> downloadM3u8Stream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
//                    isDirectVideoUrl(videoUrl!!) -> downloadDirectVideo(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
//                    else -> downloadGenericStream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
//                }
//
//                if (downloadResult) {
//                    Log.d("DownloadWorker", "Download finished successfully for '$episodeName'")
//                    db.downloadDao().updateOnSuccess(episodeUrl, destinationFile.absolutePath)
//                    showFinalNotification(notificationId, episodeName, "Download complete", true)
//                    Result.success()
//                } else {
//                    throw Exception("Download failed")
//                }
//
//            } catch (e: Exception) {
//                Log.e("DownloadWorker", "Download failed for '$episodeName'", e)
//                destinationFile.delete() // Clean up partially downloaded file
//                db.downloadDao().updateState(episodeUrl, DownloadState.FAILED)
//                showFinalNotification(notificationId, episodeName, "Download failed: ${e.message}", false)
//                Result.failure()
//            }
//        }
//    }
//
//    private fun isM3u8Url(url: String): Boolean {
//        return url.contains(".m3u8") || url.contains("hls") || url.contains("playlist")
//    }
//
//    private fun isDirectVideoUrl(url: String): Boolean {
//        return url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") || url.contains(".mov")
//    }
//
//    private suspend fun downloadM3u8Stream(
//        videoUrl: String,
//        destinationFile: File,
//        notificationId: Int,
//        episodeName: String,
//        episodeUrl: String,
//        headers: Map<String, String>
//    ): Boolean {
//        return try {
//            val downloadData = M3u8Helper.HlsDownloadData(videoUrl)
//            val totalSegments = downloadData.tsLinks.size
//            var downloadedSegments = 0
//            var lastProgress = -1
//
//            Log.d("DownloadWorker", "Starting M3U8 download of $totalSegments segments for $episodeName.")
//
//            FileOutputStream(destinationFile).use { outputStream ->
//                downloadData.tsLinks.forEachIndexed { index, segmentUrl ->
//                    if (isStopped) {
//                        db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
//                        throw InterruptedException("Download was cancelled/stopped by user")
//                    }
//
//                    val segmentData = M3u8Helper.downloadSegment(segmentUrl, downloadData, index)
//                    outputStream.write(segmentData)
//                    downloadedSegments++
//
//                    val progress = (downloadedSegments * 100) / totalSegments
//                    if (progress > lastProgress) {
//                        db.downloadDao().updateProgress(episodeUrl, progress)
//                        val content = "Downloaded $downloadedSegments of $totalSegments segments"
//                        updateNotification(notificationId, episodeName, content, progress, false)
//                        lastProgress = progress
//                    }
//                }
//            }
//            true
//        } catch (e: Exception) {
//            Log.e("DownloadWorker", "M3U8 download failed", e)
//            false
//        }
//    }
//
//    private suspend fun downloadDirectVideo(
//        videoUrl: String,
//        destinationFile: File,
//        notificationId: Int,
//        episodeName: String,
//        episodeUrl: String,
//        headers: Map<String, String>
//    ): Boolean {
//        return try {
//            Log.d("DownloadWorker", "Starting direct video download for $episodeName")
//
//            val requestBuilder = Request.Builder().url(videoUrl)
//            headers.forEach { (key, value) ->
//                requestBuilder.header(key, value)
//            }
//
//            val response = downloadClient.newCall(requestBuilder.build()).execute()
//            if (!response.isSuccessful) {
//                throw Exception("HTTP ${response.code}: ${response.message}")
//            }
//
//            val contentLength = response.body?.contentLength() ?: -1
//            val inputStream = response.body?.byteStream()
//                ?: throw Exception("Failed to get response stream")
//
//            var downloadedBytes = 0L
//            var lastProgress = -1
//            val buffer = ByteArray(CHUNK_SIZE)
//
//            FileOutputStream(destinationFile).use { outputStream ->
//                inputStream.use { input ->
//                    var bytesRead: Int
//                    while (input.read(buffer).also { bytesRead = it } != -1) {
//                        if (isStopped) {
//                            db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
//                            throw InterruptedException("Download was cancelled/stopped by user")
//                        }
//
//                        outputStream.write(buffer, 0, bytesRead)
//                        downloadedBytes += bytesRead
//
//                        if (contentLength > 0) {
//                            val progress = ((downloadedBytes * 100) / contentLength).toInt()
//                            if (progress > lastProgress) {
//                                db.downloadDao().updateProgress(episodeUrl, progress)
//                                val content = "Downloaded ${formatFileSize(downloadedBytes)} of ${formatFileSize(contentLength)}"
//                                updateNotification(notificationId, episodeName, content, progress, false)
//                                lastProgress = progress
//                            }
//                        } else {
//                            // Unknown file size - show indeterminate progress
//                            val content = "Downloaded ${formatFileSize(downloadedBytes)}"
//                            updateNotification(notificationId, episodeName, content, 0, true)
//                        }
//                    }
//                }
//            }
//            true
//        } catch (e: Exception) {
//            Log.e("DownloadWorker", "Direct video download failed", e)
//            false
//        }
//    }
//
//    private suspend fun downloadGenericStream(
//        videoUrl: String,
//        destinationFile: File,
//        notificationId: Int,
//        episodeName: String,
//        episodeUrl: String,
//        headers: Map<String, String>
//    ): Boolean {
//        return try {
//            Log.d("DownloadWorker", "Starting generic stream download for $episodeName")
//
//            // First try as direct download
//            val directResult = downloadDirectVideo(videoUrl, destinationFile, notificationId, episodeName, episodeUrl, headers)
//            if (directResult) {
//                return true
//            }
//
//            // If direct download fails, try as M3U8
//            Log.d("DownloadWorker", "Direct download failed, trying M3U8 method")
//            downloadM3u8Stream(videoUrl, destinationFile, notificationId, episodeName, episodeUrl, headers)
//
//        } catch (e: Exception) {
//            Log.e("DownloadWorker", "Generic stream download failed", e)
//            false
//        }
//    }
//
//    private fun formatFileSize(bytes: Long): String {
//        if (bytes < 1024) return "$bytes B"
//        val kb = bytes / 1024.0
//        if (kb < 1024) return "%.1f KB".format(kb)
//        val mb = kb / 1024.0
//        if (mb < 1024) return "%.1f MB".format(mb)
//        val gb = mb / 1024.0
//        return "%.1f GB".format(gb)
//    }
//
//    private fun updateNotification(notificationId: Int, title: String, content: String, progress: Int, isIndeterminate: Boolean) {
//        createNotificationChannel()
//
//        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(content)
//            .setSmallIcon(R.drawable.download_2_24px)
//            .setOngoing(true)
//            .setProgress(100, progress, isIndeterminate)
//            .setOnlyAlertOnce(true)
//            .build()
//        notificationManager.notify(notificationId, notification)
//    }
//
//    private fun createForegroundInfo(notificationId: Int, title: String, content: String): ForegroundInfo {
//        createNotificationChannel()
//
//        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(content)
//            .setSmallIcon(R.drawable.download_2_24px)
//            .setOngoing(true)
//            .setProgress(100, 0, true)
//            .build()
//
//        return ForegroundInfo(notificationId, notification)
//    }
//
//    private fun showFinalNotification(notificationId: Int, title: String, content: String, isSuccess: Boolean) {
//        notificationManager.cancel(notificationId)
//
//        val finalNotification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(content)
//            .setSmallIcon(if (isSuccess) R.drawable.download_done_24px else R.drawable.file_download_off_24px)
//            .setAutoCancel(true)
//            .build()
//        notificationManager.notify(notificationId + 1, finalNotification)
//    }
//
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                NOTIFICATION_CHANNEL_ID,
//                NOTIFICATION_CHANNEL_NAME,
//                NotificationManager.IMPORTANCE_LOW
//            )
//            notificationManager.createNotificationChannel(channel)
//        }
//    }
//}