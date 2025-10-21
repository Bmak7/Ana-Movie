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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val db = AppDatabase.getDatabase(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sourceManager by lazy { SourceManager(applicationContext) }

    // Base client - will be customized per request with headers
    private fun createDownloadClient(customHeaders: Map<String, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                // Add custom headers from Video model
                customHeaders.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }

                // Add Referer if not already present
                if (!customHeaders.containsKey("Referer")) {
                    requestBuilder.header("Referer", "https://www.faselhds.xyz")
                }

                chain.proceed(requestBuilder.build())
            }
            .build()
    }

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
        const val CHUNK_SIZE = 8192
    }

    override suspend fun doWork(): Result {
        val episodeUrl = inputData.getString(KEY_EPISODE_URL) ?: return Result.failure()
        var videoUrl = inputData.getString(KEY_VIDEO_URL)
        val episodeName = inputData.getString(KEY_EPISODE_NAME) ?: "Downloading..."
        val animeTitle = inputData.getString(KEY_ANIME_TITLE) ?: "Anime"
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        val isAudio = inputData.getBoolean(KEY_IS_AUDIO, false)
        val notificationId = episodeUrl.hashCode()

        // Parse headers from JSON
        val headersJson = inputData.getString(KEY_HEADERS_JSON)
        val headers: Map<String, String> = if (!headersJson.isNullOrBlank()) {
            try {
                Gson().fromJson<Map<String, String>>(
                    headersJson,
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (e: Exception) {
                Log.e("DownloadWorker", "Failed to parse headers JSON", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }

        Log.d("DownloadWorker", "Starting download with headers: $headers")

        // Setup destination file
        val safeAnimeTitle = animeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val safeEpisodeName = episodeName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val folderName = if (isAudio) "Ana Movie/Audio" else "Ana Movie/Video"
        val animeDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$folderName/$safeAnimeTitle"
        )
        if (!animeDir.exists()) animeDir.mkdirs()

        val fileExtension = if (isAudio) {
            getAudioFileExtension(videoUrl ?: "", episodeName)
        } else {
            getVideoFileExtension(videoUrl ?: "", episodeName)
        }

        val destinationFile = File(animeDir, "$safeEpisodeName.$fileExtension")

        return withContext(Dispatchers.IO) {
            try {
                // Fetch video URL if not provided
                if (videoUrl.isNullOrBlank()) {
                    Log.d("DownloadWorker", "Fetching video list for '$episodeName'")
                    updateNotification(notificationId, episodeName, "Finding video link...", 0, true)

                    val videos = sourceManager.fetchVideoList(episodeUrl)
                    if (videos.isNotEmpty()) {
                        videoUrl = videos.first().url
                    } else {
                        throw IllegalStateException("No video sources found")
                    }
                }

                // Create download record
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

                db.downloadDao().updateState(episodeUrl, DownloadState.DOWNLOADING)

                // Download based on URL type - pass headers to both methods
                val downloadResult = when {
                    isM3u8Url(videoUrl!!) -> {
                        Log.d("DownloadWorker", "Detected M3U8 URL, using M3U8Helper with custom headers")
                        downloadM3u8Stream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
                    }
                    else -> {
                        Log.d("DownloadWorker", "Using direct stream download with custom headers")
                        downloadStream(videoUrl!!, destinationFile, notificationId, episodeName, episodeUrl, headers)
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
                if (destinationFile.exists()) destinationFile.delete()
                db.downloadDao().updateState(episodeUrl, DownloadState.FAILED)
                showFinalNotification(notificationId, episodeName, "Download failed: ${e.message}", false)
                Result.failure()
            }
        }
    }

    private fun isM3u8Url(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/hls/", ignoreCase = true) ||
                url.contains("playlist", ignoreCase = true)
    }

    private fun getVideoFileExtension(url: String, episodeName: String): String {
        if (isM3u8Url(url)) return "mp4"

        val urlExtension = url.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (urlExtension in listOf("mp4", "mkv", "avi", "mov", "webm")) {
            return urlExtension!!
        }
        val nameExtension = episodeName.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (nameExtension in listOf("mp4", "mkv", "avi", "mov", "webm")) {
            return nameExtension!!
        }
        return "mp4"
    }

    private fun getAudioFileExtension(url: String, episodeName: String): String {
        val urlExtension = url.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (urlExtension in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac")) {
            return urlExtension!!
        }
        val nameExtension = episodeName.substringAfterLast('.').takeIf { it.length in 2..5 }?.lowercase()
        if (nameExtension in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac")) {
            return nameExtension!!
        }
        return "mp3"
    }

    private suspend fun downloadStream(
        fileUrl: String,
        destinationFile: File,
        notificationId: Int,
        episodeName: String,
        episodeUrl: String,
        headers: Map<String, String>
    ): Boolean {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var response: okhttp3.Response? = null

        val client = createDownloadClient(headers)

        try {
            val requestBuilder = Request.Builder().url(fileUrl)
            response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP Error ${response.code}: ${response.message}")
            }

            val contentLength = response.body?.contentLength() ?: -1
            inputStream = response.body?.byteStream()
                ?: throw IllegalStateException("Response body is null")

            outputStream = FileOutputStream(destinationFile)

            var downloadedBytes = 0L
            var lastProgress = -1
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) {
                    db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                    throw InterruptedException("Download was cancelled")
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
                    val content = "Downloaded ${formatFileSize(downloadedBytes)}"
                    updateNotification(notificationId, episodeName, content, 0, true)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Stream download failed for $episodeName", e)
            return false
        } finally {
            inputStream?.close()
            outputStream?.close()
            response?.body?.close()
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
            Log.d("DownloadWorker", "Parsing M3U8 playlist for $episodeName with headers: $headers")

            // Pass headers to M3u8Helper
            val downloadData = M3u8Helper.HlsDownloadData(videoUrl, headers)
            val totalSegments = downloadData.tsLinks.size

            if (totalSegments == 0) {
                throw IOException("No segments found in M3U8 playlist")
            }

            var downloadedSegments = 0
            var lastProgress = -1

            Log.d("DownloadWorker", "Downloading $totalSegments segments for $episodeName")

            FileOutputStream(destinationFile).use { outputStream ->
                downloadData.tsLinks.forEachIndexed { index, segmentUrl ->
                    if (isStopped) {
                        db.downloadDao().updateState(episodeUrl, DownloadState.PAUSED)
                        throw InterruptedException("Download cancelled")
                    }

                    val segmentData = M3u8Helper.downloadSegment(segmentUrl, downloadData, index, headers)
                    outputStream.write(segmentData)
                    downloadedSegments++

                    val progress = (downloadedSegments * 100) / totalSegments
                    if (progress > lastProgress) {
                        db.downloadDao().updateProgress(episodeUrl, progress)
                        val content = "Segment $downloadedSegments/$totalSegments"
                        updateNotification(notificationId, episodeName, content, progress, false)
                        lastProgress = progress
                    }
                }
            }

            Log.d("DownloadWorker", "M3U8 download completed successfully for $episodeName")
            true
        } catch (e: Exception) {
            Log.e("DownloadWorker", "M3U8 download failed for $episodeName", e)
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

// 3. UPDATED M3u8Helper.kt - Accept custom headers

object M3u8Helper {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val BASE_URL = "https://www.faselhds.xyz"

    // Create client with custom headers
    private fun createClient(customHeaders: Map<String, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("User-Agent", USER_AGENT)

                // Add custom headers
                customHeaders.forEach { (key, value) ->
                    newRequest.header(key, value)
                }

                // Add default Referer if not present
                if (!customHeaders.containsKey("Referer")) {
                    newRequest.header("Referer", BASE_URL)
                }

                newRequest.build()
                chain.proceed(newRequest.build())
            }
            .build()
    }

    private val ENCRYPTION_REGEX = Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
    private val TS_EXTENSION_REGEX = Regex("""#EXTINF:.+?\n(?!#)(.+)""")

    private fun getParentLink(uri: String): String {
        val split = uri.split("/").toMutableList()
        if (split.isEmpty()) return ""
        split.removeAt(split.lastIndex)
        return split.joinToString("/")
    }

    private fun isNotCompleteUrl(url: String): Boolean {
        return !url.startsWith("https://") && !url.startsWith("http://")
    }

    private fun toBytes16Big(n: ULong): ByteArray {
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[15 - i] = ((n shr (i * 8)) and 0xFFUL).toByte()
        }
        return bytes
    }

    private fun defaultIv(sequence: Int): ByteArray {
        return toBytes16Big(sequence.toULong())
    }

    private fun getDecrypted(secretKey: ByteArray, data: ByteArray, iv: ByteArray, sequence: Int): ByteArray {
        val ivKey = if (iv.isEmpty()) defaultIv(sequence) else iv
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val skSpec = SecretKeySpec(secretKey, "AES")
        val ivSpec = IvParameterSpec(ivKey)
        cipher.init(Cipher.DECRYPT_MODE, skSpec, ivSpec)
        return cipher.doFinal(data)
    }

    data class HlsDownloadData(
        val encryptionKey: ByteArray?,
        val encryptionIv: ByteArray?,
        val tsLinks: List<String>
    )

    @Throws(IOException::class)
    fun HlsDownloadData(playlistUrl: String, customHeaders: Map<String, String> = emptyMap()): HlsDownloadData {
        val client = createClient(customHeaders)
        val request = Request.Builder().url(playlistUrl).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to fetch playlist (Code: ${response.code})")

        val playlistText = response.body!!.string()
        val parentUrl = getParentLink(playlistUrl)

        var encryptionKey: ByteArray? = null
        var encryptionIv: ByteArray? = null

        ENCRYPTION_REGEX.find(playlistText)?.groupValues?.let {
            var keyUri = it[2]
            if (isNotCompleteUrl(keyUri)) {
                keyUri = "$parentUrl/$keyUri"
            }
            val keyRequest = Request.Builder().url(keyUri).build()
            val keyResponse = client.newCall(keyRequest).execute()
            if (keyResponse.isSuccessful) {
                encryptionKey = keyResponse.body!!.bytes()
            }
            encryptionIv = it.getOrNull(3)?.toByteArray()
        }

        val tsLinks = TS_EXTENSION_REGEX.findAll(playlistText).map { match ->
            var tsUrl = match.groupValues[1].trim()
            if (isNotCompleteUrl(tsUrl)) {
                tsUrl = "$parentUrl/$tsUrl"
            }
            tsUrl
        }.toList()

        if (tsLinks.isEmpty()) throw IOException("No .ts segments found in playlist")

        return HlsDownloadData(encryptionKey, encryptionIv, tsLinks)
    }

    @Throws(IOException::class)
    fun downloadSegment(
        segmentUrl: String,
        encryptionData: HlsDownloadData?,
        sequence: Int,
        customHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        val client = createClient(customHeaders)
        val request = Request.Builder().url(segmentUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to download segment $segmentUrl (Code: ${response.code})")

        val tsData = response.body!!.bytes()
        if (tsData.isEmpty()) throw IOException("Segment is empty")

        if (encryptionData?.encryptionKey != null) {
            return getDecrypted(
                encryptionData.encryptionKey,
                tsData,
                encryptionData.encryptionIv ?: byteArrayOf(),
                sequence
            )
        }
        return tsData
    }
}