package com.faselhd.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.faselhd.app.adapters.DownloadsAdapter
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.DownloadState
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.services.VideoDownloadService
import com.faselhd.app.utils.DownloadUtil
import com.faselhd.app.utils.PlayerDataHolder
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadsActivity : AppCompatActivity(), DownloadManager.Listener {
    private lateinit var downloadsRecyclerView: RecyclerView
    private lateinit var downloadsAdapter: DownloadsAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var downloadManager: DownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        downloadManager = DownloadUtil.getDownloadManager(this)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        setupRecyclerView()
        observeDownloadsFromDb()
    }

    @OptIn(UnstableApi::class)
    override fun onDownloadChanged(
        downloadManager: DownloadManager,
        download: Download,
        finalException: Exception?
    ) {
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

            val newProgress = if (download.percentDownloaded < 0) 0 else download.percentDownloaded.toInt()

            if (localDownload.downloadState != newState || localDownload.progress != newProgress) {
                localDownload.downloadState = newState
                localDownload.progress = newProgress
                db.downloadDao().upsert(localDownload)
            }
        }
    }

    private fun setupRecyclerView() {
        downloadsAdapter = DownloadsAdapter { download, action ->
            when (action) {
                DownloadsAdapter.DownloadAction.PLAY -> showPlayOptionsDialog(download)
                DownloadsAdapter.DownloadAction.DELETE -> showDeleteConfirmationDialog(download)
                DownloadsAdapter.DownloadAction.PAUSE -> pauseDownload(download)
                DownloadsAdapter.DownloadAction.RESUME -> resumeDownload(download)
            }
        }
        downloadsRecyclerView = findViewById(R.id.downloads_recycler_view)
        downloadsRecyclerView.adapter = downloadsAdapter
    }

    private fun observeDownloadsFromDb() {
        lifecycleScope.launch {
            db.downloadDao().getAllDownloadsFlow().collectLatest { downloadsList ->
                downloadsAdapter.submitList(downloadsList)
            }
        }
    }

    private fun showPlayOptionsDialog(download: com.faselhd.app.models.Download) {
        val options = arrayOf("external Player (مشغل خارجي)", "Internal Player(مشغل التطبيق)")
        AlertDialog.Builder(this)
//            .setTitle("How do you want to play?")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> playWithExternalPlayer(download)
                    1 -> playWithInternalPlayer(download)

                }
                dialog.dismiss()
            }
//            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playWithInternalPlayer(download: com.faselhd.app.models.Download) {
        val mediaUri = download.mediaUri
        if (mediaUri.isNullOrEmpty()) {
            Toast.makeText(this, "Media source not found!", Toast.LENGTH_SHORT).show()
            return
        }

        val isAudio = isAudioFile(mediaUri)
        val offlineSource = Video(
            url = mediaUri,
            quality = if (isAudio) "Downloaded Audio" else "Downloaded Video",
            videoUrl = mediaUri,
            headers = null // No headers needed for cached content
        )

        val offlineAnime = SAnime(title = download.animeTitle, url = "", thumbnail_url = download.thumbnailUrl)
        val offlineEpisode = SEpisode(name = download.episodeName, url = download.episodeUrl)

        PlayerDataHolder.videos = listOf(offlineSource)
        PlayerDataHolder.anime = offlineAnime
        PlayerDataHolder.episodeList = listOf(offlineEpisode)

        val intent = VideoPlayerActivity.newIntent(
            context = this,
            currentEpisodeUrl = offlineEpisode.url!!,
            startPosition = 0L,
            source = null
        )
        startActivity(intent)
    }

    private fun playWithExternalPlayer(download: com.faselhd.app.models.Download) {
        val filePath = download.localFilePath
        if (filePath.isNullOrEmpty() || !File(filePath).exists()) {
            Toast.makeText(this, "Downloaded file not found! Try playing with the internal player.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val file = File(filePath)
            val fileUri: Uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
            val mimeType = if (isAudioFile(file.name)) "audio/*" else "video/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external app found to play this file.", Toast.LENGTH_LONG).show()
            Log.e("DownloadsActivity", "Failed to start external player", e)
        }
    }

    @OptIn(UnstableApi::class)
    private fun showDeleteConfirmationDialog(download: com.faselhd.app.models.Download) {
        AlertDialog.Builder(this)
            .setTitle("Delete Download")
            .setMessage("Are you sure you want to delete '${download.episodeName}'?")
            .setPositiveButton("Delete") { _, _ ->
                // This is the correct way to remove an ExoPlayer download
                DownloadService.sendRemoveDownload(
                    this,
                    VideoDownloadService::class.java,
                    download.episodeUrl,
                    false
                )
                // The onDownloadChanged listener will handle removing it from the DB
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun pauseDownload(download: com.faselhd.app.models.Download) {
        DownloadService.sendSetStopReason(
            this,
            VideoDownloadService::class.java,
            download.episodeUrl,
            Download.STATE_STOPPED,
            false
        )
    }

    @OptIn(UnstableApi::class)
    private fun resumeDownload(download: com.faselhd.app.models.Download) {
        DownloadService.sendSetStopReason(
            this,
            VideoDownloadService::class.java,
            download.episodeUrl,
            Download.STOP_REASON_NONE, // 0 means no stop reason, i.e., resume
            false
        )
    }

    private fun isAudioFile(url: String): Boolean {
        return url.endsWith(".mp3", true) || url.endsWith(".m4a", true) || url.endsWith(".wav", true)
    }

    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        downloadManager.addListener(this)
    }

    @OptIn(UnstableApi::class)
    override fun onStop() {
        super.onStop()
        downloadManager.removeListener(this)
    }
}