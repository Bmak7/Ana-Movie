//package com.faselhd.app.workers
//
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.content.Context
//import android.os.Build
//import androidx.core.app.NotificationCompat
//import androidx.work.CoroutineWorker
//import androidx.work.ForegroundInfo
//import androidx.work.WorkerParameters
//import androidx.work.workDataOf
//import com.example.myapplication.R
//import com.faselhd.app.db.AppDatabase
//import com.faselhd.app.models.Download
//import com.faselhd.app.models.DownloadState
//import com.faselhd.app.network.FaselHDSource
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import android.os.Environment
//import android.util.Log
//import java.io.*
//import java.util.concurrent.TimeUnit // Add this import
//
//class DownloadWorker(
//    private val context: Context,
//    workerParams: WorkerParameters
//) : CoroutineWorker(context, workerParams) {
//
//    private val db = AppDatabase.getDatabase(context)
//    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//
//    companion object {
//        const val KEY_EPISODE_URL = "key_episode_url"
//        const val KEY_VIDEO_URL = "key_video_url"
//        const val KEY_EPISODE_NAME = "key_episode_name"
//        const val KEY_ANIME_TITLE = "key_anime_title"
//        const val KEY_THUMBNAIL_URL = "key_thumbnail_url"
//        const val NOTIFICATION_CHANNEL_ID = "download_channel"
//        const val BASE_URL = "https://www.faselhds.xyz" // <-- ADD THIS LINE
//    }
//
//    override suspend fun doWork(): Result {
//        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
//        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return Result.failure()
//        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
//        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"
//        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
//
//        val notificationId = episodeUrl.hashCode()
//        val download = Download(episodeUrl, animeTitle, episodeName, thumbnailUrl, DownloadState.DOWNLOADING, 0, "Starting...")
//        db.downloadDao().upsert(download)
//
//        val foregroundInfo = createForegroundInfo(notificationId, episodeName, "Starting download...")
//        setForeground(foregroundInfo)
//
//        // *** MODIFIED: Client creation with required headers ***
//        val client = OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .addInterceptor { chain ->
//                val original = chain.request()
//                val requestBuilder = original.newBuilder()
//                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//                    .header("Referer", BASE_URL) // The crucial header
//                val request = requestBuilder.build()
//                chain.proceed(request)
//            }
//            .build()
//
//        val request = Request.Builder().url(videoUrl).build()
//
//        return withContext(Dispatchers.IO) {
//            try {
//                Log.d("DownloadWorker", "Starting download for: $videoUrl")
//
//                // *** NEW: Safe resource handling with .use block ***
//                client.newCall(request).execute().use { response ->
//                    if (!response.isSuccessful) {
//                        throw IOException("Download failed with code: ${response.code}")
//                    }
//
//                    val body = response.body!!
//                    val contentLength = body.contentLength()
//
//                    val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
//                    val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
//                    val fileExtension = ".mp4"
//
//                    val animeDir = File(
//                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
//                        "Ana Movie/$safeAnimeTitle"
//                    )
//                    if (!animeDir.exists()) {
//                        animeDir.mkdirs()
//                    }
//
//                    val destinationFile = File(animeDir, "$safeEpisodeName$fileExtension")
//                    Log.d("DownloadWorker", "Saving to: ${destinationFile.absolutePath}")
//
//                    var lastUpdateTime = System.currentTimeMillis()
//                    var lastBytesRead: Long = 0
//                    var totalBytesRead = 0L
//
//                    body.byteStream().use { input ->
//                        FileOutputStream(destinationFile).use { output ->
//                            val buffer = ByteArray(8 * 1024)
//                            var bytesRead: Int
//                            while (input.read(buffer).also { bytesRead = it } != -1) {
//                                if (isStopped) { // Check for cancellation
//                                    destinationFile.delete()
//                                    db.downloadDao().delete(episodeUrl)
//                                    notificationManager.cancel(notificationId)
//                                    return@withContext Result.failure()
//                                }
//
//                                output.write(buffer, 0, bytesRead)
//                                totalBytesRead += bytesRead
//
//                                val currentTime = System.currentTimeMillis()
//                                if (currentTime - lastUpdateTime >= 1000) {
//                                    val progress = if (contentLength > 0) ((totalBytesRead * 100) / contentLength).toInt() else -1
//                                    val speedBps = totalBytesRead - lastBytesRead
//                                    val timeLeftStr = if (contentLength > 0 && speedBps > 0) {
//                                        formatTimeLeft((contentLength - totalBytesRead) / speedBps)
//                                    } else "Calculating..."
//
//                                    if(progress > download.progress || download.timeLeft != timeLeftStr) {
//                                        download.progress = progress
//                                        download.timeLeft = timeLeftStr
//                                        db.downloadDao().upsert(download)
//                                        val progressText = if(contentLength > 0) "Downloaded ${totalBytesRead / 1048576}MB / ${contentLength / 1048576}MB" else "Downloading..."
//                                        updateNotification(notificationId, episodeName, progress, progressText, timeLeftStr)
//                                    }
//                                    lastUpdateTime = currentTime
//                                    lastBytesRead = totalBytesRead
//                                }
//                            }
//                        }
//                    }
//
//                    download.downloadState = DownloadState.COMPLETED
//                    download.localFilePath = destinationFile.absolutePath
//                    download.progress = 100
//                    download.timeLeft = "Completed"
//                    db.downloadDao().upsert(download)
//                    finishNotification(notificationId, episodeName, "Download complete")
//                    Result.success()
//                }
//            } catch (e: Exception) {
//                Log.e("DownloadWorker", "Download failed for $episodeUrl", e) // Detailed log
//                download.downloadState = DownloadState.FAILED
//                download.timeLeft = e.localizedMessage ?: "Unknown error" // Show error in UI
//                db.downloadDao().upsert(download)
//                finishNotification(notificationId, episodeName, "Download failed")
//                Result.failure()
//            }
//        }
//    }
//    // Add this new helper function to format time
//    private fun formatTimeLeft(seconds: Long): String {
//        if (seconds < 0) return "..."
//        val hours = seconds / 3600
//        val minutes = (seconds % 3600) / 60
//        val secs = seconds % 60
//        return when {
//            hours > 0 -> String.format("%dh %02dm left", hours, minutes)
//            minutes > 0 -> String.format("%dm %02ds left", minutes, secs)
//            else -> String.format("%ds left", secs)
//        }
//    }
//
//    private fun updateNotification(notificationId: Int, title: String, progress: Int, content: String, timeLeft: String) {
//        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText("$content - $timeLeft") // Add time left to notification
//            .setSmallIcon(R.drawable.download_2_24px)
//            .setOngoing(true)
//            .setProgress(100, if (progress < 0) 0 else progress, progress < 0) // indeterminate if no progress
//            .build()
//        notificationManager.notify(notificationId, notification)
//    }
//
//    private fun createForegroundInfo(notificationId: Int, title: String, content: String): ForegroundInfo {
//        createNotificationChannel()
//        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(content)
//            .setSmallIcon(R.drawable.download_2_24px)
//            .setOngoing(true)
//            .setProgress(100, 0, true) // Indeterminate progress initially
//            .build()
//        return ForegroundInfo(notificationId, notification)
//    }
//
////    private fun updateNotification(notificationId: Int, title: String, progress: Int, content: String) {
////        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
////            .setContentTitle(title)
////            .setContentText(content)
////            .setSmallIcon(R.drawable.download_2_24px)
////            .setOngoing(true)
////            .setProgress(100, progress, false)
////            .build()
////        notificationManager.notify(notificationId, notification)
////    }
//
//    private fun finishNotification(notificationId: Int, title: String, content: String) {
//        notificationManager.cancel(notificationId) // Remove progress bar notification
//        val finalNotification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(content)
//            .setSmallIcon(R.drawable.download_2_24px)
//            .build()
//        notificationManager.notify(notificationId + 1000, finalNotification) // Show final status
//    }
//
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                NOTIFICATION_CHANNEL_ID,
//                "Downloads",
//                NotificationManager.IMPORTANCE_LOW
//            )
//            notificationManager.createNotificationChannel(channel)
//        }
//    }
//}

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
import com.example.myapplication.R // Make sure this points to your R file
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.Download
import com.faselhd.app.models.DownloadState
import com.faselhd.app.utils.M3u8Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = AppDatabase.getDatabase(context)
    // Get the system's NotificationManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_EPISODE_URL = "key_episode_url"
        const val KEY_VIDEO_URL = "key_video_url"
        const val KEY_EPISODE_NAME = "key_episode_name"
        const val KEY_ANIME_TITLE = "key_anime_title"
        const val KEY_THUMBNAIL_URL = "key_thumbnail_url"
        const val NOTIFICATION_CHANNEL_ID = "download_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Downloads"
    }

    // This is the core function that will run
    override suspend fun doWork(): Result {
        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return Result.failure()
        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"

        // The notification ID must be unique for each download
        val notificationId = episodeUrl.hashCode()

        // Create an initial entry in the database
        val download = Download(episodeUrl, animeTitle, episodeName, inputData.getString(KEY_THUMBNAIL_URL), DownloadState.DOWNLOADING, 0)
        db.downloadDao().upsert(download)

        // *** STEP 1: PROMOTE TO A FOREGROUND SERVICE ***
        // Create the initial notification and tell the system this worker is important
        val foregroundInfo = createForegroundInfo(notificationId, episodeName, "Starting download...")
        setForeground(foregroundInfo)

        // Define the destination file
        val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val animeDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ana Movie/$safeAnimeTitle")
        if (!animeDir.exists()) { animeDir.mkdirs() }
        val destinationFile = File(animeDir, "$safeEpisodeName.mp4")

        return withContext(Dispatchers.IO) {
            try {
                val downloadData = M3u8Helper.HlsDownloadData(videoUrl)
                val totalSegments = downloadData.tsLinks.size
                var downloadedSegments = 0
                var lastProgress = -1

                Log.d("DownloadWorker", "Starting download of $totalSegments segments for $episodeName.")

                FileOutputStream(destinationFile).use { outputStream ->
                    downloadData.tsLinks.forEachIndexed { index, segmentUrl ->
                        if (isStopped) {
                            outputStream.close()
                            destinationFile.delete()
                            throw InterruptedException("Download was cancelled")
                        }

                        val segmentData = M3u8Helper.downloadSegment(segmentUrl, downloadData, index)
                        outputStream.write(segmentData)
                        downloadedSegments++

                        val progress = (downloadedSegments * 100) / totalSegments
                        if (progress > lastProgress) {
                            download.progress = progress
                            db.downloadDao().upsert(download)
                            // *** STEP 2: UPDATE THE NOTIFICATION WITH PROGRESS ***
                            updateNotification(notificationId, episodeName, progress, downloadedSegments, totalSegments)
                            lastProgress = progress
                        }
                    }
                }

                Log.d("DownloadWorker", "Download finished successfully.")
                download.downloadState = DownloadState.COMPLETED
                download.localFilePath = destinationFile.absolutePath
                download.progress = 100
                db.downloadDao().upsert(download)
                // *** STEP 3: SHOW A "COMPLETE" NOTIFICATION ***
                showFinalNotification(notificationId, episodeName, "Download complete", true)
                Result.success()
            } catch (e: Exception) {
                Log.e("DownloadWorker", "Download failed for $videoUrl", e)
                destinationFile.delete()
                download.downloadState = DownloadState.FAILED
                db.downloadDao().upsert(download)
                // *** STEP 4: SHOW A "FAILED" NOTIFICATION ***
                showFinalNotification(notificationId, episodeName, "Download failed", false)
                Result.failure()
            }
        }
    }

    // Helper function to create the initial notification and ForegroundInfo
    private fun createForegroundInfo(notificationId: Int, title: String, content: String): ForegroundInfo {
        createNotificationChannel() // Ensure the channel exists

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px) // Use your download icon
            .setOngoing(true) // Makes the notification non-dismissable
            .setProgress(100, 0, true) // Indeterminate progress initially
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    // Helper function to update the progress notification
    private fun updateNotification(notificationId: Int, title: String, progress: Int, downloaded: Int, total: Int) {
        val content = "Downloaded $downloaded of $total segments"
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px)
            .setOngoing(true)
            .setProgress(100, progress, false) // Determinate progress
            .setOnlyAlertOnce(true) // Don't make a sound for every update
            .build()
        notificationManager.notify(notificationId, notification)
    }

    // Helper function to show the final status (Complete/Failed)
    private fun showFinalNotification(notificationId: Int, title: String, content: String, isSuccess: Boolean) {
        // First, cancel the ongoing progress notification
        notificationManager.cancel(notificationId)

        val finalNotification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(if (isSuccess) R.drawable.download_done_24px else R.drawable.file_download_off_24px)
            .setAutoCancel(true) // The user can dismiss this one
            .build()
        // Use a different ID for the final notification to ensure it shows
        notificationManager.notify(notificationId + 1, finalNotification)
    }

    // Helper function to create the notification channel on Android 8.0+
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}