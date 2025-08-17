package com.faselhd.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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
import com.example.myapplication.R
import com.faselhd.app.db.AppDatabase
import com.faselhd.app.models.SAnime
import com.faselhd.app.models.SEpisode
import com.faselhd.app.models.Video
import com.faselhd.app.models.WatchHistory
import com.faselhd.app.network.AnimeSource
import com.faselhd.app.network.SourceManager
import com.faselhd.app.utils.EpisodeSkip
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class VideoPlayerActivityEnhanced : AppCompatActivity() {

    companion object {
        private const val EXTRA_VIDEOS = "extra_videos"
        private const val EXTRA_ANIME = "extra_anime"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_EPISODE_LIST = "extra_episode_list"
        private const val EXTRA_START_POSITION = "extra_start_position"

        // Animation constants
        private const val ANIMATION_DURATION = 300L
        private const val FADE_ANIMATION_DURATION = 250L
        private const val SEEK_INDICATOR_DURATION = 1000L
        private const val OVERLAY_AUTO_HIDE_DELAY = 1500L
        private const val CONTROLS_AUTO_HIDE_DELAY = 4000L

        fun newIntent(
            context: Context,
            videos: List<Video?>,
            anime: SAnime,
            currentEpisode: SEpisode,
            episodeListForSeason: ArrayList<SEpisode>,
            startPosition: Long = 0L
        ): Intent {
            return Intent(context, VideoPlayerActivityEnhanced::class.java).apply {
                putParcelableArrayListExtra(EXTRA_VIDEOS, ArrayList(videos.filterNotNull()))
                putExtra(EXTRA_ANIME, anime)
                putExtra(EXTRA_EPISODE, currentEpisode)
                putParcelableArrayListExtra(EXTRA_EPISODE_LIST, episodeListForSeason)
                putExtra(EXTRA_START_POSITION, startPosition)
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

    // State variables
    private var isControlsVisible = true
    private var isLocked = false
    private var isFullscreen = true
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isOnLongPressSpeedUp = false
    private var seekChange: Long = 0
    private var isGestureInProgress = false
    private var lastProgressUpdate = 0L

    // Handlers and runnables
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControlsWithAnimation() }
    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressUpdateHandler.postDelayed(this, 1000)
        }
    }

    // Data from Intent
    private var videoList: List<Video> = emptyList()
    private var currentAnime: SAnime? = null
    private var currentEpisode: SEpisode? = null
    private var seasonEpisodeList: List<SEpisode> = emptyList()
    private var startPosition: Long = 0L

    // Database and Skip Times
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val sourceManager by lazy { SourceManager(applicationContext) }
    private var skipStamps: List<EpisodeSkip.SkipStamp> = emptyList()
    private var currentSkipStamp: EpisodeSkip.SkipStamp? = null

    // Animation flags
    private var isAnimating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        retrieveIntentData()

        if (videoList.isEmpty() || currentAnime == null || currentEpisode == null) {
            showError("Video source not found")
            return
        }

        initializeViews()
        setupAudioManager()
        setupGestureDetector()
        setupClickListeners()
        setupSeekBar()
        hideSystemUI()
        scheduleHideControls()
        startProgressUpdates()

        showSourceSelectionDialog()
    }

    private fun retrieveIntentData() {
        videoList = intent.getParcelableArrayListExtra(EXTRA_VIDEOS) ?: emptyList()
        currentAnime = intent.getParcelableExtra(EXTRA_ANIME)
        currentEpisode = intent.getParcelableExtra(EXTRA_EPISODE)
        seasonEpisodeList = intent.getParcelableArrayListExtra(EXTRA_EPISODE_LIST) ?: emptyList()
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startProgressUpdates() {
        progressUpdateHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdates() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
    }



    private fun saveWatchProgress() {
        val p = player ?: return
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val position = p.currentPosition
        val duration = p.duration

        if (duration <= 0 || episode.url.isNullOrEmpty()) return

        val progressPercentage = (position * 100) / duration

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (progressPercentage > 90) {
                    // Episode is finished
                    val currentIndex = seasonEpisodeList.indexOfFirst { it.url == episode.url }
                    if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
                        val nextEpisode = seasonEpisodeList[currentIndex + 1]

                        val nextEpisodeHistory = WatchHistory(
                            episodeUrl = nextEpisode.url!!,
                            animeUrl = anime.url!!,
                            animeTitle = anime.title ?: "Unknown Title",
                            animeThumbnailUrl = anime.thumbnail_url,
                            episodeName = nextEpisode.name,
                            lastWatchedPosition = 0L,
                            duration = 0L,
                            timestamp = System.currentTimeMillis() + 1000,
                            isFinished = false,
                            episodeNumber = nextEpisode.episode_number.toInt(),
                            seasonEpisodes = seasonEpisodeList,
                            source = sourceManager.getCurrentSourceName()
                        )

                        db.watchHistoryDao().upsert(nextEpisodeHistory)
                    }

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
                        source = sourceManager.getCurrentSourceName()
                    )
                    db.watchHistoryDao().upsert(watchHistory)
                } else {
                    // Episode is in progress
                    val watchHistory = WatchHistory(
                        episodeUrl = episode.url!!,
                        animeUrl = anime.url ?: "",
                        animeTitle = anime.title ?: "Unknown Title",
                        animeThumbnailUrl = anime.thumbnail_url,
                        episodeName = episode.name ?: "Unknown Episode",
                        lastWatchedPosition = position,
                        duration = duration,
                        timestamp = System.currentTimeMillis(),
                        isFinished = false,
                        episodeNumber = episode.episode_number.toInt(),
                        seasonEpisodes = seasonEpisodeList,
                        source = sourceManager.getCurrentSourceName()
                    )
                    db.watchHistoryDao().upsert(watchHistory)
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error saving watch progress", e)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isDpadEvent(event)) {
                scheduleHideControls()
                if (!isControlsVisible && !isLocked) {
                    showControlsWithAnimation()
                    return true
                }
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
                        showControlsWithAnimation()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextEpisode()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    rewindWithAnimation()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    fastForwardWithAnimation()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (isControlsVisible) {
            hideControlsWithAnimation()
        } else {
            super.onBackPressed()
        }
    }

    private fun isDpadEvent(event: KeyEvent): Boolean {
        return event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )
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

        // Set initial content
        tvEpisodeTitle.text = "${currentAnime?.title} - ${currentEpisode?.name}"
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        btnNextEpisode.visibility = if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1)
            View.VISIBLE else View.GONE

        // Set initial alpha for smooth animations
        topOverlay.alpha = 1f
        centerControls.alpha = 1f
        bottomControls.alpha = 1f
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }

        btnServer.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showSourceSelectionDialog()
            scheduleHideControls()
        }

        btnPlayPause.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayPause()
            scheduleHideControls()
        }

        btnRewind.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            rewindWithAnimation()
            scheduleHideControls()
        }

        btnFastForward.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            fastForwardWithAnimation()
            scheduleHideControls()
        }

        btnLock.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLockWithAnimation()
        }

        btnNextEpisode.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            playNextEpisode()
        }

        btnUnlock.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLockWithAnimation()
        }

        btnFullscreen.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showToastWithFade("Player is always in fullscreen mode")
            scheduleHideControls()
        }

        btnResize.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            cycleResizeMode()
            scheduleHideControls()
        }

        btnSubtitle.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showToastWithFade("Subtitle functionality to be implemented")
            scheduleHideControls()
        }

        btnSkipIntro.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            currentSkipStamp?.let { stamp ->
                player?.seekTo(stamp.endMs)
                hideSkipButtonWithAnimation()
                currentSkipStamp = null
            }
        }
    }

    private fun showSourceSelectionDialog() {
        if (videoList.size == 1) {
            initializePlayerForVideo(videoList.first())
            return
        }

        val sources = videoList.map { it.quality }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun showQualityDialog() {
        val trackSelector = this.trackSelector
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo == null) {
            showToastWithFade("No quality options available")
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
            showToastWithFade("No quality options available")
            return
        }

        val trackGroups = mappedTrackInfo.getTrackGroups(videoRendererIndex)
        if (trackGroups.isEmpty) {
            showToastWithFade("No quality options available")
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

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Quality")
            .setSingleChoiceItems(qualityOptions.toTypedArray(), checkedItem) { dialog, which ->
                val parametersBuilder = trackSelector.buildUponParameters()
                if (which == 0) {
                    parametersBuilder.clearSelectionOverrides(videoRendererIndex)
                } else {
                    val override = DefaultTrackSelector.SelectionOverride(
                        videoRendererIndex,
                        trackIndices[which - 1]
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
            .create()
            .show()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayerForVideo(video: Video) {
        showLoadingWithAnimation()

        player?.release()
        player = null

        tvServerName.text = "${video.quality} (Auto)"

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")

        video.headers?.let { headersMap ->
            httpDataSourceFactory.setDefaultRequestProperties(headersMap)
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaItem = MediaItem.fromUri(video.url)
        val mediaSource = if (video.url.endsWith(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setAllowMultipleAdaptiveSelections(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(false)
            )
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build().apply {
                setMediaSource(mediaSource)
                addListener(playerListener)
                playWhenReady = true

                val seekPosition = if (startPosition != -1L) startPosition else currentPosition
                seekTo(seekPosition)
                startPosition = -1L

                prepare()
            }

        playerView.player = player
        playerView.resizeMode = currentResizeMode
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> showLoadingWithAnimation()
                Player.STATE_READY -> {
                    hideLoadingWithAnimation()
                    updatePlayPauseButtonWithAnimation()
                    updateDuration()
                    fetchSkipTimes()
                }
                Player.STATE_ENDED -> playNextEpisode()
                else -> hideLoadingWithAnimation()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButtonWithAnimation()
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            updateResolutionDisplayWithAnimation()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            updateResolutionDisplayWithAnimation()
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e("VideoPlayerActivity", "ExoPlayer Error: ", error)
            hideLoadingWithAnimation()
            showToastWithFade("Player Error: ${error.message}")
        }
    }

    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun toggleLockWithAnimation() {
        if (isAnimating) return
        isAnimating = true

        isLocked = !isLocked

        if (isLocked) {
            hideControlsWithAnimation()

            lockOverlay.alpha = 0f
            lockOverlay.visibility = View.VISIBLE
            lockOverlay.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    btnUnlock.requestFocus()
                    isAnimating = false
                    hideHandler.postDelayed({
                        hideUnlockButtonWithAnimation()
                    }, 3000)
                }
                .start()
        } else {
            lockOverlay.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    lockOverlay.visibility = View.GONE
                    lockOverlay.alpha = 1f
                    showControlsWithAnimation()
                    isAnimating = false
                }
                .start()
        }
    }

    private fun hideUnlockButtonWithAnimation() {
        if (btnUnlock.visibility == View.VISIBLE) {
            btnUnlock.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(FADE_ANIMATION_DURATION)
                .withEndAction {
                    btnUnlock.visibility = View.GONE
                    btnUnlock.alpha = 1f
                    btnUnlock.scaleX = 1f
                    btnUnlock.scaleY = 1f
                }
                .start()
        }
    }

    private fun fetchSkipTimes() {
        val anime = currentAnime ?: return
        val episode = currentEpisode ?: return
        val duration = player?.duration ?: return

        if (duration <= 0) return

        lifecycleScope.launch {
            try {
                skipStamps = EpisodeSkip.getStamps(
                    anime,
                    episode.episode_number.toInt(),
                    duration
                )
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error fetching skip times", e)
            }
        }
    }

    private fun checkSkipButtonVisibility(currentPosition: Long) {
        if (skipStamps.isEmpty()) return

        val activeStamp = skipStamps.find { currentPosition in it.startMs..it.endMs }

        if (activeStamp != null && currentSkipStamp == null) {
            currentSkipStamp = activeStamp
            showSkipButtonWithAnimation(activeStamp.type.text)
        } else if (activeStamp == null && currentSkipStamp != null) {
            hideSkipButtonWithAnimation()
            currentSkipStamp = null
        }
    }

    private fun showSkipButtonWithAnimation(text: String) {
        btnSkipIntro.text = text
        btnSkipIntro.alpha = 0f
        btnSkipIntro.translationY = 50f
        btnSkipIntro.visibility = View.VISIBLE

        btnSkipIntro.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun hideSkipButtonWithAnimation() {
        if (btnSkipIntro.visibility == View.VISIBLE) {
            btnSkipIntro.animate()
                .alpha(0f)
                .translationY(50f)
                .setDuration(FADE_ANIMATION_DURATION)
                .withEndAction {
                    btnSkipIntro.visibility = View.GONE
                    btnSkipIntro.alpha = 1f
                    btnSkipIntro.translationY = 0f
                }
                .start()
        }
    }

    private fun playNextEpisode() {
        val currentIndex = seasonEpisodeList.indexOf(currentEpisode)
        if (currentIndex != -1 && currentIndex < seasonEpisodeList.size - 1) {
            val nextEpisode = seasonEpisodeList[currentIndex + 1]
            val intent = AnimeDetailsActivity.newIntentWithResume(
                context = this,
                anime = currentAnime!!,
                resumeEpisodeUrl = nextEpisode.url!!,
                source = AnimeSource.valueOf(sourceManager.getCurrentSourceName().replace(" ", "_").uppercase())
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    showUnlockButtonWithAnimation()
                } else {
                    toggleControlsWithAnimation()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    if (e.x < playerView.width / 2) {
                        rewindWithAnimation()
                    } else {
                        fastForwardWithAnimation()
                    }
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null || player == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                isGestureInProgress = true

                if (abs(dx) > abs(dy) * 1.5f) {
                    // HORIZONTAL SCROLL (SEEKING)
                    hideBrightnessOverlay()
                    hideVolumeOverlay()
                    showSeekOverlay()

                    val duration = player!!.duration
                    val sensitivityMultiplier = 1.5
                    seekChange = (dx * (duration / (playerView.width.toFloat() * sensitivityMultiplier))).toLong()

                    val newPosition = (player!!.currentPosition + seekChange).coerceIn(0, duration)

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdate > 50) {
                        player!!.seekTo(newPosition)
                        lastProgressUpdate = currentTime
                    }

                    updateSeekTimeDisplay(seekChange, newPosition)

                } else {
                    // VERTICAL SCROLL (BRIGHTNESS/VOLUME)
                    hideSeekOverlay()

                    if (e2.x < playerView.width / 2) {
                        adjustBrightnessSmooth(-dy)
                    } else {
                        adjustVolumeSmooth(-dy)
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return
                startSpeedUpMode()
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleTouchRelease()
                }
            }
            true
        }
    }

    private fun handleTouchRelease() {
        if (isOnLongPressSpeedUp) {
            stopSpeedUpMode()
        }

        if (isGestureInProgress) {
            isGestureInProgress = false

            hideHandler.postDelayed({
                hideSeekOverlay()
                hideBrightnessOverlay()
                hideVolumeOverlay()
            }, 800)
        }
    }

    private fun startSpeedUpMode() {
        isOnLongPressSpeedUp = true
        player?.setPlaybackParameters(PlaybackParameters(2f))

        speedIndicatorText.text = "Speed: 2.0x"
        speedIndicatorText.alpha = 0f
        speedIndicatorText.scaleX = 0.8f
        speedIndicatorText.scaleY = 0.8f
        speedIndicatorText.visibility = View.VISIBLE

        speedIndicatorText.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun stopSpeedUpMode() {
        isOnLongPressSpeedUp = false
        player?.setPlaybackParameters(PlaybackParameters(1f))

        speedIndicatorText.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(200)
            .withEndAction {
                speedIndicatorText.visibility = View.GONE
                speedIndicatorText.alpha = 1f
                speedIndicatorText.scaleX = 1f
                speedIndicatorText.scaleY = 1f
            }
            .start()
    }

    private fun showUnlockButtonWithAnimation() {
        btnUnlock.alpha = 0f
        btnUnlock.scaleX = 0.5f
        btnUnlock.scaleY = 0.5f
        btnUnlock.visibility = View.VISIBLE

        btnUnlock.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                hideHandler.postDelayed({
                    hideUnlockButtonWithAnimation()
                }, 3000)
            }
            .start()
    }

    private fun updateSeekTimeDisplay(seekChange: Long, newPosition: Long) {
        val changeSeconds = seekChange / 1000
        val changeSign = if (seekChange >= 0) "+" else "-"
        val changeMinutesPart = abs(changeSeconds) / 60
        val changeSecondsPart = abs(changeSeconds) % 60

        val formattedChange = String.format("%s%02d:%02d", changeSign, changeMinutesPart, changeSecondsPart)
        val formattedPosition = formatTime(newPosition)

        tvSeekTime.text = "$formattedChange [$formattedPosition]"
    }

    private fun showSeekOverlay() {
        if (tvSeekTime.visibility != View.VISIBLE) {
            tvSeekTime.alpha = 0f
            tvSeekTime.visibility = View.VISIBLE
            tvSeekTime.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }
    }

    private fun hideSeekOverlay() {
        if (tvSeekTime.visibility == View.VISIBLE) {
            tvSeekTime.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    tvSeekTime.visibility = View.GONE
                    tvSeekTime.alpha = 1f
                }
                .start()
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
            Log.w("VideoPlayerActivity", "Could not get system brightness", e)
        }

        updateVolumeProgress()
        updateBrightnessProgress()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var wasPlaying = false

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val duration = player!!.duration
                    if (duration > 0) {
                        val newPosition = (progress * duration) / 100
                        player!!.seekTo(newPosition)
                        updateCurrentTime()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideHandler.removeCallbacks(hideRunnable)
                wasPlaying = player?.isPlaying == true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (wasPlaying) {
                    player?.play()
                }
                scheduleHideControls()
            }
        })
    }

    private fun toggleControlsWithAnimation() {
        if (isControlsVisible) {
            hideControlsWithAnimation()
        } else {
            showControlsWithAnimation()
        }
    }

    private fun showControlsWithAnimation() {
        if (isAnimating || isLocked) return
        isAnimating = true

        topOverlay.alpha = 0f
        centerControls.alpha = 0f
        bottomControls.alpha = 0f
        btnLock.alpha = 0f

        topOverlay.translationY = -topOverlay.height.toFloat()
        bottomControls.translationY = bottomControls.height.toFloat()
        centerControls.scaleX = 0.8f
        centerControls.scaleY = 0.8f

        topOverlay.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
        btnLock.visibility = View.VISIBLE

        val animatorSet = AnimatorSet()
        val topSlideIn = ObjectAnimator.ofFloat(topOverlay, "translationY", -topOverlay.height.toFloat(), 0f)
        val bottomSlideIn = ObjectAnimator.ofFloat(bottomControls, "translationY", bottomControls.height.toFloat(), 0f)
        val centerScaleX = ObjectAnimator.ofFloat(centerControls, "scaleX", 0.8f, 1f)
        val centerScaleY = ObjectAnimator.ofFloat(centerControls, "scaleY", 0.8f, 1f)

        val topFade = ObjectAnimator.ofFloat(topOverlay, "alpha", 0f, 1f)
        val centerFade = ObjectAnimator.ofFloat(centerControls, "alpha", 0f, 1f)
        val bottomFade = ObjectAnimator.ofFloat(bottomControls, "alpha", 0f, 1f)
        val lockFade = ObjectAnimator.ofFloat(btnLock, "alpha", 0f, 1f)

        animatorSet.playTogether(
            topSlideIn, bottomSlideIn, centerScaleX, centerScaleY,
            topFade, centerFade, bottomFade, lockFade
        )
        animatorSet.duration = ANIMATION_DURATION
        animatorSet.interpolator = FastOutSlowInInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isControlsVisible = true
                isAnimating = false
                btnPlayPause.requestFocus()
                scheduleHideControls()
            }
        })

        animatorSet.start()
    }

    private fun hideControlsWithAnimation() {
        if (isAnimating || isLocked) return
        isAnimating = true

        val animatorSet = AnimatorSet()
        val topSlideOut = ObjectAnimator.ofFloat(topOverlay, "translationY", 0f, -topOverlay.height.toFloat())
        val bottomSlideOut = ObjectAnimator.ofFloat(bottomControls, "translationY", 0f, bottomControls.height.toFloat())
        val centerScaleX = ObjectAnimator.ofFloat(centerControls, "scaleX", 1f, 0.8f)
        val centerScaleY = ObjectAnimator.ofFloat(centerControls, "scaleY", 1f, 0.8f)

        val topFade = ObjectAnimator.ofFloat(topOverlay, "alpha", 1f, 0f)
        val centerFade = ObjectAnimator.ofFloat(centerControls, "alpha", 1f, 0f)
        val bottomFade = ObjectAnimator.ofFloat(bottomControls, "alpha", 1f, 0f)
        val lockFade = ObjectAnimator.ofFloat(btnLock, "alpha", 1f, 0f)

        animatorSet.playTogether(
            topSlideOut, bottomSlideOut, centerScaleX, centerScaleY,
            topFade, centerFade, bottomFade, lockFade
        )
        animatorSet.duration = ANIMATION_DURATION
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                topOverlay.visibility = View.GONE
                centerControls.visibility = View.GONE
                bottomControls.visibility = View.GONE
                btnLock.visibility = View.GONE

                topOverlay.alpha = 1f
                centerControls.alpha = 1f
                bottomControls.alpha = 1f
                btnLock.alpha = 1f
                topOverlay.translationY = 0f
                bottomControls.translationY = 0f
                centerControls.scaleX = 1f
                centerControls.scaleY = 1f

                hideBrightnessOverlay()
                hideVolumeOverlay()
                hideSeekOverlay()

                playerView.clearFocus()
                isControlsVisible = false
                isAnimating = false
            }
        })

        animatorSet.start()
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, CONTROLS_AUTO_HIDE_DELAY)
    }

    private fun adjustBrightnessSmooth(deltaY: Float) {
        val sensitivity = 0.003f
        currentBrightness = (currentBrightness + (deltaY * sensitivity)).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = currentBrightness }
        updateBrightnessProgressSmooth()
        showBrightnessOverlaySmooth()
    }

    private fun adjustVolumeSmooth(deltaY: Float) {
        val sensitivity = 0.05f
        val change = deltaY * sensitivity * maxVolume
        currentVolume = (currentVolume + change.toInt()).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        updateVolumeProgressSmooth()
        showVolumeOverlaySmooth()
    }

    private fun updateBrightnessProgressSmooth() {
        val progress = (currentBrightness * 100).toInt()

        ValueAnimator.ofInt(brightnessProgress.progress, progress).apply {
            duration = 100
            addUpdateListener { animator ->
                brightnessProgress.progress = animator.animatedValue as Int
                tvBrightnessValue.text = "${animator.animatedValue}%"
            }
            start()
        }
    }

    private fun updateVolumeProgressSmooth() {
        val progress = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0

        ValueAnimator.ofInt(volumeProgress.progress, progress).apply {
            duration = 100
            addUpdateListener { animator ->
                volumeProgress.progress = animator.animatedValue as Int
                tvVolumeValue.text = "${animator.animatedValue}%"
            }
            start()
        }

        val newIcon = when {
            currentVolume == 0 -> R.drawable.ic_volume_off
            currentVolume < maxVolume / 2 -> R.drawable.ic_volume_down
            else -> R.drawable.ic_volume_up
        }

        if (ivVolumeIcon.drawable?.constantState != getDrawable(newIcon)?.constantState) {
            ivVolumeIcon.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(100)
                .withEndAction {
                    ivVolumeIcon.setImageResource(newIcon)
                    ivVolumeIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()
                }
                .start()
        }
    }

    private fun showBrightnessOverlaySmooth() {
        if (brightnessOverlay.visibility != View.VISIBLE) {
            brightnessOverlay.alpha = 0f
            brightnessOverlay.translationX = -50f
            brightnessOverlay.visibility = View.VISIBLE

            brightnessOverlay.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }

        hideHandler.removeCallbacks(hideBrightnessOverlayRunnable)
        hideHandler.postDelayed(hideBrightnessOverlayRunnable, OVERLAY_AUTO_HIDE_DELAY)
    }

    private fun showVolumeOverlaySmooth() {
        if (volumeOverlay.visibility != View.VISIBLE) {
            volumeOverlay.alpha = 0f
            volumeOverlay.translationX = 50f
            volumeOverlay.visibility = View.VISIBLE

            volumeOverlay.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }

        hideHandler.removeCallbacks(hideVolumeOverlayRunnable)
        hideHandler.postDelayed(hideVolumeOverlayRunnable, OVERLAY_AUTO_HIDE_DELAY)
    }

    private val hideBrightnessOverlayRunnable = Runnable {
        hideBrightnessOverlay()
    }

    private fun hideBrightnessOverlay() {
        if (brightnessOverlay.visibility == View.VISIBLE) {
            brightnessOverlay.animate()
                .alpha(0f)
                .translationX(-50f)
                .setDuration(FADE_ANIMATION_DURATION)
                .withEndAction {
                    brightnessOverlay.visibility = View.GONE
                    brightnessOverlay.alpha = 1f
                    brightnessOverlay.translationX = 0f
                }
                .start()
        }
    }

    private val hideVolumeOverlayRunnable = Runnable {
        hideVolumeOverlay()
    }

    private fun hideVolumeOverlay() {
        if (volumeOverlay.visibility == View.VISIBLE) {
            volumeOverlay.animate()
                .alpha(0f)
                .translationX(50f)
                .setDuration(FADE_ANIMATION_DURATION)
                .withEndAction {
                    volumeOverlay.visibility = View.GONE
                    volumeOverlay.alpha = 1f
                    volumeOverlay.translationX = 0f
                }
                .start()
        }
    }

    // Legacy functions for compatibility
    private fun updateBrightnessProgress() = updateBrightnessProgressSmooth()
    private fun updateVolumeProgress() = updateVolumeProgressSmooth()
    private fun adjustBrightness(deltaY: Float) = adjustBrightnessSmooth(deltaY)
    private fun adjustVolume(deltaY: Float) = adjustVolumeSmooth(deltaY)
    private fun showBrightnessOverlay() = showBrightnessOverlaySmooth()
    private fun showVolumeOverlay() = showVolumeOverlaySmooth()

    private fun showToastWithFade(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateProgress() {
        player?.let { p ->
            val duration = p.duration
            val position = p.currentPosition

            if (duration > 0) {
                val progress = ((position * 100) / duration).toInt()

                if (!isGestureInProgress && seekBar.progress != progress) {
                    val animator = ValueAnimator.ofInt(seekBar.progress, progress)
                    animator.duration = 500
                    animator.addUpdateListener { animation ->
                        seekBar.progress = animation.animatedValue as Int
                    }
                    animator.start()
                }
            }

            tvCurrentTime.text = formatTime(position)
            checkSkipButtonVisibility(position)
        }
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

    private fun updateResolutionDisplay() {
        player?.let { p ->
            val videoSize = p.videoSize
            if (videoSize.height > 0) {
                val currentQuality = "${videoSize.height}p"
                val selectedServer = tvServerName.text.toString().split(" ")[0]
                tvServerName.text = "$selectedServer (Auto - $currentQuality)"
            }
        }
    }

    private fun showLoadingWithAnimation() {
        if (loadingIndicator.visibility != View.VISIBLE) {
            loadingIndicator.alpha = 0f
            loadingIndicator.visibility = View.VISIBLE
            loadingIndicator.animate()
                .alpha(1f)
                .setDuration(FADE_ANIMATION_DURATION)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    private fun hideLoadingWithAnimation() {
        if (loadingIndicator.visibility == View.VISIBLE) {
            loadingIndicator.animate()
                .alpha(0f)
                .setDuration(FADE_ANIMATION_DURATION)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    loadingIndicator.visibility = View.GONE
                    loadingIndicator.alpha = 1f
                }
                .start()
        }
    }

    private fun updatePlayPauseButtonWithAnimation() {
        player?.let { p ->
            val newIcon = if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_large

            ivPlayPause.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(100)
                .withEndAction {
                    ivPlayPause.setImageResource(newIcon)
                    ivPlayPause.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }

    private fun updateResolutionDisplayWithAnimation() {
        player?.let { p ->
            val videoSize = p.videoSize
            if (videoSize.height > 0) {
                val currentQuality = "${videoSize.height}p"
                val selectedServer = tvServerName.text.toString().split(" ")[0]
                val newText = "$selectedServer (Auto - $currentQuality)"

                tvServerName.animate()
                    .alpha(0.5f)
                    .setDuration(100)
                    .withEndAction {
                        tvServerName.text = newText
                        tvServerName.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun rewindWithAnimation() {
        player?.let { p ->
            p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
            showSeekIndicatorWithAnimation(rewindIndicator)
            btnRewind.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun fastForwardWithAnimation() {
        player?.let { p ->
            p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
            showSeekIndicatorWithAnimation(forwardIndicator)
            btnFastForward.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun showSeekIndicatorWithAnimation(view: View) {
        view.alpha = 0f
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.visibility = View.VISIBLE

        val animatorSet = AnimatorSet()
        val alphaAnimator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        val scaleXAnimator = ObjectAnimator.ofFloat(view, "scaleX", 0.5f, 1.1f, 1f)
        val scaleYAnimator = ObjectAnimator.ofFloat(view, "scaleY", 0.5f, 1.1f, 1f)

        animatorSet.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator)
        animatorSet.duration = 200
        animatorSet.interpolator = FastOutSlowInInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                hideHandler.postDelayed({
                    view.animate()
                        .alpha(0f)
                        .scaleX(0.8f)
                        .scaleY(0.8f)
                        .setDuration(300)
                        .withEndAction {
                            view.visibility = View.GONE
                            view.alpha = 1f
                            view.scaleX = 1f
                            view.scaleY = 1f
                        }
                        .start()
                }, SEEK_INDICATOR_DURATION)
            }
        })

        animatorSet.start()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cycleResizeMode() {
        val (newMode, modeName) = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
            }
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit to Screen"
            }
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit to Screen"
        }

        currentResizeMode = newMode
        playerView.resizeMode = currentResizeMode
        showToastWithFade(modeName)
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
        cleanupResources()
    }

    override fun onPause() {
        super.onPause()
        saveWatchProgress()
        player?.pause()
        stopProgressUpdates()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        startProgressUpdates()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun cleanupResources() {
        stopProgressUpdates()
        player?.release()
        hideHandler.removeCallbacksAndMessages(null)
        progressUpdateHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Legacy functions for compatibility
    private fun showControls() = showControlsWithAnimation()
    private fun hideControls() = hideControlsWithAnimation()
    private fun rewind() = rewindWithAnimation()
    private fun fastForward() = fastForwardWithAnimation()
    private fun toggleLock() = toggleLockWithAnimation()
    private fun showSeekIndicator(view: View) = showSeekIndicatorWithAnimation(view)
    private fun showSeekTime(text: String) {
        tvSeekTime.text = text
        showSeekOverlay()
    }
    private val hideSeekTime = Runnable { hideSeekOverlay() }
}