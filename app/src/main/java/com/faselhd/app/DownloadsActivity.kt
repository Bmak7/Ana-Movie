package com.faselhd.app

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.faselhd.app.adapters.DownloadsAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.services.VideoDownloadService
import com.faselhd.app.utils.DownloadUtil
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.myapplication.R
//import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.WorkManager // Add this import

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import java.io.File
import androidx.media3.exoplayer.offline.DownloadManager // Make sure this is imported
import com.faselhd.app.models.DownloadState


class DownloadsActivity : AppCompatActivity(), DownloadManager.Listener {
    private lateinit var downloadsRecyclerView: RecyclerView
    private lateinit var downloadsAdapter: DownloadsAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    // Get a reference to the shared DownloadManager instance
    private lateinit var downloadManager: DownloadManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        downloadManager = DownloadUtil.getDownloadManager(this) // Initialize it

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        setupRecyclerView()
        observeDownloadsFromDb()
    }

    // *** THIS IS THE CRITICAL LISTENER METHOD THAT GETS REAL-TIME UPDATES ***
    @OptIn(UnstableApi::class)
    override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: androidx.media3.exoplayer.offline.Download,
        finalException: Exception?
    ) {
        // A single download has changed its state. Let's update our database to reflect it.
        lifecycleScope.launch {
            val localDownload = db.downloadDao().getDownload(download.request.id) ?: return@launch

            val newState = when (download.state) {
                Download.STATE_COMPLETED -> DownloadState.COMPLETED
                Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
                Download.STATE_FAILED -> DownloadState.FAILED
                Download.STATE_QUEUED -> DownloadState.QUEUED
                Download.STATE_STOPPED -> DownloadState.PAUSED
                Download.STATE_REMOVING -> {
                    db.downloadDao().delete(download.request.id)
                    return@launch
                }
                else -> localDownload.downloadState
            }

            val newProgress = download.percentDownloaded.toInt()

            if (localDownload.downloadState != newState || localDownload.progress != newProgress) {
                localDownload.downloadState = newState
                localDownload.progress = if (newProgress < 0) 0 else newProgress
                db.downloadDao().upsert(localDownload)
            }
        }
    }


    @OptIn(UnstableApi::class)
    private fun setupRecyclerView() {
        downloadsAdapter = DownloadsAdapter { download, action ->
            when(action) {
                DownloadsAdapter.DownloadAction.PLAY -> {
                    // This is the only action we need for playing
                    playDownloadedFile(download)
                }
                DownloadsAdapter.DownloadAction.CANCEL -> {
                    DownloadService.sendRemoveDownload(
                        this, VideoDownloadService::class.java, download.episodeUrl, false
                    )
                }
                DownloadsAdapter.DownloadAction.PAUSE -> {
                    DownloadService.sendSetStopReason(
                        this, VideoDownloadService::class.java, download.episodeUrl, 1, false // 1 = user request
                    )
                }
                DownloadsAdapter.DownloadAction.RESUME -> {
                    DownloadService.sendSetStopReason(
                        this, VideoDownloadService::class.java, download.episodeUrl, 0, false // 0 = resume
                    )
                }
            }
        }
        downloadsRecyclerView = findViewById(R.id.downloads_recycler_view)
        downloadsRecyclerView.adapter = downloadsAdapter
    }

    // This function will now play from ExoPlayer's cache
    // In DownloadsActivity.kt -> playDownloadedFile()

    private fun playDownloadedFile(download: com.faselhd.app.models.Download) {
        // Check if the file exists locally
        val localFilePath = download.localFilePath
        val mediaUri = download.mediaUri

        val playableUri = when {
            // Priority 1: Use local file path if it exists and the file is accessible
            !localFilePath.isNullOrEmpty() && File(localFilePath).exists() -> {
                Log.d("DownloadsActivity", "Playing from local file: $localFilePath")
                Uri.fromFile(File(localFilePath)).toString()
            }
            // Priority 2: Use media URI (could be ExoPlayer cached content URI)
            !mediaUri.isNullOrEmpty() -> {
                Log.d("DownloadsActivity", "Playing from media URI: $mediaUri")
                mediaUri
            }
            // Priority 3: Fallback - shouldn't happen but handle gracefully
            else -> {
                Toast.makeText(this, "Downloaded file not found!", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Determine if this is audio or video content
        val isAudio = isAudioFile(playableUri)

        // Create the Video object for offline playback
        val offlineSource = Video(
            url = playableUri,
            quality = if (isAudio) "Downloaded Audio" else "Downloaded Video",
            videoUrl = playableUri,
            resolution = if (isAudio) "Audio" else "HD",
            headers = null, // No headers needed for local files
            subtitles = null // Will be auto-detected by the enhanced player
        )

        // Create a minimal anime and episode for the player
        val offlineAnime = SAnime(
            title = download.animeTitle,
            url = "", // Empty URL since this is offline content
            thumbnail_url = download.thumbnailUrl
        )

        val offlineEpisode = SEpisode(
            name = download.episodeName,
            url = download.episodeUrl, // Keep original URL for progress tracking
//            episode_number = 1
        )

        // Launch the video player
        val intent = VideoPlayerActivity.newIntent(
            context = this,
            videos = listOf(offlineSource),
            anime = offlineAnime,
            currentEpisode = offlineEpisode,
            episodeListForSeason = arrayListOf(), // No episode navigation for downloads
            startPosition = 0L,
            source = null // No source needed for offline content
        )

        startActivity(intent)
    }

    // Helper method to detect audio files
    private fun isAudioFile(url: String): Boolean {
        val audioExtensions = listOf("mp3", "wav", "aac", "ogg", "m4a", "flac", "wma")
        val extension = url.substringAfterLast('.', "").lowercase()
        return audioExtensions.contains(extension)
    }

    // Enhanced playWithInternalPlayer method for better offline handling
    private fun playWithInternalPlayer(download: com.faselhd.app.models.Download) {
        if (download.localFilePath == null || !File(download.localFilePath).exists()) {
            Toast.makeText(this, "Downloaded file not found at expected location!", Toast.LENGTH_LONG).show()
            return
        }

        val file = File(download.localFilePath)
        val fileUri = Uri.fromFile(file)
        val isAudio = isAudioFile(file.name)

        val localVideo = Video(
            url = fileUri.toString(),
            quality = if (isAudio) "Downloaded Audio" else "Downloaded Video",
            videoUrl = fileUri.toString(),
            resolution = if (isAudio) "Audio" else "Local File",
            headers = null,
            subtitles = null
        )

        val intent = VideoPlayerActivity.newIntent(
            context = this,
            videos = listOf(localVideo),
            anime = SAnime(title = download.animeTitle, url = ""),
            currentEpisode = SEpisode(name = download.episodeName, url = download.episodeUrl),
            episodeListForSeason = arrayListOf(),
            startPosition = 0L,
        )
        startActivity(intent)
    }

    // Enhanced method for external player with better MIME type detection
    private fun playWithExternalPlayer(download: com.faselhd.app.models.Download) {
        if (download.localFilePath.isNullOrEmpty()) {
            Toast.makeText(this, "File path not available!", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(download.localFilePath)
        if (!file.exists()) {
            Toast.makeText(this, "Downloaded file not found!", Toast.LENGTH_SHORT).show()
            return
        }

        // Use FileProvider for security
        val fileUri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.provider",
            file
        )

        // Determine MIME type based on file extension
        val mimeType = when {
            isAudioFile(file.name) -> "audio/*"
            else -> "video/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, "Play with"))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No external app found to handle this ${if (mimeType.startsWith("audio")) "audio" else "video"} file.",
                Toast.LENGTH_LONG
            ).show()
            Log.e("DownloadsActivity", "Failed to open external player", e)
        }
    }

    private fun observeDownloadsFromDb() {
        lifecycleScope.launch {
            db.downloadDao().getAllDownloadsFlow().collectLatest { downloadsList ->
                downloadsAdapter.submitList(downloadsList)
            }
        }
    }



    // *** ADD LISTENER REGISTRATION LIFECYCLE METHODS ***
    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        downloadManager.addListener(this) // Start listening for changes
    }

    @OptIn(UnstableApi::class)
    override fun onStop() {
        super.onStop()
        downloadManager.removeListener(this) // Stop listening to prevent leaks
    }

    private fun showDeleteConfirmationDialog(download: com.faselhd.app.models.Download) {
        AlertDialog.Builder(this)
            .setTitle("Delete Download")
            .setMessage("Are you sure you want to delete '${download.episodeName}'?")
            .setPositiveButton("Delete") { _, _ ->
                // Cancel the WorkManager job
                WorkManager.getInstance(this).cancelAllWorkByTag(download.episodeUrl)

                // Delete the file if it exists
                download.localFilePath?.let {
                    val file = File(it)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                // Remove from the database
                lifecycleScope.launch {
                    db.downloadDao().delete(download.episodeUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun showPlayOptionsDialog(download: com.faselhd.app.models.Download) {
        if (download.localFilePath == null) {
            Toast.makeText(this, "File not found!", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Play with In-App Player", "Play with External App")

        AlertDialog.Builder(this)
            .setTitle("How do you want to play?")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> playWithInternalPlayer(download)
                    1 -> playWithExternalPlayer(download)
                }
                dialog.dismiss()
            }
            .show()
    }

//    private fun playWithInternalPlayer(download: com.faselhd.app.models.Download) {
//        val fileUri = Uri.parse(download.localFilePath)
//        // We can reuse the VideoPlayerActivity by passing it a single video source
//        val localVideo = Video(url = fileUri.toString(), quality = "Downloaded", videoUrl = fileUri.toString(),resolution = "1080x920")
//
//        val intent = VideoPlayerActivity.newIntent(
//            context = this,
//            videos = listOf(localVideo), // Pass the local file as a video
//            anime = SAnime(title = download.animeTitle, url = ""),
//            currentEpisode = SEpisode(name = download.episodeName, url = download.episodeUrl),
//            episodeListForSeason = arrayListOf(), // No next episode from downloads screen
//            startPosition = 0L,
//        )
//        startActivity(intent)
//    }
//
//    private fun playWithExternalPlayer(download: com.faselhd.app.models.Download) {
//        val file = File(download.localFilePath!!)
//        // Use a FileProvider to securely grant access to other apps
//        val fileUri: Uri = FileProvider.getUriForFile(
//            this,
//            "${applicationContext.packageName}.provider",
//            file
//        )
//
//        val intent = Intent(Intent.ACTION_VIEW).apply {
//            setDataAndType(fileUri, "video/*")
//            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//        }
//
//        try {
//            startActivity(intent)
//        } catch (e: Exception) {
//            Toast.makeText(this, "No external player found to handle this file.", Toast.LENGTH_LONG).show()
//        }
//    }



}

