package com.faselhd.app

// CORRECT IMPORT: You need to import AspectRatioFrameLayout to access the constants.

// --- REQUIRED IMPORTS ---

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.R // Ensure this matches your package
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.utils.EpisodeSkip
import com.faselhd.app.utils.NetworkUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MimeTypes
import java.io.File
import android.util.Log

class VideoPlayerActivity : AppCompatActivity() {

    // In VideoPlayerActivity.kt

    companion object {
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_EPISODE_LIST = "extra_episode_list"
        private const val EXTRA_START_POSITION = "extra_start_position"
        private const val EXTRA_SOURCE = "extra_source"

        fun newIntent(
            context: Context,
            videos: List<Video?>,
            anime: SAnime,
            currentEpisode: SEpisode,
            episodeListForSeason: ArrayList<SEpisode>,
            startPosition: Long = 0L,
            source: AnimeSource? = null
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_VIDEOS, ArrayList(videos.filterNotNull()))
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_EPISODE, currentEpisode)
                putParcelableArrayListExtra(EXTRA_EPISODE_LIST, episodeListForSeason)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_SOURCE, source)
            }
        }
    }

    // Player and UI Components
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager
    private lateinit var trackSelector: DefaultTrackSelector

    // UI Elements
    private lateinit var btnServer: ImageButton // Changed from btnQuality
    private lateinit var tvServerName: TextView // Changed from tvResolution
    private lateinit var topOverlay: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var brightnessOverlay: LinearLayout
    private lateinit var volumeOverlay: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnPlayPause: FrameLayout
    private lateinit var ivPlayPause: ImageView
    private lateinit var btnRewind: FrameLayout
    private lateinit var btnFastForward: FrameLayout
    private lateinit var btnLock: ImageButton
    private lateinit var btnFullscreen: ImageButton // ADDED BACK
    private lateinit var btnResize: ImageButton // ADDED BACK
    private lateinit var btnSubtitle: ImageButton // ADDED BACK
    private lateinit var btnNextEpisode: ImageButton
    private lateinit var tvEpisodeTitle: TextView
    private lateinit var tvSeekTime: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var brightnessProgress: ProgressBar
    private lateinit var volumeProgress: ProgressBar
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnUnlock: ImageButton
    private lateinit var rewindIndicator: LinearLayout
    private lateinit var forwardIndicator: LinearLayout
    private lateinit var speedIndicatorText: TextView
    private lateinit var btnSkipIntro: MaterialButton
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var ivVolumeIcon: ImageView


    // State variables
    private var isControlsVisible = true
    private var isLocked = false
    private var isFullscreen = true // ADDED BACK
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL // ADDED BACK
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isOnLongPressSpeedUp = false
    private var seekChange: Long = 0 // ADDED BACK

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    // Data from Intent
    private var videoList: List<Video> = emptyList()
    private var currentAnime: SAnime? = null
    private val sourceManager by lazy { SourceManager(applicationContext) } // <-- ADD SOURCEMANAGER
    private var currentEpisode: SEpisode? = null
    private var seasonEpisodeList: List<SEpisode> = emptyList()
    private var startPosition: Long = 0L
    private var specificSource: AnimeSource? = null

    // Database and Skip Times
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var skipStamps: List<EpisodeSkip.SkipStamp> = emptyList()
    private var currentSkipStamp: EpisodeSkip.SkipStamp? = null

    private var isSeeking = false
    private var seekStartPosition = 0L

    private lateinit var btnAudioTrack: ImageButton // <-- ADD THIS



    // In VideoPlayerActivity.kt -> onCreate() method

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Retrieve data
        videoList = intent.getParcelableArrayListExtra(EXTRA_VIDEOS) ?: emptyList()
        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        currentEpisode = intent.getParcelableExtra(EXTRA_EPISODE)
        seasonEpisodeList = intent.getParcelableArrayListExtra(EXTRA_EPISODE_LIST) ?: emptyList()
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        specificSource = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource

        if (videoList.isEmpty() || currentAnime == null || currentEpisode == null) {
            Toast.makeText(this, "Video source not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        handleOfflineContentSetup()
        setupAudioManager()
        setupGestureDetector()
        setupClickListeners()
        setupSeekBar()
        hideSystemUI()
        scheduleHideControls() // ADDED BACK

        // Show server selection dialog to start playback
        showSourceSelectionDialog()
    }


    private fun saveWatchProgress() {
        val p = player ?: return
        val anime = currentAnime ?: return
        println("current anime s ${currentAnime.toString()}")
        val episode = currentEpisode ?: return
        val position = p.currentPosition
        val duration = p.duration

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        // **** THIS IS THE KEY CHANGE ****
        // Determine the correct source to save. Prioritize the one passed via intent.
        val sourceToSave = specificSource?.displayName ?: sourceManager.getCurrentSourceName()
        println("source tv : $sourceToSave")
        val progressPercentage = (position * 100) / duration

        CoroutineScope(Dispatchers.IO).launch {
            if (progressPercentage > 90) {
                // --- EPISODE IS FINISHED ---
                val currentIndex = seasonEpisodeList.indexOfFirst { it.url == episode.url }
                if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
                    val nextEpisode = seasonEpisodeList[currentIndex + 1]

                    val nextEpisodeHistory = WatchHistory(
                        episodeUrl = nextEpisode.url!!, // URL of the next episode
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = nextEpisode.name,
                        lastWatchedPosition = 0L, // Start from the beginning
                        duration = 0L, // We don't know the duration yet, set to 0
                        timestamp = System.currentTimeMillis() + 1000, // Slightly later timestamp to ensure it's on top
                        isFinished = false,
                        episodeNumber = nextEpisode.episode_number.toInt(),
                        seasonEpisodes = seasonEpisodeList,
                        source = sourceToSave
                    )

                    println("next episode :${nextEpisodeHistory.toString()}")

                    db.watchHistoryDao().upsert(nextEpisodeHistory)

                    val watchHistory = WatchHistory(
                        episodeUrl = episode.url!!,
                        animeUrl = anime.url!!,
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = episode.name ?: "Unknown Episode",
                        lastWatchedPosition = position,
                        duration = duration,
                        timestamp = System.currentTimeMillis(),
                        isFinished = true,
                        episodeNumber = episode.episode_number.toInt(),
                        seasonEpisodes = seasonEpisodeList,
                        source = sourceToSave
                    )
                    println("next next episode :${watchHistory.toString()}")
                    db.watchHistoryDao().upsert(watchHistory)
                }
            } else {
                // --- EPISODE IS IN PROGRESS ---
                val watchHistory = WatchHistory(
                    episodeUrl = episode.url!!,
                    animeUrl = anime.url!! ,
                    animeTitle = anime.title ?: "Unknown Title",
                    animeThumbnailUrl = anime.thumbnail_url,
                    episodeName = episode.name ?: "Unknown Episode",
                    lastWatchedPosition = position,
                    duration = duration,
                    timestamp = System.currentTimeMillis(),
                    isFinished = false,
                    episodeNumber = episode.episode_number.toInt(),
                    seasonEpisodes = seasonEpisodeList,
                    source = sourceToSave
                )
                println(" episode :${watchHistory.toString()}")
                db.watchHistoryDao().upsert(watchHistory)
            }
        }
    }


    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- Override dispatchKeyEvent for D-pad handling ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isDpadEvent(event)) {
                scheduleHideControls()
            }

            if (isLocked) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return super.dispatchKeyEvent(event)
                }
                return true
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsVisible) {
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextEpisode()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    rewind()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    fastForward()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (isControlsVisible) {
            hideControls()
        } else {
            super.onBackPressed()
        }
    }

    private fun isDpadEvent(event: KeyEvent): Boolean {
        return event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }


    private fun initializeViews() {
        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        topOverlay = findViewById(R.id.top_overlay)
        centerControls = findViewById(R.id.center_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        btnBack = findViewById(R.id.btn_back)
        tvEpisodeTitle = findViewById(R.id.tv_episode_title)
        btnServer = findViewById(R.id.btn_quality)
        tvServerName = findViewById(R.id.tv_resolution)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        ivPlayPause = findViewById(R.id.iv_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnFastForward = findViewById(R.id.btn_fast_forward)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.seek_bar)
        tvBrightnessValue = findViewById(R.id.tv_brightness_value)
        tvVolumeValue = findViewById(R.id.tv_volume_value)
        ivVolumeIcon = findViewById(R.id.iv_volume_icon)
        btnNextEpisode = findViewById(R.id.btn_next_episode)
        btnLock = findViewById(R.id.btn_lock)
        btnFullscreen = findViewById(R.id.btn_fullscreen) // ADDED BACK
        btnResize = findViewById(R.id.btn_resize) // ADDED BACK
        btnSubtitle = findViewById(R.id.btn_subtitle) // ADDED BACK
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        lockOverlay = findViewById(R.id.lock_overlay)
        btnUnlock = findViewById(R.id.btn_unlock)
        brightnessOverlay = findViewById(R.id.brightness_overlay)
        brightnessProgress = findViewById(R.id.brightness_progress)
        volumeOverlay = findViewById(R.id.volume_overlay)
        volumeProgress = findViewById(R.id.volume_progress)
        rewindIndicator = findViewById(R.id.rewind_indicator)
        forwardIndicator = findViewById(R.id.forward_indicator)
        speedIndicatorText = findViewById(R.id.speed_indicator_text)
        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        tvSeekTime = findViewById(R.id.tv_seek_time)

        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        btnNextEpisode.visibility = if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) View.VISIBLE else View.GONE
    }

    private fun handleAudioOnlyContent() {
        // Hide video-specific controls when playing audio files
        btnResize.visibility = View.GONE

        // You might want to show a static image or album art for audio files
        // This could be implemented by checking if the content is audio-only
        // and displaying a placeholder image
    }

    private fun handleOfflineContentSetup() {
        // Check if this is offline content
        val firstVideo = videoList.firstOrNull()
        if (firstVideo != null) {
            val isLocalFile = firstVideo.url.startsWith("file://") ||
                    firstVideo.url.startsWith("content://") ||
                    firstVideo.url.startsWith("/") ||
                    File(firstVideo.url).exists()

            if (isLocalFile) {
                Log.d("VideoPlayerActivity", "Setting up for offline playback")

                // Disable server selection for offline content
                btnServer.isEnabled = false
                btnServer.alpha = 0.5f

                // Disable next episode button if no episode list
                if (seasonEpisodeList.isEmpty()) {
                    btnNextEpisode.visibility = View.GONE
                }

                // Handle audio-only content
                if (isAudioFile(firstVideo.url)) {
                    handleAudioOnlyContent()
                    Toast.makeText(this, "Playing audio file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }




    // Method to check if current content is audio-only
    private fun isCurrentContentAudioOnly(): Boolean {
        val currentVideo = videoList.firstOrNull()
        return currentVideo?.let { isAudioFile(it.url) } ?: false
    }


    private val enhancedPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

            when (playbackState) {
                Player.STATE_READY -> {
                    updatePlayPauseButton()
                    updateDuration()

                    // Only fetch skip times for online content
                    val firstVideo = videoList.firstOrNull()
                    val isOffline = firstVideo?.url?.let { url ->
                        url.startsWith("file://") || url.startsWith("content://") ||
                                url.startsWith("/") || File(url).exists()
                    } ?: false

                    if (!isOffline) {
                        fetchSkipTimes()
                    } else {
                        Log.d("VideoPlayerActivity", "Skipping online features for offline content")
                    }
                }
                Player.STATE_ENDED -> {
                    if (seasonEpisodeList.isNotEmpty()) {
                        playNextEpisode()
                    } else {
                        // For offline content without episode list, just finish
                        Toast.makeText(this@VideoPlayerActivity, "Playback completed", Toast.LENGTH_SHORT).show()
                    }
                }
                Player.STATE_BUFFERING -> {
                    Log.d("VideoPlayerActivity", "Buffering offline content")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            updateResolutionDisplay()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateResolutionDisplay()

            // Handle audio-only content (no video track)
            if (videoSize.width == 0 && videoSize.height == 0) {
                handleAudioOnlyContent()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e("VideoPlayerActivity", "ExoPlayer Error: ", error)

            // Provide more specific error messages for offline content
            val errorMessage = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "Downloaded file not found. It may have been moved or deleted."
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    "Permission denied accessing the downloaded file."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                    "Unsupported file format for offline playback."
                else -> "Playback error: ${error.message}"
            }

            Toast.makeText(this@VideoPlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSourceSelectionDialog() {
        if (videoList.size == 1) {
            initializePlayerForVideo(videoList.first())
            return
        }

        val sources = videoList.map { it.quality }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Source")
            .setItems(sources) { dialog, which ->
                val selectedVideo = videoList[which]
                initializePlayerForVideo(selectedVideo)
                dialog.dismiss()
            }
            .setOnCancelListener {
                if (player == null) {
                    finish()
                }
            }
            .show()
    }

    // ADDED BACK: Quality selection dialog for track selection
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showQualityDialog() {
        val trackSelector = this.trackSelector
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo == null) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        var videoRendererIndex = -1
        for (i in 0 until mappedTrackInfo.rendererCount) {
            if (player!!.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
                videoRendererIndex = i
                break
            }
        }

        if (videoRendererIndex == -1) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        val trackGroups = mappedTrackInfo.getTrackGroups(videoRendererIndex)
        if (trackGroups.isEmpty) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        val qualityOptions = mutableListOf<String>()
        val trackIndices = mutableListOf<Int>()
        qualityOptions.add("Auto")

        for (i in 0 until trackGroups.length) {
            val group = trackGroups.get(i)
            for (j in 0 until group.length) {
                val format = group.getFormat(j)
                qualityOptions.add("${format.height}p")
                trackIndices.add(j)
            }
        }

        val selectionOverride = trackSelector.parameters.getSelectionOverride(videoRendererIndex, trackGroups)
        var checkedItem = 0
        if (selectionOverride != null && selectionOverride.length > 0) {
            checkedItem = trackIndices.indexOf(selectionOverride.tracks[0]) + 1
        }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Select Quality")
        builder.setSingleChoiceItems(qualityOptions.toTypedArray(), checkedItem) { dialog, which ->
            val parametersBuilder = trackSelector.buildUponParameters()
            if (which == 0) {
                parametersBuilder.clearSelectionOverrides(videoRendererIndex)
            } else {
                val override = DefaultTrackSelector.SelectionOverride(
                    videoRendererIndex,
                    trackIndices[0]
                )
                parametersBuilder.setSelectionOverride(
                    videoRendererIndex,
                    trackGroups,
                    override
                )
            }
            trackSelector.parameters = parametersBuilder.build()
            dialog.dismiss()
        }
        builder.create().show()
    }

    // In VideoPlayerActivity.kt


//    @androidx.annotation.OptIn(UnstableApi::class)
//    private fun initializePlayerForVideo(video: Video) {
//        player?.release()
//        player = null
//
//        tvServerName.text = "${video.quality} (Auto)"
//
//        // --- START OF THE FIX ---
//
//        // 1. Get the "unsafe" client that trusts all certificates
//        val unsafeOkHttpClient = NetworkUtils.getUnsafeOkHttpClient()
//
//        // 2. Create an ExoPlayer data source factory that uses our unsafe client
//        val dataSourceFactory = OkHttpDataSource.Factory(unsafeOkHttpClient)
//
//        // 3. Add the required headers (like the Referer) to the factory
//        video.headers?.let { headersMap ->
//            dataSourceFactory.setDefaultRequestProperties(headersMap)
//        }
//
//        // --- END OF THE FIX ---
//
//        val mediaItem = MediaItem.fromUri(video.url)
//        val mediaSource = if (video.url.endsWith(".m3u8", ignoreCase = true)) {
//            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
//        } else {
//            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
//        }
//
//        trackSelector = DefaultTrackSelector(this).apply {
//            setParameters(
//                buildUponParameters()
//                    .setAllowMultipleAdaptiveSelections(true)
//                    .setMaxVideoBitrate(Int.MAX_VALUE)
//                    .setForceHighestSupportedBitrate(false)
//            )
//        }
//
//        player = ExoPlayer.Builder(this)
//            .setTrackSelector(trackSelector)
//            .build().apply {
//                setMediaSource(mediaSource)
//                addListener(playerListener)
//                playWhenReady = true
//                // Correctly handle seek position
//                seekTo(startPosition)
//                startPosition = 0L // Reset start position after seeking
//                prepare()
//            }
//
//        playerView.player = player
//        playerView.resizeMode = currentResizeMode
//        updateProgress()
//    }

//    @androidx.annotation.OptIn(UnstableApi::class)
//    private fun initializePlayerForVideo(video: Video) {
//        player?.release()
//        player = null
//
//        tvServerName.text = "${video.quality} (Auto)"
//
//        val dataSourceFactory = if (video.url.startsWith("https")) {
//            val unsafeOkHttpClient = NetworkUtils.getUnsafeOkHttpClient()
//            val okHttpDataSourceFactory = OkHttpDataSource.Factory(unsafeOkHttpClient)
//                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//            video.headers?.let { okHttpDataSourceFactory.setDefaultRequestProperties(it) }
//            DefaultDataSource.Factory(this, okHttpDataSourceFactory)
//        } else {
//            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
//                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//            video.headers?.let { httpDataSourceFactory.setDefaultRequestProperties(it) }
//            DefaultDataSource.Factory(this, httpDataSourceFactory)
//        }
//
//        // ========= MODIFICATION START =========
//
//        // 1. Build subtitle configurations from the video's subtitle list
//        val subtitleConfigurations = video.subtitles?.mapNotNull { subtitle ->
//            val subtitleUri = Uri.parse(subtitle.url)
//            val mimeType = when {
//                subtitle.url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
//                subtitle.url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
//                else -> MimeTypes.TEXT_VTT // Default to VTT if extension is unknown
//            }
//            MediaItem.SubtitleConfiguration.Builder(subtitleUri)
//                .setMimeType(mimeType)
//                .setLanguage(subtitle.lang)
//                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT) // Attempt to enable by default
//                .build()
//        } ?: emptyList()
//
//        // 2. Build the MediaItem with the main video URI and the subtitle configurations
//        val mediaItem = MediaItem.Builder()
//            .setUri(video.url)
//            .setSubtitleConfigurations(subtitleConfigurations)
//            .build()
//
//        // ========= MODIFICATION END =========
//
//        // ========= MODIFICATION START =========
//// Use .contains() for a more robust check against URLs with query parameters
//        val mediaSource = if (video.url.contains(".m3u8", ignoreCase = true)) {
//            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
//        } else {
//            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
//        }
//// ========= MODIFICATION END =========
//
//        trackSelector = DefaultTrackSelector(this).apply {
//            setParameters(
//                buildUponParameters()
//                    .setAllowMultipleAdaptiveSelections(true)
//                    .setMaxVideoBitrate(Int.MAX_VALUE)
//                    .setForceHighestSupportedBitrate(false)
//            )
//        }
//
//        player = ExoPlayer.Builder(this)
//            .setTrackSelector(trackSelector)
//            .build().apply {
//                setMediaSource(mediaSource)
//                addListener(playerListener)
//                playWhenReady = true
//                seekTo(if (startPosition != -1L) startPosition else 0L)
//                startPosition = -1L
//                prepare()
//            }
//
//        playerView.player = player
//        playerView.resizeMode = currentResizeMode
//        updateProgress()
//    }

    // In VideoPlayerActivity.kt

    // Enhanced initializePlayerForVideo method to handle offline content
//    @androidx.annotation.OptIn(UnstableApi::class)
//    private fun initializePlayerForVideo(video: Video) {
//        player?.release()
//        player = null
//
//        tvServerName.text = "${video.quality} (Auto)"
//
//        // Check if this is a local file (offline content)
//        val isLocalFile = video.url.startsWith("file://") ||
//                video.url.startsWith("content://") ||
//                video.url.startsWith("/") ||
//                File(video.url).exists()
//
//        val dataSourceFactory = if (isLocalFile) {
//            // For local files, use a simple DefaultDataSource factory without network components
//            Log.d("VideoPlayerActivity", "Playing offline content: ${video.url}")
//            DefaultDataSource.Factory(this)
//        } else {
//            // For online content, use the existing network setup
//            Log.d("VideoPlayerActivity", "Playing online content: ${video.url}")
//            if (video.url.startsWith("https")) {
//                val unsafeOkHttpClient = NetworkUtils.getUnsafeOkHttpClient()
//                val okHttpDataSourceFactory = OkHttpDataSource.Factory(unsafeOkHttpClient)
//                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//                video.headers?.let { okHttpDataSourceFactory.setDefaultRequestProperties(it) }
//                DefaultDataSource.Factory(this, okHttpDataSourceFactory)
//            } else {
//                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
//                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
//                video.headers?.let { httpDataSourceFactory.setDefaultRequestProperties(it) }
//                DefaultDataSource.Factory(this, httpDataSourceFactory)
//            }
//        }
//
//        // Handle subtitles - only for online content or if subtitles are also stored locally
//        val subtitleConfigurations = if (!isLocalFile) {
//            video.subtitles?.mapNotNull { subtitle ->
//                val subtitleUri = Uri.parse(subtitle.url)
//                val mimeType = when {
//                    subtitle.url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
//                    subtitle.url.contains(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
//                    else -> null
//                }
//                if (mimeType != null) {
//                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
//                        .setMimeType(mimeType)
//                        .setLanguage(subtitle.lang)
//                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
//                        .build()
//                } else {
//                    null
//                }
//            } ?: emptyList()
//        } else {
//            // For offline content, check if there are local subtitle files
//            findLocalSubtitleFiles(video.url)
//        }
//
//        val mediaItem = MediaItem.Builder()
//            .setUri(video.url)
//            .setSubtitleConfigurations(subtitleConfigurations)
//            .build()
//
//        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
//
//        trackSelector = DefaultTrackSelector(this).apply {
//            setParameters(
//                buildUponParameters()
//                    .setAllowMultipleAdaptiveSelections(true)
//                    .setMaxVideoBitrate(Int.MAX_VALUE)
//                    .setForceHighestSupportedBitrate(false)
//            )
//        }
//
//        player = ExoPlayer.Builder(this)
//            .setTrackSelector(trackSelector)
//            .setMediaSourceFactory(mediaSourceFactory)
//            .build().apply {
//                setMediaItem(mediaItem)
//                addListener(playerListener)
//                playWhenReady = true
//                val seekPosition = if (startPosition != -1L) startPosition else 0L
//                seekTo(seekPosition)
//                startPosition = -1L
//                prepare()
//            }
//
//        playerView.player = player
//        playerView.resizeMode = currentResizeMode
//        updateProgress()
//    }

    // In VideoPlayerActivity.kt

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayerForVideo(video: Video) {
        // 1. Release any existing player instance
        player?.release()
        player = null

        tvServerName.text = video.quality

        // 2. Determine if the content is local or online
        val isLocalFile = video.url.startsWith("file://") ||
                video.url.startsWith("content://") ||
                video.url.startsWith("/") ||
                File(video.url).exists()

        // 3. Create the appropriate DataSource.Factory
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (isLocalFile) {
            Log.d("VideoPlayerActivity", "Using local data source for: ${video.url}")
            DefaultDataSource.Factory(this)
        } else {
            Log.d("VideoPlayerActivity", "Using network data source for: ${video.url}")
            val okHttpClient = NetworkUtils.getUnsafeOkHttpClient()
            val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")

            video.headers?.let { headers ->
                Log.d("VideoPlayerActivity", "Applying headers: $headers")
                httpDataSourceFactory.setDefaultRequestProperties(headers)
            }
            httpDataSourceFactory
        }

        // 4. *** THIS IS THE CRITICAL FIX ***
        //    Choose the subtitle source based on whether the file is local or online.
        val subtitleConfigurations = if (isLocalFile) {
            // For local files, search the device storage.
            findLocalSubtitleFiles(video.url)
        } else {
            // For online streams, map the subtitle data received from the network.
            video.subtitles?.mapNotNull { subtitle ->
                val subtitleUri = Uri.parse(subtitle.url)
                val mimeType = when {
                    subtitle.url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
                    subtitle.url.contains(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
                    else -> null // Ignore unknown subtitle formats
                }
                if (mimeType != null) {
                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(mimeType)
                        .setLanguage(subtitle.lang)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT) // Attempt to select it by default
                        .build()
                } else {
                    null
                }
            } ?: emptyList()
        }

        // 5. Build the MediaItem with the URI and the correctly sourced subtitles
        val mediaItem = MediaItem.Builder()
            .setUri(video.url)
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()

        // 6. Setup MediaSourceFactory and TrackSelector
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setAllowMultipleAdaptiveSelections(true)
                .build()
        }

        // 7. Build and prepare the ExoPlayer instance
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(mediaItem)
                addListener(enhancedPlayerListener) // Use your enhanced listener
                playWhenReady = true
                seekTo(if (startPosition > 0) startPosition else 0L)
                startPosition = 0L // Reset after seeking
                prepare()
            }

        // 8. Assign the player to the view
        playerView.player = player
        playerView.resizeMode = currentResizeMode
        updateProgress()
    }


    // Helper method to find local subtitle files
    private fun findLocalSubtitleFiles(videoPath: String): List<MediaItem.SubtitleConfiguration> {
        val subtitleConfigurations = mutableListOf<MediaItem.SubtitleConfiguration>()

        try {
            val videoFile = if (videoPath.startsWith("file://")) {
                File(Uri.parse(videoPath).path ?: return emptyList())
            } else {
                File(videoPath)
            }

            if (!videoFile.exists()) return emptyList()

            val videoDirectory = videoFile.parentFile ?: return emptyList()
            val videoNameWithoutExt = videoFile.nameWithoutExtension

            // Look for subtitle files with the same name as the video file
            val subtitleExtensions = arrayOf("srt", "vtt", "ass", "ssa")

            for (extension in subtitleExtensions) {
                val subtitleFile = File(videoDirectory, "$videoNameWithoutExt.$extension")
                if (subtitleFile.exists()) {
                    val mimeType = when (extension) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.APPLICATION_SS
                        else -> continue
                    }

                    val subtitleUri = Uri.fromFile(subtitleFile)
                    val config = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(mimeType)
                        .setLanguage("en") // Default to English, could be made configurable
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()

                    subtitleConfigurations.add(config)
                    Log.d("VideoPlayerActivity", "Found local subtitle: ${subtitleFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error finding local subtitles", e)
        }

        return subtitleConfigurations
    }

    // Enhanced method to detect file type for better handling
    private fun isAudioFile(url: String): Boolean {
        val audioExtensions = listOf("mp3", "wav", "aac", "ogg", "m4a", "flac", "wma")
        val extension = url.substringAfterLast('.', "").lowercase()
        return audioExtensions.contains(extension)
    }

    private fun isVideoFile(url: String): Boolean {
        val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v")
        val extension = url.substringAfterLast('.', "").lowercase()
        return videoExtensions.contains(extension)
    }
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                updatePlayPauseButton()
                updateDuration()
                fetchSkipTimes()
            } else if (playbackState == Player.STATE_ENDED) {
                playNextEpisode()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
        }

        // ADDED BACK: Track changes listener for quality updates
        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            updateResolutionDisplay()
        }

        // ADDED BACK: Video size changed listener
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateResolutionDisplay()
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e("VideoPlayerActivity", "ExoPlayer Error: ", error)
            Toast.makeText(
                this@VideoPlayerActivity,
                "Player Error: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun rewind() {
        player?.let { p ->
            p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
            showSeekIndicator(rewindIndicator)
        }
    }

    private fun fastForward() {
        player?.let { p ->
            p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
            showSeekIndicator(forwardIndicator)
        }
    }

    private fun showSeekIndicator(view: View) {
        view.visibility = View.VISIBLE
        view.animate().alpha(0f).setDuration(800).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    // ADDED BACK: Resize mode cycling
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Stretch", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "Fit to Screen", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = currentResizeMode
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnServer.setOnClickListener { showSourceSelectionDialog(); scheduleHideControls() }
        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHideControls()
        }
        btnRewind.setOnClickListener { rewind(); scheduleHideControls() }
        btnFastForward.setOnClickListener { fastForward(); scheduleHideControls() }
        btnLock.setOnClickListener { toggleLock() }
        btnNextEpisode.setOnClickListener { playNextEpisode() }
        btnUnlock.setOnClickListener { toggleLock() }
        btnFullscreen.setOnClickListener {
            Toast.makeText(this, "Player is always in fullscreen mode", Toast.LENGTH_SHORT).show()
            scheduleHideControls()
        }
        btnResize.setOnClickListener {
            cycleResizeMode()
            scheduleHideControls()
        }

        // ========= MODIFICATION START =========
        btnSubtitle.setOnClickListener {
            showSubtitleSelectionDialog()
            scheduleHideControls()
        }
        // ========= MODIFICATION END =========

        btnAudioTrack.setOnClickListener {
            showAudioTrackSelectionDialog()
            scheduleHideControls()
        }


        btnSkipIntro.setOnClickListener {
            currentSkipStamp?.let {
                player?.seekTo(it.endMs)
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }
    }


    // ========= ADD THIS ENTIRE NEW FUNCTION =========
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showAudioTrackSelectionDialog() {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        val playerInstance = player

        if (mappedTrackInfo == null || playerInstance == null) {
            Toast.makeText(this, "Player not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Find the renderer index for AUDIO tracks
        var audioRendererIndex = -1
        for (i in 0 until mappedTrackInfo.rendererCount) {
            if (playerInstance.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                audioRendererIndex = i
                break
            }
        }

        if (audioRendererIndex == -1) {
            Toast.makeText(this, "No alternate audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val trackGroups = mappedTrackInfo.getTrackGroups(audioRendererIndex)
        if (trackGroups.isEmpty) {
            Toast.makeText(this, "No alternate audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Build the list of available audio options
        val options = mutableListOf<Pair<String, DefaultTrackSelector.SelectionOverride?>>()
        var checkedItem = 0 // Default to the first track

        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups.get(groupIndex)
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                // Use language name or label, provide a fallback
                val displayName = format.label ?: format.language ?: "Track ${options.size + 1}"
                options.add(displayName to DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex))
            }
        }

        // 3. Determine the currently selected item
        val currentTracks = playerInstance.currentTracks
        for (trackGroup in currentTracks.groups) {
            if (trackGroup.type == C.TRACK_TYPE_AUDIO && trackGroup.isSelected) {
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val selectedFormat = trackGroup.getTrackFormat(i)
                        val currentIndex = options.indexOfFirst {
                            val override = it.second
                            if (override != null) {
                                val group = trackGroups.get(override.groupIndex)
                                val format = group.getFormat(override.tracks[0])
                                format == selectedFormat
                            } else false
                        }
                        if (currentIndex != -1) {
                            checkedItem = currentIndex
                        }
                        break
                    }
                }
            }
        }

        val displayNames = options.map { it.first }.toTypedArray()

        // 4. Show the selection dialog
        AlertDialog.Builder(this)
            .setTitle("Audio Track")
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                val (_, override) = options[which]
                if (override != null) {
                    val parametersBuilder = trackSelector.buildUponParameters()
                        .setSelectionOverride(audioRendererIndex, trackGroups, override)
                    trackSelector.parameters = parametersBuilder.build()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    // In VideoPlayerActivity.kt

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showSubtitleSelectionDialog() {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        val playerInstance = player

        // Ensure we have the necessary components to proceed
        if (mappedTrackInfo == null || playerInstance == null) {
            Toast.makeText(this, "Player not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Find the renderer index for text tracks (subtitles)
        var textRendererIndex = -1
        for (i in 0 until mappedTrackInfo.rendererCount) {
            if (playerInstance.getRendererType(i) == C.TRACK_TYPE_TEXT) {
                textRendererIndex = i
                break
            }
        }

        if (textRendererIndex == -1) {
            Toast.makeText(this, "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }

        val trackGroups = mappedTrackInfo.getTrackGroups(textRendererIndex)
        if (trackGroups.isEmpty) {
            Toast.makeText(this, "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Build the list of available subtitle options for the dialog
        val options = mutableListOf<Pair<String, DefaultTrackSelector.SelectionOverride?>>()
        options.add("Off" to null) // First option is always to disable subtitles

        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups.get(groupIndex)
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val displayName = format.label ?: format.language ?: "Subtitle ${options.size}"
                options.add(displayName to DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex))
            }
        }

        // 3. Determine the currently selected item to pre-check it in the dialog
        var checkedItem = 0 // Default to "Off"
        val currentTracks = playerInstance.currentTracks
        for (trackGroup in currentTracks.groups) {
            // Find the subtitle track group that is currently selected
            if (trackGroup.type == C.TRACK_TYPE_TEXT && trackGroup.isSelected) {
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val selectedFormat = trackGroup.getTrackFormat(i)
                        // Find the corresponding option in our list by matching the format
                        for (j in 1 until options.size) { // Start from 1 to skip "Off"
                            val override = options[j].second!!
                            val group = trackGroups.get(override.groupIndex)
                            val format = group.getFormat(override.tracks[0])
                            if (format == selectedFormat) {
                                checkedItem = j
                                break
                            }
                        }
                        break
                    }
                }
            }
        }

        val displayNames = options.map { it.first }.toTypedArray()

        // 4. Show the selection dialog
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                val (_, override) = options[which]
                val parametersBuilder = trackSelector.buildUponParameters()
                if (override == null) {
                    // User selected "Off", so disable the text renderer
                    parametersBuilder.setRendererDisabled(textRendererIndex, true)
                        .clearSelectionOverrides(textRendererIndex)
                } else {
                    // User selected a specific subtitle track
                    parametersBuilder
                        .setRendererDisabled(textRendererIndex, false)
                        .setSelectionOverride(textRendererIndex, trackGroups, override)
                }
                trackSelector.parameters = parametersBuilder.build()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    // ========= END OF NEW FUNCTION =========


    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            btnUnlock.requestFocus()
            hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
        } else {
            lockOverlay.visibility = View.GONE
            showControls()
        }
    }

    private fun fetchSkipTimes() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val duration = player?.duration ?: return

        if (duration <= 0) return

        lifecycleScope.launch {
            skipStamps = EpisodeSkip.getStamps(
                anime,
                episode.episode_number.toInt(),
                duration
            )
        }
    }

    private fun checkSkipButtonVisibility(currentPosition: Long) {
        if (skipStamps.isEmpty()) return

        val activeStamp = skipStamps.find { currentPosition in it.startMs..it.endMs }

        if (activeStamp != null) {
            if (btnSkipIntro.visibility == View.GONE) {
                currentSkipStamp = activeStamp
                btnSkipIntro.text = activeStamp.type.text
                btnSkipIntro.visibility = View.VISIBLE
            }
        } else {
            if (btnSkipIntro.visibility == View.VISIBLE) {
                btnSkipIntro.visibility = View.GONE
                currentSkipStamp = null
            }
        }
    }

    private fun playNextEpisode() {
        // 1. Find the current episode's index in the season list
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }
        saveWatchProgress()
        // 2. Check if there is a next episode
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]

            // 3. Launch a coroutine to load the new episode's data
            lifecycleScope.launch {
                loadVideoForEpisode(nextEpisode)
            }
        } else {
            // 4. Handle the case where it's the last episode
            Toast.makeText(this, "You've finished the season!", Toast.LENGTH_SHORT).show()
            // Optional: you could finish the activity here if you want
            // finish()
        }

    }

    private suspend fun loadVideoForEpisode(episode: SEpisode) {
        // Show a loading indicator while we fetch the new data
        loadingIndicator.visibility = View.VISIBLE
        player?.pause() // Pause the current player

        try {
            // Fetch the list of video servers/qualities for the new episode
            val newVideoList = sourceManager.fetchVideoList(episode.url!!, specificSource)

            if (newVideoList.isNotEmpty()) {
                // Update the activity's state to the new episode
                currentEpisode = episode
                videoList = newVideoList
                startPosition = 0L // Always start the next episode from the beginning

                // Update UI elements
                updateEpisodeUI()

                // Re-initialize the player with the new video
                // If there's only one server, play it directly. Otherwise, show the selection dialog.
                if (videoList.size == 1) {
                    initializePlayerForVideo(videoList.first())
                } else {
                    showSourceSelectionDialog()
                }
            } else {
                Toast.makeText(this, "Could not find video for the next episode.", Toast.LENGTH_LONG).show()
                loadingIndicator.visibility = View.GONE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading next episode: ${e.message}", Toast.LENGTH_LONG).show()
            loadingIndicator.visibility = View.GONE
        }
    }

    private fun updateEpisodeUI() {
        // Update the title at the top of the player
        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"

        // Re-check if the "next episode" button should be visible
        val currentIndex = seasonEpisodeList.indexOfFirst { it.url == currentEpisode?.url }
        btnNextEpisode.visibility = if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Clear any old skip intro buttons
        btnSkipIntro.visibility = View.GONE
        currentSkipStamp = null
        skipStamps = emptyList()

        // Reset the seek bar and time displays
        seekBar.progress = 0
        tvCurrentTime.text = formatTime(0)
        tvTotalTime.text = formatTime(0)
    }

    // ENHANCED: Gesture detector with all features from the original code
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    btnUnlock.visibility = View.VISIBLE
                    hideHandler.postDelayed({ btnUnlock.visibility = View.GONE }, 2000)
                } else {
                    toggleControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    if (e.x < playerView.width / 2) rewind() else fastForward()
                }
                return true
            }

            // ENHANCED: Full scroll implementation with brightness, volume, and seeking
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null || player == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                // Determine if the scroll is more horizontal or vertical
                if (abs(dx) > abs(dy)) {
                    // --- START: MODIFIED HORIZONTAL SCROLL LOGIC ---
                    // HORIZONTAL SCROLL (SEEKING)
                    if (!isSeeking) {
                        // Capture the starting position at the beginning of the gesture
                        isSeeking = true
                        seekStartPosition = player!!.currentPosition
                    }

                    brightnessOverlay.visibility = View.GONE
                    volumeOverlay.visibility = View.GONE
                    tvSeekTime.visibility = View.VISIBLE

                    val duration = player!!.duration
                    val sensitivityMultiplier = 2.0
                    // Calculate the total offset from the start of the gesture
                    val seekOffset = (dx * (duration / (playerView.width.toFloat() * sensitivityMultiplier))).toLong()

                    // Calculate the new position based on the start position plus the total offset
                    val newPosition = (seekStartPosition + seekOffset).coerceIn(0, duration)
                    player!!.seekTo(newPosition) // Seek to the calculated absolute position

                    val changeSeconds = seekOffset / 1000
                    val changeSign = if (seekOffset >= 0) "+" else "-"
                    val changeMinutesPart = abs(changeSeconds) / 60
                    val changeSecondsPart = abs(changeSeconds) % 60

                    val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
                    val formattedPosition = formatTime(newPosition)

                    tvSeekTime.text = "$formattedChange [$formattedPosition]"
                    // --- END: MODIFIED HORIZONTAL SCROLL LOGIC ---

                } else {
                    // VERTICAL SCROLL (BRIGHTNESS/VOLUME)
                    if (e2.x < playerView.width / 2) {
                        adjustBrightness(-dy) // Invert dy for natural feel
                    } else {
                        adjustVolume(-dy) // Invert dy for natural feel
                    }
                }
                return true
            }

            // ADDED BACK: Long press for speed up
            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return

                isOnLongPressSpeedUp = true
                player?.setPlaybackParameters(PlaybackParameters(2f))
                speedIndicatorText.text = "Speed: 2.0x"
                speedIndicatorText.visibility = View.VISIBLE
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            // Handle touch release events
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // --- ADD THIS LINE ---
                isSeeking = false // Reset seek state when the gesture is finished

                if (isOnLongPressSpeedUp) {
                    isOnLongPressSpeedUp = false
                    player?.setPlaybackParameters(PlaybackParameters(1f))
                    speedIndicatorText.visibility = View.GONE
                }

                // Hide seek time indicator after scroll
                if (tvSeekTime.visibility == View.VISIBLE) {
                    hideHandler.postDelayed({ tvSeekTime.visibility = View.GONE }, 500)
                }
            }
            true
        }
    }

    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            currentBrightness = 0.5f
            e.printStackTrace()
        }
        updateVolumeProgress()
        updateBrightnessProgress()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val duration = player!!.duration
                    if (duration > 0) {
                        player!!.seekTo((progress * duration) / 100)
                        updateCurrentTime()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { scheduleHideControls() }
        })
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        btnLock.visibility = View.VISIBLE

        if (!isLocked) {
            topOverlay.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            btnPlayPause.requestFocus()
        } else {
            btnUnlock.requestFocus()
        }

        isControlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        if (isLocked) return

        topOverlay.visibility = View.GONE
        bottomControls.visibility = View.GONE
        centerControls.visibility = View.GONE
        brightnessOverlay.visibility = View.GONE
        volumeOverlay.visibility = View.GONE
        tvSeekTime.visibility = View.GONE
        btnLock.visibility = View.GONE

        playerView.clearFocus()

        isControlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    // ADDED BACK: Brightness and volume adjustment functions
    private fun adjustBrightness(deltaY: Float) {
        currentBrightness = (currentBrightness + (deltaY / (playerView.height * 2f))).coerceIn(0f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = currentBrightness }
        updateBrightnessProgress()
        showBrightnessOverlay()
    }

    private fun adjustVolume(deltaY: Float) {
        val change = (deltaY / (playerView.height * 0.5f)) * maxVolume
        currentVolume = (currentVolume + change.toInt()).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        updateVolumeProgress()
        showVolumeOverlay()
    }

    private fun updateBrightnessProgress() {
        val progress = (currentBrightness * 100).toInt()
        brightnessProgress.progress = progress
        tvBrightnessValue.text = "$progress%"
    }

    private fun updateVolumeProgress() {
        val progress = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0
        volumeProgress.progress = progress
        tvVolumeValue.text = "$progress%"
        ivVolumeIcon.setImageResource(
            when {
                currentVolume == 0 -> R.drawable.ic_volume_off
                currentVolume < maxVolume / 2 -> R.drawable.ic_volume_down
                else -> R.drawable.ic_volume_up
            }
        )
    }

    private fun showBrightnessOverlay() {
        brightnessOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideBrightnessOverlay)
        hideHandler.postDelayed(hideBrightnessOverlay, 1000)
    }

    private val hideBrightnessOverlay = Runnable { brightnessOverlay.visibility = View.GONE }

    private fun showVolumeOverlay() {
        volumeOverlay.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideVolumeOverlay)
        hideHandler.postDelayed(hideVolumeOverlay, 1000)
    }

    private val hideVolumeOverlay = Runnable { volumeOverlay.visibility = View.GONE }

    // ADDED BACK: Show seek time function
    private fun showSeekTime(text: String) {
        tvSeekTime.text = text
        tvSeekTime.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideSeekTime)
        hideHandler.postDelayed(hideSeekTime, 1000)
    }

    private val hideSeekTime = Runnable { tvSeekTime.visibility = View.GONE }

    private fun updatePlayPauseButton() {
        player?.let { p ->
            ivPlayPause.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large)
        }
    }

    private fun updateProgress() {
        player?.let { p ->
            val duration = p.duration
            val position = p.currentPosition
            if (duration > 0) {
                seekBar.progress = ((position * 100) / duration).toInt()
            }
            tvCurrentTime.text = formatTime(position)
            checkSkipButtonVisibility(position)
        }
        hideHandler.postDelayed({ updateProgress() }, 500)
    }

    private fun updateCurrentTime() {
        player?.let { p ->
            tvCurrentTime.text = formatTime(p.currentPosition)
        }
    }

    private fun updateDuration() {
        player?.let { p ->
            val duration = p.duration
            if (duration > 0) {
                tvTotalTime.text = formatTime(duration)
            }
        }
    }

    // ADDED BACK: Function to update resolution display
    private fun updateResolutionDisplay() {
        player?.let { p ->
            val videoSize = p.videoSize
            if (videoSize.height > 0) {
                val currentQuality = "${videoSize.height}p"
                val selectedServer = tvServerName.text.toString().split(" ")[0] // Get server name before " (Auto)"
                tvServerName.text = "$selectedServer (Auto - $currentQuality)"
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWatchProgress()
        player?.release()
        hideHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        saveWatchProgress()
        player?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

