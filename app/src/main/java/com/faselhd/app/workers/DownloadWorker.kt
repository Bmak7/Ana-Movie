// In file: app/src/main/java/com/faselhd/app/workers/DownloadWorker.kt

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
import com.faselhd.app.network.FaselHDSource
import com.faselhd.app.utils.M3u8Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) { // Use applicationContext for safety

    private val db = AppDatabase.getDatabase(context)
    private val faselHDSource = FaselHDSource(context) // **** 2. ADD THIS INSTANCE ****
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

    override suspend fun doWork(): Result {
        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
        var videoUrl = inputData.getString(KEY_VIDEO_URL)
        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        val notificationId = episodeUrl.hashCode()




        // Define the destination file (same as before)
        val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val animeDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ana Movie/$safeAnimeTitle")
        if (!animeDir.exists()) { animeDir.mkdirs() }
        val destinationFile = File(animeDir, "$safeEpisodeName.mp4")

        return withContext(Dispatchers.IO) {
            try {
                // **** 4. ADD THIS LOGIC BLOCK TO FIND THE VIDEO URL IF MISSING ****
                if (videoUrl.isNullOrBlank()) {
                    Log.d("DownloadWorker", "Video URL for '$episodeName' is missing. Fetching list...")

                    // **** CORRECTED FUNCTION CALL ****
                    // Now we pass the text directly and tell the notification to be indeterminate.
                    updateNotification(notificationId, episodeName, "Finding video link...", 0, true)

                    val videos = faselHDSource.fetchVideoList(episodeUrl)
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
                    mediaUri = videoUrl!! // Save the actual media URI here
                )
                db.downloadDao().upsert(download)

                val foregroundInfo = createForegroundInfo(notificationId, episodeName, "Starting download...")
                setForeground(foregroundInfo)

                // At this point, videoUrl is guaranteed to be valid.
                // Update the state in the database to DOWNLOADING
                db.downloadDao().updateState(episodeUrl, DownloadState.DOWNLOADING)

                val downloadData = M3u8Helper.HlsDownloadData(videoUrl!!)
                val totalSegments = downloadData.tsLinks.size
                var downloadedSegments = 0
                var lastProgress = -1

                Log.d("DownloadWorker", "Starting download of $totalSegments segments for $episodeName.")

                FileOutputStream(destinationFile).use { outputStream ->
                    downloadData.tsLinks.forEachIndexed { index, segmentUrl ->
                        if (isStopped) {
                            db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                            outputStream.close()
                            // Don't delete the file, so it can be resumed later
                            throw InterruptedException("Download was cancelled/stopped by user")
                        }


                        val segmentData = M3u8Helper.downloadSegment(segmentUrl, downloadData, index)
                        outputStream.write(segmentData)
                        downloadedSegments++

                        val progress = (downloadedSegments * 100) / totalSegments
                        if (progress > lastProgress) {
                            db.downloadDao().updateProgress(episodeUrl, progress)

                            // **** CORRECTED FUNCTION CALL ****
                            // We build the content string here and pass it to the helper.
                            val content = "Downloaded $downloadedSegments of $totalSegments segments"
                            updateNotification(notificationId, episodeName, content, progress, false)

                            lastProgress = progress
                        }
                    }
                }


                Log.d("DownloadWorker", "Download finished successfully for '$episodeName'")
                db.downloadDao().updateOnSuccess(episodeUrl, destinationFile.absolutePath)
                showFinalNotification(notificationId, episodeName, "Download complete", true)
                Result.success()

            } catch (e: Exception) {
                Log.e("DownloadWorker", "Download failed for '$episodeName'", e)
                destinationFile.delete() // Clean up partially downloaded file
                db.downloadDao().updateState(episodeUrl, DownloadState.FAILED)
                showFinalNotification(notificationId, episodeName, "Download failed: ${e.message}", false)
                Result.failure()
            }
        }
    }

    // Renamed for clarity
    private fun updateNotificationProgress(notificationId: Int, title: String, progress: Int, downloaded: Int, total: Int) {
        val content = if (progress >= 0) "Downloaded $downloaded of $total segments" else "Preparing download..."
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px)
            .setOngoing(true)
            .setProgress(100, if(progress >= 0) progress else 0, progress < 0) // indeterminate if progress is -1
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun updateNotification(notificationId: Int, title: String, content: String, progress: Int, isIndeterminate: Boolean) {
        // Ensure the channel exists (important)
        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.download_2_24px)
            .setOngoing(true)
            .setProgress(100, progress, isIndeterminate) // Use the isIndeterminate flag
            .setOnlyAlertOnce(true) // Don't make a sound for every update
            .build()
        notificationManager.notify(notificationId, notification)
    }

    // Unchanged from your original code
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

    // Unchanged from your original code
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

    // Unchanged from your original code
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