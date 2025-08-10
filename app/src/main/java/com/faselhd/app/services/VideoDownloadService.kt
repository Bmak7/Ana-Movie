package com.faselhd.app.services

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.example.myapplication.R
import com.faselhd.app.utils.DownloadUtil
import android.app.PendingIntent
import android.content.Intent
import com.faselhd.app.DownloadsActivity


private const val CHANNEL_ID = "download_channel"
private const val FOREGROUND_NOTIFICATION_ID = 1

@OptIn(UnstableApi::class)
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name, // You need to add this string resource
    0
) {
    override fun getDownloadManager(): DownloadManager {
        // Return the shared instance of the DownloadManager.
        return DownloadUtil.getDownloadManager(this)
    }

    // You can optionally implement a scheduler for retries on network loss etc.
    override fun getScheduler(): Scheduler? {
        return null
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {

        // *** THIS IS THE NEW PART ***
        // 1. Create an Intent that points to your DownloadsActivity.
        val intent = Intent(this, DownloadsActivity::class.java)

        // 2. Create a PendingIntent that will fire the intent.
        // FLAG_IMMUTABLE is required for security on modern Android.
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, // A request code, 0 is fine for this use case
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = if (downloads.isEmpty()) {
            "Waiting for downloads"
        } else {
            "Downloading ${downloads.size} episode(s)"
        }

        // 3. Pass the PendingIntent to the notification builder.
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(
                this,
                R.drawable.download_2_24px,
                pendingIntent, // <-- PASS THE PENDING INTENT HERE
                message,
                downloads,
                notMetRequirements
            )
    }
}