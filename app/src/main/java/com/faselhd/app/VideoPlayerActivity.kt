package com.faselhd.app

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
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.R
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.NetworkClient
import com.faselhd.app.utils.*
import com.faselhd.app.viewmodels.VideoPlayerViewModel
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.abs

class VideoPlayerActivity : AppCompatActivity(), PlayerStateManager.StateListener {

    companion object {
        // Keep your old keys for a moment, but we'll add new ones
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_EPISODE_LIST = "extra_episode_list"

        // ++ NEW, LIGHTWEIGHT KEYS
        private const val EXTRA_CURRENT_EPISODE_URL = "extra_current_episode_url"
        private const val EXTRA_START_POSITION = "extra_start_position"
        private const val EXTRA_SOURCE = "extra_source"

        /**
         * Creates a new Intent for VideoPlayerActivity.
         * IMPORTANT: The large data objects (videos, anime, episodeList) must be set in
         * PlayerDataHolder before calling this method.
         */
        fun newIntent(
            context: Context,
            currentEpisodeUrl: String,
            startPosition: Long = 0L,
            source: AnimeSource? = null
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                // We no longer pass the large lists here
                putExtra(EXTRA_CURRENT_EPISODE_URL, currentEpisodeUrl)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_SOURCE, source)
            }
        }
    }

    // ViewModel
    private val viewModel: VideoPlayerViewModel by viewModels()

    // Player and UI Components
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager
    private lateinit var trackSelector: DefaultTrackSelector

    // UI Elements
    private lateinit var btnServer: ImageButton
    private lateinit var tvServerName: TextView
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
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnResize: ImageButton
    private lateinit var btnSubtitle: ImageButton
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
    private lateinit var btnAudioTrack: ImageButton

    // Add PlayerStateManager instance
    private var playerStateManager: PlayerStateManager? = null
    private var lastPerformanceToastTime: Long = 0



    // State variables
    private var isControlsVisible = true
    private var isLocked = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isOnLongPressSpeedUp = false
    private var isSeeking = false
    private var seekStartPosition = 0L

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    // ++ ADD THESE PROPERTIES for retry logic
    private val retryHandler = Handler(Looper.getMainLooper())
    private var currentRetryCount = 0
    private val maxRetries = 3
    private var currentVideoIndex = 0

    // -- END

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // ++ RETRIEVE DATA FROM THE NEW SOURCE
        val currentEpisodeUrl = intent.getStringExtra(EXTRA_CURRENT_EPISODE_URL)
        val startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        val source = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource

        // Get the large data from our singleton holder
        val videoList = PlayerDataHolder.videos ?: emptyList()
        val currentAnime = PlayerDataHolder.anime
        val episodeList = PlayerDataHolder.episodeList ?: emptyList()
        val currentEpisode = episodeList.firstOrNull { it.url == currentEpisodeUrl }

        // -- END OF DATA RETRIEVAL

        if (videoList.isEmpty() || currentAnime == null || currentEpisode == null || currentEpisodeUrl == null) {
            Toast.makeText(this, "Video source not found or player data missing.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupAudioManager()
        setupGestureDetector()
        setupClickListeners()
        setupSeekBar()
        hideSystemUI()
        observeViewModel()

        viewModel.initializePlayer(videoList, currentAnime, currentEpisode, episodeList, startPosition, source)

        scheduleHideControls()
    }

    private fun observeViewModel() {
        viewModel.isPlaying.observe(this) { isPlaying -> updatePlayPauseButton(isPlaying) }
        viewModel.isLoading.observe(this) { isLoading -> loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE }

        viewModel.currentPosition.observe(this) { position ->
            updateProgressUI(position)
        }

        viewModel.duration.observe(this) { duration ->
            tvTotalTime.text = formatTime(duration)
        }

        //
        // ++ THIS IS THE CORRECTED BLOCK
        //
        viewModel.videoList.observe(this) { videos ->
            // This observer is now the single entry point for starting playback,
            // both for the initial load and for the next episode.
            if (videos.isNotEmpty()) {
                // When a new list of videos arrives, reset the source index
                // and begin the automatic playback/failover flow.
                currentVideoIndex = 0
                tryNextVideo()
            }
        }
        // -- END OF CORRECTION
        //


        viewModel.episodeTitle.observe(this) { title ->
            tvEpisodeTitle.text = title
        }

        viewModel.hasNextEpisode.observe(this) { hasNext ->
            btnNextEpisode.visibility = if (hasNext) View.VISIBLE else View.GONE
        }

        viewModel.currentSkipStamp.observe(this) { skipStamp ->
            if (skipStamp != null) {
                // ++ INTEGRATION: AUTO-SKIP INTRO
                if (PlayerSettingsManager.isAutoSkipEnabled(this)) {
                    player?.seekTo(skipStamp.endMs)
                    viewModel.skipToPosition(skipStamp.endMs) // Clear the button
                } else {
                    btnSkipIntro.text = skipStamp.type.text
                    btnSkipIntro.visibility = View.VISIBLE
                }
                // -- END INTEGRATION
            } else {
                btnSkipIntro.visibility = View.GONE
            }
        }
        viewModel.currentSkipStamp.observe(this) { skipStamp ->
            if (skipStamp != null) {
                btnSkipIntro.text = skipStamp.type.text
                btnSkipIntro.visibility = View.VISIBLE
            } else {
                btnSkipIntro.visibility = View.GONE
            }
        }

        viewModel.serverName.observe(this) { serverName ->
            tvServerName.text = serverName
        }

        viewModel.playbackError.observe(this) { error ->
            error?.let {
                // This toast is now for non-recoverable errors reported by the ViewModel
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }


    // ++ NEW: Core function for automatic selection and failover
    private fun tryNextVideo() {
        val videos = viewModel.videoList.value ?: return

        // Check if there are more sources to try
        if (currentVideoIndex < videos.size) {
            val videoToTry = videos[currentVideoIndex]
            Toast.makeText(this, "source: ${videoToTry.quality}", Toast.LENGTH_SHORT).show()
            Log.d("VideoPlayerActivity", "Attempting to play source #${currentVideoIndex + 1}: ${videoToTry.quality} - ${videoToTry.url}")

            // Increment index for the *next* potential failover
            currentVideoIndex++

            // Start playing the selected video
            initializePlayerForVideo(videoToTry)
        } else {
            // All sources have been tried and failed
            Log.e("VideoPlayerActivity", "All video sources failed to play.")
            showAllSourcesFailedDialog()
        }
    }

    // ++ NEW: Dialog to show when all sources have failed
    private fun showAllSourcesFailedDialog() {
        if (isFinishing) return // Avoid showing dialog on a closing activity
        AlertDialog.Builder(this)
            .setTitle("Playback Failed")
            .setMessage("Sorry, none of the available video sources could be played. Please check your internet connection or try again later.")
            .setPositiveButton("Retry") { dialog, _ ->
                // Reset and start the process from the beginning
                currentVideoIndex = 0
                tryNextVideo()
                dialog.dismiss()
            }
            .setNegativeButton("Go Back") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
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
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnResize = findViewById(R.id.btn_resize)
        btnSubtitle = findViewById(R.id.btn_subtitle)
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
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

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
                    viewModel.playNextEpisode()
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

    // Updated showSourceSelectionDialog method with improved logic and design
    private fun showSourceSelectionDialog() {
        val videos = viewModel.videoList.value ?: return
        if (videos.isEmpty()) return

        // Check for preferred quality setting
        val preferredQuality = PlayerSettingsManager.getDefaultVideoQuality(this)
        if (preferredQuality != "auto") {
            val matchingVideo = videos.firstOrNull { video ->
                video.quality.contains(preferredQuality, ignoreCase = true)
            }
            if (matchingVideo != null) {
                initializePlayerForVideo(matchingVideo)
                return
            }
        }

        // If only one source available, use it directly
        if (videos.size == 1) {
            initializePlayerForVideo(videos.first())
            return
        }

        // Sort videos by quality (highest first) for better UX
        val sortedVideos = videos.sortedByDescending { video ->
            extractQualityNumber(video.quality)
        }

        // Create enhanced dialog items with quality indicators
        val dialogItems = sortedVideos.map { video ->
            formatQualityDisplayName(video)
        }.toTypedArray()

        // Find currently selected item if any
        val currentServerName = viewModel.serverName.value
        val selectedIndex = if (currentServerName != null) {
            sortedVideos.indexOfFirst { video ->
                currentServerName.contains(video.quality, ignoreCase = true)
            }.takeIf { it >= 0 } ?: 0
        } else 0

        // Create custom dialog with improved styling
        val dialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("🎬 Select Video Quality")
            .setSingleChoiceItems(dialogItems, selectedIndex) { dialog, which ->
                val selectedVideo = sortedVideos[which]
                initializePlayerForVideo(selectedVideo)

                // Save user preference for future
                if (PlayerSettingsManager.shouldRememberQualityChoice(this)) {
                    PlayerSettingsManager.setLastSelectedQuality(this, selectedVideo.quality)
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                if (player == null) {
                    finish() // Exit if no player is initialized
                }
                dialog.dismiss()
            }
            .setNeutralButton("Auto Select") { dialog, _ ->
                // Select best quality based on network conditions
                val bestVideo = selectBestQualityForNetwork(sortedVideos)
                initializePlayerForVideo(bestVideo)
                dialog.dismiss()
            }
            .setOnCancelListener { dialog ->
                if (player == null) {
                    finish()
                }
            }

        val dialog = dialogBuilder.create()

        // Apply custom styling
        dialog.show()
        styleSourceSelectionDialog(dialog)
    }

    // Helper method to extract quality number for sorting
    private fun extractQualityNumber(quality: String): Int {
        val regex = Regex("""(\d+)p?""")
        val matchResult = regex.find(quality)
        return matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // Helper method to format display names with quality indicators
    private fun formatQualityDisplayName(video: Video): String {
        val quality = video.quality
        val qualityNumber = extractQualityNumber(quality)

        val qualityIndicator = when {
            qualityNumber >= 2160 -> "🌟 4K"
//            qualityNumber >= 1440 -> "⭐ 2K"
//            qualityNumber >= 1080 -> "✨ HD"
//            qualityNumber >= 720 -> "📺 HD"
//            qualityNumber >= 480 -> "📱 SD"
            else -> ""
        }

        val serverType = when {
            quality.contains("premium", ignoreCase = true) -> " 💎"
            quality.contains("fast", ignoreCase = true) -> " ⚡"
            quality.contains("backup", ignoreCase = true) -> " 🔄"
            else -> ""
        }

        return "$qualityIndicator $quality$serverType"
    }

    // Helper method to select best quality based on network
    private fun selectBestQualityForNetwork(videos: List<Video>): Video {
        val networkType = NetworkUtils.getNetworkType(this)
        val connectionSpeed = NetworkUtils.getConnectionSpeed(this)

        return when {
            networkType == "WIFI" && connectionSpeed >= 10.0 -> {
                // High speed WiFi - select highest quality
                videos.maxByOrNull { extractQualityNumber(it.quality) } ?: videos.first()
            }
            networkType == "WIFI" && connectionSpeed >= 5.0 -> {
                // Medium speed WiFi - select 1080p or lower
                videos.filter { extractQualityNumber(it.quality) <= 1080 }
                    .maxByOrNull { extractQualityNumber(it.quality) } ?: videos.first()
            }
            networkType == "MOBILE" && connectionSpeed >= 3.0 -> {
                // Good mobile connection - select 720p
                videos.find { extractQualityNumber(it.quality) == 720 }
                    ?: videos.filter { extractQualityNumber(it.quality) <= 720 }
                        .maxByOrNull { extractQualityNumber(it.quality) } ?: videos.first()
            }
            else -> {
                // Slow connection - select lowest quality
                videos.minByOrNull { extractQualityNumber(it.quality) } ?: videos.first()
            }
        }
    }

    // Helper method to apply custom styling to the dialog
    private fun styleSourceSelectionDialog(dialog: AlertDialog) {
        try {
            // Style the dialog window
            dialog.window?.apply {
                setBackgroundDrawableResource(R.drawable.dialog_background)

                // Add blur effect if supported
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = attributes.apply {
                        blurBehindRadius = 10
                    }
                }
            }

            // Style the title
            val titleView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            titleView?.apply {
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(24, 24, 24, 16)
            }

            // Style the buttons
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            listOf(positiveButton, negativeButton, neutralButton).forEach { button ->
                button?.apply {
                    setTextColor(resources.getColor(R.color.green_play_button, theme))
                    isAllCaps = false
                    textSize = 14f
                }
            }

            // Add animation
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation

        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error styling dialog", e)
        }
    }

    // Enhanced method that also handles error cases and retries
    private fun showSourceSelectionDialogWithRetry(retryCount: Int = 0) {
        val videos = viewModel.videoList.value

        when {
            videos == null || videos.isEmpty() -> {
                if (retryCount < 2) {
                    // Retry loading videos
                    Toast.makeText(this, "Loading video sources...", Toast.LENGTH_SHORT).show()
                    viewModel.retryLoadingVideos()
                    Handler(Looper.getMainLooper()).postDelayed({
                        showSourceSelectionDialogWithRetry(retryCount + 1)
                    }, 1500)
                } else {
                    // Show error dialog
                    showVideoSourceErrorDialog()
                }
            }
            else -> {
                showSourceSelectionDialog()
            }
        }
    }

    // Error dialog for when no video sources are found
    private fun showVideoSourceErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Video Source Error")
            .setMessage("Unable to load video sources. This may be due to:\n\n• Network connectivity issues\n• Server maintenance\n• Content restrictions")
            .setPositiveButton("Retry") { _, _ ->
                viewModel.retryLoadingVideos()
                showSourceSelectionDialogWithRetry()
            }
            .setNegativeButton("Go Back") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }


//    @androidx.annotation.OptIn(UnstableApi::class)
//
//    private fun initializePlayerForVideo(video: Video) {
//        player?.release()
//        playerStateManager?.stopMonitoring()
//        player = null
//
//        // No need to reset currentVideoIndex here, as it's managed by the flow
//
//        viewModel.updateServerName(video.quality)
//
//        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkUtils.getUnsafeOkHttpClient())
//            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
//        video.headers?.let { httpDataSourceFactory.setDefaultRequestProperties(it) }
//
//        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
//        val isLiveStream = video.url.contains(".m3u8", ignoreCase = true)
//// Now you can just get the cache, as it's already initialized.
//        val cache: SimpleCache? = VideoCacheManager.getCache()
//
//        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (!isLiveStream && VideoCacheManager.isCacheEnabled(this)) {
//            // This is VOD (Video on Demand), so we use the cache
//            val cache = VideoCacheManager.initializeCache(this)
//            if (cache != null) {
//                Log.d("VideoPlayerActivity", "Using CacheDataSource for VOD: ${video.url}")
//                CacheDataSource.Factory()
//                    .setCache(cache)
//                    .setUpstreamDataSourceFactory(upstreamFactory)
//                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
//            } else {
//                upstreamFactory
//            }
//        } else {
//            // This is a live stream OR caching is disabled, so we bypass the cache
//            if (isLiveStream) Log.d("VideoPlayerActivity", "Bypassing cache for Live Stream: ${video.url}")
//            upstreamFactory
//        }
//
//        val isLocalFile = video.url.startsWith("file://") || File(video.url).exists()
//        val mediaItem = MediaItem.Builder()
//            .setUri(video.url)
//            .setSubtitleConfigurations(
//                if (isLocalFile) findLocalSubtitleFiles(video.url) else getSubtitleConfigsFromVideo(video)
//            )
//            .build()
//
//        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
//        trackSelector = DefaultTrackSelector(this)
//
//        player = ExoPlayer.Builder(this)
//            .setTrackSelector(trackSelector)
//            .setMediaSourceFactory(mediaSourceFactory)
//            .build().apply {
//                setMediaItem(mediaItem)
//                addListener(enhancedPlayerListener) // The error listener is key
//                playWhenReady = true
//
//                val defaultSpeed = PlayerSettingsManager.getDefaultPlaybackSpeed(this@VideoPlayerActivity)
//                playbackParameters = PlaybackParameters(defaultSpeed)
//
//                val startPos = viewModel.currentPosition.value ?: 0L
//                if (startPos > 0) seekTo(startPos)
//                prepare()
//            }
//
//        playerStateManager = PlayerStateManager(this, player!!, trackSelector)
//        playerStateManager?.addListener(this)
//        playerStateManager?.optimizeForVideo(video)
//        playerStateManager?.startMonitoring()
//
//        playerView.player = player
//        playerView.resizeMode = currentResizeMode
//        startProgressUpdates()
//    }

//    @androidx.annotation.OptIn(UnstableApi::class)
//    private fun initializePlayerForVideo(video: Video) {
//        player?.release()
//        playerStateManager?.stopMonitoring()
//        player = null
//
//        viewModel.updateServerName(video.quality)
//
//// --- START: THE FIX ---
//
//// Create the factory WITHOUT setting the User-Agent separately.
//        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkClient.client)
//            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
//// --- REMOVED --- .setUserAgent("...")
//
//// This line is PERFECT. It will now be the ONLY thing setting headers.
//// It will set BOTH the User-Agent and the Referer from the video object.
//        video.headers?.let {
//            Log.d("VideoPlayerActivity", "Setting custom headers for playback: $it")
//            httpDataSourceFactory.setDefaultRequestProperties(it)
//        }
//
//// --- END: THE FIX ---
//
//
//        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
//        val isLiveStream = video.url.contains(".m3u8", ignoreCase = true)
//
//        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (!isLiveStream && VideoCacheManager.isCacheEnabled(this)) {
//            val cache = VideoCacheManager.initializeCache(this)
//            if (cache != null) {
//                Log.d("VideoPlayerActivity", "Using CacheDataSource for VOD: ${video.url}")
//                CacheDataSource.Factory()
//                    .setCache(cache)
//                    .setUpstreamDataSourceFactory(upstreamFactory)
//                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
//                    .setCacheReadDataSourceFactory(FileDataSource.Factory()) .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
//            } else {
//                upstreamFactory
//            }
//        } else {
//            if (isLiveStream) Log.d("VideoPlayerActivity", "Bypassing cache for Live Stream: ${video.url}")
//            upstreamFactory
//        }
//
//        val isLocalFile = video.url.startsWith("file://") || File(video.url).exists()
//        val mediaItem = MediaItem.Builder()
//            .setUri(video.url)
//            .setSubtitleConfigurations(
//                if (isLocalFile) findLocalSubtitleFiles(video.url) else getSubtitleConfigsFromVideo(video)
//            )
//            .build()
//
//        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
//        trackSelector = DefaultTrackSelector(this)
//
//        player = ExoPlayer.Builder(this)
//            .setTrackSelector(trackSelector)
//            .setMediaSourceFactory(mediaSourceFactory)
//            .build().apply {
//                setMediaItem(mediaItem)
//                addListener(enhancedPlayerListener)
//                playWhenReady = true
//
//                val defaultSpeed = PlayerSettingsManager.getDefaultPlaybackSpeed(this@VideoPlayerActivity)
//                playbackParameters = PlaybackParameters(defaultSpeed)
//
//                val startPos = viewModel.currentPosition.value ?: 0L
//                if (startPos > 0) seekTo(startPos)
//                prepare()
//            }
//
//        playerStateManager = PlayerStateManager(this, player!!, trackSelector)
//        playerStateManager?.addListener(this)
//        playerStateManager?.optimizeForVideo(video)
//        playerStateManager?.startMonitoring()
//
//        playerView.player = player
//        playerView.resizeMode = currentResizeMode
//        startProgressUpdates()
//    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayerForVideo(video: Video) {
        player?.release()
        playerStateManager?.stopMonitoring()
        player = null

        viewModel.updateServerName(video.quality)

        // --- START: THE FIX ---

        val source = intent.getSerializableExtra(EXTRA_SOURCE) as? AnimeSource

        println("source video ss : ${source!!.name}")

        var httpDataSourceFactory = OkHttpDataSource.Factory(NetworkUtils.getUnsafeOkHttpClient())
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")

        // This part is perfect. It creates our factory with the required headers.
        if (source!!.name == "E3SK")
        {
             httpDataSourceFactory = OkHttpDataSource.Factory(NetworkUtils.getUnsafeOkHttpClient())
        }
//        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkUtils.getUnsafeOkHttpClient())
//            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
        video.headers?.let { httpDataSourceFactory.setDefaultRequestProperties(it) }

// This is the master factory that the player will use in the end.
        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val isLiveStream = video.url.contains(".m3u8", ignoreCase = true)
        val cache: SimpleCache? = VideoCacheManager.getCache()

        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (!isLiveStream && cache != null && VideoCacheManager.isCacheEnabled(this)) {
            // This is a cachable video.
            Log.d("VideoPlayerActivity", "Using CacheDataSource for VOD: ${video.url}")
            CacheDataSource.Factory()
                .setCache(cache)
                // Be direct: Tell the cache to use our HTTP factory for all network downloads.
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                // The master factory will be used by the player to WRAP the cache source.
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            // This is a live stream or caching is off, so just use the master factory directly.
            if (isLiveStream) Log.d("VideoPlayerActivity", "Bypassing cache for Live Stream: ${video.url}")
            upstreamFactory
        }

        val isLocalFile = video.url.startsWith("file://") || File(video.url).exists()
        val mediaItem = MediaItem.Builder()
            .setUri(video.url)
            .setSubtitleConfigurations(
                if (isLocalFile) findLocalSubtitleFiles(video.url) else getSubtitleConfigsFromVideo(video)
            )
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        trackSelector = DefaultTrackSelector(this)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(mediaItem)
                addListener(enhancedPlayerListener)
                playWhenReady = true

                val defaultSpeed = PlayerSettingsManager.getDefaultPlaybackSpeed(this@VideoPlayerActivity)
                playbackParameters = PlaybackParameters(defaultSpeed)

                val startPos = viewModel.currentPosition.value ?: 0L
                if (startPos > 0) seekTo(startPos)
                prepare()
            }

        playerStateManager = PlayerStateManager(this, player!!, trackSelector)
        playerStateManager?.addListener(this)
        playerStateManager?.optimizeForVideo(video)
        playerStateManager?.startMonitoring()

        playerView.player = player
        playerView.resizeMode = currentResizeMode
        startProgressUpdates()
    }



    // Helper function to keep the builder clean
    private fun getSubtitleConfigsFromVideo(video: Video): List<MediaItem.SubtitleConfiguration> {
        return video.subtitles?.mapNotNull { subtitle ->
            val subtitleUri = Uri.parse(subtitle.url)
            val mimeType = when {
                subtitle.url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
                subtitle.url.contains(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
                else -> null
            }
            mimeType?.let {
                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(it)
                    .setLanguage(subtitle.lang)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
        } ?: emptyList()
    }

    private val enhancedPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            viewModel.setLoading(playbackState == Player.STATE_BUFFERING)

            if (playbackState == Player.STATE_READY) {
                // Playback is successful, reset the retry counter for future errors (like HLS stuck)
                // currentRetryCount = 0
            } else if (playbackState == Player.STATE_ENDED) {
                if (PlayerSettingsManager.isAutoPlayEnabled(this@VideoPlayerActivity)) {
                    viewModel.playNextEpisode()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            player?.let { p ->
                viewModel.updatePlayerState(isPlaying, p.currentPosition, p.duration)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            updateResolutionDisplay()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateResolutionDisplay()
            if (videoSize.width == 0 && videoSize.height == 0) {
                handleAudioOnlyContent()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e("VideoPlayerActivity", "Playback Error on source attempt #${currentVideoIndex}: ", error)
            player?.release() // Release the failed player instance

            // The magic happens here: instead of showing an error, just try the next available source.
            tryNextVideo()
        }
    }

    private fun retryPlayback() {
        currentRetryCount++
        Log.d("VideoPlayerActivity", "HLS playlist stuck. Retrying... (Attempt $currentRetryCount/$maxRetries)")

        // Show feedback to the user
        Toast.makeText(this, "Stream interrupted. Reconnecting... (Attempt $currentRetryCount)", Toast.LENGTH_SHORT).show()
        viewModel.setLoading(true)

        // Wait for a few seconds before retrying
        retryHandler.postDelayed({
            player?.let {
                // Re-prepare the player with the same media item to refresh the source
                it.prepare()
                it.playWhenReady = true
            }
        }, 3000) // 3-second delay
    }

    private fun handleAudioOnlyContent() {
        btnResize.visibility = View.GONE
    }

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
                        .setLanguage("en")
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

    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        btnServer.setOnClickListener {
            showSourceSelectionDialog()
            scheduleHideControls()
        }
        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHideControls()
        }
        btnRewind.setOnClickListener {
            rewind()
            scheduleHideControls()
        }
        btnFastForward.setOnClickListener {
            fastForward()
            scheduleHideControls()
        }
        btnLock.setOnClickListener { toggleLock() }
        btnNextEpisode.setOnClickListener { viewModel.playNextEpisode() }
        btnUnlock.setOnClickListener { toggleLock() }
        btnFullscreen.setOnClickListener {
            Toast.makeText(this, "Player is always in fullscreen mode", Toast.LENGTH_SHORT).show()
            scheduleHideControls()
        }
        btnResize.setOnClickListener {
            cycleResizeMode()
            scheduleHideControls()
        }
        btnSubtitle.setOnClickListener {
            showSubtitleSelectionDialog()
            scheduleHideControls()
        }
        btnAudioTrack.setOnClickListener {
            showAudioTrackSelectionDialog()
            scheduleHideControls()
        }
        btnSkipIntro.setOnClickListener {
            viewModel.currentSkipStamp.value?.let { stamp ->
                player?.seekTo(stamp.endMs)
                viewModel.skipToPosition(stamp.endMs)
            }
        }
    }

    private fun rewind() {
        player?.let { p ->
            val newPos = (p.currentPosition - 10000).coerceAtLeast(0)
            p.seekTo(newPos)
            viewModel.updateCurrentPosition(newPos)
            showSeekIndicator(rewindIndicator)
        }
    }

    private fun fastForward() {
        player?.let { p ->
            val newPos = (p.currentPosition + 10000).coerceAtMost(p.duration)
            p.seekTo(newPos)
            viewModel.updateCurrentPosition(newPos)
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

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showSubtitleSelectionDialog() {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        val playerInstance = player

        if (mappedTrackInfo == null || playerInstance == null) {
            Toast.makeText(this, "Player not ready", Toast.LENGTH_SHORT).show()
            return
        }

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

        val options = mutableListOf<Pair<String, DefaultTrackSelector.SelectionOverride?>>()
        options.add("Off" to null)

        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups.get(groupIndex)
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val displayName = format.label ?: format.language ?: "Subtitle ${options.size}"
                options.add(displayName to DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex))
            }
        }

        var checkedItem = 0
        val currentTracks = playerInstance.currentTracks
        for (trackGroup in currentTracks.groups) {
            if (trackGroup.type == C.TRACK_TYPE_TEXT && trackGroup.isSelected) {
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val selectedFormat = trackGroup.getTrackFormat(i)
                        for (j in 1 until options.size) {
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

        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                val (_, override) = options[which]
                val parametersBuilder = trackSelector.buildUponParameters()
                if (override == null) {
                    parametersBuilder.setRendererDisabled(textRendererIndex, true)
                        .clearSelectionOverrides(textRendererIndex)
                } else {
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

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showAudioTrackSelectionDialog() {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        val playerInstance = player

        if (mappedTrackInfo == null || playerInstance == null) {
            Toast.makeText(this, "Player not ready", Toast.LENGTH_SHORT).show()
            return
        }

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

        val options = mutableListOf<Pair<String, DefaultTrackSelector.SelectionOverride?>>()
        var checkedItem = 0

        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups.get(groupIndex)
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val displayName = format.label ?: format.language ?: "Track ${options.size + 1}"
                options.add(displayName to DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex))
            }
        }

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

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null || player == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (abs(dx) > abs(dy)) {
                    // Horizontal scroll - seeking
                    if (!isSeeking) {
                        isSeeking = true
                        seekStartPosition = player!!.currentPosition
                    }

                    brightnessOverlay.visibility = View.GONE
                    volumeOverlay.visibility = View.GONE
                    tvSeekTime.visibility = View.VISIBLE

                    val duration = player!!.duration
                    val sensitivityMultiplier = 2.0
                    val seekOffset = (dx * (duration / (playerView.width.toFloat() * sensitivityMultiplier))).toLong()
                    val newPosition = (seekStartPosition + seekOffset).coerceIn(0, duration)

                    player!!.seekTo(newPosition)
                    viewModel.updateCurrentPosition(newPosition)

                    val changeSeconds = seekOffset / 1000
                    val changeSign = if (seekOffset >= 0) "+" else "-"
                    val changeMinutesPart = abs(changeSeconds) / 60
                    val changeSecondsPart = abs(changeSeconds) % 60

                    val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
                    val formattedPosition = formatTime(newPosition)

                    tvSeekTime.text = "$formattedChange [$formattedPosition]"
                } else {
                    // Vertical scroll - brightness/volume
                    if (e2.x < playerView.width / 2) {
                        adjustBrightness(-dy)
                    } else {
                        adjustVolume(-dy)
                    }
                }
                return true
            }

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

            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isSeeking = false

                if (isOnLongPressSpeedUp) {
                    isOnLongPressSpeedUp = false
                    player?.setPlaybackParameters(PlaybackParameters(1f))
                    speedIndicatorText.visibility = View.GONE
                }

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
                        val newPosition = (progress * duration) / 100
                        player!!.seekTo(newPosition)
                        viewModel.updateCurrentPosition(newPosition)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideHandler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleHideControls()
            }
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

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        ivPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large)
    }

    private fun startProgressUpdates() {
        val progressRunnable = object : Runnable {
            override fun run() {
                player?.let { p ->
                    viewModel.updatePlayerState(p.isPlaying, p.currentPosition, p.duration)
                }
                hideHandler.postDelayed(this, 500)
            }
        }
        hideHandler.post(progressRunnable)
    }

    private fun updateProgressUI(position: Long) {
        val duration = viewModel.duration.value ?: 0L
        if (duration > 0) {
            seekBar.progress = ((position * 100) / duration).toInt()
        }
        tvCurrentTime.text = formatTime(position)
    }

    private fun updateResolutionDisplay() {
        player?.let { p ->
            val videoSize = p.videoSize
            if (videoSize.height > 0) {
                val currentQuality = "${videoSize.height}p"
                val serverName = viewModel.serverName.value ?: ""
                val selectedServer = serverName.split(" ")[0]
                viewModel.updateServerName("$selectedServer (Auto - $currentQuality)")
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

        // ++ CLEAN UP THE SINGLETON HOLDER
        PlayerDataHolder.clear()
        // -- END CLEAN UP

        retryHandler.removeCallbacksAndMessages(null)
        player?.let { p ->
            val duration = viewModel.duration.value ?: 0L
            if (duration > 0) {
                viewModel.saveWatchProgress(p.currentPosition, duration)
            }
        }
        playerStateManager?.stopMonitoring()
        player?.release()
        hideHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        player?.let { p ->
            val duration = viewModel.duration.value ?: 0L
            if (duration > 0) {
                viewModel.saveWatchProgress(p.currentPosition, duration)
            }
            p.pause()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onBufferHealthChanged(percentage: Int) {
        // Optional: Update a UI element to show buffer percentage for debugging
        Log.d("PlayerStateManager", "Buffer health: $percentage%")
    }

    override fun onNetworkChanged(networkInfo: PlayerStateManager.NetworkInfo) {
        // Optional: Show a toast or icon indicating network type
        Log.d("PlayerStateManager", "Network changed: ${networkInfo.type}, Bandwidth: ${networkInfo.bandwidth} kbps")
    }

    override fun onQualityChanged(height: Int, bitrate: Int) {
        // This is useful for analytics or debugging. The `updateResolutionDisplay`
        // already shows the current resolution to the user.
        Log.d("PlayerStateManager", "Quality changed: ${height}p, Bitrate: $bitrate")
    }


    override fun onPerformanceIssue(issue: PlayerStateManager.PerformanceIssue) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPerformanceToastTime < 10000) {
            return
        }
        lastPerformanceToastTime = currentTime

        val message = when (issue) {
            PlayerStateManager.PerformanceIssue.FREQUENT_BUFFERING -> "Connection is unstable. Adjusting quality."
            PlayerStateManager.PerformanceIssue.POOR_NETWORK -> "Poor network detected. Quality may be reduced."
            PlayerStateManager.PerformanceIssue.LOW_BUFFER_HEALTH -> "Buffering... your connection may be slow."
        }
//        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
